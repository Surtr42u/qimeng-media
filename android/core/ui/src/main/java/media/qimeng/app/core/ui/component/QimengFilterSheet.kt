package media.qimeng.app.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.model.AlbumPanelDraft
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.ui.R
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 面板排版字号/节距常量（U10-3 观感对齐；值全部来自旧仓库，禁止散落裸写）。
// 拆分文件（QimengFilterSheetSections/Tags.kt）同包同模块共用，故 internal 而非 private ----------

/** 面板标题字号 18sp（旧仓库 MediaFilterSheet.kt:307 headerLabel textSize=18f + DEFAULT_BOLD 居中） */
internal val FILTER_SHEET_TITLE_FONT_SIZE = 18.sp

/** 分区标题字号 12sp（旧仓库 MediaFilterSheet.kt:315 section textSize=12f + DEFAULT_BOLD + textSecondary） */
internal val FILTER_SHEET_SECTION_FONT_SIZE = 12.sp

/** 底部「重置/应用筛选」按钮字号 15sp（旧仓库 MediaFilterSheet.kt:325 actionButton textSize=15f + DEFAULT_BOLD） */
internal val FILTER_SHEET_BUTTON_FONT_SIZE = 15.sp

/** 面板胶囊（单选选项/标签流）字号 12sp（旧仓库 styles.xml QimengTagChip textSize=12sp） */
internal val FILTER_PILL_FONT_SIZE = 12.sp

/** 分区胶囊流纵向节距 4dp（横向=SpaceM 8dp；U10-3 任务书拍板值，对齐旧版 ChipGroup 换行密度） */
internal val FILTER_PILL_ROW_SPACING = 4.dp

/**
 * 万能筛选面板（M4-2A-B3）：全项目唯一实现于 :core:ui（任务A §5.2 组件单源），
 * 分区标题与选项文案逐字照旧版 uiautomator 实录（filter_sheet.txt / filter_sheet_rest.txt）。
 *
 * 编辑态语义（旧版 MediaFilterSheet 口径）：打开=调用方已拷贝当前已应用值为 [draft]；
 * 面板内每次点选只回调 [onDraftChange]（改草稿不改已应用态）；「应用筛选」=[onApply]；
 * 「重置」=[onReset]（草稿回默认后立即应用并关面板——旧版三合一口径，旧仓库
 * MediaFilterSheet.kt L266-269「dismiss+apply(默认)」实录，修复轮 P1-1 对齐）；
 * 下滑/点外部关闭=[onDismiss]（丢弃草稿）。
 * [message]=面板内操作反馈行（修复轮 P2-1：标签重名/操作失败分流文案，调用方经 strings.xml
 * 落地后传入，null=不显示）。
 * 组件无状态、无业务规则（铁律 7）：过滤/映射语义全部在调用方与 core/model。
 *
 * U10-3 观感对齐旧版：① dragHandle=null——旧版 BottomSheetDialog（MediaFilterSheet.kt:59）
 * 无把手，M3 默认把手是旧版没有的多余元素；② 单选组从 RadioButton 圆圈行纠正为 Chip 胶囊
 * （考古见 QimengFilterSheetSections.kt FilterPill KDoc）；③ 标题/分区/底部按钮字号对齐旧版
 * 18/12/15sp（见文件顶部常量）。文件超 600 行警戒线后拆分：单选分区=QimengFilterSheetSections.kt、
 * 标签流=QimengFilterSheetTags.kt（同包 internal 协作，对外 API 不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QimengFilterSheet(
    draft: AlbumPanelDraft,
    tags: List<TagSummary>,
    message: String?,
    onDraftChange: (AlbumPanelDraft) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onAddTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 跳过半开档直接全开：旧版 BottomSheetDialog 打开即固定 62% 屏高（实录 filter_sheet.txt
    // 首帧 sheet 顶就位），半开档会让面板文案只露出一半，与实录形态不符
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // U10-3：旧版 BottomSheetDialog 无把手视图，去掉 M3 默认顶部拖拽把手（下滑关闭手势仍保留）
        dragHandle = null,
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            // 滚动区高度封顶=旧版 62% 屏高（show(): scroll LayoutParams heightPixels*0.62f），
            // 超出内部滚动、底部按钮栏恒定可见（实录两按钮在 ScrollView 之外）
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * QimengDimens.FilterSheetHeightFraction)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = QimengDimens.FilterSheetPaddingHorizontal),
            ) {
                SheetTitle()
                // 排序方式/顺位两段（任务L L4 从 git 历史 5536d79^ 恢复，结构为本仓自己的
                // Compose 实现）：G5 曾按「排序提页头」删除面板排序段，L4 按用户拍板删除页头
                // 四档行、排序唯一编辑入口回归面板——两段位置/档序/文案逐字照旧版实录
                // filter_sheet.txt（标题下首两段：排序方式七档 → 顺位二档），映射既有
                // AlbumPanelDraft.sort/order（panelDraft/withPanelDraft 原样携带，零协议改动）。
                FilterSection(
                    label = stringResource(R.string.ui_filter_sort_section),
                    options = sortOptions(),
                    selected = draft.sort,
                    onSelect = { onDraftChange(draft.copy(sort = it)) },
                )
                FilterSection(
                    label = stringResource(R.string.ui_filter_order_section),
                    options = orderOptions(),
                    selected = draft.order,
                    onSelect = { onDraftChange(draft.copy(order = it)) },
                )
                FilterSection(
                    label = stringResource(R.string.ui_filter_view_section),
                    options = viewRangeOptions(),
                    selected = draft.viewRange,
                    onSelect = { onDraftChange(draft.copy(viewRange = it)) },
                )
                FilterSection(
                    label = stringResource(R.string.ui_filter_play_section),
                    options = playRangeOptions(),
                    selected = draft.playRange,
                    onSelect = { onDraftChange(draft.copy(playRange = it)) },
                )
                FilterSection(
                    label = stringResource(R.string.ui_filter_size_section),
                    options = sizeRangeOptions(),
                    selected = draft.sizeRange,
                    onSelect = { onDraftChange(draft.copy(sizeRange = it)) },
                )
                DateRangeSection(draft = draft, onDraftChange = onDraftChange)
                FilterSection(
                    label = stringResource(R.string.ui_filter_tag_mode_section),
                    options = tagModeOptions(),
                    selected = draft.tagMode,
                    onSelect = { onDraftChange(draft.copy(tagMode = it)) },
                )
                TagsSection(
                    tags = tags,
                    selectedIds = draft.tagIds,
                    onToggleTag = { id ->
                        // 点选=切换选中；多选不关面板（草稿语义，与旧版一致）
                        val next = if (id in draft.tagIds) draft.tagIds - id else draft.tagIds + id
                        onDraftChange(draft.copy(tagIds = next))
                    },
                    onDeleteTag = onDeleteTag,
                    onAddTag = onAddTag,
                )
            }
            PanelFeedbackLine(text = message)
            FilterFooter(onReset = onReset, onApply = onApply)
        }
    }
}

// ---------- 分区骨架 ----------

/** 面板内操作反馈行（修复轮 P2-1）：重名/失败分流的专用文案显示在 footer 上方（旧版为 Toast；
 * 页面顶部列表错误行被面板遮罩盖住，故面板内独立一条，不复用其文案）；null=不显示 */
