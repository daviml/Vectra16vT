package dev.turbodash.core

import dev.turbodash.core.ecu.FuelEconomy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelTest {
    private fun run(fuel: FuelEconomy, seconds: Double, rpm: Double, pw: Double, duty: Double, kmh: Double, start: Double = 0.0): FuelEconomy.Reading {
        var r = fuel.update(start, rpm, pw, duty, 14.0, 2.7, 35.0, kmh)
        var t = start
        while (t < start + seconds) {
            t += 1.0 / 14
            r = fuel.update(t, rpm, pw, duty, 14.0, 2.7, 35.0, kmh)
        }
        return r
    }

    @Test
    fun idleShowsLitersPerHourOnly() {
        // Warm idle on the real car (logs of 01–05/11/2026): 1035 rpm, 2.04 ms, 1.75% duty.
        val r = run(FuelEconomy(), 10.0, 1035.0, 2.04, 1.75, 0.0)
        assertNull(r.kmPerLiter)
        assertTrue(r.litersPerHour in 1.5..2.5, "idle ${r.litersPerHour} l/h")
    }

    @Test
    fun cruiseGivesKmPerLiter() {
        val r = run(FuelEconomy(), 20.0, 2069.0, 2.41, 4.09, 58.0)
        val kml = assertNotNull(r.kmPerLiter)
        assertTrue(kml in 4.0..15.0, "cruise $kml km/l")
        assertEquals(false, r.cutoff)
    }

    @Test
    fun fuelCutIsFlaggedAndStopsCounting() {
        val fuel = FuelEconomy()
        run(fuel, 20.0, 2069.0, 2.41, 4.09, 58.0)
        val r = run(fuel, 5.0, 2000.0, 0.0, 0.0, 55.0, start = 20.1)
        assertTrue(r.cutoff)
        assertNull(r.kmPerLiter)
        assertTrue(r.litersPerHour < 0.1)
    }

    @Test
    fun tripAverageAndReset() {
        val fuel = FuelEconomy()
        val r = run(fuel, 120.0, 2069.0, 2.41, 4.09, 58.0)
        val trip = assertNotNull(r.tripKmPerLiter)
        assertEquals(trip, r.kmPerLiter!!, trip * 0.05)
        // Time going back = new session: the trip starts over.
        assertNull(fuel.update(0.0, 900.0, 2.6, 2.0, 14.0, 2.7, 30.0, 0.0).tripKmPerLiter)
    }
}
