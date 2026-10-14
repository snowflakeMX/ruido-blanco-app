package ruido.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * The user's own sound file. It is converted once to plain 48 kHz mono audio in the
 * app's private storage, at the same loudness as the generated sounds, so it plays in a
 * seamless loop without needing access to the original file afterwards.
 */
object OwnSound {
    const val SAMPLE_RATE = 48_000
    /** Longer files are cut: five minutes loop fine and keep the copy under 30 MB. */
    private const val MAX_SECONDS = 5 * 60
    private const val MIN_SECONDS = 5
    /** Same loudness as the generated sounds (about -16 dBFS RMS). */
    private const val TARGET_RMS = 0.158

    fun file(context: Context) = File(context.filesDir, "propio.pcm")

    fun exists(context: Context) = file(context).length() >= 2L * SAMPLE_RATE * MIN_SECONDS

    /** Decodes [uri] and stores it. Returns an error message, or null when it worked. */
    fun import(context: Context, uri: Uri): String? {
        val mono = try {
            decode(context, uri)
        } catch (e: Exception) {
            return "No se pudo leer el archivo: ${e.message ?: e.javaClass.simpleName}"
        }
        if (mono.size < SAMPLE_RATE * MIN_SECONDS) return "El archivo es muy corto (mínimo $MIN_SECONDS segundos)."
        var sum = 0.0
        for (v in mono) sum += v.toDouble() * v
        val rms = sqrt(sum / mono.size)
        if (rms < 1e-4) return "El archivo está en silencio."
        val scale = TARGET_RMS / rms
        val tmp = File(context.filesDir, "propio.tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            val bytes = ByteArray(mono.size * 2)
            for (i in mono.indices) {
                val s = (mono[i] * scale * 32767).coerceIn(-32767.0, 32767.0).toInt()
                bytes[2 * i] = s.toByte()
                bytes[2 * i + 1] = (s shr 8).toByte()
            }
            raf.write(bytes)
        }
        if (!tmp.renameTo(file(context))) return "No se pudo guardar el sonido."
        return null
    }

    /** Whole file as mono floats at [SAMPLE_RATE]. */
    private fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no tiene audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = FloatList()
            val limit = MAX_SECONDS.toLong() * rate
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && out.size < limit) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                            mixDown(pcm, info.size / 2, channels, out)
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return resample(out.toArray(), rate, SAMPLE_RATE)
        } finally {
            extractor.release()
        }
    }

    private fun mixDown(pcm: ShortBuffer, count: Int, channels: Int, out: FloatList) {
        val c = channels.coerceAtLeast(1)
        var i = 0
        while (i + c <= count) {
            var sum = 0f
            for (ch in 0 until c) sum += pcm.get(i + ch)
            out.add(sum / c / 32768f)
            i += c
        }
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val n = (input.size.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return FloatArray(n) { i ->
            val x = i * ratio
            val j = x.toInt().coerceAtMost(input.size - 1)
            val k = (j + 1).coerceAtMost(input.size - 1)
            val f = (x - j).toFloat()
            input[j] * (1 - f) + input[k] * f
        }
    }

    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    /**
     * Plays the stored sound in a loop. The last second fades into the first one, so the
     * loop point cannot be heard.
     */
    class Reader(file: File) {
        private val samples: ShortBuffer = RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
        private val total = samples.limit()
        private val fade = minOf(SAMPLE_RATE, total / 4)
        private val period = total - fade
        private var pos = 0

        /** Next sample at gain 1. */
        fun next(): Double {
            val v = if (pos < fade) {
                val t = pos.toDouble() / fade
                samples.get(pos) * t + samples.get(period + pos) * (1 - t)
            } else {
                samples.get(pos).toDouble()
            }
            pos = (pos + 1) % period
            return v / 32768.0
        }
    }
}
