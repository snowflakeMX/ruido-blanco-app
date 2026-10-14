package ruido.core

/**
 * Masking sounds. All but [OWN] are generated on the fly, so no audio files are needed.
 * [OWN] is a file the user picked; the generator plays pink noise in its place.
 */
enum class NoiseColor { WHITE, PINK, BROWN, RAIN, FAN, WAVES, OWN }

/**
 * Generates continuous noise of one kind. Each one is scaled to a similar loudness
 * (about -16 dBFS RMS at gain 1), so switching keeps the volume.
 */
class NoiseGenerator(var color: NoiseColor, seed: Long = 0x5EED, private val sampleRate: Int = 48_000) {
    private var state = if (seed == 0L) 1L else seed
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
    private var brown = 0.0

    // Rain: hiss plus random drops.
    private var hissLow = 0.0
    private var drop = 0.0
    private var dropDecay = 0.0
    private var dropLow = 0.0

    // Fan: low, steady rumble with a faint blade flutter.
    private var fanLow = 0.0
    private var fanLow2 = 0.0
    private var fanPhase = 0.0

    // Waves: pink noise that swells and recedes every 7 to 12 seconds.
    private var wavePhase = 0.0
    private var wavePeriod = 9.0
    private var waveLow = 0.0

    /** Fills [out] ramping the gain linearly from [gainFrom] to [gainTo] to avoid clicks. */
    fun fill(out: FloatArray, gainFrom: Double, gainTo: Double, count: Int = out.size) {
        if (count == 0) return
        val step = (gainTo - gainFrom) / count
        var g = gainFrom
        for (i in 0 until count) {
            g += step
            out[i] = (next() * g).coerceIn(-1.0, 1.0).toFloat()
        }
    }

    /** One sample at gain 1. */
    fun next(): Double {
        val w = white()
        return when (color) {
            NoiseColor.WHITE -> w * 0.27
            NoiseColor.PINK -> pink(w) * 0.088
            NoiseColor.BROWN -> brown(w) * 2.7
            NoiseColor.RAIN -> rain(w) * RAIN_SCALE
            NoiseColor.FAN -> fan(w) * FAN_SCALE
            NoiseColor.WAVES -> waves(w) * WAVES_SCALE
            NoiseColor.OWN -> pink(w) * 0.088
        }
    }

    private fun rain(w: Double): Double {
        // Hiss: white minus its low part, so it sounds like distant rain, not static.
        hissLow += 0.08 * (w - hissLow)
        val hiss = w - hissLow
        // Drops: about 60 per second, each a short burst that fades in ~8 ms.
        if (uniform() < 60.0 / sampleRate) {
            drop = 0.4 + 0.6 * uniform()
            dropDecay = kotlin.math.exp(-1.0 / (0.008 * sampleRate))
        }
        drop *= dropDecay
        dropLow += 0.3 * (white() * drop - dropLow)
        return hiss * 0.5 + dropLow * 1.6 + pink(w) * 0.05
    }

    private fun fan(w: Double): Double {
        // Two low-pass stages over pink noise: a soft rumble around a few hundred hertz.
        val p = pink(w)
        fanLow += 0.06 * (p - fanLow)
        fanLow2 += 0.06 * (fanLow - fanLow2)
        fanPhase += 2 * Math.PI * 22.0 / sampleRate
        if (fanPhase > 2 * Math.PI) fanPhase -= 2 * Math.PI
        return fanLow2 * (1.0 + 0.06 * kotlin.math.sin(fanPhase))
    }

    private fun waves(w: Double): Double {
        wavePhase += 1.0 / (wavePeriod * sampleRate)
        if (wavePhase >= 1.0) {
            wavePhase -= 1.0
            wavePeriod = 7.0 + 5.0 * uniform()
        }
        // Rises quickly, falls slowly; never fully silent so it keeps masking.
        val x = wavePhase
        val swell = if (x < 0.3) kotlin.math.sin(x / 0.3 * Math.PI / 2) else kotlin.math.cos((x - 0.3) / 0.7 * Math.PI / 2)
        val level = 0.55 + 0.45 * swell * swell
        // Brighter at the crest, darker in the trough.
        waveLow += (0.05 + 0.25 * swell * swell) * (pink(w) - waveLow)
        return waveLow * level
    }

    private fun uniform(): Double = (white() + 1.0) / 2.0

    /** Uniform in [-1, 1) from xorshift64. */
    private fun white(): Double {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return (x ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0
    }

    private companion object {
        const val RAIN_SCALE = 0.466
        const val FAN_SCALE = 0.125
        const val WAVES_SCALE = 0.13
    }

    /** Paul Kellet's refined pink filter. */
    private fun pink(w: Double): Double {
        b0 = 0.99886 * b0 + w * 0.0555179
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522
        b5 = -0.7616 * b5 - w * 0.0168980
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
        b6 = w * 0.115926
        return out
    }

    /** Leaky integrator, so it does not drift away from zero. */
    private fun brown(w: Double): Double {
        brown = (brown + 0.02 * w) / 1.02
        return brown
    }
}
