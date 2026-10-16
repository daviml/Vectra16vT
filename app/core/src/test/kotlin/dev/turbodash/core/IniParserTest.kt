package dev.turbodash.core

import dev.turbodash.core.ini.Constant
import dev.turbodash.core.ini.IniParser
import dev.turbodash.core.ini.OutputChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IniParserTest {

    private val ini = Fixtures.vectraIni

    @Test
    fun `reads communication settings of the MegaSpeed firmware`() {
        assertEquals("MS2Extra MegaSpeed ", ini.signature)
        assertEquals("Q", ini.queryCommand)
        assertTrue(ini.usesEnvelope)
        assertEquals(List(7) { 1024 }, ini.pageSizes)
        assertEquals(219, ini.ochBlockSize)
        assertEquals("A", ini.ochGetCommand)
        assertEquals(256, ini.blockingFactor)
        assertEquals("r\\\$tsCanId\\x04%2o%2c", ini.pageReadCommands[0])
    }

    @Test
    fun `parses constants with pages offsets and shapes`() {
        assertTrue(ini.constants.size > 900, "constants: ${ini.constants.size}")
        val ve = ini.constants["veTable1"] as Constant.Array
        assertEquals(16, ve.cols)
        assertEquals(16, ve.rows)
        val reqFuel = ini.constants["reqFuel"] as Constant.Scalar
        assertEquals("ms", reqFuel.units)
        val nCyl = ini.constants["nCylinders"] as Constant.Bits
        assertEquals(0, nCyl.page)
        assertEquals(0, nCyl.offset)
        assertEquals("4", nCyl.options[4])
        val ps = ini.constants["psCondition"] as Constant.Array
        assertEquals(7, ps.cols)
        assertEquals(2, ps.rows)
    }

    @Test
    fun `honours project symbols in preprocessor blocks`() {
        val lambda = ini.constants["afrTable1"] as Constant.Array
        assertEquals("Lambda", lambda.units)
        val afrIni = IniParser.parse(Fixtures.text("vectra.ini"), setOf("CELSIUS"))
        assertEquals("AFR", (afrIni.constants["afrTable1"] as Constant.Array).units)
        assertTrue("LAMBDA" in ini.referencedSymbols && "CELSIUS" in ini.referencedSymbols)
        assertEquals("Celsius", (ini.outputChannels["coolant"] as OutputChannel.Scalar).units)
    }

    @Test
    fun `parses output channels gauges tables and datalog`() {
        val rpm = ini.outputChannels["rpm"] as OutputChannel.Scalar
        assertEquals(6, rpm.offset)
        assertTrue(ini.outputChannels["dutyCycle1"] is OutputChannel.Formula)
        assertNotNull(ini.gauges["knockGauge"], "gauge with missing comma must still parse")
        assertNotNull(ini.tables.firstOrNull { it.zBins == "veTable1" })
        val ign = ini.tables.first { it.zBins == "advanceTable1" }
        assertEquals("srpm_table1", ign.xBins)
        assertTrue(ini.datalog.any { it.label == "Lambda" })
        assertTrue(ini.indicators.isNotEmpty())
        assertTrue(ini.frontPageGauges.contains("tachometer"))
    }

    @Test
    fun `other MegaSquirt INIs parse without errors`() {
        for (name in listOf("ms2extra-example.ini", "ms3-example.ini", "ms1extra-example.ini", "ms2bg-example.ini")) {
            val def = IniParser.parse(Fixtures.text(name), setOf("CELSIUS"))
            assertTrue(def.signature.isNotBlank(), "$name: signature")
            assertTrue(def.constants.isNotEmpty(), "$name: constants")
            assertTrue(def.outputChannels.containsKey("rpm"), "$name: rpm channel")
            assertTrue(def.ochBlockSize > 0, "$name: och block size")
        }
    }
}
