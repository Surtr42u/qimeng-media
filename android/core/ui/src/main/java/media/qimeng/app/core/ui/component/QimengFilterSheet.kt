package media.qimeng.app.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import media.qimeng.app.core.model.AlbumPanelDraft
import media.qimeng.app.core.model.PanelCountRange
import media.qimeng.app.core.model.PanelDateRange
import media.qimeng.app.core.model.PanelSizeRange
import media.qimeng.app.core.model.PanelTagMode
import media.qimeng.app.core.model.PANEL_DEFAULT_YEAR_FROM
import media.qimeng.app.core.model.PANEL_MIN_YEAR
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.ui.R
import media.qimeng.app.core.ui.theme.QimengDimens

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
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                // 排序方式/顺位两段已移除（任务G G5）：排序 pill 行提到相册页头（四档，对齐 Web
                // AlbumsPage sort-row）后与本面板重复编辑同一 sort/order 状态——双入口档位集不一致
                // （面板七档+顺位 vs 页头四档）会让已选态在两处互相脱钩，按「单一编辑入口」收敛到页头；
                // 草稿仍携带当前 sort/order 原样往返（panelDraft/withPanelDraft 不动），应用面板其余
                // 筛选不会重置已选排序档。模型/协议零改动。
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
            message?.let { feedbackText ->
                // 面板内操作反馈（修复轮 P2-1）：重名/失败分流的专用文案显示在面板内 footer 上方
                // （旧版为 Toast；页面顶部列表错误行被面板遮罩盖住，故面板内独立一条，不复用其文案）
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
            FilterFooter(onReset = onReset, onApply = onApply)
        }
    }
}

// ---------- 分区骨架 ----------

/** 面板标题「筛选」（旧版 headerLabel：居中；fillMaxWidth 使 dump 节点为通栏，与旧 TextView 同形） */
@Composable
private fun SheetTitle() {
    Text(
        text = stringResource(R.string.ui_filter_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = QimengDimens.SpaceXS,
                bottom = QimengDimens.FilterTitleBottomPadding,
            ),
    )
}

/** 分区标题（旧版 section：次级文字色通栏横幅——实录标题横贯 [60,1056]，fillMaxWidth 同口径） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = QimengDimens.FilterSectionTopSpacing,
                bottom = QimengDimens.SpaceXS,
            ),
    )
}

/** 单选行选项：值=领域枚举（业务）、标签=文案（strings.xml），组件内不做任何映射 */
data class QimengRadioOption<T>(val value: T, val label: String)

/** 分区 = 标题 + 单选组（横排自动换行，实录单选项按行流动排布） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FilterSection(
    label: String,
    options: List<QimengRadioOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SectionLabel(label)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        options.forEach { option ->
            Row(
                // 任务 H1：clickable 换 foundation 标准 selectable（role=RadioButton）——
                // 无障碍语义与 RadioButton 状态联通，视觉零变化
                modifier = Modifier.selectable(
                    selected = option.value == selected,
                    role = Role.RadioButton,
                    onClick = { onSelect(option.value) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // onClick=null：去掉 48dp 最小触区包裹，圈紧凑化让每行多排一个选项
                // （旧版 ChipGroup 一行 4 个选项的换行密度）；点击语义由整行 selectable 承担
                RadioButton(
                    selected = option.value == selected,
                    onClick = null,
                )
                Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------- 时间范围（含按年份起止行） ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateRangeSection(
    draft: AlbumPanelDraft,
    onDraftChange: (AlbumPanelDraft) -> Unit,
) {
    SectionLabel(stringResource(R.string.ui_filter_date_section))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        dateRangeOptions().forEach { option ->
            Row(
                // selectable role=RadioButton 同 [FilterSection]（任务 H1 语义升级）
                modifier = Modifier.selectable(
                    selected = option.value == draft.dateRange,
                    role = Role.RadioButton,
                    onClick = { onDateRangePicked(option.value, draft, onDraftChange) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // onClick=null 同 [FilterSection]：紧凑圈 + 整行承担点击
                RadioButton(
                    selected = option.value == draft.dateRange,
                    onClick = null,
                )
                Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (draft.dateRange == PanelDateRange.YEAR_RANGE) {
        YearRangeRow(
            yearFrom = draft.yearFrom,
            yearTo = draft.yearTo,
            onYearFrom = { onDraftChange(draft.copy(yearFrom = it)) },
            onYearTo = { onDraftChange(draft.copy(yearTo = it)) },
        )
    }
}

/**
 * 选「按年份」时初始化起止年（P2-3 修正的旧版缺省口径镜像：旧仓库 MediaFilterState 缺省
 * yearStart=2020、yearEnd=当前年，NumberPicker value 取该缺省——即初值 2020..当前年，
 * 非双当前年）；起止大小不做面板内强校验——最终 yearFrom<=yearTo 由 core/model toAssetQuery
 * 交叉归一（旧版 buildFooter start=min/end=max 同口径），组件保持无业务规则。
 */
private fun onDateRangePicked(
    range: PanelDateRange,
    draft: AlbumPanelDraft,
    onDraftChange: (AlbumPanelDraft) -> Unit,
) {
    if (range == PanelDateRange.YEAR_RANGE) {
        val currentYear = LocalDate.now().year
        onDraftChange(
            draft.copy(
                dateRange = range,
                yearFrom = draft.yearFrom ?: PANEL_DEFAULT_YEAR_FROM,
                yearTo = draft.yearTo ?: currentYear,
            ),
        )
    } else {
        onDraftChange(draft.copy(dateRange = range))
    }
}

