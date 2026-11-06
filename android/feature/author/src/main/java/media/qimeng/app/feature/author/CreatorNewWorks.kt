package media.qimeng.app.feature.author

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import media.qimeng.app.core.model.CreatorProfile

@Composable
internal fun CreatorNewWorks(profile: CreatorProfile?, busy: Boolean, onDetails: () -> Unit, onRefresh: () -> Unit) {
    if (profile != null && profile.feedUrl.isNotBlank() && profile.trackUpdates) {
        CreatorUpdatesCard(profile, busy, onDetails, onRefresh)
    } else {
        CreatorSection {
            Text("作者新作", style = MaterialTheme.typography.titleMedium)
            Text("尚未接入可用的作品发布订阅，可前往作者发布页查看新作。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            creatorXUrl(profile).takeIf(String::isNotBlank)?.let { CreatorExternalLink("查看 X 发布 ↗", it) }
            TextButton(onClick = onDetails) { Text("发布来源与详细资料") }
        }
    }
}
