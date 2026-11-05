package dev.turbodash.app.ui.dash

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.turbodash.app.Brand
import dev.turbodash.app.ui.Palette
import dev.turbodash.core.alerts.Severity
import dev.turbodash.core.dash.DashTemplates
import dev.turbodash.core.dash.DashWidget
import dev.turbodash.core.ecu.LaunchState
import dev.turbodash.core.ecu.Role
import dev.turbodash.core.ecu.SessionState
import kotlin.math.min

/** What the main page needs for one frame: channel values, their alert level and the ECU states. */
class HudFrame(
    val value: (Role) -> Double?,
    val alert: (Role) -> Severity?,
    val lambdaTarget: Double?,
    val launch: LaunchState,
    val pops: Boolean,
)

private val T = DashTemplates

/** The ☰ button that opens the side menu (Painel, Mapas, Logs, Ajustes, estilo do painel). */
@Composable
fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(36.dp, 30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Palette.muted, modifier = Modifier.size(20.dp))
    }
}

/** Number as large as its box allows: bold digits are ~0.6 em wide, a dot counts as half. */
@Composable
private fun FitNumber(
    text: String,
    color: Color,
    modifier: Modifier,
    weight: FontWeight = FontWeight.Bold,
    minChars: Float = 3f,
    heightFraction: Float = 0.85f,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val units = maxOf(text.count { it != '.' } + 0.5f * text.count { it == '.' }, minChars)
        val sp = min(maxWidth.value / (units * 0.6f), maxHeight.value * heightFraction).coerceAtLeast(10f)
        Text(
            text, color = color, fontSize = sp.sp, lineHeight = sp.sp, fontWeight = weight,
            style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false,
        )
    }
}

/** Blinking alpha for shift light, lamps and danger states; read it only in draw/layer blocks. */
@Composable
private fun rememberFlash(periodMs: Int): State<Float> =
    rememberInfiniteTransition(label = "flash").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(periodMs), RepeatMode.Reverse), label = "flash",
    )

// ---------------------------------------------------------------- Principal

/**
 * Principal style: shift light, giant RPM with speed and gear at its foot, lambda value with its
 * target, and six tiles (centered numbers, fill bars). Landscape puts RPM on the left.
 */
@Composable
fun MainHud(f: HudFrame, modifier: Modifier) {
    BoxWithConstraints(modifier.padding(4.dp)) {
        @Composable
        fun Tiles(m: Modifier) {
            Column(m) {
                listOf(listOf(T.mapNumber, T.clt, T.mat), listOf(T.fuel, T.batt, T.tps)).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        row.forEach { w ->
                            NumberTile(w, f.value(w.role), Modifier.weight(1f).fillMaxHeight().tileFrame(f.alert(w.role)), centered = true, fill = true)
                        }
                    }
                }
            }
        }
        if (maxWidth > maxHeight) {
            Column(Modifier.fillMaxSize()) {
                ShiftLight(f.value(Role.RPM), Modifier.weight(0.2f).fillMaxWidth())
                Row(Modifier.weight(2.8f).fillMaxWidth()) {
                    BigRpm(f, Modifier.weight(1.5f).fillMaxHeight())
                    Column(Modifier.weight(3f).fillMaxHeight()) {
                        LambdaValue(f, Modifier.weight(0.9f).fillMaxWidth())
                        Tiles(Modifier.weight(2f).fillMaxWidth())
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ShiftLight(f.value(Role.RPM), Modifier.weight(0.28f).fillMaxWidth())
                BigRpm(f, Modifier.weight(2.5f).fillMaxWidth())
                LambdaValue(f, Modifier.weight(1.05f).fillMaxWidth())
                Tiles(Modifier.weight(2.3f).fillMaxWidth())
            }
        }
    }
}

/** 15 LEDs (green, amber, red) from 1500 rpm to the shift point, where they all flash red. */
@Composable
private fun ShiftLight(rpm: Double?, modifier: Modifier) {
    val flash = rememberFlash(120)
    val n = 15
    Canvas(modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val shift = rpm != null && rpm >= T.SHIFT_RPM
        for (i in 0 until n) {
            val on = rpm != null && rpm >= T.SHIFT_LIGHT_START + i * (T.SHIFT_RPM - T.SHIFT_LIGHT_START) / n
            val c = when {
                shift -> Palette.danger.copy(alpha = flash.value)
                !on -> Palette.surface
                i < 9 -> Palette.ok
                i < 13 -> Palette.warn
                else -> Palette.danger
            }
            drawRoundRect(c, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(4.dp.toPx()))
        }
    }
}

