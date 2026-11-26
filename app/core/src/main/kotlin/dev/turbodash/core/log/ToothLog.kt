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

    /** Lines written, of any kind. */
    var blocks = 0
        private set

    /** Reads that can hold edges of a start (not arming reads, not failures). */
    var edgeBlocks = 0
        private set

    init {
        out.write("# TurboDash: composite logger da MS2/Extra (pagina 0xf2) lido durante a partida\n")
        out.write("# time_s = mesmo relogio da coluna Time do .msl\n")
        out.write("# cada leitura = 4 blocos de 256 bytes; o ultimo bloco religa o logger com o buffer vazio\n")
        out.write("# kind: arm = leitura que liga o logger (normalmente zeros); partial = lido por tempo no arranque (bordas desde a leitura anterior);\n")
        out.write("#       fired = lido quando o motor pegou; full = a ECU avisou buffer completo; fail = leitura falhou (motivo em note)\n")
        out.write("# hex = 1024 bytes: 341 registros de 3 bytes (big-endian) + 1 byte = versao do formato (1 quando o buffer encheu)\n")
        out.write("# registro: bit 23 nivel do secundario, bit 22 nivel do primario, bit 21 borda veio do secundario, bit 20 sincronizado,\n")
        out.write("#           bits 0-19 tempo desde a borda anterior x 2/3 us; registro 000000 = ainda nao gravado\n")
        out.write("time_s;kind;rpm;status3;hex;note\n")
    }

    fun append(b: ToothBlock) {
        val hex = StringBuilder(b.data.size * 2)
        for (x in b.data) hex.append(HEX[(x.toInt() shr 4) and 0xF]).append(HEX[x.toInt() and 0xF])
        val note = b.note.replace(';', ',').replace('\n', ' ')
        out.write(String.format(Locale.US, "%.3f;%s;%.0f;%d;%s;%s\n", b.time, b.kind, b.rpm, b.status3, hex, note))
        out.flush() // a handful of lines per start, and the link may drop at any moment
        blocks++
        if (b.hasEdges) edgeBlocks++
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