/** 年份起止行（旧版 NumberPicker 起始年—结束年 的官方 API 最小实现：两个下拉+破折号；不新增依赖） */
@Composable
private fun YearRangeRow(
    yearFrom: Int?,
    yearTo: Int?,
    onYearFrom: (Int) -> Unit,
    onYearTo: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = QimengDimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YearPicker(year = yearFrom, onPick = onYearFrom, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.ui_filter_year_dash),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = QimengDimens.SpaceM),
        )
        YearPicker(year = yearTo, onPick = onYearTo, modifier = Modifier.weight(1f))
    }
}

/**
 * 单个年份下拉（1990..当前年，旧版 NumberPicker min/max 口径；值未初始化时显示占位空串）。
 * 任务 H1：锚点从手绘 clip+background+clickable 药丸换 M3 可点击 [Surface] 标准件
 * （形状/颜色 token 逐项不变），下拉菜单本体 DropdownMenu（已是标准件）不动。
 */
@Composable
private fun YearPicker(year: Int?, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = year?.toString().orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = QimengDimens.SpaceS),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val maxYear = remember { LocalDate.now().year }
            (PANEL_MIN_YEAR..maxYear).forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(text = candidate.toString()) },
                    onClick = {
                        onPick(candidate)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ---------- 标签流 ----------

/** 标签流：按钮多选（选中实底/未选中软底，QimengPills 胶囊语言）+ 长按删除（确认框）+ 添加行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
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
 * 任务 H1 审查记档的**例外**（M3 芯片家族没有长按参数）：两轮标准件替换实测均破坏功能——
 * ① FilterChip 常态态 + 外层 combinedClickable：m3 1.4 芯片内层手势吞掉外层长按，
 * 且长按抬起被误转成点击（模拟器实证）；② FilterChip enabled=false 纯视觉化 + 外层
 * combinedClickable：连单击都到不了外层（m3 1.4 禁用 Surface 仍拦截手势节点，实测）。
 * 按任务书「以不破坏功能为前提」保留既有手绘胶囊（token 与 QimengSegPill 同谱，
 * 行为经前批次实测验证），待 M3 提供带长按的芯片标准件或内层手势可穿透后再收编。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(QimengDimens.PillCornerRadius))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                horizontal = QimengDimens.ChipHorizontalPadding,
                vertical = QimengDimens.SpaceS,
            ),
    )
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
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------- 选项文案表（实录逐字；值→协议映射在 core/model / :core:data，此处只陈列） ----------

@Composable
private fun viewRangeOptions(): List<QimengRadioOption<PanelCountRange>> = listOf(
    QimengRadioOption(PanelCountRange.ALL, stringResource(R.string.ui_filter_range_all)),
    QimengRadioOption(PanelCountRange.NONE, stringResource(R.string.ui_filter_range_none_viewed)),
    QimengRadioOption(PanelCountRange.LOW, stringResource(R.string.ui_filter_range_low)),
    QimengRadioOption(PanelCountRange.MID, stringResource(R.string.ui_filter_range_mid)),
    QimengRadioOption(PanelCountRange.HIGH, stringResource(R.string.ui_filter_range_high)),
)

@Composable
private fun playRangeOptions(): List<QimengRadioOption<PanelCountRange>> = listOf(
    QimengRadioOption(PanelCountRange.ALL, stringResource(R.string.ui_filter_range_all)),
    QimengRadioOption(PanelCountRange.NONE, stringResource(R.string.ui_filter_range_none_played)),
    QimengRadioOption(PanelCountRange.LOW, stringResource(R.string.ui_filter_range_low)),
    QimengRadioOption(PanelCountRange.MID, stringResource(R.string.ui_filter_range_mid)),
    QimengRadioOption(PanelCountRange.HIGH, stringResource(R.string.ui_filter_range_high)),
)

@Composable
private fun sizeRangeOptions(): List<QimengRadioOption<PanelSizeRange>> = listOf(
    QimengRadioOption(PanelSizeRange.ALL, stringResource(R.string.ui_filter_range_all)),
    QimengRadioOption(PanelSizeRange.LT_1M, stringResource(R.string.ui_filter_size_lt_1m)),
    QimengRadioOption(PanelSizeRange.M_1_TO_10, stringResource(R.string.ui_filter_size_m1_10)),
    QimengRadioOption(PanelSizeRange.M_10_TO_50, stringResource(R.string.ui_filter_size_m10_50)),
    QimengRadioOption(PanelSizeRange.GT_50M, stringResource(R.string.ui_filter_size_gt_50m)),
)

@Composable
private fun dateRangeOptions(): List<QimengRadioOption<PanelDateRange>> = listOf(
    QimengRadioOption(PanelDateRange.ALL, stringResource(R.string.ui_filter_range_all)),
    QimengRadioOption(PanelDateRange.TODAY, stringResource(R.string.ui_filter_date_today)),
    QimengRadioOption(PanelDateRange.WEEK, stringResource(R.string.ui_filter_date_week)),
    QimengRadioOption(PanelDateRange.MONTH, stringResource(R.string.ui_filter_date_month)),
    QimengRadioOption(PanelDateRange.QUARTER, stringResource(R.string.ui_filter_date_quarter)),
    QimengRadioOption(PanelDateRange.YEAR, stringResource(R.string.ui_filter_date_year)),
    QimengRadioOption(PanelDateRange.YEAR_RANGE, stringResource(R.string.ui_filter_date_year_range)),
)

@Composable
private fun tagModeOptions(): List<QimengRadioOption<PanelTagMode>> = listOf(
    QimengRadioOption(PanelTagMode.FUZZY, stringResource(R.string.ui_filter_tag_mode_fuzzy)),
    QimengRadioOption(PanelTagMode.EXACT, stringResource(R.string.ui_filter_tag_mode_exact)),
)
