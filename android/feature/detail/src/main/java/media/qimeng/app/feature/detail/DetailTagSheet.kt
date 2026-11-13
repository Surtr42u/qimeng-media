package media.qimeng.app.feature.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.icon.ClearIcon
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

// ---------- 页面私有尺寸/字号档（本文件单源；来源注释随条目） ----------

/** 「当前标签」勾选移除图标边长（chip 内随行小图标档） */
private val TAG_CLEAR_ICON_SIZE = 16.dp

/** 保存按钮内嵌指示器直径单源到 QimengDimens（V8 #5：16dp 强缩失衡 → 对齐 labelLarge 文字行高） */
private val TAG_SAVE_PROGRESS_SIZE = QimengDimens.ButtonLoadingIndicatorSize

/** 弹层标题字号 18sp（旧 MediaDetailFragment.kt:1258-1265 标签弹窗标题，Bold+居中同源） */
private val TAG_SHEET_TITLE_TEXT_SIZE = 18.sp

/** 分节标题字号 13sp（旧 TagSheetHelper.kt:160-165 分节头） */
private val TAG_SECTION_TITLE_TEXT_SIZE = 13.sp

/** ＋ 新建钮宽 52dp（U10-2 D8 拍板：旧版视觉「＋」实底主色圆钮） */
private val TAG_ADD_BUTTON_WIDTH = 52.dp

/** ＋ 钮与输入框间距 10dp（U10-2 D8 拍板；现成间距档 8/12dp 均不符，不硬凑） */
private val TAG_ADD_ROW_SPACING = 10.dp

/** 添加行顶距 8dp（U10-2b 回收垂直空隙：旧 inputRow setPadding(0,8,0,12) 顶值） */
private val TAG_ADD_ROW_TOP_SPACING = 8.dp

/** 弹层内容顶距 18dp（U10-2b 回收副标题删除后的空隙：旧 MediaDetailFragment.kt:1254
 *  sheetContainer setPadding 顶 18dp——与横向 20dp 同源同处） */
private val TAG_SHEET_CONTENT_TOP_PADDING = 18.dp

/** 标签池采用较易点选的 36dp 最小高度，文字放大但不随选中改变尺寸。 */
private val TAG_POOL_CHIP_HEIGHT = 36.dp
private val TAG_POOL_CHIP_TEXT_SIZE = 14.sp

/** 当前标签固定预留约两行，更多内容内部滚动，不挤动下方候选池。 */
private val TAG_CURRENT_TAGS_HEIGHT = 76.dp

/** 仅对颜色作短过渡，选中时不改变标签尺寸或位置。 */
private const val TAG_SELECTION_TRANSITION_MS = 120

/** 弹层保持固定高度，避免勾选摘要增长使底部锚定的整个弹层上移。 */
private const val TAG_SHEET_HEIGHT_FRACTION = 0.8f

/** 底部「取消/保存」按钮区顶距 18dp（U10-2 D9 拍板：旧版弹窗按钮区间距） */
private val TAG_FOOTER_TOP_SPACING = 18.dp

/**
 * 标签管理：当前标签置顶并固定内容区高度，全量候选池在其下独立滚动。
 * 选中只改变边框与底色，无勾选图标和占位；摘要 × 仍立即解绑已有
 * 关联，普通点选仍为保存前草稿。新建成功后清空输入，底部取消/保存沿用原流程。
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf("") }
    // D11：dragHandle=null 对齐旧版弹窗无把手形态
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(TAG_SHEET_HEIGHT_FRACTION)
                // U10-2b：顶距 18dp=旧 sheetContainer setPadding 顶值（TAG_SHEET_CONTENT_TOP_PADDING
                // 记档）；副标题说明行已删（用户反馈「标签间隔太多」——旧版弹窗无此行）
                .padding(top = TAG_SHEET_CONTENT_TOP_PADDING)
                // D7：弹层横向 20dp=旧 MediaDetailFragment.kt:1254 sheetContainer（token 见
                // QimengDimens.DetailSheetPaddingHorizontal，不与筛选面板 20dp 同源混用）
                .padding(horizontal = QimengDimens.DetailSheetPaddingHorizontal)
                // 底部呼吸空间与页面级留白同档（DETAIL_BOTTOM_SPACER 单源在 DetailScreen.kt）
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = QimengDimens.SpaceL),
            ) {
                Text(
                    text = stringResource(R.string.detail_tag_sheet_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = TAG_SHEET_TITLE_TEXT_SIZE,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.detail_tag_selected_count, selectedTagIds.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SheetCurrentTags(
                pool = pool,
                selectedTagIds = selectedTagIds,
                onUnbindTag = onUnbindTag,
            )
            Spacer(modifier = Modifier.height(QimengDimens.SpaceL))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SheetTagPool(
                    pool = pool,
                    selectedTagIds = selectedTagIds,
                    onToggleTag = onToggleTag,
                )
                SheetAddTagRow(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onCreateTag = onCreateTag,
                )
            }
            SheetFooterButtons(
                savingTags = savingTags,
                onSave = onSave,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * 「当前标签」分节：实底主色 chip（selected=true，D1 旧 TagSheetHelper.kt:74-76 反白语义），
 * 关闭图标=立即 DELETE 单条解绑（N4 I7b；N3 #32 逐条删端点，失败由 VM 回滚乐观态+横幅）；
 * LEGACY §A:17「删除只解除本文件关联」语义不变——解绑不动标签池本体。空态=「暂无标签」。
 */
