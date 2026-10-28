package media.qimeng.app.feature.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.DiscoveryEntry
import media.qimeng.app.core.ui.component.QimengThumbnail
import media.qimeng.app.core.ui.theme.softDaylightShadow

@Composable
internal fun DiscoveryCard(state: StatsUiState, onChange: () -> Unit, onEntryClick: (DiscoveryEntry) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(color = MaterialTheme.colorScheme.surface, shape = shape,
        modifier = Modifier.fillMaxWidth().softDaylightShadow(shape)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("此刻发现", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onChange, enabled = !state.discoveriesLoading) {
                    Text(if (state.discoveriesLoading) "正在换组…" else "换一组")
                }
            }
            Text("换个角度逛影库", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.discoveriesError?.let { message ->
                TextButton(onClick = onChange) { Text(message) }
            }
            if (state.discoveriesLoading && state.discoveries.isEmpty()) Text("正在发现…")
            if (!state.discoveriesLoading && state.discoveriesError == null && state.discoveries.isEmpty()) Text("暂无可发现的内容")
            state.discoveries.forEach { entry ->
                Row(Modifier.fillMaxWidth().clickable { onEntryClick(entry) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QimengThumbnail(model = entry.thumbUrl, modifier = Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(8.dp)))
                    Text(entry.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
