package media.qimeng.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.R
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 一条胶囊的数据与回调集合（共享胶囊语言：多页复用，禁止各页自绘一套）。
 *
 * @param text 显示文本（调用方决定是否带计数后缀——规格书约定计数不含「全部/收起」）
 * @param selected 选中态（实底主色 vs 软底）
 */
data class QimengPill(
    val text: String,
    val selected: Boolean = false,
)

/**
 * 单行横滑胶囊栏（首页三 tab / 分区胶囊 / 排行榜周期 / 排序组等一行放得下的场景）。
 *
 * @param dividerBeforeIndex 在第 index 个胶囊前插入竖分隔线（null=不插入；
 *   相册页芯片栏「角色 | 类型」间分隔线——旧版 fragment_all_files.xml L117-118，M4-2A-B2）
 */
@Composable
fun QimengChipRow(
    pills: List<QimengPill>,
    onPillClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    dividerBeforeIndex: Int? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        pills.forEachIndexed { index, pill ->
            if (index == dividerBeforeIndex) ChipDivider()
            PillChip(pill = pill, onClick = { onPillClick(index) })
        }
    }
}

/**
 * 词丸流（旧版实录 search_entry：推荐搜索/搜索历史 ChipGroup 换行药丸流，M4-2A-B4）。
 * 无「收起 ▲」尾丸、无折叠语义——搜索页两区词丸旧版全量平铺不折叠；
 * 胶囊渲染复用 [PillChip] 单源，禁各页自绘（铁律 7 / §5 组件单源）。
 * （任务 H1 清偿：原带「收起 ▲」折叠尾丸的 QimengPillFlowRow 全仓零调用已删除，
 * 折叠语义由 [QimengFloatingPillPanel]/[QimengValuePillFlow] 分承。）
 *
 * 词丸流间隙（BVIS 2026-09-09 勘正）：纵横 [QimengDimens.WordPillSpacing]/[QimengDimens.WordPillRowSpacing]
 * 均 8dp——旧实录 search_entry.txt 推荐词丸行位 404/528/652px@density3 → 行节距 124px=41.3dp，
 * 32dp 芯片 → 纵向间隙 ≈8dp；同帧枚缘 329→353px → 横向间隙 24px=8dp。F 批曾以「2dp 行距 =
 * 行节距 34dp 对齐实录」登记（把词丸间隙与胶囊输入框场高 34dp 两个数混淆），走查实测该档节距
 * 33.9dp 偏紧，本批按实录清偿；FilterChip 48dp 布局膨胀时代的 56dp 节距不复返（[QimengSegPill]
 * 紧凑化保持）。
 * 药丸容器间隙各随其实录：本组件 8dp、[QimengFloatingPillPanel] 4dp（all_partition_pills 实测）、
 * [QimengValuePillFlow] 保持 8dp（G5 Web 基准拍板保护）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QimengWordPillFlow(
    pills: List<QimengPill>,
    onPillClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.WordPillSpacing),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.WordPillRowSpacing),
    ) {
        pills.forEachIndexed { index, pill -> PillChip(pill = pill, onClick = { onPillClick(index) }) }
    }
}

/**
 * 悬浮药丸面板（旧版 fragment_all_files.xml 药丸容器形态，M4-2A-B2）：悬浮在网格上方
 * （elevation 4dp + 页面底色，不推挤网格——旧版 FrameLayout 叠放）、最大高度=屏高一半
 * （旧版 MaxHeightScrollView.onMeasure：heightPixels/2 的 AT_MOST 语义）超出内部滚动、
 * 药丸 FlowRow 自动换行、末尾固定「收起 ▲」、点药丸不收起。
 * 折叠时调用方不组合本组件（collapsed=true 渲染 null）。
 * 药丸间隙纵横 [QimengDimens.FloatingPillPanelSpacing]=4dp（BVIS：旧实录 all_partition_pills.txt
 * 行位 466/568px@density3 → 行节距 102px=34dp，30dp 芯片 → 间隙 4dp；此前复用 SpaceM=8dp
 * 实测节距 40dp 偏松，本批清偿）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QimengFloatingPillPanel(
    pills: List<QimengPill>,
    onPillClick: (index: Int) -> Unit,
    collapsed: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (collapsed) return
    // 旧版 MaxHeightScrollView 逐字口径：最大高度 = 屏幕像素高的一半（无旧版 dp 常量，按屏推导）
    val halfScreenHeight = LocalConfiguration.current.screenHeightDp.dp / 2
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = halfScreenHeight)
            .shadow(elevation = QimengDimens.PillsPanelElevation)
            .background(MaterialTheme.colorScheme.background)
            .padding(
                horizontal = QimengDimens.ScreenPaddingHorizontal,
                vertical = QimengDimens.SpaceXS,
            )
            .verticalScroll(rememberScrollState()),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(QimengDimens.FloatingPillPanelSpacing),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.FloatingPillPanelSpacing),
        ) {
            pills.forEachIndexed { index, pill -> PillChip(pill = pill, onClick = { onPillClick(index) }) }
            PillChip(
                pill = QimengPill(text = stringResource(R.string.ui_pill_collapse), selected = false),
                onClick = onCollapse,
            )
        }
    }
}

/**
 * 芯片行竖分隔线（旧版 fragment_all_files.xml L117-118：1dp × 18dp；色=qmColorDivider→outlineVariant）。
 * 任务 H1：自绘 Box 换 M3 [VerticalDivider] 标准件（尺寸/颜色 token 逐项不变）。
 */
@Composable
private fun ChipDivider() {
    VerticalDivider(
        thickness = QimengDimens.DividerThickness,
        modifier = Modifier.height(QimengDimens.DividerHeight),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 内联药丸值区块（任务G G5）：候选值胶囊进文档流排布——展开时推挤下方内容，
 * 不悬浮遮挡网格（相册页对齐 Web AlbumsPage .value-row 形态，替代悬浮面板的呈现容器）。
 * 与 [QimengFloatingPillPanel] 的差别：
 * - 进文档流（调用方放进页面 Column，网格自然下移）而非 Box 叠放；
 * - 无「收起 ▲」尾丸——整块显隐由调用方控制（相册页维度芯片行的 D3 拍板语义），
 *   区块内的「展开 ⌄/收起 ⌃」两行钳制切换钮也由调用方按阈值渲染；
 * - [maxLines] 行数钳制（默认不限）：对齐 Web .value-row 收起态 max-height 两行的视觉语义，
 *   用「行数」而非 dp 表达——dp 需按字号/内边距换算，行数与 Web 交互语义一一对应。
 * 胶囊渲染复用 [PillChip] 单源（铁律 7：禁止各页自绘胶囊）。
 * 悬浮面板本体保留：收藏/历史页仍在用（G5 只改相册页接线）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QimengValuePillFlow(
    pills: List<QimengPill>,
    onPillClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        maxLines = maxLines,
        overflow = FlowRowOverflow.Clip,
    ) {
        pills.forEachIndexed { index, pill -> PillChip(pill = pill, onClick = { onPillClick(index) }) }
    }
}

/** 胶囊本体：选中实底主色/未选中软底（旧版 QimengCapsuleChip 的 M3 token 翻译）。
 *  渲染委托 [QimengSegPill]（G6 查重收敛：FilterChip 替身与本组件视觉语义完全同谱，
 *  单枚胶囊渲染单源，视觉与行为零变化）。 */
@Composable
private fun PillChip(pill: QimengPill, onClick: () -> Unit) {
    QimengSegPill(
        text = pill.text,
        selected = pill.selected,
        onClick = onClick,
    )
}
