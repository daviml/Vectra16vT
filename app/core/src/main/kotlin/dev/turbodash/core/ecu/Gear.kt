package dev.turbodash.core.ecu

/**
 * Engaged gear from engine rpm ÷ phone GPS speed. With the clutch engaged each gear has a fixed
 * rpm per km/h; anything that matches none (clutch down, neutral, wheel spin) shows no gear.
 *
 * GPS speed arrives once a second and about 0.8 s late, so it is compared with the rpm of 0.8 s
 * ago (best fit on 97 real logs). A new gear must hold for [hold] seconds before it is shown, so
 * a shift or a GPS glitch does not make the number flicker.
 */
class GearEstimator(
    /** rpm per km/h of each gear, 1st first. */
    private val rpmPerKmh: List<Double> = VECTRA_F18CR,
    private val gpsLag: Double = 0.8,
    /** How far (fraction) rpm/speed may be from a gear and still count as that gear. */
    private val tolerance: Double = 0.07,
    /** Below this GPS speed is too noisy (and the car may be moving with the clutch down). */
    private val minKmh: Double = 8.0,
    private val hold: Double = 0.4,
) {
    private val times = ArrayDeque<Double>()
    private val rpms = ArrayDeque<Double>()
    private var shown = 0
    private var candidate = 0
    private var candidateSince = 0.0

    /** Gear (1 = 1st) for the frame at [time] seconds, or 0 when no gear matches. */
    fun update(time: Double, rpm: Double?, kmh: Double?): Int {
        if (times.isNotEmpty() && time < times.last()) reset() // new session
        if (rpm != null) {
            times.addLast(time)
            rpms.addLast(rpm)
        }
        // Keep the newest sample at or before time - lag (and nothing older).
        while (times.size > 1 && times[1] <= time - gpsLag) {
            times.removeFirst()
            rpms.removeFirst()
        }
        val lagged = if (times.isNotEmpty() && times.first() <= time - gpsLag) rpms.first() else null
        val now = match(lagged, kmh)
        if (now != candidate) {
            candidate = now
            candidateSince = time
        }
        if (candidate != shown && time - candidateSince >= hold) shown = candidate
        return shown
    }

    /** Gear whose rpm/speed is closest to this one, if within [tolerance]. */
    fun match(rpm: Double?, kmh: Double?): Int {
        if (rpm == null || kmh == null || kmh < minKmh || rpm < 500) return 0
        val ratio = rpm / kmh
        val best = rpmPerKmh.indices.minBy { kotlin.math.abs(ratio / rpmPerKmh[it] - 1) }
        return if (kotlin.math.abs(ratio / rpmPerKmh[best] - 1) <= tolerance) best + 1 else 0
    }

    fun reset() {
        times.clear()
        rpms.clear()
        shown = 0
        candidate = 0
    }

    companion object {
        /**
         * Vectra 2.0 16v, F18 CR gearbox, 215/45 R17: averages from the logs (foot on the throttle,
         * steady rpm), as the GPS sees them. Spread inside a gear 2-5%, gaps between gears >= 26%.
         */
        val VECTRA_F18CR = listOf(109.7, 70.2, 48.3, 36.4, 28.85)
    }
}
