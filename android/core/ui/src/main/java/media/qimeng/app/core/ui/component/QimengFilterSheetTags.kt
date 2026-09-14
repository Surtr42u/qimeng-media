package media.qimeng.app.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.ui.R
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 筛选面板「标签流」区块（U10-3 自 QimengFilterSheet.kt 拆出使各文件回落 600 行警戒线内，
 * 行为与文案零变化；标签胶囊视觉同批对齐旧版，见 [TagChip] KDoc）。
 */

/** 标签流：按钮多选（选中实底/未选中软底，与单选胶囊同一套胶囊语言）+ 长按删除（确认框）+ 添加行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsSection(
    tags: List<TagSummary>,
    selectedIds: List<String>,
    onToggleTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
) {
    SectionLabel(stringResource(R.string.ui_filter_tags_section))
    var showAddDialog by remember { mutableStateOf(false) }
    // 长按删除确认框（P2-2b 恢复旧版 v1.16 口径）：挂起待删标签，确认后才回调删除
    var pendingDelete by remember { mutableStateOf<TagSummary?>(null) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        tags.forEach { tag ->
            TagChip(
                label = tag.name,
                selected = tag.id in selectedIds,
                onClick = { onToggleTag(tag.id) },
                // 长按不直接删：先弹确认框（旧仓库 MediaFilterSheet.kt L239-250 实读，P2-2b 对齐）
                onLongClick = { pendingDelete = tag },
            )
        }
    }
    AddTagRow(onClick = { showAddDialog = true })
    if (showAddDialog) {
        AddTagDialog(
            onConfirm = { name ->
                onAddTag(name)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
    pendingDelete?.let { tag ->
        DeleteTagDialog(
            tag = tag,
            onConfirm = {
                onDeleteTag(tag.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 标签胶囊：点击切换选中、长按删除（确认框）。
 * U10-3 观感对齐：字号 14sp→12sp（[FILTER_PILL_FONT_SIZE]）并去掉选中态 SemiBold 加重
 * （旧版 styles.xml QimengTagChip textSize=12sp 无 bold）、高度固定 30dp（[QimengDimens.ChipHeight]），
 * 未选中底色 surfaceVariant→secondaryContainer（旧 qmColorChipBg→secondaryContainer 槽映射见
 * Theme.kt）——与单选胶囊 [FilterPill] 完全同一套胶囊语言（旧 GUIDE_UI「与筛选药丸同一套胶囊语言」）。
 *
 * 保留手绘容器（不走 M3 芯片标准件）是任务 H1 审查记档的**例外**（M3 芯片家族没有长按参数）：
 * 两轮标准件替换实测均破坏功能——① FilterChip 常态态 + 外层 combinedClickable：m3 1.4 芯片内层
 * 手势吞掉外层长按，且长按抬起被误转成点击（模拟器实证）；② FilterChip enabled=false 纯视觉化 +
 * 外层 combinedClickable：连单击都到不了外层（m3 1.4 禁用 Surface 仍拦截手势节点，实测）。
 * 待 M3 提供带长按的芯片标准件或内层手势可穿透后再收编。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QimengDimens.PillCornerRadius))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondaryContainer,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .height(QimengDimens.ChipHeight)
            .padding(horizontal = QimengDimens.ChipHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FILTER_PILL_FONT_SIZE,
                fontWeight = FontWeight.Normal,
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 「+ 添加标签」行（实录逐字文案；旧版为 primary 色全宽文本行）——任务 H1 换 M3 TextButton 标准件 */
@Composable
private fun AddTagRow(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // TextButton 默认内容居中，旧版实录是左对齐全宽文本行——Text 撑满后回左对齐
        Text(
            text = stringResource(R.string.ui_filter_add_tag),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 添加标签对话框（旧版 AlertDialog：标题「添加标签」/输入提示「标签名称」/添加·取消；空名不提交） */
@Composable
private fun AddTagDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.ui_filter_add_tag_dialog_title)) },
        text = {
            // core:ui 自家消费胶囊输入框（G6）；label 走 placeholder 语义，对齐 Web
            QimengCapsuleTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = stringResource(R.string.ui_filter_add_tag_input_hint),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                },
            ) {
                Text(text = stringResource(R.string.ui_filter_add_tag_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ui_filter_add_tag_cancel))
            }
        },
    )
}

/**
 * 长按删除标签确认对话框（修复轮 P2-2b 恢复旧版 v1.16 口径）：文案逐字照旧仓库
 * MediaFilterSheet.kt L239-250（标题「删除标签」/正文警示级联解除文件关联/「删除」「取消」），
 * 确认后才回调删除。
 */
@Composable
private fun DeleteTagDialog(tag: TagSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.ui_filter_delete_tag_dialog_title)) },
        text = { Text(text = stringResource(R.string.ui_filter_delete_tag_message, tag.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.ui_filter_delete_tag_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ui_filter_delete_tag_cancel))
            }
        },
    )
}
