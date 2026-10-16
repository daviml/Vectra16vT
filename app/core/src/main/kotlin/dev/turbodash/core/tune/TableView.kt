package dev.turbodash.core.tune

import dev.turbodash.core.ini.Constant
import dev.turbodash.core.ini.TableDef

/**
 * A 3D table (VE, ignition, lambda target...) resolved from a [Tune]: axes plus z values,
 * with z indexed as `z[y * xs.size + x]` (the INI's X-order).
 */
class TableView(
    val def: TableDef,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val z: DoubleArray,
    val zDigits: Int,
    val xUnits: String,
    val yUnits: String,
    val zUnits: String,
) {
    fun at(xi: Int, yi: Int) = z[yi * xs.size + xi]

    val min get() = z.minOrNull() ?: 0.0
    val max get() = z.maxOrNull() ?: 0.0

    /** Bilinear interpolation, clamped at the table edges (how the ECU looks up tables). */
    fun interpolate(x: Double, y: Double): Double {
        val (x0, x1, fx) = locate(xs, x)
        val (y0, y1, fy) = locate(ys, y)
        val a = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx
        val b = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx
        return a + (b - a) * fy
    }

    /** Fractional cell position of a point, used to draw the live cursor. */
    fun position(x: Double, y: Double): Pair<Double, Double> {
        val (x0, _, fx) = locate(xs, x)
        val (y0, _, fy) = locate(ys, y)
        return (x0 + fx) to (y0 + fy)
    }

    companion object {
        /** Returns null when the INI table references constants this tune does not have. */
        fun from(tune: Tune, def: TableDef): TableView? {
            val ini = tune.ini
            val xc = ini.constants[def.xBins] as? Constant.Array ?: return null
            val yc = ini.constants[def.yBins] as? Constant.Array ?: return null
            val zc = ini.constants[def.zBins] as? Constant.Array ?: return null
            val xs = tune.array(xc)
            val ys = tune.array(yc)
            val z = tune.array(zc)
            if (xs.isEmpty() || ys.isEmpty() || z.size < xs.size * ys.size) return null
            return TableView(
                def, xs, ys, z, zc.digits,
                tune.unitsOf(xc.units), tune.unitsOf(yc.units), tune.unitsOf(zc.units),
            )
        }

        internal fun locate(axis: DoubleArray, v: Double): Triple<Int, Int, Double> {
            if (axis.size == 1 || v <= axis[0]) return Triple(0, 0, 0.0)
            val last = axis.size - 1
            if (v >= axis[last]) return Triple(last, last, 0.0)
            for (i in 0 until last) {
                val a = axis[i]
                val b = axis[i + 1]
                if (v in a..b) {
                    val f = if (b == a) 0.0 else (v - a) / (b - a)
                    return Triple(i, i + 1, f)
                }
            }
            return Triple(last, last, 0.0)
        }
    }
}
