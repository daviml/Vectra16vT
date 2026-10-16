package dev.turbodash.core

import dev.turbodash.core.sim.Scenario
import dev.turbodash.core.sim.SyntheticEngine
import kotlin.test.Test
import kotlin.test.assertTrue

class ScenariosTest {

    private fun boostShare(scenario: Scenario): Double {
        val engine = SyntheticEngine(Fixtures.vectraTune(), scenario)
        val samples = (0 until 600).map { engine.values(it * 0.1)["map"]!! } // first minute
        return samples.count { it > 110 }.toDouble() / samples.size
    }

    @Test
    fun `boost pulls scenario spends a good part of the time in boost`() {
        val share = boostShare(Scenario.BOOST_PULLS)
        assertTrue(share > 0.3, "boost ${"%.0f".format(share * 100)}% of the time")
    }

    @Test
    fun `city scenario never builds boost`() {
        assertTrue(boostShare(Scenario.CITY_HEAT) == 0.0)
    }
}
