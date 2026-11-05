package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 资料概览默认只读，点击对应卡片的编辑入口才创建草稿。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreatorProfileDetails(
    name: String,
    state: CreatorProfileUiState,
    onClose: () -> Unit,
    onEdit: (CreatorProfileSection) -> Unit,
    onRefresh: () -> Unit,
    onReview: () -> Unit,
) {
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showChecks by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                QimengTopBar(title = "资料与维护", onBack = onClose)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    val item = state.profile
                    if (item != null) {
                        Text(when (item.status) {
                            "verified" -> "身份已确认"
                            "candidate" -> "资料待核实"
                            else -> "资料待完善"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CreatorProfileGroup(CreatorProfileSection.X, !state.busy, onEdit) {
                            Text(item.xProfile?.biography?.ifBlank { null } ?: "尚未收录X简介。", style = MaterialTheme.typography.bodyMedium)
                            item.xProfile?.url?.takeIf(String::isNotBlank)?.let { CreatorExternalLink("前往 X ↗", it) }
                        }
                        CreatorProfileGroup(CreatorProfileSection.BASIC, !state.busy, onEdit) {
                            Text("公开昵称", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.nickname.ifBlank { "尚未填写" }, style = MaterialTheme.typography.bodyMedium)
                            Text("整理简介", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.biography.substringBefore("\n\n").ifBlank { "尚未填写" }, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (item.biography.isNotBlank()) {
                                TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "收起完整说明" else "查看完整说明与核验记录") }
                                if (showNotes) Text(item.biography, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CreatorStatusRow("头像", "本地图片或影库代理", if (state.avatar != null) "可用" else "未加载")
                        }
                        CreatorProfileGroup(CreatorProfileSection.SOURCES, !state.busy, onEdit) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(item.homepageUrl) + item.evidenceUrls).filter(String::isNotBlank).distinct().forEach { url ->
                                    CreatorExternalLink(creatorSourceLabel(url) + " ↗", url)
                                }
                            }
                            Text("公开资料检查 · ${if (item.autoRefresh) "已开启" else "已关闭"}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onRefresh, enabled = !state.busy) { Text(if (state.busy) "检查中…" else "检查来源") }
                            if (item.lastError.isNotBlank() || item.lastSuccessAt.isNotBlank()) {
                                TextButton(onClick = { showChecks = !showChecks }) { Text(if (showChecks) "收起检查记录" else "查看检查记录") }
                                if (showChecks) {
                                    if (item.lastSuccessAt.isNotBlank()) Text("最近成功 ${item.lastSuccessAt}", style = MaterialTheme.typography.bodySmall)
                                    if (item.lastError.isNotBlank()) Text(item.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        CreatorProfileGroup(CreatorProfileSection.SUBSCRIPTION, !state.busy, onEdit) {
                            Text(if (item.feedUrl.isBlank()) "尚未配置作品发布订阅" else "已配置作品发布来源", style = MaterialTheme.typography.bodyMedium)
                            Text("订阅检查 · ${if (item.trackUpdates) "已开启" else "已关闭"}", style = MaterialTheme.typography.bodySmall)
                            if (item.feedUrl.isNotBlank()) CreatorExternalLink("查看订阅来源 ↗", item.feedUrl)
                        }
                        if (item.candidate.hasContent) CreatorSection {
                            Text("待核对的候选资料", style = MaterialTheme.typography.titleSmall)
                            Text("核对候选资料与来源后保存。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onReview, enabled = !state.busy) { Text("填入候选草稿，继续核对") }
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun CreatorProfileGroup(
    section: CreatorProfileSection,
    enabled: Boolean,
    onEdit: (CreatorProfileSection) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CreatorSection {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { onEdit(section) }, enabled = enabled) { Text("编辑${section.title}") }
        }
        content()
    }
}
