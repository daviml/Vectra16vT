package dev.turbodash.core.map

/**
 * Streets from a Mapbox Vector Tile (OpenMapTiles schema, e.g. OpenFreeMap): only the
 * "transportation" layer's line geometry, nothing else (no names, no buildings).
 * A small hand-written protobuf reader keeps the app free of extra dependencies.
 */
object MvtRoads {
    /** 0 = main road, 1 = medium, 2 = local street. */
    const val CLASSES = 3

    /** One street line; coordinates are fractions of the tile (0..1, slightly beyond at the edges). */
    class Road(val cls: Int, val xs: FloatArray, val ys: FloatArray)

    private val classOf = mapOf(
        "motorway" to 0, "trunk" to 0, "primary" to 0,
        "secondary" to 1, "tertiary" to 1,
        "minor" to 2, "busway" to 2,
    )

    fun decode(bytes: ByteArray): List<Road> {
        val out = ArrayList<Road>()
        val r = Reader(bytes, 0, bytes.size)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 3 && wire == 2) {
                val len = r.varint().toInt()
                layer(Reader(bytes, r.pos, r.pos + len), out)
                r.pos += len
            } else r.skip(wire)
        }
        return out
    }

    private fun layer(r: Reader, out: MutableList<Road>) {
        var name: String? = null
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<String?>()
        val features = ArrayList<Pair<Int, Int>>() // byte ranges, decoded once keys/values are known
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> {
                    name = r.string()
                    if (name != "transportation") return
                }
                field == 2 && wire == 2 -> { val len = r.varint().toInt(); features += r.pos to r.pos + len; r.pos += len }
                field == 3 && wire == 2 -> keys += r.string()
                field == 4 && wire == 2 -> { val len = r.varint().toInt(); values += stringValue(Reader(r.buf, r.pos, r.pos + len)); r.pos += len }
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        if (name != "transportation") return
        val classKey = keys.indexOf("class")
        if (classKey < 0) return
        for ((start, end) in features) feature(Reader(r.buf, start, end), classKey, values, extent.toFloat(), out)
    }

    private fun stringValue(r: Reader): String? {
        var s: String? = null
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 1 && wire == 2) s = r.string() else r.skip(wire)
        }
        return s
    }

    private fun feature(r: Reader, classKey: Int, values: List<String?>, extent: Float, out: MutableList<Road>) {
        var type = 0
        var cls = -1
        var geomStart = -1; var geomEnd = -1
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 2 && wire == 2 -> {
                    val end = r.pos + r.varint().toInt()
                    while (r.pos < end) {
                        val k = r.varint().toInt(); val v = r.varint().toInt()
                        if (k == classKey) cls = values.getOrNull(v)?.let { classOf[it] } ?: -1
                    }
                }
                field == 3 && wire == 0 -> type = r.varint().toInt()
                field == 4 && wire == 2 -> { val len = r.varint().toInt(); geomStart = r.pos; geomEnd = r.pos + len; r.pos += len }
                else -> r.skip(wire)
            }
        }
        if (type != 2 || cls < 0 || geomStart < 0) return // lines only
        // Geometry: commands MoveTo(1)/LineTo(2) with zigzag deltas; each MoveTo starts a new line.
        val g = Reader(r.buf, geomStart, geomEnd)
        var x = 0; var y = 0
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        fun flush() {
            if (xs.size >= 2) out += Road(cls, xs.toFloatArray(), ys.toFloatArray())
            xs.clear(); ys.clear()
        }
        while (g.more()) {
            val cmd = g.varint().toInt()
            val id = cmd and 7; val count = cmd ushr 3
            when (id) {
                1, 2 -> repeat(count) {
                    if (id == 1) flush()
                    x += zigzag(g.varint()); y += zigzag(g.varint())
                    xs += x / extent; ys += y / extent
                }
                else -> {} // ClosePath: not used by lines
            }
        }
        flush()
    }

    private fun zigzag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        fun tag(): Pair<Int, Int> { val t = varint().toInt(); return (t ushr 3) to (t and 7) }
        fun string(): String { val len = varint().toInt(); val s = String(buf, pos, len, Charsets.UTF_8); pos += len; return s }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val len = varint().toInt(); pos += len }
                5 -> pos += 4
                else -> error("wire type $wire")
            }
        }
    }
}
