package ruido.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ruido.app.ui.DiagnosticsScreen
import ruido.app.ui.HistoryScreen
import ruido.app.ui.HomeScreen
import ruido.app.ui.NightDetailScreen
import ruido.app.ui.NightScreen
import ruido.app.ui.PatternsScreen
import ruido.app.ui.Readiness
import ruido.app.ui.RuidoTheme
import ruido.app.ui.SettingsScreen
import ruido.core.NoiseColor
import ruido.core.Patterns
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private sealed interface Route {
        data object Home : Route
        data object Settings : Route
        data object History : Route
        data class Night(val startMs: Long) : Route
        data object Diagnostics : Route
        data object Patterns : Route
    }

    private lateinit var storage: NightStorage

    /** Bumped on every resume, so permissions and files are re-read after leaving the app. */
    private val resumes = mutableIntStateOf(0)

    /** What to start once the permission dialog answers. */
    private var afterPermission: (() -> Unit)? = null

    /** Progress or error of importing the user's own sound, shown in settings. */
    private val ownSoundStatus = mutableStateOf<String?>(null)

    private val pickSound = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importOwnSound(uri)
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) afterPermission?.invoke()
        else Live.update { it.copy(error = "Sin permiso de micrófono la app no puede escuchar.") }
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SettingsRepo.init(this)
        storage = NightStorage(this)
        // Opened from the quick settings tile: start the night right away.
        if (savedInstanceState == null) handleStartIntent(intent)
        setContent {
            RuidoTheme {
                // Surface sets the default text color; without it plain text is black on black.
                Surface(color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartIntent(intent)
    }

    /** Set by the tile; the night starts once the app is on screen, as Android requires for the microphone. */
    private var startWhenVisible = false

    private fun handleStartIntent(intent: Intent?) {
        if (intent?.component?.className != NightTile.START_ALIAS) return
        setIntent(Intent(this, MainActivity::class.java))
        startWhenVisible = true
    }

    override fun onResume() {
        super.onResume()
        if (startWhenVisible) {
            startWhenVisible = false
            if (!Live.state.value.running) withMicrophone { NightService.startNight(this) }
        }
        resumes.intValue++
    }

    @Composable
    private fun App() {
        val live by Live.state.collectAsStateWithLifecycle()
        val settings by SettingsRepo.state.collectAsStateWithLifecycle()
        val previewPlaying by SoundPreview.playing.collectAsStateWithLifecycle()
        var route by remember { mutableStateOf<Route>(Route.Home) }
        var nightsVersion by remember { mutableIntStateOf(0) }
        val resumeCount = resumes.intValue
        val runningStart = if (live.night) live.startMs else null

        // Only the night screen may show over the lock screen; everything else needs unlocking.
        LaunchedEffect(live.night) { setShowWhenLocked(live.night) }

        val goBack = { route = if (route is Route.Night) Route.History else Route.Home }
        BackHandler(enabled = route != Route.Home) { goBack() }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (live.night && route == Route.Home) {
                NightScreen(
                    live = live,
                    settings = settings,
                    onStop = { NightService.stop(this@MainActivity) },
                    onShowLevels = { unlockThen { route = Route.Diagnostics } },
                    onScreen = ::setScreen,
                )
                return@Box
            }
            LaunchedEffect(Unit) { setScreen(null, false) }

            when (val r = route) {
                Route.Home -> HomeScreen(
                    settings = settings,
                    lastNight = remember(nightsVersion, resumeCount, live.running) { storage.all(runningStart).firstOrNull() },
                    error = live.error,
                    testRunning = live.testing,
                    readiness = remember(resumeCount) { readiness() },
                    onStart = { withMicrophone { NightService.startNight(this@MainActivity) } },
                    onAnswer = { woke ->
                        storage.all(runningStart).firstOrNull()?.let { storage.setWokeUp(it.startMs, woke) }
                        nightsVersion++
                    },
                    onOpenNight = { route = Route.Night(it) },
                    onSettings = { route = Route.Settings },
                    onHistory = { route = Route.History },
                    onPatterns = { route = Route.Patterns },
                    onDiagnostics = { route = Route.Diagnostics },
                    onFixBattery = ::requestBatteryExemption,
                    onOpenAppSettings = ::openAppSettings,
                )
                Route.Settings -> SettingsScreen(
                    settings = settings,
                    live = live,
                    previewPlaying = previewPlaying,
                    onChange = SettingsRepo::update,
                    onPreviewSound = { SoundPreview.start(this@MainActivity) },
                    onStartTest = { withMicrophone { NightService.startTest(this@MainActivity) } },
                    onStopTest = { NightService.stop(this@MainActivity) },
                    onPickOwnSound = { pickSound.launch(arrayOf("audio/*")) },
                    ownSoundStatus = ownSoundStatus.value,
                    onBack = { if (live.testing) NightService.stop(this@MainActivity); goBack() },
                )
                Route.History -> HistoryScreen(
                    nights = remember(nightsVersion, resumeCount, live.running) { storage.all(runningStart) },
                    onOpen = { route = Route.Night(it) },
                    onBack = goBack,
                )
                is Route.Night -> NightDetailScreen(
                    night = remember(r.startMs, nightsVersion, resumeCount) { storage.get(r.startMs, runningStart) },
                    onAnswer = { woke ->
                        storage.setWokeUp(r.startMs, woke)
                        nightsVersion++
                    },
                    onDismissEvent = { eventMs, dismissed ->
                        storage.setDismissed(r.startMs, eventMs, dismissed)
                        nightsVersion++
                    },
                    onShare = ::share,
                    onDelete = {
                        storage.delete(r.startMs)
                        nightsVersion++
                        route = Route.History
                    },
                    onBack = goBack,
                )
                Route.Diagnostics -> DiagnosticsScreen(live = live, onBack = goBack)
                Route.Patterns -> {
                    val zone = ZoneId.systemDefault()
                    val nights = remember(nightsVersion, resumeCount, live.running) { storage.all(runningStart) }
                    val now = remember(nightsVersion, resumeCount) { System.currentTimeMillis() }
                    val observations = remember(nights) { storage.observations(runningStart) }
                    PatternsScreen(
                        patterns = remember(observations) { Patterns(observations, now, zone) },
                        records = nights.map { it.record },
                        preventionEnabled = settings.preventionEnabled,
                        tonight = if (live.night) live.windows
                        else remember(observations, settings) { Prevention.windows(observations, settings, now, settings.wakeAtMs(now), zone) },
                        zone = zone,
                        onBack = goBack,
                    )
                }
            }
        }
    }

    private fun importOwnSound(uri: Uri) {
        ownSoundStatus.value = "Preparando el sonido…"
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "archivo"
        Thread({
            val error = OwnSound.import(applicationContext, uri)
            runOnUiThread {
                if (error == null) {
                    SettingsRepo.update { it.copy(ownSoundName = name, color = NoiseColor.OWN) }
                    ownSoundStatus.value = null
                } else {
                    ownSoundStatus.value = error
                }
            }
        }, "ruido-import").start()
    }

    private fun withMicrophone(start: () -> Unit) {
        Live.update { it.copy(error = null) }
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            start()
        } else {
            afterPermission = start
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun readiness() = Readiness(
        microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        notifications = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
    )

    /** Runs [action] right away, or after the user unlocks the phone. */
    private fun unlockThen(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            action()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }

    /** [brightness] 0..1, or null for the phone's normal brightness. */
    private fun setScreen(brightness: Float?, keepOn: Boolean) {
        val attrs = window.attributes
        attrs.screenBrightness = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { openAppSettings() }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Compartir noche"))
    }
}
