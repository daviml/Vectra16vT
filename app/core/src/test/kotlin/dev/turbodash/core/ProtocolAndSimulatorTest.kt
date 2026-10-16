package dev.turbodash.core

import dev.turbodash.core.alerts.Alert
import dev.turbodash.core.ecu.ChannelDecoder
import dev.turbodash.core.ecu.EcuClient
import dev.turbodash.core.ecu.EcuSession
import dev.turbodash.core.ecu.SessionState
import dev.turbodash.core.ecu.Snapshot
import dev.turbodash.core.protocol.CommandFormatter
import dev.turbodash.core.protocol.MsEnvelope
import dev.turbodash.core.sim.Scenario
import dev.turbodash.core.sim.SimulatedEcu
import dev.turbodash.core.sim.SyntheticEngine
import dev.turbodash.core.tune.Tune
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolAndSimulatorTest {

    private val ini = Fixtures.vectraIni

    @Test
    fun `formats page read command`() {
        val cmd = CommandFormatter.format(ini.pageReadCommands[0], CommandFormatter.Params(canId = 0, offset = 256, count = 128))
        assertContentEquals(byteArrayOf('r'.code.toByte(), 0, 4, 1, 0, 0, 0x80.toByte()), cmd)
        assertContentEquals(byteArrayOf('b'.code.toByte(), 3, 0x0a), CommandFormatter.format(ini.burnCommands[2], CommandFormatter.Params(canId = 3)))
        assertContentEquals("A".toByteArray(), CommandFormatter.format(ini.ochGetCommand))
    }

    @Test
    fun `envelope carries length and CRC32`() {
        val w = MsEnvelope.wrap("Q".toByteArray())
        assertEquals(7, w.size)
        assertEquals(0, w[0].toInt()); assertEquals(1, w[1].toInt())
        assertEquals(MsEnvelope.crc32("Q".toByteArray()), MsEnvelope.getU32(w, 3))
    }

    private fun simulator(tune: Tune, t: Double = 5.0) =
        SimulatedEcu(ini, tune, SyntheticEngine(tune, Scenario.CITY_HEAT), clock = { t })

    @Test
    fun `client reads signature pages and realtime data from the simulator`() {
        val tune = Fixtures.vectraTune()
        val ecu = simulator(tune)
        val client = EcuClient(ini, ecu)
        val sig = client.querySignature()
        assertTrue(client.signatureMatches(sig), "signature '$sig'")
        for (p in 0 until ini.nPages) {
            val page = client.readPage(p)
            assertContentEquals(tune.pages[p], page, "page $p")
            assertEquals(MsEnvelope.crc32(page), client.pageCrc32(p))
        }
        val snap = ChannelDecoder(ini, tune).decode(client.readOutputChannels(), 5.0)
        assertEquals(950.0, snap["rpm"]!!, 60.0) // idle phase of the drive cycle
        assertEquals(snap["afr1"]!! / 14.7, snap["lambda1"]!!, 1e-9)
        assertTrue(snap["dutyCycle1"]!! > 0.0, "duty from formula with tune constants")
        assertTrue(snap["coolant"]!! in 0.0..120.0)
    }

    @Test
    fun `session connects reads the tune from the ECU and streams frames`() {
        val tune = Fixtures.vectraTune()
        val frames = CopyOnWriteArrayList<Snapshot>()
        val states = CopyOnWriteArrayList<SessionState>()
        var tuneFromEcu: Tune? = null
        val gotFrames = CountDownLatch(5)
        lateinit var session: EcuSession
        session = EcuSession(
            ini, openTransport = { SimulatedEcu(ini, tune, SyntheticEngine(tune, Scenario.CITY_HEAT)) },
            fallbackTune = null, targetHz = 200.0,
            listener = object : EcuSession.Listener {
                override fun onState(state: SessionState) { states += state }
                override fun onTune(tune: Tune, fromEcu: Boolean) { if (fromEcu) tuneFromEcu = tune }
                override fun onFrame(snapshot: Snapshot, alerts: List<Alert>) { frames += snapshot; gotFrames.countDown() }
            },
        )
        val th = Thread { session.run() }.apply { start() }
        assertTrue(gotFrames.await(10, TimeUnit.SECONDS), "frames: ${frames.size}, states: $states")
        session.stop()
        th.join(5000)
        assertTrue(states.any { it is SessionState.ReadingTune })
        assertTrue(states.last() is SessionState.Stopped)
        for (p in 0 until ini.nPages) assertContentEquals(tune.pages[p], tuneFromEcu!!.pages[p])
        assertTrue(frames.all { (it["rpm"] ?: 0.0) > 0 })
    }

    @Test
    fun `session refuses an ECU with another signature`() {
        val tune = Fixtures.vectraTune()
        val states = CopyOnWriteArrayList<SessionState>()
        val session = EcuSession(
            ini, openTransport = { SimulatedEcu(ini, tune, SyntheticEngine(tune, Scenario.CITY_HEAT), signature = "MS3 Format 0569.00 ") },
            fallbackTune = tune,
            listener = object : EcuSession.Listener {
                override fun onState(state: SessionState) { states += state }
                override fun onFrame(snapshot: Snapshot, alerts: List<Alert>) {}
            },
        )
        session.run() // returns by itself
        val failed = states.filterIsInstance<SessionState.Failed>().single()
        assertTrue(!failed.retrying && failed.message.contains("MS3"), failed.message)
    }
}