@Composable
private fun BigRpm(f: HudFrame, modifier: Modifier) {
    val rpm = f.value(Role.RPM)
    BoxWithConstraints(modifier.tileFrame(f.alert(Role.RPM)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        val footSp = (maxHeight.value * 0.16f).coerceIn(16f, 44f)
        Column(Modifier.fillMaxSize()) {
            Text("RPM", color = Palette.muted, fontSize = 13.sp, lineHeight = 13.sp)
            FitNumber(fmt(rpm, 0), levelColor(T.rpmMain, rpm), Modifier.weight(1f).fillMaxWidth(), minChars = 4f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                FootValue(fmt(f.value(Role.SPEED), 0), "km/h", footSp)
                Spacer(Modifier.weight(1f))
                FootValue(fmt(f.value(Role.GEAR), 0), "marcha", footSp)
            }
        }
    }
}

@Composable
private fun RowScope.FootValue(value: String, units: String, sizeSp: Float) {
    Text(
        value, color = Palette.text, fontSize = sizeSp.sp, lineHeight = sizeSp.sp, fontWeight = FontWeight.Bold,
        style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.alignByBaseline(),
    )
    Text(units, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.alignByBaseline().padding(start = 4.dp))
}

/** Lambda without the bar: value in the middle, target small under it, color against the target. */
@Composable
private fun LambdaValue(f: HudFrame, modifier: Modifier) {
    val v = f.value(Role.LAMBDA)
    val target = f.lambdaTarget
    Box(modifier.tileFrame(f.alert(Role.LAMBDA)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("Lambda", color = Palette.muted, fontSize = 13.sp, lineHeight = 13.sp, modifier = Modifier.align(Alignment.TopStart))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            FitNumber(fmt(v, 3), lambdaColor(v, target), Modifier.weight(1f).fillMaxWidth(), minChars = 5f, heightFraction = 0.9f)
            if (target != null) {
                Text("alvo ${fmt(target, 2)}", color = Palette.accent, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------- Racing

private val RaceLine = Color(0xFFE6E6E6)
private val RaceRed = Color(0xFFFF2A2A)
private val RaceBlue = Color(0xFF2F8FE8)
private val RaceYellow = Color(0xFFE8D83A)
private val RaceSky = Color(0xFF9FC6E4)
private val RaceMaroon = Color(0xFFD63A3A)
private val RaceGreen = Color(0xFF2F8F3C)

/** Alert color of a channel: its own limits or the alert engine, whichever is worse. */
private fun raceAlert(w: DashWidget, value: Double?, alert: Severity?): Color? {
    val level = levelColor(w, value)
    return when {
        alert == Severity.CRITICAL || level == Palette.danger -> RaceRed
        alert == Severity.WARNING || level == Palette.warn -> Palette.warn
        else -> null
    }
}

/**
 * Racing style, like a motorsport display: black, thin outlined boxes with the name on the
 * border, RPM scale on top, big gear in the middle and a lamp strip (menu, launch, pops,
 * alerts, connection, REC) at the bottom, which replaces the status line.
 */
@Composable
fun RacingHud(
    f: HudFrame,
    state: SessionState?,
    logRows: Int?,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val flash = rememberFlash(130)
    BoxWithConstraints(modifier.background(Color.Black).padding(horizontal = 6.dp, vertical = 4.dp)) {
        val landscape = maxWidth > maxHeight

        @Composable
        fun Value(w: DashWidget, m: Modifier, border: Color = RaceLine) {
            val v = f.value(w.role)
            val alert = raceAlert(w, v, f.alert(w.role))
            RaceBox(w.title, m, border, alert) {
                FitNumber(fmt(v, w.digits), alert ?: Color.White, Modifier.fillMaxSize(), FontWeight.Medium, heightFraction = 0.75f)
            }
        }

        @Composable
        fun Lambda(m: Modifier) {
            val v = f.value(Role.LAMBDA)
            val target = f.lambdaTarget
            val color = lambdaColor(v, target)
            val alert = if (color == Palette.danger || f.alert(Role.LAMBDA) == Severity.CRITICAL) RaceRed else null
            RaceBox("Lambda", m, alert = alert, double = true) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    FitNumber(fmt(v, 3), alert ?: if (color == Palette.info) Palette.info else Color.White, Modifier.weight(1f).fillMaxWidth(), FontWeight.Medium, minChars = 5f)
                    if (target != null) Text("alvo ${fmt(target, 2)}", color = Color(0xFFAAAAAA), fontSize = 11.sp, lineHeight = 11.sp)
                }
            }
        }

        @Composable
        fun Gear(m: Modifier) = RaceBox("Marcha", m) {
            FitNumber(fmt(f.value(Role.GEAR), 0), Color.White, Modifier.fillMaxSize(), FontWeight.Normal, minChars = 1f, heightFraction = 0.95f)
        }

        @Composable
        fun Group(m: Modifier) = Column(m) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Value(T.tps, Modifier.weight(1f).fillMaxHeight(), RaceYellow)
                Value(T.adv, Modifier.weight(1f).fillMaxHeight(), RaceSky)
                Value(T.duty, Modifier.weight(1f).fillMaxHeight(), RaceMaroon)
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Value(T.fuel, Modifier.weight(1f).fillMaxHeight(), RaceGreen)
                Value(T.batt, Modifier.weight(1f).fillMaxHeight())
            }
        }

        @Composable
        fun MapRpm(m: Modifier) = Column(m) {
            Value(T.mapNumber, Modifier.weight(1f).fillMaxWidth())
            Value(T.rpmMain, Modifier.weight(1f).fillMaxWidth())
        }

        @Composable
        fun Lamps(m: Modifier) = LampStrip(f, state, logRows, !landscape, flash, onOpenMenu, onOpenSettings, m)

        if (landscape) {
            // Left block (2) as wide as the right one (2): gear and lambda sit in the center.
            Column(Modifier.fillMaxSize()) {
                RpmScale(f.value(Role.RPM), flash, Modifier.weight(0.5f).fillMaxWidth())
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Value(T.clt, Modifier.weight(1f).fillMaxHeight())
                    Value(T.mat, Modifier.weight(1f).fillMaxHeight())
                    Lambda(Modifier.weight(1.3f).fillMaxHeight())
                    Value(T.speedMain, Modifier.weight(2f).fillMaxHeight())
                }
                Row(Modifier.weight(2f).fillMaxWidth()) {
                    MapRpm(Modifier.weight(2f).fillMaxHeight())
                    Gear(Modifier.weight(1.3f).fillMaxHeight())
                    Group(Modifier.weight(2f).fillMaxHeight())
                }
                Lamps(Modifier.weight(0.42f).fillMaxWidth())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                RpmScale(f.value(Role.RPM), flash, Modifier.weight(0.42f).fillMaxWidth())
                Row(Modifier.weight(0.95f).fillMaxWidth()) {
                    Value(T.clt, Modifier.weight(1f).fillMaxHeight())
                    Value(T.mat, Modifier.weight(1f).fillMaxHeight())
                    Value(T.speedMain, Modifier.weight(1f).fillMaxHeight())
                }
                Lambda(Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.weight(2.1f).fillMaxWidth()) {
                    MapRpm(Modifier.weight(1f).fillMaxHeight())
                    Gear(Modifier.weight(2f).fillMaxHeight())
                }
                Group(Modifier.weight(1.9f).fillMaxWidth())
                Lamps(Modifier.weight(0.32f).fillMaxWidth())
            }
        }
    }
}

/** Outlined box with its name on the top border; [alert] recolors it (red also fills it). */
@Composable
private fun RaceBox(
    title: String,
    modifier: Modifier,
    border: Color = RaceLine,
    alert: Color? = null,
    double: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = alert ?: border
    Box(modifier.padding(3.dp)) {
        var frame = Modifier.fillMaxSize().padding(top = 8.dp)
        if (double) frame = frame.border(2.dp, c).padding(3.dp)
        frame = frame.border(2.dp, c)
        if (alert == RaceRed) frame = frame.background(Color(0xFF2A0000))
        Box(frame.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center, content = content)
        Text(
            title, color = alert ?: Color.White, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).background(Color.Black).padding(horizontal = 6.dp),
        )
    }
}

