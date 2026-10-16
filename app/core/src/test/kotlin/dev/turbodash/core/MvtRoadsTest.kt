package dev.turbodash.core

import dev.turbodash.core.map.MvtRoads
import kotlin.test.Test
import kotlin.test.assertTrue

class MvtRoadsTest {

    @Test
    fun `decodes streets of a real OpenFreeMap tile`() {
        // Central São Paulo, zoom 14.
        val bytes = javaClass.getResourceAsStream("/sp-14-6068-9295.pbf")!!.readBytes()
        val roads = MvtRoads.decode(bytes)
        val byClass = roads.groupingBy { it.cls }.eachCount()
        println("roads=${roads.size} byClass=$byClass points=${roads.sumOf { it.xs.size }}")
        assertTrue(roads.size > 500)
        assertTrue((0 until MvtRoads.CLASSES).all { (byClass[it] ?: 0) > 0 })
        // Coordinates are tile fractions (a small buffer beyond the edges is allowed).
        assertTrue(roads.all { r -> r.xs.all { it in -0.2f..1.2f } && r.ys.all { it in -0.2f..1.2f } })
    }
}
