package ruido.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ruido.app.LiveState

/** Live numbers while listening. Levels are relative to this phone's microphone. */
@Composable
fun DiagnosticsScreen(live: LiveState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Diagnóstico", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!live.running) {
                Hint("La app no está escuchando. Iniciá la noche, o usá \"Probar detección\" en Ajustes, para ver los niveles en vivo.")
            } else {
                Section(phaseText(live)) {
                    LevelMeter(live)
                    Line("Fondo aprendido", db(live.baselineDb))
                    Line("Fondo + sonido propio", db(live.expectedDb))
                    Line("Sonido propio en el micrófono", live.leakDb?.let(::db) ?: "sin medir")
                    Line("Diferencia para contar como ruido", "%.0f dB".format(live.thresholdDb))
                    Line("Volumen del ruido ahora", "${(live.gain * 100).toInt()} %")
                    Line("Ruidos / veces que sonó", "${live.events} / ${live.playbackStarts}")
                    Line("Fuente del micrófono", live.audioSource.ifEmpty { "—" })
                    Line("Reconocimiento de sonidos", live.classifier.ifEmpty { "—" })
                    Line("Último ruido", kindName(live.lastKind).ifEmpty { "—" })
                    Line("Prevención esta noche", if (live.windows.isEmpty()) "ninguna" else windowsText(live.windows))
                }
                Hint("Los valores son relativos a este micrófono (0 dB es el máximo que puede captar), no los decibeles de un sonómetro.")
            }
            live.error?.let { Text("Último error: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
