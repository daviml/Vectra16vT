package dev.turbodash.core.sim

import dev.turbodash.core.ini.Constant
import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.log.LogData
import dev.turbodash.core.tune.TableView
import dev.turbodash.core.tune.Tune
import kotlin.math.exp
import kotlin.math.sin

enum class Scenario(val label: String) {
    CITY_HEAT("Trânsito no calor (sem boost)"),
    BOOST_PULLS("Puxadas com boost"),
}

/**
 * Synthetic engine driven by the real tune: VE, ignition and lambda target come from the
 * tune's own tables, so the dashboard and the map cursor behave like on the car.
 *
 * [timeScale] speeds up slow effects (warm-up, heat soak) so they show in a short demo.
 */
class SyntheticEngine(
    private val tune: Tune,
    private val scenario: Scenario,
    private val timeScale: Double = 10.0,
) : EngineSource {

    private val ini: IniDefinition = tune.ini
    private val ve = table("veTable1", "frpm_table1", "fmap_table1")
    private val adv = table("advanceTable1", "srpm_table1", "smap_table1")
    private val afr = table("afrTable1", "arpm_table1", "amap_table1")
    private val reqFuel = tune.scalar("reqFuel") ?: 8.0
    private val injOpen = tune.scalar("injOpen") ?: 1.0
    private val matTemps = tune.array("matTemps")
    private val matRetard = tune.array("matRetard")
    private val wueTemps = tune.array("tempTable")
    private val wueBins = tune.array("wueBins")

    private fun table(z: String, x: String, y: String): TableView? {
        val zc = ini.constants[z] as? Constant.Array ?: return null
        return TableView.from(tune, dev.turbodash.core.ini.TableDef(z, z, z, x, null, y, null, zc.name, emptyList()))
    }

    private fun noise(t: Double, f: Double, amp: Double) = amp * sin(t * f) * sin(t * f * 0.37 + 1.3)

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f.coerceIn(0.0, 1.0)

    /**
     * 30 s of hard driving, boost for ~12 s of it: 2nd and 3rd gear pulls with a shift,
     * lift-off, a short cruise and back to idle.
     */
    private fun pulls(t: Double): Triple<Double, Double, Double> {
        val p = t % 30.0
        val peak = 165.0
        return when {
            p < 3 -> Triple(950 + noise(t, 3.1, 25.0), 36 + noise(t, 2.3, 2.0), 0.0)
            p < 5 -> Triple(lerp(1200.0, 2800.0, (p - 3) / 2), lerp(60.0, 95.0, (p - 3) / 2), 40.0)
            // 2nd gear: the turbo spools in the first ~2.5 s.
            p < 11 -> Triple(lerp(2800.0, 6200.0, (p - 5) / 6), lerp(95.0, peak, (p - 5) / 2.5) + noise(t, 4.0, 2.0), 100.0)
            p < 12 -> Triple(lerp(6200.0, 4300.0, p - 11), lerp(peak, 75.0, p - 11), 0.0) // shift
            p < 17 -> Triple(lerp(4300.0, 6200.0, (p - 12) / 5), lerp(120.0, peak, (p - 12) / 1.2) + noise(t, 4.0, 2.0), 100.0)
            p < 21 -> Triple(lerp(6200.0, 2500.0, (p - 17) / 4), 25.0, 0.0) // lift-off, overrun
            p < 27 -> Triple(2300 + noise(t, 0.7, 100.0), 50 + noise(t, 0.9, 4.0), 12.0)
            else -> Triple(lerp(2300.0, 950.0, (p - 27) / 3), 28.0, 0.0)
        }
    }

    /** Drive cycle phase → (rpm, map kPa, tps %). */
    private fun drive(t: Double): Triple<Double, Double, Double> {
        if (scenario == Scenario.BOOST_PULLS) return pulls(t)
        val cycle = 60.0
        val n = (t / cycle).toInt()
        val p = t % cycle
        val pull = n % 3 == 2 // every third lap: full throttle up to atmospheric pressure
        val peakMap = 98.0
        return when {
            p < 10 -> Triple(950 + noise(t, 3.1, 25.0), 36 + noise(t, 2.3, 2.0), 0.0)
            p < 18 -> Triple(lerp(1200.0, 3500.0, (p - 10) / 8), lerp(55.0, 92.0, (p - 10) / 8), 30.0)
            p < 33 -> if (pull && p < 26) {
                val f = (p - 18) / 8
                Triple(lerp(2500.0, 6200.0, f), lerp(85.0, peakMap, f * 2), 100.0)
            } else Triple(2200 + noise(t, 0.7, 120.0), 48 + noise(t, 0.9, 4.0), 12.0)
            p < 40 -> Triple(lerp(2600.0, 1100.0, (p - 33) / 7), 24.0, 0.0)
            else -> Triple(950 + noise(t, 3.1, 25.0), 36 + noise(t, 2.3, 2.0), 0.0)
        }
    }

    private fun interp(xs: DoubleArray?, ys: DoubleArray?, x: Double, default: Double): Double {
        if (xs == null || ys == null || xs.isEmpty() || ys.size < xs.size) return default
        if (x <= xs[0]) return ys[0]
        for (i in 0 until xs.size - 1) {
            if (x <= xs[i + 1]) {
                val f = if (xs[i + 1] == xs[i]) 0.0 else (x - xs[i]) / (xs[i + 1] - xs[i])
                return ys[i] + (ys[i + 1] - ys[i]) * f
            }
        }
        return ys[xs.size - 1]
    }

    override fun values(t: Double): Map<String, Double> {
        val (rpm, map, tps) = drive(t)
        val slow = t * timeScale // seconds of "engine life" for thermal effects
        val clt = 25 + 67 * (1 - exp(-slow / 400)) + (if (scenario == Scenario.CITY_HEAT) 10 * (1 - exp(-slow / 2500)) else 0.0)
        // Heat soak: intake air climbs with time and load, cools a bit when moving fast.
        val soak = if (scenario == Scenario.CITY_HEAT) 40 * (1 - exp(-slow / 1800)) else 20.0
        val mat = 32 + soak + (map - 40) * 0.12 + noise(t, 0.3, 1.5)
        val baro = 100.0

        val target = afr?.interpolate(rpm, map) ?: if (map > 100) 0.82 else 0.98
        // Lean drift under load once the air is hot: what the alerts are meant to catch.
        val lean = if (map > 95 && mat > 60) 0.08 else 0.0
        val lambda = target + lean + noise(t, 5.0, 0.012)
        val ego = 100 + noise(t, 0.8, 3.0) - lean * 120

        val veCurr = ve?.interpolate(rpm, map) ?: 70.0
        val wue = interp(wueTemps, wueBins, clt, 100.0)
        val pw = if (rpm < 300) 0.0 else reqFuel * veCurr / 100 * map / 100 * wue / 100 + injOpen
        val retard = interp(matTemps, matRetard, mat, 0.0)
        val advance = (adv?.interpolate(rpm, map) ?: 20.0) - retard

        return mapOf(
            "seconds" to t,
            "rpm" to rpm,
            "map" to map,
            "barometer" to baro,
            "tps" to tps,
            "coolant" to clt,
            "mat" to mat,
            "airtemp" to mat - 5,
            "batteryVoltage" to 13.9 + noise(t, 1.1, 0.15),
            "afr1" to lambda * 14.7,
            "afr2" to lambda * 14.7,
            "afrtgt1" to target * 14.7,
            "egoCorrection1" to ego,
            "egoCorrection2" to ego,
            "airCorrection" to 100.0,
            "warmupEnrich" to wue,
            "baroCorrection" to 100.0,
            "gammaEnrich" to wue,
            "veCurr1" to veCurr,
            "pulseWidth1" to pw,
            "pulseWidth2" to pw,
            "advance" to advance,
            "base_advance" to advance + retard,
            "mat_retard" to retard,
            "dwell" to 3.0,
            "fuelload" to map,
            "ignload" to map,
            "afrload1" to map,
            "TPSdot" to 0.0,
            "MAPdot" to 0.0,
            "synccnt" to 0.0,
            "status1" to 136.0, // sync ok + full sync
            "engine" to if (rpm > 300) 1.0 else 0.0,
            "runsecs" to t,
            // Regulator referenced to the manifold: base 3 bar plus boost.
            "fuel" to 3.0 + maxOf(0.0, map - baro) / 100 + noise(t, 1.7, 0.05),
            "oil" to 1.2 + rpm / 2000 - (clt - 80).coerceAtLeast(0.0) * 0.01,
        )
    }
}

