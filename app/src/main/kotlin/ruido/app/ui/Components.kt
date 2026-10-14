package ruido.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ruido.app.LiveState
import ruido.app.StoredNight
import ruido.core.Levels
import ruido.core.NightEngine
import ruido.core.SoundKind
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
        ms < 1_000 -> "<1 s"
        ms < 60_000 -> "${ms / 1000} s"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

fun phaseText(live: LiveState): String =
    if (live.phase == NightEngine.Phase.PLAYING && live.preventing) "Prevención: sonando suave" else phaseText(live.phase)

fun kindName(kind: SoundKind?): String = when (kind) {
    SoundKind.MUSIC -> "música"
    SoundKind.VOICES -> "voces"
    SoundKind.KNOCKS -> "golpes"
    SoundKind.DOORS -> "puerta"
    SoundKind.STEPS -> "pasos"
    SoundKind.DOG -> "perro"
    SoundKind.TV -> "tele o radio"
    SoundKind.ROOM -> "de la habitación"
    SoundKind.OTHER -> "otro"
    null -> ""
}

/** Prevention windows as "00:50 a 02:00, 03:10 a 04:00". */
fun windowsText(windows: List<LongRange>): String = windows.joinToString(", ") { "${clock(it.first)} a ${clock(it.last + 1)}" }

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
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
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
            val top = size.height * 0.2f
            val barHeight = size.height * 0.6f
            drawRect(Night.SurfaceHigh, Offset(0f, top), Size(size.width, barHeight))
            if (live.levelDb > Levels.FLOOR_DB) {
                drawRect(
                    if (live.noisy) Night.Red else Night.Amber,
                    Offset(0f, top), Size(size.width * frac(live.levelDb), barHeight),
                )
            }
            if (measuring) {
                val bx = size.width * frac(live.expectedDb)
                drawRect(Night.TextDim, Offset(bx - 3f, 0f), Size(6f, size.height))
                val tx = size.width * frac(live.triggerDb)
                drawRect(Color.White, Offset(tx - 4f, 0f), Size(8f, size.height))
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
fun Timeline(night: StoredNight, legend: Boolean = false) {
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
        if (legend) Hint("Rayas: ruidos detectados. Barras: tiempo que sonó el ruido.")
    }
}

/** Filter chip that is clearly marked when selected. */
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Night.Amber,
            selectedLabelColor = Night.Black,
            labelColor = Night.Text,
        ),
        modifier = Modifier.testTag(tag),
    )
}

/**
 * A button that only acts when its knob is slid all the way to the right, so a stray
 * touch in the dark can never start or end the night. [onDragStart] lets the night
 * screen stay readable while sliding.
 */
@Composable
fun SwipeButton(
    text: String,
    onComplete: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    onDragStart: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var trackWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val knob = 64.dp
    val knobPx = with(density) { knob.toPx() }
    val maxOffset = (trackWidth - knobPx).coerceAtLeast(1f)
    val color = if (enabled) Night.Amber else Night.AmberDim
    Box(
        modifier
            .height(knob + 8.dp)
            .fillMaxWidth()
            .onSizeChanged { trackWidth = it.width }
            .background(if (filled) color.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(50))
            .border(2.dp, color, RoundedCornerShape(50))
            .semantics(mergeDescendants = true) {
                // Screen readers and tests can still activate it directly.
                if (enabled) onClick(text) { onComplete(); true } else disabled()
            }
            .testTag(tag)
            .draggable(
                enabled = enabled,
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxOffset)) }
                },
                onDragStarted = { onDragStart() },
                onDragStopped = {
                    if (offset.value >= maxOffset * 0.85f) {
                        offset.snapTo(maxOffset)
                        onComplete()
                    }
                    offset.animateTo(0f)
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            color = color,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = knob + 8.dp, end = 16.dp),
        )
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .padding(4.dp)
                .size(knob)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("›››", color = Night.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}
