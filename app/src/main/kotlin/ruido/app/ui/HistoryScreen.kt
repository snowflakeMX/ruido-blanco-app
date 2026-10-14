package ruido.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ruido.app.StoredNight
import ruido.core.NightRecord

@Composable
fun HistoryScreen(nights: List<StoredNight>, onOpen: (Long) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Historial", onBack)
        if (nights.isEmpty()) {
            Text(
                "Todavía no hay noches registradas. Aparecen acá después de usar \"Iniciar noche\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(nights, key = { it.startMs }) { night -> NightSummaryCard(night, onClick = { onOpen(night.startMs) }) }
        }
    }
}

/** Compact card: date, hours, counts and a timeline. */
@Composable
fun NightSummaryCard(night: StoredNight, onClick: (() -> Unit)? = null) {
    val r = night.record
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("night-${night.startMs}")
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(day(r.startMs), style = MaterialTheme.typography.titleMedium)
            Text(hours(night), color = statusColor(night))
            Row {
                Stat("${r.events.size}", "ruidos", Modifier.weight(1f))
                Stat("${r.playbackStarts}", "veces sonó", Modifier.weight(1f))
                Stat(duration(r.totalPlaybackMs), "sonando", Modifier.weight(1f))
            }
            Timeline(night)
            if (onClick != null) Text("Ver detalle ›", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun NightDetailScreen(
    night: StoredNight?,
    onAnswer: (Boolean?) -> Unit,
    onShare: (String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Detalle de la noche", onBack)
        if (night == null) {
            Text("Esta noche ya no existe.", Modifier.padding(16.dp))
            return@Column
        }
        val r = night.record
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NightSummaryCard(night)
            if (night.interruptedAtMs != null) {
                Section {
                    Text(
                        "Esta noche se cortó a las ${clock(night.interruptedAtMs)}: Android cerró la app, se reinició el teléfono " +
                            "o se instaló una versión nueva. Revisá que la app esté en \"Aplicaciones que nunca entran en suspensión\".",
                        color = Night.Red,
                    )
                }
            }
            if (night.shownEndMs != null) MorningQuestion(r.wokeUp, onAnswer)

            Section("Ruidos (${r.events.size})") {
                if (r.events.isEmpty()) Hint("No hubo ruidos por encima del umbral.")
                for (e in r.events) {
                    Row {
                        Text(clock(e.startMs), Modifier.weight(0.25f), style = MaterialTheme.typography.bodyLarge)
                        Text(duration(e.durationMs), Modifier.weight(0.25f))
                        Text("+%.0f dB".format(e.peakExcessDb), Modifier.weight(0.2f))
                        Text(
                            when {
                                e.startedPlayback -> "encendió"
                                e.duringPlayback -> "ya sonaba"
                                else -> "ignorado"
                            },
                            Modifier.weight(0.3f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (r.events.isNotEmpty()) {
                    Hint("\"+dB\": cuánto superó al fondo. \"Ignorado\": llegó en los últimos 15 minutos antes de despertar.")
                }
            }

            Section("Veces que sonó (${r.spans.size})") {
                if (r.spans.isEmpty()) Hint("El ruido no se activó.")
                for (span in r.spans) {
                    Text("${clock(span.startMs)} a ${clock(span.endMs)} · ${duration(span.durationMs)}")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onShare(shareText(night)) }, modifier = Modifier.testTag("share-night")) { Text("Compartir") }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete-night")) { Text("Borrar") }
            }
            HorizontalDivider()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar esta noche?") },
            text = { Text("Se borra el registro de esta noche. No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.testTag("confirm-delete")) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun MorningQuestion(answer: Boolean?, onAnswer: (Boolean?) -> Unit) {
    Section("¿Los ruidos te despertaron?") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = answer == true, onClick = { onAnswer(if (answer == true) null else true) }, label = { Text("Sí") }, modifier = Modifier.testTag("woke-yes"))
            FilterChip(selected = answer == false, onClick = { onAnswer(if (answer == false) null else false) }, label = { Text("No") }, modifier = Modifier.testTag("woke-no"))
        }
        Hint("Sirve para saber si la app ayuda y para ajustar la sensibilidad.")
    }
}

private fun hours(night: StoredNight): String {
    val start = clock(night.startMs)
    return when {
        night.record.endMs != null -> "$start a ${clock(night.record.endMs!!)} · ${duration(night.record.endMs!! - night.startMs)}"
        night.interruptedAtMs != null -> "$start a ${clock(night.interruptedAtMs)} · se cortó"
        else -> "Desde las $start · en curso"
    }
}

@Composable
private fun statusColor(night: StoredNight) =
    if (night.interruptedAtMs != null) Night.Red else MaterialTheme.colorScheme.onSurfaceVariant

/** Plain text summary, readable in a chat or an email. */
fun shareText(night: StoredNight): String = buildString {
    val r: NightRecord = night.record
    appendLine("Ruido blanco · ${day(r.startMs)}")
    appendLine(hours(night))
    appendLine("${r.events.size} ruidos, sonó ${r.playbackStarts} veces (${duration(r.totalPlaybackMs)} en total)")
    r.wokeUp?.let { appendLine(if (it) "Los ruidos me despertaron." else "No me despertaron los ruidos.") }
    if (r.events.isNotEmpty()) {
        appendLine()
        appendLine("Ruidos:")
        for (e in r.events) {
            appendLine("- ${clock(e.startMs)} · ${duration(e.durationMs)} · +%.0f dB (pico %.0f dBFS)".format(e.peakExcessDb, e.peakLevelDb) +
                if (e.startedPlayback) " · encendió" else "")
        }
    }
    appendLine()
    appendLine("Datos para ajustar:")
    append(r.toCsv())
}
