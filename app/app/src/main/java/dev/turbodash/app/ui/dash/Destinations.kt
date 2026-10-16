package dev.turbodash.app.ui.dash

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turbodash.app.ecu.GpsSpeed
import dev.turbodash.app.nav.Navigation
import dev.turbodash.app.nav.Place
import dev.turbodash.app.ui.Palette
import dev.turbodash.core.map.formatDistance
import kotlinx.coroutines.launch

private val quickNames = listOf("Casa", "Trabalho", "Namorada", "Oficina", "Posto")

/** Where to go: saved shortcuts, address search and the route in progress. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DestinationsDialog(nav: Navigation, gps: GpsSpeed, onDismiss: () -> Unit) {
    val favorites by nav.favorites.collectAsStateWithLifecycle()
    val recents by nav.recents.collectAsStateWithLifecycle()
    val active by nav.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Place?>(null) }
    var removing by remember { mutableStateOf<Place?>(null) }
    // Typing only with the car stopped.
    val moving = (gps.current() ?: 0.0) > 5.0

    fun go(p: Place) { nav.go(p); onDismiss() }
    fun search() {
        if (query.isBlank() || searching) return
        searching = true
        scope.launch {
            results = nav.search(query.trim())
            searching = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Destino", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Fechar", tint = Palette.muted) }
            }

            active?.let { a ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(a.destination.name.ifBlank { a.destination.address }, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            a.remainingM != null -> "Faltam ${formatDistance(a.remainingM)}"
                            a.failed -> "Sem rota (sem internet?) — tentando de novo"
                            else -> "Calculando rota…"
                        },
                        color = Palette.muted, fontSize = 14.sp,
                    )
                    Button(
                        onClick = { nav.stop(); onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.danger),
                    ) { Text("Encerrar rota") }
                }
            }

            if (favorites.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    favorites.forEach { p ->
                        Text(
                            p.name, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surfaceHigh)
                                .combinedClickable(onClick = { go(p) }, onLongClick = { removing = p })
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = query, onValueChange = { query = it },
                enabled = !moving,
                placeholder = { Text(if (moving) "Pare o carro para digitar" else "Endereço (rua, número, cidade)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = {
                    if (searching) CircularProgressIndicator(Modifier.padding(10.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { search() }, enabled = !moving) { Icon(Icons.Filled.Search, "Buscar") }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            val list = results ?: recents
            val title = when {
                results == null -> if (recents.isEmpty()) null else "Recentes"
                results!!.isEmpty() -> "Nada encontrado. Tente \"Rua, número, cidade\"."
                else -> null
            }
            if (title != null) Text(title, color = Palette.muted, fontSize = 13.sp)
            if (favorites.isEmpty() && results == null) {
                Text("Busque um endereço e toque na ★ para salvar como Casa, Trabalho…", color = Palette.muted, fontSize = 13.sp)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list) { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.surface)
                            .combinedClickable(onClick = { go(p) }).padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.address, color = Palette.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                        IconButton(onClick = { saving = p }) { Icon(Icons.Filled.Star, "Salvar como atalho", tint = Palette.accent) }
                    }
                }
            }
        }
    }

    saving?.let { p ->
        var name by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = null },
            title = { Text("Salvar atalho") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(p.address, color = Palette.muted, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickNames.forEach { n -> SuggestionChip(onClick = { name = n }, label = { Text(n) }) }
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Nome") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { nav.saveFavorite(name, p); saving = null }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { saving = null }) { Text("Cancelar") } },
        )
    }

    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Apagar \"${p.name}\"?") },
            text = { Text(p.address) },
            confirmButton = { TextButton(onClick = { nav.removeFavorite(p); removing = null }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }
}
