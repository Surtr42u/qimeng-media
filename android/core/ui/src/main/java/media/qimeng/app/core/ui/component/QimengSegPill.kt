package media.qimeng.app.core.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.theme.QimengDimens

/** 按压缩放最小值（ADR-0031 玻璃微交互 0.96；旧 0.92 大幅缩放随旧胶囊语言退役） */
private const val SEG_PILL_PRESSED_SCALE = 0.96f

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
 * - border=null 去掉 FilterChip 默认描边（胶囊语言是实底填充，Web .seg 无描边）。
 * FilterChip 无勾选图标的前提是不传 leadingIcon（默认 null，勾选位不占位）。
 *
 * 紧凑化（F 批 2026-09-09）：CompositionLocal(LMinimumInteractiveComponentSize=0.dp)——
 * 旧版 QimengTagChip chipMinTouchTargetSize=0dp（styles.xml L26）的 Compose 等价。material3
 * 1.4.0 的可点 Surface（FilterChip 的容器）内部施 minimumInteractiveComponentSize，把 32dp
 * 视觉胶囊的布局节点撑到 48dp（实测搜索页词丸行节距 56dp 的元凶）；归零后布局高回到
 * FilterChipTokens.ContainerHeight=32dp=旧版 QimengCapsuleChip 高度。全仓胶囊单源在此一处
 * 收口：首页三胶囊/相册芯片/缓存档位等消费方一并紧凑化，即旧版 30-32dp 紧凑胶囊语言。
 * 字号同步压回 labelMedium 12sp=旧版 textSize 12sp（styles.xml L13，全仓胶囊统一字号）。
 *
 * 按下缩放反馈（ADR-0031 玻璃微交互，2026-10-02 起）：pressed 0.96→1.0 spring 回弹
 * （旧 GUIDE_UI 0.92/100ms tween 款随旧视觉语言退役）；全仓胶囊单源在本组件，一处改全局生效。
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
    // 按下态经自持 interactionSource 观测（传入 FilterChip 覆盖其默认源），缩放在
    // graphicsLayer 块内延迟读取 pressScale，缩放动画不触发重组。
    // ADR-0031：tween(100ms) 换 spring 物理（medium bouncy 回弹=玻璃微交互规范；
    // 1x-100ms 定宽动画是旧版语言）
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) SEG_PILL_PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "qimengSegPillPressScale",
    )
    // F 批紧凑化：见头部 KDoc——消 FilterChip 可点 Surface 的 48dp 布局下限（旧版
    // chipMinTouchTargetSize=0dp 等价），胶囊布局高回 32dp
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            // m3 1.4 FilterChip 内建 label 横向留白实测已接近旧版 14dp 胶囊横向内边距
            // （目检 dump：不再额外补白，胶囊宽度与基线差 <2dp/侧，故 label 不加 padding）
            label = {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
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
            interactionSource = interactionSource,
            modifier = modifier.graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
        )
    }
}
