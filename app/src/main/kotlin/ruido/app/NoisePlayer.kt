package ruido.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import ruido.core.NoiseColor
import ruido.core.NoiseGenerator
import java.io.File

/**
 * Plays generated noise on its own thread. The gain comes from [gain] (0..1) and is
 * ramped per sample, so changes never click. While the gain is 0 the speaker is paused.
 */
class NoisePlayer(
    color: NoiseColor,
    /** The user's own sound, used when [color] is [NoiseColor.OWN]. */
    private val ownFile: File? = null,
    private val onError: (String) -> Unit,
) {
    @Volatile var gain = 0.0
    @Volatile var color: NoiseColor = color

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "ruido-playback").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(2000)
        thread = null
    }

    private fun loop() {
        val generator = NoiseGenerator(color, seed = System.nanoTime())
        val own = ownFile?.takeIf { it.exists() }?.let { f -> runCatching { OwnSound.Reader(f) }.getOrNull() }
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            onError("No se pudo abrir el altavoz: ${e.message}")
            return
        }
        val chunk = FloatArray(CHUNK)
        var current = 0.0
        var playing = false
        try {
            while (running) {
                val next = gain
                if (next == 0.0 && current == 0.0) {
                    if (playing) {
                        track.pause(); track.flush(); playing = false
                    }
                    Thread.sleep(50)
                    continue
                }
                if (!playing) {
                    track.play(); playing = true
                }
                val color = color
                if (color == NoiseColor.OWN && own != null) {
                    val step = (next - current) / chunk.size
                    var g = current
                    for (i in chunk.indices) {
                        g += step
                        chunk[i] = (own.next() * g).coerceIn(-1.0, 1.0).toFloat()
                    }
                } else {
                    generator.color = color
                    generator.fill(chunk, current, next)
                }
                current = next
                track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "playback", e)
            onError("El sonido se detuvo: ${e.message}")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        const val TAG = "NoisePlayer"
        const val SAMPLE_RATE = 48_000
        /** 20 ms per write. */
        const val CHUNK = 960
    }
}
