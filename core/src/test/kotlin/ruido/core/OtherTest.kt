package ruido.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelsTest {
    @Test
    fun fullScaleSineIsAboutMinus3() {
        val s = ShortArray(1600) { (32767 * kotlin.math.sin(2 * Math.PI * 440 * it / 16000)).toInt().toShort() }
        assertEquals(-3.0, Levels.rmsDbfs(s), 0.1)
    }

    @Test
    fun silenceIsTheFloor() {
        assertEquals(Levels.FLOOR_DB, Levels.rmsDbfs(ShortArray(100)))
    }

    @Test
    fun addingTwoEqualLevelsGives3dB() {
        assertEquals(-57.0, Levels.addDb(-60.0, -60.0), 0.05)
    }
}

class RollingPercentileTest {
    @Test
    fun percentileOfWindow() {
        val p = RollingPercentile(capacity = 10)
        assertNull(p.percentile(0.5))
        for (i in 1..10) p.add(-i * 5.0) // -5 .. -50
        assertEquals(-50.0, p.percentile(0.0))
        assertEquals(-5.0, p.percentile(1.0))
    }

    @Test
    fun oldValuesFallOut() {
        val p = RollingPercentile(capacity = 3)
        repeat(3) { p.add(-80.0) }
        repeat(3) { p.add(-20.0) }
        assertEquals(-20.0, p.percentile(0.0))
    }
}

class NoiseGeneratorTest {
    @Test
    fun colorsHaveSimilarLoudnessAndDoNotClip() {
        for (color in NoiseColor.entries) {
            val g = NoiseGenerator(color)
            val buf = FloatArray(48_000 * 5)
            g.fill(buf, 1.0, 1.0)
            val db = Levels.rmsDbfs(buf)
            val peak = buf.maxOf { abs(it) }
            println("$color: $db dBFS, peak $peak")
            assertTrue(db in -19.0..-13.0, "$color level $db")
            assertTrue(peak < 1.0f, "$color clips")
        }
    }

    @Test
    fun gainRampsWithoutJumps() {
        val g = NoiseGenerator(NoiseColor.WHITE)
        val buf = FloatArray(1000)
        g.fill(buf, 0.0, 0.0)
        assertTrue(buf.all { it == 0f })
    }
}

class NightRecordTest {
    @Test
    fun csvRoundTrip() {
        val r = NightRecord(
            startMs = 1000, endMs = 9000,
            events = listOf(NoiseEvent(2000, 2500, 18.4, -41.2, true, false)),
            spans = listOf(PlaybackSpan(2000, 8000, PlaybackSpan.Reason.NOISE)),
            wokeUp = false,
        )
        val back = NightRecord.fromCsv(r.toCsv())
        assertNotNull(back)
        assertEquals(r, back)
        assertEquals(6000, back.totalPlaybackMs)
    }

    @Test
    fun filesFromOlderVersionsStillParse() {
        // Older builds wrote the sound kind and "not a neighbor" after each noise, and preventive playbacks.
        val old = NightRecord.fromCsv("N,1000,9000,\nE,2000,2500,18.4,-41.2,true,false,MUSIC,true\nP,2000,8000,PREVENTIVE\n")!!
        assertEquals(2000, old.events.single().startMs)
        assertEquals(PlaybackSpan.Reason.PREVENTIVE, old.spans.single().reason)
    }

    @Test
    fun openNightParses() {
        val r = NightRecord(1000, null, emptyList(), emptyList())
        assertEquals(r, NightRecord.fromCsv(r.toCsv()))
    }
}

class TrendsTest {
    private val zone = java.time.ZoneId.of("America/Argentina/Buenos_Aires")

    private fun ms(y: Int, mo: Int, d: Int, h: Int) =
        java.time.LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun saturdayOneAmIsFridayNight() {
        assertEquals(4, Trends.nightOfWeek(ms(2026, 10, 17, 1), zone))
    }

    @Test
    fun weeklyAndByNight() {
        val start = ms(2026, 10, 16, 23) // Friday night
        val r = NightRecord(
            start, start + 8 * 3_600_000L,
            listOf(NoiseEvent(start + 3_600_000L, start + 3_601_000L, 15.0, -40.0, true, false)),
            emptyList(), wokeUp = true,
        )
        assertEquals(1.0, Trends.byNight(listOf(r), zone)[4])
        val week = Trends.weekly(listOf(r), zone).single()
        assertEquals(1, week.wokeUp)
        assertEquals(1.0, week.noisesPerNight)
    }
}
