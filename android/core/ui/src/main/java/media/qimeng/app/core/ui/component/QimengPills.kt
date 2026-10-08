package media.qimeng.app.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 折叠语义由 [QimengValuePillFlow] 及其容器 [QimengValuePillBlock] 分承。）
 *
 * 词丸流间隙（BVIS 2026-09-09 勘正）：纵横 [QimengDimens.WordPillSpacing]/[QimengDimens.WordPillRowSpacing]
 * 均 8dp——旧实录 search_entry.txt 推荐词丸行位 404/528/652px@density3 → 行节距 124px=41.3dp，
 * 32dp 芯片 → 纵向间隙 ≈8dp；同帧枚缘 329→353px → 横向间隙 24px=8dp。F 批曾以「2dp 行距 =
 * 行节距 34dp 对齐实录」登记（把词丸间隙与胶囊输入框场高 34dp 两个数混淆），走查实测该档节距
 * 33.9dp 偏紧，本批按实录清偿；FilterChip 48dp 布局膨胀时代的 56dp 节距不复返（[QimengSegPill]
 * 紧凑化保持）。
 * 药丸容器间隙各随其实录：本组件 8dp、[QimengValuePillFlow] 6dp/4dp（U10-2b/7 改：G5 Web 基准让位
 * 旧版实录，见常量注释）。旧悬浮面板形态（QimengFloatingPillPanel，FrameLayout 叠放）已随
 * 2026-09-15 批「三页胶囊统一 in-flow」全仓零调用删除（删除先例：任务 H1 删 QimengPillFlowRow）。
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

// ---------- 值区块钳制规格（2026-09-15 批自相册页私有常量收编为三页单源；文档随迁） ----------

/**
 * 值区块收起态钳制阈值：候选值超过该数量（含「全部」胶囊）默认收起两行——对齐 Web 值行
 * 交互阈值 VALUE_COLLAPSE_THRESHOLD=9（web/src/pages/AlbumsPage.tsx，原型交互阈值），任务G G5。
 */
private const val VALUE_BLOCK_COLLAPSE_THRESHOLD = 9

/** 值区块收起态行数——对齐 Web .value-row 收起态 max-height:66px（两行胶囊+行距，
 *  web/src/styles/prototype.css）；以「行数」表达与胶囊实际高度解耦，视觉≈两行药丸 */
private const val VALUE_BLOCK_COLLAPSED_LINES = 2

/**
 * 值区块展开态限高 = 屏高 ÷ 本除数（U10-2b/7，2026-09-14 用户反馈「旧版点开展示一部分，
 * 超过可以往下滑」）：复刻旧版 MaxHeightScrollView.kt:15-18 onMeasure
 * displayMetrics.heightPixels/2 的 AT_MOST 语义——最多半屏、超出纵向滚动。Compose 侧以
 * screenHeightDp/2 的 dp 近似（原实现按原始像素测量，不含密度换算，语义同为「半屏」）。
 */
private const val VALUE_BLOCK_MAX_HEIGHT_DIVISOR = 2

/**
 * 文档流「值区块」容器（任务G G5 首创于相册页；2026-09-15 批用户反馈「收藏和浏览记录的
 * 胶囊没和相册的对齐」，抽为相册/收藏/浏览历史三页单源，替代各页独立实现与已删除的
 * 悬浮面板 QimengFloatingPillPanel——悬浮形态退役，三页呈现完全一致）：
 * - 进文档流推挤网格（非叠放遮挡），对齐 Web AlbumsPage .value-row 形态；
 * - 收起态钳制 [VALUE_BLOCK_COLLAPSED_LINES] 行；候选超 [VALUE_BLOCK_COLLAPSE_THRESHOLD]
 *   出现「展开 ⌄/收起 ⌃」切换钮（文案与 Web expand-btn 逐字一致含箭头符）；
 * - 展开态限高半屏+纵向滚动（U10-2b/7 旧版 MaxHeightScrollView 行为复刻）。
 * 整块显隐由调用方门控（filter.expanded 的 D3 拍板语义：进页默认收起/点已激活维 toggle/
 * 切维展开），本组件只管「显出后钳几行」。
 *
 * @param pills 候选值胶囊（渲染复用 [QimengValuePillFlow]→[PillChip] 单源）
 * @param onPillClick 胶囊点击（index 对应 [pills] 下标）
 * @param resetKey 两行钳制展开态的归位键——值变化（如切维度）即归位「收起两行」
 *   （相册/收藏/历史页传 state.activeDim，Web setDim 重置 expanded 同口径；
 *   旋转/进程重建经 rememberSaveable 存活）
 */
