package dev.turbodash.app.ui.dash

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import dev.turbodash.app.nav.Navigation
import dev.turbodash.core.map.formatDistance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turbodash.app.ecu.GpsSpeed
import dev.turbodash.app.ui.Palette
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Compass heading in degrees (magnetic), or null without a rotation sensor. Uses the direction
 * the back of the phone points when it stands on a mount (the car's forward direction), and the
 * top edge when the phone lies flat.
 */
@Composable
private fun rememberCompass(context: Context): State<Float?> {
    val heading = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val r = FloatArray(9)
        var sx = 0.0; var sy = 0.0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                // Columns of R are the device axes in world coordinates (east, north, up).
                var east = -r[2].toDouble(); var north = -r[5].toDouble()      // back of the phone
                if (hypot(east, north) < 0.5) { east = r[1].toDouble(); north = r[4].toDouble() } // lying flat: top edge
                val n = hypot(east, north).takeIf { it > 1e-3 } ?: return
                // Low-pass on the unit vector (no jump at 0/360 degrees).
                sx = sx * 0.85 + east / n * 0.15; sy = sy * 0.85 + north / n * 0.15
                val deg = ((Math.toDegrees(atan2(sx, sy)) + 360) % 360).toFloat()
                if (heading.value.let { it == null || angleDiff(it, deg) > 1f }) heading.value = deg
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return heading
}

private fun angleDiff(a: Float, b: Float): Float { val d = ((a - b + 540) % 360) - 180; return kotlin.math.abs(d) }

// Main road, medium, local street.
private val roadColors = arrayOf(Color(0xFF8E97A6), Color(0xFF717A88), Color(0xFF545C69))
private val roadWidths = floatArrayOf(6f, 4.5f, 3f)
private val routeColor = Color(0xFF1D4ED8)

/** GTA-style mini map: dark background, streets only, an arrow pointing where the car goes. */
@Composable
fun MiniMap(gps: GpsSpeed, nav: Navigation, onOpenDestinations: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        gps.acquire()
        onDispose { gps.release() }
    }
    // Ask for location right here (the first time the map shows), not only in Ajustes.
    var permitted by remember { mutableStateOf(gps.hasPermission()) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = gps.hasPermission()
        if (permitted) gps.refresh()
    }
    val locationPerms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    LaunchedEffect(Unit) { if (!permitted) askLocation.launch(locationPerms) }
    var enabled by remember { mutableStateOf(gps.isEnabled()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            enabled = gps.isEnabled()
            if (!permitted && gps.hasPermission()) { permitted = true; gps.refresh() } // granted in system settings
        }
    }
    val loc by gps.location.collectAsStateWithLifecycle()
    val compass by rememberCompass(context)
    // Redraw when a tile arrives.
    val tiles by RoadTiles.version.collectAsStateWithLifecycle()
    var heading by remember { mutableFloatStateOf(0f) }
    val active by nav.active.collectAsStateWithLifecycle()
    // Route in tile units, converted once per new route.
    val routeTiles = remember(active?.route) {
        active?.route?.let { r ->
            val xs = DoubleArray(r.lats.size); val ys = DoubleArray(r.lats.size)
            for (i in xs.indices) { val (x, y) = RoadTiles.tileXY(r.lats[i], r.lons[i]); xs[i] = x; ys[i] = y }
            xs to ys
        }
    }

    // Heading: GPS course when moving (reliable in a car), compass + declination when slow.
    val l = loc
    val speedKmh = if (l != null && l.hasSpeed()) l.speed * 3.6f else 0f
    val gpsHeading = if (l != null && l.hasBearing() && speedKmh >= 8f) l.bearing else null
    val declination = remember(l?.latitude?.toInt(), l?.longitude?.toInt()) {
        l?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination } ?: 0f
    }
    val target = gpsHeading ?: compass?.let { (it + declination + 360) % 360 }
    if (target != null && angleDiff(target, heading) > 1.5f) heading = target

    Box(modifier.background(Palette.surface).clickable { if (permitted) onOpenDestinations() else askLocation.launch(locationPerms) }) {
        Canvas(Modifier.fillMaxSize()) {
            tiles // read here so a new tile redraws the canvas
            val cx = size.width / 2
            val cy = size.height * 0.64f
            // Look further ahead at speed: ~250 m stopped, ~450 m at 70 km/h above the arrow.
            val range = (250f + speedKmh * 2.8f).coerceAtMost(450f)
            val ppm = cy / range
            if (l != null) {
                val (tx, ty) = RoadTiles.tileXY(l.latitude, l.longitude)
                val scale = (ppm * RoadTiles.tileMeters(l.latitude)).toFloat() // screen px per tile
                val reach = hypot(size.width, size.height) / scale // view radius, in tiles
                val visible = buildList {
                    for (y in floor(ty - reach).toInt()..floor(ty + reach).toInt())
                        for (x in floor(tx - reach).toInt()..floor(tx + reach).toInt()) {
                            val cells = RoadTiles.get(context, x, y) ?: continue
                            // Offset of this tile from the car, small enough for float precision.
                            val ox = (x - tx).toFloat(); val oy = (y - ty).toFloat()
                            for (c in cells) {
                                if (c.maxX + ox < -reach || c.minX + ox > reach || c.maxY + oy < -reach || c.minY + oy > reach) continue
                                add(Triple(ox, oy, c))
                            }
                        }
                }
                withTransform({
                    // Tile units around the car -> screen, rotated so the travel direction points up.
                    translate(cx, cy)
                    rotate(-heading, Offset.Zero)
                    scale(scale, scale, Offset.Zero)
                }) {
                    // Local streets first, main roads on top.
                    for (cls in roadColors.indices.reversed()) {
                        val stroke = Stroke(roadWidths[cls].dp.toPx() / scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        for ((ox, oy, c) in visible) {
                            val path = c.paths[cls] ?: continue
                            translate(ox, oy) { drawPath(path, roadColors[cls], style = stroke) }
                        }
                    }
                    // The route ahead of the car, on top of the streets.
                    routeTiles?.let { (xs, ys) ->
                        val from = (active?.segment ?: 0).coerceIn(0, xs.size - 2)
                        val path = Path()
                        for (i in from until xs.size) {
                            val px = (xs[i] - tx).toFloat(); val py = (ys[i] - ty).toFloat()
                            if (i == from) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(path, routeColor, style = Stroke(7.dp.toPx() / scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
            // The car: an arrow pointing up (direction of travel).
            val a = 11.dp.toPx()
            val arrow = Path().apply {
                moveTo(cx, cy - a * 1.25f)
                lineTo(cx + a * 0.85f, cy + a * 0.9f)
                lineTo(cx, cy + a * 0.35f)
                lineTo(cx - a * 0.85f, cy + a * 0.9f)
                close()
            }
            drawPath(arrow, Palette.background, style = Stroke(4.dp.toPx(), join = StrokeJoin.Round))
            drawPath(arrow, Palette.accent)
            if (l == null) drawCircle(Palette.muted.copy(alpha = 0.3f), a * 2.2f, Offset(cx, cy), style = Stroke(1.5f))
        }
        // Only text on the map while navigating: distance left to the destination.
        active?.remainingM?.let { m ->
            Text(
                formatDistance(m), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Palette.background.copy(alpha = 0.75f)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        val msg = when {
            !permitted -> "Toque para permitir a localização"
            !enabled -> "Ligue a localização do celular"
            l == null -> "Procurando GPS…"
            RoadTiles.offline && tiles >= 0 -> "Sem internet para o mapa"
            else -> null
        }
        if (msg != null) {
            Text(msg, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
        }
    }
}
