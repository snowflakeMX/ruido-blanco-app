package ruido.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SettingsTest {
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")
    private fun ms(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun wakeTimeIsTheNextOccurrence() {
        val s = AppSettings(wakeHour = 7, wakeMinute = 0)
        assertEquals(ms("2026-10-15T07:00"), s.wakeAtMs(ms("2026-10-14T23:30"), zone))
        assertEquals(ms("2026-10-15T07:00"), s.wakeAtMs(ms("2026-10-15T01:00"), zone))
        assertEquals(ms("2026-10-16T07:00"), s.wakeAtMs(ms("2026-10-15T07:00"), zone))
    }

    @Test
    fun valuesAreKeptInRange() {
        val s = AppSettings(thresholdDb = 1, volumePercent = 500, screenOnSeconds = -3).clamped()
        assertEquals(4, s.thresholdDb)
        assertEquals(100, s.volumePercent)
        assertEquals(0, s.screenOnSeconds)
    }

    @Test
    fun configUsesTheSettings() {
        val c = AppSettings(thresholdDb = 9, holdMinutes = 20, volumePercent = 30).toNightConfig(null)
        assertEquals(9.0, c.thresholdDb, 0.0)
        assertEquals(20 * 60_000L, c.holdMs)
        assertEquals(0.3, c.volume, 1e-9)
        assertEquals(null, c.endAtMs)
    }
}
