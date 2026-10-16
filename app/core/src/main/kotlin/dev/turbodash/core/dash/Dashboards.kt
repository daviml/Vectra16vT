package dev.turbodash.core.dash

import dev.turbodash.core.ecu.Role

enum class WidgetKind {
    /** Round gauge with colored warning/danger arcs. */
    ARC,
    /** Large numeric tile. */
    NUMBER,
    /** Horizontal bar. */
    BAR,
    /** Lambda bar centered on 1.0 with the target marked. */
    LAMBDA,
    /** Several number tiles stacked in one cell ([DashWidget.stack]). */
    STACK,
    /** Heading-up street mini map from the phone GPS (no ECU channel). */
    MINIMAP,
}

/** Visual style of the dashboard; the templates (Rua, Pull...) are the same in every style. */
enum class DashStyle(val label: String) {
    /** Analog arcs for the main gauges. */
    GAUGES("Ponteiros"),
    /** Big numbers with a 30-second trend line in every tile. */
    DIGITAL("Digital"),
}

/**
 * One widget of a dashboard template. Colors follow the template limits (chosen for engine
 * safety), not the INI gauge limits, which are often generic.
 */
data class DashWidget(
    val role: Role,
    val kind: WidgetKind,
    val title: String = role.label,
    val min: Double,
    val max: Double,
    val warnHigh: Double? = null,
    val dangerHigh: Double? = null,
    val warnLow: Double? = null,
    val dangerLow: Double? = null,
    val digits: Int = 0,
    /** Relative width in its row. */
    val weight: Float = 1f,
    val units: String = "",
    /** Widgets shown stacked inside a [WidgetKind.STACK] cell. */
    val stack: List<DashWidget> = emptyList(),
)

data class DashTemplate(val id: String, val name: String, val rows: List<List<DashWidget>>)

/** Ready-made layouts. Temperatures in °C, pressures in kPa/bar, as the INI is configured (CELSIUS). */
object DashTemplates {

    private val rpm = DashWidget(Role.RPM, WidgetKind.ARC, "RPM", 0.0, 8000.0, warnHigh = 6000.0, dangerHigh = 6800.0, weight = 1.25f)
    private val speed = DashWidget(Role.SPEED, WidgetKind.ARC, "Velocidade", 0.0, 220.0, warnHigh = 160.0, dangerHigh = 190.0, units = "km/h")
    private val map = DashWidget(Role.MAP, WidgetKind.ARC, "MAP", 0.0, 250.0, warnHigh = 180.0, dangerHigh = 210.0, units = "kPa")
    private val lambda = DashWidget(Role.LAMBDA, WidgetKind.LAMBDA, "Lambda", 0.6, 1.4, digits = 3, weight = 3f)
    private val clt = DashWidget(Role.CLT, WidgetKind.NUMBER, "Água", 0.0, 120.0, warnHigh = 98.0, dangerHigh = 105.0, units = "°C")
    private val mat = DashWidget(Role.MAT, WidgetKind.NUMBER, "Ar", 0.0, 100.0, warnHigh = 55.0, dangerHigh = 70.0, units = "°C")
    private val batt = DashWidget(Role.BATTERY, WidgetKind.NUMBER, "Bateria", 8.0, 16.0, warnLow = 12.0, dangerLow = 11.0, warnHigh = 15.0, dangerHigh = 15.8, digits = 1, units = "V")
    private val fuel = DashWidget(Role.FUEL_PRESSURE, WidgetKind.NUMBER, "Comb.", 0.0, 6.0, warnLow = 2.5, dangerLow = 2.0, digits = 1, units = "bar")
    private val tps = DashWidget(Role.TPS, WidgetKind.NUMBER, "TPS", 0.0, 100.0, units = "%")
    private val adv = DashWidget(Role.ADVANCE, WidgetKind.NUMBER, "Avanço", -10.0, 50.0, digits = 1, units = "°")
    private val duty = DashWidget(Role.DUTY, WidgetKind.NUMBER, "Duty bico", 0.0, 100.0, warnHigh = 85.0, dangerHigh = 90.0, units = "%")
    private val ego = DashWidget(Role.EGO_CORR, WidgetKind.NUMBER, "Corr. sonda", 50.0, 150.0, warnLow = 85.0, warnHigh = 115.0, dangerLow = 75.0, dangerHigh = 125.0, units = "%")
    private val pw = DashWidget(Role.PULSE_WIDTH, WidgetKind.NUMBER, "Injeção", 0.0, 25.0, digits = 2, units = "ms")
    private val ve = DashWidget(Role.VE, WidgetKind.NUMBER, "VE", 0.0, 150.0, units = "%")
    private val wue = DashWidget(Role.WARMUP, WidgetKind.NUMBER, "Aquec.", 100.0, 200.0, units = "%")
    private val matRetard = DashWidget(Role.MAT_RETARD, WidgetKind.NUMBER, "Retardo ar", 0.0, 15.0, digits = 1, units = "°")
    private val knock = DashWidget(Role.KNOCK_RETARD, WidgetKind.NUMBER, "Retardo knock", 0.0, 20.0, digits = 1, units = "°")
    private val sync = DashWidget(Role.SYNC_LOSS, WidgetKind.NUMBER, "Perda sinc.", 0.0, 255.0, warnHigh = 1.0)
    private val dwell = DashWidget(Role.DWELL, WidgetKind.NUMBER, "Dwell", 0.0, 10.0, digits = 2, units = "ms")
    private val baro = DashWidget(Role.BARO, WidgetKind.NUMBER, "Baro", 50.0, 110.0, digits = 1, units = "kPa")

    private fun DashWidget.asNumber() = copy(kind = WidgetKind.NUMBER, weight = 1f)

    private val rpmSpeed = DashWidget(
        Role.RPM, WidgetKind.STACK, "RPM e velocidade", 0.0, 1.0,
        stack = listOf(
            DashWidget(Role.RPM, WidgetKind.NUMBER, "RPM", 0.0, 7000.0, warnHigh = 6000.0, dangerHigh = 6500.0),
            DashWidget(Role.SPEED, WidgetKind.NUMBER, "Velocidade", 0.0, 160.0, units = "km/h"),
        ),
    )
    private val miniMap = DashWidget(Role.SPEED, WidgetKind.MINIMAP, "Mapa", 0.0, 1.0)

    val all: List<DashTemplate> = listOf(
        DashTemplate(
            "rua", "Rua",
            listOf(
                listOf(rpmSpeed, miniMap),
                listOf(lambda),
                listOf(map.asNumber(), clt, mat),
                listOf(fuel, batt, tps),
            ),
        ),
        DashTemplate(
            "diagnostico", "Diagnóstico",
            listOf(
                listOf(rpm.asNumber(), map.asNumber(), tps, speed.asNumber()),
                listOf(clt, mat, baro, batt),
                listOf(lambda.asNumber(), ego, ve, fuel),
                listOf(pw, duty, adv, dwell),
                listOf(matRetard, wue, knock, sync),
            ),
        ),
    )
}
