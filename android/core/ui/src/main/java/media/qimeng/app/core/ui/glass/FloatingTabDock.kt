package media.qimeng.app.core.ui.glass

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import media.qimeng.app.core.model.TabBarMaterial
import media.qimeng.app.core.ui.theme.QimengShapes

/**
 * 悬浮玻璃坞底栏（2026-10-03 悬浮玻璃坞批，ADR-0031 视觉语言的承载件）：
 * 独立悬浮层的胶囊坞——内容从坞身后滚过，坞层持**单颗**指示胶囊按槽位中心弹性滑动。
 *
 * ## 为什么是单胶囊滑动（旧分支胶囊缺陷的根修）
 *
 * 旧实现（ui/app-redesign 的 GlassNavBar）每颗 tab 各持一颗胶囊做 scale+alpha 交叉淡化：
 * 两颗胶囊的 spring 相位独立（一颗收还没收完另一颗已开始放），移动过程「空隙忽宽忽窄」；
 * 且胶囊宽写死 46dp，端部槽位与中段槽位的空隙节奏不一致。本实现：
 * - 全坞只有一颗胶囊，位移用 spring（[Spring.DampingRatioMediumBouncy]/
 *   [Spring.StiffnessMediumLow]）滑到目标槽位中心——过冲只改变位置不改变胶囊尺寸，
 *   正是「液态」滑动语感；**首次实测前胶囊不渲染（NaN 门控），实测值到达即 snapTo
 *   直接落位到选中槽中心（零入场动画）**——若首帧也从 0/空算位起步，弹簧会把胶囊从
 *   越缘位置滑入槽 0（约 40dp 可见弹入），即旧分支的首帧伪影（评审项 3 根修）；
 * - 胶囊宽 = 槽位实测宽 − 2×槽内边距（[TabDockDefaults.SlotInnerPadding]），派生而非写死；
 * - Row 首尾加与槽内边距等值的水平 padding，使「端部空隙 = 中段相邻空隙」按构造成立：
 *   中段相邻两颗胶囊边缘间距 = 2×槽内边距；端部 = Row 首尾 padding + 槽内边距
 *   = 2×槽内边距，两者恒等，与屏宽/槽位数无关。
 *
 * RTL 注记：AndroidManifest 未声明 supportsRtl（App 强制 LTR），胶囊 translationX 按
 * Row 左缘单向计算成立；未来若开启 RTL，须按 LocalLayoutDirection 镜像位移原点
 * （RTL 下 Row 首位在右侧，translationX 需改为 Row 宽 − 胶囊左偏）。
 *
 * 弹跳微交互留给图标（选中放大 [ICON_SELECTED_SCALE]，spring
 * [Spring.DampingRatioMediumBouncy]/[Spring.StiffnessMedium]，沿用分支手感）。
 *
 * 行为契约与被替换的 M3 NavigationBar 同谱：选中态/回调由壳层驱动（本组件零内部导航
 * 状态，壳层的防抖/双击回顶逻辑不受影响）；a11y = 整项可点 + 标签文本作可读名 +
 * Role.Tab 角色 + selected 选中语义。CLASSIC 材质不经本组件（壳层直接渲染
 * 主线现行 M3 NavigationBar，逐字保底）。
 *
 * @param items 导航项（[GlassNavItem] 由壳层映射传入，:core:ui 不感知 :app 的
 *   TopLevelDestination，铁律「feature/壳层依赖 core 单向」）
 * @param selectedIndex 当前选中槽位（越界自动钳制）
 * @param onSelect 点击回调（传槽位下标）
 * @param material 材质档（LIQUID/FROSTED/SOLID；CLASSIC 由壳层拦截不进本组件）
 * @param backdrop 内容层捕获（LIQUID/FROSTED 必传；null 或降级档自动回退伪玻璃渲染器）。
 *   封装类型 [QimengBackdropState]——库 Backdrop 类型不外泄出 glass 包（评审必修 1：
 *   :core:ui 对库是 implementation 依赖，公共签名暴露库类型会让 :app 编译炸
 *   unresolved reference），壳层经 rememberQimengBackdropState 取得
 */
