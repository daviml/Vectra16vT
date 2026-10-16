package dev.turbodash.app

import android.content.Context

/**
 * Time-limited demo builds (BuildConfig.TRIAL_DAYS > 0). The trial runs [BuildConfig.TRIAL_DAYS]
 * from the first launch, but never past twice that from the build date, so reinstalling does not
 * restart it. The clock is not trusted to go backwards: the latest time seen is remembered.
 */
object Trial {
    private const val DAY = 86_400_000L

    val enabled get() = BuildConfig.TRIAL_DAYS > 0

    /** When the demo stops working (ms), or null for a normal build. */
    fun expiresAt(context: Context): Long? {
        if (!enabled) return null
        val prefs = context.getSharedPreferences("trial", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val firstRun = prefs.getLong("firstRun", 0L).takeIf { it > 0 } ?: now.also { prefs.edit().putLong("firstRun", it).apply() }
        val days = BuildConfig.TRIAL_DAYS * DAY
        return minOf(firstRun + days, BuildConfig.BUILD_TIME + 2 * days)
    }

    /** "Now" that never goes back in time (setting the phone clock back does not extend the demo). */
    private fun now(context: Context): Long {
        val prefs = context.getSharedPreferences("trial", Context.MODE_PRIVATE)
        val now = maxOf(System.currentTimeMillis(), prefs.getLong("lastSeen", 0L), BuildConfig.BUILD_TIME)
        prefs.edit().putLong("lastSeen", now).apply()
        return now
    }

    fun expired(context: Context): Boolean = expiresAt(context)?.let { now(context) >= it } ?: false

    /** Whole days left (rounded up), for the notice in Ajustes. */
    fun daysLeft(context: Context): Int? {
        val end = expiresAt(context) ?: return null
        return ((end - now(context)).coerceAtLeast(0) + DAY - 1).div(DAY).toInt()
    }
}
