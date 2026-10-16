package dev.turbodash.core.ini

/**
 * Parser for TunerStudio/MegaTune `.ini` ECU definition files.
 *
 * The parser is deliberately lenient: unknown keys and malformed lines are skipped and
 * reported in [IniDefinition.warnings] instead of failing, because vendor INIs are hand-edited.
 */
object IniParser {

    private val CLASS_WORDS = setOf("scalar", "array", "bits", "string")

    fun parse(text: String, symbols: Set<String>): IniDefinition {
        val pre = IniPreprocessor.process(text, symbols)
        return Builder(pre).build(symbols)
    }

    // ---------------------------------------------------------------- tokenizing helpers

    /** Removes a `;` comment that is outside quotes and braces. */
    internal fun stripComment(line: String): String {
        var inQuote = false
        var depth = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && inQuote -> i++ // skip escaped char inside quotes
                c == '"' -> inQuote = !inQuote
                !inQuote && c == '{' -> depth++
                !inQuote && c == '}' -> depth--
                !inQuote && depth <= 0 && c == ';' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /** Splits on commas that are not inside quotes, braces, brackets or parentheses. */
    internal fun splitFields(value: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var depth = 0
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (inQuote) {
                sb.append(c)
                if (c == '\\' && i + 1 < value.length) { sb.append(value[i + 1]); i++ }
                else if (c == '"') inQuote = false
            } else when (c) {
                '"' -> { inQuote = true; sb.append(c) }
                '{', '[', '(' -> { depth++; sb.append(c) }
                '}', ']', ')' -> { depth--; sb.append(c) }
                ',' -> if (depth <= 0) { out += sb.toString().trim(); sb.clear() } else sb.append(c)
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank() || out.isNotEmpty()) out += sb.toString().trim()
        return out
    }

    internal fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t.substring(1, t.length - 1) else t
    }

    private fun intOf(s: String?): Int? = s?.trim()?.let { t ->
        t.toIntOrNull() ?: t.toDoubleOrNull()?.toInt()
            ?: if (t.startsWith("0x", true)) t.substring(2).toIntOrNull(16) else null
    }

    private fun doubleOf(s: String?): Double? = s?.trim()?.toDoubleOrNull()

    // ---------------------------------------------------------------- builder

    private class Builder(private val pre: IniPreprocessor.Result) {
        val warnings = ArrayList<String>()

        var signature = ""
        var queryCommand = "Q"
        var versionInfo: String? = null
        var endianBig = true
        val pageSizes = ArrayList<Int>()
        var pageIdentifiers = listOf<String>()
        var pageRead = listOf<String>()
        var pageChunkWrite = listOf<String>()
        var pageValueWrite = listOf<String>()
        var burn = listOf<String>()
        var crc = listOf<String>()
        var envelope: String? = null
        var blockingFactor = 256
        var blockReadTimeout = 500
        var ochBlockSize = 0
        var ochGetCommand = "A"

        val constants = LinkedHashMap<String, Constant>()
        val pcVars = LinkedHashMap<String, Constant>()
        val channels = LinkedHashMap<String, OutputChannel>()
        val gauges = LinkedHashMap<String, GaugeDef>()
        val frontGauges = sortedMapOf<Int, String>()
        val indicators = ArrayList<IndicatorDef>()
        val tables = ArrayList<TableDef>()
        val curves = ArrayList<CurveDef>()
        val datalog = ArrayList<DatalogEntry>()
        val settingGroups = ArrayList<SettingGroup>()

        // parse state
        var currentPage = 0
        val lastOffsetByPage = HashMap<Int, Int>()
        var gaugeCategory: String? = null
        var pendingTable: MutableMap<String, String>? = null
        var pendingCurve: MutableMap<String, String>? = null

