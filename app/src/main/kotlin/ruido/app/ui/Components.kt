package ruido.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ruido.app.LiveState
import ruido.app.StoredNight
import ruido.core.Levels
import ruido.core.NightEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es"))

fun clock(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun day(ms: Long): String =
    dayFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())).replaceFirstChar { it.uppercase() }

fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms < 60_000 -> "${ms / 1000} s"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

fun phaseText(phase: NightEngine.Phase?): String = when (phase) {
    NightEngine.Phase.CALIBRATING_QUIET -> "Midiendo el silencio…"
    NightEngine.Phase.CALIBRATING_LEAK -> "Midiendo su propio sonido…"
    NightEngine.Phase.LISTENING -> "Escuchando"
    NightEngine.Phase.PLAYING -> "Tapando ruido"
    NightEngine.Phase.ENDED -> "Terminando"
    null -> "Iniciando…"
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 4.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("‹ Volver") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A labeled slider over whole numbers, with the current value on the right. */
@Composable
fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    valueText: (Int) -> String,
    onChange: (Int) -> Unit,
    tag: String,
    hint: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText(value),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("$tag-value"),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v ->
                val snapped = (range.first + ((v - range.first) / step).roundToInt() * step).coerceIn(range.first, range.last)
                if (snapped != value) onChange(snapped)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            modifier = Modifier.testTag(tag),
        )
        if (hint != null) Hint(hint)
    }
}

/** Small number with a caption, for summaries. */
@Composable
fun Stat(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Live microphone level as a bar, with a line where a sound starts counting as a noise.
 * Levels are relative to this phone's microphone (dBFS), not sound-meter decibels.
 */
@Composable
fun LevelMeter(live: LiveState) {
    val low = -90.0
    val high = -10.0
    fun frac(db: Double) = ((db.coerceIn(low, high) - low) / (high - low)).toFloat()
    val measuring = live.phase == NightEngine.Phase.LISTENING || live.phase == NightEngine.Phase.PLAYING
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(36.dp).testTag("level-meter")) {
            drawRect(Night.SurfaceHigh, size = size)
            if (live.levelDb > Levels.FLOOR_DB) {
                drawRect(
                    if (live.noisy) Night.Red else Night.Amber,
                    size = Size(size.width * frac(live.levelDb), size.height),
                )
            }
            if (measuring) {
                val bx = size.width * frac(live.expectedDb)
                drawRect(Night.TextDim, Offset(bx - 1.5f, 0f), Size(3f, size.height))
                val tx = size.width * frac(live.triggerDb)
                drawRect(Night.Text, Offset(tx - 3f, 0f), Size(6f, size.height))
            }
        }
        Row {
            Text("Nivel ${db(live.levelDb)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (measuring) Text("Línea de disparo ${db(live.triggerDb)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun db(v: Double) = if (v <= Levels.FLOOR_DB) "—" else "%.0f dB".format(v)

/** The night from start to end: playback as bars, noises as ticks, with hour marks. */
@Composable
fun Timeline(night: StoredNight) {
    val end = night.shownEndMs ?: return
    val start = night.startMs
    if (end <= start) return
    val total = (end - start).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                fun x(ms: Long) = ((ms - start) / total).coerceIn(0f, 1f) * size.width
                val barTop = size.height * 0.3f
                val barHeight = size.height * 0.4f
                drawRect(Night.SurfaceHigh, Offset(0f, barTop), Size(size.width, barHeight))
                for (span in night.record.spans) {
                    drawRect(
                        Night.AmberDim, Offset(x(span.startMs), barTop),
                        Size(maxOf(3f, x(span.endMs) - x(span.startMs)), barHeight),
                    )
                }
                for (event in night.record.events) {
                    val ex = x(event.startMs)
                    drawLine(Night.Amber, Offset(ex, 0f), Offset(ex, size.height), strokeWidth = 4f)
                }
                val hour = 3_600_000L
                var mark = (start / hour + 1) * hour
                while (mark < end) {
                    drawLine(Night.TextDim, Offset(x(mark), size.height * 0.85f), Offset(x(mark), size.height), strokeWidth = 2f)
                    mark += hour
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(start), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(clock(end), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hint("Rayas: ruidos detectados. Barras: tiempo que sonó el ruido.")
    }
}
