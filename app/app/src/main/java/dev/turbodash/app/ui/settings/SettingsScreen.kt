package dev.turbodash.app.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turbodash.app.data.ProfileStore
import dev.turbodash.app.ecu.SessionManager
import dev.turbodash.app.ecu.Target
import dev.turbodash.app.ui.Palette
import dev.turbodash.app.ui.logs.share
import dev.turbodash.core.alerts.AlertConfig
import dev.turbodash.core.ecu.SessionState
import dev.turbodash.core.sim.Scenario
import dev.turbodash.core.tune.Msq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class BtDevice(val name: String, val address: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(store: ProfileStore, sessions: SessionManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by sessions.state.collectAsStateWithLifecycle()
    val target by sessions.target.collectAsStateWithLifecycle()
    val ini by sessions.ini.collectAsStateWithLifecycle()
    val tuneInfo by sessions.tune.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) } // bump to re-read store values
    var choice by remember { mutableStateOf<Target?>(null) }

    fun runIo(block: () -> String) {
        scope.launch {
            message = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "Erro: ${it.message}" } }
            version++
        }
    }

    val pickIni = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runIo {
            val (name, bytes) = readUri(context, uri)
            store.importIni(name, bytes)
            sessions.refreshProfile()
            "INI importado: ${store.ini()?.signature?.trim()}"
        }
    }
    val pickMsq = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runIo {
            val (name, bytes) = readUri(context, uri)
            store.importMsq(name, bytes)
            sessions.refreshProfile()
            "Calibração importada: $name"
        }
    }
    val pickProps = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runIo {
            val (_, bytes) = readUri(context, uri)
            val r = store.importProjectProperties(bytes)
            sessions.refreshProfile()
            "Projeto do TunerStudio aplicado: $r"
        }
    }
    var btGranted by remember { mutableStateOf(hasBtPermission(context)) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        btGranted = hasBtPermission(context)
    }
    var gpsGranted by remember { mutableStateOf(sessions.gps.hasPermission()) }
    val askGps = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        gpsGranted = sessions.gps.hasPermission()
        // Already connected: start reading speed right away.
        if (gpsGranted) sessions.gps.refresh()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Demo builds: the only place the expiry is shown.
        dev.turbodash.app.Trial.expiresAt(context)?.let { end ->
            val left = dev.turbodash.app.Trial.daysLeft(context) ?: 0
            Section("Demonstração") {
                Text(
                    (if (left <= 1) "Último dia de teste" else "Faltam $left dias de teste") +
                        " • expira em " + SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()).format(Date(end)),
                    fontSize = 14.sp,
                )
            }
        }
        message?.let { Text(it, color = if (it.startsWith("Erro")) Palette.danger else Palette.ok) }

        // ------------------------------------------------------------ connection
        Section("Conexão") {
            val active = sessions.isActive
            val stateText = when (val s = state) {
                null -> "Desconectado"
                is SessionState.Connecting -> "Conectando… (tentativa ${s.attempt})"
                is SessionState.ReadingTune -> "Lendo calibração ${(s.progress * 100).toInt()}%"
                is SessionState.Running -> "Conectado: ${s.ecuSignature.trim()} (%.0f Hz)".format(s.hz)
                is SessionState.Failed -> s.message
                SessionState.Stopped -> "Desconectado"
            }
            Text(stateText, color = if (state is SessionState.Failed) Palette.danger else Palette.muted)
            target?.let { Text(it.label, fontSize = 12.sp, color = Palette.muted) }

            if (!btGranted && Build.VERSION.SDK_INT >= 31) {
                OutlinedButton(onClick = {
                    askPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
                }) { Text("Permitir Bluetooth") }
            }
            if (!gpsGranted) {
                OutlinedButton(onClick = {
                    askGps.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Permitir GPS (velocidade)") }
            }
            val devices = remember(btGranted, version) { if (btGranted) bondedDevices(context) else emptyList() }
            val saved = store.bluetoothAddress
            Text("Bluetooth pareado", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            if (btGranted && devices.isEmpty()) Text("Nenhum dispositivo pareado. Pareie o módulo da MegaSquirt nas configurações do Android.", color = Palette.muted, fontSize = 12.sp)
            devices.forEach { d ->
                val t = Target.Bluetooth(d.address, d.name)
                val sel = (choice ?: target ?: defaultChoice(saved, devices)) == t
                RadioRow(sel, "${d.name}  ${d.address}" + if (d.address == saved) "  (do TunerStudio)" else "") { choice = t }
            }
            Text("Sem o carro", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Scenario.entries.forEach { sc ->
                val t = Target.Simulator(sc)
                RadioRow((choice ?: target ?: defaultChoice(saved, devices)) == t, "Simulador: ${sc.label}") { choice = t }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                val pick = choice ?: target ?: defaultChoice(saved, devices)
                // Enabled even without the INI: pressing it then says what is missing.
                Button(enabled = pick != null, onClick = {
                    val t = pick ?: return@Button
                    if (t is Target.Bluetooth) store.bluetoothAddress = t.address
                    scope.launch(Dispatchers.IO) { sessions.start(t) }
                }) { Text(if (active) "Reconectar" else "Conectar") }
                if (active) {
                    Button(onClick = { scope.launch(Dispatchers.IO) { sessions.stop() } }, colors = ButtonDefaults.buttonColors(containerColor = Palette.danger)) {
                        Text("Desconectar")
                    }
                }
            }
            if (ini == null) {
                Text(
                    "Para conectar (no carro ou no simulador) falta o INI: toque em \"Importar INI\" em Perfil da ECU, " +
                        "logo abaixo, e escolha o mainController.ini.",
                    color = Palette.warn, fontSize = 13.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var auto by remember { mutableStateOf(store.autoLog) }
                Switch(auto, onCheckedChange = { auto = it; store.autoLog = it })
                Text("  Gravar log automaticamente ao conectar")
            }
        }

        // ------------------------------------------------------------ profile
        Section("Perfil da ECU") {
            val def = ini
            Text(
                if (def != null) "INI: ${store.iniName ?: "importado"}\nAssinatura: \"${def.signature}\" • ${def.constants.size} parâmetros • ${def.outputChannels.size} canais"
                else "Nenhum INI importado. Use o mainController.ini da pasta projectCfg do seu projeto TunerStudio.",
                color = if (def != null) Palette.text else Palette.warn, fontSize = 13.sp,
            )
            Text(
                if (store.hasMsq) "Calibração: ${store.msqName} (usada no simulador e para ver mapas offline)" else "Sem calibração .msq (opcional).",
                color = Palette.muted, fontSize = 13.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickIni.launch(arrayOf("*/*")) }) { Text("Importar INI") }
                OutlinedButton(onClick = { pickMsq.launch(arrayOf("*/*")) }) { Text("Importar .msq") }
                OutlinedButton(onClick = { pickProps.launch(arrayOf("*/*")) }) { Text("Importar project.properties") }
            }
            if (def != null) {
                Text("Opções do projeto (iguais às do TunerStudio)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text("O project.properties preenche isso sozinho. Ex.: CELSIUS, LAMBDA.", color = Palette.muted, fontSize = 12.sp)
                var syms by remember(version) { mutableStateOf(store.symbols) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    store.knownSymbols().forEach { s ->
                        FilterChip(selected = s in syms, onClick = {
                            syms = if (s in syms) syms - s else syms + s
                        }, label = { Text(s, fontSize = 12.sp) })
                    }
                }
                if (syms != store.symbols) {
                    Button(onClick = {
                        runIo {
                            store.symbols = syms
                            sessions.refreshProfile()
                            "Opções salvas. Reconecte para aplicar."
                        }
                    }) { Text("Salvar opções") }
                }
            }
        }

        // ------------------------------------------------------------ alerts
        Section("Alertas") {
            AlertEditor(store.alertConfig) { cfg ->
                store.alertConfig = cfg
                sessions.updateAlertConfig()
                message = "Limites de alerta salvos."
            }
        }

        // ------------------------------------------------------------ backup
        Section("Backup da calibração") {
            val info = tuneInfo
            Text(
                when {
                    info == null -> "Conecte para ler a calibração da ECU."
                    info.fromEcu -> "Calibração lida da ECU nesta conexão. Exporte um .msq para guardar ou abrir no TunerStudio."
                    else -> "Calibração do arquivo importado."
                },
                color = Palette.muted, fontSize = 13.sp,
            )
            Button(enabled = info != null, onClick = {
                val t = info?.tune ?: return@Button
                scope.launch {
                    val f = withContext(Dispatchers.IO) {
                        val dir = File(context.filesDir, "exports").apply { mkdirs() }
                        File(dir, SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).format(Date()) + "_ecu.msq").also {
                            it.writeText(Msq.write(t), Charsets.ISO_8859_1)
                        }
                    }
                    share(context, f)
                }
            }) { Text("Exportar .msq") }
        }

        Section("Sobre") {
            val installed = remember {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                "Versão ${info.versionName} • instalada em " +
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(info.lastUpdateTime))
            }
            dev.turbodash.app.Brand.logo?.let { logo ->
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(logo), contentDescription = dev.turbodash.app.Brand.maker,
                    modifier = Modifier.padding(vertical = 4.dp).height(48.dp),
                )
            }
            Text(dev.turbodash.app.Brand.name, fontWeight = FontWeight.Bold)
            if (dev.turbodash.app.Brand.maker.isNotEmpty()) Text(dev.turbodash.app.Brand.maker, fontSize = 13.sp)
            Text(installed, fontSize = 13.sp)
            if (dev.turbodash.app.Brand.demo) {
                Text(
                    "Versão de demonstração (proposta). Tecnologia TurboDash.",
                    color = Palette.accent, fontSize = 12.sp,
                )
            }
            Text(
                "Somente leitura: este app não grava nada na ECU. " +
                    "Alertas são uma ajuda, não substituem instrumentos e bom senso. Use por sua conta e risco.",
                color = Palette.muted, fontSize = 12.sp,
            )
            Text(
                "Mapa, endereços e rotas: © colaboradores do OpenStreetMap (ODbL), via OpenFreeMap, Nominatim e OSRM.",
                color = Palette.muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
            content()
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun AlertEditor(initial: AlertConfig, onSave: (AlertConfig) -> Unit) {
    var c by remember { mutableStateOf(initial) }
    @Composable
    fun field(label: String, value: Double?, set: (Double?) -> AlertConfig, modifier: Modifier = Modifier) {
        var text by remember(label) { mutableStateOf(value?.let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() } ?: "") }
        OutlinedTextField(
            text, onValueChange = { t -> text = t; c = set(t.replace(',', '.').toDoubleOrNull()) },
            label = { Text(label, fontSize = 11.sp) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field("Ar atenção °C", c.matWarnC, { c.copy(matWarnC = it ?: c.matWarnC) }, Modifier.weight(1f))
        field("Ar crítico °C", c.matCritC, { c.copy(matCritC = it ?: c.matCritC) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field("Água atenção °C", c.cltWarnC, { c.copy(cltWarnC = it ?: c.cltWarnC) }, Modifier.weight(1f))
        field("Água crítica °C", c.cltCritC, { c.copy(cltCritC = it ?: c.cltCritC) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field("Pobre: MAP mín. kPa", c.leanMinMapKpa, { c.copy(leanMinMapKpa = it ?: c.leanMinMapKpa) }, Modifier.weight(1f))
        field("Pobre: margem lambda", c.leanMarginLambda, { c.copy(leanMarginLambda = it ?: c.leanMarginLambda) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field("Duty atenção %", c.dutyWarnPct, { c.copy(dutyWarnPct = it ?: c.dutyWarnPct) }, Modifier.weight(1f))
        field("Duty crítico %", c.dutyCritPct, { c.copy(dutyCritPct = it ?: c.dutyCritPct) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field("Sonda no limite ±%", c.egoSaturationPct, { c.copy(egoSaturationPct = it ?: c.egoSaturationPct) }, Modifier.weight(1f))
        field("Overboost kPa (vazio = off)", c.overboostKpa, { c.copy(overboostKpa = it) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field("Combustível mín. bar", c.fuelMinBar, { c.copy(fuelMinBar = it ?: c.fuelMinBar) }, Modifier.weight(1f))
        Spacer(Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onSave(c) }) { Text("Salvar limites") }
        OutlinedButton(onClick = { c = AlertConfig(); onSave(c) }) { Text("Padrão") }
    }
    Text(
        "Mistura pobre só é avaliada com carga (MAP acima do mínimo e acelerador aberto): " +
            "pobre na lenta não quebra pistão, pobre com carga sim.",
        color = Palette.muted, fontSize = 12.sp,
    )
}

private fun defaultChoice(saved: String?, devices: List<BtDevice>): Target? =
    devices.firstOrNull { it.address == saved }?.let { Target.Bluetooth(it.address, it.name) }
        // Nothing paired yet (first run, demo): the boost simulator, so "Conectar" works right away.
        ?: if (devices.isEmpty()) Target.Simulator(dev.turbodash.core.sim.Scenario.BOOST_PULLS) else null

private fun hasBtPermission(context: Context) = Build.VERSION.SDK_INT < 31 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<BtDevice> = try {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    adapter?.bondedDevices?.map { BtDevice(it.name ?: "Sem nome", it.address) }?.sortedBy { it.name } ?: emptyList()
} catch (e: SecurityException) {
    emptyList()
}

private fun readUri(context: Context, uri: Uri): Pair<String, ByteArray> {
    var name = "arquivo"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0) ?: name
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("não foi possível ler o arquivo")
    return name to bytes
}
