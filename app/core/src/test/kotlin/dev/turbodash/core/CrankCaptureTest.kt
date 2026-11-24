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
    fun `arms with the engine stopped and reads only when the ECU flags a full buffer`() {
        val f = Fake()
        assertTrue(f.capture.update(0.0, 0.0, 0))          // key on: arming read, nothing stored
        assertFalse(f.capture.update(0.1, 0.0, 0x02))      // leftover flag with the engine stopped: ignored
        for (i in 2..20) assertFalse(f.capture.update(i * 0.1, 150.0, 0)) // cranking, buffer filling
        assertEquals(1, f.reads)
        assertTrue(f.capture.update(2.5, 160.0, 0x02))     // full
        assertFalse(f.capture.update(2.6, 160.0, 0x02))    // flag not cleared yet: wait the minimum gap
        assertTrue(f.capture.update(5.0, 170.0, 0x0a))     // other status3 bits do not matter
        assertEquals(listOf("full", "full"), f.blocks.map { it.kind })
        assertEquals(listOf(2.5, 5.0), f.blocks.map { it.time })
        assertEquals(160.0, f.blocks[0].rpm)
    }

    @Test
    fun `stops after the engine catches and arms again when it stalls`() {
        val f = Fake()
        f.capture.update(0.0, 150.0, 0)                    // connected while already cranking
        assertTrue(f.capture.update(1.0, 900.0, 0x02))     // buffer that holds the moment it fired
        assertFalse(f.capture.update(4.0, 1100.0, 0x02))   // running for more than 2.5 s: disarmed
        assertFalse(f.capture.update(60.0, 1100.0, 0x02))
        assertEquals(2, f.reads)
        assertTrue(f.capture.update(61.0, 0.0, 0))         // stalled: armed again
        assertTrue(f.capture.update(63.0, 140.0, 0x02))
        assertEquals(2, f.blocks.size)
    }

    @Test
    fun `reads a partial buffer when cranking never fills it`() {
        val f = Fake()
        f.capture.update(0.0, 0.0, 0)
        for (i in 1..49) assertFalse(f.capture.update(i * 0.1, 90.0, 0))
        assertTrue(f.capture.update(5.1, 90.0, 0))
        assertEquals("partial", f.blocks.single().kind)
    }

    @Test
    fun `gives up after two failed reads`() {
        val f = Fake()
        f.fail = true
        assertFalse(f.capture.update(0.0, 0.0, 0))
        assertFalse(f.capture.disabled)
        assertFalse(f.capture.update(0.1, 0.0, 0))
        assertTrue(f.capture.disabled)
        f.fail = false
        f.capture.reset()                                   // a reconnection does not bring it back
        assertFalse(f.capture.update(1.0, 150.0, 0x02))
        assertEquals(2, f.reads)
    }

    @Test
    fun `writes one hex line per buffer`() {
        val out = StringWriter()
        val w = ToothLogWriter(out)
        w.append(ToothBlock(12.3456, "full", 151.4, 2, byteArrayOf(0x10, 0xab.toByte(), 0x00)))
        w.close()
        val lines = out.toString().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(listOf("time_s;kind;rpm;status3;hex", "12.346;full;151;2;10ab00"), lines)
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

        // 200 rpm on a 60-2 wheel = 193 edges/s: a 341-record buffer every ~1.8 s of the 5 s of cranking.
        assertTrue(blocks.size in 2..4, "blocks: ${blocks.map { "%.1f %s %.0f".format(it.time, it.kind, it.rpm) }}")
        assertTrue(blocks.all { it.kind == ToothBlock.KIND_FULL && it.data.size == 1024 })
        assertTrue(blocks.any { it.rpm == 200.0 })
        // Sync flag + tooth time of the simulator: 60 s / 200 rpm / 60 teeth / 0.66 us = 7575 ticks.
        val first = blocks.first().data
        assertEquals((1 shl 20) or 7575, ((first[0].toInt() and 0xFF) shl 16) or ((first[1].toInt() and 0xFF) shl 8) or (first[2].toInt() and 0xFF))
        // Logger left alone once the engine runs: arming read + the stored buffers (+ at most one more).
        assertTrue(ecu.loggerReads <= blocks.size + 2, "logger reads: ${ecu.loggerReads}")

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