/** RPM 0–7 scale: blue bar, amber from the warning limit, flashing red at the shift point. */
@Composable
private fun RpmScale(rpm: Double?, flash: State<Float>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val steps = 7
    Canvas(modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
        val barH = min(size.height * 0.4f, 16.dp.toPx())
        val barTop = size.height - barH
        val tick = 2.dp.toPx()
        if (rpm != null) {
            val c = when {
                rpm >= T.SHIFT_RPM -> RaceRed.copy(alpha = flash.value)
                rpm >= (T.rpmMain.warnHigh ?: Double.MAX_VALUE) -> Palette.warn
                else -> RaceBlue
            }
            val f = (rpm / (steps * 1000)).coerceIn(0.0, 1.0).toFloat()
            drawRect(c, Offset(0f, barTop), Size(size.width * f, barH))
        }
        for (i in 0..steps) {
            val x = size.width * i / steps
            drawLine(Color.White, Offset(x, barTop - 5.dp.toPx()), Offset(x, size.height), tick)
            val text = measurer.measure(i.toString(), label)
            drawText(text, topLeft = Offset(x - text.size.width / 2f, barTop - 7.dp.toPx() - text.size.height))
        }
    }
}

/** Bottom strip: menu, brand, LAUNCH / POPS / ÁGUA / COMB / BAT lamps, connection and REC. */
@Composable
private fun LampStrip(
    f: HudFrame,
    state: SessionState?,
    logRows: Int?,
    compact: Boolean,
    flash: State<Float>,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    fun level(w: DashWidget): Pair<Color?, Boolean> = when (raceAlert(w, f.value(w.role), f.alert(w.role))) {
        RaceRed -> RaceRed to true
        null -> null to false
        else -> Palette.warn to false
    }
    val (connText, connColor) = when (state) {
        is SessionState.Running -> "%.0f Hz".format(state.hz) to Palette.ok
        is SessionState.Connecting, is SessionState.ReadingTune -> "conectando" to Palette.warn
        is SessionState.Failed -> "falha" to Palette.danger
        else -> "desconectado" to Palette.muted
    }
    Row(
        modifier.padding(3.dp).border(2.dp, RaceLine).clickable(onClick = onOpenSettings).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        MenuButton(onOpenMenu, Modifier.size(32.dp, 24.dp))
        if (!compact) Text(Brand.name.uppercase(), color = Color(0xFFBBBBBB), fontSize = 13.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Lamp("LAUNCH", if (f.launch != LaunchState.OFF) Palette.warn else null, f.launch == LaunchState.ACTIVE, flash, compact)
        Lamp("POPS", if (f.pops) RaceRed else null, f.pops, flash, compact)
        level(T.clt).let { (c, b) -> Lamp("ÁGUA", c, b, flash, compact) }
        level(T.fuel).let { (c, b) -> Lamp("COMB", c, b, flash, compact) }
        level(T.batt).let { (c, b) -> Lamp("BAT", c, b, flash, compact) }
        Box(Modifier.size(7.dp).clip(CircleShape).background(connColor))
        if (!compact) Text(connText, color = connColor, fontSize = 11.sp, maxLines = 1)
        if (logRows != null) {
            Text(
                "REC $logRows", color = RaceRed, fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                modifier = Modifier.border(1.5.dp, RaceRed, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Indicator lamp: dark outline when off, filled with [on] when lit, optionally flashing. */
@Composable
private fun Lamp(text: String, on: Color?, blink: Boolean, flash: State<Float>, compact: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    val off = Color(0xFF3D3D3D)
    Text(
        text,
        color = when (on) { null -> off; Palette.warn -> Color.Black; else -> Color.White },
        fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
        modifier = Modifier
            .graphicsLayer { alpha = if (on != null && blink) flash.value else 1f }
            .clip(shape)
            .then(if (on != null) Modifier.background(on) else Modifier.border(1.5.dp, off, shape))
            .padding(horizontal = if (compact) 3.dp else 5.dp, vertical = 1.dp),
    )
}
