package ruido.core

import kotlin.math.max
import kotlin.math.min

/**
 * Decides, every 100 ms window of microphone level, whether masking noise should play
 * and at what gain. Pure logic: the Android service feeds it levels and applies [Tick.gain].
 *
 * The night can open with a stretch of constant noise ([NightConfig.constantMs]), for
 * example while the kid falls asleep and the parents are out of the room. After it,
 * the engine measures the room and switches to listening.
 *
 * The microphone also hears the noise this app plays. To avoid keeping itself on, the
 * engine learns at start how loud its own noise sounds to the microphone ("leak") and,
 * while playing, compares the level with background + leak instead of background alone.
 */
class NightEngine(config: NightConfig, startMs: Long) {

    enum class Phase { CONSTANT, CALIBRATING_QUIET, CALIBRATING_LEAK, LISTENING, PLAYING, ENDED }

    data class Tick(
        val phase: Phase,
        /** Gain the player should use now, 0..1. */
        val gain: Double,
        val levelDb: Double,
        val smoothedDb: Double,
        val baselineDb: Double,
        /** Background plus the app's own noise at the current gain. */
        val expectedDb: Double,
        val noisy: Boolean,
        /** Set on the tick a noise ends. */
        val finishedEvent: NoiseEvent? = null,
        /** Set on the tick a playback ends. */
        val finishedSpan: PlaybackSpan? = null,
    )

    var config = config
        private set

    var phase = Phase.CALIBRATING_QUIET
        private set
    var gain = 0.0
        private set

    /** When the constant noise at the start ends, or null when there is none (or it already ended). */
    var constantEndMs: Long? = null
        private set

    private var target = 0.0
    private var phaseStartMs = startMs

    private val baseline = RollingPercentile(
        max(1, (config.baselineWindowMs / config.windowMs).toInt()),
    )
    private var baselineDb = Levels.FLOOR_DB
    private var leakPowerAtUnitGain = 0.0
    private var leakCalibrated = false
    private val leakSamples = ArrayList<Double>()

    private val recentPower = DoubleArray(max(1, config.smoothingWindows))
    private var recentCount = 0
    private var recentNext = 0

    private var noisySinceMs: Long? = null
    private var eventStartMs: Long? = null
    private var eventPeakExcess = 0.0
    private var eventPeakLevel = Levels.FLOOR_DB
    private var eventStartedPlayback = false
    private var eventDuringPlayback = false

    private var spanStartMs: Long? = null
    private var spanReason = PlaybackSpan.Reason.NOISE
    /** Gain for noises in the current playback (grows with louder noises when adaptive). */
    private var noiseGain = 0.0
    private var lastNoiseMs = 0L
    private var forcedStop = false

    val events = mutableListOf<NoiseEvent>()
    val spans = mutableListOf<PlaybackSpan>()

    init {
        if (config.constantMs > 0) {
            phase = Phase.CONSTANT
            constantEndMs = startMs + config.constantMs
            spanStartMs = startMs
            spanReason = PlaybackSpan.Reason.CONSTANT
            target = config.volume
        }
    }

