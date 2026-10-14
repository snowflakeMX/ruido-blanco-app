package ruido.app

import ruido.core.Observation
import ruido.core.Patterns
import java.time.ZoneId

/** When tonight's masking should start before any noise: learned times plus the user's own. */
object Prevention {
    fun windows(
        observations: List<Observation>,
        settings: AppSettings,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<LongRange> {
        if (!settings.preventionEnabled) return emptyList()
        val learned = Patterns(observations, fromMs, zone).riskWindows(fromMs, toMs, settings.preventionLeadMinutes * 60_000L)
        val manual = settings.manualSlots.flatMap { it.windows(fromMs, toMs, zone) }
        return Patterns.merge(learned + manual).mapNotNull { r ->
            val from = maxOf(r.first, fromMs)
            val to = minOf(r.last, toMs)
            if (to > from) from..to else null
        }
    }
}
