package ruido.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ruido.app.AppSettings
import ruido.app.LiveState
import ruido.core.NightEngine
import ruido.core.NoiseColor

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    live: LiveState,
    previewPlaying: Boolean,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onPreviewSound: () -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onBack: () -> Unit,
) {
    val s = settings
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopBar("Ajustes", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TestSection(live, previewPlaying, onPreviewSound, onStartTest, onStopTest)

            Section("Horario") {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Hora de despertar", style = MaterialTheme.typography.bodyLarge)
                        Hint("La noche termina sola a esta hora. Desde 15 minutos antes ya no empieza a sonar.")
                    }
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(context, { _, h, m -> onChange { it.copy(wakeHour = h, wakeMinute = m) } }, s.wakeHour, s.wakeMinute, true).show()
                        },
                        modifier = Modifier.testTag("wake-time"),
                    ) { Text("%02d:%02d".format(s.wakeHour, s.wakeMinute)) }
                }
            }

            Section("Sonido") {
                Text("Tipo de ruido", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in NoiseColor.entries) {
                        Choice(colorName(c), s.color == c, { onChange { it.copy(color = c) } }, "color-${c.name}")
                    }
                }
                Hint("Rosa y marrón suenan más graves y suelen tapar mejor voces y música de los vecinos.")
                IntSlider(
                    "Volumen del ruido", s.volumePercent, 5..100, 5, { "$it %" },
                    { v -> onChange { it.copy(volumePercent = v) } }, "volume",
                )
                IntSlider(
                    "Volumen multimedia del teléfono", s.mediaVolumePercent, 5..100, 5, { "$it %" },
                    { v -> onChange { it.copy(mediaVolumePercent = v) } }, "media-volume",
                    hint = "Se fija mientras la app escucha y se restaura al terminar. Es el tope real: la nena duerme en la habitación, mantenelo moderado.",
                )
                IntSlider(
                    "Sigue sonando tras el último ruido", s.holdMinutes, 1..60, 1, { "$it min" },
                    { v -> onChange { it.copy(holdMinutes = v) } }, "hold",
                )
                IntSlider(
                    "Subida del volumen", s.fadeInSeconds, 1..30, 1, { "$it s" },
                    { v -> onChange { it.copy(fadeInSeconds = v) } }, "fade-in",
                )
                IntSlider(
                    "Bajada del volumen", s.fadeOutSeconds, 5..120, 5, { "$it s" },
                    { v -> onChange { it.copy(fadeOutSeconds = v) } }, "fade-out",
                )
            }

            Section("Detección") {
                IntSlider(
                    "Diferencia con el silencio", s.thresholdDb, 4..30, 1, { "$it dB" },
                    { v -> onChange { it.copy(thresholdDb = v) } }, "threshold",
                    hint = "Cuánto más fuerte que el fondo tiene que ser un sonido para contar como ruido. Menos = más sensible. Probalo arriba con \"Probar detección\".",
                )
                IntSlider(
                    "Máximo sonando seguido", s.maxPlaybackMinutes, 15..240, 15, { "$it min" },
                    { v -> onChange { it.copy(maxPlaybackMinutes = v) } }, "max-playback",
                    hint = "Pasado este tiempo baja el ruido y vuelve a medir el silencio, por si el ambiente cambió (por ejemplo, empezó a llover).",
                )
            }

            Section("Pantalla de noche") {
                Text("Tiempo legible después de tocarla", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sec in listOf(0, 10, 15, 30, 60)) {
                        Choice(
                            if (sec == 0) "Negra al iniciar" else if (sec == 60) "1 min" else "$sec s",
                            s.screenOnSeconds == sec, { onChange { it.copy(screenOnSeconds = sec) } }, "screen-on-$sec",
                        )
                    }
                }
                IntSlider(
                    "Brillo al tocarla", s.nightBrightnessPercent, 5..100, 5, { "$it %" },
                    { v -> onChange { it.copy(nightBrightnessPercent = v) } }, "brightness",
                    hint = "Pasado ese tiempo la pantalla queda totalmente negra (no emite luz) y el teléfono la apaga con su tiempo normal. Tocala para volver a verla.",
                )
            }

            TextButton(onClick = { confirmReset = true }) { Text("Volver a los valores por defecto") }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("¿Volver a los valores por defecto?") },
            confirmButton = {
                TextButton(onClick = {
                    onChange { AppSettings() }
                    confirmReset = false
                }) { Text("Sí, restablecer") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TestSection(
    live: LiveState,
    previewPlaying: Boolean,
    onPreviewSound: () -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
) {
    Section("Probar") {
        if (live.night) {
            Hint("Hay una noche en curso. Terminala para usar las pruebas; los cambios igual se aplican al instante.")
            return@Section
        }
        Hint("Los cambios de abajo se aplican al instante mientras probás.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onPreviewSound,
                enabled = !previewPlaying && !live.running,
                modifier = Modifier.weight(1f).testTag("preview-sound"),
            ) { Text(if (previewPlaying) "Sonando…" else "Probar sonido") }
            if (live.test) {
                Button(onClick = onStopTest, modifier = Modifier.weight(1f).testTag("stop-test")) { Text("Detener prueba") }
            } else {
                Button(onClick = onStartTest, enabled = !previewPlaying, modifier = Modifier.weight(1f).testTag("start-test")) {
                    Text("Probar detección")
                }
            }
        }
        if (live.test) {
            Text(phaseText(live.phase), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("test-phase"))
            LevelMeter(live)
            Row {
                Stat("${live.events}", "ruidos detectados", Modifier.weight(1f))
                Stat(if (live.phase == NightEngine.Phase.PLAYING) "${(live.gain * 100).toInt()} %" else "—", "volumen actual", Modifier.weight(1f))
            }
            Hint(
                "Primero mide el silencio y su propio sonido (unos 15 s). Después hacé un ruido: si la barra pasa la línea blanca, " +
                    "se activa el ruido. La línea gris es el fondo. Si se activa con ruidos normales de la casa, subí la diferencia.",
            )
        } else {
            Hint("\"Probar sonido\" reproduce 6 s con el tipo y volumen elegidos. \"Probar detección\" escucha como de noche, sin guardar nada.")
        }
    }
}

private fun colorName(c: NoiseColor) = when (c) {
    NoiseColor.WHITE -> "Blanco"
    NoiseColor.PINK -> "Rosa"
    NoiseColor.BROWN -> "Marrón"
}
