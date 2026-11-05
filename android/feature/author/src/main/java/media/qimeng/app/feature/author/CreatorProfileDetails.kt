package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 全屏资料子页沿用父页状态，关闭时保留本地作品滚动位置。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreatorProfileDetails(
    name: String,
    state: CreatorProfileUiState,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onReview: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                QimengTopBar(title = "作者资料", onBack = onClose)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    CreatorSection {
                        val item = state.profile
                        if (item != null) {
                            Text(if (item.status == "verified") "身份已确认" else "资料待核实", style = MaterialTheme.typography.labelMedium)
                            if (!item.xProfile?.biography.isNullOrBlank()) {
                                Text("X 简介原文", style = MaterialTheme.typography.titleSmall)
                                Text(item.xProfile!!.biography, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("账号与核验信息", style = MaterialTheme.typography.titleSmall)
                            if (item.biography.isNotBlank()) Text(item.biography, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOfNotNull(item.xProfile?.url, item.homepageUrl) + item.evidenceUrls).filter(String::isNotBlank).distinct().forEach { url ->
                                    CreatorExternalLink(creatorSourceLabel(url) + " ↗", url)
                                }
                            }
                            CreatorStatusRow("头像", "本地图片或影库代理", if (state.avatar != null) "可用" else "未加载")
                            CreatorStatusRow("自动维护资料", "变更先生成待审候选", if (item.autoRefresh) "已开启" else "未开启")
                            CreatorStatusRow("作品订阅", "仅使用作品发布专用来源", if (item.trackUpdates) "已开启" else "未开启")
                            if (item.lastError.isNotBlank()) Text("上次检查未完成：${item.lastError}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            if (item.candidate.hasContent) TextButton(onClick = onReview, enabled = !state.busy) { Text("审阅候选资料") }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = onEdit, enabled = !state.busy && item != null) { Text("编辑资料与来源") }
                        TextButton(onClick = onRefresh, enabled = !state.busy && item != null) { Text(if (state.busy) "正在检查…" else "检查已配置来源") }
                    }
                }
            }
        }
    }
}
