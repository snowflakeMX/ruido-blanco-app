package ruido.core

import ruido.core.NightConfig.Companion.MINUTE
import ruido.core.NightConfig.Companion.SECOND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Simulated bedroom: the microphone hears the background, the neighbors and the app's
 * own noise (which, at full volume, is far louder than the background).
 */
class NightEngineTest {

    private class Room(
        val backgroundDb: Double = -60.0,
        /** Level of the app's noise at the microphone at gain 1. */
        val leakAtUnitGainDb: Double = -35.0,
    ) {
        val noises = mutableListOf<Triple<Long, Long, Double>>()
        var backgroundShiftDb = 0.0

        fun noise(atMs: Long, durationMs: Long, levelDb: Double) {
            noises += Triple(atMs, atMs + durationMs, levelDb)
        }

        fun levelAt(nowMs: Long, gain: Double): Double {
            var power = Levels.dbToPower(backgroundDb + backgroundShiftDb)
            power += Levels.dbToPower(leakAtUnitGainDb) * gain * gain
            for ((from, to, db) in noises) if (nowMs in from until to) power += Levels.dbToPower(db)
            return Levels.powerToDb(power)
        }
    }

    private class Run(val engine: NightEngine, val ticks: List<Pair<Long, NightEngine.Tick>>) {
        fun gainAt(ms: Long) = ticks.last { it.first <= ms }.second.gain
        fun phaseAt(ms: Long) = ticks.last { it.first <= ms }.second.phase
    }

    private fun run(room: Room, config: NightConfig, durationMs: Long, onTick: (Long) -> Unit = {}): Run {
        val engine = NightEngine(config, 0)
        val ticks = ArrayList<Pair<Long, NightEngine.Tick>>()
        var now = 0L
        while (now < durationMs) {
            onTick(now)
            // The microphone hears what was played during the previous window.
            ticks += now to engine.tick(now, room.levelAt(now, engine.gain))
            now += config.windowMs
        }
        return Run(engine, ticks)
    }

    private val config = NightConfig(volume = 1.0)

    @Test
    fun quietNightNeverPlays() {
        val r = run(Room(), config, 30 * MINUTE)
        assertTrue(r.engine.spans.isEmpty())
        assertTrue(r.engine.events.isEmpty())
        assertEquals(NightEngine.Phase.LISTENING, r.phaseAt(30 * MINUTE))
    }

    @Test
    fun calibrationLearnsBackgroundAndLeak() {
        val r = run(Room(), config, 30 * SECOND)
        val last = r.ticks.last().second
        assertEquals(-60.0, last.baselineDb, 0.6)
        assertEquals(-35.0, r.engine.leakDb!!, 1.0)
    }

    @Test
    fun barkStartsPlaybackThatStopsAfterHold() {
        val room = Room().apply { noise(2 * MINUTE, 1 * SECOND, -40.0) }
        val r = run(room, config, 25 * MINUTE)

        assertEquals(1, r.engine.spans.size, "one playback")
        val span = r.engine.spans.single()
        assertTrue(span.startMs - 2 * MINUTE <= 700, "starts fast: ${span.startMs}")
        // Holds 15 min after the bark, then fades out over 20 s.
        assertTrue(span.durationMs in (15 * MINUTE)..(15 * MINUTE + 25 * SECOND), "lasted ${span.durationMs}")
        assertEquals(1.0, r.gainAt(5 * MINUTE))
        assertEquals(0.0, r.gainAt(20 * MINUTE))
        assertEquals(NightEngine.Phase.LISTENING, r.phaseAt(20 * MINUTE))
        assertTrue(r.engine.events.single().startedPlayback)
    }

    @Test
    fun withoutLeakCompensationItKeepsItselfOn() {
        val room = Room().apply { noise(2 * MINUTE, 1 * SECOND, -40.0) }
        val r = run(room, config.copy(calibrateLeakMs = 0), 40 * MINUTE)
        // Its own noise counts as noise, so it only stops at the 60 min safety limit.
        assertEquals(1.0, r.gainAt(39 * MINUTE))
    }

    @Test
    fun noiseWhilePlayingExtendsHold() {
        val room = Room().apply {
            noise(2 * MINUTE, 1 * SECOND, -40.0)
            // Must stand out over background + the app's own noise (-35 dB at the mic).
            noise(12 * MINUTE, 2 * SECOND, -20.0)
        }
        val r = run(room, config, 40 * MINUTE)
        val span = r.engine.spans.single()
        assertTrue(span.endMs >= 27 * MINUTE, "hold restarted at the second noise: ${span.endMs}")
        assertEquals(2, r.engine.events.size)
        assertTrue(r.engine.events[1].duringPlayback)
    }

    @Test
    fun shortClickIsIgnored() {
        val room = Room().apply { noise(2 * MINUTE, 100, -30.0) }
        val r = run(room, config, 5 * MINUTE)
        assertTrue(r.engine.spans.isEmpty())
    }

