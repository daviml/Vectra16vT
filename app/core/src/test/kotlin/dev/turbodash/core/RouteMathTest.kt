package dev.turbodash.core

import dev.turbodash.core.map.RoutePath
import dev.turbodash.core.map.formatDistance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteMathTest {

    // An L-shaped route in São Paulo: ~1 km east, then ~1 km north.
    private val dLon = 1000 / (111_320.0 * Math.cos(Math.toRadians(-23.56)))
    private val dLat = 1000 / 110_540.0
    private val route = RoutePath(
        doubleArrayOf(-23.56, -23.56, -23.56 + dLat),
        doubleArrayOf(-46.65, -46.65 + dLon, -46.65 + dLon),
    )

    @Test
    fun `remaining distance follows the route`() {
        assertEquals(2000.0, route.length, 1.0)
        val mid = route.locate(-23.56, -46.65 + dLon / 2)
        assertEquals(1500.0, mid.remainingM, 2.0)
        assertTrue(mid.offRouteM < 1.0)
    }

    @Test
    fun `off route distance is measured to the closest street`() {
        // 60 m south of the first leg.
        val fix = route.locate(-23.56 - 60 / 110_540.0, -46.65 + dLon / 2)
        assertEquals(60.0, fix.offRouteM, 1.0)
        assertEquals(0, fix.segment)
    }

    @Test
    fun `distance text`() {
        assertEquals("850 m", formatDistance(847.0))
        assertEquals("3,2 km", formatDistance(3240.0))
        assertEquals("12 km", formatDistance(12_400.0))
    }
}
