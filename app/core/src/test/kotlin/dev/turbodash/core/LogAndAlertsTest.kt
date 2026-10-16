package dev.turbodash.core

import dev.turbodash.core.alerts.AlertConfig
import dev.turbodash.core.alerts.AlertEngine
import dev.turbodash.core.alerts.Severity
import dev.turbodash.core.analysis.LogAnalyzer
import dev.turbodash.core.ecu.ChannelDecoder
import dev.turbodash.core.ecu.ChannelRoles
import dev.turbodash.core.ecu.EcuClient
import dev.turbodash.core.ecu.Snapshot
import dev.turbodash.core.log.MslReader
import dev.turbodash.core.log.MslWriter
import dev.turbodash.core.sim.ReplayEngine
import dev.turbodash.core.sim.Scenario
import dev.turbodash.core.sim.SimulatedEcu
import dev.turbodash.core.sim.SyntheticEngine
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogAndAlertsTest {

    private val ini = Fixtures.vectraIni
    private val roles = ChannelRoles(ini)

    /** Records [seconds] of the simulated drive at 10 Hz into an msl string. */
    private fun recordLog(seconds: Int, scenario: Scenario, timeScale: Double): String {
        val tune = Fixtures.vectraTune()
        var now = 0.0
        val ecu = SimulatedEcu(ini, tune, SyntheticEngine(tune, scenario, timeScale), clock = { now })
        val client = EcuClient(ini, ecu)
        val decoder = ChannelDecoder(ini, tune)
        val out = StringWriter()
        val writer = MslWriter(ini, tune, decoder, out)
        for (i in 0 until seconds * 10) {
            now = i / 10.0
            writer.append(decoder.decode(client.readOutputChannels(), now))
        }
        writer.close()
        return out.toString()
    }

    @Test
    fun `msl log is written with INI columns and reads back`() {
        val text = recordLog(30, Scenario.CITY_HEAT, 10.0)
        assertTrue(text.startsWith("\"MS2Extra MegaSpeed \""))
        val log = MslReader.read(StringReader(text))
        assertEquals(300, log.rows.size)
        assertTrue(log.column("Lambda") >= 0 && log.column("RPM") >= 0 && log.column("MAT") >= 0)
        assertFalse(log.headers.contains("MAF"), "MAF column is conditional on MAFOption")
        assertEquals("RPM", log.units[log.column("RPM")])
        assertEquals(29.9, log.durationSeconds, 0.01)
    }

    @Test
    fun `analyzer flags heat soak in the city scenario`() {
        val log = MslReader.read(StringReader(recordLog(240, Scenario.CITY_HEAT, 40.0)))
        val report = LogAnalyzer.analyze(log)
        val mat = report.stats.first { it.label.startsWith("Ar") }
        assertTrue(mat.max > 55, "max MAT ${mat.max}")
        assertTrue(report.hotAir.isNotEmpty())
        assertTrue(report.leanUnderLoad.isNotEmpty(), "pulls with hot air drift lean in the model")
        assertFalse(report.boostSeen)
        assertTrue(LogAnalyzer.toText(report).contains("Conclusões"))
    }

    @Test
    fun `replay engine plays a recorded log back through the simulator`() {
        val log = MslReader.read(StringReader(recordLog(20, Scenario.BOOST_PULLS, 10.0)))
        val tune = Fixtures.vectraTune()
        var now = 0.0
        val ecu = SimulatedEcu(ini, tune, ReplayEngine(ini, log), clock = { now })
        val client = EcuClient(ini, ecu)
        val decoder = ChannelDecoder(ini, tune)
        now = 3.0
        val s = decoder.decode(client.readOutputChannels(), now)
        val row = log.rows.first { it[log.column("Time")] >= 3.0 }
        assertEquals(row[log.column("RPM")], s["rpm"]!!, 1.0)
        assertEquals(row[log.column("Lambda")], s["lambda1"]!!, 0.01)
    }

    private fun snap(t: Double, vararg v: Pair<String, Double>) =
        Snapshot(t, mapOf("rpm" to 3000.0, "tps" to 50.0, "barometer" to 100.0) + v)

    @Test
    fun `hot intake air raises a critical alert after the confirmation delay`() {
        val engine = AlertEngine(roles, AlertConfig(confirmSeconds = 0.5, clearSeconds = 2.0))
        assertTrue(engine.evaluate(snap(0.0, "mat" to 75.0)).isEmpty())
        val a = engine.evaluate(snap(0.6, "mat" to 75.0))
        assertEquals("mat_crit", a.single().id)
        assertEquals(Severity.CRITICAL, a.single().severity)
        assertTrue(engine.evaluate(snap(1.0, "mat" to 40.0)).isNotEmpty(), "still shown during clear delay")
        assertTrue(engine.evaluate(snap(3.5, "mat" to 40.0)).isEmpty())
    }

    @Test
    fun `lean mixture under load is critical but not at idle`() {
        val engine = AlertEngine(roles, AlertConfig(confirmSeconds = 0.0))
        // target lambda 0.82 (AFR 12.05), measured 0.92 at 150 kPa
        val lean = engine.evaluate(snap(0.0, "map" to 150.0, "lambda1" to 0.92, "afrtgt1" to 12.05))
        assertTrue(lean.any { it.id == "lean_load" })
        val idle = AlertEngine(roles, AlertConfig(confirmSeconds = 0.0))
            .evaluate(snap(0.0, "map" to 35.0, "lambda1" to 1.3, "afrtgt1" to 14.4, "rpm" to 1600.0))
        assertTrue(idle.none { it.id == "lean_load" }, "lean at idle does not break pistons")
    }
}