        fun build(symbols: Set<String>): IniDefinition {
            var section = ""
            pre.lines.forEachIndexed { idx, rawLine ->
                val line = stripComment(rawLine).trim()
                if (line.isEmpty()) return@forEachIndexed
                if (line.startsWith("[") && line.contains("]")) {
                    flushTableOrCurve()
                    section = line.substring(1, line.indexOf(']')).trim()
                    return@forEachIndexed
                }
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEachIndexed
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                try {
                    when (section) {
                        "MegaTune", "TunerStudio" -> header(key, value)
                        "Constants" -> constantLine(key, value, idx + 1)
                        "PcVariables" -> pcVariableLine(key, value, idx + 1)
                        "OutputChannels" -> outputChannelLine(key, value, idx + 1)
                        "GaugeConfigurations" -> gaugeLine(key, value)
                        "FrontPage" -> frontPageLine(key, value)
                        "TableEditor" -> tableLine(key, value)
                        "CurveEditor" -> curveLine(key, value)
                        "Datalog" -> datalogLine(key, value)
                        "SettingGroups" -> settingGroupLine(key, value)
                    }
                } catch (e: Exception) {
                    warnings += "linha ${idx + 1}: ${e.message}"
                }
            }
            flushTableOrCurve()

            val n = pageSizes.size
            fun pad(list: List<String>) = List(n) { list.getOrElse(it) { "" } }
            return IniDefinition(
                signature = signature,
                queryCommand = queryCommand,
                versionInfoCommand = versionInfo,
                endianBig = endianBig,
                pageSizes = pageSizes.toList(),
                pageIdentifiers = pad(pageIdentifiers),
                pageReadCommands = pad(pageRead),
                pageChunkWriteCommands = pad(pageChunkWrite.ifEmpty { pageValueWrite }),
                burnCommands = pad(burn),
                crc32CheckCommands = pad(crc),
                messageEnvelope = envelope,
                blockingFactor = blockingFactor,
                blockReadTimeoutMs = blockReadTimeout,
                ochBlockSize = ochBlockSize,
                ochGetCommand = ochGetCommand,
                constants = constants,
                pcVariables = pcVars,
                outputChannels = channels,
                gauges = gauges,
                frontPageGauges = frontGauges.values.toList(),
                indicators = indicators,
                tables = tables,
                curves = curves,
                datalog = datalog,
                settingGroups = settingGroups,
                referencedSymbols = pre.referencedSymbols,
                activeSymbols = symbols,
                warnings = warnings,
            )
        }

        private fun commandList(value: String) = splitFields(value).map { unquote(it) }

        private fun header(key: String, value: String) {
            when (key) {
                "signature" -> signature = unquote(splitFields(value).first())
                "queryCommand" -> queryCommand = unquote(splitFields(value).first())
                "versionInfo" -> versionInfo = unquote(splitFields(value).first())
            }
        }

        // ------------------------------------------------------------ [Constants]

        private fun constantLine(key: String, value: String, line: Int) {
            val f = splitFields(value)
            val cls = f.firstOrNull()?.lowercase()
            if (cls in CLASS_WORDS) {
                parseConstant(key, f, currentPage, hasOffset = true, line = line)?.let { constants[key] = it }
                return
            }
            when (key) {
                "page" -> currentPage = (intOf(f.first()) ?: 1) - 1
                "endianness" -> endianBig = !value.trim().startsWith("little", ignoreCase = true)
                "nPages" -> Unit
                "pageSize" -> { pageSizes.clear(); f.forEach { pageSizes += intOf(it) ?: 0 } }
                "pageIdentifier" -> pageIdentifiers = commandList(value)
                "pageReadCommand" -> pageRead = commandList(value)
                "pageChunkWrite" -> pageChunkWrite = commandList(value)
                "pageValueWrite" -> pageValueWrite = commandList(value)
                "burnCommand" -> burn = commandList(value)
                "crc32CheckCommand" -> crc = commandList(value)
                "messageEnvelopeFormat" -> envelope = unquote(f.first())
                "blockingFactor" -> blockingFactor = intOf(f.first()) ?: blockingFactor
                "blockReadTimeout" -> blockReadTimeout = intOf(f.first()) ?: blockReadTimeout
                "endianess" -> endianBig = !value.trim().startsWith("little", ignoreCase = true)
            }
        }

        private fun pcVariableLine(key: String, value: String, line: Int) {
            val f = splitFields(value)
            if (f.firstOrNull()?.lowercase() in CLASS_WORDS) {
                parseConstant(key, f, -1, hasOffset = false, line = line)?.let { pcVars[key] = it }
            }
        }