    @Test
    fun slowBackgroundChangeIsAbsorbed() {
        val room = Room()
        // A fan raises the background by 6 dB, below the 12 dB threshold.
        val r = run(room, config, 20 * MINUTE) { now -> if (now >= 2 * MINUTE) room.backgroundShiftDb = 6.0 }
        assertTrue(r.engine.spans.isEmpty())
        assertEquals(-54.0, r.ticks.last().second.baselineDb, 0.6)
    }

    @Test
    fun stopsAtWakeTimeAndStartsNothingJustBefore() {
        val end = 60 * MINUTE
        val room = Room().apply {
            noise(30 * MINUTE, 1 * SECOND, -40.0)
            noise(50 * MINUTE, 1 * SECOND, -40.0) // within the last 15 min: ignored
        }
        val r = run(room, config.copy(endAtMs = end), 62 * MINUTE)
        assertEquals(1, r.engine.spans.size)
        assertEquals(NightEngine.Phase.ENDED, r.phaseAt(61 * MINUTE))
        assertEquals(0.0, r.gainAt(61 * MINUTE))
    }

    @Test
    fun playbackRunningAtWakeTimeIsCut() {
        val end = 60 * MINUTE
        val room = Room().apply { noise(40 * MINUTE, 1 * SECOND, -40.0) }
        val r = run(room, config.copy(endAtMs = end, holdMs = 30 * MINUTE), 62 * MINUTE)
        assertEquals(end, r.engine.spans.single().endMs)
    }

    @Test
    fun maxPlaybackStopsAndRelearnsBackground() {
        val room = Room()
        // Loud music next door that never stops.
        room.noise(2 * MINUTE, 10 * 60 * MINUTE, -30.0)
        val r = run(room, config.copy(maxPlaybackMs = 30 * MINUTE), 50 * MINUTE)
        val first = r.engine.spans.first()
        assertTrue(first.durationMs in (30 * MINUTE)..(31 * MINUTE))
        // After re-learning, the music is the new background and no longer triggers.
        assertEquals(1, r.engine.spans.size)
        assertEquals(NightEngine.Phase.LISTENING, r.phaseAt(49 * MINUTE))
    }

    @Test
    fun finishClosesOpenPlayback() {
        val room = Room().apply { noise(2 * MINUTE, 1 * SECOND, -40.0) }
        val config = config
        val engine = NightEngine(config, 0)
        var now = 0L
        while (now < 5 * MINUTE) {
            engine.tick(now, room.levelAt(now, engine.gain)); now += 100
        }
        engine.finish(now)
        assertEquals(now, engine.spans.single().endMs)
        assertEquals(NightEngine.Phase.ENDED, engine.phase)
        assertNull(engine.tick(now + 100, -60.0).finishedSpan)
    }

    @Test
    fun mutedMicrophoneIsIgnored() {
        // Another app holds the microphone for a minute: Android feeds digital silence.
        val engine = NightEngine(config, 0)
        val room = Room()
        var now = 0L
        while (now < 10 * MINUTE) {
            val muted = now in (3 * MINUTE) until (4 * MINUTE)
            engine.tick(now, if (muted) Levels.FLOOR_DB else room.levelAt(now, engine.gain))
            now += 100
        }
        assertTrue(engine.spans.isEmpty(), "returning from silence is not a noise")
        assertEquals(-60.0, engine.tick(now, room.levelAt(now, 0.0)).baselineDb, 0.6)
    }

    @Test
    fun neighborNoiseDuringLeakCalibrationDoesNotInflateIt() {
        val room = Room().apply { noise(11_500, 800, -30.0) }
        val r = run(room, config, 30 * SECOND)
        assertEquals(-35.0, r.engine.leakDb!!, 1.0)
    }

    @Test
    fun lowerThresholdAppliesImmediately() {
        val room = Room().apply { noise(3 * MINUTE, 1 * SECOND, -52.0) } // +8 dB
        val engine = NightEngine(config, 0)
        var now = 0L
        while (now < 5 * MINUTE) {
            if (now == 2 * MINUTE) engine.updateConfig(config.copy(thresholdDb = 6.0))
            engine.tick(now, room.levelAt(now, engine.gain)); now += 100
        }
        assertEquals(1, engine.spans.size + if (engine.phase == NightEngine.Phase.PLAYING) 1 else 0)
    }

    @Test
    fun recalibrateLearnsAgain() {
        val room = Room()
        val engine = NightEngine(config, 0)
        var now = 0L
        while (now < 2 * MINUTE) { engine.tick(now, room.levelAt(now, engine.gain)); now += 100 }
        engine.recalibrate(now)
        assertEquals(NightEngine.Phase.CALIBRATING_QUIET, engine.phase)
        assertNull(engine.leakDb)
        val louder = Room(backgroundDb = -50.0)
        while (now < 3 * MINUTE) { engine.tick(now, louder.levelAt(now, engine.gain)); now += 100 }
        assertEquals(NightEngine.Phase.LISTENING, engine.phase)
        assertEquals(-50.0, engine.tick(now, -50.0).baselineDb, 0.6)
    }
}
