package ruido.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ruido.core.Levels
import ruido.core.NightEngine

/** What the listening service is doing right now, for the screens. */
data class LiveState(
    val running: Boolean = false,
    /** True for the test mode in settings: nothing is saved and there is no end time. */
    val test: Boolean = false,
    val phase: NightEngine.Phase? = null,
    val levelDb: Double = Levels.FLOOR_DB,
    val baselineDb: Double = Levels.FLOOR_DB,
    val expectedDb: Double = Levels.FLOOR_DB,
    val thresholdDb: Double = 0.0,
    val leakDb: Double? = null,
    val gain: Double = 0.0,
    val noisy: Boolean = false,
    val events: Int = 0,
    val playbackStarts: Int = 0,
    val startMs: Long = 0,
    val endAtMs: Long? = null,
    val audioSource: String = "",
    val error: String? = null,
) {
    /** Level a sound must reach to count as a noise right now. */
    val triggerDb: Double get() = expectedDb + thresholdDb
    val night: Boolean get() = running && !test
}

object Live {
    private val mutable = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = mutable

    /** Atomic, so the audio thread and the screen can both update it. */
    fun update(transform: (LiveState) -> LiveState) {
        mutable.update(transform)
    }
}
