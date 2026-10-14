package ruido.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.pow

/**
 * Where a moment falls in the week, seen as nights: a night belongs to the evening it
 * starts on, so Saturday 01:30 is part of Friday night. [night] is 0 for Monday night
 * to 6 for Sunday night; [slot] counts 15 minutes from noon (0 = 12:00, 48 = 00:00).
 */
data class NightSlot(val night: Int, val slot: Int) {
    val index: Int get() = night * Patterns.SLOTS_PER_DAY + slot

    companion object {
        fun of(ms: Long, zone: ZoneId): NightSlot {
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
            val evening = if (t.hour < 12) t.toLocalDate().minusDays(1) else t.toLocalDate()
            val minutesFromNoon = ((t.hour + 12) % 24) * 60 + t.minute
            return NightSlot(evening.dayOfWeek.value - 1, minutesFromNoon / Patterns.SLOT_MINUTES)
        }

        /** Local time at which a slot starts. */
        fun timeOf(slot: Int): LocalTime = LocalTime.NOON.plusMinutes(slot.toLong() * Patterns.SLOT_MINUTES)
    }
}

/** One stored night as the pattern model sees it: when it listened and when neighbors were heard. */
data class Observation(val startMs: Long, val endMs: Long, val noisesMs: List<Long>) {
    companion object {
        /** Uses only noises that teach something about the neighbors. */
        fun of(record: NightRecord, endMs: Long?): Observation? {
            val end = endMs ?: record.endMs ?: return null
            if (end <= record.startMs) return null
            return Observation(record.startMs, end, record.events.filter { it.fromNeighbors }.map { it.startMs })
        }
    }
}

/**
 * Fixed time window the user knows is noisy, such as "Friday night from 01:00 to 04:00".
 * [nights] uses [NightSlot.night] numbering. Times before noon belong to the next morning.
 */
data class ManualSlot(val nights: Set<Int>, val startMinute: Int, val endMinute: Int) {
    fun encode(): String = "${nights.sorted().joinToString("")}-$startMinute-$endMinute"

    /** Absolute windows overlapping [fromMs]..[toMs]. */
    fun windows(fromMs: Long, toMs: Long, zone: ZoneId): List<LongRange> {
        val out = ArrayList<LongRange>()
        val first = ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromMs), zone).toLocalDate().minusDays(1)
        val last = ZonedDateTime.ofInstant(Instant.ofEpochMilli(toMs), zone).toLocalDate()
        var evening = first
        while (!evening.isAfter(last)) {
            if ((evening.dayOfWeek.value - 1) in nights) {
                val start = at(evening, startMinute, zone)
                val end = at(evening, endMinute, zone)
                if (end > start && end > fromMs && start < toMs) out += start until end
            }
            evening = evening.plusDays(1)
        }
        return out
    }

    private fun at(evening: LocalDate, minute: Int, zone: ZoneId): Long {
        val day = if (minute < 12 * 60) evening.plusDays(1) else evening
        return day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
    }

    companion object {
        fun decode(text: String): ManualSlot? = runCatching {
            val (n, s, e) = text.split('-')
            val nights = n.map { it.digitToInt() }.filter { it in 0..6 }.toSet()
            val start = s.toInt()
            val end = e.toInt()
            require(nights.isNotEmpty() && start in 0 until 24 * 60 && end in 0 until 24 * 60)
            ManualSlot(nights, start, end)
        }.getOrNull()
    }
}

/**
 * Learns at what times of the week the neighbors are usually noisy. The week is split
 * in 15-minute slots per night. Each slot keeps the share of observed nights with a
 * neighbor noise in it, with recent nights weighing more (half-life [halfLifeDays]).
 * Few nights are noisy data, so each slot also borrows from its neighbors (±30 min)
 * and from the same time on nights of the same group (weekday or weekend).
 */
