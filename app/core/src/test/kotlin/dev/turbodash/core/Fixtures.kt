package dev.turbodash.core

import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.ini.IniParser
import dev.turbodash.core.tune.Msq
import dev.turbodash.core.tune.Tune

/** Real files from the Vectra 16v Turbo project (TunerStudio) used as test fixtures. */
object Fixtures {
    /** Project settings of the Vectra TunerStudio project (`ecuSettings=CELSIUS|LAMBDA|PORT_STATUS`). */
    val VECTRA_SYMBOLS = setOf("CELSIUS", "LAMBDA", "PORT_STATUS")

    fun text(name: String): String =
        Fixtures::class.java.getResourceAsStream("/$name")!!.readBytes().toString(Charsets.ISO_8859_1)

    val vectraIni: IniDefinition by lazy { IniParser.parse(text("vectra.ini"), VECTRA_SYMBOLS) }

    fun vectraTune(): Tune {
        val tune = Tune(vectraIni)
        Fixtures::class.java.getResourceAsStream("/vectra.msq")!!.use { Msq.apply(Msq.read(it), tune) }
        return tune
    }
}
