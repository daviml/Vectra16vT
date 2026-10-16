package dev.turbodash.core.map

import kotlin.math.cos
import kotlin.math.hypot

/** A driving route as a polyline (degrees), with the length of each point from the start. */
class RoutePath(val lats: DoubleArray, val lons: DoubleArray) {
    init { require(lats.size == lons.size && lats.size >= 2) }

    private val mx = 111_320.0 * cos(Math.toRadians(lats[0]))
    private val my = 110_540.0

    /** Distance from the start to each point, meters. */
    val along = DoubleArray(lats.size).also { a ->
        for (i in 1 until lats.size) a[i] = a[i - 1] + hypot((lons[i] - lons[i - 1]) * mx, (lats[i] - lats[i - 1]) * my)
    }
    val length get() = along.last()

    /** Where a position falls on the route. */
    class Fix(val offRouteM: Double, val remainingM: Double, val segment: Int)

    /**
     * Closest point of the route to (lat, lon). [fromSegment] limits the search to what is ahead
     * of the last fix (minus a little), so a route that passes the same street twice is followed in order.
     */
    fun locate(lat: Double, lon: Double, fromSegment: Int = 0): Fix {
        var best = Double.MAX_VALUE; var bestSeg = 0; var bestT = 0.0
        val start = (fromSegment - 3).coerceAtLeast(0)
        for (i in start until lats.size - 1) {
            val ax = (lons[i] - lon) * mx; val ay = (lats[i] - lat) * my
            val bx = (lons[i + 1] - lon) * mx; val by = (lats[i + 1] - lat) * my
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + dx * t, ay + dy * t)
            if (d < best) { best = d; bestSeg = i; bestT = t }
        }
        val at = along[bestSeg] + (along[bestSeg + 1] - along[bestSeg]) * bestT
        return Fix(best, (length - at).coerceAtLeast(0.0), bestSeg)
    }
}

/** Remaining distance as shown under the mini map: "850 m", "3,2 km", "12 km". */
fun formatDistance(m: Double): String = when {
    m < 1000 -> "${(Math.round(m / 10.0) * 10)} m"
    m < 10_000 -> "%.1f km".format(java.util.Locale.forLanguageTag("pt-BR"), m / 1000)
    else -> "${Math.round(m / 1000)} km"
}
