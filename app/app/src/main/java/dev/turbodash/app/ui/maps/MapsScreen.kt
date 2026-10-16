package dev.turbodash.app.ui.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turbodash.app.ecu.SessionManager
import dev.turbodash.app.ui.Palette
import dev.turbodash.core.tune.TableView
import java.util.Locale

@Composable
fun MapsScreen(sessions: SessionManager) {
    val tuneInfo by sessions.tune.collectAsStateWithLifecycle()
    val snapshot by sessions.snapshot.collectAsStateWithLifecycle()
    val info = tuneInfo
    if (info == null) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Conecte na ECU ou importe uma calibração (.msq) em Ajustes para ver os mapas.", color = Palette.muted)
        }
        return
    }
    val tables = remember(info) {
        info.tune.ini.tables.mapNotNull { TableView.from(info.tune, it) }
            .distinctBy { it.def.zBins }
    }
    var selected by rememberSaveable { mutableStateOf(tables.indexOfFirst { it.def.zBins == "veTable1" }.coerceAtLeast(0)) }
    var menu by remember { mutableStateOf(false) }
    val table = tables.getOrNull(selected) ?: return

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(table.def.title) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    tables.forEachIndexed { i, t ->
                        DropdownMenuItem(text = { Text(t.def.title) }, onClick = { selected = i; menu = false })
                    }
                }
            }
        }
        Text(
            (if (info.fromEcu) "Lido da ECU" else "Do arquivo .msq") + " • somente leitura • eixo X: ${table.xUnits} • Y: ${table.yUnits} • valores: ${table.zUnits}",
            color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        val x = table.def.xChannel?.let { snapshot?.get(it) }
        val y = table.def.yChannel?.let { snapshot?.get(it) }
        if (x != null && y != null) {
            Text(
                "Agora: %.0f × %.0f → %.${table.zDigits}f".format(Locale.US, x, y, table.interpolate(x, y)),
                color = Palette.accent, fontSize = 14.sp,
            )
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Heatmap(table, if (x != null && y != null) table.position(x, y) else null)
        }
    }
}

/** Grid with load on the vertical axis (highest at the top, like TunerStudio) and rpm across. */
@Composable
private fun Heatmap(t: TableView, cursor: Pair<Double, Double>?) {
    val measurer = rememberTextMeasurer()
    val cellW = 48.dp
    val cellH = 30.dp
    val axisW = 52.dp
    val axisH = 26.dp
    val nx = t.xs.size
    val ny = t.ys.size
    val lo = t.min
    val hi = t.max
    val style = TextStyle(color = Color.Black, fontSize = 11.sp)
    val axisStyle = TextStyle(color = Palette.muted, fontSize = 11.sp)

    Canvas(Modifier.size(axisW + cellW * nx, axisH + cellH * ny)) {
        val cw = cellW.toPx()
        val ch = cellH.toPx()
        val ax = axisW.toPx()
        val ah = axisH.toPx()
        fun rowTop(yi: Int) = (ny - 1 - yi) * ch
        for (yi in 0 until ny) {
            val ty = rowTop(yi)
            val label = measurer.measure(fmtAxis(t.ys[yi]), axisStyle)
            drawText(label, topLeft = Offset(ax - label.size.width - 6, ty + (ch - label.size.height) / 2))
            for (xi in 0 until nx) {
                val v = t.at(xi, yi)
                val f = if (hi > lo) ((v - lo) / (hi - lo)).toFloat() else 0.5f
                drawRect(heat(f), Offset(ax + xi * cw, ty), Size(cw - 1, ch - 1))
                val txt = measurer.measure("%.${t.zDigits.coerceAtMost(3)}f".format(Locale.US, v), style)
                drawText(txt, topLeft = Offset(ax + xi * cw + (cw - txt.size.width) / 2, ty + (ch - txt.size.height) / 2))
            }
        }
        for (xi in 0 until nx) {
            val label = measurer.measure(fmtAxis(t.xs[xi]), axisStyle)
            drawText(label, topLeft = Offset(ax + xi * cw + (cw - label.size.width) / 2, ny * ch + (ah - label.size.height) / 2))
        }
        cursor?.let { (fx, fy) ->
            val cx = ax + (fx.toFloat() + 0.5f) * cw
            val cy = (ny - 1 - fy.toFloat()) * ch + ch / 2
            val xi = fx.toInt().coerceIn(0, nx - 1)
            val yi = fy.toInt().coerceIn(0, ny - 1)
            drawRect(Color.White, Offset(ax + xi * cw, rowTop(yi)), Size(cw - 1, ch - 1), style = Stroke(3f))
            drawCircle(Color.White, 7f, Offset(cx, cy))
            drawCircle(Color.Black, 7f, Offset(cx, cy), style = Stroke(2f))
        }
    }
}

private fun fmtAxis(v: Double) = if (v >= 100 || v == Math.floor(v)) "%.0f".format(Locale.US, v) else "%.1f".format(Locale.US, v)

/** Blue → green → yellow → red. */
private fun heat(f: Float): Color {
    val stops = listOf(Color(0xFF4EA8DE), Color(0xFF2EC4B6), Color(0xFFFFE066), Color(0xFFFF9F1C), Color(0xFFE63946))
    val p = f.coerceIn(0f, 1f) * (stops.size - 1)
    val i = p.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], p - i)
}