    fun tick(nowMs: Long, levelDb: Double): Tick {
        val smoothedDb = smooth(levelDb)
        val expectedDb = Levels.powerToDb(Levels.dbToPower(baselineDb) + leakPowerAtUnitGain * gain * gain)
        val detecting = phase == Phase.LISTENING || phase == Phase.PLAYING
        val excess = smoothedDb - expectedDb
        // Android feeds pure digital silence while another app (a call, the assistant) holds the
        // microphone. Those windows say nothing about the room: don't learn from them.
        val muted = levelDb <= Levels.FLOOR_DB + 0.5
        val noisy = detecting && !muted && recentCount >= recentPower.size && excess >= config.thresholdDb

        var finishedEvent: NoiseEvent? = null
        var finishedSpan: PlaybackSpan? = null

        when (phase) {
            Phase.CONSTANT -> finishedSpan = updateConstant(nowMs)

            Phase.CALIBRATING_QUIET -> {
                if (!muted) baseline.add(levelDb)
                if (nowMs - phaseStartMs >= config.calibrateQuietMs) {
                    baselineDb = baseline.percentile(config.baselinePercentile) ?: Levels.FLOOR_DB
                    if (config.calibrateLeakMs > 0 && !leakCalibrated && config.volume > 0) {
                        enter(Phase.CALIBRATING_LEAK, nowMs)
                        target = config.volume
                    } else {
                        enter(Phase.LISTENING, nowMs)
                    }
                }
            }

            Phase.CALIBRATING_LEAK -> {
                if (target > 0 && gain >= target) {
                    if (!muted) leakSamples += levelDb
                    if ((nowMs - phaseStartMs) >= config.calibrateLeakMs + 1_000) {
                        // Median, so a neighbor's noise during these seconds doesn't inflate it.
                        val median = leakSamples.sorted().getOrNull(leakSamples.size / 2) ?: baselineDb
                        val leakPower = Levels.dbToPower(median) - Levels.dbToPower(baselineDb)
                        leakPowerAtUnitGain = max(0.0, leakPower) / (gain * gain)
                        leakCalibrated = true
                        target = 0.0
                    }
                } else if (target == 0.0 && gain == 0.0) {
                    enter(Phase.LISTENING, nowMs)
                }
            }

            Phase.LISTENING, Phase.PLAYING -> {
                if (!muted) finishedEvent = trackEvent(nowMs, levelDb, excess, noisy)
                finishedSpan = updatePlayback(nowMs)
                if (phase == Phase.LISTENING && gain == 0.0 && !noisy && !muted && eventStartMs == null) {
                    baseline.add(levelDb)
                    baselineDb = baseline.percentile(config.baselinePercentile) ?: baselineDb
                }
            }

            Phase.ENDED -> Unit
        }

        stepGain()
        return Tick(phase, gain, levelDb, smoothedDb, baselineDb, expectedDb, noisy, finishedEvent, finishedSpan)
    }

    /** Ends the constant noise now (fading out) and moves on to listening. */
    fun endConstant(nowMs: Long) {
        val end = constantEndMs ?: return
        constantEndMs = min(end, nowMs)
    }

    /** Ends the night now: fades out and closes any open noise or playback. */
    fun finish(nowMs: Long) {
        closeEvent(nowMs)
        closeSpan(nowMs)
        phase = Phase.ENDED
        constantEndMs = null
        target = 0.0
    }

    /**
     * Applies new settings mid-night (used by the test mode in settings). The end time,
     * window and averaging lengths keep their original values.
     */
    fun updateConfig(newConfig: NightConfig) {
        config = newConfig.copy(
            endAtMs = config.endAtMs,
            windowMs = config.windowMs,
            smoothingWindows = config.smoothingWindows,
            baselineWindowMs = config.baselineWindowMs,
            constantMs = config.constantMs,
        )
        if (phase == Phase.PLAYING && noiseGain > 0) noiseGain = config.volume
        if (phase == Phase.CONSTANT && target > 0) target = config.volume
    }

    /**
     * Forgets the background and the app's own noise and measures both again, for example
     * after the phone's media volume changed.
     */
    fun recalibrate(nowMs: Long) {
        if (phase == Phase.CONSTANT) return // Measured once the constant noise ends.
        closeEvent(nowMs)
        closeSpan(nowMs)
        baseline.clear()
        baselineDb = Levels.FLOOR_DB
        leakCalibrated = false
        leakPowerAtUnitGain = 0.0
        leakSamples.clear()
        forcedStop = false
        target = 0.0
        gain = 0.0
        enter(Phase.CALIBRATING_QUIET, nowMs)
    }

    /** How loud the app's own noise sounds to the microphone at the configured volume, or null if unknown. */
    val leakDb: Double?
        get() = if (leakCalibrated) Levels.powerToDb(leakPowerAtUnitGain * config.volume * config.volume) else null

    private fun updateConstant(nowMs: Long): PlaybackSpan? {
        val endAt = config.endAtMs
        val wake = endAt != null && nowMs >= endAt
        if (wake || nowMs >= (constantEndMs ?: nowMs)) target = 0.0
        if (target > 0.0 || gain > 0.0) return null
        val span = closeSpan(nowMs)
        constantEndMs = null
        if (wake) {
            phase = Phase.ENDED
        } else {
            enter(Phase.CALIBRATING_QUIET, nowMs)
        }
        return span
    }