@Composable
private fun PanelFeedbackLine(text: String?) {
    text?.let { feedbackText ->
        Text(
            text = feedbackText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QimengDimens.FilterSheetPaddingHorizontal)
                .padding(top = QimengDimens.SpaceS),
        )
    }
}

/** 面板标题「筛选」（旧版 headerLabel：18sp Bold 居中，MediaFilterSheet.kt:304-311；fillMaxWidth 使 dump 节点为通栏，与旧 TextView 同形） */
@Composable
private fun SheetTitle() {
    Text(
        text = stringResource(R.string.ui_filter_title),
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = FILTER_SHEET_TITLE_FONT_SIZE,
            fontWeight = FontWeight.Bold,
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = QimengDimens.SpaceXS,
                bottom = QimengDimens.FilterTitleBottomPadding,
            ),
    )
}

/** 分区标题（旧版 section：12sp Bold 次级文字色通栏横幅，MediaFilterSheet.kt:313-319——实录标题横贯 [60,1056]，fillMaxWidth 同口径） */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FILTER_SHEET_SECTION_FONT_SIZE,
            fontWeight = FontWeight.Bold,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = QimengDimens.FilterSectionTopSpacing,
                bottom = QimengDimens.SpaceXS,
            ),
    )
}

// ---------- 底部按钮栏 ----------

/** 底部固定两按钮（实录逐字：左半宽「重置」软底、右半宽「应用筛选」实底；在滚动区外恒可见） */
@Composable
private fun FilterFooter(onReset: () -> Unit, onApply: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = QimengDimens.FilterSheetPaddingHorizontal,
                end = QimengDimens.FilterSheetPaddingHorizontal,
                top = QimengDimens.SpaceL,
                bottom = QimengDimens.FilterFooterBottomPadding,
            ),
    ) {
        SheetButton(
            text = stringResource(R.string.ui_filter_reset),
            filled = false,
            onClick = onReset,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(QimengDimens.SpaceM))
        SheetButton(
            text = stringResource(R.string.ui_filter_apply),
            filled = true,
            onClick = onApply,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 面板操作按钮（旧版 actionButton：胶囊语言，高 48dp，实底主色/软底次级）。
 * 任务 H1：手绘 Box 换 M3 [Button] 标准件——实底档用默认主色组、软底档覆盖 surfaceVariant；
 * 旧版按钮平面无投影（实录 dump 无 elevation 表现），压平 M3 默认投影。
 * U10-3：文字 15sp Bold 对齐旧版 actionButton（MediaFilterSheet.kt:325）。
 */
@Composable
private fun SheetButton(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(QimengDimens.FilterButtonHeight),
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        colors = if (filled) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        // fillMaxWidth+居中：旧版按钮即整宽 TextView（实录「重置」节点 [60,2264][534,2408] 通栏），
        // 文本节点与按钮同宽，dump 形态与旧版一致
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = FILTER_SHEET_BUTTON_FONT_SIZE,
                fontWeight = FontWeight.Bold,
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
