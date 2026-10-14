package ruido.core

/** Masking noise colors, generated on the fly so no audio files are needed. */
enum class NoiseColor { WHITE, PINK, BROWN }

/**
 * Generates continuous noise of one color. Each color is scaled to a similar
 * loudness (about -16 dBFS RMS at gain 1), so switching colors keeps the volume.
 */
class NoiseGenerator(var color: NoiseColor, seed: Long = 0x5EED) {
    private var state = if (seed == 0L) 1L else seed
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
    private var brown = 0.0

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
        }
    }

    /** Uniform in [-1, 1) from xorshift64. */
    private fun white(): Double {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return (x ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0
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
