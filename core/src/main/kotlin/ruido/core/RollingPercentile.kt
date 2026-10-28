package ruido.core

import kotlin.math.roundToInt

/**
 * Percentile of the last [capacity] values, quantized to [binDb] steps between
 * [Levels.FLOOR_DB] and 0 dBFS. Constant memory and cheap updates, so it can run
 * on every 100 ms window all night.
 */
class RollingPercentile(
    private val capacity: Int,
    private val binDb: Double = 0.5,
) {
    private val binCount = ((-Levels.FLOOR_DB) / binDb).roundToInt() + 1
    private val histogram = IntArray(binCount)
    private val ring = IntArray(capacity)
    private var next = 0

    var size = 0
        private set

    fun add(db: Double) {
        val bin = toBin(db)
        if (size == capacity) {
            histogram[ring[next]]--
        } else {
            size++
        }
        ring[next] = bin
        histogram[bin]++
        next = (next + 1) % capacity
    }

    /** Value at fraction [p] in 0..1, or null while empty. */
    fun percentile(p: Double): Double? {
        if (size == 0) return null
        val target = (p.coerceIn(0.0, 1.0) * (size - 1)).roundToInt()
        var seen = 0
        for (bin in 0 until binCount) {
            seen += histogram[bin]
            if (seen > target) return fromBin(bin)
        }
        return fromBin(binCount - 1)
    }

    fun clear() {
        histogram.fill(0)
        next = 0
        size = 0
    }

    private fun toBin(db: Double): Int =
        ((db.coerceIn(Levels.FLOOR_DB, 0.0) - Levels.FLOOR_DB) / binDb).roundToInt()
            .coerceIn(0, binCount - 1)

    private fun fromBin(bin: Int): Double = Levels.FLOOR_DB + bin * binDb
}
