package dev.turbodash.app.ui.dash

import android.content.Context
import androidx.compose.ui.graphics.Path
import dev.turbodash.core.map.MvtRoads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Street geometry only (no names) from OpenFreeMap vector tiles at zoom 14, kept in memory and on
 * disk so an area already driven works without internet. [version] changes whenever a tile arrives.
 */
object RoadTiles {
    const val ZOOM = 14
    private const val TILEJSON = "https://tiles.openfreemap.org/planet"
    private const val CELLS = 8 // each tile split in 8x8 cells (~280 m) so only what is on screen is drawn

    /** Part of a tile: one prebuilt path per street class, in tile fractions, with its bounding box. */
    class Cell(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float, val paths: Array<Path?>)

    private val memory = android.util.LruCache<Long, List<Cell>>(16)
    private val loading = java.util.Collections.synchronizedSet(HashSet<Long>())
    private val failedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    @Volatile private var template: String? = null
    @Volatile private var cleaned = false

    /** True when the last tile that had to come from the network failed. */
    @Volatile var offline = false
        private set

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /** The tile if ready; otherwise starts loading it (disk, then network) and returns null. */
    fun get(context: Context, x: Int, y: Int): List<Cell>? {
        val k = key(x, y)
        memory.get(k)?.let { return it }
        if (!cleaned) { cleaned = true; scope.launch { File(context.cacheDir, "tiles").deleteRecursively() } } // old image tiles (0.1.7-0.1.9)
        if (System.currentTimeMillis() - (failedAt[k] ?: 0L) < 30_000 || !loading.add(k)) return null
        val dir = File(context.cacheDir, "roads")
        scope.launch {
            try {
                val file = File(dir, "${ZOOM}_${x}_${y}.pbf")
                val bytes = if (file.isFile) file.readBytes() else download(dir, x, y)?.also { b ->
                    dir.mkdirs()
                    writeAtomic(file, b)
                }
                if (bytes != null) {
                    memory.put(k, build(MvtRoads.decode(bytes)))
                    failedAt.remove(k)
                    offline = false
                } else {
                    failedAt[k] = System.currentTimeMillis()
                    offline = true
                }
            } catch (_: Exception) {
                failedAt[k] = System.currentTimeMillis()
                offline = true
            } finally {
                loading.remove(k)
                _version.value++
            }
        }
        return null
    }

    /** Saves the tile to disk if it is not there yet (streets along a new route); does not load it. */
    fun prefetch(context: Context, x: Int, y: Int) {
        val dir = File(context.cacheDir, "roads")
        scope.launch {
            val file = File(dir, "${ZOOM}_${x}_${y}.pbf")
            if (file.isFile) return@launch
            try {
                download(dir, x, y)?.let { dir.mkdirs(); writeAtomic(file, it) }
            } catch (_: Exception) {
                // best effort; the map loads it again when needed
            }
        }
    }

    /** Write then rename, so the map never reads a half-written tile. */
    private fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".${Thread.currentThread().id}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) tmp.delete()
    }

    private fun build(roads: List<MvtRoads.Road>): List<Cell> {
        class Acc { var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; val paths = arrayOfNulls<Path>(MvtRoads.CLASSES) }
        val cells = HashMap<Int, Acc>()
        for (r in roads) {
            val x0 = r.xs.min(); val x1 = r.xs.max(); val y0 = r.ys.min(); val y1 = r.ys.max()
            val cx = (((x0 + x1) / 2) * CELLS).toInt().coerceIn(0, CELLS - 1)
            val cy = (((y0 + y1) / 2) * CELLS).toInt().coerceIn(0, CELLS - 1)
            val acc = cells.getOrPut(cy * CELLS + cx) { Acc() }
            acc.minX = minOf(acc.minX, x0); acc.maxX = maxOf(acc.maxX, x1)
            acc.minY = minOf(acc.minY, y0); acc.maxY = maxOf(acc.maxY, y1)
            val path = acc.paths[r.cls] ?: Path().also { acc.paths[r.cls] = it }
            path.moveTo(r.xs[0], r.ys[0])
            for (i in 1 until r.xs.size) path.lineTo(r.xs[i], r.ys[i])
        }
        return cells.values.map { Cell(it.minX, it.minY, it.maxX, it.maxY, it.paths) }
    }

    private fun download(dir: File, x: Int, y: Int): ByteArray? {
        val url = (template ?: tileTemplate(dir) ?: return null)
            .replace("{z}", ZOOM.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        val bytes = get(url)
        // The tile URL carries the data release date; an old one stops working after a while.
        if (bytes == null) File(dir, "template.txt").delete().also { template = null }
        return bytes
    }

    /** Current tile URL from the TileJSON, remembered on disk for a week. */
    private fun tileTemplate(dir: File): String? {
        val saved = File(dir, "template.txt")
        if (saved.isFile && System.currentTimeMillis() - saved.lastModified() < 7 * 86_400_000L) {
            return saved.readText().also { template = it }
        }
        val json = get(TILEJSON)?.toString(Charsets.UTF_8) ?: return if (saved.isFile) saved.readText() else null
        val t = JSONObject(json).getJSONArray("tiles").getString(0)
        dir.mkdirs()
        saved.writeText(t)
        template = t
        return t
    }

    private fun get(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "TurboDash/0.1")
        }
        return try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }

    /** Web Mercator position in tile units at [ZOOM]. */
    fun tileXY(lat: Double, lon: Double): Pair<Double, Double> {
        val n = (1 shl ZOOM).toDouble()
        val r = Math.toRadians(lat)
        return (lon + 180.0) / 360.0 * n to (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
    }

    /** Ground meters across one tile at [lat]. */
    fun tileMeters(lat: Double) = 40_075_016.686 * cos(Math.toRadians(lat)) / (1 shl ZOOM)
}
