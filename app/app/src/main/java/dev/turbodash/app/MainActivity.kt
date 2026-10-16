package dev.turbodash.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import dev.turbodash.app.ui.Palette
import dev.turbodash.app.ui.TurboDashTheme
import dev.turbodash.app.ui.dash.DashboardScreen
import dev.turbodash.app.ui.logs.LogsScreen
import dev.turbodash.app.ui.maps.MapsScreen
import dev.turbodash.app.ui.settings.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    DASH("Painel", Icons.Filled.Home),
    MAPS("Mapas", Icons.Filled.DateRange),
    LOGS("Logs", Icons.Filled.List),
    SETTINGS("Ajustes", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TurboDashApp
        setContent {
            TurboDashTheme { AppRoot(app) }
        }
    }
}

@Composable
private fun AppRoot(app: TurboDashApp) {
    // Demo builds: once expired, the app does nothing but say so.
    var expired by remember { mutableStateOf(Trial.expired(app)) }
    if (Trial.enabled) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (!expired) {
                kotlinx.coroutines.delay(60_000)
                expired = Trial.expired(app)
            }
        }
    }
    if (expired) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.sessions.stop() }
        }
        ExpiredScreen()
        return
    }
    var tab by rememberSaveable { mutableStateOf(if (app.store.hasIni) Tab.DASH else Tab.SETTINGS) }
    var disclaimer by remember { mutableStateOf(!app.store.disclaimerAccepted) }
    var dashStyle by remember { mutableStateOf(app.store.dashStyle) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    @Composable
    fun Content(modifier: Modifier) {
        Box(modifier) {
            when (tab) {
                Tab.DASH -> DashboardScreen(
                    app.sessions, app.nav, style = dev.turbodash.core.dash.DashStyle.GAUGES,
                    onStyleChange = { dashStyle = it; app.store.dashStyle = it },
                    onOpenSettings = { tab = Tab.SETTINGS },
                )
                Tab.MAPS -> MapsScreen(app.sessions)
                Tab.LOGS -> LogsScreen(app.sessions) { app.store.alertConfig }
                Tab.SETTINGS -> SettingsScreen(app.store, app.sessions)
            }
        }
    }

    if (landscape) {
        // Side rail instead of the bottom bar: height is what is scarce on a car mount.
        Row(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
            NavigationRail(containerColor = Palette.surface) {
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label, fontSize = 11.sp) },
                    )
                }
            }
            Content(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Scaffold(
            containerColor = Palette.background,
            bottomBar = {
                NavigationBar(containerColor = Palette.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding -> Content(Modifier.fillMaxSize().padding(padding)) }
    }

    if (disclaimer) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { app.store.disclaimerAccepted = true; disclaimer = false }) { Text("Entendi") }
            },
            title = { Text("Antes de usar") },
            text = {
                Text(
                    "O ${Brand.name} mostra dados da sua MegaSquirt e avisa quando algo parece perigoso para o motor. " +
                        "Os alertas ajudam, mas não garantem nada: dirija com atenção e não mexa no celular dirigindo. " +
                        "Esta versão só lê a ECU — nenhuma alteração é gravada nela.",
                )
            },
        )
    }
}

@Composable
private fun ExpiredScreen() {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Brand.logo?.let {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(it), contentDescription = Brand.maker,
                modifier = Modifier.padding(bottom = 24.dp).height(56.dp),
            )
        }
        Text("Demonstração encerrada", color = Palette.text, fontSize = 22.sp)
        Text(
            "O período de teste do ${Brand.name} terminou.",
            color = Palette.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
