package ruido.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Plays the configured noise for a few seconds so the volume and color can be judged. */
object SoundPreview {
    private val mutable = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = mutable

    private const val SECONDS = 6
    private var thread: Thread? = null

    fun start(context: Context) {
        if (mutable.value || Live.state.value.running) return
        val app = context.applicationContext
        mutable.value = true
        thread = Thread({
            val settings = SettingsRepo.current
            val volume = MediaVolume(app)
            val player = NoisePlayer(settings.color, OwnSound.file(app)) { }
            try {
                volume.set(settings.mediaVolumePercent)
                player.start()
                val steps = SECONDS * 20
                for (i in 0..steps) {
                    // Settings may change while it plays: follow them.
                    val s = SettingsRepo.current
                    player.color = s.color
                    volume.set(s.mediaVolumePercent)
                    val v = s.volumePercent / 100.0
                    val ramp = minOf(i, steps - i) / 20.0 // 1 s fade at each end
                    player.gain = v * ramp.coerceIn(0.0, 1.0)
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) {
            } finally {
                player.gain = 0.0
                Thread.sleep(100)
                player.stop()
                volume.restore()
                mutable.value = false
            }
        }, "ruido-preview").apply { start() }
    }

    fun stop() {
        thread?.interrupt()
    }
}
