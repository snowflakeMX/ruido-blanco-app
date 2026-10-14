package ruido.core

import kotlin.math.max
import kotlin.math.min

/**
 * Decides, every 100 ms window of microphone level, whether masking noise should play
 * and at what gain. Pure logic: the Android service feeds it levels and applies [Tick.gain].
 *
 * The microphone also hears the noise this app plays. To avoid keeping itself on, the
 * engine learns at start how loud its own noise sounds to the microphone ("leak") and,
 * while playing, compares the level with background + leak instead of background alone.
 */
class NightEngine(config: NightConfig, startMs: Long) {

    enum class Phase { CALIBRATING_QUIET, CALIBRATING_LEAK, LISTENING, PLAYING, ENDED }

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
        /** Start time of a noise that began on this tick, so it can be classified. */
        val startedEventMs: Long? = null,
    )

    var config = config
        private set

    var phase = Phase.CALIBRATING_QUIET
        private set
    var gain = 0.0
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
    /** The noise that started the current playback, and whether others came after it. */
    private var spanTriggerMs: Long? = null
    private var spanOtherNoise = false
    /** The current playback was started by a room sound: fade out and forget it. */
    private var cancelSpan = false
    /** Gain for noises in the current playback (grows with louder noises when adaptive). */
    private var noiseGain = 0.0
    private var lastNoiseMs = Long.MIN_VALUE / 2
    /** Noise times before each event, to undo an event later found to be a room sound. */
    private val noiseBefore = HashMap<Long, Long>()
    private var lastNoiseEventMs: Long? = null
    private val kinds = HashMap<Long, SoundKind>()
    private var startedEventMs: Long? = null
    private var forcedStop = false

    val events = mutableListOf<NoiseEvent>()
    val spans = mutableListOf<PlaybackSpan>()

    fun tick(nowMs: Long, levelDb: Double): Tick {
        lastTickMs = nowMs
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
        startedEventMs = null

        when (phase) {
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
        return Tick(phase, gain, levelDb, smoothedDb, baselineDb, expectedDb, noisy, finishedEvent, finishedSpan, startedEventMs)
    }

    /** Ends the night now: fades out and closes any open noise or playback. */
    fun finish(nowMs: Long) {
        closeEvent(nowMs)
        closeSpan(nowMs)
        phase = Phase.ENDED
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
            preventive = config.preventive,
        )
        if (phase == Phase.PLAYING && noiseGain > 0) noiseGain = config.volume
    }

    /**
     * Sets the kind of the noise that started at [eventStartMs], once the classifier is
     * done (about a second later). A room sound (snoring, coughing, the kid) does not keep
     * the masking on, and if it alone started it, the masking fades out again.
     */
    fun classify(eventStartMs: Long, kind: SoundKind) {
        kinds[eventStartMs] = kind
        val i = events.indexOfFirst { it.startMs == eventStartMs }
        if (i >= 0) events[i] = events[i].copy(kind = kind)
        if (kind.neighbors) return
        if (spanTriggerMs == eventStartMs && !spanOtherNoise && spanReason == PlaybackSpan.Reason.NOISE && phase == Phase.PLAYING) {
            cancelSpan = true
        }
        if (lastNoiseEventMs == eventStartMs) {
            lastNoiseMs = noiseBefore[eventStartMs] ?: (Long.MIN_VALUE / 2)
        }
    }

    /** True while playing softly because noise is expected now, not because of a noise. */
    val preventing: Boolean
        get() = phase == Phase.PLAYING && !noiseActive(lastTickMs) && inPreventive(lastTickMs)

    private var lastTickMs = 0L

    /**
     * Forgets the background and the app's own noise and measures both again, for example
     * after the phone's media volume changed.
     */
    fun recalibrate(nowMs: Long) {
        closeEvent(nowMs)
        closeSpan(nowMs)
        baseline.clear()
        baselineDb = Levels.FLOOR_DB
        leakCalibrated = false
        leakPowerAtUnitGain = 0.0
        leakSamples.clear()
        forcedStop = false
        cancelSpan = false
        target = 0.0
        gain = 0.0
        enter(Phase.CALIBRATING_QUIET, nowMs)
    }

    /** How loud the app's own noise sounds to the microphone at the configured volume, or null if unknown. */
    val leakDb: Double?
        get() = if (leakCalibrated) Levels.powerToDb(leakPowerAtUnitGain * config.volume * config.volume) else null

    private fun trackEvent(nowMs: Long, levelDb: Double, excess: Double, noisy: Boolean): NoiseEvent? {
        if (!noisy) {
            noisySinceMs = null
            return closeEvent(nowMs)
        }
        val since = noisySinceMs ?: nowMs.also { noisySinceMs = it }
        if (eventStartMs == null && nowMs - since + config.windowMs >= config.minEventMs) {
            eventStartMs = since
            startedEventMs = since
            eventPeakExcess = excess
            eventPeakLevel = levelDb
            eventDuringPlayback = phase == Phase.PLAYING
            eventStartedPlayback = false
            noiseBefore[since] = lastNoiseMs
            onNoise(nowMs, since, excess)
        } else if (eventStartMs != null) {
            eventPeakExcess = max(eventPeakExcess, excess)
            eventPeakLevel = max(eventPeakLevel, levelDb)
            onNoise(nowMs, eventStartMs!!, eventPeakExcess)
        }
        return null
    }

    private fun onNoise(nowMs: Long, eventMs: Long, excess: Double) {
        if (forcedStop) return
        // Already known to come from the room: it does not keep the masking on.
        if (kinds[eventMs]?.neighbors == false) return
        val endAt = config.endAtMs
        val tooLate = endAt != null && nowMs >= endAt - config.quietBeforeEndMs
        if (phase == Phase.LISTENING) {
            if (tooLate) return
            startSpan(nowMs, PlaybackSpan.Reason.NOISE)
            spanTriggerMs = eventMs
            eventStartedPlayback = true
        } else if (spanTriggerMs != eventMs) {
            // A neighbor's noise after all: keep playing even if the first one was a room sound.
            spanOtherNoise = true
            cancelSpan = false
        }
        lastNoiseMs = nowMs
        lastNoiseEventMs = eventMs
        noiseGain = max(noiseGain, gainFor(excess))
        target = max(target, noiseGain)
    }

    /** Masking gain for a noise [excess] dB above the expected level. */
    private fun gainFor(excess: Double): Double {
        if (!config.adaptiveVolume) return config.volume
        val louder = ((excess - config.thresholdDb) / ADAPTIVE_RANGE_DB).coerceIn(0.0, 1.0)
        return config.volume * (0.5 + 0.5 * louder)
    }

    private fun noiseActive(nowMs: Long) = nowMs - lastNoiseMs < config.holdMs

    private fun inPreventive(nowMs: Long): Boolean {
        val endAt = config.endAtMs
        if (endAt != null && nowMs >= endAt - config.quietBeforeEndMs) return false
        return config.preventive.any { nowMs in it }
    }

    private fun startSpan(nowMs: Long, reason: PlaybackSpan.Reason) {
        enter(Phase.PLAYING, nowMs)
        spanStartMs = nowMs
        spanReason = reason
        spanTriggerMs = null
        spanOtherNoise = false
        cancelSpan = false
        noiseGain = 0.0
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
        if (phase == Phase.LISTENING) {
            if (!forcedStop && gain == 0.0 && inPreventive(nowMs)) {
                startSpan(nowMs, PlaybackSpan.Reason.PREVENTIVE)
                target = config.volume * config.preventiveVolume
            }
            return null
        }
        if (phase != Phase.PLAYING) return null

        val start = spanStartMs ?: nowMs
        if (!forcedStop && nowMs - start >= config.maxPlaybackMs) forcedStop = true
        target = when {
            forcedStop || cancelSpan -> 0.0
            noiseActive(nowMs) && noiseGain > 0 -> noiseGain
            inPreventive(nowMs) -> config.volume * config.preventiveVolume
            else -> 0.0
        }
        if (!noiseActive(nowMs)) noiseGain = 0.0

        if (target == 0.0 && gain == 0.0) {
            val span = if (cancelSpan) dropSpan() else closeSpan(nowMs)
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

    /** Forgets a playback started by a room sound; its noise no longer counts as starting it. */
    private fun dropSpan(): PlaybackSpan? {
        val trigger = spanTriggerMs
        spanStartMs = null
        cancelSpan = false
        val i = events.indexOfFirst { it.startMs == trigger }
        if (i >= 0) events[i] = events[i].copy(startedPlayback = false)
        if (eventStartMs == trigger) eventStartedPlayback = false
        return null
    }

    private fun closeEvent(nowMs: Long): NoiseEvent? {
        val start = eventStartMs ?: return null
        eventStartMs = null
        noisySinceMs = null
        return NoiseEvent(start, nowMs, eventPeakExcess, eventPeakLevel, eventStartedPlayback, eventDuringPlayback, kinds[start])
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
