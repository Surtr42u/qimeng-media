package media.qimeng.app.feature.upload

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.ui.component.QimengAuthorSuggestSection
import media.qimeng.app.core.ui.component.QimengSourceSection
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 上传页批次默认区（2026-10-08 体验重构）：
 * 解耦上传与元数据录入的繁重感——改为可选折叠卡。
 * 默认收起为轻量入口，传单作者套图时可展开预设；传多作者或杂图无需预设，直接上传后整理。
 * UI 只做渲染与 VM 调用，业务规则全在 UploadViewModel（ADR-0008 铁律 7）。
 */
@Composable
internal fun BatchDefaultSection(state: UploadUiState, viewModel: UploadViewModel) {
    val hasConfiguredAuthor = state.batchAuthorName != null
    var expanded by rememberSaveable { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "批次作者与来源（可选）",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (hasConfiguredAuthor) {
                            Text(
                                text = " · 已设置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (hasConfiguredAuthor) {
                            val authorText = "作者：${state.batchAuthorName}"
                            val sourcesText = if (state.batchSources.isNotEmpty()) {
                                " · 来源：${state.batchSources.joinToString(", ")}"
                            } else ""
                            "$authorText$sourcesText"
                        } else {
                            "多张图属同一作者时可展开预设；杂图无需设置，直接上传即可"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = if (hasConfiguredAuthor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasConfiguredAuthor && !expanded) {
                        TextButton(
                            onClick = { viewModel.clearBatchAuthor() },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("清除", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (expanded) "收起" else "展开设置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
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
        }
    }
}

/** 批次默认区标题 */
private const val BATCH_AUTHOR_TITLE = "批次作者（新进文件自动继承）"
private const val BATCH_SOURCE_TITLE = "批次来源"

/** 批次来源未选作者时的禁用提示（来源挂靠以作者块为前提，服务端口径） */
private const val BATCH_SOURCE_LOCKED_HINT = "先选择批次作者后才能设置来源"
