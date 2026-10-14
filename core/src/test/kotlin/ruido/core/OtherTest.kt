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
    fun kindAndDismissedRoundTripAndOldFilesStillParse() {
        val r = NightRecord(
            1000, 9000,
            listOf(
                NoiseEvent(2000, 2500, 18.4, -41.2, true, false, SoundKind.MUSIC, dismissed = false),
                NoiseEvent(3000, 3500, 14.0, -45.0, false, true, null, dismissed = true),
            ),
            emptyList(),
        )
        assertEquals(r, NightRecord.fromCsv(r.toCsv()))
        val old = NightRecord.fromCsv("N,1000,9000,\nE,2000,2500,18.4,-41.2,true,false\n")!!
        assertEquals(null, old.events.single().kind)
        assertTrue(old.events.single().fromNeighbors)
    }

    @Test
    fun openNightParses() {
        val r = NightRecord(1000, null, emptyList(), emptyList())
        assertEquals(r, NightRecord.fromCsv(r.toCsv()))
    }
}

class SoundKindTest {
    private val labels = listOf("Silence", "Snoring", "Pink noise", "Music", "Knock", "Speech", "Cough")

    private fun scores(vararg pairs: Pair<String, Float>) =
        FloatArray(labels.size).also { a -> for ((l, v) in pairs) a[labels.indexOf(l)] = v }

    @Test
    fun maskingNoiseAndSilenceAreIgnored() {
        assertEquals(SoundKind.KNOCKS, SoundKind.classify(labels, scores("Pink noise" to 0.9f, "Knock" to 0.2f)))
    }

    @Test
    fun clearSnoringIsARoomSound() {
        assertEquals(SoundKind.ROOM, SoundKind.classify(labels, scores("Snoring" to 0.6f, "Music" to 0.1f)))
    }

    @Test
    fun weakRoomGuessStaysANeighbor() {
        assertEquals(SoundKind.OTHER, SoundKind.classify(labels, scores("Cough" to 0.1f, "Music" to 0.05f)))
    }

    @Test
    fun musicAndVoices() {
        assertEquals(SoundKind.MUSIC, SoundKind.classify(labels, scores("Music" to 0.7f, "Speech" to 0.3f)))
        assertEquals(SoundKind.VOICES, SoundKind.ofLabel("Shout"))
        assertEquals(SoundKind.DOORS, SoundKind.ofLabel("Slam"))
        assertEquals(SoundKind.ROOM, SoundKind.ofLabel("Baby cry, infant cry"))
    }
}
