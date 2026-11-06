package dev.turbodash.app.ui.dash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
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

/**
 * Number as large as its box allows: bold digits are ~0.6 em wide, a dot counts as half. Sized for
 * [widest] when that is longer, so the number keeps its size as the value changes. [tight] trims
 * the line to the digits (they have no descenders), so the number can use the box's full height.
 */
@Composable
private fun FitNumber(
    text: String,
    color: Color,
    modifier: Modifier,
    weight: FontWeight = FontWeight.Bold,
    minChars: Float = 3f,
    heightFraction: Float = 0.85f,
    widest: String = text,
    tight: Boolean = false,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val sized = if (widest.length > text.length) widest else text
        val units = maxOf(sized.count { it != '.' } + 0.5f * sized.count { it == '.' }, minChars)
        val sp = min(maxWidth.value / (units * 0.6f), maxHeight.value * heightFraction).coerceAtLeast(10f)
        Text(
            text, color = color, fontSize = sp.sp, lineHeight = (if (tight) sp * 0.78f else sp).sp, fontWeight = weight,
            style = TextStyle(
                fontFeatureSettings = "tnum",
                lineHeightStyle = if (tight) LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both) else LineHeightStyle.Default,
            ),
            maxLines = 1, softWrap = false,
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
 * Principal style: RPM bar, giant RPM with speed and gear at its foot, lambda value with its
 * target, and six tiles told apart by icon and color. Portrait stacks the tiles in two columns so
 * their numbers can be big; landscape puts RPM on the left and the tiles in three columns.
 */
@Composable
fun MainHud(f: HudFrame, modifier: Modifier) {
    BoxWithConstraints(modifier.padding(4.dp)) {
        @Composable
        fun Tiles(rows: List<List<DashWidget>>, m: Modifier) {
            Column(m) {
                rows.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        row.forEach { w -> MainTile(w, f.value(w.role), f.alert(w.role), Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
        if (maxWidth > maxHeight) {
            Column(Modifier.fillMaxSize()) {
                RpmBar(f.value(Role.RPM), Modifier.weight(0.2f).fillMaxWidth())
                Row(Modifier.weight(2.8f).fillMaxWidth()) {
                    BigRpm(f, Modifier.weight(1.5f).fillMaxHeight())
                    Column(Modifier.weight(3f).fillMaxHeight()) {
                        LambdaValue(f, Modifier.weight(0.9f).fillMaxWidth())
                        Tiles(listOf(listOf(T.clt, T.mat, T.mapNumber), listOf(T.fuel, T.batt, T.tps)), Modifier.weight(2f).fillMaxWidth())
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                RpmBar(f.value(Role.RPM), Modifier.weight(0.28f).fillMaxWidth())
                BigRpm(f, Modifier.weight(1.72f).fillMaxWidth())
                LambdaValue(f, Modifier.weight(0.95f).fillMaxWidth())
                Tiles(
                    listOf(listOf(T.clt, T.mat), listOf(T.mapNumber, T.tps), listOf(T.fuel, T.batt)),
                    Modifier.weight(3.45f).fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * RPM as one continuous bar from 0 to the end of the scale, so idle already shows: green, amber
 * from the warning limit, red from the shift point, where the whole bar flashes red. The track
 * shows where those zones start and has a tick every 1000 rpm.
 */
@Composable
private fun RpmBar(rpm: Double?, modifier: Modifier) {
    val flash = rememberFlash(120)
    // Frames arrive about 14 times a second: glide between them so the bar moves like a needle.
    val shown by animateFloatAsState((rpm ?: 0.0).toFloat(), tween(80, easing = LinearEasing), label = "rpm")
    val max = T.rpmMain.max.toFloat()
    val warn = ((T.rpmMain.warnHigh ?: T.SHIFT_RPM) / max).toFloat()
    val shift = (T.SHIFT_RPM / max).toFloat()
    Canvas(modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
        val track = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(5.dp.toPx()))) }
        clipPath(track) {
            fun band(from: Float, to: Float, c: Color) {
                if (to > from) drawRect(c, Offset(size.width * from, 0f), Size(size.width * (to - from), size.height))
            }
            band(0f, warn, Palette.surface)
            band(warn, shift, Palette.warn.copy(alpha = 0.22f).compositeOver(Palette.surface))
            band(shift, 1f, Palette.danger.copy(alpha = 0.28f).compositeOver(Palette.surface))
            val v = (shown / max).coerceIn(0f, 1f)
            if (rpm != null && rpm >= T.SHIFT_RPM) {
                band(0f, v, Palette.danger.copy(alpha = flash.value))
            } else {
                band(0f, min(v, warn), Palette.ok)
                band(warn, min(v, shift), Palette.warn)
                band(shift, v, Palette.danger)
            }
            val tick = 1.dp.toPx()
            for (k in 1 until (max / 1000).toInt()) {
                drawRect(Color.White.copy(alpha = 0.10f), Offset(size.width * k * 1000 / max - tick, 0f), Size(tick, size.height))
            }
        }
    }
}

@Composable
private fun BigRpm(f: HudFrame, modifier: Modifier) {
    val rpm = f.value(Role.RPM)
    BoxWithConstraints(modifier.tileFrame(f.alert(Role.RPM)).padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp)) {
        val footSp = (maxHeight.value * 0.3f).coerceIn(18f, 60f)
        Text("RPM", color = Palette.muted, fontSize = 13.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.fillMaxSize()) {
            FitNumber(
                fmt(rpm, 0), levelColor(T.rpmMain, rpm), Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
                minChars = 4f, heightFraction = 1.22f, tight = true,
            )
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
    Text(
        units, color = Palette.muted, fontSize = (sizeSp * 0.3f).coerceIn(14f, 18f).sp,
        modifier = Modifier.alignByBaseline().padding(start = 6.dp),
    )
}

/** How a main-page tile is recognized: its icon, its own color, and the widest value it shows. */
private class TileLook(val icon: ImageVector, val color: Color, val widest: String)

private val tileLooks = mapOf(
    Role.CLT to TileLook(DashIcons.water, Color(0xFF4EA8DE), "120"),
    Role.MAT to TileLook(DashIcons.air, Color(0xFFC3CCDA), "100"),
    Role.MAP to TileLook(DashIcons.gauge, Color(0xFFF28CB1), "250"),
    Role.FUEL_PRESSURE to TileLook(DashIcons.fuel, Color(0xFF5BD17A), "6.0"),
    Role.BATTERY to TileLook(DashIcons.battery, Color(0xFFA78BFA), "14.5"),
    Role.TPS to TileLook(DashIcons.pedal, Color(0xFFE0C79A), "100"),
)

/**
 * Main-page tile: name with its colored icon, the number sized for the widest value the tile can
 * show (so it doesn't jump in size), a fill bar in the tile's color and the icon large and faint
 * behind it all. The number stays white: amber and red are kept for alerts.
 */
@Composable
private fun MainTile(w: DashWidget, value: Double?, alert: Severity?, modifier: Modifier) {
    val look = tileLooks.getValue(w.role)
    val level = levelColor(w, value)
    val alarm = level == Palette.warn || level == Palette.danger
    Box(modifier.tileFrame(alert)) {
        Icon(
            look.icon, contentDescription = null, tint = (if (alarm) level else look.color).copy(alpha = 0.10f),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.92f).aspectRatio(1f)
                .graphicsLayer { translationX = size.width * 0.12f },
        )
        Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(17.dp))
                Text(
                    w.title, color = Palette.muted, fontSize = 15.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, modifier = Modifier.weight(1f).padding(start = 5.dp),
                )
                if (w.units.isNotEmpty()) Text(w.units, color = Palette.muted.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
            }
            FitNumber(
                fmt(value, w.digits), level, Modifier.weight(1f).fillMaxWidth(),
                minChars = 1f, heightFraction = 1.25f, widest = look.widest, tight = true,
            )
            FillBar(w, value, Modifier.fillMaxWidth().height(5.dp), normal = look.color)
        }
    }
}

/** Lambda without the bar: value in the middle, target small under it, color against the target. */
@Composable
private fun LambdaValue(f: HudFrame, modifier: Modifier) {
    val v = f.value(Role.LAMBDA)
    val target = f.lambdaTarget
    Box(modifier.tileFrame(f.alert(Role.LAMBDA)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("Lambda", color = Palette.muted, fontSize = 13.sp, lineHeight = 13.sp, modifier = Modifier.align(Alignment.TopStart))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            FitNumber(fmt(v, 3), lambdaColor(v, target), Modifier.weight(1f).fillMaxWidth(), minChars = 5f, heightFraction = 1.2f, tight = true)
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
