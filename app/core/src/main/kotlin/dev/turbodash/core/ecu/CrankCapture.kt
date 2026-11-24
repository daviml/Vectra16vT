package dev.turbodash.core.ecu

/** One buffer of the ECU's composite logger, as read (undecoded), with the engine state at that moment. */
class ToothBlock(
    /** Session time of the read, seconds (same clock as [Snapshot.time]). */
    val time: Double,
    /** [KIND_FULL] = the ECU flagged the buffer as complete; [KIND_PARTIAL] = read on a timeout, may hold old records. */
    val kind: String,
    val rpm: Double,
    val status3: Int,
    val data: ByteArray,
) {
    companion object {
        const val KIND_FULL = "full"
        const val KIND_PARTIAL = "partial"
    }
}

/**
 * Records the crank trigger edges while the engine is being started, to find out why sync is lost
 * during slow cranking.
 *
 * Uses the MS2/Extra composite logger (page 0xf2) the way TunerStudio's standard definition does:
 * reading the buffer starts the logger, the ECU sets bit 1 of `status3` when the 341 records are in,
 * and the next read fetches them and restarts it. Between reads the normal realtime polling goes on,
 * so the regular log only loses the ~0.1 s each buffer takes to arrive.
 *
 * Armed while the engine is not running (stopped or cranking); disarmed after the buffer that holds
 * the moment it fired (or [RUN_HOLD_S] later), and armed again if it stalls. Read failures never reach the session: after [MAX_FAILURES] the
 * capture switches itself off until the app is restarted.
 */
class CrankCapture(
    private val readBuffer: () -> ByteArray,
    private val onBlock: (ToothBlock) -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    var failures = 0
        private set
    val disabled get() = failures >= MAX_FAILURES

    private var armed = false
    private var lastRead = Double.NEGATIVE_INFINITY
    private var runningSince: Double? = null
    private var blocks = 0

    /** New connection: the ECU may have been restarted, so the logger has to be armed again. */
    fun reset() {
        armed = false
        runningSince = null
        lastRead = Double.NEGATIVE_INFINITY
        blocks = 0
    }

    /** Call once per realtime frame. Returns true when a logger buffer was read on this call. */
    fun update(time: Double, rpm: Double, status3: Int): Boolean {
        if (disabled) return false
        val running = rpm >= RUN_RPM
        if (running) { if (runningSince == null) runningSince = time } else runningSince = null
        val since = runningSince

        if (!armed) {
            if (running) return false
            // Engine stopped or cranking: start the logger. What comes back is whatever was in the buffer.
            if (!read(time, rpm, status3, emitAs = null)) return false
            armed = true
            blocks = 0
            return true
        }
        if (since != null && time - since > RUN_HOLD_S) {
            armed = false // running and the ECU never flagged a full buffer
            return false
        }
        if (blocks >= MAX_BLOCKS || time - lastRead < MIN_GAP_S) return false
        val ready = status3 and READY_BIT != 0
        if (running) {
            // One last buffer, the one that holds the moment the engine fired; at running speed the
            // logger would fill three buffers a second.
            if (!ready) return false
            armed = false
            return read(time, rpm, status3, ToothBlock.KIND_FULL)
        }
        return when {
            // rpm > 0: with the engine stopped a ready flag is left over from before, there are no new edges.
            ready && rpm > 0 -> read(time, rpm, status3, ToothBlock.KIND_FULL)
            rpm > 0 && !running && time - lastRead > PARTIAL_AFTER_S -> read(time, rpm, status3, ToothBlock.KIND_PARTIAL)
            else -> false
        }
    }

    private fun read(time: Double, rpm: Double, status3: Int, emitAs: String?): Boolean {
        lastRead = time
        val data = try {
            readBuffer()
        } catch (e: Exception) {
            failures++
            armed = false
            onFailure(e)
            return false
        }
        if (emitAs != null) {
            blocks++
            onBlock(ToothBlock(time, emitAs, rpm, status3, data))
        }
        return true
    }

    companion object {
        /** Composite logger: edge levels, sync flag and time between edges. */
        const val TABLE = 0xf2
        const val READY_BIT = 0x02
        /** Same threshold the log analysis uses for "engine running". */
        const val RUN_RPM = 450.0
        const val RUN_HOLD_S = 2.5
        const val MIN_GAP_S = 0.4
        /** Cranking too slowly to fill a buffer (or a firmware that never raises the flag): read anyway. */
        const val PARTIAL_AFTER_S = 5.0
        /** A 60-2 wheel at 150 rpm fills one buffer every ~2.4 s: 40 buffers is over 90 s of cranking. */
        const val MAX_BLOCKS = 40
        const val MAX_FAILURES = 2

        /** TunerStudio offers these loggers for the same signatures (its built-in stdMS2 definitions). */
        fun supported(signature: String): Boolean = signature.trim().startsWith("MS2Extra")
    }
}
