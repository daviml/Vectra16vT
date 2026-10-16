package dev.turbodash.core

import dev.turbodash.core.alerts.Alert
import dev.turbodash.core.ecu.EcuSession
import dev.turbodash.core.ecu.SessionState
import dev.turbodash.core.ecu.Snapshot
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EcuSessionStopTest {

    @Test
    fun `stop ends a session that is waiting to retry a failed connection`() {
        val states = java.util.Collections.synchronizedList(ArrayList<SessionState>())
        val session = EcuSession(
            Fixtures.vectraIni,
            openTransport = { throw IOException("Mega desligada") }, // like Bluetooth with the car off
            fallbackTune = null,
            listener = object : EcuSession.Listener {
                override fun onState(state: SessionState) { states += state }
                override fun onFrame(snapshot: Snapshot, alerts: List<Alert>) {}
            },
        )
        val thread = Thread { session.run() }.apply { start() }
        // Let it fail a few times so it sits in the (growing) retry wait.
        Thread.sleep(3500)
        session.stop()
        thread.join(500)
        assertFalse(thread.isAlive, "session still running 0.5 s after stop()")
        assertTrue(states.last() == SessionState.Stopped)
    }
}
