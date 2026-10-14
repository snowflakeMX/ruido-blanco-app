package ruido.app

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** Sets the phone's media volume for a while and puts the previous one back. */
class MediaVolume(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var saved: Int? = null

    fun set(percent: Int) {
        if (saved == null) saved = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (max * percent / 100.0).roundToInt().coerceIn(1, max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }

    fun restore() {
        saved?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) } }
        saved = null
    }
}