@Composable
fun FloatingTabDock(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    material: TabBarMaterial,
    backdrop: QimengBackdropState?,
    modifier: Modifier = Modifier,
) {
    // 整坞按压进度（0=静止，1=全按；官方 Interactive Glass Bottom Bar 配方 spring(0.5,300)）：
    // 真玻璃档经 layerBlock 让坞体随按压微膨大 + lens 折射随压增益——「液态受压」语感。
    // 动画值只在 drawBackdrop 的 layerBlock/effects lambda 内读取（draw 阶段），不触发重组。
    // 按压源用计数而非布尔：多指/同帧乱序下布尔会互相覆盖（后 false 覆盖先 true），
    // 计数增量与顺序无关、净值守恒。
    val pressProgress = remember { Animatable(0f) }
    var pressCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(pressCount > 0) {
        pressProgress.animateTo(
            if (pressCount > 0) 1f else 0f,
            spring(dampingRatio = DOCK_PRESS_DAMPING, stiffness = DOCK_PRESS_STIFFNESS),
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = TabDockDefaults.DockHorizontalMargin,
                end = TabDockDefaults.DockHorizontalMargin,
                bottom = TabDockDefaults.DockBottomOffset,
            ),
    ) {
        DockBody(
            material = material,
            // 公共边界解包：库实例只在 glass 包内部流转，不出本文件私有渲染链
            backdrop = backdrop?.backdrop,
            pressProgress = pressProgress,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DockItemsRow(
                items = items,
                selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                onSelect = onSelect,
                onPressChange = { pressed ->
                    pressCount = if (pressed) pressCount + 1 else (pressCount - 1).coerceAtLeast(0)
                },
            )
        }
    }
}

/**
 * 坞几何/视觉常量单源：壳层的「内容底部让位」必须从这里取值（QimengDimens 不收坞档，
 * 防止主题 token 与坞几何两处口径漂移）。
 */
object TabDockDefaults {

    /** 坞体高度（图标区 + 常显标签行；沿分支 GlassNavBar 的 62dp 浮坞紧凑档） */
    val DockHeight: Dp = 62.dp

    /** 坞体距屏幕底缘（导航栏 inset 之上）的外距 */
    val DockBottomOffset: Dp = 10.dp

    /** 坞体距屏幕左右缘的外距 */
    val DockHorizontalMargin: Dp = 16.dp

    /**
     * 槽内边距（18dp）：每槽内胶囊/图标两侧的呼吸位，同时是 Row 首尾 padding 的取值
     * （「端部空隙 = 中段空隙」的构造保证，见 FloatingTabDock KDoc 的推导）。
     */
    val SlotInnerPadding: Dp = 18.dp

    /**
     * 坞占位净高（坞高 + 坞底外距）：Tab 页滚动区底部让位的固定部分；调用侧还需叠加
     * 导航栏 inset——直接用 [bottomClearance] 而非手拼。
     */
    val DockClearance: Dp = DockHeight + DockBottomOffset

    /** 选中指示胶囊高度（垫在 24dp 图标后的受光胶囊，沿分支几何） */
    val PillHeight: Dp = 27.dp

    /** 指示胶囊图标边长（沿分支几何） */
    val IconSize: Dp = 24.dp

    /**
     * Tab 页滚动区底部让位（导航栏 inset + 坞档）：内容从坞身后滚过时最后一项停在
     * 底栏上方所需的 contentPadding bottom。取坞档与主线 M3 NavigationBar 高度（80dp）
     * 的较大者——页面对材质无感（不因材质档换让位值），CLASSIC 档的 M3 坞同样悬浮
     * 于内容之上，其高度 80dp 大于玻璃坞档 72dp，取 max 两档都成立且 CLASSIC 逐像素
     * 对齐主线 Scaffold bottomBar 时代的让位。
     */
    @Composable
    fun bottomClearance(): Dp =
        WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding() +
            maxOf(DockClearance, CLASSIC_NAV_BAR_HEIGHT)

    /** 主线现行 M3 NavigationBar 高度（CLASSIC 保底档让位口径 = 主线 bottomBar 时代原值） */
    private val CLASSIC_NAV_BAR_HEIGHT: Dp = 80.dp
}

/** 底部导航项数据（玻璃坞通用入参，自旧分支 GlassNavBar.kt 迁入本文件） */
data class GlassNavItem(
    val label: String,
    val icon: ImageVector,
)

