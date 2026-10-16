package dev.turbodash.app.ui.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.turbodash.app.ui.Palette
import dev.turbodash.core.alerts.Severity
import dev.turbodash.core.dash.DashStyle
import dev.turbodash.core.dash.DashWidget
import dev.turbodash.core.dash.WidgetKind
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Color for a value given the widget's warning/danger limits. */
fun levelColor(w: DashWidget, v: Double?): Color {
    if (v == null) return Palette.muted
    val dh = w.dangerHigh
    val dl = w.dangerLow
    val wh = w.warnHigh
    val wl = w.warnLow
    return when {
        dh != null && v >= dh -> Palette.danger
        dl != null && v <= dl -> Palette.danger
        wh != null && v >= wh -> Palette.warn
        wl != null && v <= wl -> Palette.warn
        else -> Palette.text
    }
}

fun fmt(v: Double?, digits: Int): String = if (v == null || v.isNaN()) "--" else "%.${digits}f".format(Locale.US, v)

/** Font size (sp) for [chars] monospace characters that must fit in a box of the given dp size. */
private fun fitText(chars: Int, widthDp: Float, heightDp: Float, max: Float): Float =
    min(widthDp / (chars.coerceAtLeast(2) * 0.62f), heightDp * 0.8f).coerceIn(10f, max)

/** Everything a widget needs to draw itself for one frame. */
class WidgetData(
    val value: Double?,
    val target: Double? = null,
    val alert: Severity? = null,
    /** Recent values, oldest first (Digital style trend line). */
    val history: FloatArray? = null,
    /** Values of [DashWidget.stack] for a [WidgetKind.STACK] cell. */
    val children: List<WidgetData> = emptyList(),
)

@Composable
fun Widget(w: DashWidget, data: WidgetData, style: DashStyle, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val alertColor = when (data.alert) {
        Severity.CRITICAL -> Palette.danger
        Severity.WARNING -> Palette.warn
        null -> null
    }
    var m = modifier.padding(3.dp).clip(shape).background(
        if (alertColor != null) alertColor.copy(alpha = 0.12f).compositeOver(Palette.surface) else Palette.surface,
    )
    if (alertColor != null) m = m.border(2.dp, alertColor, shape)
    when {
        w.kind == WidgetKind.LAMBDA -> LambdaBar(w, data, m)
        w.kind == WidgetKind.STACK -> StackTile(w, data, m)
        style == DashStyle.DIGITAL -> DigitalTile(w, data, m)
        w.kind == WidgetKind.ARC -> ArcGauge(w, data.value, m)
        w.kind == WidgetKind.BAR -> BarGauge(w, data.value, m)
        else -> NumberTile(w, data.value, m)
    }
}

private fun Color.compositeOver(bg: Color) = Color(
    red = red * alpha + bg.red * (1 - alpha),
    green = green * alpha + bg.green * (1 - alpha),
    blue = blue * alpha + bg.blue * (1 - alpha),
)

@Composable
private fun TitleRow(w: DashWidget, sizeSp: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(w.title, color = Palette.muted, fontSize = sizeSp.sp, lineHeight = sizeSp.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (w.units.isNotEmpty()) Text(w.units, color = Palette.muted.copy(alpha = 0.7f), fontSize = (sizeSp * 0.85f).sp, lineHeight = sizeSp.sp, maxLines = 1)
    }
}

@Composable
fun NumberTile(w: DashWidget, value: Double?, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        val text = fmt(value, w.digits)
        val titleSp = (maxHeight.value * 0.15f).coerceIn(10f, 14f)
        val rulerDp = (maxHeight.value * 0.09f).coerceIn(6f, 11f)
        val numberH = maxHeight.value - titleSp * 1.4f - rulerDp - 10f
        // Proportional bold digits (tabular, so values don't jitter) are narrower than monospace:
        // the number can be much larger in the same tile. A dot counts as half a digit.
        val units = maxOf(text.count { it != '.' } + 0.5f * text.count { it == '.' }, 3f)
        val big = min(maxWidth.value / (units * 0.6f), numberH * 0.95f).coerceIn(12f, 110f)
        Column(Modifier.fillMaxSize()) {
            TitleRow(w, titleSp)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text, color = levelColor(w, value), fontSize = big.sp, lineHeight = big.sp, fontWeight = FontWeight.Bold,
                    style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false,
                )
            }
            Ruler(w, value, Modifier.fillMaxWidth().height(rulerDp.dp))
        }
    }
}

/**
 * Horizontal scale like the lambda bar: track colored by zone (normal / warning / danger)
 * and a dot at the current value.
 */
