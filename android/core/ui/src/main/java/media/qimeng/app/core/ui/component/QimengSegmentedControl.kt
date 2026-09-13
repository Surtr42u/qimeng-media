package media.qimeng.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 分段控件轨道高度（旧版 fragment_data_stats.xml 时间范围胶囊组 layout_height=36dp） */
private val SEG_TRACK_HEIGHT = 36.dp

/** 分段控件轨道内边距（旧版同布局 padding=3dp，选中浮丸与轨道的间隙档） */
private val SEG_TRACK_INNER_PADDING = 3.dp

/** 分段文字字号（旧版同布局三档 TextView textSize=13sp） */
private val SEG_LABEL_SP = 13.sp

/**
 * 连体分段选择控件（2026-09-13 用户终裁对齐旧版数字统计页：时间范围三档不再是三枚独立
 * 胶囊，而是「一条软底轨道 + 等宽分段 + 选中段主色浮丸」的合体形态，旧版截图同款）。
 *
 * 视觉 token 逐项对照旧版 fragment_data_stats.xml + bg_capsule_soft/bg_capsule_primary：
 * - 轨道 = 全圆角软底（surfaceVariant ≈ 旧 qmColorChipBg），高 [SEG_TRACK_HEIGHT]，
 *   内边距 [SEG_TRACK_INNER_PADDING]；
 * - 选中段 = 主色全圆角实底（primary ≈ 旧 qmColorPrimary）+ onPrimary 字；
 *   未选段 = 透明底 + onSurfaceVariant 字（旧 qmColorTextSecondary 档）；
 * - 文字 [SEG_LABEL_SP]，选中 SemiBold（纯视觉层组件，不承载业务规则——ADR-0008）。
 *
 * 与 [QimengSegPill]（单枚胶囊，首页三胶囊/设置档位等场景继续使用）的分工：本组件只服务
 * 「互斥单选的连体分段」语义（数据统计页时段档），不替代散排胶囊族。
 *
 * @param options 分段文案（下标即分段序）
 * @param selectedIndex 当前选中下标（调用方状态单源）
 * @param onSelect 点选回调（携带分段下标）
 */
@Composable
fun QimengSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SEG_TRACK_HEIGHT)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(SEG_TRACK_INNER_PADDING),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable(onClick = { onSelect(index) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = SEG_LABEL_SP,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
