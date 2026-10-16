package dev.turbodash.app.nav

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import dev.turbodash.app.ecu.GpsSpeed
import dev.turbodash.app.ui.dash.RoadTiles
import dev.turbodash.core.map.RoutePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.floor

/** A destination: a saved shortcut ("Casa") or a search result. */
data class Place(val name: String, val address: String, val lat: Double, val lon: Double)

/** The route being driven. [route] is null while it is being calculated or when that failed. */
data class ActiveRoute(
    val destination: Place,
    val route: RoutePath? = null,
    val remainingM: Double? = null,
    /** Index of the route segment the car is on; what is behind it is no longer drawn. */
    val segment: Int = 0,
    val failed: Boolean = false,
)

/**
 * Turn-by-turn-free navigation: saved places, address search (OpenStreetMap Nominatim, Photon as
 * backup) and car routes (OSRM). Follows the GPS, recalculates when the car leaves the route and
 * ends by itself on arrival. Call from the main thread.
 */
class Navigation(private val context: Context, private val gps: GpsSpeed) {

    private val prefs = context.getSharedPreferences("nav", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _favorites = MutableStateFlow(load("favorites"))
    val favorites: StateFlow<List<Place>> = _favorites.asStateFlow()
    private val _recents = MutableStateFlow(load("recents"))
    val recents: StateFlow<List<Place>> = _recents.asStateFlow()
    private val _active = MutableStateFlow<ActiveRoute?>(null)
    val active: StateFlow<ActiveRoute?> = _active.asStateFlow()

    private var follow: Job? = null
    private var routing: Job? = null
    private var routedAt = 0L
    private var offCount = 0

    // ------------------------------------------------------------------ places

    fun saveFavorite(name: String, place: Place) {
        val p = place.copy(name = name.trim())
        _favorites.value = (_favorites.value.filterNot { it.name.equals(p.name, ignoreCase = true) } + p)
        save("favorites", _favorites.value)
    }

    fun removeFavorite(place: Place) {
        _favorites.value = _favorites.value - place
        save("favorites", _favorites.value)
    }

    private fun load(key: String): List<Place> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(o.getString("name"), o.optString("address"), o.getDouble("lat"), o.getDouble("lon"))
        }
    } catch (_: Exception) { emptyList() }

    private fun save(key: String, list: List<Place>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("address", it.address).put("lat", it.lat).put("lon", it.lon)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    // ------------------------------------------------------------------ search

    /** Addresses matching [query], closest to the car first when the position is known. */
    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val near = gps.location.value
        // The same name shows once (a square, its bus stops, its outline...): the first is the best match.
        (nominatim(query, near) ?: photon(query, near) ?: emptyList())
            .distinctBy { it.address }
    }

    private fun nominatim(q: String, near: Location?): List<Place>? {
        var url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&countrycodes=br&accept-language=pt-BR&q=" +
            URLEncoder.encode(q, "UTF-8")
        if (near != null) {
            // Prefer results around the car (not exclusive: other cities still show up).
            url += "&viewbox=${near.longitude - 0.4},${near.latitude + 0.4},${near.longitude + 0.4},${near.latitude - 0.4}"
        }
        val a = JSONArray(http(url) ?: return null)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val parts = o.getString("display_name").split(", ")
            Place("", parts.take(4).joinToString(", "), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }

    private fun photon(q: String, near: Location?): List<Place>? {
        var url = "https://photon.komoot.io/api/?limit=8&q=" + URLEncoder.encode(q, "UTF-8")
        if (near != null) url += "&lat=${near.latitude}&lon=${near.longitude}"
        val features = JSONObject(http(url) ?: return null).getJSONArray("features")
        return (0 until features.length()).map { i ->
            val f = features.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val street = listOf(p.optString("name"), p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.distinct()
            val address = (street + listOf(p.optString("district"), p.optString("city"))).filter { it.isNotBlank() }.joinToString(", ")
            Place("", address, c.getDouble(1), c.getDouble(0))
        }
    }

    // ------------------------------------------------------------------ route

    fun go(place: Place) {
        stop()
        _recents.value = (listOf(place.copy(name = "")) + _recents.value.filterNot { it.lat == place.lat && it.lon == place.lon }).take(6)
        save("recents", _recents.value)
        _active.value = ActiveRoute(place)
        gps.acquire() // keep the position coming even when the dashboard is not on screen
        offCount = 0
        follow = scope.launch { gps.location.collect { it?.let(::onLocation) } }
    }

    fun stop() {
        if (_active.value == null) return
        follow?.cancel(); routing?.cancel()
        follow = null; routing = null
        _active.value = null
        gps.release()
    }

    private fun onLocation(loc: Location) {
        val a = _active.value ?: return
        val dest = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, a.destination.lat, a.destination.lon, dest)
        if (dest[0] < 35f) { stop(); return } // arrived
        val route = a.route
        if (route == null) {
            // First route, or retry after a failure (every 20 s).
            if (routing?.isActive != true && SystemClock.elapsedRealtime() - routedAt > (if (a.failed) 20_000 else 0)) calculate(loc)
            return
        }
        val fix = route.locate(loc.latitude, loc.longitude, a.segment)
        _active.value = a.copy(remainingM = fix.remainingM, segment = fix.segment)
        // Left the route: only trust real GPS fixes, and wait a few in a row (GPS jumps near buildings).
        val precise = loc.provider == LocationManager.GPS_PROVIDER && loc.accuracy < 30f
        offCount = if (precise && fix.offRouteM > 50) offCount + 1 else 0
        if (offCount >= 3 && routing?.isActive != true && SystemClock.elapsedRealtime() - routedAt > 10_000) {
            offCount = 0
            calculate(loc)
        }
    }

    private fun calculate(from: Location) {
        val a = _active.value ?: return
        routedAt = SystemClock.elapsedRealtime()
        routing = scope.launch {
            val route = withContext(Dispatchers.IO) { osrm(from, a.destination) }
            val now = _active.value ?: return@launch
            if (now.destination != a.destination) return@launch
            if (route == null) {
                _active.value = now.copy(failed = true)
                return@launch
            }
            val fix = route.locate(from.latitude, from.longitude)
            _active.value = now.copy(route = route, remainingM = fix.remainingM, segment = fix.segment, failed = false)
            prefetchStreets(route)
        }
    }

    private fun osrm(from: Location, to: Place): RoutePath? {
        // Start in the direction the car is already going (no U-turn suggested right after a reroute).
        val bearing = if (from.hasBearing() && from.hasSpeed() && from.speed > 2.5f) "&bearings=${from.bearing.toInt()},60;" else ""
        val path = "/route/v1/driving/${from.longitude},${from.latitude};${to.lon},${to.lat}?overview=full&geometries=geojson$bearing"
        for (server in listOf("https://router.project-osrm.org", "https://routing.openstreetmap.de/routed-car")) {
            try {
                val json = JSONObject(http(server + path) ?: continue)
                if (json.optString("code") != "Ok") continue
                val coords = json.getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                if (coords.length() < 2) continue
                val lats = DoubleArray(coords.length()); val lons = DoubleArray(coords.length())
                for (i in 0 until coords.length()) {
                    val c = coords.getJSONArray(i)
                    lons[i] = c.getDouble(0); lats[i] = c.getDouble(1)
                }
                return RoutePath(lats, lons)
            } catch (_: Exception) {
                // try the next server
            }
        }
        return null
    }

    /** Downloads the streets along the whole route now, so the map keeps working if the signal drops. */
    private fun prefetchStreets(route: RoutePath) {
        val tiles = LinkedHashSet<Pair<Int, Int>>()
        for (i in route.lats.indices) {
            val (x, y) = RoadTiles.tileXY(route.lats[i], route.lons[i])
            tiles += floor(x).toInt() to floor(y).toInt()
        }
        tiles.forEach { (x, y) -> RoadTiles.prefetch(context, x, y) }
    }

    private fun http(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "TurboDash/0.2 (Android)")
        }
        return try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
