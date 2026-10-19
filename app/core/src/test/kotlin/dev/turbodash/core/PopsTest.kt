package dev.turbodash.core

import dev.turbodash.core.ecu.ChannelRoles
import dev.turbodash.core.ecu.Pops
import dev.turbodash.core.ecu.Snapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopsTest {

    private val roles = ChannelRoles(Fixtures.vectraIni)
    private fun s(rpm: Double, tps: Double, pw: Double, adv: Double) =
        Snapshot(0.0, mapOf("rpm" to rpm, "tps" to tps, "pulseWidth1" to pw, "advance" to adv))

    @Test
    fun `foot off above 3000 rpm with retarded spark and fuel on is the pops window`() {
        assertTrue(Pops.active(s(rpm = 3800.0, tps = 0.7, pw = 1.1, adv = 0.0), roles))
    }

    @Test
    fun `fuel cut, normal timing, throttle or low rpm are not pops`() {
        assertFalse(Pops.active(s(3800.0, 0.7, pw = 0.0, adv = 0.0), roles))   // cut already in
        assertFalse(Pops.active(s(3800.0, 0.7, pw = 1.1, adv = 42.0), roles))  // normal overrun timing
        assertFalse(Pops.active(s(3800.0, tps = 30.0, pw = 4.0, adv = 5.0), roles)) // accelerating
        assertFalse(Pops.active(s(rpm = 1100.0, 0.7, pw = 2.0, adv = 6.0), roles)) // idle with retarded idle advance
        assertFalse(Pops.active(null, roles))
    }
}
