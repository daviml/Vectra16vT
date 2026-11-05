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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

fun valueOf(role: Role, s: Snapshot, roles: ChannelRoles): Double? = when (role) {
    Role.LAMBDA -> roles.lambda(s)
    Role.GEAR -> roles.value(s, role)?.takeIf { it > 0 } // 0 = no gear: shows "--"
    else -> roles.value(s, role)
}

/**
 * Page 0 is the main dashboard in the chosen [style] (Principal or Racing); swipe sideways for
 * the template pages (Diagnóstico).
 */
@Composable
fun DashboardScreen(
    sessions: SessionManager,
    style: DashStyle,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val snapshot by sessions.snapshot.collectAsStateWithLifecycle()
    val alerts by sessions.alerts.collectAsStateWithLifecycle()
    val state by sessions.state.collectAsStateWithLifecycle()
    val log by sessions.log.collectAsStateWithLifecycle()
    val ini by sessions.ini.collectAsStateWithLifecycle()
    val roles = remember(ini) { sessions.roles() }
    val tuneInfo by sessions.tune.collectAsStateWithLifecycle()
    val launch = dev.turbodash.core.ecu.Launch.state(snapshot, tuneInfo?.tune)
    // Pops windows can be 0.1 s long: keep the badge up at least ~0.7 s so it can be seen.
    val popsUntil = remember { longArrayOf(0L) } // plain holder: no recomposition on write
    val popsNow = dev.turbodash.core.ecu.Pops.active(snapshot, roles)
    val now = android.os.SystemClock.elapsedRealtime()
    if (popsNow) popsUntil[0] = now + 700
    val pops = popsNow || now < popsUntil[0]
    val pager = rememberPagerState { 1 + DashTemplates.all.size }

    // The dash is meant to stay on while driving.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val s = snapshot
    fun value(role: Role): Double? = if (s == null || roles == null) null else valueOf(role, s, roles)
    fun alertOf(role: Role): Severity? = alerts.filter { it.role == role }.maxByOrNull { it.severity.ordinal }?.severity
    val frame = HudFrame(
        value = { value(it) },
        alert = { alertOf(it) },
        lambdaTarget = if (s != null) roles?.lambdaTarget(s) else null,
        launch = launch,
        pops = pops,
    )

    @Composable
    fun Status() = StatusBar(state, log?.rows, launch, pops, onOpenMenu, onOpenSettings, Modifier.fillMaxWidth())

    // Each page draws its own top line, so swiping does not make the layout jump.
    HorizontalPager(pager, Modifier.fillMaxSize().background(Palette.background)) { page ->
        when {
            page == 0 && style == DashStyle.RACING ->
                RacingHud(frame, state, log?.rows, onOpenMenu, onOpenSettings, Modifier.fillMaxSize())
            page == 0 -> Column(Modifier.fillMaxSize()) {
                Status()
                MainHud(frame, Modifier.weight(1f).fillMaxWidth())
            }
            else -> Column(Modifier.fillMaxSize()) {
                Status()
                TemplatePage(DashTemplates.all[page - 1].rows, frame, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TemplatePage(rows: List<List<DashWidget>>, frame: HudFrame, modifier: Modifier) {
    fun dataFor(x: DashWidget): WidgetData {
        val children = x.stack.map { dataFor(it) }
        return WidgetData(
            value = frame.value(x.role),
            target = if (x.kind == WidgetKind.LAMBDA) frame.lambdaTarget else null,
            alert = (if (x.kind == WidgetKind.STACK) children.mapNotNull { it.alert } else listOfNotNull(frame.alert(x.role))).maxByOrNull { it.ordinal },
            children = children,
        )
    }

    BoxWithConstraints(modifier.padding(4.dp)) {
        @Composable
        fun RowsColumn(part: List<List<DashWidget>>, modifier: Modifier) {
            Column(modifier) {
                part.forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(rowWeight(row))) {
                        row.forEach { w -> Widget(w, dataFor(w), Modifier.weight(w.weight).fillMaxSize()) }
                    }
                }
            }
        }
        if (maxWidth > maxHeight) {
            // Landscape (phone on a car mount): split the template in two columns.
            val split = if (rows.first().any { it.kind == WidgetKind.ARC || it.kind == WidgetKind.STACK }) 1 else (rows.size + 1) / 2
            Row(Modifier.fillMaxSize()) {
                RowsColumn(rows.take(split), Modifier.weight(1f).fillMaxSize())
                RowsColumn(rows.drop(split), Modifier.weight(1.15f).fillMaxSize())
            }
        } else {
            RowsColumn(rows, Modifier.fillMaxSize())
        }
    }
}

private fun rowWeight(row: List<DashWidget>): Float = row.maxOf {
    when (it.kind) {
        WidgetKind.ARC -> 1.85f
        WidgetKind.LAMBDA -> 0.6f
        WidgetKind.NUMBER -> 1f
        WidgetKind.BAR -> 0.6f
        WidgetKind.STACK -> 2.0f
    }
}

@Composable
private fun StatusBar(
    state: SessionState?,
    logRows: Int?,
    launch: dev.turbodash.core.ecu.LaunchState,
    pops: Boolean,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val (text, color) = when (state) {
        null -> "Desconectado — toque para conectar" to Palette.muted
        is SessionState.Connecting -> "Conectando… (tentativa ${state.attempt})" to Palette.warn
        is SessionState.ReadingTune -> "Lendo calibração da ECU ${(state.progress * 100).toInt()}%" to Palette.warn
        is SessionState.Running -> "${state.ecuSignature.trim()} • %.0f Hz".format(state.hz) to Palette.ok
        is SessionState.Failed -> (if (state.retrying) "Falha: ${state.message} — tentando de novo" else state.message) to Palette.danger
        SessionState.Stopped -> "Desconectado" to Palette.muted
    }
    Row(
        modifier.clickable(onClick = onOpenSettings).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton(onOpenMenu)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        // Launch / pops: a badge takes the place of the connection text (no extra space).
        when {
            launch != dev.turbodash.core.ecu.LaunchState.OFF ->
                Box(Modifier.weight(1f)) { LaunchBadge(active = launch == dev.turbodash.core.ecu.LaunchState.ACTIVE) }
            pops -> Box(Modifier.weight(1f)) { PopsBadge() }
            else -> Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        }
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


/** Outline while the launch button is held; filled and pulsing once the ECU is holding the launch rpm. */
@Composable
private fun LaunchBadge(active: Boolean) {
    val pulse by rememberInfiniteTransition(label = "launch").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "launch",
    )
    val shape = RoundedCornerShape(6.dp)
    Text(
        if (active) "LAUNCH ATIVO" else "LAUNCH",
        color = if (active) Palette.background else Palette.accent,
        fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
        modifier = Modifier
            .alpha(if (active) pulse else 1f)
            .clip(shape)
            .then(if (active) Modifier.background(Palette.accent) else Modifier.border(1.5.dp, Palette.accent, shape))
            .padding(horizontal = 10.dp, vertical = 1.dp),
    )
}

/** Overrun pops window (foot off, retarded spark, fuel still on): red, flickering like a backfire. */
@Composable
private fun PopsBadge() {
    val flicker by rememberInfiniteTransition(label = "pops").animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "pops",
    )
    Text(
        "POPS",
        color = Color.White,
        fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
        modifier = Modifier
            .alpha(flicker)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.danger)
            .padding(horizontal = 10.dp, vertical = 1.dp),
    )
}
