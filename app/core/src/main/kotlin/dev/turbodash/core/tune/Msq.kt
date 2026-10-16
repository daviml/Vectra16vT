package dev.turbodash.core.tune

import dev.turbodash.core.ini.Constant
import dev.turbodash.core.ini.IniDefinition
import org.w3c.dom.Element
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Values read from a TunerStudio `.msq` file, keyed by constant name (user units, not raw). */
class MsqFile(
    val signature: String?,
    val values: Map<String, MsqValue>,
    val author: String?,
    val writeDate: String?,
)

sealed class MsqValue {
    data class Number(val value: Double) : MsqValue()
    data class Numbers(val values: DoubleArray, val rows: Int, val cols: Int) : MsqValue()
    data class Text(val value: String) : MsqValue()
}

object Msq {

    fun read(input: InputStream): MsqFile {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // msq files never need external entities; refuse them.
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val root = doc.documentElement
        val values = LinkedHashMap<String, MsqValue>()
        for (tag in listOf("constant", "pcVariable")) {
            val nodes = root.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                val name = e.getAttribute("name")
                if (name.isEmpty()) continue
                parseValue(e)?.let { values[name] = it }
            }
        }
        val version = root.getElementsByTagName("versionInfo").item(0) as? Element
        val bib = root.getElementsByTagName("bibliography").item(0) as? Element
        return MsqFile(
            signature = version?.getAttribute("signature")?.takeIf { it.isNotEmpty() },
            values = values,
            author = bib?.getAttribute("author"),
            writeDate = bib?.getAttribute("writeDate"),
        )
    }

    private fun parseValue(e: Element): MsqValue? {
        val text = e.textContent.trim()
        if (text.startsWith("\"")) return MsqValue.Text(text.removeSurrounding("\""))
        val rows = e.getAttribute("rows").toIntOrNull()
        val cols = e.getAttribute("cols").toIntOrNull()
        val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (rows != null || cols != null || parts.size > 1) {
            val nums = parts.mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
            return MsqValue.Numbers(nums, rows ?: nums.size, cols ?: 1)
        }
        return parts.firstOrNull()?.toDoubleOrNull()?.let { MsqValue.Number(it) } ?: MsqValue.Text(text)
    }

    /**
     * Writes msq values into [tune]. Returns names that were in the file but could not be applied
     * (unknown to this INI or with an unknown option label).
     */
    fun apply(msq: MsqFile, tune: Tune): List<String> {
        val skipped = ArrayList<String>()
        for ((name, v) in msq.values) {
            val c = tune.ini.constantOrPc(name)
            if (c == null) { skipped += name; continue }
            when (c) {
                is Constant.Scalar -> if (c.translateExpr != null) Unit else when (v) {
                    is MsqValue.Number -> if (c.page >= 0) tune.setScalar(c, v.value) else tune.pcValues[name] = v.value
                    is MsqValue.Numbers -> v.values.firstOrNull()?.let { if (c.page >= 0) tune.setScalar(c, it) }
                    else -> skipped += name
                }
                is Constant.Array -> when (v) {
                    is MsqValue.Numbers -> if (c.page >= 0) tune.setArray(c, v.values)
                    is MsqValue.Number -> if (c.page >= 0) tune.setArray(c, doubleArrayOf(v.value))
                    else -> skipped += name
                }
                is Constant.Bits -> {
                    val idx = when (v) {
                        is MsqValue.Text -> c.options.indexOf(v.value).takeIf { it >= 0 } ?: v.value.toDoubleOrNull()?.toInt()
                        is MsqValue.Number -> v.value.toInt()
                        else -> null
                    }
                    if (idx == null) skipped += name else tune.setBitsIndex(c, idx)
                }
                is Constant.Text -> Unit
            }
        }
        return skipped
    }

    /** Serializes [tune] in TunerStudio's msq format (compatible with TS "Open Tune"). */
    fun write(tune: Tune, author: String = "TurboDash"): String {
        val ini = tune.ini
        val sb = StringBuilder()
        val date = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).format(Date())
        sb.append("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n")
        sb.append("<msq xmlns=\"http://www.msefi.com/:msq\">\n")
        sb.append("<bibliography author=\"${esc(author)}\" tuneComment=\"\" writeDate=\"${esc(date)}\"/>\n")
        sb.append("<versionInfo fileFormat=\"5.0\" nPages=\"${ini.nPages}\" signature=\"${esc(ini.signature)}\"/>\n")
        sb.append("<page>\n")
        for (c in ini.pcVariables.values) {
            if (c is Constant.Bits) {
                sb.append("<pcVariable name=\"${c.name}\">\"${esc(tune.bitsLabel(c))}\"</pcVariable>\n")
            } else if (c is Constant.Scalar) {
                tune.pcValues[c.name]?.let {
                    sb.append("<pcVariable digits=\"${c.digits}\" name=\"${c.name}\" units=\"${esc(c.units)}\">${fmt(it)}</pcVariable>\n")
                }
            }
        }
        sb.append("</page>\n")
        for (p in 0 until ini.nPages) {
            sb.append("<page number=\"$p\" size=\"${ini.pageSizes[p]}\">\n")
            for (c in ini.constants.values) {
                if (c.page != p) continue
                when (c) {
                    is Constant.Bits -> sb.append("<constant name=\"${c.name}\">\"${esc(tune.bitsLabel(c))}\"</constant>\n")
                    is Constant.Scalar -> sb.append(
                        "<constant digits=\"${c.digits}\" name=\"${c.name}\" units=\"${esc(tune.unitsOf(c.units))}\">${fmt(tune.scalar(c))}</constant>\n"
                    )
                    is Constant.Array -> {
                        val vals = tune.array(c)
                        val rows = if (c.is2d) c.rows else c.count
                        val cols = if (c.is2d) c.cols else 1
                        sb.append("<constant cols=\"$cols\" digits=\"${c.digits}\" name=\"${c.name}\" rows=\"$rows\" units=\"${esc(tune.unitsOf(c.units))}\">\n")
                        for (r in 0 until rows) {
                            sb.append("         ")
                            for (col in 0 until cols) sb.append(fmt(vals[r * cols + col])).append(' ')
                            sb.append('\n')
                        }
                        sb.append("      </constant>\n")
                    }
                    is Constant.Text -> sb.append("<constant name=\"${c.name}\">\"${esc(tune.text(c))}\"</constant>\n")
                }
            }
            sb.append("</page>\n")
        }
        sb.append("</msq>\n")
        return sb.toString()
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 1_000_000.0) / 1_000_000.0
        return r.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
