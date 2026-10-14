package ruido.app

import android.content.Context
import org.tensorflow.lite.Interpreter
import ruido.core.SoundKind
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.ZipInputStream

/**
 * Tells what a noise was with YAMNet, a small sound recognition model that runs on the
 * phone (nothing leaves it). It only hears about one second of audio right after a
 * noise starts, and that audio is discarded after classifying.
 */
class SoundClassifier private constructor(private val interpreter: Interpreter, private val labels: List<String>) {

    private val output = arrayOf(FloatArray(labels.size))

    /** [samples] are 16 kHz mono PCM, exactly [SAMPLES] of them. */
    fun classify(samples: ShortArray): SoundKind {
        val input = FloatArray(SAMPLES) { i -> samples.getOrElse(i) { 0 } / 32768f }
        interpreter.run(input, output)
        return SoundKind.classify(labels, output[0])
    }

    fun close() = interpreter.close()

    companion object {
        /** 0.975 s at 16 kHz: the model's input. */
        const val SAMPLES = 15_600
        private const val MODEL = "yamnet.tflite"

        fun load(context: Context): SoundClassifier {
            val model = map(context)
            val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
            return SoundClassifier(interpreter, labels(context))
        }

        private fun map(context: Context): MappedByteBuffer {
            context.assets.openFd(MODEL).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }

        /** The label list is stored inside the model file (TFLite metadata is a zip appended to it). */
        private fun labels(context: Context): List<String> {
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            ZipInputStream(ByteArrayInputStream(bytes, zipStart(bytes), bytes.size - zipStart(bytes))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".txt")) return zip.readBytes().decodeToString().lines().filter { it.isNotBlank() }
                }
            }
            error("labels missing")
        }

        /** Offset of the first zip local header ("PK\u0003\u0004"). */
        private fun zipStart(bytes: ByteArray): Int {
            for (i in 0 until bytes.size - 4) {
                if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()) return i
            }
            error("no zip")
        }
    }
}