/** 选中指示胶囊的主色透明度（走查 r1 校准：0.20→0.30，选中胶囊在玻璃坞上可辨） */
private const val PILL_TINT_ALPHA = 0.30f

/** 选中图标放大档（spring 弹一下的「点亮」语感，沿分支） */
private const val ICON_SELECTED_SCALE = 1.12f

/**
 * 坞体渲染分派（四材质中 CLASSIC 由壳层拦截，此处只接 LIQUID/FROSTED/SOLID）：
 * LIQUID/FROSTED 按设备能力走真 backdrop 特效或降级伪玻璃；SOLID 走不透明纯色坞。
 */
@Composable
private fun DockBody(
    material: TabBarMaterial,
    backdrop: Backdrop?,
    pressProgress: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val capability = resolveBackdropCapabilities()
    when (material) {
        TabBarMaterial.LIQUID -> when {
            capability == DockGlassCapability.Pseudo || backdrop == null ->
                PseudoGlassDockBody(modifier, content)
            // API 31–32 无 AGSL（RuntimeShader）：lens 不可用，降级为 blur+vibrancy
            capability == DockGlassCapability.BlurOnly ->
                GlassDockBody(
                    backdrop = backdrop,
                    pressProgress = pressProgress,
                    modifier = modifier,
                    scrimAlpha = LIQUID_SCRIM_ALPHA,
                    effects = {
                        vibrancy()
                        blur(LIQUID_BLUR_RADIUS.toPx())
                    },
                    content = content,
                )
            else ->
                GlassDockBody(
                    backdrop = backdrop,
                    pressProgress = pressProgress,
                    modifier = modifier,
                    scrimAlpha = LIQUID_SCRIM_ALPHA,
                    effects = {
                        // 官方效果顺序口径：color filter ⇒ blur ⇒ lens（vibrancy 属 color filter）
                        vibrancy()
                        blur(LIQUID_BLUR_RADIUS.toPx())
                        // 折射量随按压增益（官方交互教程同型：lens 参数乘按压进度）——
                        // 玻璃「压得越深、透得越多」；上限仍在 ≤短边 合法区间
                        lens(
                            refractionHeight = LENS_REFRACTION_HEIGHT.toPx(),
                            refractionAmount = (LENS_REFRACTION_AMOUNT * (1f + LENS_PRESS_GAIN * pressProgress.value)).toPx(),
                        )
                    },
                    content = content,
                )
        }

        TabBarMaterial.FROSTED -> when {
            capability == DockGlassCapability.Pseudo || backdrop == null ->
                PseudoGlassDockBody(modifier, content)
            // FROSTED 无 lens 无 vibrancy，BlurOnly 与 Full 同管线（真模糊即可成立）
            else ->
                GlassDockBody(
                    backdrop = backdrop,
                    pressProgress = pressProgress,
                    modifier = modifier,
                    scrimAlpha = FROSTED_SCRIM_ALPHA,
                    effects = {
                        blur(FROSTED_BLUR_RADIUS.toPx())
                    },
                    content = content,
                )
        }

        // SOLID：不透明 M3 坞——零捕获零模糊（性能/兼容档）
        TabBarMaterial.SOLID -> SolidDockBody(modifier, content)

        // 不可达：壳层对 CLASSIC 直接渲染 M3 NavigationBar，不进本组件；兜底走伪玻璃防脏档
        TabBarMaterial.CLASSIC -> PseudoGlassDockBody(modifier, content)
    }
}

/**
 * 真玻璃坞体（Backdrop 库管线，官方 Glass Bottom Bar 教程配方）：
 * drawBackdrop(backdrop, shape=pill, effects, onDrawSurface = 主题色低透明 scrim +
 * 受光发丝描边)。受光边与落影各只允许一个来源（评审项 4 口径）：
 * - 受光边唯一来源 = 下方 onDrawSurface 自画的 1dp 发丝描边——库默认 Highlight
 *   （Highlight.Default：0.5dp 白 50% 环，API≥33 走 AGSL 生效）显式关掉，否则 API 33+
 *   「库环 + 自画描边」双受光边叠加过亮；
 * - 落影唯一来源 = 库默认 Shadow（Shadow.Default，drawBackdrop 缺省开启）——坞体
 *   （本函数 modifier 链）没有自画投影：Modifier.shadow 只存在于伪玻璃档（GlassSurface
 *   elevation）与 SOLID 档，真玻璃档靠库投影分层，故保留缺省值不传 shadow。
 */
