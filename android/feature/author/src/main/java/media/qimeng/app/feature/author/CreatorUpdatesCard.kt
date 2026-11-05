package media.qimeng.app.feature.author

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.CreatorProfile

// 首屏保留本地作品的入口，完整记录由用户按需展开，不把记录数称为未读数。
private const val UPDATE_PREVIEW_COUNT = 3

@Composable
internal fun CreatorUpdatesCard(profile: CreatorProfile, busy: Boolean, onEdit: () -> Unit, onRefresh: () -> Unit) {
    var expanded by rememberSaveable(profile.authorId) { mutableStateOf(false) }
    CreatorSection {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最近公开动态", style = MaterialTheme.typography.titleMedium)
            if (profile.updates.isNotEmpty()) Text("${profile.updates.size} 条记录", style = MaterialTheme.typography.labelMedium)
        }
        Text("查看作者公开发布的内容，点击前往原帖。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (profile.updates.isEmpty()) {
            Text(creatorUpdatesEmptyText(profile), style = MaterialTheme.typography.bodyMedium)
        } else {
            if (profile.lastError.isNotBlank()) Text("本次检查未完成，以下保留上次记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            profile.updates.take(if (expanded) profile.updates.size else UPDATE_PREVIEW_COUNT).forEach { update ->
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CreatorExternalLink(update.title.ifBlank { "查看原帖" }, update.url)
                    if (update.publishedAt.isNotBlank()) Text(update.publishedAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("外部内容 · 在浏览器打开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (profile.updates.size > UPDATE_PREVIEW_COUNT) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起动态" else "查看全部 ${profile.updates.size} 条记录")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, enabled = !busy) { Text(if (profile.trackUpdates) "订阅设置" else "设置动态追踪") }
            // 现有刷新端点同时检查主页与已启用订阅，因此入口明确说明两者。
            TextButton(onClick = onRefresh, enabled = !busy && profile.homepageUrl.isNotBlank()) { Text("检查资料与动态") }
        }
    }
}

internal fun creatorUpdatesEmptyText(profile: CreatorProfile): String = when {
    profile.feedUrl.isBlank() -> "尚未设置公开订阅源，可先前往作者主页查看。"
    !profile.trackUpdates -> "动态追踪未开启，可在设置中按需启用。"
    profile.lastError.isNotBlank() -> "检查未完成，暂时没有可展示的动态。"
    profile.lastCheckedAt.isBlank() -> "已开启追踪，尚未检查公开动态。"
    else -> "暂无已获取的公开动态，不代表作者没有发布新作品。"
}

@Composable
internal fun CreatorExternalLink(label: String, url: String, showAddress: Boolean = false) {
    val handler = LocalUriHandler.current
    var failed by rememberSaveable(url) { mutableStateOf(false) }
    // 与 Web 一致：旧缓存或导入数据也不能触发非 HTTPS scheme。
    TextButton(enabled = url.startsWith("https://"), onClick = {
        try { handler.openUri(url); failed = false }
        catch (_: IllegalArgumentException) { failed = true }
        catch (_: ActivityNotFoundException) { failed = true }
    }) {
        Text(if (showAddress) "$label · ${url.removePrefix("https://")}" else label)
    }
    if (failed) Text("无法打开此链接", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
