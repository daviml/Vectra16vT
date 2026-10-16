package dev.turbodash.app.ecu

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.turbodash.core.alerts.Alert
import dev.turbodash.core.alerts.Severity

/** Sound and vibration for critical alerts: once when one appears, then every few seconds while it lasts. */
class AlertNotifier(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) { null }

    private var activeCritical = emptySet<String>()
    private var lastBeep = 0L

    fun update(alerts: List<Alert>) {
        val critical = alerts.filter { it.severity == Severity.CRITICAL }.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val isNew = (critical - activeCritical).isNotEmpty()
        if (critical.isNotEmpty() && (isNew || now - lastBeep > 4000)) {
            lastBeep = now
            tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 600)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        }
        activeCritical = critical
    }

    fun clear() {
        activeCritical = emptySet()
    }
}
