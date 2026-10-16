package dev.turbodash.app.ui.logs

import android.content.Context
import android.content.Intent
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

@Composable
fun LogsScreen(sessions: SessionManager, alertConfig: () -> dev.turbodash.core.alerts.AlertConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by sessions.log.collectAsStateWithLifecycle()
    val state by sessions.state.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf(listOf<File>()) }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }

    fun refresh() {
        files = sessions.logsDir.listFiles { f -> f.name.endsWith(".msl") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    LaunchedEffect(log?.file) { refresh() }

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
        if (busy) Text("Analisando o log…", color = Palette.accent, modifier = Modifier.padding(bottom = 8.dp))
        if (files.isEmpty()) Text("Nenhum log ainda.", color = Palette.muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.name }) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.name, fontWeight = FontWeight.Bold)
                        Text(
                            "%.1f MB • %s".format(f.length() / 1e6, SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(f.lastModified()))),
                            color = Palette.muted, fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            TextButton(onClick = { share(context, f) }) { Text("Enviar") }
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) { sessions.start(Target.Replay(f)) }
                            }) { Text("Reproduzir") }
                            TextButton(enabled = log?.file != f, onClick = { confirmDelete = f }) { Text("Excluir", color = Palette.danger) }
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
    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton(onClick = { f.delete(); confirmDelete = null; refresh() }) { Text("Excluir", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
            title = { Text("Excluir ${f.name}?") },
            text = { Text("O arquivo será apagado do celular.") },
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