@Composable
private fun GlassDockBody(
    backdrop: Backdrop,
    pressProgress: Animatable<Float, AnimationVector1D>,
    scrimAlpha: Float,
    effects: BackdropEffectScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = glassColors()
    val scrimColor = MaterialTheme.colorScheme.surface.copy(alpha = scrimAlpha)
    Box(
        modifier = modifier
            .height(TabDockDefaults.DockHeight)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { QimengShapes.pill },
                effects = effects,
                // 见本函数 KDoc：受光边唯一来源=自画发丝描边，关掉库默认高光环防双描边
                highlight = { null },
                // 按压微膨大走 layerBlock（官方交互教程口径：形变必须进 layerBlock 走硬件层
                // 属性，外包 graphicsLayer 会连背景折射一起缩放——那是错的）
                layerBlock = {
                    val grow = DOCK_PRESS_GROW.toPx() / size.width
                    scaleX = 1f + grow * pressProgress.value
                    scaleY = 1f + grow * pressProgress.value
                },
                onDrawSurface = {
                    // 低透明 scrim：纯采样内容上压一层主题底色保文字可读
                    //（官方教程口径「balance between beauty and readability」）
                    drawRect(scrimColor)
                    // 受光发丝描边：上亮下暗 1dp 渐变（玻璃边缘环境受光，色源=GlassColors）
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(glass.edgeTop, glass.edgeBottom),
                            startY = 0f,
                            endY = size.height,
                        ),
                        cornerRadius = CornerRadius(size.height / 2f),
                        style = Stroke(width = DOCK_EDGE_STROKE_WIDTH.toPx()),
                    )
                },
            ),
        content = content,
    )
}

/**
 * 伪玻璃坞体（LIQUID/FROSTED 在 API ≤30 或 backdrop 不可用时的共用降级）：
 * 分支 GlassSurface 加厚档观感——半透明体 + 受光描边 + 顶部高光纱 + 投影，无模糊。
 * 这是「真模糊降级形态」：AuroraBackdrop 氛围层覆于其下仍提供部分景深（ADR-0031 降级策略）。
 */
@Composable
private fun PseudoGlassDockBody(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        shape = QimengShapes.pill,
        strong = true,
        elevation = PSEUDO_GLASS_ELEVATION,
        modifier = modifier.height(TabDockDefaults.DockHeight),
        content = content,
    )
}

