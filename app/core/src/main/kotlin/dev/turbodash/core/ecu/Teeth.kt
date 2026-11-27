package dev.turbodash.core.ecu

/** What the "DENTES" badge shows about the crank trigger capture ([CrankCapture]). */
enum class TeethBadge {
    /** The logger is armed and waiting for (or recording) a start. */
    ARMED,
    /** The buffer of the moment the engine fired was read: this start is in the file. */
    CAPTURED,
    /** The last read failed (the capture tries again by itself). */
    FAILED,
}

object Teeth {
    /** How long the "captured" confirmation stays up once the engine runs. */
    const val CAPTURED_S = 3.0
    /** How long a failure stays up once the engine runs (it is always shown with the engine stopped). */
    const val FAILED_RUNNING_S = 5.0

    /**
     * Badge for the last capture read: its [kind] and the rpm at that read, [ageS] seconds ago, with
     * the engine now at [rpmNow]. Null = nothing to show (no capture on this connection, or the engine
     * has been running for a while: the badge must not distract while driving).
     */
    fun badge(kind: String?, rpmAtRead: Double, ageS: Double, rpmNow: Double?): TeethBadge? {
        kind ?: return null
        val runningNow = (rpmNow ?: 0.0) >= CrankCapture.RUN_RPM
        return when {
            kind == ToothBlock.KIND_FAIL -> if (!runningNow || ageS < FAILED_RUNNING_S) TeethBadge.FAILED else null
            rpmAtRead >= CrankCapture.RUN_RPM -> if (ageS < CAPTURED_S) TeethBadge.CAPTURED else null
            // Armed: up while the engine is stopped or cranking, and in the short wait for the read
            // taken once it fires.
            !runningNow || ageS < FAILED_RUNNING_S -> TeethBadge.ARMED
            else -> null
        }
    }
}
