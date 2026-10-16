package dev.turbodash.core.log

import dev.turbodash.core.ecu.ChannelDecoder
import dev.turbodash.core.ecu.Snapshot
import dev.turbodash.core.expr.Expression
import dev.turbodash.core.ini.DatalogEntry
import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.tune.Tune
import java.io.BufferedReader
import java.io.Closeable
import java.io.Reader
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes TunerStudio-style `.msl` datalogs (tab separated, readable by MegaLogViewer):
 * ```
 * "<signature>"
 * "Capture Date: ..."
 * Time    RPM    MAP ...
 * s       RPM    kPa ...
 * 0.000   850    35.2 ...
 * ```
 * Columns come from the INI `[Datalog]` section; entries whose condition is false for the
 * current tune (e.g. MAF columns without a MAF) are left out, as TunerStudio does.
 */
class MslWriter(
    ini: IniDefinition,
    tune: Tune?,
    private val decoder: ChannelDecoder,
    private val out: Writer,
    /** Columns not in the INI (e.g. phone GPS speed), with their units. */
    extraColumns: List<Pair<DatalogEntry, String>> = emptyList(),
) : Closeable {

    val entries: List<DatalogEntry> = ini.datalog.filter { e ->
        val cond = e.condition ?: return@filter true
        val ctx = tune?.evalContext ?: return@filter true
        Expression.compileOrZero(cond).evalNumber(ctx) != 0.0
    } + extraColumns.map { it.first }

    private val extraUnits = extraColumns.associate { it.first.channel to it.second }

    private var rows = 0

    init {
        val date = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).format(Date())
        out.write("\"${ini.signature}\"\n")
        out.write("\"Capture Date: $date\"\n")
        out.write(entries.joinToString("\t") { it.label } + "\n")
        out.write(entries.joinToString("\t") { extraUnits[it.channel] ?: decoder.unitsOf(it.channel) } + "\n")
    }

    fun append(s: Snapshot) {
        val line = entries.joinToString("\t") { e ->
            val v = if (e.channel == "time") s.time else s[e.channel] ?: 0.0
            format(e, v)
        }
        out.write(line)
        out.write("\n")
        if (++rows % 50 == 0) out.flush()
    }

    /** Inserts a marker line, as TunerStudio's "Mark" button does. */
    fun mark(n: Int) {
        out.write("MARK %03d\n".format(n))
        out.flush()
    }

    private fun format(e: DatalogEntry, v: Double): String = try {
        if (e.format.contains('d')) String.format(Locale.US, e.format, Math.round(v))
        else String.format(Locale.US, e.format, v)
    } catch (ex: Exception) {
        String.format(Locale.US, "%.3f", v)
    }

    override fun close() {
        out.flush()
        out.close()
    }
}

/** A datalog loaded into memory, column-oriented by header label. */
class LogData(val signature: String?, val headers: List<String>, val units: List<String>, val rows: List<DoubleArray>) {

    fun column(label: String): Int = headers.indexOfFirst { it.equals(label, ignoreCase = true) }

    /** First column whose label matches any of the candidates. */
    fun columnAny(vararg labels: String): Int = labels.firstNotNullOfOrNull { l -> column(l).takeIf { it >= 0 } } ?: -1

    val durationSeconds: Double
        get() {
            val t = column("Time")
            if (t < 0 || rows.isEmpty()) return 0.0
            return rows.last()[t] - rows.first()[t]
        }
}

object MslReader {

    fun read(reader: Reader): LogData {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader)
        var signature: String? = null
        var headers: List<String>? = null
        var units: List<String>? = null
        val rows = ArrayList<DoubleArray>()
        br.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEach
            if (line.startsWith("\"")) {
                if (signature == null) signature = line.trim('"')
                return@forEach
            }
            if (line.startsWith("MARK")) return@forEach
            val parts = line.split('\t')
            when {
                headers == null -> headers = parts.map { it.trim() }
                units == null && parts.any { it.isNotBlank() && it.trim().toDoubleOrNull() == null } -> units = parts.map { it.trim() }
                else -> {
                    if (units == null) units = List(headers!!.size) { "" }
                    rows += DoubleArray(headers!!.size) { i -> parts.getOrNull(i)?.trim()?.toDoubleOrNull() ?: Double.NaN }
                }
            }
        }
        return LogData(signature, headers ?: emptyList(), units ?: emptyList(), rows)
    }
}