@Composable
fun Ruler(w: DashWidget, value: Double?, modifier: Modifier) {
    val span = (w.max - w.min).takeIf { it > 0 } ?: 1.0
    fun frac(v: Double) = ((v - w.min) / span).coerceIn(0.0, 1.0).toFloat()
    val dotColor = levelColor(w, value).let { if (it == Palette.text) Palette.ok else it }
    Canvas(modifier) {
        val h = size.height
        val r = CornerRadius(h / 2)
        drawRoundRect(Palette.ok.copy(alpha = 0.28f), cornerRadius = r)
        fun zone(from: Double, to: Double, c: Color) {
            val x0 = size.width * frac(from)
            val x1 = size.width * frac(to)
            if (x1 > x0) drawRect(c, Offset(x0, 0f), Size(x1 - x0, h))
        }
        // Low zones on the left, high zones on the right (rect edges are hidden under the round ends).
        w.warnLow?.let { zone(w.min, it, Palette.warn.copy(alpha = 0.45f)) }
        w.dangerLow?.let { zone(w.min, it, Palette.danger.copy(alpha = 0.6f)) }
        w.warnHigh?.let { zone(it, w.max, Palette.warn.copy(alpha = 0.45f)) }
        w.dangerHigh?.let { zone(it, w.max, Palette.danger.copy(alpha = 0.6f)) }
        value?.let {
            val c = Offset(size.width * frac(it), h / 2)
            drawCircle(Palette.background, h * 0.95f, c)
            drawCircle(dotColor, h * 0.75f, c)
        }
    }
}

/** Several number tiles (with their rulers) stacked in one cell, e.g. RPM over speed. */
@Composable
fun StackTile(w: DashWidget, data: WidgetData, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        w.stack.forEachIndexed { i, child ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 10.dp).background(Palette.track))
            NumberTile(child, data.children.getOrNull(i)?.value, Modifier.weight(1f))
        }
    }
}

/** Digital style: number, units and a trend line of the last ~30 s. */
@Composable
fun DigitalTile(w: DashWidget, data: WidgetData, modifier: Modifier) {
    val value = data.value
    val color = levelColor(w, value)
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        val text = fmt(value, w.digits)
        val titleSp = (maxHeight.value * 0.16f).coerceIn(10f, 15f)
        val trendH = (maxHeight.value * 0.28f).coerceIn(10f, 60f)
        val units = maxOf(text.count { it != '.' } + 0.5f * text.count { it == '.' }, 3f)
        val big = min(maxWidth.value / (units * 0.6f), (maxHeight.value - titleSp * 1.5f - trendH) * 0.9f).coerceIn(12f, 110f)
        Column(Modifier.fillMaxSize()) {
            TitleRow(w, titleSp)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text, color = color, fontSize = big.sp, lineHeight = big.sp, fontWeight = FontWeight.Bold,
                    style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false,
                )
            }
            Trend(w, data.history, if (color == Palette.text) Palette.accent else color, Modifier.fillMaxWidth().height(trendH.dp))
        }
    }
}

/** Line of recent values scaled to the widget range, with warning/danger limits as faint lines. */
@Composable
private fun Trend(w: DashWidget, history: FloatArray?, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val span = (w.max - w.min).takeIf { it > 0 } ?: 1.0
        val h = history?.takeIf { it.size >= 2 } ?: return@Canvas
        // Zoom on the data so slow changes (air temperature creeping up) are visible, but never
        // below 15% of the gauge range, so sensor noise does not look like a big swing.
        var lo = h.min().toDouble()
        var hi = h.max().toDouble()
        val minWindow = span * 0.15
        if (hi - lo < minWindow) { val mid = (hi + lo) / 2; lo = mid - minWindow / 2; hi = mid + minWindow / 2 }
        val pad = (hi - lo) * 0.1
        lo -= pad; hi += pad
        fun y(v: Double) = size.height * (1f - ((v - lo) / (hi - lo)).toFloat())
        // Limits drawn on the same scale as the line, only when they fall inside the window:
        // the line crossing a limit means the value really crossed it.
        listOfNotNull(w.warnHigh, w.warnLow).filter { it in lo..hi }
            .forEach { drawLine(Palette.warn.copy(alpha = 0.5f), Offset(0f, y(it)), Offset(size.width, y(it)), 1.5f) }
        listOfNotNull(w.dangerHigh, w.dangerLow).filter { it in lo..hi }
            .forEach { drawLine(Palette.danger.copy(alpha = 0.6f), Offset(0f, y(it)), Offset(size.width, y(it)), 1.5f) }
        val dx = size.width / (DashHistory.CAPACITY - 1)
        val x0 = size.width - dx * (h.size - 1)
        val path = Path()
        h.forEachIndexed { i, v ->
            val yy = y(v.toDouble()).coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x0, yy) else path.lineTo(x0 + dx * i, yy)
        }
        drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}

