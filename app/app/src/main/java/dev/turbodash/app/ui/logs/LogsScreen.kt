package dev.turbodash.app.ui.logs

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turbodash.app.ecu.SessionManager
import dev.turbodash.app.ecu.Target
import dev.turbodash.app.ui.Palette
import dev.turbodash.core.analysis.LogAnalyzer
import dev.turbodash.core.log.MslReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogsScreen(sessions: SessionManager, alertConfig: () -> dev.turbodash.core.alerts.AlertConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by sessions.log.collectAsStateWithLifecycle()
    val state by sessions.state.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf(listOf<File>()) }
    var sent by remember { mutableStateOf(LogBundle.sent(context)) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var zipping by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<File>?>(null) }

    fun refresh() {
        files = sessions.logsDir.listFiles { f -> f.name.endsWith(".msl") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        sent = LogBundle.sent(context)
        selected = selected.filterTo(mutableSetOf()) { n -> files.any { it.name == n } }
    }
    LaunchedEffect(log?.file) { refresh() }

    // The log being recorded is still growing: never bundle it.
    val finished = files.filter { it != log?.file }
    val unsent = finished.filter { it.name !in sent }

    fun sendBundle(toSend: List<File>) {
        if (toSend.isEmpty()) return
        zipping = true
        scope.launch {
            val zip = withContext(Dispatchers.IO) {
                runCatching { LogBundle.zip(toSend, File(context.filesDir, "exports")) }.getOrNull()
            }
            zipping = false
            if (zip != null) {
                LogBundle.share(context, zip, toSend.size)
                LogBundle.markSent(context, toSend.map { it.name })
                selected = emptySet()
                refresh()
            } else report = "Erro ao compactar os logs."
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val l = log
                if (l != null) {
                    val secs = (System.currentTimeMillis() - l.startedAt) / 1000
                    Text("Gravando ${l.file.name}", fontWeight = FontWeight.Bold)
                    Text("${l.rows} amostras • %d:%02d min".format(secs / 60, secs % 60), color = Palette.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { sessions.stopLog(); refresh() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.danger)) { Text("Parar") }
                        OutlinedButton(onClick = { sessions.mark() }) { Text("Marcar momento") }
                    }
                    Text("Use \"Marcar\" quando algo estranho acontecer, para achar o ponto no log depois.", color = Palette.muted, fontSize = 12.sp)
                } else {
                    Text("Nenhum log em gravação", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { sessions.startLog() }, enabled = sessions.isActive && state != null,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Iniciar log") }
                    if (!sessions.isActive) Text("Conecte na ECU para gravar.", color = Palette.muted, fontSize = 12.sp)
                }
            }
        }
        Text("Logs salvos", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        if (selected.isNotEmpty()) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !zipping, onClick = { sendBundle(finished.filter { it.name in selected }) }) {
                    Text("Enviar ${selected.size}")
                }
                Button(
                    onClick = { confirmDelete = finished.filter { it.name in selected } },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.danger),
                ) { Text("Excluir ${selected.size}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                TextButton(onClick = { selected = finished.mapTo(mutableSetOf()) { it.name } }) { Text("Todos") }
                TextButton(onClick = { selected = finished.filter { it.name.startsWith(today) }.mapTo(mutableSetOf()) { it.name } }) { Text("Todos de hoje") }
                TextButton(onClick = { selected = emptySet() }) { Text("Cancelar") }
            }
        } else {
            if (unsent.isNotEmpty()) {
                Button(enabled = !zipping, onClick = { sendBundle(unsent) }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text("Enviar novos (${unsent.size}) num arquivo só")
                }
            }
            if (finished.isNotEmpty()) {
                Text("Toque e segure num log para escolher quais enviar ou excluir.", color = Palette.muted, fontSize = 12.sp)
                TextButton(onClick = { confirmDelete = finished }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("Excluir todos (${finished.size})", color = Palette.danger)
                }
            }
        }
        if (busy) Text("Analisando o log…", color = Palette.accent, modifier = Modifier.padding(bottom = 8.dp))
        if (zipping) Text("Compactando…", color = Palette.accent, modifier = Modifier.padding(bottom = 8.dp))
        if (files.isEmpty()) Text("Nenhum log ainda.", color = Palette.muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.name }) { f ->
                val recording = log?.file == f
                fun toggle() { selected = if (f.name in selected) selected - f.name else selected + f.name }
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        enabled = !recording,
                        onClick = { if (selected.isNotEmpty()) toggle() },
                        onLongClick = { toggle() },
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected.isNotEmpty()) Checkbox(checked = f.name in selected, enabled = !recording, onCheckedChange = { toggle() })
                        Column(Modifier.padding(12.dp)) {
                            Text(f.name, fontWeight = FontWeight.Bold)
                            Text(
                                "%.1f MB • %s".format(f.length() / 1e6, SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(f.lastModified()))) +
                                    if (f.name in sent) " • enviado" else "",
                                color = Palette.muted, fontSize = 12.sp,
                            )
                            if (selected.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        report = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val data = f.reader(Charsets.ISO_8859_1).use { MslReader.read(it) }
                                                LogAnalyzer.toText(LogAnalyzer.analyze(data, alertConfig()))
                                            }.getOrElse { "Erro ao analisar: ${it.message}" }
                                        }
                                        busy = false
                                    }
                                }) { Text("Analisar") }
                                TextButton(onClick = {
                                    share(context, f)
                                    LogBundle.markSent(context, listOf(f.name))
                                    sent = LogBundle.sent(context)
                                }) { Text("Enviar") }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) { sessions.start(Target.Replay(f)) }
                                }) { Text("Reproduzir") }
                                TextButton(enabled = !recording, onClick = { confirmDelete = listOf(f) }) { Text("Excluir", color = Palette.danger) }
                            }
                        }
                    }
                }
            }
        }
    }

    report?.let { r ->
        AlertDialog(
            onDismissRequest = { report = null },
            confirmButton = { TextButton(onClick = { report = null }) { Text("Fechar") } },
            title = { Text("Análise do log") },
            text = { Text(r, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }
    confirmDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    toDelete.forEach { it.delete() }
                    confirmDelete = null
                    selected = emptySet()
                    refresh()
                }) { Text("Excluir", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
            title = { Text(if (toDelete.size == 1) "Excluir ${toDelete[0].name}?" else "Excluir ${toDelete.size} logs?") },
            text = { Text(if (toDelete.size == 1) "O arquivo será apagado do celular." else "Os arquivos serão apagados do celular.") },
        )
    }
}

fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Enviar ${file.name}"))
}
