package dev.turbodash.core

import dev.turbodash.core.ecu.Launch
import dev.turbodash.core.ecu.LaunchState
import dev.turbodash.core.ecu.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchTest {

    // The Vectra tune: launch "Largada/Flatshift" on PE1.
    private val tune = Fixtures.vectraTune()
    private fun snap(portbde: Int, status2: Int) = Snapshot(0.0, mapOf("portbde" to portbde.toDouble(), "status2" to status2.toDouble()))

    @Test
    fun `button released shows nothing`() {
        // Real values from the logs: 211 at rest (PE1 high); 209 with PE1 pulled low.
        assertEquals(LaunchState.OFF, Launch.state(snap(portbde = 211, status2 = 0), tune))
    }

    @Test
    fun `button held arms the launch`() {
        assertEquals(LaunchState.ARMED, Launch.state(snap(portbde = 209, status2 = 0), tune))
    }

    @Test
    fun `ecu launch flag wins`() {
        assertEquals(LaunchState.ACTIVE, Launch.state(snap(portbde = 209, status2 = 8), tune))
    }

    @Test
    fun `no data shows nothing`() {
        assertEquals(LaunchState.OFF, Launch.state(null, tune))
        assertEquals(LaunchState.OFF, Launch.state(Snapshot(0.0, emptyMap()), tune))
        // A channel the source never filled reads 0: not "every button pressed".
        assertEquals(LaunchState.OFF, Launch.state(snap(portbde = 0, status2 = 0), tune))
    }
}
