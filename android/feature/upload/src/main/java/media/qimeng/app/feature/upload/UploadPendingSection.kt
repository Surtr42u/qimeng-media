package media.qimeng.app.feature.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.ui.component.QimengAuthorSuggestSection
import media.qimeng.app.core.ui.component.QimengSourceSection
import media.qimeng.app.core.ui.component.QimengThumbnail

/**
 * 上传页暂存区（2026-09-25 暂存区重做自 UploadScreen 拆出，控制 600 行红线）：
 * 批次默认区（持久化配置）+ 暂存条目卡（缩略图 + 失效态 + 编辑入口）。
 * UI 只做渲染与 VM 调用，业务规则全在 UploadViewModel（ADR-0008 铁律 7）。
 */

/**
 * 批次默认区：作者联想 + 来源多选 + 一键应用到全部。三项配置均持久化
 * （StagingRepository.batchConfig），新进暂存项自动继承；来源区在批次作者未选时禁用
 * （来源挂在作者块下，服务端口径——同编辑页门槛）。
 */
@Composable
internal fun BatchDefaultSection(state: UploadUiState, viewModel: UploadViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        QimengAuthorSuggestSection(
            title = BATCH_AUTHOR_TITLE,
            query = state.batchAuthorQuery,
            committedName = state.batchAuthorName,
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
            enabled = state.batchAuthorId != null,
            disabledHint = BATCH_SOURCE_LOCKED_HINT,
            onToggle = viewModel::toggleBatchSource,
            onAddCustom = viewModel::addCustomBatchSource,
        )
        TextButton(
            onClick = viewModel::applyBatchToAll,
            enabled = state.batchAuthorId != null && state.pendingItems.isNotEmpty(),
        ) {
            Text("应用到全部（${state.pendingItems.size} 项）")
        }
    }
}

/**
 * 暂存条目卡：折叠态 = 缩略图 + 落库名 + 挂靠摘要 + 编辑/移除；展开态 = 逐项快捷编辑
 * （作品名联想 + 作者联想 + 来源多选 + 库覆盖，见 [StagedItemEditor]）。
 * 失效条目（收件箱源文件已不存在）：只渲染「文件已不存在」+ 清除，不阻塞其他项。
 */
@Composable
internal fun StagedItemRow(
    item: StagedUpload,
    editing: Boolean,
    missing: Boolean,
    itemAuthorQuery: String,
    itemAuthorSuggestions: List<AuthorSuggestion>,
    itemNameSuggestions: List<String>,
    sourceOptions: List<String>,
    libraries: List<LibraryChoice>,
    viewModel: UploadViewModel,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StagedThumbnail(item)
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(item.effectiveUploadName, style = MaterialTheme.typography.bodyMedium)
                if (item.effectiveUploadName != item.displayName) {
                    Text(
                        text = "原文件名：${item.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (missing) {
                    Text(
                        text = MISSING_FILE_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = sizeLabel(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AttachmentSummary(item)
                }
            }
            if (missing) {
                TextButton(onClick = { viewModel.removeItem(item) }) { Text("清除") }
            } else {
                TextButton(onClick = { viewModel.toggleItemExpanded(item) }) {
                    Text(if (editing) "收起" else "编辑")
                }
                TextButton(onClick = { viewModel.removeItem(item) }) { Text("移除") }
            }
        }
        if (editing && !missing) {
            StagedItemEditor(
                item = item,
                itemAuthorQuery = itemAuthorQuery,
                itemAuthorSuggestions = itemAuthorSuggestions,
                itemNameSuggestions = itemNameSuggestions,
                sourceOptions = sourceOptions,
                libraries = libraries,
                viewModel = viewModel,
            )
        }
    }
}

/** 暂存条目缩略图：Coil 直载（收件箱路径类 = file:// 直读；相册类 = content://），
 *  视频条目角标（首帧解码经全局 ImageLoader 的 coil-video 解码器） */
@Composable
private fun StagedThumbnail(item: StagedUpload) {
    Box {
        QimengThumbnail(
            model = thumbnailModel(item),
            contentDescription = item.displayName,
            modifier = Modifier.size(THUMBNAIL_SIZE_DP.dp),
        )
        if (item.isVideo) {
            Text(
                text = VIDEO_BADGE_TEXT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(2.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = BADGE_BG_ALPHA),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/** 缩略图数据源（收件箱路径类走 file:// 形态；content:// 原样） */
private fun thumbnailModel(item: StagedUpload): String =
    if (item.isPathSource) "file://" + item.source else item.source

/** 尺寸/来源说明行（字节数人性化；未知 -1 显示「大小未知」，同旧口径） */
private fun sizeLabel(item: StagedUpload): String = when {
    item.sizeBytes < 0 -> SIZE_UNKNOWN
    else -> formatBytes(item.sizeBytes)
}

/** 折叠态挂靠摘要行（作者名 + 来源词；未挂作者提示可编辑补挂） */
@Composable
private fun AttachmentSummary(item: StagedUpload) {
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

/** 字节数人性化展示（1024 进位） */
internal fun formatBytes(bytes: Long): String = when {
    bytes < KB_UNIT -> "$bytes B"
    bytes < MB_UNIT -> "${bytes / KB_UNIT} KB"
    bytes < GB_UNIT -> String.format("%.1f MB", bytes.toDouble() / MB_UNIT)
    else -> String.format("%.2f GB", bytes.toDouble() / GB_UNIT)
}

/** 批次默认区标题 */
private const val BATCH_AUTHOR_TITLE = "批次作者（新进暂存项自动继承）"
private const val BATCH_SOURCE_TITLE = "批次来源"

/** 批次来源未选作者时的禁用提示（来源挂靠以作者块为前提，服务端口径） */
private const val BATCH_SOURCE_LOCKED_HINT = "先选择批次作者后才能设置来源"

/** 失效条目文案（收件箱源文件已不存在；清除见 StagedItemRow） */
internal const val MISSING_FILE_TEXT = "文件已不存在"

/** 视频条目角标 */
private const val VIDEO_BADGE_TEXT = "视频"

/** 角标底色不透明度（主色上叠白字） */
private const val BADGE_BG_ALPHA = 0.8f

/** 暂存卡缩略图边长（dp） */
private const val THUMBNAIL_SIZE_DP = 56

/** 字节换算基数（1024 进位） */
private const val KB_UNIT = 1024L
private const val MB_UNIT = KB_UNIT * 1024L
private const val GB_UNIT = MB_UNIT * 1024L

private const val SIZE_UNKNOWN = "大小未知"
