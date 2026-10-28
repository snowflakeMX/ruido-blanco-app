package ruido.core

/** A noise that crossed the threshold. Never contains audio. */
data class NoiseEvent(
    val startMs: Long,
    val endMs: Long,
    /** Highest level above what was expected, in dB. */
    val peakExcessDb: Double,
    /** Highest raw level, in dBFS. */
    val peakLevelDb: Double,
    /** True when this noise started a playback. */
    val startedPlayback: Boolean,
    /** True when masking was already playing. */
    val duringPlayback: Boolean,
) {
    val durationMs: Long get() = endMs - startMs
}

/** A continuous stretch of masking playback. */
data class PlaybackSpan(
    val startMs: Long,
    val endMs: Long,
    val reason: Reason,
) {
    /** PREVENTIVE only appears in nights recorded by older versions. */
    enum class Reason { NOISE, CONSTANT, PREVENTIVE }

    val durationMs: Long get() = endMs - startMs
}

data class NightRecord(
    val startMs: Long,
    val endMs: Long?,
    val events: List<NoiseEvent>,
    val spans: List<PlaybackSpan>,
    /** Morning answer to "did noises wake you up?", null if unanswered. */
    val wokeUp: Boolean? = null,
) {
    val totalPlaybackMs: Long get() = spans.sumOf { it.durationMs }
    val playbackStarts: Int get() = spans.size

    fun toCsv(): String = buildString {
        appendLine("N,$startMs,${endMs ?: ""},${wokeUp ?: ""}")
        for (e in events) {
            appendLine(
                "E,${e.startMs},${e.endMs},${fmt(e.peakExcessDb)},${fmt(e.peakLevelDb)}," +
                    "${e.startedPlayback},${e.duringPlayback}",
            )
        }
        for (s in spans) appendLine("P,${s.startMs},${s.endMs},${s.reason}")
    }

    companion object {
        private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

        /** Parses [toCsv] output; unknown or broken lines are skipped. */
        fun fromCsv(text: String): NightRecord? {
            var start: Long? = null
            var end: Long? = null
            var woke: Boolean? = null
            val events = mutableListOf<NoiseEvent>()
            val spans = mutableListOf<PlaybackSpan>()
            for (line in text.lineSequence()) {
                val f = line.trim().split(',')
                runCatching {
                    when (f[0]) {
                        "N" -> {
                            start = f[1].toLong()
                            end = f.getOrNull(2)?.toLongOrNull()
                            woke = f.getOrNull(3)?.toBooleanStrictOrNull()
                        }
                        "E" -> events += NoiseEvent(
                            f[1].toLong(), f[2].toLong(), f[3].toDouble(), f[4].toDouble(),
                            f[5].toBooleanStrict(), f[6].toBooleanStrict(),
                        )
                        "P" -> spans += PlaybackSpan(f[1].toLong(), f[2].toLong(), PlaybackSpan.Reason.valueOf(f[3]))
                    }
                }
            }
            val s = start ?: return null
            return NightRecord(s, end, events, spans, woke)
        }
    }
}
