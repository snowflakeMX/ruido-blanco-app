package ruido.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatternsTest {
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")
    private val firstMonday = LocalDate.of(2026, 9, 7)

    private fun ms(date: LocalDate, hour: Int, minute: Int = 0) =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    /** A night from 23:00 to 07:00 starting on [evening], with noises at the given next-morning times. */
    private fun night(evening: LocalDate, vararg noises: Pair<Int, Int>) = Observation(
        ms(evening, 23), ms(evening.plusDays(1), 7),
        noises.map { (h, m) -> if (h >= 12) ms(evening, h, m) else ms(evening.plusDays(1), h, m) },
    )

    @Test
    fun saturdayOneAmBelongsToFridayNight() {
        val s = NightSlot.of(ms(LocalDate.of(2026, 10, 17), 1, 30), zone) // Saturday 01:30
        assertEquals(4, s.night)
        assertEquals(54, s.slot) // 13.5 h after noon
    }

    @Test
    fun nothingIsRiskyBeforeSevenNights() {
        val obs = (0 until 6).map { night(firstMonday.plusDays(it.toLong()), 1 to 0) }
        val p = Patterns(obs, ms(firstMonday.plusDays(7), 22), zone)
        assertFalse(p.ready)
        assertTrue(p.riskWindows(ms(firstMonday.plusDays(7), 22), ms(firstMonday.plusDays(8), 7), 0).isEmpty())
    }

    @Test
    fun repeatedFridayPartyBecomesRiskyOnlyOnWeekendNights() {
        // Four weeks: every Friday night music at 01:00-02:00, other nights quiet.
        val obs = (0 until 28).map { d ->
            val evening = firstMonday.plusDays(d.toLong())
            if (evening.dayOfWeek == java.time.DayOfWeek.FRIDAY) night(evening, 1 to 0, 1 to 20, 1 to 45) else night(evening)
        }
        val now = ms(firstMonday.plusDays(28), 20)
        val p = Patterns(obs, now, zone)
        assertTrue(p.ready)
        val friday = 4
        val oneAm = NightSlot.of(ms(LocalDate.of(2026, 10, 10), 1, 0), zone).slot
        assertTrue(p.risky(friday, oneAm), "p=${p.probability(friday, oneAm)}")
        assertFalse(p.risky(friday, oneAm - 12), "three hours earlier is calm")
        assertFalse(p.risky(1, oneAm), "Tuesday is calm")

        // Next Friday night: one window, starting 10 minutes early.
        val nextFriday = firstMonday.plusDays(32)
        val windows = p.riskWindows(ms(nextFriday, 22), ms(nextFriday.plusDays(1), 7), 10 * 60_000L)
        assertEquals(1, windows.size)
        assertTrue(windows[0].first <= ms(nextFriday.plusDays(1), 0, 50), "starts early: ${windows[0]}")
        assertTrue(windows[0].last >= ms(nextFriday.plusDays(1), 1, 44))
    }

    @Test
    fun oneLoudNightIsNotAPattern() {
        val obs = (0 until 14).map { d ->
            val evening = firstMonday.plusDays(d.toLong())
            if (d == 2) night(evening, 2 to 0) else night(evening)
        }
        val p = Patterns(obs, ms(firstMonday.plusDays(14), 20), zone)
        val slot = NightSlot.of(ms(firstMonday.plusDays(3), 2, 0), zone).slot
        assertFalse(p.risky(2, slot))
    }

    @Test
    fun manualSlotCrossesMidnightAndRoundTrips() {
        val slot = ManualSlot(setOf(4), 23 * 60, 3 * 60) // Friday night 23:00 to 03:00
        assertEquals(slot, ManualSlot.decode(slot.encode()))
        val friday = LocalDate.of(2026, 10, 16)
        val w = slot.windows(ms(friday, 20), ms(friday.plusDays(1), 8), zone)
        assertEquals(listOf(ms(friday, 23) until ms(friday.plusDays(1), 3)), w)
        assertTrue(slot.windows(ms(friday.minusDays(1), 20), ms(friday, 8), zone).isEmpty(), "not on Thursday night")
    }

    @Test
    fun mergeJoinsOverlaps() {
        assertEquals(listOf(0L..20L, 30L..40L), Patterns.merge(listOf(10L..20L, 0L..12L, 30L..40L)))
    }

    @Test
    fun trendsCountOnlyNeighborNoises() {
        val friday = LocalDate.of(2026, 10, 16)
        val r = NightRecord(
            ms(friday, 23), ms(friday.plusDays(1), 7),
            listOf(
                NoiseEvent(ms(friday.plusDays(1), 1), ms(friday.plusDays(1), 1) + 500, 15.0, -40.0, true, false),
                NoiseEvent(ms(friday.plusDays(1), 2), ms(friday.plusDays(1), 2) + 500, 15.0, -40.0, false, false, SoundKind.ROOM),
            ),
            emptyList(), wokeUp = true,
        )
        assertEquals(1.0, Trends.byNight(listOf(r), zone)[4])
        val week = Trends.weekly(listOf(r), zone).single()
        assertEquals(1, week.wokeUp)
        assertEquals(1.0, week.noisesPerNight)
    }
}
