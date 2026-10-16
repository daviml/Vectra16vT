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
) {
    interface Listener {
        fun onState(state: SessionState)
        fun onTune(tune: Tune, fromEcu: Boolean) {}
        fun onFrame(snapshot: Snapshot, alerts: List<Alert>)
    }

    val roles = ChannelRoles(ini)
    private val decoder = ChannelDecoder(ini, fallbackTune)
    private val alerts = AlertEngine(roles, alertConfig)

    var alertConfig: AlertConfig
        get() = alerts.config
        set(v) { alerts.config = v }

    @Volatile private var stopped = false
    @Volatile private var transport: Transport? = null
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

                if (readTuneFromEcu && !tuneLoaded) {
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
                pollLoop(client, sig)
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

    private fun pollLoop(client: EcuClient, sig: String) {
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
}
