package ruido.app

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ruido.core.NightEngine
import ruido.core.NightRecord
import ruido.core.NoiseColor
import ruido.core.NoiseEvent
import ruido.core.PlaybackSpan
import java.io.File

/**
 * Runs the real activity on the computer (Robolectric): taps buttons, moves sliders and
 * saves screenshots of every screen to build/capturas.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class AppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/capturas/$name.png")

    companion object {
        private const val HOUR = 3_600_000L
        private const val START = 1_760_400_000_000L

        fun seedNights() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            File(context.filesDir, "nights").deleteRecursively()
            val storage = NightStorage(context)
            storage.save(
                NightRecord(
                    startMs = START, endMs = START + 8 * HOUR,
                    events = listOf(
                        NoiseEvent(START + HOUR, START + HOUR + 1500, 18.0, -38.0, startedPlayback = true, duringPlayback = false),
                        NoiseEvent(START + HOUR + 300_000, START + HOUR + 302_000, 15.0, -40.0, startedPlayback = false, duringPlayback = true),
                        NoiseEvent(START + 4 * HOUR, START + 4 * HOUR + 900, 22.0, -33.0, startedPlayback = true, duringPlayback = false),
                    ),
                    spans = listOf(
                        PlaybackSpan(START + HOUR, START + HOUR + 20 * 60_000, PlaybackSpan.Reason.NOISE),
                        PlaybackSpan(START + 4 * HOUR, START + 4 * HOUR + 15 * 60_000, PlaybackSpan.Reason.NOISE),
                    ),
                ),
            )
            // A night Android cut off: no end.
            val cut = START - 24 * HOUR
            storage.save(NightRecord(cut, null, emptyList(), emptyList()))
            File(context.filesDir, "nights/$cut.csv").setLastModified(cut + 3 * HOUR)
        }
    }

    /** Two weeks of nights: every Friday and Saturday night the neighbors are loud around 01:00. */
    private fun seedPatternNights() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "nights").deleteRecursively()
        val storage = NightStorage(context)
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        for (d in 1..14L) {
            val evening = today.minusDays(d)
            val start = evening.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
            val end = evening.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            val loud = evening.dayOfWeek == java.time.DayOfWeek.FRIDAY || evening.dayOfWeek == java.time.DayOfWeek.SATURDAY
            val events = if (!loud) emptyList() else listOf(1 to 0, 1 to 30, 2 to 10).map { (h, m) ->
                val t = evening.plusDays(1).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
                NoiseEvent(t, t + 2000, 16.0, -40.0, startedPlayback = false, duringPlayback = false, kind = ruido.core.SoundKind.MUSIC)
            }
            storage.save(NightRecord(start, end, events, emptyList(), wokeUp = if (loud) d % 2 == 0L else false))
        }
    }

    @Before
    fun reset() {
        Live.update { LiveState() }
    }

    /** The app keeps these in memory: start every test from scratch. */
    @After
    fun cleanUp() {
        Live.update { LiveState() }
        SettingsRepo.reset()
    }

    @Test
    fun homeScreen() {
        compose.onNodeWithTag("start-night").assertIsDisplayed()
        shot("01-inicio")
    }

    @Test
    fun settingsSlidersChangeAndPersist() {
        SettingsRepo.update { AppSettings() }
        compose.onNodeWithTag("open-settings").performClick()
        shot("02-ajustes")

        compose.onNodeWithTag("threshold").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        compose.onNodeWithTag("threshold-value").assertTextEquals("8 dB")
        assertEquals(8, SettingsRepo.current.thresholdDb)

        compose.onNodeWithTag("volume").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(70f) }
        compose.onNodeWithTag("volume-value").assertTextEquals("70 %")

        compose.onNodeWithTag("color-BROWN").performScrollTo().performClick()
        assertEquals(NoiseColor.BROWN, SettingsRepo.current.color)

        compose.onNodeWithTag("screen-on-30").performScrollTo().performClick()
        assertEquals(30, SettingsRepo.current.screenOnSeconds)
        shot("03-ajustes-abajo")

        // Leave and come back: values are kept, also in the saved preferences.
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("threshold").performScrollTo()
        compose.onNodeWithTag("threshold-value").assertTextEquals("8 dB")
        val prefs = compose.activity.getSharedPreferences("settings", 0)
        assertEquals(8, prefs.getInt("threshold_db", -1))
        assertEquals("BROWN", prefs.getString("color", null))
    }

    @Test
    fun settingsTestModeShowsLiveLevel() {
        compose.onNodeWithTag("open-settings").performClick()
        Live.update {
            LiveState(
                running = true, test = true, phase = NightEngine.Phase.LISTENING,
                levelDb = -38.0, baselineDb = -62.0, expectedDb = -62.0, thresholdDb = 12.0, noisy = true, events = 2,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("test-phase").assertTextEquals("Escuchando")
        compose.onNodeWithTag("level-meter").assertIsDisplayed()
        compose.onNodeWithTag("stop-test").assertIsDisplayed()
        shot("04-ajustes-prueba")

        // The service stopped: the test controls go back to "start".
        Live.update { it.stopped() }
        compose.waitForIdle()
        compose.onNodeWithTag("start-test").assertIsDisplayed()
        compose.onNodeWithTag("stop-test").assertDoesNotExist()
        compose.onNodeWithTag("test-phase").assertDoesNotExist()
    }

    @Test
    fun nightScreenDimsAndWakesOnTouch() {
        SettingsRepo.update { it.copy(screenOnSeconds = 10, nightBrightnessPercent = 40) }
        // The whole night screen is one tap target, so its content lives in the unmerged tree.
        // Drive the clock by hand, so the 10 s timer only passes when the test says so.
        compose.mainClock.autoAdvance = false
        Live.update {
            LiveState(running = true, phase = NightEngine.Phase.LISTENING, startMs = START, endAtMs = START + 8 * HOUR, events = 3)
        }
        frame(200)
        compose.onNodeWithTag("night-awake", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0.4f, brightness(), 0.001f)
        assertEquals(true, keepsScreenOn())
        shot("05-noche-legible")

        frame(11_000)
        compose.onNodeWithTag("night-awake", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0.01f, brightness(), 0.001f)
        assertEquals(false, keepsScreenOn())

        compose.onNodeWithTag("night-screen").performClick()
        frame(200)
        compose.onNodeWithTag("night-awake", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0.4f, brightness(), 0.001f)

        // Night over: normal brightness again.
        Live.update { LiveState() }
        frame(200)
        displayed("start-night")
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness())
    }

    @Test
    fun historyListDetailAndDelete() {
        seedNights()
        compose.onNodeWithTag("open-history").performScrollTo().performClick()
        compose.onNodeWithText("se cortó", substring = true).assertIsDisplayed()
        shot("06-historial")

        compose.onNodeWithTag("night-$START").performClick()
        compose.onNodeWithText("Ruidos (3)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("woke-no").performScrollTo().performClick()
        shot("07-detalle")
        val context = compose.activity
        assertEquals(false, NightStorage(context).get(START)!!.record.wokeUp)

        compose.onNodeWithTag("delete-night").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-delete").performClick()
        compose.onNodeWithTag("night-$START").assertDoesNotExist()
        assertEquals(1, NightStorage(context).all().size)
    }

    @Test
    fun preventionSlotsAndRecognitionSettings() {
        SettingsRepo.update { AppSettings() }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("add-slot").performScrollTo().performClick()
        compose.onNodeWithTag("slot-night-5").performClick() // add Saturday night to the default Friday
        compose.onNodeWithTag("confirm-slot").performClick()
        compose.onNodeWithTag("slot-0").performScrollTo().assertTextEquals("Vie, Sáb · 01:00 a 04:00")
        assertEquals(setOf(4, 5), SettingsRepo.current.manualSlots.single().nights)

        compose.onNodeWithTag("adaptive").performScrollTo().performClick()
        assertEquals(true, SettingsRepo.current.adaptiveVolume)
        compose.onNodeWithTag("prevention").performScrollTo()
        shot("10-ajustes-prevencion")

        val prefs = compose.activity.getSharedPreferences("settings", 0)
        assertEquals("45-60-240", prefs.getString("manual_slots", null))

        compose.onNodeWithTag("color-OWN").performScrollTo().performClick()
        compose.onNodeWithTag("own-sound-status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pick-own-sound").assertIsDisplayed()
    }

    @Test
    fun patternsScreenShowsWhatItLearned() {
        seedPatternNights()
        compose.onNodeWithTag("open-patterns").performScrollTo().performClick()
        compose.onNodeWithText("Aprendió de 14 noches", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("week-map").assertIsDisplayed()
        shot("09-horarios")
        compose.onNodeWithTag("weekdays").performScrollTo().assertIsDisplayed()
        shot("09-horarios-abajo")
    }

    @Test
    fun noiseCanBeMarkedAsNotANeighbor() {
        seedNights()
        compose.onNodeWithTag("open-history").performScrollTo().performClick()
        compose.onNodeWithTag("night-$START").performClick()
        compose.onNodeWithTag("event-${START + HOUR}").performScrollTo().performClick()
        compose.onNodeWithTag("event-${START + HOUR}").assert(hasText("no fue un vecino", substring = true))
        val stored = NightStorage(compose.activity).get(START)!!.record.events.first()
        assertEquals(true, stored.dismissed)
        shot("07-detalle-descartado")
    }

    @Test
    fun diagnosticsWhileListening() {
        Live.update {
            LiveState(
                running = true, test = true, phase = NightEngine.Phase.PLAYING, levelDb = -50.0, baselineDb = -61.0,
                expectedDb = -52.0, thresholdDb = 12.0, leakDb = -53.0, gain = 0.5, events = 4, playbackStarts = 1,
                audioSource = "Sin procesar",
            )
        }
        compose.onNodeWithTag("open-diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Sin procesar").assertIsDisplayed()
        shot("08-diagnostico")
    }

    /** Moves the paused clock and lets composition and layout catch up. */
    private fun frame(ms: Long) {
        // State from flows lands on the main looper, outside the paused clock: settle it,
        // move time, then run one more frame so composition and layout both catch up.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Like assertIsDisplayed, but a failure prints what is on screen. */
    private fun displayed(tag: String) {
        try {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        } catch (e: AssertionError) {
            throw AssertionError("$tag not displayed. Screen:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }

    private fun brightness(): Float {
        var b = 0f
        compose.activityRule.scenario.onActivity { b = it.window.attributes.screenBrightness }
        return b
    }

    private fun keepsScreenOn(): Boolean {
        var on = false
        compose.activityRule.scenario.onActivity {
            on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        }
        return on
    }
}