        private fun parseConstant(name: String, f: List<String>, page: Int, hasOffset: Boolean, line: Int): Constant? {
            val cls = f[0].lowercase()
            val type = DataType.parse(f.getOrNull(1) ?: "")
            var i = 2
            val offset: Int = if (hasOffset) {
                val o = f.getOrNull(i++)?.trim() ?: return null
                if (o.equals("lastOffset", ignoreCase = true)) lastOffsetByPage[page] ?: 0
                else intOf(o) ?: run { warnings += "linha $line: offset inválido em $name"; return null }
            } else 0

            val c: Constant? = when (cls) {
                "scalar" -> {
                    type ?: return null
                    Constant.Scalar(
                        name, page, offset, type,
                        units = unquote(f.getOrNull(i) ?: ""),
                        scale = NumOrExpr.parse(f.getOrNull(i + 1)) ?: NumOrExpr.of(1.0),
                        translate = doubleOf(f.getOrNull(i + 2)) ?: 0.0,
                        lo = NumOrExpr.parse(f.getOrNull(i + 3)),
                        hi = NumOrExpr.parse(f.getOrNull(i + 4)),
                        digits = intOf(f.getOrNull(i + 5)) ?: 0,
                        translateExpr = f.getOrNull(i + 2)?.trim()?.takeIf { it.startsWith("{") },
                    )
                }
                "array" -> {
                    type ?: return null
                    val shape = f.getOrNull(i) ?: return null
                    val dims = Regex("\\d+").findAll(shape).map { it.value.toInt() }.toList()
                    if (dims.isEmpty()) return null
                    val is2d = dims.size >= 2
                    val cols = dims[0]
                    val rows = if (is2d) dims[1] else 1
                    Constant.Array(
                        name, page, offset, type, cols, rows, is2d,
                        units = unquote(f.getOrNull(i + 1) ?: ""),
                        scale = NumOrExpr.parse(f.getOrNull(i + 2)) ?: NumOrExpr.of(1.0),
                        translate = doubleOf(f.getOrNull(i + 3)) ?: 0.0,
                        lo = NumOrExpr.parse(f.getOrNull(i + 4)),
                        hi = NumOrExpr.parse(f.getOrNull(i + 5)),
                        digits = intOf(f.getOrNull(i + 6)) ?: 0,
                    )
                }
                "bits" -> {
                    type ?: return null
                    val spec = f.getOrNull(i) ?: return null
                    val nums = Regex("\\d+").findAll(spec).map { it.value.toInt() }.toList()
                    if (nums.isEmpty()) return null
                    val lo = nums[0]
                    val hi = nums.getOrElse(1) { lo }
                    val opts = expandOptions(f.drop(i + 1))
                    Constant.Bits(name, page, offset, type, lo, hi, opts)
                }
                "string" -> {
                    val len = intOf(f.getOrNull(i)) ?: return null
                    Constant.Text(name, page, offset, len)
                }
                else -> null
            }
            if (c != null && page >= 0) {
                val end = offset + c.byteSize
                if (end > (lastOffsetByPage[page] ?: 0)) lastOffsetByPage[page] = end
            }
            return c
        }

        /** Unquotes bit option labels and expands `$name` references to `#define` lists. */
        private fun expandOptions(raw: List<String>, seen: Set<String> = emptySet()): List<String> {
            val out = ArrayList<String>()
            for (r in raw) {
                val t = r.trim()
                if (t.startsWith("$")) {
                    val name = t.substring(1)
                    val def = pre.defines[name]
                    if (def != null && name !in seen) out += expandOptions(splitFields(def), seen + name)
                } else if (t.isNotEmpty()) out += unquote(t)
            }
            return out
        }

        // ------------------------------------------------------------ [OutputChannels]

        private fun outputChannelLine(key: String, value: String, line: Int) {
            val f = splitFields(value)
            when (key) {
                "ochBlockSize" -> { ochBlockSize = intOf(f.first()) ?: 0; return }
                "ochGetCommand" -> { ochGetCommand = unquote(f.first()); return }
            }
            val first = f.firstOrNull() ?: return
            if (first.startsWith("{")) {
                channels[key] = OutputChannel.Formula(key, first.removeSurrounding("{", "}").trim(), unquote(f.getOrNull(1) ?: ""))
                return
            }
            when (first.lowercase()) {
                "scalar" -> {
                    val type = DataType.parse(f.getOrNull(1) ?: "") ?: return
                    val off = intOf(f.getOrNull(2)) ?: return
                    channels[key] = OutputChannel.Scalar(
                        key, type, off, unquote(f.getOrNull(3) ?: ""),
                        NumOrExpr.parse(f.getOrNull(4)) ?: NumOrExpr.of(1.0),
                        doubleOf(f.getOrNull(5)) ?: 0.0,
                    )
                }
                "bits" -> {
                    val type = DataType.parse(f.getOrNull(1) ?: "") ?: return
                    val off = intOf(f.getOrNull(2)) ?: return
                    val nums = Regex("\\d+").findAll(f.getOrNull(3) ?: "").map { it.value.toInt() }.toList()
                    if (nums.isEmpty()) return
                    channels[key] = OutputChannel.Bits(key, type, off, nums[0], nums.getOrElse(1) { nums[0] })
                }
                "array" -> Unit // not used for display
                else -> warnings += "linha $line: canal desconhecido $key"
            }
        }

        // ------------------------------------------------------------ [GaugeConfigurations]

        private fun gaugeLine(key: String, value: String) {
            if (key == "gaugeCategory") { gaugeCategory = unquote(value); return }
            var f = splitFields(value)
            // Tolerate a missing comma between the channel and the title: `knockRetard "Title", ...`
            if (f.isNotEmpty() && f[0].contains('"')) {
                val q = f[0].indexOf('"')
                f = listOf(f[0].substring(0, q).trim(), f[0].substring(q).trim()) + f.drop(1)
            }
            if (f.size < 5) return
            gauges[key] = GaugeDef(
                name = key, channel = f[0], title = unquote(f[1]), units = unquote(f[2]),
                lo = NumOrExpr.parse(f.getOrNull(3)), hi = NumOrExpr.parse(f.getOrNull(4)),
                loDanger = NumOrExpr.parse(f.getOrNull(5)), loWarn = NumOrExpr.parse(f.getOrNull(6)),
                hiWarn = NumOrExpr.parse(f.getOrNull(7)), hiDanger = NumOrExpr.parse(f.getOrNull(8)),
                valueDigits = intOf(f.getOrNull(9)) ?: 0, labelDigits = intOf(f.getOrNull(10)) ?: 0,
                category = gaugeCategory,
            )
        }

