package media.qimeng.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import media.qimeng.app.core.model.AlbumPanelDraft
import media.qimeng.app.core.model.AssetSort
import media.qimeng.app.core.model.PANEL_DEFAULT_YEAR_FROM
import media.qimeng.app.core.model.PANEL_MIN_YEAR
import media.qimeng.app.core.model.PanelCountRange
import media.qimeng.app.core.model.PanelDateRange
import media.qimeng.app.core.model.PanelSizeRange
import media.qimeng.app.core.model.PanelTagMode
import media.qimeng.app.core.model.SortOrder
import media.qimeng.app.core.ui.R
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 筛选面板「单选分区」区块（U10-3 自 QimengFilterSheet.kt 拆出使各文件回落 600 行警戒线内，
 * 同包 internal 协作、对外 API 不变、行为零变化）。
 */

/**
 * 单选行选项：值=领域枚举（业务）、标签=文案（strings.xml），组件内不做任何映射。
 */
data class QimengRadioOption<T>(val value: T, val label: String)

/**
 * 分区 = 标题 + 单选胶囊组（FlowRow 横向自动换行）。
 * U10-3 考古纠正：旧版单选组实为 ChipGroup+Chip Material 胶囊（旧仓库 MediaFilterSheet.kt:286-302
 * singleGroup：isSingleSelection + isSingleLine=false 多行换行），当年误读 uiautomator dump 的
 * class=RadioButton 而做成「圆圈行」；GUIDE_UI（旧仓库 docs L172）「与筛选药丸同一套胶囊语言：
 * 选中实底 primary/未选中软底 chipBg」。语义不变：selectable(role=RadioButton) 的无障碍语义与
 * 点选回调原样保留（任务 H1 口径），只换视觉容器。
 * 换行节距：横向 8dp（[QimengDimens.SpaceM]）/纵向 [FILTER_PILL_ROW_SPACING] 4dp——360dp 屏宽
 * 减面板左右 20dp 内容边距后，4 字档（76dp 胶囊+8dp 间距）一行约 4 个，与旧版实录密度一致。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> FilterSection(
    label: String,
    options: List<QimengRadioOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SectionLabel(label)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(FILTER_PILL_ROW_SPACING),
    ) {
        options.forEach { option ->
            FilterPill(
                label = option.label,
                selected = option.value == selected,
                onClick = { onSelect(option.value) },
            )
        }
    }
}

/**
 * 单选胶囊本体，规格逐项对齐旧版 QimengTagChip（GUIDE_UI「标签胶囊 Chip」）：12sp Regular、
 * 高 30dp（[QimengDimens.ChipHeight] 既有 token 只读引用）、水平内边距 14dp
 * （[QimengDimens.ChipHorizontalPadding]）、圆角 100dp（[QimengDimens.PillCornerRadius]）；
 * 选中=实底 primary + onPrimary 字，未选中=软底 secondaryContainer + onSurfaceVariant 字
 * （旧 qmColorChipBg→secondaryContainer 槽映射见 Theme.kt）。标签流 [TagChip] 同一胶囊语言，
 * 区别仅在长按删除手势，故不合并为一个组件。
 */
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QimengDimens.PillCornerRadius))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondaryContainer,
            )
            // role=RadioButton 语义保留（原整行 selectable 同款，任务 H1）；触区即胶囊本身，
            // 无 48dp 最小触达（selectable 无该下限）——旧版 Chip 恰为 30dp 高紧凑触区
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .height(QimengDimens.ChipHeight)
            // 水平内边距用面板局部常量（FILTER_PILL_HORIZONTAL_PADDING 10dp）：
            // 三桶行一行化拍板（2026-09-17），不再引用页面药丸共享的 ChipHorizontalPadding 14dp
            .padding(horizontal = FILTER_PILL_HORIZONTAL_PADDING),
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

