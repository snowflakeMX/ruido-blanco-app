package ruido.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Conversions between PCM samples, power and dBFS. */
object Levels {
    /** Floor used instead of -infinity for digital silence. */
    const val FLOOR_DB = -96.0

    /** RMS level in dBFS of the first [count] 16-bit samples. */
    fun rmsDbfs(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return FLOOR_DB
        var sum = 0.0
        for (i in 0 until count) {
            val x = samples[i] / 32768.0
            sum += x * x
        }
        return powerToDb(sum / count)
    }

    /** RMS level in dBFS of float samples in [-1, 1]. */
    fun rmsDbfs(samples: FloatArray, count: Int = samples.size): Double {
        if (count <= 0) return FLOOR_DB
        var sum = 0.0
        for (i in 0 until count) {
            val x = samples[i].toDouble()
            sum += x * x
        }
        return powerToDb(sum / count)
    }

    fun powerToDb(power: Double): Double =
        if (power <= 0.0) FLOOR_DB else max(FLOOR_DB, 10.0 * log10(power))

    fun dbToPower(db: Double): Double = 10.0.pow(db / 10.0)

    /** Level of the sum of two uncorrelated signals. */
    fun addDb(a: Double, b: Double): Double = powerToDb(dbToPower(a) + dbToPower(b))

    fun amplitudeToDb(amplitude: Double): Double =
        if (amplitude <= 0.0) FLOOR_DB else max(FLOOR_DB, 20.0 * log10(amplitude))

    fun rms(power: Double): Double = sqrt(power)
}
