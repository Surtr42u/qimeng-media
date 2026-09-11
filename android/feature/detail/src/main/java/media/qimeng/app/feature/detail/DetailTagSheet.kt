package media.qimeng.app.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.icon.ClearIcon
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

// ---------- 页面私有尺寸档（本文件单源；来源注释随条目） ----------

/** 「当前标签」勾选移除图标边长（chip 内随行小图标档） */
private val TAG_CLEAR_ICON_SIZE = 16.dp

/** 保存按钮内嵌转圈直径（按钮内小尺寸档） */
/** 保存按钮内嵌指示器直径单源到 QimengDimens（V8 #5：16dp 强缩失衡 → 对齐 labelLarge 文字行高） */
private val TAG_SAVE_PROGRESS_SIZE = QimengDimens.ButtonLoadingIndicatorSize

/**
 * 标签管理弹窗（LEGACY §A / Web TagDialog；自 DetailSections.kt 拆出，纯移动零行为变化）：
 * 外层整体可上下滚动；「当前标签」=勾选集（chip 带 ClearIcon 点击即时移除勾选）；「其他标签」=未选池
 * （点击勾选；池按服务端名字序——LEGACY §A 要求创建时间序但协议无 createdAt，
 * 名字序降级已拍板）；新建输入框+按钮（成功回调才清空输入，失败保留重试）；保存=整体替换（saving 转圈防重）。
 * 当前标签空态=「暂无标签」。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
internal fun DetailTagManageSheet(
    pool: List<TagChip>,
    selectedTagIds: List<String>,
    savingTags: Boolean,
    onToggleTag: (String) -> Unit,
    onUnbindTag: (String) -> Unit,
    onCreateTag: (name: String, onCreated: () -> Unit) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var draft by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                // 底部呼吸空间与页面级留白同档（DETAIL_BOTTOM_SPACER 单源在 DetailScreen.kt）
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            Text(
                text = stringResource(R.string.detail_tag_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.detail_tag_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = QimengDimens.SpaceXS),
            )
            SheetTagSection(title = stringResource(R.string.detail_tag_section_current)) {
                val selectedChips = pool.filter { it.id in selectedTagIds }
                if (selectedChips.isEmpty()) {
                    SheetEmptyHint()
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
                        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
                    ) {
                        selectedChips.forEach { chip ->
                            // 关闭图标 = **立即** DELETE 单条解绑（N4 I7b；N3 #32 逐条删端点，
                            // 失败由 VM 回滚乐观态+横幅提示）；LEGACY §A:17「删除只解除本文件
                            // 关联」语义不变——解绑不动标签池本体。
                            DisplayPill(
                                text = chip.name,
                                trailing = {
                                    Icon(
                                        imageVector = ClearIcon,
                                        contentDescription = stringResource(R.string.detail_tag_remove_selection),
                                        modifier = Modifier
                                            .padding(start = QimengDimens.SpaceS)
                                            .size(TAG_CLEAR_ICON_SIZE)
                                            .clickable { onUnbindTag(chip.id) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            SheetTagSection(title = stringResource(R.string.detail_tag_section_other)) {
                // 池序 = 服务端名字序（GET /tags 恒名称升序；LEGACY 创建时间序的已拍板降级）
                val otherChips = pool.filterNot { it.id in selectedTagIds }
                if (otherChips.isEmpty()) {
                    SheetEmptyHint()
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
                        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
                    ) {
                        otherChips.forEach { chip ->
                            Surface(
                                onClick = { onToggleTag(chip.id) },
                                shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = chip.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(
                                        horizontal = QimengDimens.ChipHorizontalPadding,
                                        vertical = QimengDimens.SpaceS,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = QimengDimens.SpaceL),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
            ) {
                QimengCapsuleTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = stringResource(R.string.detail_tag_new_placeholder),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = draft.isNotBlank(),
                    // 创建成功回调才清空输入框（Web TagDialog 同款；失败保留输入供重试，错误经横幅反馈）
                    onClick = { onCreateTag(draft) { draft = "" } },
                ) {
                    Text(text = stringResource(R.string.detail_tag_create))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = QimengDimens.SpaceM),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !savingTags) {
                    Text(text = stringResource(R.string.detail_cancel))
                }
                Spacer(modifier = Modifier.width(QimengDimens.SpaceS))
                // 保存中=禁用态大面积容器，夜间走不透明禁用底消 dither 横带（W6 #49）
                Button(onClick = onSave, enabled = !savingTags, colors = qimengFilledButtonColors()) {
                    if (savingTags) {
                        // V6：expressive LoadingIndicator 替换（仅控件替换，size 约束原样）
                        LoadingIndicator(modifier = Modifier.size(TAG_SAVE_PROGRESS_SIZE))
                        Spacer(modifier = Modifier.width(QimengDimens.SpaceS))
                    }
                    Text(text = stringResource(R.string.detail_save))
                }
            }
        }
    }
}

/** 弹窗分节标题容器（「当前标签」「其他标签」共用结构） */
@Composable
private fun SheetTagSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = QimengDimens.SpaceL)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/** 弹窗空态提示（「暂无标签」，LEGACY §A:16） */
@Composable
private fun SheetEmptyHint() {
    Text(
        text = stringResource(R.string.detail_tag_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = QimengDimens.SpaceS),
    )
}
