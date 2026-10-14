package ruido.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import ruido.core.NightRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es"))

fun clock(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
fun day(ms: Long): String = dayFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        TextButton(onClick = onBack) { Text("‹ Volver") }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** Summary of one night: counts, timeline and the morning question. */
@Composable
fun NightCard(night: NightRecord, onAnswer: ((Boolean) -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val end = night.endMs
            Text(day(night.startMs).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "${clock(night.startMs)} – ${end?.let(::clock) ?: "en curso"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${night.events.size} ruidos · el ruido sonó ${night.playbackStarts} veces, ${duration(night.totalPlaybackMs)} en total")
            if (end != null && end > night.startMs) Timeline(night, end)
            if (onAnswer != null && end != null) {
                when (night.wokeUp) {
                    null -> {
                        Text("¿Los ruidos te despertaron?")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { onAnswer(true) }) { Text("Sí") }
                            OutlinedButton(onClick = { onAnswer(false) }) { Text("No") }
                        }
                    }
                    true -> Text("Respondiste: los ruidos te despertaron.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    false -> Text("Respondiste: dormiste sin despertarte por ruidos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Night from start to end: playback as bars, noises as ticks, with hour marks. */
@Composable
fun Timeline(night: NightRecord, endMs: Long) {
    val amber = Night.Amber
    val dim = Night.AmberDim
    val text = Night.TextDim
    val total = (endMs - night.startMs).toFloat()
    Column {
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            fun x(ms: Long) = ((ms - night.startMs) / total).coerceIn(0f, 1f) * size.width
            drawRect(Night.SurfaceHigh, Offset(0f, size.height * 0.35f), Size(size.width, size.height * 0.3f))
            for (span in night.spans) {
                drawRect(dim, Offset(x(span.startMs), size.height * 0.35f), Size(maxOf(2f, x(span.endMs) - x(span.startMs)), size.height * 0.3f))
            }
            for (event in night.events) {
                val ex = x(event.startMs)
                drawLine(amber, Offset(ex, 0f), Offset(ex, size.height), strokeWidth = 3f)
            }
            // Hour marks.
            val hour = 3_600_000L
            var mark = (night.startMs / hour + 1) * hour
            while (mark < endMs) {
                drawLine(text, Offset(x(mark), size.height * 0.8f), Offset(x(mark), size.height), strokeWidth = 1f)
                mark += hour
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(night.startMs), color = text, style = MaterialTheme.typography.labelSmall)
            Text("▮ ruido sonando   | ruido detectado", color = text, style = MaterialTheme.typography.labelSmall)
            Text(clock(endMs), color = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
