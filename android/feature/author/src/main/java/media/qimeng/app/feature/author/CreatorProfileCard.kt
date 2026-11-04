package media.qimeng.app.feature.author

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import media.qimeng.app.core.model.CreatorProfileDraft
import media.qimeng.app.core.model.toDraft
import media.qimeng.app.core.ui.theme.QimengDimens

@Composable
internal fun CreatorProfileCard(
    name: String,
    state: CreatorProfileUiState,
    fileCount: Int?,
    onFollow: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onReview: () -> Unit,
) {
    var expanded by rememberSaveable(name) { mutableStateOf(false) }
    val profile = state.profile
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = QimengDimens.SpaceM),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.avatar != null) {
                    AsyncImage(model = state.avatar, contentDescription = "作者头像", modifier = Modifier.size(52.dp).clip(CircleShape))
                } else {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(name.take(1).ifBlank { "作" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(14.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(profile?.nickname?.ifBlank { name } ?: name, style = MaterialTheme.typography.titleMedium)
                    if (!profile?.nickname.isNullOrBlank() && profile?.nickname != name) {
                        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${state.author?.fileCount ?: fileCount ?: "—"} 个本地作品", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.author?.let { author ->
                    TextButton(onClick = onFollow, enabled = !state.followBusy) { Text(if (author.followed) "已关注" else "关注") }
                }
            }
            if (!profile?.biography.isNullOrBlank()) {
                Text(profile!!.biography, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(when (profile?.status) { "verified" -> "身份已核实"; "candidate" -> "资料待核实"; else -> "尚未完善资料" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起资料" else "资料与维护") }
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry, enabled = !state.busy) { Text("重新读取") }
            }
            if (state.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            if (expanded) {
                HorizontalDivider()
                profile?.let { item ->
                    if (item.homepageUrl.isNotBlank()) CreatorExternalLink("作者主页", item.homepageUrl)
                    item.evidenceUrls.forEach { CreatorExternalLink("资料来源", it) }
                    if (state.avatarError) Text("头像暂不可用，保留作者首字", style = MaterialTheme.typography.bodySmall)
                    Text("公开资料自动检查：${if (item.autoRefresh) "已开启" else "关闭"} · 新作订阅：${if (item.trackUpdates) "已开启" else "关闭"}", style = MaterialTheme.typography.bodySmall)
                    if (item.lastSuccessAt.isNotBlank()) Text("最近成功：${item.lastSuccessAt}", style = MaterialTheme.typography.bodySmall)
                    if (item.lastError.isNotBlank()) Text(item.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (item.candidate.hasContent) {
                        Text("有待审阅的资料", style = MaterialTheme.typography.titleSmall)
                        Text(item.candidate.nickname, style = MaterialTheme.typography.bodyMedium)
                        Text(item.candidate.biography, style = MaterialTheme.typography.bodySmall)
                        item.candidate.evidenceUrls.forEach { CreatorExternalLink("候选证据", it) }
                        TextButton(onClick = onReview, enabled = !state.busy) { Text("审阅并填写草稿") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onEdit, enabled = !state.busy) { Text("编辑资料") }
                        TextButton(onClick = onRefresh, enabled = !state.busy && item.homepageUrl.isNotBlank()) { Text("检查公开资料") }
                    }
                    if (item.updates.isNotEmpty()) {
                        Text("公开新作动态", style = MaterialTheme.typography.titleSmall)
                        item.updates.forEach { update -> CreatorExternalLink(update.title.ifBlank { "查看原帖" }, update.url) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorExternalLink(label: String, url: String) {
    val handler = LocalUriHandler.current
    var failed by rememberSaveable(url) { mutableStateOf(false) }
    TextButton(onClick = {
        try { handler.openUri(url); failed = false }
        catch (_: IllegalArgumentException) { failed = true }
        catch (_: android.content.ActivityNotFoundException) { failed = true }
    }) { Text("$label · ${url.removePrefix("https://")}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
    if (failed) Text("无法打开此链接", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

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
                Text("附属资料不会改变作者名、作品关联或搜索来源。", style = MaterialTheme.typography.bodySmall)
                CreatorTextField("公开昵称", draft.nickname, !state.busy) { value -> onDraft { it.copy(nickname = value) } }
                CreatorTextField("简介", draft.biography, !state.busy, singleLine = false) { value -> onDraft { it.copy(biography = value) } }
                CreatorTextField("作者主页（HTTPS）", draft.homepageUrl, !state.busy) { value -> onDraft { it.copy(homepageUrl = value) } }
                CreatorTextField("头像来源（HTTPS）", draft.avatarUrl, !state.busy) { value -> onDraft { it.copy(avatarUrl = value) } }
                CreatorTextField("核验来源（每行一个链接）", draft.evidenceUrls.joinToString("\n"), !state.busy, singleLine = false) { value ->
                    onDraft { it.copy(evidenceUrls = value.lines().map(String::trim).filter(String::isNotBlank)) }
                }
                CreatorCheck("身份与证据已核实", draft.status == "verified", !state.busy) { checked -> onDraft { it.copy(status = if (checked) "verified" else "candidate", autoRefresh = checked && it.autoRefresh) } }
                CreatorCheck("每天检查公开资料，更新先待审阅", draft.autoRefresh, !state.busy && draft.status == "verified" && draft.homepageUrl.isNotBlank()) { checked -> onDraft { it.copy(autoRefresh = checked) } }
                CreatorTextField("公开 RSS / Atom 订阅地址（可选）", draft.feedUrl, !state.busy) { value -> onDraft { it.copy(feedUrl = value) } }
                CreatorCheck("追踪此订阅的新作", draft.trackUpdates, !state.busy && draft.feedUrl.isNotBlank()) { checked -> onDraft { it.copy(trackUpdates = checked) } }
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
