package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import media.qimeng.app.core.model.CreatorProfileDraft
import media.qimeng.app.core.model.CreatorXProfile
import media.qimeng.app.core.model.toDraft
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 一次编辑一个资料块，完整草稿及revision继续由ViewModel持有。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreatorProfileEditor(
    state: CreatorProfileUiState,
    onDraft: ((CreatorProfileDraft) -> CreatorProfileDraft) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val draft = state.draft ?: return
    val section = state.editingSection
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val close = {
        if (!state.busy) {
            if (draft != state.profile?.toDraft()) confirmDiscard = true else onClose()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBackground
                    isAppearanceLightNavigationBars = lightBackground
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                QimengTopBar(title = section.title, onBack = close)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("修改后保存，返回资料概览。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (section == CreatorProfileSection.X) {
                        CreatorTextField("X 账号主页", draft.xProfile?.url.orEmpty(), !state.busy) { value ->
                            onDraft { it.copy(xProfile = CreatorXProfile(value, it.xProfile?.biography.orEmpty())) }
                        }
                        CreatorTextField("X 简介原文", draft.xProfile?.biography.orEmpty(), !state.busy, singleLine = false) { value ->
                            onDraft { it.copy(xProfile = CreatorXProfile(it.xProfile?.url.orEmpty(), value)) }
                        }
                        Text("简介原文显示在作者主页。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (section == CreatorProfileSection.BASIC || section == CreatorProfileSection.REVIEW) {
                        CreatorTextField("公开昵称", draft.nickname, !state.busy) { value -> onDraft { it.copy(nickname = value) } }
                        CreatorTextField("整理简介", draft.biography, !state.busy, singleLine = false) { value -> onDraft { it.copy(biography = value) } }
                        CreatorTextField("头像来源", draft.avatarUrl, !state.busy) { value -> onDraft { it.copy(avatarUrl = value) } }
                    }
                    if (section == CreatorProfileSection.SOURCES || section == CreatorProfileSection.REVIEW) {
                        CreatorTextField("创作者主页", draft.homepageUrl, !state.busy) { value ->
                            onDraft { it.copy(homepageUrl = value, autoRefresh = it.autoRefresh && value.trim().startsWith("https://")) }
                        }
                        CreatorTextField("核验链接（每行一个）", draft.evidenceUrls.joinToString("\n"), !state.busy, singleLine = false) { value ->
                            onDraft { it.copy(evidenceUrls = value.lines()) }
                        }
                    }
                    if (section == CreatorProfileSection.SOURCES) {
                        Text("身份状态", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("empty" to "待完善", "candidate" to "待核实", "verified" to "已确认").forEach { (value, label) ->
                                FilterChip(selected = draft.status == value, onClick = {
                                    onDraft { it.copy(status = value, autoRefresh = value == "verified" && it.autoRefresh, trackUpdates = value == "verified" && it.trackUpdates) }
                                }, enabled = !state.busy, label = { Text(label) })
                            }
                        }
                        CreatorCheck("自动检查公开资料", draft.autoRefresh, !state.busy && draft.status == "verified" && draft.homepageUrl.trim().startsWith("https://")) { checked ->
                            onDraft { it.copy(autoRefresh = checked) }
                        }
                        Text("检查变更会进入待审候选。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (section == CreatorProfileSection.SUBSCRIPTION) {
                        CreatorTextField("作品发布订阅地址（RSS / Atom）", draft.feedUrl, !state.busy) { value ->
                            onDraft { it.copy(feedUrl = value, trackUpdates = it.trackUpdates && value.trim().startsWith("https://")) }
                        }
                        CreatorCheck("检查作品订阅更新", draft.trackUpdates, !state.busy && draft.status == "verified" && draft.feedUrl.trim().startsWith("https://")) { checked ->
                            onDraft { it.copy(trackUpdates = checked) }
                        }
                        Text("用于作品发布专用订阅。未配置时，可从作者主页打开发布入口。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (section == CreatorProfileSection.REVIEW) Text("核对候选中的简介、头像及主页来源后保存。", style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = close, enabled = !state.busy) { Text("取消") }
                    Button(onClick = onSave, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(if (state.busy) "保存中…" else "保存资料") }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存修改？") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
    )
}

@Composable
private fun CreatorTextField(label: String, value: String, enabled: Boolean, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        enabled = enabled, singleLine = singleLine, minLines = if (singleLine) 1 else 4)
}

@Composable
private fun CreatorCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
