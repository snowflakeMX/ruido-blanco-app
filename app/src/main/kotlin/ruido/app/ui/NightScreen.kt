package ruido.app.ui

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import ruido.app.AppSettings
import ruido.app.LiveState

/**
 * Shown while the night runs. Readable mode: brightness from the settings and large warm
 * text. After the configured seconds it turns fully black at minimum brightness (an AMOLED
 * screen then emits no light) and the phone's own timeout switches the screen off. Any
 * touch, or coming back to the app, makes it readable again.
 *
 * [onScreen] tells the activity how to set the window: brightness (0..1) and whether to
 * keep the screen on.
 */
@Composable
fun NightScreen(
    live: LiveState,
    settings: AppSettings,
    onStop: () -> Unit,
    onShowLevels: () -> Unit,
    onScreen: (brightness: Float, keepOn: Boolean) -> Unit,
) {
    // 0 means "black right after starting"; a touch still shows it for a few seconds.
    val awakeSeconds = if (settings.screenOnSeconds == 0) 10 else settings.screenOnSeconds
    var awake by remember { mutableStateOf(settings.screenOnSeconds > 0) }
    var wake by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        if (wake > 0 || settings.screenOnSeconds > 0) wake++
        onPauseOrDispose { }
    }

    LaunchedEffect(wake, awakeSeconds) {
        if (wake == 0 && !awake) {
            onScreen(MIN_BRIGHTNESS, false)
            return@LaunchedEffect
        }
        awake = true
        onScreen(settings.nightBrightnessPercent / 100f, true)
        delay(awakeSeconds * 1000L)
        awake = false
        confirmStop = false
        onScreen(MIN_BRIGHTNESS, false)
    }

    // Brightness changes in settings apply while readable.
    LaunchedEffect(settings.nightBrightnessPercent) {
        if (awake) onScreen(settings.nightBrightnessPercent / 100f, true)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Night.Black)
            .testTag("night-screen")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { wake++ },
        contentAlignment = Alignment.Center,
    ) {
        if (!awake) return@Box
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp).testTag("night-awake"),
        ) {
            Text(clock(System.currentTimeMillis()), color = Night.Amber, fontSize = 80.sp)
            Text(phaseText(live.phase), color = Night.Text, fontSize = 24.sp)
            live.endAtMs?.let { Text("Termina a las ${clock(it)}", color = Night.TextDim, fontSize = 18.sp) }
            Text(
                if (live.events == 1) "1 ruido detectado" else "${live.events} ruidos detectados",
                color = Night.TextDim, fontSize = 18.sp,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = {
                    if (confirmStop) onStop() else {
                        confirmStop = true
                        wake++
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Night.Amber),
                modifier = Modifier.testTag("stop-night"),
            ) {
                Text(if (confirmStop) "Tocá otra vez para terminar" else "Terminar noche", fontSize = 18.sp)
            }
            TextButton(onClick = onShowLevels) { Text("Ver nivel del micrófono", color = Night.TextDim, fontSize = 16.sp) }
        }
    }
}

/** Lowest brightness Android accepts without treating it as "off". */
const val MIN_BRIGHTNESS = 0.01f
