package ruido.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Night-by-night numbers for the trends screen. */
object Trends {
    data class Week(val mondayEpochDay: Long, val nights: Int, val noisesPerNight: Double, val wokeUp: Int, val answered: Int)

    /** Average noises per night for each night of the week (Monday night first); null when no nights. */
    fun byNight(records: List<NightRecord>, zone: ZoneId): List<Double?> {
        val sums = DoubleArray(7)
        val counts = IntArray(7)
        for (r in records) {
            val n = nightOfWeek(r.startMs, zone)
            sums[n] += r.events.size.toDouble()
            counts[n]++
        }
        return (0 until 7).map { if (counts[it] == 0) null else sums[it] / counts[it] }
    }

    /**
     * Night of the week of [ms]: 0 for Monday night to 6 for Sunday night. A night belongs
     * to the evening it starts on, so Saturday 01:30 is part of Friday night.
     */
    fun nightOfWeek(ms: Long, zone: ZoneId): Int = evening(ms, zone).dayOfWeek.value - 1

    private fun evening(ms: Long, zone: ZoneId): LocalDate {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        return if (t.hour < 12) t.toLocalDate().minusDays(1) else t.toLocalDate()
    }

    /** One entry per week (Monday to Sunday nights), oldest first, for the last [weeks] weeks with data. */
    fun weekly(records: List<NightRecord>, zone: ZoneId, weeks: Int = 8): List<Week> =
        records.groupBy { r ->
            val e = evening(r.startMs, zone)
            e.minusDays((e.dayOfWeek.value - 1).toLong()).toEpochDay()
        }.map { (monday, list) ->
            Week(
                mondayEpochDay = monday,
                nights = list.size,
                noisesPerNight = list.sumOf { r -> r.events.size }.toDouble() / list.size,
                wokeUp = list.count { it.wokeUp == true },
                answered = list.count { it.wokeUp != null },
            )
        }.sortedBy { it.mondayEpochDay }.takeLast(weeks)
}
