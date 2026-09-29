package media.qimeng.app.feature.upload

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.ui.component.QimengAuthorSuggestSection
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengSourceSection

/**
 * 暂存条目展开编辑器（2026-09-25 暂存区重做自 UploadPendingSection 拆出，控制 600 行红线）：
 * 作品名（基名编辑 + 扩展名锁定 + 序号联想回填）/ 目标库覆盖 / 作者联想 / 来源多选。
 * UI 只做渲染与 VM 调用，业务规则全在 UploadViewModel（ADR-0008 铁律 7）。
 */
@Composable
internal fun StagedItemEditor(
    item: StagedUpload,
    itemAuthorQuery: String,
    itemAuthorSuggestions: List<AuthorSuggestion>,
    itemNameSuggestions: List<String>,
    sourceOptions: List<String>,
    libraries: List<LibraryChoice>,
    viewModel: UploadViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UploadNameField(item, itemNameSuggestions, viewModel)
        LibraryOverrideSection(item, libraries, viewModel)
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

/**
 * 作品名编辑段：基名输入（扩展名锁定后缀）+ 序号联想建议列表。
 * 输入框值 = 编辑基名（未编辑显示展示名基名）；清空 = 未编辑（入队回退展示名，同旧口径）。
 */
@Composable
private fun UploadNameField(
    item: StagedUpload,
    suggestions: List<String>,
    viewModel: UploadViewModel,
) {
    Text(text = ITEM_NAME_TITLE, style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        QimengCapsuleTextField(
            value = item.uploadBaseName ?: item.defaultBaseName,
            onValueChange = { viewModel.onItemNameChanged(item, it) },
            placeholder = UPLOAD_NAME_PLACEHOLDER,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (item.extension.isNotEmpty()) {
            Text(
                text = item.extension,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                text = LOCKED_BADGE_TEXT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
    Text(
        text = uploadNameHint(item),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (suggestions.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                suggestions.forEach { base ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.pickNameSuggestion(item, base) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 展示即最终落库名：建议基名 + 锁定扩展名（点击回填基名）
                        Text(
                            text = base + item.extension,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = SUGGESTION_APPLY_TEXT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 作品名提示行（扩展名锁定 + 联想来源说明） */
private fun uploadNameHint(item: StagedUpload): String = if (item.extension.isEmpty()) {
    UPLOAD_NAME_HINT
} else {
    "$UPLOAD_NAME_HINT（扩展名 ${item.extension} 锁定不可改）"
}

/** 逐项目标库覆盖段：库 pills 单选（再点同库取消覆盖 = 回归批次默认） */
@Composable
private fun LibraryOverrideSection(
    item: StagedUpload,
    libraries: List<LibraryChoice>,
    viewModel: UploadViewModel,
) {
    Text(text = ITEM_LIBRARY_TITLE, style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 「跟随批次默认」胶囊：无覆盖态的显式表达（清除逐项覆盖）
        QimengSegPill(
            text = FOLLOW_BATCH_LABEL,
            selected = item.libraryIdOverride == null,
            onClick = { viewModel.clearItemLibraryOverride(item) },
        )
    }
    if (libraries.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "或指定其他库：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LibraryPillFlow(item, libraries, viewModel)
    }
}

/** 库覆盖 pills 流（库多时换行；选中态 = 该项当前覆盖库） */
@Composable
private fun LibraryPillFlow(
    item: StagedUpload,
    libraries: List<LibraryChoice>,
    viewModel: UploadViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        libraries.chunked(LIBRARY_PILL_PER_ROW).forEach { rowLibraries ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowLibraries.forEach { library ->
                    QimengSegPill(
                        text = library.name,
                        selected = item.libraryIdOverride == library.id,
                        onClick = { viewModel.toggleItemLibrary(item, library) },
                    )
                }
            }
        }
    }
}

/** 逐项编辑分区标题与提示（来源挂靠以作者块为前提，服务端口径） */
private const val ITEM_NAME_TITLE = "作品名（落库文件名）"
private const val ITEM_AUTHOR_TITLE = "作者"
private const val ITEM_SOURCE_TITLE = "来源"
private const val ITEM_SOURCE_LOCKED_HINT = "先选择作者后才能设置来源"
private const val ITEM_LIBRARY_TITLE = "目标库"

/** 作品名输入占位与提示（口径：基名可编辑、扩展名锁定，作者匹配与展示依据） */
private const val UPLOAD_NAME_PLACEHOLDER = "作品名"
private const val UPLOAD_NAME_HINT = "仅作品名可编辑；建议为库内既有命名风格 + 下一序号"

/** 扩展名锁定角标 */
private const val LOCKED_BADGE_TEXT = "锁定"

/** 建议行副文案 */
private const val SUGGESTION_APPLY_TEXT = "点击采用"

/** 「跟随批次默认」胶囊文案 */
private const val FOLLOW_BATCH_LABEL = "跟随批次默认"

/** 库覆盖 pills 每行个数（避免超长库名单行溢出） */
private const val LIBRARY_PILL_PER_ROW = 3
