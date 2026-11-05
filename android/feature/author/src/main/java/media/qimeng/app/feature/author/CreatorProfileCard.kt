package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import media.qimeng.app.core.ui.component.QimengSettingsCard
import media.qimeng.app.core.ui.theme.QimengDimens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreatorProfileCard(
    name: String,
    state: CreatorProfileUiState,
    onFollow: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onReview: () -> Unit,
) {
    var expanded by rememberSaveable(name) { mutableStateOf(false) }
    val profile = state.profile
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = QimengDimens.ScreenPaddingHorizontal,
            vertical = QimengDimens.SpaceM,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CreatorSection {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.avatar != null) {
                    AsyncImage(
                        model = state.avatar, contentDescription = "作者头像", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                    )
                } else {
                    Surface(modifier = Modifier.size(64.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Text(name.take(1).ifBlank { "作" }, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    if (!profile?.nickname.isNullOrBlank() && profile?.nickname != name) {
                        Text(profile!!.nickname, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        when (profile?.status) {
                            "verified" -> "身份已确认"
                            "candidate" -> "资料待核实"
                            else -> if (profile == null) "作者档案" else "资料待完善"
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                state.author?.let { author ->
                    TextButton(onClick = onFollow, enabled = !state.followBusy) {
                        Text(if (state.followBusy) "处理中…" else if (author.followed) "已关注" else "关注作者")
                    }
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起资料" else "资料详情") }
                TextButton(onClick = onEdit, enabled = !state.busy) { Text("编辑资料") }
            }
            if (!profile?.biography.isNullOrBlank()) {
                Text(
                    profile!!.biography.substringBefore("\n\n"), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在处理作者资料…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry, enabled = !state.busy) { Text("重新读取") }
            }
            profile?.let { item ->
                if (expanded) {
                    HorizontalDivider()
                    if (item.biography.isNotBlank()) {
                        Text("账号与核验信息", style = MaterialTheme.typography.titleSmall)
                        Text(item.biography, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(item.homepageUrl) + item.evidenceUrls).filter(String::isNotBlank).distinct().forEach { url ->
                            CreatorExternalLink(creatorSourceLabel(url) + " ↗", url)
                        }
                    }
                    CreatorStatusRow("头像", "本地图片或影库代理", if (state.avatar != null) "可用" else if (state.avatarError) "暂不可用" else if (item.avatarUrl.isBlank()) "未设置" else "加载中")
                    CreatorStatusRow("自动维护资料", "变更先生成待审候选", if (item.autoRefresh) "已开启" else "未开启")
                    CreatorStatusRow("追踪公开动态", "需公开 RSS / Atom 订阅源", if (item.trackUpdates) "已开启" else "未开启")
                    if (item.lastSuccessAt.isNotBlank()) Text("最近维护成功：${item.lastSuccessAt}", style = MaterialTheme.typography.bodySmall)
                    if (item.lastError.isNotBlank()) Text("上次在线检查未完成：${item.lastError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (item.lastError.isNotBlank()) {
                    TextButton(onClick = { expanded = true }) { Text("在线检查未完成，查看详情") }
                }
                if (item.candidate.hasContent) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("发现资料变化", style = MaterialTheme.typography.titleSmall)
                            Text("已保存的资料保持原样，请核对候选及证据后再保存。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onReview, enabled = !state.busy) { Text("审阅候选资料") }
                        }
                    }
                }
            }
        }
        profile?.let {
            if (it.feedUrl.isNotBlank() || it.trackUpdates || it.updates.isNotEmpty()) {
                CreatorUpdatesCard(it, state.busy, onEdit, onRefresh)
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
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(status, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
