package dev.turbodash.core.ecu

import dev.turbodash.core.alerts.Alert
import dev.turbodash.core.alerts.AlertConfig
import dev.turbodash.core.alerts.AlertEngine
import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.protocol.Transport
import dev.turbodash.core.tune.Tune

sealed class SessionState {
    data class Connecting(val attempt: Int) : SessionState()
    data class ReadingTune(val progress: Float) : SessionState()
    data class Running(val hz: Double, val ecuSignature: String) : SessionState()
    data class Failed(val message: String, val retrying: Boolean) : SessionState()
    data object Stopped : SessionState()
}

/**
 * Connection lifecycle: open transport → check signature → read the calibration → poll realtime
 * data, reconnecting after link failures. Runs on the caller's thread ([run] blocks until [stop]).
 */
class EcuSession(
    val ini: IniDefinition,
    private val openTransport: () -> Transport,
    /** Tune used for formulas until (or instead of) the one read from the ECU. */
    private val fallbackTune: Tune?,
    private val listener: Listener,
    alertConfig: AlertConfig = AlertConfig(),
    private val targetHz: Double = 20.0,
    private val readTuneFromEcu: Boolean = true,
    /** Record the crank trigger edges while the engine is being started (MS2/Extra composite logger). */
    private val crankCapture: Boolean = false,
) {
    interface Listener {
        fun onState(state: SessionState)
        fun onTune(tune: Tune, fromEcu: Boolean) {}
        fun onFrame(snapshot: Snapshot, alerts: List<Alert>)
        /** A composite logger buffer read during cranking ([crankCapture]). */
        fun onToothBlock(block: ToothBlock) {}
    }

    val roles = ChannelRoles(ini)
    private val decoder = ChannelDecoder(ini, fallbackTune)
    private val alerts = AlertEngine(roles, alertConfig)

    var alertConfig: AlertConfig
        get() = alerts.config
        set(v) { alerts.config = v }

    @Volatile private var stopped = false
    @Volatile private var transport: Transport? = null
    private var client: EcuClient? = null
    private val capture = CrankCapture(
        readBuffer = { client!!.readLoggerBuffer(CrankCapture.TABLE) },
        onBlock = { listener.onToothBlock(it) },
        // A late answer would be taken for the next realtime block: wait for it and throw it away.
        onFailure = { client?.resync() },
    )
    private val rpmChannel = roles.channel(Role.RPM)
    private val t0 = System.nanoTime()

    fun stop() {
        stopped = true
        try { transport?.close() } catch (_: Exception) {}
    }

    fun run() {
        var attempt = 0
        var tuneLoaded = false
        while (!stopped) {
            attempt++
            listener.onState(SessionState.Connecting(attempt))
            try {
                val t = openTransport()
                transport = t
                if (stopped) { t.close(); break }
                val client = EcuClient(ini, t)
                this.client = client

                val sig = client.querySignature()
                if (!client.signatureMatches(sig)) {
                    listener.onState(
                        SessionState.Failed(
                            "A ECU respondeu \"${sig.trim()}\", mas o INI é para \"${ini.signature.trim()}\". " +
                                "Importe o INI do firmware que está na ECU.",
                            retrying = false,
                        )
                    )
                    t.close()
                    stopped = true
                    break
                }

                val capturing = crankCapture && CrankCapture.supported(sig) && !capture.disabled
                capture.reset()
                var pendingTune: TuneReader? = null
                if (readTuneFromEcu && !tuneLoaded && capturing && fallbackTune != null && !engineRunning(client)) {
                    // Engine not started yet: reading the 7 pages first would take the seconds in which
                    // it is cranked. Poll right away with the msq tune and fetch the pages in between.
                    listener.onTune(fallbackTune, fromEcu = false)
                    pendingTune = TuneReader(client)
                } else if (readTuneFromEcu && !tuneLoaded) {
                    val total = ini.pageSizes.sum().coerceAtLeast(1)
                    var done = 0
                    val pages = ini.pageSizes.indices.map { p ->
                        client.readPage(p) { off ->
                            listener.onState(SessionState.ReadingTune((done + off).toFloat() / total))
                        }.also { done += it.size }
                    }
                    val tune = Tune(ini, pages)
                    fallbackTune?.pcValues?.let { tune.pcValues.putAll(it) }
                    decoder.tune = tune
                    tuneLoaded = true
                    listener.onTune(tune, fromEcu = true)
                } else if (!tuneLoaded && fallbackTune != null) {
                    listener.onTune(fallbackTune, fromEcu = false)
                    tuneLoaded = true
                }

                attempt = 0
                pollLoop(client, sig, capturing, pendingTune) { tuneLoaded = true }
            } catch (e: Exception) {
                if (stopped) break
                listener.onState(SessionState.Failed(e.message ?: e.javaClass.simpleName, retrying = true))
                try { transport?.close() } catch (_: Exception) {}
                // Retry wait in short steps, so stop() ends the session right away.
                val until = System.nanoTime() + minOf(5000L, 1000L * attempt) * 1_000_000
                while (!stopped && System.nanoTime() < until) sleep(100)
            }
        }
        try { transport?.close() } catch (_: Exception) {}
        listener.onState(SessionState.Stopped)
    }

    private fun engineRunning(client: EcuClient): Boolean {
        val snap = decoder.decode(client.readOutputChannels(), (System.nanoTime() - t0) / 1e9)
        return (rpmChannel?.let { snap[it] } ?: 0.0) >= CrankCapture.RUN_RPM
    }

    /** Reads the calibration one block per call, so it can be spread between realtime frames. */
    private inner class TuneReader(private val client: EcuClient) {
        private val pages = ini.pageSizes.map { ByteArray(it) }
        private var page = 0
        private var offset = 0

        /** Returns the tune once the last block is in, null before that. */
        fun step(): Tune? {
            while (page < pages.size && offset >= pages[page].size) { page++; offset = 0 }
            if (page < pages.size) {
                val n = minOf(client.pageBlockSize, pages[page].size - offset)
                client.readPageBlock(page, offset, n).copyInto(pages[page], offset)
                offset += n
                if (offset < pages[page].size || page < pages.size - 1) return null
            }
            return Tune(ini, pages)
        }
    }

    private fun pollLoop(client: EcuClient, sig: String, capturing: Boolean, tuneReader: TuneReader?, onTuneLoaded: () -> Unit) {
        var pendingTune = tuneReader
        var runningSince: Double? = null
        val periodNs = (1e9 / targetHz).toLong()
        var frames = 0
        var windowStart = System.nanoTime()
        var hz = 0.0
        while (!stopped) {
            val start = System.nanoTime()
            val block = client.readOutputChannels()
            val snap = decoder.decode(block, (start - t0) / 1e9)
            listener.onFrame(snap, alerts.evaluate(snap))
            frames++
            val rpm = rpmChannel?.let { snap[it] } ?: 0.0
            if (rpm >= CrankCapture.RUN_RPM) { if (runningSince == null) runningSince = snap.time } else runningSince = null
            if (capturing) capture.update(snap.time, rpm, (snap["status3"] ?: 0.0).toInt())
            // Calibration left for later: only with the engine stopped or already running for a while,
            // never during cranking.
            val settled = rpm == 0.0 || runningSince?.let { snap.time - it > TUNE_AFTER_START_S } == true
            val reader = pendingTune
            if (reader != null && settled) {
                val tune = reader.step()
                if (tune != null) {
                    fallbackTune?.pcValues?.let { tune.pcValues.putAll(it) }
                    decoder.tune = tune
                    pendingTune = null
                    onTuneLoaded()
                    listener.onTune(tune, fromEcu = true)
                }
            }
            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000L) {
                hz = frames * 1e9 / (now - windowStart)
                frames = 0
                windowStart = now
                listener.onState(SessionState.Running(hz, sig))
            } else if (hz == 0.0 && frames == 1) {
                listener.onState(SessionState.Running(0.0, sig))
            }
            val wait = periodNs - (System.nanoTime() - start)
            if (wait > 0) sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }

    private fun sleep(ms: Long, nanos: Int = 0) {
        try { Thread.sleep(ms, nanos) } catch (_: InterruptedException) { stopped = true }
    }

    /** Decoder in use (the app needs it for log units). */
    fun channelDecoder(): ChannelDecoder = decoder

    private companion object {
        const val TUNE_AFTER_START_S = 3.0
    }
}
