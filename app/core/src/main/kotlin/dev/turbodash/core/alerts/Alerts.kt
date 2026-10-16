package dev.turbodash.core.alerts

import dev.turbodash.core.ecu.ChannelRoles
import dev.turbodash.core.ecu.Role
import dev.turbodash.core.ecu.Snapshot
import kotlin.math.abs

enum class Severity { WARNING, CRITICAL }

data class Alert(
    val id: String,
    val severity: Severity,
    val title: String,
    val message: String,
    /** Session time (s) when the condition started. */
    val since: Double,
    /** Gauge the alert is about, so the dashboard can highlight it. */
    val role: Role? = null,
)

/** Thresholds. Defaults are conservative for a turbo engine with cast pistons. */
data class AlertConfig(
    val matWarnC: Double = 55.0,
    val matCritC: Double = 70.0,
    val cltWarnC: Double = 98.0,
    val cltCritC: Double = 105.0,
    /** Lean check only above this MAP (kPa): that is where lean mixtures break pistons. */
    val leanMinMapKpa: Double = 90.0,
    /** Lambda this much above target, under load, is "lean". */
    val leanMarginLambda: Double = 0.05,
    /** Used when the firmware does not report a target. */
    val leanAbsoluteLambda: Double = 0.95,
    val egoSaturationPct: Double = 15.0,
    val dutyWarnPct: Double = 85.0,
    val dutyCritPct: Double = 90.0,
    /** Null disables the check (engine running without boost control). */
    val overboostKpa: Double? = 200.0,
    val battLowV: Double = 11.5,
    val battHighV: Double = 15.5,
    /** Fuel pressure below this (bar) while running is critical: the mixture goes lean. */
    val fuelMinBar: Double = 2.0,
    /** A condition must hold this long before it alerts (filters sensor glitches). */
    val confirmSeconds: Double = 0.5,
    /** ...and must stay clear this long before the alert goes away. */
    val clearSeconds: Double = 2.0,
)

/**
 * Evaluates safety rules on every frame and keeps track of which alerts are active,
 * with confirmation and clear delays so the screen does not flicker.
 */
class AlertEngine(private val roles: ChannelRoles, var config: AlertConfig = AlertConfig()) {

    private class RuleState(var trueSince: Double? = null, var falseSince: Double? = null, var active: Alert? = null)

    private val states = HashMap<String, RuleState>()
    private var lastSyncCount: Double? = null
    private var syncLossUntil = -1.0

