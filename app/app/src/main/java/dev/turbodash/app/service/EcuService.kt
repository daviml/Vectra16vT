package dev.turbodash.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.turbodash.app.MainActivity

/**
 * Keeps the process alive while connected to the car, so logging continues with the screen off.
 * The session itself lives in [dev.turbodash.app.ecu.SessionManager].
 */
class EcuService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Conexão com a ECU", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("${dev.turbodash.app.Brand.name} conectado")
            .setContentText("Lendo a ECU e gravando log")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        // Location type only when allowed, otherwise Android 14+ refuses to start the service.
        val gpsAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (gpsAllowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        ServiceCompat.startForeground(this, 1, notification, type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "ecu"
    }
}
