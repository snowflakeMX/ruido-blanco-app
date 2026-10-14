package ruido.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ruido.core.NightRecord
import ruido.core.Patterns
import ruido.core.Trends
import java.time.LocalDate
import java.time.ZoneId

val NIGHT_NAMES = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

/** Hours shown in the map: 20:00 to 08:00. */
private val MAP_HOURS = (20..23) + (0..7)

/**
 * What the app learned about the neighbors: a week map of when they are usually noisy,
 * tonight's prevention, and how the nights evolve over the weeks.
 */
@Composable
fun PatternsScreen(
    patterns: Patterns,
    records: List<NightRecord>,
    preventionEnabled: Boolean,
    tonight: List<LongRange>,
    zone: ZoneId,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Horarios de los vecinos", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Lo que aprendió") {
                Text(
                    when {
                        !preventionEnabled -> "La prevención está apagada en Ajustes. Igual sigue aprendiendo."
                        !patterns.ready -> "Lleva ${patterns.nights} de ${Patterns.MIN_NIGHTS} noches. " +
                            "Hasta completarlas solo registra; después empieza a encenderse antes de los ruidos habituales."
                        else -> "Aprendió de ${patterns.nights} noches. Se enciende suave un rato antes de los horarios marcados."
                    },
                    modifier = Modifier.testTag("patterns-status"),
                )
                Text(
                    if (tonight.isEmpty()) "Esta noche no hay horarios de prevención."
                    else "Esta noche: ${windowsText(tonight)}.",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("tonight"),
                )
                Hint("Las franjas fijas que agregues en Ajustes se suman siempre, aunque todavía no haya aprendido.")
            }

            Section("Mapa de la semana") {
                WeekMap(patterns)
                Hint(
                    "Cada fila es una noche (la del viernes incluye la madrugada del sábado). Más color = ruido más frecuente. " +
                        "Con borde: horarios en los que se adelanta.",
                )
            }

            Section("Tendencias") {
                val weeks = Trends.weekly(records, zone)
                if (weeks.isEmpty()) {
                    Hint("Aparecen después de la primera noche.")
                } else {
                    Text("Ruidos por noche, semana a semana", style = MaterialTheme.typography.bodyLarge)
                    Bars(
                        weeks.map { it.noisesPerNight },
                        weeks.map { LocalDate.ofEpochDay(it.mondayEpochDay).let { d -> "${d.dayOfMonth}/${d.monthValue}" } },
                        tag = "weeks",
                    )
                    Text("Ruidos por noche según el día", style = MaterialTheme.typography.bodyLarge)
                    Bars(Trends.byNight(records, zone).map { it ?: 0.0 }, NIGHT_NAMES, tag = "weekdays")
                    val answered = weeks.sumOf { it.answered }
                    if (answered > 0) {
                        Text("Te despertaron ${weeks.sumOf { it.wokeUp }} de $answered noches respondidas.")
                    }
                    Hint("Solo cuentan los ruidos de los vecinos: no los marcados como \"no fue un vecino\" ni los de la habitación.")
                }
            }
        }
    }
}

@Composable
private fun WeekMap(patterns: Patterns) {
    Column(Modifier.testTag("week-map"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp))
            for (h in MAP_HOURS) {
                Text(
                    if (h % 2 == 0) "%02d".format(h) else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        for (night in 0 until 7) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(NIGHT_NAMES[night], fontSize = 13.sp, modifier = Modifier.width(33.dp))
                for (h in MAP_HOURS) {
                    val first = ((h + 12) % 24) * 4
                    val p = (first until first + 4).maxOf { patterns.probability(night, it) }
                    val risky = (first until first + 4).any { patterns.risky(night, it) }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(22.dp)
                            .background(if (p < 0.02) Night.SurfaceHigh else Night.Amber.copy(alpha = (0.2f + 0.8f * p.toFloat()).coerceAtMost(1f)))
                            .let { if (risky) it.border(2.dp, Color.White) else it },
                    )
                }
            }
        }
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
