package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.CreatorProfileDraft
import media.qimeng.app.core.model.toDraft

@Composable
internal fun CreatorProfileEditor(
    state: CreatorProfileUiState,
    onDraft: ((CreatorProfileDraft) -> CreatorProfileDraft) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val draft = state.draft ?: return
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val close = {
        if (!state.busy) {
            if (draft != state.profile?.toDraft()) confirmDiscard = true else onClose()
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("作者资料与维护") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("公开资料", style = MaterialTheme.typography.titleMedium)
                Text("附属资料不会改变作者名、作品关联或搜索来源。", style = MaterialTheme.typography.bodySmall)
                CreatorTextField("公开昵称", draft.nickname, !state.busy) { value -> onDraft { it.copy(nickname = value) } }
                CreatorTextField("简介", draft.biography, !state.busy, singleLine = false) { value -> onDraft { it.copy(biography = value) } }
                CreatorTextField("作者主页（HTTPS）", draft.homepageUrl, !state.busy) { value -> onDraft { it.copy(homepageUrl = value) } }
                CreatorTextField("头像来源（HTTPS）", draft.avatarUrl, !state.busy) { value -> onDraft { it.copy(avatarUrl = value) } }
                CreatorTextField("核验来源（每行一个链接）", draft.evidenceUrls.joinToString("\n"), !state.busy, singleLine = false) { value ->
                    onDraft { it.copy(evidenceUrls = value.lines()) }
                }
                HorizontalDivider()
                Text("身份与自动维护", style = MaterialTheme.typography.titleMedium)
                CreatorCheck("身份与证据已核实", draft.status == "verified", !state.busy) { checked -> onDraft { it.copy(status = if (checked) "verified" else "candidate", autoRefresh = checked && it.autoRefresh, trackUpdates = checked && it.trackUpdates) } }
                CreatorCheck("每天检查公开资料，更新先待审阅", draft.autoRefresh, !state.busy && draft.status == "verified" && draft.homepageUrl.isNotBlank()) { checked -> onDraft { it.copy(autoRefresh = checked) } }
                Text("只生成候选，核对并保存后才更新资料。", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("追踪公开动态", style = MaterialTheme.typography.titleMedium)
                CreatorTextField("公开 RSS / Atom 订阅地址（可选）", draft.feedUrl, !state.busy) { value -> onDraft { it.copy(feedUrl = value) } }
                CreatorCheck("追踪此订阅的新作", draft.trackUpdates, !state.busy && draft.status == "verified" && draft.feedUrl.isNotBlank()) { checked -> onDraft { it.copy(trackUpdates = checked) } }
                Text("未提供订阅源的平台不能自动追踪新作。检查失败保留已有资料；不自动下载作品。", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = onSave, enabled = !state.busy) { Text(if (state.busy) "保存中…" else "保存资料") } },
        dismissButton = { TextButton(onClick = close, enabled = !state.busy) { Text("取消") } },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存修改？") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
    )
}

@Composable
private fun CreatorTextField(label: String, value: String, enabled: Boolean, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = singleLine)
}

@Composable
private fun CreatorCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
