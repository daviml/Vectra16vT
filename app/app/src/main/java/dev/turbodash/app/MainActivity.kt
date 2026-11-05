package dev.turbodash.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import dev.turbodash.app.ui.Palette
import dev.turbodash.app.ui.TurboDashTheme
import dev.turbodash.app.ui.dash.DashboardScreen
import dev.turbodash.app.ui.dash.MenuButton
import dev.turbodash.core.dash.DashStyle
import kotlinx.coroutines.launch
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
    // No tab bar: the whole screen is for the gauges, a ☰ button opens this side menu.
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    fun closeMenu() { scope.launch { drawer.close() } }
    BackHandler(enabled = drawer.isOpen) { closeMenu() }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Only a tap on ☰ opens it: an edge swipe would fight the dashboard pages.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(280.dp), drawerContainerColor = Palette.surface) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Text(
                        Brand.name.uppercase(), color = Palette.accent, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                    )
                    Tab.entries.forEach { t ->
                        NavigationDrawerItem(
                            label = { Text(t.label) }, icon = { Icon(t.icon, contentDescription = null) },
                            selected = tab == t, onClick = { tab = t; closeMenu() },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.track)
                    Text("Estilo do painel", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                    DashStyle.entries.forEach { st ->
                        NavigationDrawerItem(
                            label = { Text(st.label) }, icon = { RadioButton(selected = dashStyle == st, onClick = null) },
                            selected = false,
                            onClick = { dashStyle = st; app.store.dashStyle = st; tab = Tab.DASH; closeMenu() },
                        )
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
            if (tab == Tab.DASH) {
                DashboardScreen(app.sessions, dashStyle, onOpenMenu = openMenu, onOpenSettings = { tab = Tab.SETTINGS })
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        MenuButton(openMenu)
                        Spacer(Modifier.width(12.dp))
                        Text(tab.label, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            Tab.MAPS -> MapsScreen(app.sessions)
                            Tab.LOGS -> LogsScreen(app.sessions) { app.store.alertConfig }
                            Tab.SETTINGS -> SettingsScreen(app.store, app.sessions)
                            Tab.DASH -> Unit
                        }
                    }
                }
            }
        }
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
