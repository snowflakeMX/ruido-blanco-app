package ruido.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ruido.app.AppSettings
import ruido.core.NightRecord

@Composable
fun HomeScreen(
    settings: AppSettings,
    lastNight: NightRecord?,
    error: String?,
    batteryOptimized: Boolean,
    onStart: () -> Unit,
    onAnswer: (Long, Boolean) -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onDiagnostics: () -> Unit,
    onFixBattery: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ruido blanco", style = MaterialTheme.typography.headlineMedium)

        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(72.dp)) {
            Text("Iniciar noche", fontSize = 22.sp)
        }
        Text(
            "Termina sola a las %02d:%02d. Dejá el teléfono cerca de la pared de los vecinos y lejos de la cama."
                .format(settings.wakeHour, settings.wakeMinute),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (error != null) Warning("La última noche se interrumpió: $error")

        if (batteryOptimized) {
            Warning(
                "Android puede detener la app durante la noche. Permití que funcione sin restricciones de batería. " +
                    "En Samsung, agregala también a Ajustes › Batería › Límites de uso en segundo plano › Aplicaciones que nunca entran en suspensión.",
                action = "Permitir" to onFixBattery,
            )
        }

        if (lastNight != null) {
            Text("Última noche", style = MaterialTheme.typography.titleMedium)
            NightCard(lastNight) { woke -> onAnswer(lastNight.startMs, woke) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSettings) { Text("Ajustes") }
            OutlinedButton(onClick = onHistory) { Text("Historial") }
            OutlinedButton(onClick = onDiagnostics) { Text("Diagnóstico") }
        }
    }
}

@Composable
private fun Warning(text: String, action: Pair<String, () -> Unit>? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text)
            if (action != null) TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}