/** 纯色坞体（SOLID）：不透明 surfaceContainerHighest + 轻投影 + pill 形，零采样零特效 */
@Composable
private fun SolidDockBody(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = glassColors()
    Box(
        modifier = modifier
            .height(TabDockDefaults.DockHeight)
            // surfaceContainerHighest：深浅两主题下都与页面底色拉开最大一档（对比更稳者）
            .shadow(
                elevation = SOLID_DOCK_ELEVATION,
                shape = QimengShapes.pill,
                ambientColor = glass.shadow,
                spotColor = glass.shadow,
            )
            .clip(QimengShapes.pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        content = content,
    )
}

/**
 * 坞内条目行 + 单颗滑动指示胶囊（根因修复的落点，几何见 FloatingTabDock KDoc）。
 * 胶囊先声明（垫底）后声明条目行（图标压在胶囊上）。
 */
@Composable
private fun DockItemsRow(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onPressChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    // Row 实测宽（含自身首尾 padding）：胶囊几何全部由它派生，不写死任何屏宽假设
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val slotInnerPaddingPx = with(density) { TabDockDefaults.SlotInnerPadding.toPx() }
    // 槽位宽 = (Row 宽 − 首尾 padding×2) / 条数；胶囊宽 = 槽宽 − 槽内边距×2（派生而非写死）
    val slotWidthPx =
        if (rowWidthPx > 0 && items.isNotEmpty()) {
            (rowWidthPx - 2 * slotInnerPaddingPx) / items.size
        } else {
            0f
        }
    val pillWidthPx = (slotWidthPx - 2 * slotInnerPaddingPx).coerceAtLeast(0f)
    // 目标 = 选中槽位中心（相对 Row 左缘）：首尾 padding + i×槽宽 + 半槽
    // 单胶囊滑动：Animatable 初值 NaN（未落位哨兵）——首实测帧 snapTo 直接落位零动画，
    // 此后切换走 spring（MediumBouncy + MediumLow，位移过冲安全不改胶囊尺寸，
    // 「液态」滑动语感，任务冻结口径）。评审项 3 根修：animateFloatAsState 首帧以
    // 「未测量空算目标（≈18dp）」起步，实测后弹簧从越缘位置滑入槽 0（约 40dp 可见弹入）
    val pillCenter = remember { Animatable(Float.NaN) }
    LaunchedEffect(slotWidthPx, selectedIndex) {
        // 未测量帧不落位：slotWidthPx==0 时 target 是空算值（只剩槽内边距 18dp），
        // 若此刻 snapTo 会提前解除 NaN 哨兵，实测后仍从 18dp 弹到真实槽心——伪影照旧；
        // 保持 NaN 到实测值到来才落位（评审项 3 的关键缝）
        if (slotWidthPx <= 0f) return@LaunchedEffect
        val target = slotInnerPaddingPx + selectedIndex * slotWidthPx + slotWidthPx / 2f
        if (pillCenter.value.isNaN()) {
            // 首测量（或进程恢复首帧）：直接落位到选中槽中心，零入场动画
            pillCenter.snapTo(target)
        } else {
            pillCenter.animateTo(
                target,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }
    val pillTint = MaterialTheme.colorScheme.primary.copy(alpha = PILL_TINT_ALPHA)

    Box {
        // 指示胶囊：未测量（pillWidthPx==0）不组合；已测量但未落位（NaN）在绘制层整颗
        // 隐藏（graphicsLayer 内读 Animatable 状态，逐帧只重刷层不触发重组）——
        // 双门控保证「不落位不画」，杜绝越缘位置闪现
        if (pillWidthPx > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(with(density) { pillWidthPx.toDp() })
                    .height(TabDockDefaults.PillHeight)
                    .graphicsLayer {
                        val center = pillCenter.value
                        if (center.isNaN()) {
                            alpha = 0f
                        } else {
                            alpha = 1f
                            translationX = center - pillWidthPx / 2f
                        }
                    }
                    // 胶囊体纵向微渐变（顶亮底沉）：给指示胶囊一点圆柱体积感，替代纯平色
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                pillTint.copy(alpha = PILL_TINT_ALPHA + PILL_TOP_ALPHA_GAIN),
                                pillTint,
                            ),
                        ),
                        shape = RoundedCornerShape(PILL_CORNER_PERCENT),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .height(TabDockDefaults.DockHeight)
                .onSizeChanged { rowWidthPx = it.width }
                // 首尾 padding = 槽内边距：「端部空隙 = 中段空隙」按构造成立（KDoc 推导）
                .padding(horizontal = TabDockDefaults.SlotInnerPadding),
        ) {
            items.forEachIndexed { index, item ->
                DockNavItem(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    onPressChange = onPressChange,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * 单个坞条目：图标点亮 spring + 常显标签（选中 SemiBold），a11y = Role.Tab + selected。
 * 触感（2026-10-03 玻璃坞迭代批）：按压缩放（pressScale 单源 0.94，之前条目无按压反馈）+
 * 点击轻触觉（TextHandleMove 轻档——探索批 EXPLORE 4 同款 API，本批转正为常开）。
 */
@Composable
private fun DockNavItem(
    item: GlassNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    // 按压态上抛给坞层（整坞微膨大驱动）；per-item pressScale 自行渲染按压缩放
    LaunchedEffect(pressed) { onPressChange(pressed) }
    val iconScale by animateFloatAsState(
        targetValue = if (selected) ICON_SELECTED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "dockIconScale",
    )
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .pressScale(interactionSource, pressedScale = DOCK_ITEM_PRESSED_SCALE)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            // 可读名=标签文本（icon contentDescription 置空防重复朗读），补 Tab 角色与选中态
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(TabDockDefaults.IconSize)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
        }
        Spacer(Modifier.height(LABEL_TOP_SPACING))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            maxLines = 1,
        )
    }
}

// ---------- 坞几何/视觉具名常量（魔法值零容忍；档位含义见各注释） ----------

/** 顶部标签与图标的间距（沿分支 GlassNavBar 的 2dp 紧凑档） */
private val LABEL_TOP_SPACING = 2.dp

/** 胶囊圆角（RoundedCornerShape 百分比：100% = 全圆胶囊） */
private const val PILL_CORNER_PERCENT = 100

/** LIQUID 档 backdrop 采样模糊半径（「小半径」档：保折射可辨、不糊采样细节） */
private val LIQUID_BLUR_RADIUS = 10.dp

/** FROSTED 档采样模糊半径（与 Web 端 Aurora Glass 的 blur(22px) 同源——CSS px 与 dp 同为密度无关单位） */
private val FROSTED_BLUR_RADIUS = 22.dp

/** LIQUID 档 lens 折射高度（官方 Glass Bottom Bar 教程同值 16dp；≤坞圆角 31dp 合法区间内） */
private val LENS_REFRACTION_HEIGHT = 16.dp

/** LIQUID 档 lens 折射量（官方教程同值 32dp；≤坞短边合法区间内） */
private val LENS_REFRACTION_AMOUNT = 32.dp

/** LIQUID 档表面 scrim 透明度（低遮盖：折射与 vibrancy 为主角，官方教程白 0.5 同档取低） */
private const val LIQUID_SCRIM_ALPHA = 0.40f

/** FROSTED 档表面 scrim 透明度（比 LIQUID 稍高遮盖：磨砂语言以「读得清」优先） */
private const val FROSTED_SCRIM_ALPHA = 0.62f

/** 伪玻璃坞投影高度（沿分支 GlassNavBar 的加厚档 14dp） */
private val PSEUDO_GLASS_ELEVATION = 14.dp

/** 纯色坞轻投影高度（不透明体自分层，投影只需轻档） */
private val SOLID_DOCK_ELEVATION = 6.dp

/** 玻璃坞受光发丝描边宽度（与 GlassSurface 的 GLASS_EDGE_WIDTH 同档 1dp；坞层独立渲染再声明一次） */
private val DOCK_EDGE_STROKE_WIDTH = 1.dp

// ---------- 触感/立体感（2026-10-03 玻璃坞迭代批，官方 Interactive Glass Bottom Bar 配方） ----------

/** 坞条目按压缩放档（GlassIconButton 同款 0.94 深档——小控件触感更明确） */
private const val DOCK_ITEM_PRESSED_SCALE = 0.94f

/** 整坞按压 spring 阻尼（官方交互教程同值 0.5——明显弹性） */
private const val DOCK_PRESS_DAMPING = 0.5f

/** 整坞按压 spring 刚度（官方同值 300） */
private const val DOCK_PRESS_STIFFNESS = 300f

/** 整坞按压膨大量（官方 LiquidBottomTabs 同型 16dp/坞宽；取半档 8dp≈2.5% 的克制 swell） */
private val DOCK_PRESS_GROW = 8.dp

/** lens 折射量按压增益（32dp×1.25=40dp @ 全按，仍在 ≤坞短边 62dp 合法区间内） */
private const val LENS_PRESS_GAIN = 0.25f

/** 指示胶囊顶部渐变提亮增益（顶缘 alpha 0.30+0.12=0.42，底缘回落 0.30——圆柱体积感） */
private const val PILL_TOP_ALPHA_GAIN = 0.12f

/**
 * 真模糊能力档（全坞唯一 SDK 分叉点，禁止散写 SDK_INT）：
 * - [DockGlassCapability.Full]（API ≥33）：vibrancy + blur + lens 全量（lens 依赖 AGSL
 *   RuntimeShader，官方 isRuntimeShaderSupported = API 33）；
 * - [DockGlassCapability.BlurOnly]（API 31–32）：RenderEffect 可用（API 31）但 AGSL 不可用，
 *   去掉 lens；
 * - [DockGlassCapability.Pseudo]（API ≤30）：RenderEffect 不可用，不走 drawBackdrop，
 *   回退伪玻璃渲染器。
 */
private enum class DockGlassCapability {
    Full,
    BlurOnly,
    Pseudo,
}

private fun resolveBackdropCapabilities(): DockGlassCapability = when {
    Build.VERSION.SDK_INT >= 33 -> DockGlassCapability.Full
    Build.VERSION.SDK_INT >= 31 -> DockGlassCapability.BlurOnly
    else -> DockGlassCapability.Pseudo
}
