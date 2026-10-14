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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ruido.app.AppSettings
import ruido.app.StoredNight

/** What the phone is ready for; checked on every return to the app. */
data class Readiness(
    val microphone: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
)

@Composable
fun HomeScreen(
    settings: AppSettings,
    lastNight: StoredNight?,
    error: String?,
    testRunning: Boolean,
    readiness: Readiness,
    onStart: () -> Unit,
    onAnswer: (Boolean?) -> Unit,
    onOpenNight: (Long) -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onDiagnostics: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ruido blanco", style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = onStart,
            enabled = !testRunning,
            modifier = Modifier.fillMaxWidth().height(80.dp).testTag("start-night"),
        ) { Text("Iniciar noche", fontSize = 24.sp) }
        Hint(
            if (testRunning) "Hay una prueba de detección en curso. Detenela en Ajustes para iniciar la noche."
            else "Termina sola a las %02d:%02d. Dejá el teléfono enchufado, cerca de la pared de los vecinos y lejos de las camas."
                .format(settings.wakeHour, settings.wakeMinute),
        )

        if (error != null) {
            Section { Text("La última vez se detuvo: $error", color = Night.Red) }
        }

        if (!readiness.batteryUnrestricted || !readiness.microphone || !readiness.notifications) {
            Section("Antes de la primera noche") {
                Check(readiness.microphone, "Micrófono", "Se pide al iniciar la noche.")
                Check(readiness.notifications, "Aviso mientras escucha", "Se pide al iniciar. Desde el aviso se puede terminar la noche.")
                Check(readiness.batteryUnrestricted, "Batería sin restricciones", "Si no, Android puede cortar la app de madrugada.")
                if (!readiness.batteryUnrestricted) {
                    OutlinedButton(onClick = onFixBattery, modifier = Modifier.testTag("fix-battery")) { Text("Permitir") }
                }
            }
        }
        Section("En Samsung, una sola vez") {
            Hint(
                "Ajustes › Batería › Límites de uso en segundo plano › Aplicaciones que nunca entran en suspensión › agregá Ruido blanco. " +
                    "Sin esto, Samsung puede cerrar la app durante la noche.",
            )
            TextButton(onClick = onOpenAppSettings) { Text("Abrir ajustes de la app") }
        }

        if (lastNight != null) {
            Text("Última noche", style = MaterialTheme.typography.titleMedium)
            NightSummaryCard(lastNight, onClick = { onOpenNight(lastNight.startMs) })
            if (lastNight.shownEndMs != null) MorningQuestion(lastNight.record.wokeUp, onAnswer)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f).testTag("open-settings")) { Text("Ajustes") }
            OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f).testTag("open-history")) { Text("Historial") }
        }
        TextButton(onClick = onDiagnostics, modifier = Modifier.testTag("open-diagnostics")) { Text("Diagnóstico") }
    }
}

@Composable
private fun Check(ok: Boolean, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(if (ok) "✓" else "•", color = if (ok) Night.Green else Night.Amber, fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!ok) Hint(detail)
        }
    }
}
