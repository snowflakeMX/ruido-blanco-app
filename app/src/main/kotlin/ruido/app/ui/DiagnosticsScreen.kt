package ruido.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ruido.app.LiveState
import ruido.core.Levels

/** Live numbers to tune the detection together. Levels are relative to this microphone (dBFS). */
@Composable
fun DiagnosticsScreen(live: LiveState, onShareLast: () -> Unit, onBack: () -> Unit) {
    val history = remember { ArrayDeque<Pair<Double, Double>>() }
    if (live.running) {
        history.addLast(live.levelDb to live.expectedDb)
        while (history.size > 300) history.removeFirst()
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TopBar("Diagnóstico", onBack)
        if (!live.running) {
            Text("La noche no está en curso. Iniciala para ver los niveles en vivo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row("Estado", live.phase?.name ?: "-")
            Row("Nivel", db(live.levelDb))
            Row("Fondo aprendido", db(live.baselineDb))
            Row("Esperado (fondo + ruido propio)", db(live.expectedDb))
            Row("Ruido propio en el micrófono", live.leakDb?.let(::db) ?: "sin medir")
            Row("Volumen actual", "${(live.gain * 100).toInt()} %")
            Row("¿Ruido ahora?", if (live.noisy) "Sí" else "No")
            Row("Ruidos / veces que sonó", "${live.events} / ${live.playbackStarts}")
            Row("Fuente del micrófono", live.audioSource)
            Text("Últimos 30 s: nivel (claro) y esperado (oscuro)", style = MaterialTheme.typography.labelSmall)
            LevelChart(history.toList())
        }
        live.error?.let { Text("Último error: $it", color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onShareLast) { Text("Compartir registro de la última noche") }
    }
}

@Composable
private fun Row(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun db(v: Double) = if (v <= Levels.FLOOR_DB) "—" else "%.1f dBFS".format(v)

@Composable
private fun LevelChart(points: List<Pair<Double, Double>>) {
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        if (points.size < 2) return@Canvas
        val top = 0.0
        val bottom = -90.0
        fun y(db: Double) = ((top - db.coerceIn(bottom, top)) / (top - bottom) * size.height).toFloat()
        fun x(i: Int) = i * size.width / 299f
        fun line(select: (Pair<Double, Double>) -> Double): Path = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(select(p))) else lineTo(x(i), y(select(p))) }
        }
        drawPath(line { it.second }, Night.AmberDim, style = Stroke(width = 3f))
        drawPath(line { it.first }, Night.Amber, style = Stroke(width = 2f))
        for (mark in listOf(-30.0, -60.0)) {
            drawLine(Night.SurfaceHigh, Offset(0f, y(mark)), Offset(size.width, y(mark)))
        }
    }
}