        // ------------------------------------------------------------ [FrontPage]

        private fun frontPageLine(key: String, value: String) {
            val gaugeIdx = Regex("^gauge(\\d+)$").find(key)?.groupValues?.get(1)?.toIntOrNull()
            if (gaugeIdx != null) { frontGauges[gaugeIdx] = value.trim(); return }
            if (key == "indicator") {
                val f = splitFields(value)
                if (f.size < 3) return
                indicators += IndicatorDef(
                    expr = f[0].removeSurrounding("{", "}").trim(),
                    offLabel = unquote(f[1]), onLabel = unquote(f[2]),
                    offBg = f.getOrElse(3) { "white" }, offFg = f.getOrElse(4) { "black" },
                    onBg = f.getOrElse(5) { "green" }, onFg = f.getOrElse(6) { "black" },
                )
            }
        }

        // ------------------------------------------------------------ [TableEditor] / [CurveEditor]

        private fun flushTableOrCurve() {
            pendingTable?.let { t ->
                val x = splitFields(t["xBins"] ?: "")
                val y = splitFields(t["yBins"] ?: "")
                val z = splitFields(t["zBins"] ?: "")
                if (x.isNotEmpty() && y.isNotEmpty() && z.isNotEmpty()) {
                    tables += TableDef(
                        id = t["id"]!!, mapId = t["mapId"] ?: "", title = t["title"] ?: t["id"]!!,
                        xBins = x[0], xChannel = x.getOrNull(1)?.takeIf { it.isNotBlank() },
                        yBins = y[0], yChannel = y.getOrNull(1)?.takeIf { it.isNotBlank() },
                        zBins = z[0],
                        upDownLabel = splitFields(t["upDownLabel"] ?: "").map { unquote(it) },
                    )
                }
            }
            pendingTable = null
            pendingCurve?.let { c ->
                val x = splitFields(c["xBins"] ?: "")
                val y = splitFields(c["yBins"] ?: "")
                val labels = splitFields(c["columnLabel"] ?: "").map { unquote(it) }
                curves += CurveDef(
                    id = c["id"]!!, title = c["title"] ?: c["id"]!!,
                    xBins = x.getOrNull(0), xChannel = x.getOrNull(1)?.takeIf { it.isNotBlank() },
                    yBins = y.getOrNull(0),
                    xLabel = labels.getOrNull(0), yLabel = labels.getOrNull(1),
                )
            }
            pendingCurve = null
        }

        private fun tableLine(key: String, value: String) {
            if (key == "table") {
                flushTableOrCurve()
                val f = splitFields(value)
                pendingTable = mutableMapOf("id" to f[0], "mapId" to f.getOrElse(1) { "" }, "title" to unquote(f.getOrElse(2) { f[0] }))
                return
            }
            pendingTable?.let { if (key !in it) it[key] = value }
        }

        private fun curveLine(key: String, value: String) {
            if (key == "curve") {
                flushTableOrCurve()
                val f = splitFields(value)
                pendingCurve = mutableMapOf("id" to f[0], "title" to unquote(f.getOrElse(1) { f[0] }))
                return
            }
            // Curves may have several yBins lines (multiple lines on one graph); keep the first.
            pendingCurve?.let { if (key !in it) it[key] = value }
        }

        // ------------------------------------------------------------ [Datalog] / [SettingGroups]

        private fun datalogLine(key: String, value: String) {
            if (key != "entry") return
            val f = splitFields(value)
            if (f.size < 2) return
            datalog += DatalogEntry(
                channel = f[0], label = unquote(f[1]), type = f.getOrElse(2) { "float" },
                format = unquote(f.getOrElse(3) { "%.3f" }),
                condition = f.getOrNull(4)?.removeSurrounding("{", "}")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        private fun settingGroupLine(key: String, value: String) {
            val f = splitFields(value)
            when (key) {
                "settingGroup" -> settingGroups += SettingGroup(f[0], unquote(f.getOrElse(1) { f[0] }), emptyList())
                "settingOption" -> {
                    val g = settingGroups.removeLastOrNull() ?: return
                    settingGroups += g.copy(options = g.options + (f[0] to unquote(f.getOrElse(1) { f[0] })))
                }
            }
        }
    }
}
