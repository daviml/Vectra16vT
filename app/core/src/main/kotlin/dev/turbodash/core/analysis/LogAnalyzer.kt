package dev.turbodash.core.analysis

import dev.turbodash.core.alerts.AlertConfig
import dev.turbodash.core.log.LogData
import java.util.Locale
import kotlin.math.abs

/** Summary of one channel over a log. */
data class ChannelStats(val label: String, val min: Double, val max: Double, val avg: Double, val timeAtMax: Double)

/** A stretch of time where a condition held. */
data class Episode(val start: Double, val end: Double, val worst: Double, val detail: String) {
    val duration get() = end - start
}

data class LogReport(
    val durationSeconds: Double,
    val samples: Int,
    val sampleRateHz: Double,
    val stats: List<ChannelStats>,
    val leanUnderLoad: List<Episode>,
    val hotAir: List<Episode>,
    val hotCoolant: List<Episode>,
    val egoSaturatedPct: Double?,
    val maxDuty: Double?,
    val boostSeen: Boolean,
    val findings: List<String>,
)

/**
 * Offline analysis of a `.msl` log (ours or TunerStudio's). Works by column label so it does
 * not need the INI; labels follow the MS2/MS3 TunerStudio conventions.
 */
object LogAnalyzer {

    private val TIME = arrayOf("Time")
    private val RPM = arrayOf("RPM")
    private val MAP = arrayOf("MAP")
    private val TPS = arrayOf("TPS", "Throttle")
    private val MAT = arrayOf("MAT", "IAT")
    private val CLT = arrayOf("CLT")
    private val LAMBDA = arrayOf("Lambda", "Lambda1")
    private val AFR = arrayOf("AFR", "AFR1")
    private val TARGET = arrayOf("AFR Target 1", "AFR Target", "Lambda Target")
    private val EGO = arrayOf("EGO cor1", "EGO cor", "Gego")
    private val DUTY = arrayOf("DutyCycle1", "Duty Cycle 1")
    private val ADV = arrayOf("SPK: Spark Advance", "Spark Advance", "Advance")
    private val BARO = arrayOf("Barometro", "Barometer", "Baro")