    /** Alerts currently active, most severe first. */
    fun evaluate(s: Snapshot): List<Alert> {
        val c = config
        val t = s.time
        val rpm = roles.value(s, Role.RPM) ?: 0.0
        val running = rpm > 400
        val map = roles.value(s, Role.MAP)
        val mat = roles.value(s, Role.MAT)
        val clt = roles.value(s, Role.CLT)
        val lambda = roles.lambda(s)
        val target = roles.lambdaTarget(s)
        val ego = roles.value(s, Role.EGO_CORR)
        val duty = roles.value(s, Role.DUTY)
        val batt = roles.value(s, Role.BATTERY)
        val tps = roles.value(s, Role.TPS) ?: 100.0
        // Below 0.3 bar the sensor is most likely not installed; do not alarm on it.
        val fuel = roles.value(s, Role.FUEL_PRESSURE)?.takeIf { it > 0.3 }

        // Lost-sync counter increments mean a trigger problem; show it for 10 s.
        roles.value(s, Role.SYNC_LOSS)?.let { sc ->
            lastSyncCount?.let { if (sc > it) syncLossUntil = t + 10 }
            lastSyncCount = sc
        }

        val leanLimit = target?.let { it + c.leanMarginLambda } ?: c.leanAbsoluteLambda
        val underLoad = running && map != null && map >= c.leanMinMapKpa && tps > 5

        val rules = listOf(
            Rule("mat_crit", Severity.CRITICAL, mat != null && mat >= c.matCritC,
                "Ar admitido muito quente", "Ar a ${fmt(mat)} °C — risco alto de detonação/pré-ignição. Alivie o pé."),
            Rule("mat_warn", Severity.WARNING, mat != null && mat >= c.matWarnC && mat < c.matCritC,
                "Ar admitido quente", "Ar a ${fmt(mat)} °C. Evite carga alta até baixar."),
            Rule("clt_crit", Severity.CRITICAL, clt != null && clt >= c.cltCritC,
                "Motor superaquecendo", "Água a ${fmt(clt)} °C. Pare o carro com segurança."),
            Rule("clt_warn", Severity.WARNING, clt != null && clt >= c.cltWarnC && clt < c.cltCritC,
                "Motor quente", "Água a ${fmt(clt)} °C."),
            Rule("lean_load", Severity.CRITICAL, underLoad && lambda != null && lambda > leanLimit && lambda < 1.6,
                "Mistura POBRE com carga",
                "Lambda ${fmt(lambda, 2)}" + (target?.let { " (alvo ${fmt(it, 2)})" } ?: "") + " a ${fmt(map)} kPa. Tire o pé!"),
            Rule("ego_sat", Severity.WARNING, running && ego != null && abs(ego - 100) >= c.egoSaturationPct,
                "Correção da sonda no limite", "Sonda corrigindo ${fmt(ego?.minus(100))}%. O mapa de VE está longe do real."),
            Rule("duty_crit", Severity.CRITICAL, running && duty != null && duty >= c.dutyCritPct,
                "Bicos no limite", "Duty ${fmt(duty)}%. Não há mais combustível disponível — a mistura vai empobrecer."),
            Rule("duty_warn", Severity.WARNING, running && duty != null && duty >= c.dutyWarnPct && duty < c.dutyCritPct,
                "Bicos perto do limite", "Duty ${fmt(duty)}%."),
            Rule("overboost", Severity.CRITICAL, c.overboostKpa != null && map != null && map >= c.overboostKpa,
                "OVERBOOST", "MAP ${fmt(map)} kPa acima do limite de ${fmt(c.overboostKpa)} kPa."),
            Rule("batt", Severity.WARNING, running && batt != null && (batt < c.battLowV || batt > c.battHighV),
                "Tensão da bateria anormal", "Bateria ${fmt(batt, 1)} V."),
            Rule("fuel_low", Severity.CRITICAL, running && fuel != null && fuel < c.fuelMinBar,
                "Pressão de combustível baixa", "Combustível a ${fmt(fuel, 1)} bar. Mistura vai empobrecer — alivie o pé.", Role.FUEL_PRESSURE),
            Rule("sync", Severity.WARNING, t < syncLossUntil,
                "Perda de sincronismo", "A ECU perdeu o sinal de rotação. Verifique roda fônica/sensor."),
        )

        val active = ArrayList<Alert>()
        for (r in rules) {
            val st = states.getOrPut(r.id) { RuleState() }
            if (r.condition) {
                st.falseSince = null
                val since = st.trueSince ?: t.also { st.trueSince = it }
                if (st.active != null || t - since >= c.confirmSeconds) {
                    st.active = Alert(r.id, r.severity, r.title, r.message, since, r.role ?: roleFor(r.id))
                }
            } else {
                st.trueSince = null
                if (st.active != null) {
                    val since = st.falseSince ?: t.also { st.falseSince = it }
                    if (t - since >= c.clearSeconds) { st.active = null; st.falseSince = null }
                }
            }
            st.active?.let { active += it }
        }
        return active.sortedBy { it.severity != Severity.CRITICAL }
    }

    fun reset() {
        states.clear()
        lastSyncCount = null
        syncLossUntil = -1.0
    }

    private class Rule(
        val id: String, val severity: Severity, val condition: Boolean, val title: String, val message: String,
        val role: Role? = null,
    )

    private fun roleFor(id: String): Role? = when {
        id.startsWith("mat") -> Role.MAT
        id.startsWith("clt") -> Role.CLT
        id == "lean_load" -> Role.LAMBDA
        id == "ego_sat" -> Role.EGO_CORR
        id.startsWith("duty") -> Role.DUTY
        id == "overboost" -> Role.MAP
        id == "batt" -> Role.BATTERY
        id == "sync" -> Role.RPM
        else -> null
    }
}

private fun fmt(v: Double?, digits: Int = 0): String =
    if (v == null) "--" else "%.${digits}f".format(java.util.Locale.US, v)
