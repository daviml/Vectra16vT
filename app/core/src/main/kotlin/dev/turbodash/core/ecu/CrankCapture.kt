package dev.turbodash.core.ecu

/**
 * One read of the ECU's composite logger, as it came (undecoded), with the engine state at that moment.
 * A failed read is recorded too ([KIND_FAIL], no data, the reason in [note]).
 */
class ToothBlock(
    /** Session time of the read, seconds (same clock as [Snapshot.time]). */
    val time: Double,
    val kind: String,
    val rpm: Double,
    val status3: Int,
    val data: ByteArray,
    val note: String = "",
) {
    /** True for the reads that can hold edges of this start (not the arming read, not a failure). */
    val hasEdges get() = kind != KIND_ARM && kind != KIND_FAIL

    companion object {
        /** First read of a connection or after a stall: arms the logger; usually zeros or an old recording. */
        const val KIND_ARM = "arm"
        /** The ECU flagged the buffer as complete. */
        const val KIND_FULL = "full"
        /** Read on time while cranking: the edges since the previous read, zeros after the last one. */
        const val KIND_PARTIAL = "partial"
        /** Read as soon as the engine ran: holds the moment it fired. */
        const val KIND_FIRED = "fired"
        const val KIND_FAIL = "fail"
    }
}

/**
 * Records the crank trigger edges while the engine is being started, to find out why sync is lost
 * during slow cranking.
 *
 * Uses the MS2/Extra composite logger (page 0xf2), read in blocks of 256 bytes by
 * [EcuClient.readLoggerBuffer]: the last block of each read arms the logger with an empty buffer,
 * so every read returns the edges since the previous one (records not reached are zeros).
 * Until 0.5.4 the buffer was asked for in one 1024-byte request, which the firmware refuses (0x84),
 * so the logger was never armed and the "buffer complete" bit of `status3` never showed up.
 * Reads go by time: one every [CRANK_READ_S] of cranking and one as soon as the engine runs; the
 * bit only brings a read forward. Between reads the normal realtime polling goes on.
 *
 * Armed while the engine is not running (stopped or cranking), disarmed by the read taken when it
 * fires, armed again if it stalls. A failed read never reaches the session and never switches the
 * capture off: it is recorded and tried again after a wait that grows with consecutive failures.
 */
class CrankCapture(
    private val readBuffer: () -> ByteArray,
    private val onBlock: (ToothBlock) -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    /** Failed reads since the app started (shown nowhere; tests and debugging). */
    var failures = 0
        private set

    private var armed = false
    private var lastRead = Double.NEGATIVE_INFINITY
    private var retryAt = Double.NEGATIVE_INFINITY
    private var consecutiveFailures = 0
    private var runningSince: Double? = null
    private var crankingSince: Double? = null
    private var blocks = 0

    /** New connection: the ECU may have been restarted, so the logger has to be armed again. */
    fun reset() {
        armed = false
        runningSince = null
        crankingSince = null
        lastRead = Double.NEGATIVE_INFINITY
        retryAt = Double.NEGATIVE_INFINITY
        consecutiveFailures = 0
        blocks = 0
    }

    /** True while the ECU logger is believed to be recording for this capture. */
    val isArmed get() = armed

    /**
     * The ECU logger was switched off behind the capture's back (a calibration page was read: the
     * firmware copies it into the same RAM). The next [update] with the engine not running arms it again.
     */
    fun disarm() {
        armed = false
    }

    /** Call once per realtime frame. Returns true when a logger buffer was read on this call. */
    fun update(time: Double, rpm: Double, status3: Int): Boolean {
        val running = rpm >= RUN_RPM
        val cranking = rpm > 0 && !running
        if (running) { if (runningSince == null) runningSince = time } else runningSince = null
        if (cranking) { if (crankingSince == null) crankingSince = time } else crankingSince = null
        if (time < retryAt) return false

        if (!armed) {
            if (running) return false
            // Engine stopped or cranking: start the logger. What comes back is whatever was in the buffer.
            if (!read(time, rpm, status3, ToothBlock.KIND_ARM)) return false
            armed = true
            blocks = 0
            return true
        }
        if (blocks >= MAX_BLOCKS) {
            if (running) armed = false
            return false
        }
        if (time - lastRead < MIN_GAP_S) return false
        val ready = status3 and READY_BIT != 0
        if (running) {
            // One last buffer, the one that holds the moment the engine fired (tried again if it fails).
            val ok = read(time, rpm, status3, if (ready) ToothBlock.KIND_FULL else ToothBlock.KIND_FIRED)
            if (ok) armed = false
            return ok
        }
        val since = crankingSince ?: return false // stopped: no new edges, a ready flag is a leftover
        return when {
            ready -> read(time, rpm, status3, ToothBlock.KIND_FULL)
            time - maxOf(lastRead, since) >= CRANK_READ_S -> read(time, rpm, status3, ToothBlock.KIND_PARTIAL)
            else -> false
        }
    }

    private fun read(time: Double, rpm: Double, status3: Int, kind: String): Boolean {
        lastRead = time
        val data = try {
            readBuffer()
        } catch (e: Exception) {
            failures++
            consecutiveFailures++
            // A pass that failed after the first block has already stopped the logger, and the firmware
            // keeps its buffer: the next read (after the wait) still returns those edges and arms it again,
            // so it stays a partial/fired read. Seen on the car on 2026-11-26: error 0x81 while cranking at
            // 11.2 V, the next read held the 75 records of the cranking. Only a failed arming read re-arms.
            if (kind == ToothBlock.KIND_ARM) armed = false
            retryAt = time + minOf(RETRY_S * consecutiveFailures, RETRY_MAX_S)
            onBlock(ToothBlock(time, ToothBlock.KIND_FAIL, rpm, status3, ByteArray(0), "$kind: ${e.javaClass.simpleName}: ${e.message ?: ""}"))
            onFailure(e)
            return false
        }
        consecutiveFailures = 0
        if (kind != ToothBlock.KIND_ARM) blocks++
        onBlock(ToothBlock(time, kind, rpm, status3, data))
        return true
    }

    companion object {
        /** Composite logger: edge levels, sync flag and time between edges. */
        const val TABLE = 0xf2
        const val READY_BIT = 0x02
        /** Same threshold the log analysis uses for "engine running". */
        const val RUN_RPM = 450.0
        const val MIN_GAP_S = 0.4
        /**
         * A 60-2 wheel at 150 rpm gives 145 teeth a second: the 341 records hold 1.2 s (both edges)
         * to 2.4 s (one edge) of cranking, so a read every 1.2 s loses nothing either way.
         */
        const val CRANK_READ_S = 1.2
        /** 60 reads = over 70 s of cranking. */
        const val MAX_BLOCKS = 60
        /** Wait after a failed read, times the number of failures in a row, up to [RETRY_MAX_S]. */
        const val RETRY_S = 1.0
        const val RETRY_MAX_S = 30.0

        /** TunerStudio offers these loggers for the same signatures (its built-in stdMS2 definitions). */
        fun supported(signature: String): Boolean = signature.trim().startsWith("MS2Extra")
    }
}
