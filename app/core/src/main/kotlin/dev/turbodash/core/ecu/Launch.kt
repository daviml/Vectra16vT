package dev.turbodash.core.ecu

import dev.turbodash.core.ini.Constant
import dev.turbodash.core.tune.Tune

/** Launch control as the dashboard shows it. */
enum class LaunchState { OFF, ARMED, ACTIVE }

/**
 * MS2/Extra launch control: ACTIVE when the ECU reports launch in progress (`status2` bit 3,
 * the "CONTROLE DE LARGADA" indicator), ARMED while the launch button is held (its input pin,
 * PE0/PE1 from `launch_opt_pins`, reads 0 in `portbde`: the button pulls it to ground).
 */
object Launch {
    fun state(s: Snapshot?, tune: Tune?): LaunchState {
        s ?: return LaunchState.OFF
        val status2 = s["status2"]?.toInt()
        if (status2 != null && status2 and 8 != 0) return LaunchState.ACTIVE
        tune ?: return LaunchState.OFF
        val enabled = (tune.ini.constants["launch_opt_on"] as? Constant.Bits)?.let { tune.bitsIndex(it) != 0 } ?: false
        if (!enabled) return LaunchState.OFF
        val bit = when (tune.bitsLabel("launch_opt_pins")) {
            "PE0" -> 1
            "PE1" -> 2
            else -> return LaunchState.OFF
        }
        // 0 means no data (old logs, simulators): a real ECU always has some inputs high.
        val port = s["portbde"]?.toInt()?.takeIf { it != 0 } ?: return LaunchState.OFF
        return if (port and bit == 0) LaunchState.ARMED else LaunchState.OFF
    }
}

/**
 * Overrun "pops" window: foot off, still above 2500 rpm, the ECU still injecting (fuel cut not
 * in yet) and the spark retarded to 10 deg or less. Read from what the ECU does, so it follows
 * whatever pops cells the tune has instead of a fixed rpm range.
 */
object Pops {
    fun active(s: Snapshot?, roles: ChannelRoles?): Boolean {
        if (s == null || roles == null) return false
        val tps = roles.value(s, Role.TPS) ?: return false
        val rpm = roles.value(s, Role.RPM) ?: return false
        val pw = roles.value(s, Role.PULSE_WIDTH) ?: return false
        val adv = roles.value(s, Role.ADVANCE) ?: return false
        return tps < 2.0 && rpm > 2500 && pw > 0.5 && adv <= 10.0
    }
}
