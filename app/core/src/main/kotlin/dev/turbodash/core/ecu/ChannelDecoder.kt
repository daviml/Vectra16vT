package dev.turbodash.core.ecu

import dev.turbodash.core.expr.EvalContext
import dev.turbodash.core.expr.Expression
import dev.turbodash.core.ini.Constant
import dev.turbodash.core.ini.DataType
import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.ini.OutputChannel
import dev.turbodash.core.tune.Tune

/** One decoded realtime frame. [time] is seconds since the session started. */
class Snapshot(val time: Double, val values: Map<String, Double>) {
    operator fun get(name: String): Double? = values[name]
}

/**
 * Turns the raw output block into named values, including formula channels
 * (`dutyCycle1`, `lambda1`, ...). Formulas may reference tune constants, so a [Tune] is used
 * when available (read from the ECU or loaded from an msq).
 */
class ChannelDecoder(private val ini: IniDefinition, var tune: Tune?) {

    private val rawChannels = ini.outputChannels.values.filter { it !is OutputChannel.Formula }
    private val formulas = ini.outputChannels.values.filterIsInstance<OutputChannel.Formula>()

    fun decode(block: ByteArray, time: Double): Snapshot {
        val values = HashMap<String, Double>(ini.outputChannels.size * 2)
        val t = tune
        val tuneCtx = t?.evalContext
        for (ch in rawChannels) {
            when (ch) {
                is OutputChannel.Scalar -> {
                    val raw = readRaw(block, ch.offset, ch.type) ?: continue
                    val r = if (ch.type.float) java.lang.Float.intBitsToFloat(raw.toInt()).toDouble() else raw.toDouble()
                    val scale = ch.scale.number
                        ?: ch.scale.expr?.let { e -> tuneCtx?.let { Expression.compileOrZero(e).evalNumber(it) } }
                        ?: 1.0
                    values[ch.name] = (r + ch.translate) * scale
                }
                is OutputChannel.Bits -> {
                    val raw = readRaw(block, ch.offset, ch.type) ?: continue
                    val n = ch.bitHi - ch.bitLo + 1
                    values[ch.name] = ((raw ushr ch.bitLo) and ((1L shl n) - 1)).toDouble()
                }
                is OutputChannel.Formula -> Unit
            }
        }

        // Formulas are evaluated lazily with memoization so they can reference each other in any order.
        val inProgress = HashSet<String>()
        val ctx = object : EvalContext {
            override fun value(name: String): Double? {
                values[name]?.let { return it }
                val f = ini.outputChannels[name] as? OutputChannel.Formula
                if (f != null) {
                    if (!inProgress.add(name)) return 0.0 // cycle guard
                    val v = Expression.compileOrZero(f.expr).evalNumber(this).let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
                    inProgress.remove(name)
                    values[name] = v
                    return v
                }
                return t?.numericValue(name)
            }
            override fun options(name: String): List<String>? = (ini.constantOrPc(name) as? Constant.Bits)?.options
            override fun timeNow(): Double = time
        }
        for (f in formulas) ctx.value(f.name)
        return Snapshot(time, values)
    }

    private fun readRaw(b: ByteArray, offset: Int, type: DataType): Long? {
        if (offset < 0 || offset + type.size > b.size) return null
        var v = 0L
        for (i in 0 until type.size) {
            val idx = if (ini.endianBig) offset + i else offset + type.size - 1 - i
            v = (v shl 8) or (b[idx].toLong() and 0xFF)
        }
        if (!type.float && type.signed) {
            val bits = type.size * 8
            if (v and (1L shl (bits - 1)) != 0L) v -= (1L shl bits)
        }
        return v
    }

    /** Units of a channel, resolving `{ bitStringValue(...) }` style units against the tune. */
    fun unitsOf(channel: String): String {
        val u = ini.outputChannels[channel]?.units ?: return ""
        return tune?.unitsOf(u) ?: if (u.startsWith("{")) "" else u
    }
}
