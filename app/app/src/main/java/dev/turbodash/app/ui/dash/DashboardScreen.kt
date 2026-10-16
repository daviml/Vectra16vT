package dev.turbodash.app.ui.dash

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turbodash.app.ecu.SessionManager
import dev.turbodash.app.ui.Palette
import dev.turbodash.core.alerts.Severity
import dev.turbodash.core.dash.DashStyle
import dev.turbodash.core.dash.DashTemplates
import dev.turbodash.core.dash.DashWidget
import dev.turbodash.core.dash.WidgetKind
import dev.turbodash.core.ecu.ChannelRoles
import dev.turbodash.core.ecu.Role
import dev.turbodash.core.ecu.SessionState
import dev.turbodash.core.ecu.Snapshot

/** Last ~30 s of every role, sampled at 4 Hz, for the Digital style trend lines. */
class DashHistory {
    companion object { const val CAPACITY = 120 }

    private val series = HashMap<Role, ArrayDeque<Float>>()
    private var lastTime = -1.0

    fun add(s: Snapshot, roles: ChannelRoles) {
        if (s.time < lastTime) { series.clear(); lastTime = -1.0 } // new session
        if (lastTime >= 0 && s.time - lastTime < 0.25) return
        lastTime = s.time
        for (role in Role.entries) {
            val v = valueOf(role, s, roles) ?: continue
            val q = series.getOrPut(role) { ArrayDeque(CAPACITY) }
            if (q.size == CAPACITY) q.removeFirst()
            q.addLast(v.toFloat())
        }
    }

    fun get(role: Role): FloatArray? = series[role]?.toFloatArray()
}

fun valueOf(role: Role, s: Snapshot, roles: ChannelRoles): Double? =
    if (role == Role.LAMBDA) roles.lambda(s) else roles.value(s, role)

@Composable
fun DashboardScreen(
    sessions: SessionManager,
    nav: dev.turbodash.app.nav.Navigation,
    style: DashStyle,
    onStyleChange: (DashStyle) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val snapshot by sessions.snapshot.collectAsStateWithLifecycle()
    val alerts by sessions.alerts.collectAsStateWithLifecycle()
    val state by sessions.state.collectAsStateWithLifecycle()
    val log by sessions.log.collectAsStateWithLifecycle()
    val ini by sessions.ini.collectAsStateWithLifecycle()
    val roles = remember(ini) { sessions.roles() }
    val history = remember { DashHistory() }
    var showDestinations by remember { mutableStateOf(false) }
    if (showDestinations) DestinationsDialog(nav, sessions.gps) { showDestinations = false }
    val pager = rememberPagerState { DashTemplates.all.size }

    LaunchedEffect(snapshot) {
        val s = snapshot
        if (s != null && roles != null) history.add(s, roles)
    }

    // The dash is meant to stay on while driving.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().background(Palette.background)) {
        // No tab names: swipe sideways to switch between Rua and Diagnóstico.
        StatusBar(state, log?.rows, onOpenSettings, Modifier.fillMaxWidth())
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val rows = DashTemplates.all[page].rows
            BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
                @Composable
                fun RowsColumn(part: List<List<DashWidget>>, modifier: Modifier) {
                    Column(modifier) {
                        part.forEach { row ->
                            Row(Modifier.fillMaxWidth().weight(rowWeight(row, style))) {
                                row.forEach { w ->
                                    if (w.kind == WidgetKind.MINIMAP) {
                                        MiniMap(sessions.gps, nav, { showDestinations = true }, Modifier.weight(w.weight).fillMaxSize().padding(3.dp).clip(RoundedCornerShape(12.dp)))
                                        return@forEach
                                    }
                                    val s = snapshot
                                    fun dataFor(x: DashWidget) = WidgetData(
                                        value = if (s == null || roles == null) null else valueOf(x.role, s, roles),
                                        alert = alerts.filter { it.role == x.role }.minByOrNull { it.severity.ordinal * -1 }?.severity,
                                    )
                                    val children = w.stack.map { dataFor(it) }
                                    val data = WidgetData(
                                        children = children,
                                        value = if (s == null || roles == null) null else valueOf(w.role, s, roles),
                                        target = if (s != null && w.kind == WidgetKind.LAMBDA) roles?.lambdaTarget(s) else null,
                                        alert = (if (w.kind == WidgetKind.STACK) children.mapNotNull { it.alert } else alerts.filter { it.role == w.role }.map { it.severity }).maxByOrNull { it.ordinal },
                                        history = if (style == DashStyle.DIGITAL) history.get(w.role) else null,
                                    )
                                    Widget(w, data, style, Modifier.weight(w.weight).fillMaxSize())
                                }
                            }
                        }
                    }
                }
                if (maxWidth > maxHeight) {
                    // Landscape (phone on a car mount): split the template in two columns.
                    val split = if (rows.first().any { it.kind == WidgetKind.ARC || it.kind == WidgetKind.STACK || it.kind == WidgetKind.MINIMAP }) 1 else (rows.size + 1) / 2
                    Row(Modifier.fillMaxSize()) {
                        RowsColumn(rows.take(split), Modifier.weight(1f).fillMaxSize())
                        RowsColumn(rows.drop(split), Modifier.weight(1.15f).fillMaxSize())
                    }
                } else {
                    RowsColumn(rows, Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun rowWeight(row: List<DashWidget>, style: DashStyle): Float = row.maxOf {
    when (it.kind) {
        WidgetKind.ARC -> if (style == DashStyle.DIGITAL) 1.5f else 1.85f
        WidgetKind.LAMBDA -> 0.6f
        WidgetKind.NUMBER -> if (style == DashStyle.DIGITAL) 1.15f else 1f
        WidgetKind.BAR -> 0.6f
        WidgetKind.STACK, WidgetKind.MINIMAP -> 2.0f
    }
}

@Composable
private fun StatusBar(state: SessionState?, logRows: Int?, onOpenSettings: () -> Unit, modifier: Modifier) {
    val (text, color) = when (state) {
        null -> "Desconectado — toque para conectar" to Palette.muted
        is SessionState.Connecting -> "Conectando… (tentativa ${state.attempt})" to Palette.warn
        is SessionState.ReadingTune -> "Lendo calibração da ECU ${(state.progress * 100).toInt()}%" to Palette.warn
        is SessionState.Running -> "${state.ecuSignature.trim()} • %.0f Hz".format(state.hz) to Palette.ok
        is SessionState.Failed -> (if (state.retrying) "Falha: ${state.message} — tentando de novo" else state.message) to Palette.danger
        SessionState.Stopped -> "Desconectado" to Palette.muted
    }
    Row(
        modifier.clickable(onClick = onOpenSettings).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        // Brand logo, small, on the status line only (the gauges stay clean).
        dev.turbodash.app.Brand.logo?.let { logo ->
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(logo), contentDescription = null, modifier = Modifier.height(18.dp).padding(end = 8.dp))
        }
        if (logRows != null) {
            val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
                0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "rec",
            )
            Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(Palette.danger))
            Spacer(Modifier.width(6.dp))
            Text("REC $logRows", color = Palette.danger, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

