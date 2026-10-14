package ruido.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ruido.app.ui.DiagnosticsScreen
import ruido.app.ui.HistoryScreen
import ruido.app.ui.HomeScreen
import ruido.app.ui.NightScreen
import ruido.app.ui.RuidoTheme
import ruido.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private enum class Screen { HOME, SETTINGS, HISTORY, DIAGNOSTICS }

    private lateinit var settingsStore: SettingsStore
    private lateinit var storage: NightStorage

    /** Bumped on every resume, so permission states are re-read after visiting system settings. */
    private val resumes = mutableStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) NightService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        storage = NightStorage(this)

        setContent {
            RuidoTheme {
                val live by Live.state.collectAsStateWithLifecycle()
                val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
                var screen by remember { mutableStateOf(Screen.HOME) }
                var nightsVersion by remember { mutableStateOf(0) }
                val scope = rememberCoroutineScope()
                val s = settings ?: return@RuidoTheme
                val resumeCount by resumes

                if (live.running && screen == Screen.HOME) {
                    NightScreen(
                        live = live,
                        settings = s,
                        onStop = { NightService.stop(this) },
                        onDiagnostics = { screen = Screen.DIAGNOSTICS },
                        window = window,
                    )
                    return@RuidoTheme
                }
                SideEffect { restoreWindow() }

                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        settings = s,
                        lastNight = remember(nightsVersion, live.running) { storage.latest() },
                        error = live.error,
                        batteryOptimized = remember(resumeCount) { !isIgnoringBatteryOptimizations() },
                        onStart = ::startNight,
                        onAnswer = { start, woke ->
                            storage.setWokeUp(start, woke)
                            nightsVersion++
                        },
                        onSettings = { screen = Screen.SETTINGS },
                        onHistory = { screen = Screen.HISTORY },
                        onDiagnostics = { screen = Screen.DIAGNOSTICS },
                        onFixBattery = ::requestBatteryExemption,
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        settings = s,
                        onChange = { scope.launch { settingsStore.save(it) } },
                        onBack = { screen = Screen.HOME },
                    )
                    Screen.HISTORY -> HistoryScreen(
                        nights = remember(nightsVersion) { storage.all() },
                        onShare = ::shareNight,
                        onBack = { screen = Screen.HOME },
                    )
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(
                        live = live,
                        onShareLast = { storage.latest()?.let { shareNight(it.startMs) } },
                        onBack = { screen = Screen.HOME },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.value++
    }

    private fun startNight() {
        Live.update { it.copy(error = null) }
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) NightService.start(this) else permissions.launch(needed.toTypedArray())
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
        )
    }

    private fun shareNight(startMs: Long) {
        val csv = storage.csv(startMs) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Noche $startMs")
            .putExtra(Intent.EXTRA_TEXT, csv)
        startActivity(Intent.createChooser(send, "Compartir registro"))
    }

    /** Undo what the night screen did to the window. */
    private fun restoreWindow() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attrs = window.attributes
        if (attrs.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attrs
        }
    }
}