// ---------- 时间范围（含按年份起止行） ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DateRangeSection(
    draft: AlbumPanelDraft,
    onDraftChange: (AlbumPanelDraft) -> Unit,
) {
    SectionLabel(stringResource(R.string.ui_filter_date_section))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(FILTER_PILL_ROW_SPACING),
    ) {
        dateRangeOptions().forEach { option ->
            FilterPill(
                label = option.label,
                selected = option.value == draft.dateRange,
                onClick = { onDateRangePicked(option.value, draft, onDraftChange) },
            )
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

// ---------- 选项文案表（实录逐字；值→协议映射在 core/model / :core:data，此处只陈列） ----------

// 排序方式三档/顺位二档文案：2026-09-17 用户拍板精简（原七档照旧版实录 filter_sheet.txt，
// 删文件日期/添加日期/点击次数/名字四档），只保留 默认/观看次数/文件大小，档序按用户口径
@Composable
internal fun sortOptions(): List<QimengRadioOption<AssetSort>> = listOf(
    QimengRadioOption(AssetSort.DEFAULT, stringResource(R.string.ui_filter_sort_default)),
    QimengRadioOption(AssetSort.VIEW_COUNT, stringResource(R.string.ui_filter_sort_view_count)),
    QimengRadioOption(AssetSort.SIZE_BYTES, stringResource(R.string.ui_filter_sort_file_size)),
)

@Composable
internal fun orderOptions(): List<QimengRadioOption<SortOrder>> = listOf(
    QimengRadioOption(SortOrder.DESC, stringResource(R.string.ui_filter_order_desc)),
    QimengRadioOption(SortOrder.ASC, stringResource(R.string.ui_filter_order_asc)),
)

@Composable
internal fun viewRangeOptions(): List<QimengRadioOption<PanelCountRange>> = listOf(
    QimengRadioOption(PanelCountRange.ALL, stringResource(R.string.ui_filter_range_all)),
    QimengRadioOption(PanelCountRange.NONE, stringResource(R.string.ui_filter_range_none_viewed)),
    QimengRadioOption(PanelCountRange.LOW, stringResource(R.string.ui_filter_range_low)),
    QimengRadioOption(PanelCountRange.MID, stringResource(R.string.ui_filter_range_mid)),
    QimengRadioOption(PanelCountRange.HIGH, stringResource(R.string.ui_filter_range_high)),
)

@Composable
internal fun playRangeOptions(): List<QimengRadioOption<PanelCountRange>> = listOf(
    QimengRadioOption(PanelCountRange.ALL, stringResource(R.string.ui_filter_range_all)),
    QimengRadioOption(PanelCountRange.NONE, stringResource(R.string.ui_filter_range_none_played)),
    QimengRadioOption(PanelCountRange.LOW, stringResource(R.string.ui_filter_range_low)),
    QimengRadioOption(PanelCountRange.MID, stringResource(R.string.ui_filter_range_mid)),
    QimengRadioOption(PanelCountRange.HIGH, stringResource(R.string.ui_filter_range_high)),
)

@Composable
internal fun sizeRangeOptions(): List<QimengRadioOption<PanelSizeRange>> = listOf(
    QimengRadioOption(PanelSizeRange.ALL, stringResource(R.string.ui_filter_range_all)),
    QimengRadioOption(PanelSizeRange.LT_1M, stringResource(R.string.ui_filter_size_lt_1m)),
    QimengRadioOption(PanelSizeRange.M_1_TO_10, stringResource(R.string.ui_filter_size_m1_10)),
    QimengRadioOption(PanelSizeRange.M_10_TO_50, stringResource(R.string.ui_filter_size_m10_50)),
    QimengRadioOption(PanelSizeRange.GT_50M, stringResource(R.string.ui_filter_size_gt_50m)),
)

@Composable
internal fun dateRangeOptions(): List<QimengRadioOption<PanelDateRange>> = listOf(
    QimengRadioOption(PanelDateRange.ALL, stringResource(R.string.ui_filter_range_all)),
    QimengRadioOption(PanelDateRange.TODAY, stringResource(R.string.ui_filter_date_today)),
    QimengRadioOption(PanelDateRange.WEEK, stringResource(R.string.ui_filter_date_week)),
    QimengRadioOption(PanelDateRange.MONTH, stringResource(R.string.ui_filter_date_month)),
    QimengRadioOption(PanelDateRange.QUARTER, stringResource(R.string.ui_filter_date_quarter)),
    QimengRadioOption(PanelDateRange.YEAR, stringResource(R.string.ui_filter_date_year)),
    QimengRadioOption(PanelDateRange.YEAR_RANGE, stringResource(R.string.ui_filter_date_year_range)),
)

@Composable
internal fun tagModeOptions(): List<QimengRadioOption<PanelTagMode>> = listOf(
    QimengRadioOption(PanelTagMode.FUZZY, stringResource(R.string.ui_filter_tag_mode_fuzzy)),
    QimengRadioOption(PanelTagMode.EXACT, stringResource(R.string.ui_filter_tag_mode_exact)),
)
