package dev.turbodash.app.ecu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone GPS: vehicle speed (km/h) and position/bearing for the mini map, using the platform
 * LocationManager so it works without Google Play Services. Falls back to distance/time when a
 * fix has no speed.
 *
 * Several parts of the app need it (ECU session for the log, dashboard for the map), so it runs
 * while at least one of them holds it ([acquire]/[release]). Call on the main thread.
 */
@androidx.compose.runtime.Stable
class GpsSpeed(private val context: Context) : LocationListener {

    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile private var kmh: Double? = null
    @Volatile private var lastFixAt = 0L
    private var previous: Location? = null
    private var running = false
    private var users = 0

    private val _location = MutableStateFlow<Location?>(null)
    /** Latest fix, for the mini map. */
    val location: StateFlow<Location?> = _location.asStateFlow()

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Fine or approximate location (approximate still shows the map, but no GPS speed). */
    fun hasPermission() =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Location switched on in the phone's quick settings. */
    fun isEnabled() = lm?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } ?: false

    fun acquire() {
        users++
        start()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** Starts updates if someone needs them (e.g. right after the permission was granted). */
    fun refresh() {
        if (users > 0) start()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running || lm == null || !hasPermission()) return
        // GPS for speed and precise position; the network provider gives a position within seconds
        // (indoors, or while the GPS is still searching satellites).
        val providers = buildList {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        for (p in providers) {
            try {
                lm.requestLocationUpdates(p, if (p == LocationManager.GPS_PROVIDER) 250L else 2000L, 0f, this, Looper.getMainLooper())
                running = true
                if (_location.value == null) lm.getLastKnownLocation(p)?.let { _location.value = it }
            } catch (_: Exception) {
                // Provider missing on this phone: use the others.
            }
        }
    }

    private fun stop() {
        if (running) lm?.removeUpdates(this)
        running = false
        previous = null
        kmh = null
    }

    /** Current speed, or null without a recent fix (older than 3 s). */
    fun current(): Double? = if (SystemClock.elapsedRealtime() - lastFixAt > 3000) null else kmh

    override fun onLocationChanged(location: Location) {
        if (location.provider != LocationManager.GPS_PROVIDER) {
            // Coarse fix: only for the map, and only while there is no recent GPS fix.
            if (SystemClock.elapsedRealtime() - lastFixAt > 5000) _location.value = location
            return
        }
        val v = if (location.hasSpeed()) location.speed * 3.6 else previous?.let { p ->
            val dt = (location.time - p.time) / 1000.0
            if (dt > 0) p.distanceTo(location) / dt * 3.6 else null
        }
        previous = location
        lastFixAt = SystemClock.elapsedRealtime()
        // GPS jitters around 1-2 km/h when stopped.
        kmh = v?.let { if (it < 2.0) 0.0 else it }
        _location.value = location
    }

    override fun onProviderDisabled(provider: String) { kmh = null }
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
