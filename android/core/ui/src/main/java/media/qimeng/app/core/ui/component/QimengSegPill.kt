package media.qimeng.app.core.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 分段选择胶囊（任务 H1：内部实现从自绘 Text+clip+background 换 M3 [FilterChip] 标准件）。
 *
 * 为什么不换 SingleChoiceSegmentedButtonRow（任务书二选一）：SegmentedButton 是连体分段布局，
 * 消费方（feature/settings 缓存档位、feature/stats 时段档、feature/upload 目标库、QimengPills
 * 全部胶囊族）都在 spacedBy 的 Row/FlowRow 里逐枚独立排布，换连体分段要改全部调用点布局——
 * FilterChip 方案签名零变化、调用点零改动，按「改动面小者为准」选 FilterChip。
 *
 * 胶囊视觉 token 逐项保住（G6 已定语言，任务 H1 明确保留）：
 * - 圆角=[QimengDimens.PillCornerRadius]（Web .pill 999px）；
 * - 选中=主色实底 + onPrimary 字 + SemiBold；未选=surfaceVariant 软底 + onSurfaceVariant；
 * - border=null 去掉 FilterChip 默认描边（胶囊语言是实底填充，Web .seg 无描边）；
 * - FilterChip 内建 8dp label 横向留白，补 [QimengDimens.SpaceS] 凑足旧版 14dp 胶囊横向内边距。
 * FilterChip 无勾选图标的前提是不传 leadingIcon（默认 null，勾选位不占位）。
 *
 * 本组件仍是全仓单枚胶囊渲染的唯一来源（PillChip 经此委托）。QimengFilterSheet 标签胶囊
 * 因 M3 芯片无长按能力（两轮标准件化实测破坏功能）保留手绘实现，属已记档例外，
 * 其 token 与本组件同谱；禁止再开其他平行实现。
 *
 * @param text 胶囊文案
 * @param selected 选中态（实底主色 vs 软底）
 * @param onClick 点按回调（分段切换语义，由调用方驱动状态）
 */
@Composable
fun QimengSegPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        // m3 1.4 FilterChip 内建 label 横向留白实测已接近旧版 14dp 胶囊横向内边距
        // （目检 dump：不再额外补白，胶囊宽度与基线差 <2dp/侧，故 label 不加 padding）
        label = {
            Text(
                text = text,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = null,
        modifier = modifier,
    )
}
