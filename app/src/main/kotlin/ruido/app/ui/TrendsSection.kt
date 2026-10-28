package ruido.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ruido.core.NightRecord
import ruido.core.Trends
import java.time.LocalDate
import java.time.ZoneId

val NIGHT_NAMES = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

/** How the nights evolve: noises per night week by week and by night of the week. */
@Composable
fun TrendsSection(records: List<NightRecord>, zone: ZoneId = ZoneId.systemDefault()) {
    val weeks = Trends.weekly(records, zone)
    if (weeks.isEmpty()) return
    Section("Tendencias") {
        Text("Ruidos por noche, semana a semana", style = MaterialTheme.typography.bodyLarge)
        Bars(
            weeks.map { it.noisesPerNight },
            weeks.map { LocalDate.ofEpochDay(it.mondayEpochDay).let { d -> "${d.dayOfMonth}/${d.monthValue}" } },
            tag = "weeks",
        )
        Text("Ruidos por noche según el día", style = MaterialTheme.typography.bodyLarge)
        Bars(Trends.byNight(records, zone).map { it ?: 0.0 }, NIGHT_NAMES, tag = "weekdays")
        val answered = weeks.sumOf { it.answered }
        if (answered > 0) Text("Te despertaron ${weeks.sumOf { it.wokeUp }} de $answered noches respondidas.")
        Hint("La noche del viernes incluye la madrugada del sábado.")
    }
}

/** Simple bar chart with a label under each bar and the value above it. */
@Composable
private fun Bars(values: List<Double>, labels: List<String>, tag: String) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    Row(
        Modifier.fillMaxWidth().height(120.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (v >= 10) "%.0f".format(v) else "%.1f".format(v), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((70 * (v / max)).coerceAtLeast(2.0).dp)
                        .background(Night.Amber),
                )
                Text(labels.getOrElse(i) { "" }, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
