package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import media.qimeng.app.core.ui.component.QimengSettingsCard
import media.qimeng.app.core.ui.theme.QimengDimens

/** 主卡保留X原文与X入口，完整账号资料和维护进入详情子页。 */
@Composable
internal fun CreatorProfileCard(
    name: String,
    state: CreatorProfileUiState,
    onFollow: () -> Unit,
    onDetails: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = QimengDimens.SpaceM),
    ) {
        CreatorSection {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.avatar != null) {
                    AsyncImage(model = state.avatar, contentDescription = "作者头像", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape))
                } else {
                    Surface(modifier = Modifier.size(64.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(contentAlignment = Alignment.Center) { Text(name.ifBlank { "作" }.let { it.substring(0, it.offsetByCodePoints(0, 1)) }, style = MaterialTheme.typography.headlineSmall) }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Text("${state.author?.fileCount?.toString() ?: "—"} 个本地文件", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.author?.let { author ->
                    TextButton(onClick = onFollow, enabled = !state.followBusy) {
                        Text(if (state.followBusy) "处理中…" else if (author.followed) "已关注" else "关注")
                    }
                }
            }
            state.profile?.xProfile?.biography?.takeIf(String::isNotBlank)?.let { biography ->
                Text(biography, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.busy && state.profile == null) Text("正在读取作者资料…", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                creatorXUrl(state.profile).takeIf(String::isNotBlank)?.let { CreatorExternalLink("前往 X ↗", it) }
                TextButton(onClick = onDetails) { Text("详细资料") }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry, enabled = !state.busy) { Text("重新读取") }
            }
        }
    }
}

@Composable
internal fun CreatorSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    QimengSettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun CreatorStatusRow(title: String, description: String, status: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(status, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
