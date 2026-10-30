package media.qimeng.app.feature.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import media.qimeng.app.core.ui.component.QimengSourceSection
import media.qimeng.app.core.ui.theme.QimengDimens

@Composable
internal fun AuthorSourcesDialog(
    editor: AuthorSourcesEditor,
    onChange: (List<String>) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var discard by remember { mutableStateOf(false) }
    val dismiss = {
        if (!editor.saving) {
            if (editor.sources != editor.original) discard = true else onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("来源 / 出处") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM)) {
                Text(editor.author.displayName, style = MaterialTheme.typography.titleMedium)
                Text("记录获取渠道、平台或个人链接，不参与作品匹配。清空并保存将移除全部来源。", style = MaterialTheme.typography.bodySmall)
                editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (editor.loading) Text("读取中…")
                if (!editor.loading && !editor.loaded) TextButton(onClick = onRetry) { Text("重试") }
                QimengSourceSection(
                    title = "来源记录", selectedSources = editor.sources, options = editor.options,
                    enabled = editor.loaded && !editor.saving, disabledHint = "请先完成读取",
                    onToggle = { word -> onChange(if (word in editor.sources) editor.sources - word else editor.sources + word) },
                    onAddCustom = { word -> if (word.isNotBlank() && word.trim() !in editor.sources) onChange(editor.sources + word.trim()) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = editor.loaded && !editor.saving && editor.sources != editor.original) { Text(if (editor.saving) "保存中…" else "保存来源") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !editor.saving) { Text("关闭") } },
    )
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        text = { Text("本次来源修改将丢弃。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
    )
}
