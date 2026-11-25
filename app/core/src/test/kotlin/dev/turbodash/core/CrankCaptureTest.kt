package dev.turbodash.core

import dev.turbodash.core.alerts.Alert
import dev.turbodash.core.ecu.CrankCapture
import dev.turbodash.core.ecu.EcuSession
import dev.turbodash.core.ecu.SessionState
import dev.turbodash.core.ecu.Snapshot
import dev.turbodash.core.ecu.ToothBlock
import dev.turbodash.core.log.ToothLogWriter
import dev.turbodash.core.protocol.EcuTimeoutException
import dev.turbodash.core.sim.EngineSource
import dev.turbodash.core.sim.SimulatedEcu
import dev.turbodash.core.tune.Tune
import java.io.StringWriter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrankCaptureTest {

    private class Fake {
        var reads = 0
        var fail = false
        val blocks = ArrayList<ToothBlock>()
        val capture = CrankCapture(
            readBuffer = { reads++; if (fail) throw EcuTimeoutException("sem resposta") else ByteArray(1024) { reads.toByte() } },
            onBlock = { blocks += it },
        )
    }

    @Test
    fun `arms with the engine stopped and reads by time while cranking`() {
        val f = Fake()
        assertTrue(f.capture.update(0.0, 0.0, 0))          // key on: arming read
        assertFalse(f.capture.update(0.1, 0.0, 0x02))      // leftover flag with the engine stopped: ignored
        for (i in 0..11) assertFalse(f.capture.update(30.0 + i * 0.1, 150.0, 0)) // cranking, buffer filling
        assertTrue(f.capture.update(31.25, 150.0, 0))      // 1.2 s of cranking: read, flag or no flag
        assertFalse(f.capture.update(31.3, 160.0, 0x02))   // flag right after a read: wait the minimum gap
        assertTrue(f.capture.update(31.75, 160.0, 0x0a))   // the flag brings the next read forward
        assertFalse(f.capture.update(32.5, 170.0, 0))
        assertTrue(f.capture.update(33.0, 170.0, 0))
        assertEquals(listOf("arm", "partial", "full", "partial"), f.blocks.map { it.kind })
        assertEquals(listOf(0.0, 31.25, 31.75, 33.0), f.blocks.map { it.time })
        assertEquals(150.0, f.blocks[1].rpm)
        assertEquals(4, f.reads)
    }

    @Test
    fun `reads once more when the engine catches and arms again when it stalls`() {
        val f = Fake()
        f.capture.update(0.0, 150.0, 0)                    // connected while already cranking
        assertFalse(f.capture.update(0.3, 900.0, 0))       // fired, but the arming read was 0.3 s ago
        assertTrue(f.capture.update(0.5, 900.0, 0))        // buffer that holds the moment it fired
        assertFalse(f.capture.update(4.0, 1100.0, 0x02))   // running: left alone
        assertFalse(f.capture.update(60.0, 1100.0, 0x02))
        assertEquals(2, f.reads)
        assertTrue(f.capture.update(61.0, 0.0, 0))         // stalled: armed again
        assertFalse(f.capture.update(62.0, 140.0, 0))
        assertTrue(f.capture.update(63.5, 140.0, 0))
        assertEquals(listOf("arm", "fired", "arm", "partial"), f.blocks.map { it.kind })
    }

    @Test
    fun `a short start still gets the buffer of the moment it fired`() {
        val f = Fake()
        f.capture.update(0.0, 0.0, 0)
        for (i in 1..8) assertFalse(f.capture.update(10.0 + i * 0.1, 150.0, 0)) // 0.8 s of cranking
        assertTrue(f.capture.update(10.9, 800.0, 0))
        assertEquals(listOf("arm", "fired"), f.blocks.map { it.kind })
    }

    @Test
    fun `a failed read is recorded and tried again later, never switched off`() {
        val f = Fake()
        f.fail = true
        assertFalse(f.capture.update(0.0, 0.0, 0))
        assertFalse(f.capture.update(0.5, 0.0, 0))         // waiting 1 s
        assertFalse(f.capture.update(1.0, 0.0, 0))         // second failure: now waits 2 s
        assertFalse(f.capture.update(2.5, 0.0, 0))
        assertEquals(2, f.reads)
        assertEquals(listOf("fail", "fail"), f.blocks.map { it.kind })
        assertTrue(f.blocks[0].note.contains("sem resposta"))
        assertFalse(f.blocks[0].hasEdges)
        f.fail = false
        assertTrue(f.capture.update(3.0, 0.0, 0))          // back to work
        assertFalse(f.capture.update(5.0, 150.0, 0))
        assertTrue(f.capture.update(6.5, 150.0, 0))
        assertEquals(listOf("fail", "fail", "arm", "partial"), f.blocks.map { it.kind })
        f.fail = true
        assertFalse(f.capture.update(8.0, 150.0, 0))
        assertEquals(3, f.capture.failures)
        f.fail = false
        f.capture.reset()                                   // a reconnection starts clean
        assertTrue(f.capture.update(7.1, 150.0, 0))
    }

    @Test
    fun `writes one hex line per buffer`() {
        val out = StringWriter()
        val w = ToothLogWriter(out)
        w.append(ToothBlock(12.3456, "full", 151.4, 2, byteArrayOf(0x10, 0xab.toByte(), 0x00)))
        w.close()
        val lines = out.toString().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(listOf("time_s;kind;rpm;status3;hex;note", "12.346;full;151;2;10ab00;"), lines)
        assertEquals("2026-11-24_08.36.28.dentes.csv", ToothLogWriter.nameFor("2026-11-24_08.36.28.msl"))
    }

    /** Key on for 1 s, 5 s of slow cranking, then the engine runs. */
    private class ColdStart : EngineSource {
        override fun values(t: Double): Map<String, Double> = mapOf(
            "rpm" to when { t < 1.0 -> 0.0; t < 6.0 -> 200.0; else -> 1100.0 },
            "batteryVoltage" to 12.0, "coolant" to 33.0,
        )
    }

    @Test
    fun `session records cranking and reads the tune afterwards without stopping the frames`() {
        val ini = Fixtures.vectraIni
        val tune = Fixtures.vectraTune()
        val blocks = CopyOnWriteArrayList<ToothBlock>()
        val frames = CopyOnWriteArrayList<Snapshot>()
        val states = CopyOnWriteArrayList<SessionState>()
        val tunes = CopyOnWriteArrayList<Boolean>()
        var ecuTune: Tune? = null
        lateinit var ecu: SimulatedEcu
        val session = EcuSession(
            ini, openTransport = { SimulatedEcu(ini, tune, ColdStart()).also { ecu = it } },
            fallbackTune = tune, targetHz = 50.0, crankCapture = true,
            listener = object : EcuSession.Listener {
                override fun onState(state: SessionState) { states += state }
                override fun onTune(tune: Tune, fromEcu: Boolean) { tunes += fromEcu; if (fromEcu) ecuTune = tune }
                override fun onFrame(snapshot: Snapshot, alerts: List<Alert>) { frames += snapshot }
                override fun onToothBlock(block: ToothBlock) { blocks += block }
            },
        )
        val th = Thread { session.run() }.apply { start() }
        Thread.sleep(11_000)
        session.stop()
        th.join(5000)

        // Arming read with the key on, one read every 1.2 s of the 5 s of cranking, one when it fires.
        val shown = blocks.map { "%.1f %s %.0f".format(it.time, it.kind, it.rpm) }
        assertEquals(ToothBlock.KIND_ARM, blocks.first().kind, "$shown")
        val edges = blocks.filter { it.hasEdges }
        assertTrue(edges.size in 4..6, "blocks: $shown")
        assertTrue(blocks.all { it.data.size == 1024 }, "$shown")
        assertTrue(edges.dropLast(1).all { it.kind == ToothBlock.KIND_PARTIAL && it.rpm == 200.0 }, "$shown")
        assertEquals(ToothBlock.KIND_FIRED, edges.last().kind, "$shown")
        // Sync flag + tooth time of the simulator: 60 s / 200 rpm / 60 teeth / 0.66 us = 7575 ticks.
        val first = edges.first().data
        assertEquals((1 shl 20) or 7575, ((first[0].toInt() and 0xFF) shl 16) or ((first[1].toInt() and 0xFF) shl 8) or (first[2].toInt() and 0xFF))
        // Logger left alone once the engine runs: every read is in the file.
        assertEquals(blocks.size, ecu.loggerReads)

        // Frames kept coming while cranking, and never a "reading calibration" screen.
        val cranking = frames.filter { (it["rpm"] ?: 0.0) == 200.0 }
        assertTrue(cranking.size > 100, "frames while cranking: ${cranking.size}")
        assertTrue(cranking.zipWithNext().all { (a, b) -> b.time - a.time < 0.5 }, "gap in the frames while cranking")
        assertFalse(states.any { it is SessionState.ReadingTune })
        assertTrue(states.none { it is SessionState.Failed }, "$states")
        // msq tune first, the one from the ECU once the engine was running.
        assertEquals(listOf(false, true), tunes.toList())
        for (p in 0 until ini.nPages) assertContentEquals(tune.pages[p], ecuTune!!.pages[p])
    }

    @Test
    fun `without the option the session never touches the logger`() {
        val ini = Fixtures.vectraIni
        val tune = Fixtures.vectraTune()
        val states = CopyOnWriteArrayList<SessionState>()
        lateinit var ecu: SimulatedEcu
        val session = EcuSession(
            ini, openTransport = { SimulatedEcu(ini, tune, ColdStart()).also { ecu = it } },
            fallbackTune = tune, targetHz = 50.0,
            listener = object : EcuSession.Listener {
                override fun onState(state: SessionState) { states += state }
                override fun onFrame(snapshot: Snapshot, alerts: List<Alert>) {}
            },
        )
        val th = Thread { session.run() }.apply { start() }
        Thread.sleep(3000)
        session.stop()
        th.join(5000)
        assertEquals(0, ecu.loggerReads)
        assertTrue(states.any { it is SessionState.ReadingTune }) // calibration read up front, as before
    }
}
