package dev.turbodash.core

import dev.turbodash.core.ini.Constant
import dev.turbodash.core.tune.Msq
import dev.turbodash.core.tune.MsqValue
import dev.turbodash.core.tune.TableView
import dev.turbodash.core.tune.Tune
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TuneMsqTest {

    private val ini = Fixtures.vectraIni

    @Test
    fun `values from the Vectra msq match TunerStudio`() {
        val tune = Fixtures.vectraTune()
        assertEquals(7.8, tune.scalar("reqFuel")!!, 0.01)
        assertEquals(0.7, tune.scalar("injOpen")!!, 0.001)
        assertEquals(6000.0, tune.scalar("RevLimNormal2")!!, 0.5)
        assertEquals("4", tune.bitsLabel("nCylinders"))
        assertEquals("Densidade do Ar", tune.bitsLabel("algorithm"))
        assertEquals("Desativado", tune.bitsLabel("knk_option"))

        val ve = tune.array("veTable1")!!
        assertEquals(256, ve.size)
        assertEquals(71.0, ve[0], 0.01)   // 20 kPa, 501 rpm
        assertEquals(89.0, ve[255], 0.01) // 200 kPa, 7500 rpm
        assertEquals(19.0, tune.array("advanceTable1")!![0], 0.05)
        assertEquals(0.9796, tune.array("afrTable1")!![0], 0.004)
        assertEquals(82.214, tune.array("matTemps")!![1], 0.1)
    }

    @Test
    fun `every msq value survives the encode and decode through page bytes`() {
        val tune = Fixtures.vectraTune()
        val msq = javaClass.getResourceAsStream("/vectra.msq")!!.use { Msq.read(it) }
        val mismatches = ArrayList<String>()
        for ((name, v) in msq.values) {
            val c = ini.constants[name] ?: continue
            when (c) {
                is Constant.Scalar -> if (v is MsqValue.Number && c.translateExpr == null) {
                    val tol = abs(tune.resolve(c.scale, 1.0)) / 2 + 1e-6
                    val got = tune.scalar(c)
                    if (abs(got - v.value) > tol) mismatches += "$name: msq=${v.value} decoded=$got"
                }
                is Constant.Array -> if (v is MsqValue.Numbers) {
                    val tol = abs(tune.resolve(c.scale, 1.0)) / 2 + 1e-6
                    val got = tune.array(c)
                    v.values.forEachIndexed { i, x -> if (i < got.size && abs(got[i] - x) > tol) mismatches += "$name[$i]: msq=$x decoded=${got[i]}" }
                }
                is Constant.Bits -> if (v is MsqValue.Text && v.value !in c.options) mismatches += "$name: option ${v.value}"
                else -> Unit
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.take(20).joinToString("\n"))
    }

    @Test
    fun `msq writer output reloads to identical pages`() {
        val tune = Fixtures.vectraTune()
        val xml = Msq.write(tune)
        val reloaded = Tune(ini)
        Msq.apply(Msq.read(xml.byteInputStream(Charsets.ISO_8859_1)), reloaded)
        for (p in 0 until ini.nPages) assertContentEquals(tune.pages[p], reloaded.pages[p], "page $p")
    }

    @Test
    fun `table view interpolates like the ECU`() {
        val tune = Fixtures.vectraTune()
        val def = ini.tables.first { it.zBins == "veTable1" }
        val t = assertNotNull(TableView.from(tune, def))
        assertEquals(16, t.xs.size)
        assertEquals(71.0, t.interpolate(0.0, 0.0), 0.01)       // clamps below the table
        assertEquals(t.at(2, 3), t.interpolate(t.xs[2], t.ys[3]), 1e-9)
        val mid = t.interpolate((t.xs[0] + t.xs[1]) / 2, t.ys[0])
        assertEquals((t.at(0, 0) + t.at(1, 0)) / 2, mid, 1e-9)
    }
}
