package media.qimeng.app.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 四维胶囊筛选区（相册/收藏共用；历史页复用同一结构、维度子集）：维度芯片行 +
 * 当前维度的药丸容器（「全部」前置、「其他」置底、末尾「收起 ▲」）。
 * 交互语义（规格书 §药丸容器）：点维度芯片切换当前维度并默认展开；点已激活维度芯片 = 切换展开/折叠；
 * 药丸多选不退出、仅「收起 ▲」收起（折叠后点维度芯片再展开）。
 *
 * @param dimChips 维度芯片（label 已含数量后缀，调用方组装；数量不含「全部/收起」）
 * @param pills 当前维度的候选药丸（激活态已由调用方按 key+kind 判定）
 * @param onToggleExpand 折叠态下点「收起 ▲」与再点维度芯片的展开切换由调用方状态机翻转
 */
@Composable
fun QimengFourDimSection(
    dimChips: List<QimengPill>,
    activeDimIndex: Int,
    onDimClick: (index: Int) -> Unit,
    pills: List<QimengPill>,
    onPillClick: (index: Int) -> Unit,
    expanded: Boolean,
    onCollapse: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        QimengChipRow(
            pills = dimChips,
            onPillClick = { index ->
                if (index == activeDimIndex) onToggleExpand()
                onDimClick(index)
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        QimengPillFlowRow(
            pills = pills,
            onPillClick = onPillClick,
            collapsed = !expanded,
            onCollapse = onCollapse,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
