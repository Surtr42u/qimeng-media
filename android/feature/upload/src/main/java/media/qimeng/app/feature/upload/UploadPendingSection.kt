package media.qimeng.app.feature.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.ui.component.QimengAuthorSuggestSection
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengSourceSection

/**
 * 上传页待传区（挂靠批自 UploadScreen 拆出，控制 600 行红线）：批次默认区 +
 * 逐项编辑行。UI 只做渲染与 VM 调用，业务规则全在 UploadViewModel（ADR-0008 铁律 7）。
 */

/**
 * 批次默认区（挂靠批）：作者联想 + 来源多选 + 一键应用到全部待传项。
 * 新选文件自动继承当前批次默认（VM 侧口径）；来源区在批次作者未选时禁用
 * （来源挂在作者块下，服务端口径——同编辑页门槛）。
 */
@Composable
internal fun BatchDefaultSection(state: UploadUiState, viewModel: UploadViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        QimengAuthorSuggestSection(
            title = BATCH_AUTHOR_TITLE,
            query = state.batchAuthorQuery,
            committedName = state.batchAuthor?.displayName,
            committedIsExisting = true,
            suggestions = state.batchAuthorSuggestions,
            onQueryChange = viewModel::onBatchAuthorQueryChange,
            onPickSuggestion = viewModel::pickBatchAuthor,
            onCommitInput = viewModel::commitBatchAuthor,
            onClear = viewModel::clearBatchAuthor,
        )
        QimengSourceSection(
            title = BATCH_SOURCE_TITLE,
            selectedSources = state.batchSources,
            options = state.sourceOptions,
            enabled = state.batchAuthor != null,
            disabledHint = BATCH_SOURCE_LOCKED_HINT,
            onToggle = viewModel::toggleBatchSource,
            onAddCustom = viewModel::addCustomBatchSource,
        )
        TextButton(
            onClick = viewModel::applyBatchToAll,
            enabled = state.batchAuthor != null && state.pendingItems.isNotEmpty(),
        ) {
            Text("应用到全部（${state.pendingItems.size} 项）")
        }
    }
}

/**
 * 待上传文件行：折叠态 = 落库名 + 挂靠摘要 + 编辑/移除；展开态 = 逐项快捷编辑
 * （作品名 + 作者联想 + 来源多选；清空作者 = 该项不带挂靠）。
 */
@Composable
internal fun PendingItemRow(
    item: UploadItem,
    editing: Boolean,
    itemAuthorQuery: String,
    itemAuthorSuggestions: List<AuthorSuggestion>,
    sourceOptions: List<String>,
    viewModel: UploadViewModel,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.effectiveUploadName, style = MaterialTheme.typography.bodyMedium)
                if (item.effectiveUploadName != item.displayName) {
                    Text(
                        text = "原文件名：${item.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.relativeDir.isNotEmpty()) {
                    Text(
                        text = "子目录：${item.relativeDir}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AttachmentSummary(item)
            }
            TextButton(onClick = { viewModel.toggleItemExpanded(item) }) {
                Text(if (editing) "收起" else "编辑")
            }
            TextButton(onClick = { viewModel.removeItem(item) }) { Text("移除") }
        }
        if (editing) {
            PendingItemEditor(
                item = item,
                itemAuthorQuery = itemAuthorQuery,
                itemAuthorSuggestions = itemAuthorSuggestions,
                sourceOptions = sourceOptions,
                viewModel = viewModel,
            )
        }
    }
}

/** 逐项编辑展开体：作品名输入 + 作者联想 + 来源多选（组件全部复用 core:ui 无状态段） */
@Composable
private fun PendingItemEditor(
    item: UploadItem,
    itemAuthorQuery: String,
    itemAuthorSuggestions: List<AuthorSuggestion>,
    sourceOptions: List<String>,
    viewModel: UploadViewModel,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QimengCapsuleTextField(
            value = item.uploadFileName ?: item.displayName,
            onValueChange = { viewModel.setItemUploadName(item, it) },
            placeholder = UPLOAD_NAME_PLACEHOLDER,
            singleLine = true,
        )
        Text(
            text = UPLOAD_NAME_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QimengAuthorSuggestSection(
            title = ITEM_AUTHOR_TITLE,
            query = itemAuthorQuery,
            committedName = item.attachAuthorName,
            committedIsExisting = true,
            suggestions = itemAuthorSuggestions,
            onQueryChange = viewModel::onItemAuthorQueryChange,
            onPickSuggestion = { viewModel.pickItemAuthor(item, it) },
            onCommitInput = { viewModel.commitItemAuthor(item) },
            onClear = { viewModel.clearItemAuthor(item) },
        )
        QimengSourceSection(
            title = ITEM_SOURCE_TITLE,
            selectedSources = item.attachSources.orEmpty(),
            options = sourceOptions,
            enabled = item.attachAuthorId != null,
            disabledHint = ITEM_SOURCE_LOCKED_HINT,
            onToggle = { viewModel.toggleItemSource(item, it) },
            onAddCustom = { viewModel.addCustomItemSource(item, it) },
        )
    }
}

/** 折叠态挂靠摘要行（作者名 + 来源词；未挂作者提示可编辑补挂） */
@Composable
private fun AttachmentSummary(item: UploadItem) {
    val author = item.attachAuthorName
    val sources = item.attachSources.orEmpty()
    val text = when {
        author != null && sources.isNotEmpty() -> "挂靠：$author · 来源 ${sources.joinToString("、")}"
        author != null -> "挂靠：$author"
        else -> "未挂靠作者（点「编辑」补挂）"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 字节数人性化展示（未知 -1 显示"大小未知"） */
private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> SIZE_UNKNOWN
    bytes < KB_UNIT -> "$bytes B"
    bytes < MB_UNIT -> "${bytes / KB_UNIT} KB"
    bytes < GB_UNIT -> String.format("%.1f MB", bytes.toDouble() / MB_UNIT)
    else -> String.format("%.2f GB", bytes.toDouble() / GB_UNIT)
}

/** 批次默认区标题 */
private const val BATCH_AUTHOR_TITLE = "批次作者（新选文件自动继承）"
private const val BATCH_SOURCE_TITLE = "批次来源"

/** 批次来源未选作者时的禁用提示（来源挂靠以作者块为前提，服务端口径） */
private const val BATCH_SOURCE_LOCKED_HINT = "先选择批次作者后才能设置来源"

/** 逐项编辑分区标题与提示（来源挂靠以作者块为前提，服务端口径） */
private const val ITEM_AUTHOR_TITLE = "作者"
private const val ITEM_SOURCE_TITLE = "来源"
private const val ITEM_SOURCE_LOCKED_HINT = "先选择作者后才能设置来源"

/** 逐项编辑作品名输入（占位与提示文案；口径：保留扩展名，作者匹配与展示依据） */
private const val UPLOAD_NAME_PLACEHOLDER = "作品名（落库文件名）"
private const val UPLOAD_NAME_HINT = "保留扩展名；这是作者匹配与展示依据"

/** 字节换算基数（1024 进位） */
private const val KB_UNIT = 1024L
private const val MB_UNIT = KB_UNIT * 1024L
private const val GB_UNIT = MB_UNIT * 1024L

private const val SIZE_UNKNOWN = "大小未知"
