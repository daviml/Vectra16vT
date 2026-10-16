package dev.turbodash.core.ini

/** Storage type of a value inside an ECU page or the realtime block. */
enum class DataType(val size: Int, val signed: Boolean, val float: Boolean = false) {
    U08(1, false), S08(1, true),
    U16(2, false), S16(2, true),
    U32(4, false), S32(4, true),
    F32(4, true, float = true);

    companion object {
        fun parse(s: String): DataType? = entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
    }
}

/** A number in the INI that may instead be a `{ expression }` evaluated against the tune. */
data class NumOrExpr(val number: Double?, val expr: String?) {
    companion object {
        fun parse(field: String?): NumOrExpr? {
            val f = field?.trim() ?: return null
            if (f.isEmpty()) return null
            if (f.startsWith("{")) return NumOrExpr(null, f.removeSurrounding("{", "}").trim())
            return f.toDoubleOrNull()?.let { NumOrExpr(it, null) }
        }
        fun of(v: Double) = NumOrExpr(v, null)
    }
}

/** Calibration constant (page data) or PC variable (page = -1). */
sealed class Constant {
    abstract val name: String
    /** Zero-based page index, -1 for PcVariables. */
    abstract val page: Int
    abstract val offset: Int
    abstract val type: DataType
    abstract val byteSize: Int

    data class Scalar(
        override val name: String, override val page: Int, override val offset: Int, override val type: DataType,
        val units: String, val scale: NumOrExpr, val translate: Double,
        val lo: NumOrExpr?, val hi: NumOrExpr?, val digits: Int,
        /** Set when the INI gives the translate as an expression; such constants are derived views of other bytes. */
        val translateExpr: String? = null,
    ) : Constant() {
        override val byteSize get() = type.size
    }

    data class Array(
        override val name: String, override val page: Int, override val offset: Int, override val type: DataType,
        /** INI shape `[cols x rows]`; a 1-D array has rows = 1 internally but is written to msq with cols=1. */
        val cols: Int, val rows: Int, val is2d: Boolean,
        val units: String, val scale: NumOrExpr, val translate: Double,
        val lo: NumOrExpr?, val hi: NumOrExpr?, val digits: Int,
    ) : Constant() {
        val count get() = cols * rows
        override val byteSize get() = type.size * count
    }

    data class Bits(
        override val name: String, override val page: Int, override val offset: Int, override val type: DataType,
        val bitLo: Int, val bitHi: Int, val options: List<String>,
    ) : Constant() {
        override val byteSize get() = type.size
        val bitCount get() = bitHi - bitLo + 1
    }

    data class Text(
        override val name: String, override val page: Int, override val offset: Int, val length: Int,
    ) : Constant() {
        override val type get() = DataType.U08
        override val byteSize get() = length
    }
}

/** Realtime value, either decoded from the output block or computed from a formula. */
sealed class OutputChannel {
    abstract val name: String
    abstract val units: String

    data class Scalar(
        override val name: String, val type: DataType, val offset: Int,
        override val units: String, val scale: NumOrExpr, val translate: Double,
    ) : OutputChannel()

    data class Bits(
        override val name: String, val type: DataType, val offset: Int, val bitLo: Int, val bitHi: Int,
    ) : OutputChannel() {
        override val units get() = ""
    }

    data class Formula(override val name: String, val expr: String, override val units: String) : OutputChannel()
}

data class GaugeDef(
    val name: String, val channel: String, val title: String, val units: String,
    val lo: NumOrExpr?, val hi: NumOrExpr?,
    val loDanger: NumOrExpr?, val loWarn: NumOrExpr?, val hiWarn: NumOrExpr?, val hiDanger: NumOrExpr?,
    val valueDigits: Int, val labelDigits: Int, val category: String?,
)

data class IndicatorDef(
    val expr: String, val offLabel: String, val onLabel: String,
    val offBg: String, val offFg: String, val onBg: String, val onFg: String,
)

data class TableDef(
    val id: String, val mapId: String, val title: String,
    val xBins: String, val xChannel: String?,
    val yBins: String, val yChannel: String?,
    val zBins: String, val upDownLabel: List<String>,
)

data class CurveDef(
    val id: String, val title: String,
    val xBins: String?, val xChannel: String?,
    val yBins: String?,
    val xLabel: String?, val yLabel: String?,
)

data class DatalogEntry(
    val channel: String, val label: String, val type: String, val format: String, val condition: String?,
)

data class SettingGroup(val name: String, val title: String, val options: List<Pair<String, String>>)

/** Everything the app needs from a TunerStudio INI, for one set of active project symbols. */
class IniDefinition(
    val signature: String,
    val queryCommand: String,
    val versionInfoCommand: String?,
    val endianBig: Boolean,
    val pageSizes: List<Int>,
    val pageIdentifiers: List<String>,
    val pageReadCommands: List<String>,
    val pageChunkWriteCommands: List<String>,
    val burnCommands: List<String>,
    val crc32CheckCommands: List<String>,
    val messageEnvelope: String?,
    val blockingFactor: Int,
    val blockReadTimeoutMs: Int,
    val ochBlockSize: Int,
    val ochGetCommand: String,
    val constants: Map<String, Constant>,
    val pcVariables: Map<String, Constant>,
    val outputChannels: Map<String, OutputChannel>,
    val gauges: Map<String, GaugeDef>,
    val frontPageGauges: List<String>,
    val indicators: List<IndicatorDef>,
    val tables: List<TableDef>,
    val curves: List<CurveDef>,
    val datalog: List<DatalogEntry>,
    val settingGroups: List<SettingGroup>,
    /** Symbols tested by `#if` anywhere in the file (for the project-settings screen). */
    val referencedSymbols: Set<String>,
    val activeSymbols: Set<String>,
    val warnings: List<String>,
) {
    val nPages get() = pageSizes.size
    val usesEnvelope get() = messageEnvelope?.startsWith("msEnvelope", ignoreCase = true) == true

    /** Constant or PC variable by name. */
    fun constantOrPc(name: String): Constant? = constants[name] ?: pcVariables[name]
}
