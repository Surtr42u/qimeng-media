package media.qimeng.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 胶囊圆角（旧版 GUIDE_UI §UI 约束：胶囊 100dp 圆角语义 → Compose 侧用大圆角近似） */
private val PILL_CORNER_RADIUS = 100.dp

/** 胶囊纵向内边距（旧版 30dp 高胶囊的近似档） */
private val PILL_VERTICAL_PADDING = 6.dp

/** 胶囊横向内边距（旧版 14dp 同值） */
private val PILL_HORIZONTAL_PADDING = 14.dp

/** 胶囊间间距 */
private val PILL_SPACING = 8.dp

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
 */
@Composable
fun QimengChipRow(
    pills: List<QimengPill>,
    onPillClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PILL_SPACING),
    ) {
        pills.forEachIndexed { index, pill -> PillChip(pill = pill, onClick = { onPillClick(index) }) }
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
        horizontalArrangement = Arrangement.spacedBy(PILL_SPACING),
        verticalArrangement = Arrangement.spacedBy(PILL_SPACING),
    ) {
        pills.forEachIndexed { index, pill -> PillChip(pill = pill, onClick = { onPillClick(index) }) }
        PillChip(
            pill = QimengPill(text = COLLAPSE_LABEL, selected = false),
            onClick = onCollapse,
        )
    }
}

/** 「收起 ▲」固定文案（旧版 §药丸容器 收起药丸语言） */
private const val COLLAPSE_LABEL = "收起 ▲"

/** 胶囊本体：选中实底主色/未选中软底（旧版 QimengCapsuleChip 的 M3 token 翻译） */
@Composable
private fun PillChip(pill: QimengPill, onClick: () -> Unit) {
    val shape = RoundedCornerShape(PILL_CORNER_RADIUS)
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
            .padding(horizontal = PILL_HORIZONTAL_PADDING, vertical = PILL_VERTICAL_PADDING),
    )
}