    private fun trackEvent(nowMs: Long, levelDb: Double, excess: Double, noisy: Boolean): NoiseEvent? {
        if (!noisy) {
            noisySinceMs = null
            return closeEvent(nowMs)
        }
        val since = noisySinceMs ?: nowMs.also { noisySinceMs = it }
        if (eventStartMs == null && nowMs - since + config.windowMs >= config.minEventMs) {
            eventStartMs = since
            eventPeakExcess = excess
            eventPeakLevel = levelDb
            eventDuringPlayback = phase == Phase.PLAYING
            eventStartedPlayback = false
            onNoise(nowMs, excess)
        } else if (eventStartMs != null) {
            eventPeakExcess = max(eventPeakExcess, excess)
            eventPeakLevel = max(eventPeakLevel, levelDb)
            onNoise(nowMs, eventPeakExcess)
        }
        return null
    }

    private fun onNoise(nowMs: Long, excess: Double) {
        if (forcedStop) return
        val endAt = config.endAtMs
        val tooLate = endAt != null && nowMs >= endAt - config.quietBeforeEndMs
        if (phase == Phase.LISTENING) {
            if (tooLate) return
            enter(Phase.PLAYING, nowMs)
            spanStartMs = nowMs
            spanReason = PlaybackSpan.Reason.NOISE
            noiseGain = 0.0
            eventStartedPlayback = true
        }
        lastNoiseMs = nowMs
        noiseGain = max(noiseGain, gainFor(excess))
        target = noiseGain
    }

    /** Masking gain for a noise [excess] dB above the expected level. */
    private fun gainFor(excess: Double): Double {
        if (!config.adaptiveVolume) return config.volume
        val louder = ((excess - config.thresholdDb) / ADAPTIVE_RANGE_DB).coerceIn(0.0, 1.0)
        return config.volume * (0.5 + 0.5 * louder)
    }

    private fun updatePlayback(nowMs: Long): PlaybackSpan? {
        val endAt = config.endAtMs
        if (endAt != null && nowMs >= endAt) {
            closeEvent(nowMs)
            val span = closeSpan(nowMs)
            phase = Phase.ENDED
            target = 0.0
            return span
        }
        if (phase != Phase.PLAYING) return null

        val start = spanStartMs ?: nowMs
        if (!forcedStop && nowMs - start >= config.maxPlaybackMs) forcedStop = true
        if (forcedStop || nowMs - lastNoiseMs >= config.holdMs) target = 0.0

        if (target == 0.0 && gain == 0.0) {
            val span = closeSpan(nowMs)
            noiseGain = 0.0
            if (forcedStop) {
                // The background may have changed for good: learn it again.
                forcedStop = false
                baseline.clear()
                enter(Phase.CALIBRATING_QUIET, nowMs)
            } else {
                enter(Phase.LISTENING, nowMs)
            }
            return span
        }
        return null
    }

    private fun closeEvent(nowMs: Long): NoiseEvent? {
        val start = eventStartMs ?: return null
        eventStartMs = null
        noisySinceMs = null
        return NoiseEvent(start, nowMs, eventPeakExcess, eventPeakLevel, eventStartedPlayback, eventDuringPlayback)
            .also { events += it }
    }

    private fun closeSpan(nowMs: Long): PlaybackSpan? {
        val start = spanStartMs ?: return null
        spanStartMs = null
        return PlaybackSpan(start, nowMs, spanReason).also { spans += it }
    }

    private fun enter(next: Phase, nowMs: Long) {
        phase = next
        phaseStartMs = nowMs
        if (next == Phase.LISTENING || next == Phase.CALIBRATING_QUIET) {
            // Recent windows may contain the app's own noise at a gain that no longer applies.
            recentCount = 0
            recentNext = 0
        }
    }

    private fun stepGain() {
        val volume = max(config.volume, 1e-6)
        if (gain < target) {
            val fade = if (phase == Phase.CALIBRATING_LEAK) 500L else config.fadeInMs
            gain = min(target, gain + volume * config.windowMs / max(1L, fade))
        } else if (gain > target) {
            val fade = if (phase == Phase.CALIBRATING_LEAK) 500L else config.fadeOutMs
            gain = max(target, gain - volume * config.windowMs / max(1L, fade))
        }
        if (gain < 1e-9) gain = 0.0
    }

    private companion object {
        /** Noises this many dB above the threshold get the full volume when adaptive. */
        const val ADAPTIVE_RANGE_DB = 15.0
    }

    private fun smooth(levelDb: Double): Double {
        recentPower[recentNext] = Levels.dbToPower(levelDb)
        recentNext = (recentNext + 1) % recentPower.size
        if (recentCount < recentPower.size) recentCount++
        var sum = 0.0
        for (i in 0 until recentCount) sum += recentPower[i]
        return Levels.powerToDb(sum / recentCount)
    }
}
