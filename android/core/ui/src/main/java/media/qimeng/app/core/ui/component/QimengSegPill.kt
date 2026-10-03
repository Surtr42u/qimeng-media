package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.glass.GlassSurface
import media.qimeng.app.core.ui.glass.pressScale
import media.qimeng.app.core.ui.glass.rememberPressScaleSource
import media.qimeng.app.core.ui.theme.QimengShapes

/** 按下缩放档（GUIDE_UI §UI约束「按下反馈动画」；spring 语言与玻璃件族一致） */
private const val SEG_PILL_PRESSED_SCALE = 0.92f

/** 胶囊布局高（旧版 QimengCapsuleChip / FilterChip ContainerHeight 同档 32dp 紧凑语言） */
private val SEG_PILL_HEIGHT = 32.dp

/** 胶囊文案横向内边距（旧版 14dp 胶囊横向内边距档） */
private val SEG_PILL_LABEL_HORIZONTAL_PADDING = 14.dp

/** 选中态染色透明度（主色染玻璃体：留 8% 透给光影笔，「有色玻璃」而非实底贴膜） */
private const val SEG_PILL_SELECTED_TINT_ALPHA = 0.92f

/**
 * 分段选择胶囊——全仓单枚胶囊渲染唯一来源（首页三胶囊/相册芯片/搜索词丸/详情值丸/
 * 收藏历史值区块/设置与统计档位等经 [QimengSegPill] 与 PillChip 委托全部收敛于此）。
 *
 * 2026-10-03 液态感强化批：容器从 M3 FilterChip 换 [GlassSurface] 玻璃体（用户反馈
 * 「其他胶囊液态感不明显」）——素玻璃未选档 + 主色染色玻璃选中档（tintOverlay 染在
 * 体上、光影笔照常叠出玻璃光学特征）；几何逐项保留：32dp 高/胶囊圆角/12sp 字号/
 * 选中 SemiBold；按下 0.92 spring 缩放（pressScale 单源，与玻璃钮族同反馈语言）。
 * 紧凑化前提不破坏：无 M3 芯片即无 48dp 最小触达，无需 LocalMinimumInteractiveComponentSize。
 * 语义对齐 FilterChip：Role.Checkbox + selected 状态（无障碍读作可选中项）。
 * QimengFilterSheet 标签胶囊因长按能力保留手绘实现（已记档例外），token 与本组件同谱。
 *
 * @param text 胶囊文案
 * @param selected 选中态（主色染色玻璃 vs 素玻璃）
 * @param onClick 点按回调（分段切换语义，由调用方驱动状态）
 */
@Composable
fun QimengSegPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = rememberPressScaleSource()
    // 选中=onPrimary（染色彩充分）；未选=onSurface（主内容色，与坞未选档同口径防浅底发虚）
    val labelColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    GlassSurface(
        shape = QimengShapes.pill,
        tintOverlay = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = SEG_PILL_SELECTED_TINT_ALPHA)
        } else {
            null
        },
        modifier = modifier
            .height(SEG_PILL_HEIGHT)
            .pressScale(interaction, pressedScale = SEG_PILL_PRESSED_SCALE)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semanticsPillSelected(selected),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = SEG_PILL_LABEL_HORIZONTAL_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor,
                maxLines = 1,
            )
        }
    }
}

/** FilterChip 语义等价物：可选项角色 + 选中态（无障碍朗读与换芯片前一致） */
private fun Modifier.semanticsPillSelected(selected: Boolean): Modifier = semantics {
    role = Role.Checkbox
    this.selected = selected
}
