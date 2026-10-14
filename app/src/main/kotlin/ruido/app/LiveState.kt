package ruido.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ruido.core.Levels
import ruido.core.NightEngine

/** What the night service is doing right now, for the screens. */
data class LiveState(
    val running: Boolean = false,
    val phase: NightEngine.Phase? = null,
    val levelDb: Double = Levels.FLOOR_DB,
    val baselineDb: Double = Levels.FLOOR_DB,
    val expectedDb: Double = Levels.FLOOR_DB,
    val leakDb: Double? = null,
    val gain: Double = 0.0,
    val noisy: Boolean = false,
    val events: Int = 0,
    val playbackStarts: Int = 0,
    val startMs: Long = 0,
    val endAtMs: Long? = null,
    val audioSource: String = "",
    val error: String? = null,
)

object Live {
    private val mutable = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = mutable

    fun update(transform: (LiveState) -> LiveState) {
        mutable.value = transform(mutable.value)
    }
}
