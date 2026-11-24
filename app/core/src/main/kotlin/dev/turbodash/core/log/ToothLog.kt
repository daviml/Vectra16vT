package dev.turbodash.core.log

import dev.turbodash.core.ecu.ToothBlock
import java.io.Closeable
import java.io.Writer
import java.util.Locale

/**
 * Companion file of a datalog with the composite logger buffers read while cranking
 * (`<same name>.dentes.csv`). The buffers are stored as read, in hex: decoding is left to the
 * analysis, so nothing is lost if the record layout is not what was assumed.
 */
class ToothLogWriter(private val out: Writer) : Closeable {

    var blocks = 0
        private set

    init {
        out.write("# TurboDash: composite logger da MS2/Extra (pagina 0xf2) lido durante a partida\n")
        out.write("# time_s = mesmo relogio da coluna Time do .msl; kind full = buffer completo, partial = lido por tempo\n")
        out.write("# hex = 1024 bytes: 341 registros de 3 bytes (big-endian) + 1 byte sem uso\n")
        out.write("# registro: bit 23 SecLevel, bit 22 PriLevel, bit 21 Trigger, bit 20 Sync, bits 0-19 tempo desde a borda anterior x 0,66 us\n")
        out.write("time_s;kind;rpm;status3;hex\n")
    }

    fun append(b: ToothBlock) {
        val hex = StringBuilder(b.data.size * 2)
        for (x in b.data) hex.append(HEX[(x.toInt() shr 4) and 0xF]).append(HEX[x.toInt() and 0xF])
        out.write(String.format(Locale.US, "%.3f;%s;%.0f;%d;%s\n", b.time, b.kind, b.rpm, b.status3, hex))
        out.flush() // a handful of lines per start, and the link may drop at any moment
        blocks++
    }

    override fun close() {
        out.flush()
        out.close()
    }

    companion object {
        const val SUFFIX = ".dentes.csv"
        private const val HEX = "0123456789abcdef"

        /** `2026-11-24_08.36.28.msl` → `2026-11-24_08.36.28.dentes.csv`. */
        fun nameFor(mslName: String): String = mslName.removeSuffix(".msl") + SUFFIX
    }
}