    fun analyze(log: LogData, config: AlertConfig = AlertConfig()): LogReport {
        val rows = log.rows
        val iTime = log.columnAny(*TIME)
        val iRpm = log.columnAny(*RPM)
        val iMap = log.columnAny(*MAP)
        val iTps = log.columnAny(*TPS)
        val iMat = log.columnAny(*MAT)
        val iClt = log.columnAny(*CLT)
        val iLambda = log.columnAny(*LAMBDA)
        val iAfr = log.columnAny(*AFR)
        val iTarget = log.columnAny(*TARGET)
        val iEgo = log.columnAny(*EGO)
        val iDuty = log.columnAny(*DUTY)
        val iAdv = log.columnAny(*ADV)
        val iBaro = log.columnAny(*BARO)

        fun time(r: DoubleArray, idx: Int) = if (iTime >= 0) r[iTime] else idx * 0.1
        fun v(r: DoubleArray, i: Int) = if (i >= 0) r[i].takeUnless { it.isNaN() } else null
        fun lambda(r: DoubleArray) = v(r, iLambda) ?: v(r, iAfr)?.let { it / 14.7 }
        fun target(r: DoubleArray): Double? {
            val t = v(r, iTarget) ?: return null
            if (t <= 0) return null
            // "AFR Target" columns are in AFR; a value below 3 is already lambda.
            return if (t > 3) t / 14.7 else t
        }

        val duration = if (rows.size > 1) time(rows.last(), rows.size - 1) - time(rows.first(), 0) else 0.0
        val rate = if (duration > 0) (rows.size - 1) / duration else 0.0

        val stats = ArrayList<ChannelStats>()
        fun stat(label: String, idx: Int) {
            if (idx < 0) return
            var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE; var sum = 0.0; var n = 0; var tMax = 0.0
            rows.forEachIndexed { k, r ->
                val x = r[idx]
                if (x.isNaN()) return@forEachIndexed
                if (x < mn) mn = x
                if (x > mx) { mx = x; tMax = time(r, k) }
                sum += x; n++
            }
            if (n > 0) stats += ChannelStats(label, mn, mx, sum / n, tMax)
        }
        stat("RPM", iRpm); stat("MAP (kPa)", iMap); stat("TPS (%)", iTps)
        stat("Ar admitido (°C)", iMat); stat("Água (°C)", iClt)
        stat("Lambda", if (iLambda >= 0) iLambda else iAfr)
        stat("Correção sonda (%)", iEgo); stat("Duty bico (%)", iDuty); stat("Avanço (°)", iAdv)

        val lean = episodes(rows, ::time) { r ->
            val map = v(r, iMap) ?: return@episodes null
            val rpm = v(r, iRpm) ?: 0.0
            val tps = v(r, iTps) ?: 100.0
            val l = lambda(r) ?: return@episodes null
            val limit = target(r)?.let { it + config.leanMarginLambda } ?: config.leanAbsoluteLambda
            if (rpm > 400 && tps > 5 && map >= config.leanMinMapKpa && l > limit && l < 1.6) l else null
        }.map { it.copy(detail = "lambda máx %.2f com carga".format(Locale.US, it.worst)) }

        val hotAir = episodes(rows, ::time) { r -> v(r, iMat)?.takeIf { it >= config.matWarnC } }
            .map { it.copy(detail = "ar até %.0f °C".format(Locale.US, it.worst)) }
        val hotClt = episodes(rows, ::time) { r -> v(r, iClt)?.takeIf { it >= config.cltWarnC } }
            .map { it.copy(detail = "água até %.0f °C".format(Locale.US, it.worst)) }

        var egoSat: Double? = null
        if (iEgo >= 0) {
            var running = 0; var sat = 0
            for (r in rows) {
                if ((v(r, iRpm) ?: 0.0) < 400) continue
                val e = v(r, iEgo) ?: continue
                running++
                if (abs(e - 100) >= config.egoSaturationPct) sat++
            }
            if (running > 0) egoSat = 100.0 * sat / running
        }

        val maxDuty = stats.firstOrNull { it.label.startsWith("Duty") }?.max
        val boostSeen = iMap >= 0 && rows.any { r ->
            val baro = v(r, iBaro)?.takeIf { it > 50 } ?: 100.0
            (v(r, iMap) ?: 0.0) > baro + 5
        }

        val findings = ArrayList<String>()
        if (rows.isEmpty()) findings += "Log vazio."
        stats.firstOrNull { it.label.startsWith("Ar") }?.let {
            when {
                it.max >= config.matCritC -> findings += "Ar admitido chegou a %.0f °C (crítico). Sem intercooler isso favorece detonação/pré-ignição.".format(Locale.US, it.max)
                it.max >= config.matWarnC -> findings += "Ar admitido chegou a %.0f °C (alto).".format(Locale.US, it.max)
                else -> findings += "Ar admitido ficou até %.0f °C (ok).".format(Locale.US, it.max)
            }
        }
        stats.firstOrNull { it.label.startsWith("Água") }?.let {
            if (it.max >= config.cltWarnC) findings += "Água chegou a %.0f °C — verificar arrefecimento.".format(Locale.US, it.max)
        }
        if (lean.isNotEmpty()) findings += "${lean.size} trecho(s) de mistura pobre com carga — o mais longo durou %.1f s.".format(Locale.US, lean.maxOf { it.duration })
        egoSat?.let { if (it > 10) findings += "Correção da sonda no limite em %.0f%% do tempo — VE precisa de ajuste.".format(Locale.US, it) }
        maxDuty?.let { if (it >= config.dutyWarnPct) findings += "Duty dos bicos chegou a %.0f%% — perto do limite.".format(Locale.US, it) }
        if (boostSeen) findings += "O MAP passou da pressão atmosférica: houve pressão positiva no coletor."

        return LogReport(duration, rows.size, rate, stats, lean, hotAir, hotClt, egoSat, maxDuty, boostSeen, findings)
    }

    /** Groups consecutive rows where [metric] is non-null; worst = max metric. Gaps under 1 s are merged. */
    private fun episodes(rows: List<DoubleArray>, time: (DoubleArray, Int) -> Double, metric: (DoubleArray) -> Double?): List<Episode> {
        val out = ArrayList<Episode>()
        var start = -1.0; var end = -1.0; var worst = -Double.MAX_VALUE
        rows.forEachIndexed { k, r ->
            val m = metric(r)
            val t = time(r, k)
            if (m != null) {
                if (start < 0 || t - end > 1.0) {
                    if (start >= 0) out += Episode(start, end, worst, "")
                    start = t; worst = m
                }
                end = t
                if (m > worst) worst = m
            }
        }
        if (start >= 0) out += Episode(start, end, worst, "")
        return out
    }

    /** Plain-text report in Portuguese for sharing. */
    fun toText(r: LogReport): String = buildString {
        appendLine("Relatório do log")
        appendLine("Duração: %.1f min, %d amostras (%.1f Hz)".format(Locale.US, r.durationSeconds / 60, r.samples, r.sampleRateHz))
        appendLine()
        appendLine("Conclusões:")
        r.findings.forEach { appendLine("• $it") }
        appendLine()
        appendLine("Canais (mín / média / máx):")
        r.stats.forEach { appendLine("  %-22s %8.2f %8.2f %8.2f".format(Locale.US, it.label, it.min, it.avg, it.max)) }
        if (r.leanUnderLoad.isNotEmpty()) {
            appendLine()
            appendLine("Mistura pobre com carga:")
            r.leanUnderLoad.take(20).forEach { appendLine("  %.1f s – %.1f s: %s".format(Locale.US, it.start, it.end, it.detail)) }
        }
    }
}
