package dev.turbodash.core.tune

import dev.turbodash.core.expr.EvalContext
import dev.turbodash.core.expr.Expression
import dev.turbodash.core.ini.Constant
import dev.turbodash.core.ini.DataType
import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.ini.NumOrExpr
import kotlin.math.roundToLong

/**
 * A calibration as raw ECU page bytes, interpreted through an [IniDefinition].
 *
 * User value = (raw + translate) * scale, as in TunerStudio.
 */
class Tune(val ini: IniDefinition, pages: List<ByteArray>? = null) {

    val pages: List<ByteArray> = pages?.map { it.copyOf() } ?: ini.pageSizes.map { ByteArray(it) }

    /** Values of [IniDefinition.pcVariables] (not stored in the ECU). Bits hold the option index. */
    val pcValues = HashMap<String, Double>()

    /** Lets formulas such as scale expressions see constants of this tune. */
    val evalContext: EvalContext = object : EvalContext {
        override fun value(name: String): Double? = numericValue(name)
        override fun options(name: String): List<String>? = (ini.constantOrPc(name) as? Constant.Bits)?.options
    }

    // ------------------------------------------------------------------ raw access

    fun readRaw(page: Int, offset: Int, type: DataType): Long {
        val b = pages[page]
        if (offset < 0 || offset + type.size > b.size) return 0
        var v = 0L
        for (i in 0 until type.size) {
            val idx = if (ini.endianBig) offset + i else offset + type.size - 1 - i
            v = (v shl 8) or (b[idx].toLong() and 0xFF)
        }
        if (type.float) return v
        if (type.signed) {
            val bits = type.size * 8
            if (v and (1L shl (bits - 1)) != 0L) v -= (1L shl bits)
        }
        return v
    }

    fun writeRaw(page: Int, offset: Int, type: DataType, value: Long) {
        val b = pages[page]
        if (offset < 0 || offset + type.size > b.size) return
        for (i in 0 until type.size) {
            val shift = 8 * (type.size - 1 - i)
            val idx = if (ini.endianBig) offset + i else offset + type.size - 1 - i
            b[idx] = ((value shr shift) and 0xFF).toByte()
        }
    }

    private fun clampRaw(type: DataType, v: Long): Long {
        if (type.float) return v
        val bits = type.size * 8
        val min = if (type.signed) -(1L shl (bits - 1)) else 0L
        val max = if (type.signed) (1L shl (bits - 1)) - 1 else (1L shl bits) - 1
        return v.coerceIn(min, max)
    }

    private fun rawToUser(type: DataType, raw: Long, scale: Double, translate: Double): Double {
        val r = if (type.float) java.lang.Float.intBitsToFloat(raw.toInt()).toDouble() else raw.toDouble()
        return (r + translate) * scale
    }

    private fun userToRaw(type: DataType, user: Double, scale: Double, translate: Double): Long {
        val r = if (scale == 0.0) 0.0 else user / scale - translate
        return if (type.float) java.lang.Float.floatToIntBits(r.toFloat()).toLong() else clampRaw(type, r.roundToLong())
    }

    fun resolve(n: NumOrExpr?, default: Double): Double = when {
        n == null -> default
        n.number != null -> n.number
        n.expr != null -> Expression.compileOrZero(n.expr).evalNumber(evalContext)
        else -> default
    }

    // ------------------------------------------------------------------ typed access

    /** Numeric value of a constant as seen by expressions: scalars scaled, bits as raw index. */
    fun numericValue(name: String): Double? {
        pcValues[name]?.let { return it }
        return when (val c = ini.constantOrPc(name)) {
            is Constant.Scalar -> if (c.page < 0) null else scalar(c)
            is Constant.Bits -> if (c.page < 0) null else bitsIndex(c).toDouble()
            is Constant.Array -> if (c.page < 0) null else array(c).firstOrNull()
            else -> null
        }
    }

    fun scalar(c: Constant.Scalar): Double =
        rawToUser(c.type, readRaw(c.page, c.offset, c.type), resolve(c.scale, 1.0), c.translate)

    fun setScalar(c: Constant.Scalar, value: Double) =
        writeRaw(c.page, c.offset, c.type, userToRaw(c.type, value, resolve(c.scale, 1.0), c.translate))

    fun array(c: Constant.Array): DoubleArray {
        val scale = resolve(c.scale, 1.0)
        return DoubleArray(c.count) { i ->
            rawToUser(c.type, readRaw(c.page, c.offset + i * c.type.size, c.type), scale, c.translate)
        }
    }

    fun setArray(c: Constant.Array, values: DoubleArray) {
        val scale = resolve(c.scale, 1.0)
        for (i in 0 until minOf(values.size, c.count)) {
            writeRaw(c.page, c.offset + i * c.type.size, c.type, userToRaw(c.type, values[i], scale, c.translate))
        }
    }

    fun bitsIndex(c: Constant.Bits): Int {
        if (c.page < 0) return pcValues[c.name]?.toInt() ?: 0
        val raw = readRaw(c.page, c.offset, c.type)
        return ((raw ushr c.bitLo) and ((1L shl c.bitCount) - 1)).toInt()
    }

    fun bitsLabel(c: Constant.Bits): String = c.options.getOrElse(bitsIndex(c)) { bitsIndex(c).toString() }

    fun setBitsIndex(c: Constant.Bits, index: Int) {
        if (c.page < 0) { pcValues[c.name] = index.toDouble(); return }
        val mask = ((1L shl c.bitCount) - 1) shl c.bitLo
        val raw = readRaw(c.page, c.offset, c.type) and 0xFFFFFFFFL
        val nv = (raw and mask.inv()) or ((index.toLong() shl c.bitLo) and mask)
        writeRaw(c.page, c.offset, c.type, nv)
    }

    fun text(c: Constant.Text): String {
        val b = pages[c.page]
        val end = minOf(b.size, c.offset + c.length)
        return String(b, c.offset, maxOf(0, end - c.offset), Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
    }

    fun scalar(name: String): Double? = (ini.constants[name] as? Constant.Scalar)?.let { scalar(it) }
    fun array(name: String): DoubleArray? = (ini.constants[name] as? Constant.Array)?.let { array(it) }
    fun bitsLabel(name: String): String? = (ini.constants[name] as? Constant.Bits)?.let { bitsLabel(it) }

    /** Display units of a constant; units given as `{ expression }` are evaluated. */
    fun unitsOf(units: String): String =
        if (units.startsWith("{")) Expression.compileOrZero(units.removeSurrounding("{", "}")).eval(evalContext).let {
            if (it is String) it else ""
        } else units

    fun copy(): Tune = Tune(ini, pages).also { it.pcValues.putAll(pcValues) }
}
