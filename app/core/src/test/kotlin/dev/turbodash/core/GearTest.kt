package dev.turbodash.core

import dev.turbodash.core.ecu.GearEstimator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GearTest {

    @Test
    fun `rpm per km h picks the nearest gear within tolerance`() {
        val g = GearEstimator()
        assertEquals(3, g.match(2898.0, 60.0))  // 48.3 rpm per km/h
        assertEquals(5, g.match(2308.0, 80.0))  // 28.85
        assertEquals(2, g.match(2808.0, 40.0))  // 70.2
        assertEquals(0, g.match(1100.0, 60.0))  // clutch down / neutral at 60 km/h
        assertEquals(0, g.match(2000.0, 5.0))   // too slow for the GPS
        assertEquals(0, g.match(2000.0, null))  // no GPS
    }

    @Test
    fun `gear uses the rpm of 0,8 s ago and waits before changing`() {
        val g = GearEstimator()
        var t = 0.0
        fun run(seconds: Double, rpm: Double, kmh: Double): Int {
            var gear = 0
            val end = t + seconds
            while (t < end) { gear = g.update(t, rpm, kmh); t += 0.075 }
            return gear
        }
        assertEquals(3, run(3.0, 2898.0, 60.0))
        // Upshift: rpm drops at once, but the old rpm is still in the 0.8 s window + 0.4 s hold.
        assertEquals(3, run(0.5, 2184.0, 60.0))
        assertEquals(4, run(1.5, 2184.0, 60.0))
        // A single bad sample does not change the gear.
        assertEquals(4, g.update(t, 1100.0, 60.0))
        assertEquals(4, run(1.0, 2184.0, 60.0))
    }

    /** Runs over the real logs (when the project folder is there) and checks the hit rate. */
    @Test
    fun `real logs - foot on the throttle at steady rpm shows a gear`() {
        val dir = File("../../logs").takeIf { it.isDirectory } ?: return
        var n = 0
        var hit = 0
        var changes = 0
        for (f in dir.listFiles { x -> x.name.endsWith(".msl") }!!.sorted()) {
            val lines = f.readLines(Charsets.ISO_8859_1)
            val header = lines.getOrNull(2)?.split('\t') ?: continue
            val iT = header.indexOf("Time"); val iR = header.indexOf("RPM"); val iV = header.indexOf("GPS Speed"); val iP = header.indexOf("TPS")
            if (iT < 0 || iR < 0 || iV < 0 || iP < 0) continue
            val g = GearEstimator()
            var last = 0
            val rows = lines.drop(4).map { it.split('\t') }.filter { it.size > iV && it[iT].toDoubleOrNull() != null }
            rows.forEachIndexed { i, c ->
                val gear = g.update(c[iT].toDouble(), c[iR].toDouble(), c[iV].toDouble())
                if (gear != last) { changes++; last = gear }
                // Throttle open and rpm steady for the last ~2 s: clutch surely engaged.
                val past = rows.getOrNull(i - 26) ?: return@forEachIndexed
                val rpm = c[iR].toDouble()
                if (c[iP].toDouble() > 8 && c[iV].toDouble() >= 15 && kotlin.math.abs(rpm / past[iR].toDouble() - 1) < 0.05) {
                    n++
                    if (gear != 0) hit++
                }
            }
        }
        if (n == 0) return
        println("Marcha nos logs: ${"%.1f".format(100.0 * hit / n)}% de $n amostras com pé no acelerador; $changes mudanças no total")
        assertTrue(hit > n * 0.85)
    }
}
