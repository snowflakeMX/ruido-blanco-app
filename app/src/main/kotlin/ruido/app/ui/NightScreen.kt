package ruido.app.ui

import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import ruido.app.AppSettings
import ruido.app.LiveState
import ruido.app.ScreenOffService
import ruido.core.NightEngine

/**
 * Shown while the night runs: pure black (OLED pixels off) with dim ember text, at minimum
 * brightness. After the configured seconds it goes fully black and turns the screen off.
 * A tap shows it again.
 */
@Composable
fun NightScreen(
    live: LiveState,
    settings: AppSettings,
    onStop: () -> Unit,
    onDiagnostics: () -> Unit,
    window: Window,
) {
    var visible by remember { mutableStateOf(true) }
    var wake by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val attrs = window.attributes
        attrs.screenBrightness = 0.01f
        window.attributes = attrs
        onDispose { }
    }

    LifecycleResumeEffect(Unit) {
        visible = true
        wake++
        onPauseOrDispose { }
    }

    LaunchedEffect(wake) {
        visible = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        delay(settings.screenOnSeconds * 1000L)
        visible = false
        confirmStop = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (settings.turnScreenOff) ScreenOffService.lockScreen()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Night.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { wake++ },
        contentAlignment = Alignment.Center,
    ) {
        if (!visible) return@Box
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(clock(System.currentTimeMillis()), color = Night.Ember, fontSize = 64.sp)
            Text(phaseText(live), color = Night.Ember, fontSize = 18.sp)
            live.endAtMs?.let { Text("Termina a las ${clock(it)}", color = Night.Ember) }
            Text("${live.events} ruidos", color = Night.Ember)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = {
                if (confirmStop) onStop() else {
                    confirmStop = true
                    wake++
                }
            }) {
                Text(if (confirmStop) "Tocá otra vez para terminar" else "Terminar noche", color = Night.Ember)
            }
            TextButton(onClick = onDiagnostics) { Text("Diagnóstico", color = Night.Ember) }
        }
    }
}

private fun phaseText(live: LiveState): String = when (live.phase) {
    NightEngine.Phase.CALIBRATING_QUIET -> "Midiendo el silencio…"
    NightEngine.Phase.CALIBRATING_LEAK -> "Probando el sonido…"
    NightEngine.Phase.LISTENING -> "Escuchando"
    NightEngine.Phase.PLAYING -> "Tapando ruido"
    NightEngine.Phase.ENDED -> "Terminando"
    null -> "Iniciando…"
}