@Composable
fun ArcGauge(w: DashWidget, value: Double?, modifier: Modifier) {
    val sweep = 260f
    val startAngle = 140f
    fun frac(v: Double) = ((v - w.min) / (w.max - w.min)).coerceIn(0.0, 1.0).toFloat()
    BoxWithConstraints(modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
        val sizeDp = min(maxWidth.value, maxHeight.value)
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.08f
            val d = size.minDimension - stroke
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            val arcSize = Size(d, d)
            drawArc(Palette.track, startAngle, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            w.warnHigh?.let { wh ->
                val end = w.dangerHigh ?: w.max
                drawArc(Palette.warn.copy(alpha = 0.55f), startAngle + sweep * frac(wh), sweep * (frac(end) - frac(wh)), false, topLeft, arcSize, style = Stroke(stroke * 0.45f))
            }
            w.dangerHigh?.let { dh ->
                drawArc(Palette.danger.copy(alpha = 0.7f), startAngle + sweep * frac(dh), sweep * (1f - frac(dh)), false, topLeft, arcSize, style = Stroke(stroke * 0.45f))
            }
            if (value != null) {
                val f = frac(value)
                val c = levelColor(w, value).let { if (it == Palette.text) Palette.accent else it }
                drawArc(c, startAngle, sweep * f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val angle = Math.toRadians((startAngle + sweep * f).toDouble())
                val r = d / 2 - stroke * 1.6f
                drawLine(Palette.text.copy(alpha = 0.8f), center, Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat()), strokeWidth = stroke * 0.3f, cap = StrokeCap.Round)
                drawCircle(Palette.text, stroke * 0.45f, center)
            }
        }
        val valueSp = (sizeDp * 0.17f).coerceIn(12f, 60f)
        val titleSp = (sizeDp * 0.07f).coerceIn(9f, 16f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (sizeDp * 0.25f).dp)) {
            Text(
                fmt(value, w.digits), color = levelColor(w, value), fontSize = valueSp.sp, lineHeight = valueSp.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1,
            )
            Text(
                if (w.units.isEmpty()) w.title else "${w.title} · ${w.units}",
                color = Palette.muted, fontSize = titleSp.sp, lineHeight = titleSp.sp, maxLines = 1,
            )
        }
    }
}

@Composable
fun BarGauge(w: DashWidget, value: Double?, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val textSp = (maxHeight.value * 0.4f).coerceIn(10f, 20f)
        val barDp = (maxHeight.value * 0.25f).coerceIn(4f, 14f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(w.title, color = Palette.muted, fontSize = (textSp * 0.75f).sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text(fmt(value, w.digits), color = levelColor(w, value), fontSize = textSp.sp, lineHeight = textSp.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Canvas(Modifier.fillMaxWidth().height(barDp.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Palette.track, cornerRadius = r)
                if (value != null) {
                    val f = ((value - w.min) / (w.max - w.min)).coerceIn(0.0, 1.0).toFloat()
                    val c = levelColor(w, value).let { if (it == Palette.text) Palette.ok else it }
                    drawRoundRect(c, size = Size(size.width * f, size.height), cornerRadius = r)
                }
            }
        }
    }
}

/**
 * Compact lambda row: label and target on one line, a thin rich→lean gradient bar under it,
 * and the value on the right. The orange tick is the target, the white tick lambda 1.0.
 */
@Composable
fun LambdaBar(w: DashWidget, data: WidgetData, modifier: Modifier) {
    val value = data.value
    val target = data.target
    fun frac(v: Double) = ((v - w.min) / (w.max - w.min)).coerceIn(0.0, 1.0).toFloat()
    val lean = value != null && target != null && value > target + 0.05
    val color = when {
        value == null -> Palette.muted
        lean -> Palette.danger
        target != null && value < target - 0.08 -> Palette.info
        else -> Palette.ok
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val h = maxHeight.value
        val valueSp = (h * 0.55f).coerceIn(14f, 40f)
        val labelSp = (h * 0.2f).coerceIn(9f, 13f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row {
                    Text(w.title, color = Palette.muted, fontSize = labelSp.sp, lineHeight = labelSp.sp)
                    Spacer(Modifier.width(8.dp))
                    if (target != null) Text("alvo ${fmt(target, 2)}", color = Palette.accent, fontSize = labelSp.sp, lineHeight = labelSp.sp)
                    Spacer(Modifier.weight(1f))
                    Text("rico ◂ ▸ pobre", color = Palette.muted.copy(alpha = 0.7f), fontSize = (labelSp * 0.85f).sp, lineHeight = labelSp.sp)
                }
                Spacer(Modifier.height(4.dp))
                Canvas(Modifier.fillMaxWidth().height((h * 0.22f).coerceIn(8f, 14f).dp)) {
                    val bh = size.height
                    drawRoundRect(
                        Brush.horizontalGradient(
                            0f to Palette.info.copy(alpha = 0.45f),
                            frac(1.0) to Palette.ok.copy(alpha = 0.35f),
                            1f to Palette.danger.copy(alpha = 0.5f),
                        ),
                        cornerRadius = CornerRadius(bh / 2),
                    )
                    val x1 = size.width * frac(1.0)
                    drawLine(Palette.text.copy(alpha = 0.6f), Offset(x1, 0f), Offset(x1, bh), strokeWidth = 2f)
                    target?.let {
                        val xt = size.width * frac(it)
                        drawLine(Palette.accent, Offset(xt, -3f), Offset(xt, bh + 3f), strokeWidth = 5f)
                    }
                    value?.let {
                        val c = Offset(size.width * frac(it), bh / 2)
                        drawCircle(Palette.background, bh * 0.75f, c)
                        drawCircle(color, bh * 0.6f, c)
                    }
                }
            }
            Text(
                fmt(value, w.digits), color = color, fontSize = valueSp.sp, lineHeight = valueSp.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}
