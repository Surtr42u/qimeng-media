package media.qimeng.app.core.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.theme.QimengShapes

/** 受光描边宽度（玻璃边缘线，1dp 恒定——2dp 起出现「描框」感，背离液态玻璃取向） */
private val GLASS_EDGE_WIDTH = 1.dp

/** 顶部高光纱的纵向渐隐比例（占面板高；0.4=高光集中在上 40% 渐隐至透明） */
private const val SHEEN_FADE_FRACTION = 0.4f

/**
 * 玻璃面板（ADR-0031「流光玻璃」核心容器，全 App 玻璃质感单源）：
 * 半透明渐变体 + 底部内影 + 顶部镜面高光池 + 顶部高光纱 + 受光渐变描边（上亮下暗）
 * + 可选染色层与投影，圆角由 [shape] 定。
 *
 * 纯渲染容器（铁律 7）：不碰 API/业务，视觉参数全部来自 [glassColors] 主题单源。
 * API 26–30 无需分支：本组件不含 blur 依赖，观感各 API 级别一致（ADR-0031 降级策略；
 * 当前实现零 blur，`Modifier.blur` 仅为未来自有内容的预留策略，见 ADR-0031 决策§2）。
 *
 * @param shape 面板形状（默认面板 24dp 圆角；胶囊场合传 QimengShapes.pill）
 * @param strong true=加厚档（底栏/悬浮条等压住滚动内容的场合）
 * @param elevation 投影高度（null=无投影；网格中的小面板建议 null 防投影叠印）
 * @param tintOverlay 染色层（2026-10-03 液态感强化批新增；选中态胶囊等「有色玻璃」场合，
 *   画在体之上、光影之下——玻璃的光学特征不被染色盖掉。null=素玻璃）
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = QimengShapes.panel,
    strong: Boolean = false,
    elevation: Dp? = null,
    tintOverlay: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = glassColors()
    val fill = if (strong) glass.fillStrong else glass.fill
    Box(
        modifier = modifier
            .then(
                if (elevation != null) {
                    Modifier.shadow(
                        elevation = elevation,
                        shape = shape,
                        ambientColor = glass.shadow,
                        spotColor = glass.shadow,
                    )
                } else {
                    Modifier
                },
            )
            // 先 clip 后画：描边/高光不越出圆角（嵌套内容同被约束在玻璃形体内）
            .clip(shape)
            .drawBehind {
                drawGlass(fill = fill, glass = glass, shape = shape, tintOverlay = tintOverlay)
            },
        content = content,
    )
}

/** 镜面高光池圆心横向位置（面板宽的比例；0.5=顶缘正中聚光） */
private const val SPECULAR_CENTER_X_FRACTION = 0.5f

/** 镜面高光池圆心纵向位置（面板高的比例；负值=圆心在面板上缘之外，光斑只落上缘弧面） */
private const val SPECULAR_CENTER_Y_FRACTION = -0.25f

/** 镜面高光池半径（面板宽的比例；光斑横向铺满大半个面板、纵向自然渐隐） */
private const val SPECULAR_RADIUS_FRACTION = 0.85f

/** 底部内影起始位置（面板高的比例；0.45=下 55% 向内渐暗） */
private const val INNER_SHADE_START_FRACTION = 0.45f

/** 玻璃面板六笔绘制：体 → 染色 → 底部内影 → 镜面高光池 → 顶部高光纱 → 受光描边
 *  （顺序不可换：描边必须压在高光上，光影永远在染色之上——「有色玻璃」而非「有色贴膜」） */
private fun DrawScope.drawGlass(fill: Color, glass: GlassColors, shape: Shape, tintOverlay: Color?) {
    // 圆角半径取自 shape 的 outline（圆角矩形/胶囊都落在 topLeft 半径上；非圆角形状=0）
    val radius = shape.createOutline(size, layoutDirection, this).cornerRadiusPx()
    // 1) 半透明体
    drawRoundRect(color = fill, cornerRadius = CornerRadius(radius))
    // 1b) 染色层（选中态胶囊：主色低透染在体上，后续光影笔照常叠出玻璃光学特征）
    if (tintOverlay != null) {
        drawRoundRect(color = tintOverlay, cornerRadius = CornerRadius(radius))
    }
    // 2) 底部内影：下缘向内渐暗（玻璃体厚度/纵深）
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, glass.innerShade),
            startY = size.height * INNER_SHADE_START_FRACTION,
            endY = size.height,
        ),
    )
    // 3) 镜面高光池：上缘弧面的聚光斑（液态玻璃「光在曲面上流动」的静态近似）
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(glass.specular, Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(
                x = size.width * SPECULAR_CENTER_X_FRACTION,
                y = size.height * SPECULAR_CENTER_Y_FRACTION,
            ),
            radius = size.width * SPECULAR_RADIUS_FRACTION,
        ),
    )
    // 4) 顶部高光纱：上缘向下在 SHEEN_FADE_FRACTION 比例内渐隐
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(glass.sheen, Color.Transparent),
            startY = 0f,
            endY = size.height * SHEEN_FADE_FRACTION,
        ),
    )
    // 5) 受光描边：上亮下暗渐变描边（玻璃边缘环境受光）
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(glass.edgeTop, glass.edgeBottom),
            startY = 0f,
            endY = size.height,
        ),
        cornerRadius = CornerRadius(radius),
        style = Stroke(width = GLASS_EDGE_WIDTH.toPx()),
    )
}

/** outline → 圆角半径 px（非圆角 outline 返回 0，直角描边照常生效） */
private fun androidx.compose.ui.graphics.Outline.cornerRadiusPx(): Float = when (this) {
    // Outline.Rounded 的 RoundRect 属性名是 roundRect（无 ed，ui-graphics 官方签名）
    is androidx.compose.ui.graphics.Outline.Rounded -> roundRect.topLeftCornerRadius.x
    else -> 0f
}

/** 玻璃内容卡按压缩放档（GlassCard 可点形态的微交互） */
private const val GLASS_CARD_PRESSED_SCALE = 0.97f

/**
 * 玻璃内容卡（ADR-0031）：页面级内容区块的标准容器——标准玻璃面板 + 可选点击
 * （点击形态附 spring 按压缩放）。统计卡/入口卡/详情区块等「Surface(color=surfaceVariant)
 * 时代」卡片的替换单源，禁止 feature 再各写一套玻璃卡。
 * 无点击时不加交互修饰（与 Surface 无 onClick 同语义）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = QimengShapes.panel,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    GlassSurface(
        shape = shape,
        modifier = modifier.then(
            if (onClick != null) {
                Modifier
                    .pressScale(interactionSource, pressedScale = GLASS_CARD_PRESSED_SCALE)
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            } else {
                Modifier
            },
        ),
        content = content,
    )
}
