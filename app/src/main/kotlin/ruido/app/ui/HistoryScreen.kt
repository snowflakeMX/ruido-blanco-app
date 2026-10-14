package ruido.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ruido.core.NightRecord

@Composable
fun HistoryScreen(nights: List<NightRecord>, onShare: (Long) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        TopBar("Historial", onBack)
        if (nights.isEmpty()) {
            Text("Todavía no hay noches registradas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(nights, key = { it.startMs }) { night ->
                Column {
                    NightCard(night)
                    TextButton(onClick = { onShare(night.startMs) }) { Text("Compartir registro") }
                }
            }
        }
    }
}
