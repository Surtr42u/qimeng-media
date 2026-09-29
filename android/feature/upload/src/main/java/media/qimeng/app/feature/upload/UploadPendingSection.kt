package media.qimeng.app.feature.upload

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import media.qimeng.app.core.ui.component.QimengAuthorSuggestSection
import media.qimeng.app.core.ui.component.QimengSourceSection

/**
 * 上传页批次默认区（2026-09-25 暂存区重做自 UploadScreen 拆出；2026-09-29 直传化收窄：
 * 暂存条目卡与逐项编辑器随暂存区退役删除，本文件只余批次默认区）。
 * UI 只做渲染与 VM 调用，业务规则全在 UploadViewModel（ADR-0008 铁律 7）。
 */

/**
 * 批次默认区（常驻配置区之一，2026-09-28 固化布局）：作者联想 + 来源多选。
 * 两项配置均持久化（StagingRepository.batchConfig），直传入队时自动继承；来源区在批次
 * 作者未选时禁用（来源挂在作者块下，服务端口径——同编辑页门槛）。
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
    }
}

/** 批次默认区标题 */
private const val BATCH_AUTHOR_TITLE = "批次作者（新进文件自动继承）"
private const val BATCH_SOURCE_TITLE = "批次来源"

/** 批次来源未选作者时的禁用提示（来源挂靠以作者块为前提，服务端口径） */
private const val BATCH_SOURCE_LOCKED_HINT = "先选择批次作者后才能设置来源"
