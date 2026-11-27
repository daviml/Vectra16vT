package dev.turbodash.core

import dev.turbodash.core.ecu.TeethBadge
import dev.turbodash.core.ecu.Teeth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeethBadgeTest {
    @Test
    fun `armed while stopped or cranking, confirmed when it fires, gone while driving`() {
        assertNull(Teeth.badge(null, 0.0, 0.0, 0.0))                       // no capture on this link
        assertEquals(TeethBadge.ARMED, Teeth.badge("arm", 0.0, 30.0, 0.0)) // key on, any time
        assertEquals(TeethBadge.ARMED, Teeth.badge("partial", 150.0, 0.5, 160.0))
        assertEquals(TeethBadge.ARMED, Teeth.badge("partial", 150.0, 0.5, 900.0)) // fired, read pending
        assertNull(Teeth.badge("partial", 150.0, 20.0, 900.0))             // never got the fired read: hide
        assertEquals(TeethBadge.CAPTURED, Teeth.badge("fired", 1000.0, 1.0, 1000.0))
        assertNull(Teeth.badge("fired", 1000.0, 4.0, 1000.0))
        assertEquals(TeethBadge.FAILED, Teeth.badge("fail", 0.0, 60.0, 0.0)) // stopped: stays up
        assertEquals(TeethBadge.FAILED, Teeth.badge("fail", 150.0, 2.0, 1100.0))
        assertNull(Teeth.badge("fail", 150.0, 10.0, 1100.0))               // driving: not a distraction
    }
}
