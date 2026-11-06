package dev.turbodash.core.ecu

import kotlin.math.sqrt

/**
 * Fuel use from the injectors, like a factory trip computer: open fraction (duty cycle) without
 * the dead time, flow corrected for the measured fuel pressure, times a factor fitted to real
 * fill-ups. The rate is smoothed over about [smoothing] seconds; the trip average runs from the
 * last [reset] (each connection).
 */
class FuelEconomy(
    private val injectors: Int = 4,
    /** Bosch 42 lb green at [ratedBar]. */
    private val ccPerMin: Double = 441.0,
    private val ratedBar: Double = 3.0,
    private val deadTimeMs: Double = 0.479,
    private val deadTimePerVolt: Double = 0.15,
    /**
     * Real ÷ computed fuel: 20.05 L filled from 29/10 to 03/11/2026 against 15.0 L from the
     * injectors in the 26 logs of that period (ratio 1.25–1.42 log by log against the air estimate).
     */
    private val calibration: Double = 1.34,
    private val smoothing: Double = 1.0,
    /** Below this speed the car counts as stopped and the rate is shown in l/h. */
    private val movingKmh: Double = 5.0,
) {
    /**
     * [kmPerLiter] is null when stopped or in fuel cut; [cutoff] = engine turning with the
     * injectors closed (foot off in gear); [tripKmPerLiter] is null until there is enough data.
     */
    data class Reading(val litersPerHour: Double, val kmPerLiter: Double?, val cutoff: Boolean, val tripKmPerLiter: Double?)

    private var last = Double.NaN
    private var lph = 0.0
    private var kmh = 0.0
    private var tripLiters = 0.0
    private var tripKm = 0.0

    /** Instant flow in cm³/s; 0 with the engine stopped or the injectors closed. */
    fun ccPerSecond(rpm: Double?, pwMs: Double?, dutyPct: Double?, battery: Double?, fuelBar: Double?, mapKpa: Double?): Double {
        if (rpm == null || rpm < 300 || pwMs == null || dutyPct == null || pwMs <= 0.0) return 0.0
        val dead = deadTimeMs + deadTimePerVolt * (14.0 - (battery ?: 14.0))
        val open = (pwMs - dead).coerceAtLeast(0.0) / pwMs
        // Gauge fuel pressure plus intake vacuum = pressure across the injector.
        val across = if (fuelBar == null || mapKpa == null) ratedBar else (fuelBar + (100.0 - mapKpa) / 100.0).coerceAtLeast(0.5)
        return injectors * ccPerMin / 60.0 * dutyPct / 100.0 * open * sqrt(across / ratedBar) * calibration
    }

    fun update(
        time: Double, rpm: Double?, pwMs: Double?, dutyPct: Double?, battery: Double?, fuelBar: Double?, mapKpa: Double?, speedKmh: Double?,
    ): Reading {
        val dt = time - last
        if (last.isNaN() || dt < 0 || dt > 2) { // first frame or new session
            if (!last.isNaN() && dt < 0) reset()
            last = time
            return reading(rpm, pwMs)
        }
        last = time
        val cc = ccPerSecond(rpm, pwMs, dutyPct, battery, fuelBar, mapKpa)
        val a = dt / (smoothing + dt)
        lph += (cc * 3.6 - lph) * a
        kmh += ((speedKmh ?: 0.0) - kmh) * a
        tripLiters += cc * dt / 1000.0
        tripKm += (speedKmh ?: 0.0) * dt / 3600.0
        return reading(rpm, pwMs)
    }

    private fun reading(rpm: Double?, pwMs: Double?): Reading {
        val cutoff = rpm != null && rpm >= 300 && (pwMs == null || pwMs <= 0.0)
        val kml = if (cutoff || kmh < movingKmh || lph < 0.05) null else (kmh / lph).coerceAtMost(99.9)
        val trip = if (tripKm >= 0.3 && tripLiters >= 0.03) (tripKm / tripLiters).coerceAtMost(99.9) else null
        return Reading(lph, kml, cutoff, trip)
    }

    fun reset() {
        last = Double.NaN
        lph = 0.0
        kmh = 0.0
        tripLiters = 0.0
        tripKm = 0.0
    }
}