@Composable
fun QimengValuePillBlock(
    pills: List<QimengPill>,
    onPillClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
) {
    // 区块内「两行/全部」与调用方的 filter.expanded（整块显隐）是两层不同的展开
    var valuesExpanded by rememberSaveable(resetKey) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .padding(
                start = QimengDimens.ScreenPaddingHorizontal,
                end = QimengDimens.ScreenPaddingHorizontal,
                // Web .value-row margin-top 10px 的近似 token 档（8dp）
                top = QimengDimens.SpaceM,
            )
            .then(
                if (valuesExpanded) {
                    Modifier
                        .heightIn(max = LocalConfiguration.current.screenHeightDp.dp / VALUE_BLOCK_MAX_HEIGHT_DIVISOR)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                },
            ),
    ) {
        QimengValuePillFlow(
            pills = pills,
            onPillClick = onPillClick,
            // 收起=钳制两行（≈Web max-height 66px）；展开=全部值推挤网格
            maxLines = if (valuesExpanded) Int.MAX_VALUE else VALUE_BLOCK_COLLAPSED_LINES,
        )
        // 展开钮只在候选超阈值时出现（换到右下角并升级为精致微胶囊）
        if (pills.size > VALUE_BLOCK_COLLAPSE_THRESHOLD) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Surface(
                    shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(QimengDimens.PillCornerRadius))
                        .clickable { valuesExpanded = !valuesExpanded },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (valuesExpanded) R.string.ui_values_collapse else R.string.ui_values_expand,
                            ),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
 *   用「行数」而非 dp 表达——dp 需按字号/内边距换算，行数与 Web 交互语义一一对应；
 * - 展开态限高+纵向滚动由调用方容器承担（U10-2b/7 旧版 MaxHeightScrollView 复刻），
 *   本组件只管流式排布。
 * 药丸间隙=纵横异值 6dp/4dp（U10-2b/7：对齐旧版 FlowLayout.kt:18-19，G5 Web 8/8 基准退役；
 * 全仓唯一消费方=相册页，故不设参数直接改默认）。
 * 胶囊渲染复用 [PillChip] 单源（铁律 7：禁止各页自绘胶囊）。
 * 值区块容器统一走 [QimengValuePillBlock]（2026-09-15 批三页单源；G5 时期「悬浮面板本体保留、
 * 收藏/历史页仍在用」的分治口径随悬浮面板删除一并退役）。
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
        horizontalArrangement = Arrangement.spacedBy(VALUE_PILL_SPACING_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(VALUE_PILL_SPACING_VERTICAL),
        maxLines = maxLines,
        overflow = FlowRowOverflow.Clip,
    ) {
        pills.forEachIndexed { index, pill -> PillChip(pill = pill, onClick = { onPillClick(index) }) }
    }
}

/** 值药丸流水平间隙 6dp（U10-2b/7：旧版 FlowLayout.kt:18-19 hSpace=6） */
private val VALUE_PILL_SPACING_HORIZONTAL = 6.dp

/** 值药丸流垂直间隙 4dp（U10-2b/7：旧版 FlowLayout.kt:18-19 vSpace=4） */
private val VALUE_PILL_SPACING_VERTICAL = 4.dp

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
