package media.qimeng.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
 * 药丸容器（旧版 GUIDE_UI §药丸容器）：自动换行 + 末尾「收起 ▲」；折叠 = 完全隐藏、无摘要行
 * （折叠时调用方不渲染本组件即可——为显式表达该语义，collapsed=true 时这里渲染 null）。
 * 多选不退出：点胶囊只回调 onPillClick，不收起；仅「收起 ▲」触发 onCollapse。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QimengPillFlowRow(
    pills: List<QimengPill>,
    onPillClick: (index: Int) -> Unit,
    collapsed: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (collapsed) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        pills.forEachIndexed { index, pill -> PillChip(pill = pill, onClick = { onPillClick(index) }) }
        PillChip(
            pill = QimengPill(text = stringResource(R.string.ui_pill_collapse), selected = false),
            onClick = onCollapse,
        )
    }
}

/**
 * 悬浮药丸面板（旧版 fragment_all_files.xml 药丸容器形态，M4-2A-B2）：悬浮在网格上方
 * （elevation 4dp + 页面底色，不推挤网格——旧版 FrameLayout 叠放）、最大高度=屏高一半
 * （旧版 MaxHeightScrollView.onMeasure：heightPixels/2 的 AT_MOST 语义）超出内部滚动、
 * 药丸 FlowRow 自动换行、末尾固定「收起 ▲」、点药丸不收起。
 * 折叠时调用方不组合本组件（与 [QimengPillFlowRow] 同语义，collapsed=true 渲染 null）。
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
            horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        ) {
            pills.forEachIndexed { index, pill -> PillChip(pill = pill, onClick = { onPillClick(index) }) }
            PillChip(
                pill = QimengPill(text = stringResource(R.string.ui_pill_collapse), selected = false),
                onClick = onCollapse,
            )
        }
    }
}

/** 芯片行竖分隔线（旧版 fragment_all_files.xml L117-118：1dp × 18dp；色=qmColorDivider→outlineVariant） */
@Composable
private fun ChipDivider() {
    Box(
        modifier = Modifier
            .width(QimengDimens.DividerThickness)
            .height(QimengDimens.DividerHeight)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 胶囊本体：选中实底主色/未选中软底（旧版 QimengCapsuleChip 的 M3 token 翻译） */
@Composable
private fun PillChip(pill: QimengPill, onClick: () -> Unit) {
    val shape = RoundedCornerShape(QimengDimens.PillCornerRadius)
    val background =
        if (pill.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (pill.selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = pill.text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (pill.selected) FontWeight.SemiBold else FontWeight.Normal,
        color = contentColor,
        modifier = Modifier
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(
                horizontal = QimengDimens.ChipHorizontalPadding,
                vertical = QimengDimens.SpaceS,
            ),
    )
}
