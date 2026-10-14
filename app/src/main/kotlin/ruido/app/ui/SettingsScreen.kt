package ruido.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ruido.app.AppSettings
import ruido.core.NoiseColor
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(settings: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    val s = settings
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar("Ajustes", onBack)

        Section("Horario")
        Stepper(
            "Hora de despertar", "%02d:%02d".format(s.wakeHour, s.wakeMinute),
            "La noche termina sola a esta hora. 15 min antes ya no empieza a sonar.",
            onMinus = { onChange(s.addWakeMinutes(-15)) }, onPlus = { onChange(s.addWakeMinutes(15)) },
        )

        Section("Sonido")
        Text("Tipo de ruido")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in NoiseColor.entries) {
                FilterChip(
                    selected = s.color == c,
                    onClick = { onChange(s.copy(color = c)) },
                    label = { Text(colorName(c)) },
                )
            }
        }
        Stepper(
            "Volumen del ruido", "${(s.volume * 100).roundToInt()} %", null,
            onMinus = { onChange(s.copy(volume = (s.volume - 0.05f).coerceAtLeast(0.05f))) },
            onPlus = { onChange(s.copy(volume = (s.volume + 0.05f).coerceAtMost(1f))) },
        )
        Stepper(
            "Volumen multimedia del teléfono", "${s.mediaVolumePercent} %",
            "Se fija al iniciar la noche y se restaura al terminar. Es el tope real: mantenelo moderado porque la nena duerme en la habitación.",
            onMinus = { onChange(s.copy(mediaVolumePercent = (s.mediaVolumePercent - 5).coerceAtLeast(5))) },
            onPlus = { onChange(s.copy(mediaVolumePercent = (s.mediaVolumePercent + 5).coerceAtMost(100))) },
        )
        Stepper(
            "Sigue sonando después del último ruido", "${s.holdMinutes} min", null,
            onMinus = { onChange(s.copy(holdMinutes = (s.holdMinutes - if (s.holdMinutes > 5) 5 else 1).coerceAtLeast(1))) },
            onPlus = { onChange(s.copy(holdMinutes = (s.holdMinutes + if (s.holdMinutes >= 5) 5 else 1).coerceAtMost(60))) },
        )
        Stepper(
            "Subida (fade-in)", "${s.fadeInSeconds} s", null,
            onMinus = { onChange(s.copy(fadeInSeconds = (s.fadeInSeconds - 1).coerceAtLeast(1))) },
            onPlus = { onChange(s.copy(fadeInSeconds = (s.fadeInSeconds + 1).coerceAtMost(30))) },
        )
        Stepper(
            "Bajada (fade-out)", "${s.fadeOutSeconds} s", null,
            onMinus = { onChange(s.copy(fadeOutSeconds = (s.fadeOutSeconds - 5).coerceAtLeast(5))) },
            onPlus = { onChange(s.copy(fadeOutSeconds = (s.fadeOutSeconds + 5).coerceAtMost(120))) },
        )

        Section("Detección")
        Stepper(
            "Sensibilidad", "%.0f dB".format(s.thresholdDb),
            "Cuánto más fuerte que el fondo tiene que ser un ruido. Menos dB = más sensible.",
            onMinus = { onChange(s.copy(thresholdDb = (s.thresholdDb - 1).coerceAtLeast(4f))) },
            onPlus = { onChange(s.copy(thresholdDb = (s.thresholdDb + 1).coerceAtMost(30f))) },
        )
        Stepper(
            "Máximo sonando seguido", "${s.maxPlaybackMinutes} min",
            "Después de este tiempo se apaga y vuelve a medir el fondo, por si el ambiente cambió.",
            onMinus = { onChange(s.copy(maxPlaybackMinutes = (s.maxPlaybackMinutes - 15).coerceAtLeast(15))) },
            onPlus = { onChange(s.copy(maxPlaybackMinutes = (s.maxPlaybackMinutes + 15).coerceAtMost(240))) },
        )

        Section("Pantalla")
        Text("Tiempo con la pantalla encendida")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (sec in listOf(0, 10, 30, 60)) {
                FilterChip(
                    selected = s.screenOnSeconds == sec,
                    onClick = { onChange(s.copy(screenOnSeconds = sec)) },
                    label = { Text(if (sec == 0) "Nada" else if (sec == 60) "1 min" else "$sec s") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Apagar la pantalla del todo")
                Hint("Si está apagado, la pantalla queda negra y se apaga con el tiempo normal del teléfono.")
            }
            Switch(checked = s.turnScreenOff, onCheckedChange = { onChange(s.copy(turnScreenOff = it)) })
        }

        HorizontalDivider()
        OutlinedButton(onClick = { onChange(AppSettings()) }) { Text("Volver a los valores por defecto") }
    }
}

private fun AppSettings.addWakeMinutes(delta: Int): AppSettings {
    val total = ((wakeHour * 60 + wakeMinute + delta) % (24 * 60) + 24 * 60) % (24 * 60)
    return copy(wakeHour = total / 60, wakeMinute = total % 60)
}

private fun colorName(c: NoiseColor) = when (c) {
    NoiseColor.WHITE -> "Blanco"
    NoiseColor.PINK -> "Rosa"
    NoiseColor.BROWN -> "Marrón"
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Stepper(label: String, value: String, hint: String?, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            OutlinedButton(onClick = onMinus) { Text("−") }
            Text(value, Modifier.width(72.dp), textAlign = TextAlign.Center)
            OutlinedButton(onClick = onPlus) { Text("+") }
        }
        if (hint != null) Hint(hint)
    }
}