/**
 * Replays a recorded `.msl` in a loop, mapping log columns back to channels through the INI
 * `[Datalog]` labels.
 */
class ReplayEngine(ini: IniDefinition, private val log: LogData) : EngineSource {

    private val columns: List<Pair<String, Int>>
    private val timeCol = log.column("Time")
    private val times: DoubleArray = if (timeCol >= 0) DoubleArray(log.rows.size) { log.rows[it][timeCol] } else DoubleArray(0)
    private val duration = log.durationSeconds.coerceAtLeast(1.0)

    init {
        val byLabel = ini.datalog.associate { it.label.lowercase() to it.channel }
        columns = log.headers.mapIndexedNotNull { i, h ->
            val ch = byLabel[h.lowercase()] ?: return@mapIndexedNotNull null
            // Lambda is a formula of afr1 in MS2 INIs; feed the raw channel instead.
            val target = if (ch == "lambda1") "afr1" else if (ch == "lambda2") "afr2" else ch
            target to i
        }
    }

    override fun values(t: Double): Map<String, Double> {
        if (log.rows.isEmpty()) return emptyMap()
        val row = if (timeCol >= 0) {
            val t0 = log.rows.first()[timeCol]
            val tt = t0 + (t % duration)
            val k = times.binarySearch(tt).let { if (it >= 0) it else (-it - 1) }
            log.rows[k.coerceAtMost(log.rows.size - 1)]
        } else log.rows[(t * 10).toInt() % log.rows.size]
        val out = HashMap<String, Double>()
        for ((ch, i) in columns) {
            val v = row[i]
            if (v.isNaN()) continue
            out[ch] = if (ch == "afr1" || ch == "afr2") {
                // The column may hold lambda (LAMBDA projects) or AFR.
                if (v < 3) v * 14.7 else v
            } else v
        }
        return out
    }
}
