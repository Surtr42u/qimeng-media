package media.qimeng.app.core.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.theme.QimengShapes

/** 底栏玻璃坞高度（图标区 32 + 标签行；比 M3 NavigationBar 80dp 紧凑，浮动坞语言） */
private val NAV_BAR_HEIGHT = 62.dp

/** 选中态指示胶囊尺寸（图标背后的受光胶囊） */
private val NAV_PILL_WIDTH = 46.dp
private val NAV_PILL_HEIGHT = 27.dp

/** 选中图标放大档（spring 弹一下的「点亮」语感） */
private const val NAV_ICON_SELECTED_SCALE = 1.12f

/** 指示胶囊横向内边距（文本坞内左右呼吸） */
private val NAV_ITEM_HORIZONTAL_PADDING = 4.dp

/**
 * 底部导航项数据（玻璃底栏的通用入参——:core:ui 不感知 :app 的 TopLevelDestination，
 * 由壳层映射传入；铁律「feature/壳层依赖 core 单向」）。
 */
data class GlassNavItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * 玻璃底部导航坞（ADR-0031）：悬浮玻璃胶囊坞 + 选中项 spring 弹性指示胶囊 + 图标点亮微交互。
 *
 * 行为契约与被替换的 M3 NavigationBar 完全同谱：选中态/回调由壳层驱动（本组件零内部
 * 导航状态）；a11y 语义=整项可点 + 文本即 contentDescription（label 常显）。
 * 视觉：加厚玻璃坞（[GlassSurface strong]）压住下方滚动内容，受光描边在深色滚动内容上
 * 恒可辨——真 backdrop 模糊的降级形态，ADR-0031 降级策略。
 */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    ) {
        GlassSurface(
            shape = QimengShapes.pill,
            strong = true,
            elevation = 14.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NAV_BAR_HEIGHT),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                items.forEachIndexed { index, item ->
                    GlassNavItem(
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** 单个导航项：指示胶囊（scale in/out spring）+ 图标点亮 + 常显标签 */
@Composable
private fun GlassNavItem(
    item: GlassNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 指示胶囊：选中 1 / 未选 0 的缩放（spring 带轻微回弹），配合 alpha 同步隐现
    val pillScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "navPillScale",
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "navPillAlpha",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) NAV_ICON_SELECTED_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "navIconScale",
    )
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = NAV_ITEM_HORIZONTAL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (pillAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .width(NAV_PILL_WIDTH)
                        .height(NAV_PILL_HEIGHT)
                        .graphicsLayer {
                            scaleX = pillScale
                            scaleY = pillScale
                            alpha = pillAlpha
                        }
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            shape = RoundedCornerShape(100),
                        ),
                )
            }
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            maxLines = 1,
        )
    }
}
