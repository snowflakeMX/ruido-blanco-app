package ruido.core

/** Tunable parameters of one night. Times in milliseconds. */
data class NightConfig(
    /** How many dB above the expected level counts as a noise. */
    val thresholdDb: Double = 12.0,
    /** Masking keeps playing this long after the last noise. */
    val holdMs: Long = 15 * MINUTE,
    val fadeInMs: Long = 5 * SECOND,
    val fadeOutMs: Long = 20 * SECOND,
    /** Playback gain, 0..1. */
    val volume: Double = 0.5,
    /** Longest continuous playback before re-learning the background. */
    val maxPlaybackMs: Long = 60 * MINUTE,
    /** Absolute end of the night (wake-up time), or null for no end. */
    val endAtMs: Long? = null,
    /** No new playback starts this close to [endAtMs]. */
    val quietBeforeEndMs: Long = 15 * MINUTE,
    val windowMs: Long = 100,
    /** Windows averaged before comparing with the threshold (4 x 100 ms = 400 ms). */
    val smoothingWindows: Int = 4,
    /** A noise must stay above the threshold this long to count. */
    val minEventMs: Long = 200,
    val baselineWindowMs: Long = 5 * MINUTE,
    val baselinePercentile: Double = 0.2,
    val calibrateQuietMs: Long = 10 * SECOND,
    /** Plays the noise briefly at start to learn how much the microphone hears it. 0 disables. */
    val calibrateLeakMs: Long = 3 * SECOND,
    /** Constant noise at the start of the night, before listening begins. 0 = none. */
    val constantMs: Long = 0,
    /** Louder noises get louder masking: from half of [volume] up to [volume]. */
    val adaptiveVolume: Boolean = false,
) {
    companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
    }
}