@Composable
private fun SheetCurrentTags(
    pool: List<TagChip>,
    selectedTagIds: List<String>,
    onUnbindTag: (String) -> Unit,
) {
    SheetTagSection(title = stringResource(R.string.detail_tag_section_current)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(TAG_CURRENT_TAGS_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            val selectedChips = pool.filter { it.id in selectedTagIds }
            if (selectedChips.isEmpty()) {
                SheetEmptyHint()
            } else {
                // 2026-09-15 用户反馈「标签间距依旧没修复」收紧：横向 8→6dp（SpaceS）、
                // 纵向 4→2dp（SpaceXXS）——U10-2b 只删了说明行未动 chip 间隙，本批落地
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
                    verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXXS),
                ) {
                    selectedChips.forEach { chip ->
                        DisplayPill(
                            text = chip.name,
                            selected = true,
                            trailing = {
                                Icon(
                                    imageVector = ClearIcon,
                                    contentDescription = stringResource(R.string.detail_tag_remove_selection),
                                    tint = MaterialTheme.colorScheme.onPrimary,
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
    }
}

/** 全量池保持名称展示顺序；点选只改变颜色和描边，不移走标签或改变尺寸。 */
@Composable
private fun SheetTagPool(
    pool: List<TagChip>,
    selectedTagIds: List<String>,
    onToggleTag: (String) -> Unit,
) {
    if (pool.isEmpty()) {
        return
    }
    SheetTagSection(title = stringResource(R.string.detail_tag_section_pool)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
        ) {
            pool.forEach { chip ->
                key(chip.id) {
                    val selected = chip.id in selectedTagIds
                    val background by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        animationSpec = tween(TAG_SELECTION_TRANSITION_MS),
                        label = "tagBackground",
                    )
                    val foreground by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        animationSpec = tween(TAG_SELECTION_TRANSITION_MS),
                        label = "tagForeground",
                    )
                    Surface(
                        shape = RoundedCornerShape(QimengDimens.RankCardCornerRadius),
                        color = background,
                        border = BorderStroke(
                            QimengDimens.DividerThickness,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = TAG_POOL_CHIP_HEIGHT)
                                .toggleable(
                                    value = selected,
                                    role = Role.Checkbox,
                                    onValueChange = { onToggleTag(chip.id) },
                                )
                                .padding(
                                    horizontal = QimengDimens.SpaceL,
                                    vertical = QimengDimens.SpaceS,
                                ),
                        ) {
                            Text(
                                text = chip.name,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = TAG_POOL_CHIP_TEXT_SIZE,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = foreground,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 新建输入框与「添加」按钮；成功回调才清空输入，失败保留重试。 */
@Composable
private fun SheetAddTagRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    onCreateTag: (name: String, onCreated: () -> Unit) -> Unit,
) {
    val addDesc = stringResource(R.string.detail_tag_add_desc)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TAG_ADD_ROW_TOP_SPACING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAG_ADD_ROW_SPACING),
    ) {
        QimengCapsuleTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = stringResource(R.string.detail_tag_new_placeholder),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Surface(
            onClick = { onCreateTag(draft) { onDraftChange("") } },
            enabled = draft.isNotBlank(),
            shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(TAG_ADD_BUTTON_WIDTH)
                .height(QimengDimens.CapsuleFieldHeight)
                .semantics { contentDescription = addDesc },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.detail_tag_create),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * 底部「取消/保存」双钮（NAS 草稿整体替换模型的有意保留项，不回退旧版单条即时写；顶距
 * 对齐 [TAG_FOOTER_TOP_SPACING]）。保存中双钮禁用防重（saving 转圈嵌在保存钮内）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SheetFooterButtons(
    savingTags: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TAG_FOOTER_TOP_SPACING),
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

/** 弹窗分节标题容器（「当前标签」「选择标签」共用结构；标题 13sp Regular 次级灰，D6 旧 TagSheetHelper.kt:160-165）。
 *  节顶距不设（U10-2b 移除原 SpaceL 12dp）：节间距回到「分节标题自带上下 6dp padding」语义
 *  ——旧版节间即 6dp（TagSheetHelper.kt:160-165），弹层内容顶距由外层 18dp 承担 */
@Composable
private fun SheetTagSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = TAG_SECTION_TITLE_TEXT_SIZE,
                fontWeight = FontWeight.Normal,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = QimengDimens.SpaceS),
        )
        content()
    }
}

/** 弹窗空态提示（「暂无标签」，LEGACY §A:16）。样式=分节标题同款（U10-2b：旧版空态即
 *  13sp 次级灰，原 bodyMedium 偏大偏松） */
@Composable
private fun SheetEmptyHint() {
    Text(
        text = stringResource(R.string.detail_tag_empty),
        style = MaterialTheme.typography.titleSmall.copy(
            fontSize = TAG_SECTION_TITLE_TEXT_SIZE,
            fontWeight = FontWeight.Normal,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = QimengDimens.SpaceS),
    )
}