class Patterns(
    observations: List<Observation>,
    nowMs: Long,
    private val zone: ZoneId,
    halfLifeDays: Double = 21.0,
    /** A slot is risky above this probability... */
    val threshold: Double = 0.4,
    /** ...and when noise was heard around it on at least this many nights. */
    private val minNights: Int = 2,
) {
    /** Nights long enough to count. */
    val nights: Int

    /** Prevention starts only after this many nights, so a single loud night does not decide it. */
    val ready: Boolean get() = nights >= MIN_NIGHTS

    private val seen = DoubleArray(SLOTS)
    private val heard = DoubleArray(SLOTS)
    /** For each slot, which nights (by position) had a noise in it. */
    private val heardNights = Array(SLOTS) { HashSet<Int>() }
    private val probability = DoubleArray(SLOTS)

    init {
        var counted = 0
        observations.forEachIndexed { n, o ->
            if (o.endMs - o.startMs < MIN_NIGHT_MS) return@forEachIndexed
            counted++
            val ageDays = (nowMs - o.startMs).coerceAtLeast(0) / DAY_MS.toDouble()
            val weight = 0.5.pow(ageDays / halfLifeDays)
            val slots = HashSet<Int>()
            var t = o.startMs - o.startMs % SLOT_MS
            while (t < o.endMs) {
                slots += NightSlot.of(t, zone).index
                t += SLOT_MS
            }
            for (i in slots) seen[i] += weight
            val noisy = o.noisesMs.filter { it in o.startMs..o.endMs }.map { NightSlot.of(it, zone).index }.toSet()
            for (i in noisy) {
                heard[i] += weight
                heardNights[i] += n
            }
        }
        nights = counted
        for (night in 0 until 7) for (slot in 0 until SLOTS_PER_DAY) {
            probability[night * SLOTS_PER_DAY + slot] = smoothed(night, slot)
        }
    }

    private fun smoothed(night: Int, slot: Int): Double {
        var num = 0.0
        var den = 0.0
        val weekend = isWeekend(night)
        for (other in 0 until 7) {
            val groupWeight = when {
                other == night -> 1.0
                isWeekend(other) == weekend -> GROUP_WEIGHT
                else -> continue
            }
            for (offset in -NEIGHBOR_SLOTS..NEIGHBOR_SLOTS) {
                val s = slot + offset
                if (s !in 0 until SLOTS_PER_DAY) continue
                val k = groupWeight * NEIGHBOR_WEIGHTS[kotlin.math.abs(offset)]
                val i = other * SLOTS_PER_DAY + s
                num += k * heard[i]
                den += k * seen[i]
            }
        }
        // A slot never observed borrows nothing: unknown means no risk.
        return if (den < 1e-9) 0.0 else num / den
    }

    /** Probability (0..1) of hearing the neighbors in this slot. */
    fun probability(night: Int, slot: Int): Double = probability[night * SLOTS_PER_DAY + slot]

    /** Nights that had a noise within ±30 min of this slot. */
    fun nightsHeard(night: Int, slot: Int): Int {
        val set = HashSet<Int>()
        for (offset in -NEIGHBOR_SLOTS..NEIGHBOR_SLOTS) {
            val s = slot + offset
            if (s in 0 until SLOTS_PER_DAY) set += heardNights[night * SLOTS_PER_DAY + s]
        }
        return set.size
    }

    fun risky(night: Int, slot: Int): Boolean =
        ready && probability(night, slot) >= threshold && nightsHeard(night, slot) >= minNights

    /**
     * Windows between [fromMs] and [toMs] when masking should already be playing: each
     * risky slot, starting [leadMs] early. Overlapping windows are merged.
     */
    fun riskWindows(fromMs: Long, toMs: Long, leadMs: Long): List<LongRange> {
        if (!ready) return emptyList()
        val out = ArrayList<LongRange>()
        var t = fromMs - fromMs % SLOT_MS
        while (t < toMs + leadMs) {
            val s = NightSlot.of(t, zone)
            if (risky(s.night, s.slot)) out += (t - leadMs) until (t + SLOT_MS)
            t += SLOT_MS
        }
        return merge(out).mapNotNull { r ->
            val from = maxOf(r.first, fromMs)
            val to = minOf(r.last + 1, toMs)
            if (to > from) from until to else null
        }
    }

    companion object {
        const val SLOT_MINUTES = 15
        const val SLOTS_PER_DAY = 24 * 60 / SLOT_MINUTES
        const val SLOTS = 7 * SLOTS_PER_DAY
        const val MIN_NIGHTS = 7
        private const val SLOT_MS = SLOT_MINUTES * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
        /** Shorter sessions (a test, a nap) do not count as a night. */
        private const val MIN_NIGHT_MS = 2 * 60 * 60_000L
        private const val NEIGHBOR_SLOTS = 2
        private val NEIGHBOR_WEIGHTS = doubleArrayOf(1.0, 0.6, 0.3)
        private const val GROUP_WEIGHT = 0.3

        /** Friday and Saturday nights. */
        fun isWeekend(night: Int) = night == DayOfWeek.FRIDAY.value - 1 || night == DayOfWeek.SATURDAY.value - 1

        /** Sorts and joins overlapping or touching ranges. */
        fun merge(ranges: List<LongRange>): List<LongRange> {
            val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
            val out = ArrayList<LongRange>()
            for (r in sorted) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last + 1) {
                    out[out.size - 1] = last.first..maxOf(last.last, r.last)
                } else {
                    out += r
                }
            }
            return out
        }
    }
}

/** Night-by-night numbers for the trends screen. */
object Trends {
    data class Week(val mondayEpochDay: Long, val nights: Int, val noisesPerNight: Double, val wokeUp: Int, val answered: Int)

    /** Average neighbor noises per night for each night of the week (Monday night first); null when no nights. */
    fun byNight(records: List<NightRecord>, zone: ZoneId): List<Double?> {
        val sums = DoubleArray(7)
        val counts = IntArray(7)
        for (r in records) {
            val n = NightSlot.of(r.startMs, zone).night
            sums[n] += r.events.count { it.fromNeighbors }.toDouble()
            counts[n]++
        }
        return (0 until 7).map { if (counts[it] == 0) null else sums[it] / counts[it] }
    }

    /** One entry per week (Monday to Sunday nights), oldest first, for the last [weeks] weeks with data. */
    fun weekly(records: List<NightRecord>, zone: ZoneId, weeks: Int = 8): List<Week> =
        records.groupBy { r ->
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(r.startMs), zone)
            val evening = if (t.hour < 12) t.toLocalDate().minusDays(1) else t.toLocalDate()
            evening.minusDays((evening.dayOfWeek.value - 1).toLong()).toEpochDay()
        }.map { (monday, list) ->
            Week(
                mondayEpochDay = monday,
                nights = list.size,
                noisesPerNight = list.sumOf { r -> r.events.count { it.fromNeighbors } }.toDouble() / list.size,
                wokeUp = list.count { it.wokeUp == true },
                answered = list.count { it.wokeUp != null },
            )
        }.sortedBy { it.mondayEpochDay }.takeLast(weeks)
}
