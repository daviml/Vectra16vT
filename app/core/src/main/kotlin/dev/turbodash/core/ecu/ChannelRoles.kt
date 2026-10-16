package dev.turbodash.core.ecu

import dev.turbodash.core.ini.IniDefinition

/**
 * Firmware-independent meaning of a channel. Dashboards and alerts use roles, and each role is
 * resolved to whatever channel name the loaded INI uses (MS1, MS2, MS3 and forks differ).
 */
enum class Role(
    val label: String,
    val candidates: List<String>,
    /** Value supplied by the phone, not the ECU (always available as this channel name). */
    val external: Boolean = false,
) {
    SPEED("Velocidade", listOf("gpsSpeed"), external = true),
    RPM("RPM", listOf("rpm", "RPM")),
    MAP("MAP", listOf("map", "MAP")),
    BARO("Barômetro", listOf("barometer", "baro")),
    TPS("TPS", listOf("tps", "throttle", "TPS")),
    CLT("Temp. motor", listOf("coolant", "clt", "CLT")),
    MAT("Temp. ar", listOf("mat", "iat", "MAT", "airtemp")),
    LAMBDA("Lambda", listOf("lambda1", "lambda", "egoVoltage")),
    AFR("AFR", listOf("afr1", "AFR", "afr")),
    AFR_TARGET("Alvo AFR", listOf("afrtgt1", "afrtarget", "afrTarget")),
    EGO_CORR("Correção sonda", listOf("egoCorrection1", "egoCorrection", "egocor1")),
    ADVANCE("Avanço", listOf("advance", "Advance", "spkadv")),
    PULSE_WIDTH("Tempo injeção", listOf("pulseWidth1", "pulseWidth", "pw1")),
    DUTY("Duty bico", listOf("dutyCycle1", "dutyCycle", "DutyCycle1")),
    BATTERY("Bateria", listOf("batteryVoltage", "batt", "battery")),
    VE("VE", listOf("veCurr1", "veCurr", "VE1")),
    WARMUP("Aquecimento", listOf("warmupEnrich", "wue")),
    ACCEL("Aceleração rápida", listOf("accDecEnrich", "accelEnrich")),
    DWELL("Dwell", listOf("dwell")),
    SYNC_LOSS("Perdas de sincronismo", listOf("synccnt", "syncCount")),
    KNOCK_RETARD("Retardo detonação", listOf("knockRetard", "knock_retard")),
    MAT_RETARD("Retardo por ar quente", listOf("mat_retard")),
    BOOST_DUTY("Duty boost", listOf("boostduty")),
    FUEL_PRESSURE("Pressão comb.", listOf("fuel", "fuelPressure")),
    OIL_PRESSURE("Pressão óleo", listOf("oil", "oilPressure")),
    ;
}

/** Role → channel name mapping for one INI. */
class ChannelRoles(ini: IniDefinition) {
    private val map: Map<Role, String> = Role.entries.mapNotNull { role ->
        (if (role.external) role.candidates.first() else role.candidates.firstOrNull { it in ini.outputChannels })?.let { role to it }
    }.toMap()

    fun channel(role: Role): String? = map[role]

    fun value(s: Snapshot, role: Role): Double? = map[role]?.let { s[it] }

    /** Lambda directly, or AFR / 14.7 when the firmware only reports AFR. */
    fun lambda(s: Snapshot): Double? = value(s, Role.LAMBDA) ?: value(s, Role.AFR)?.let { it / 14.7 }

    /** Lambda target: MS2/MS3 report the target as AFR x10 scaled to AFR (gasoline scale). */
    fun lambdaTarget(s: Snapshot): Double? = value(s, Role.AFR_TARGET)?.takeIf { it > 0 }?.let { it / 14.7 }

    /** Manifold pressure above barometric, in kPa (negative = vacuum). */
    fun boostKpa(s: Snapshot): Double? {
        val map = value(s, Role.MAP) ?: return null
        val baro = value(s, Role.BARO)?.takeIf { it > 50 } ?: 100.0
        return map - baro
    }
}
