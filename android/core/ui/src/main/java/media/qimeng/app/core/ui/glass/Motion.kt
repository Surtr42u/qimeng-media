package media.qimeng.app.core.ui.glass

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 「流光玻璃」动效规范（ADR-0031）第二层：壳层转场与微交互的 spring 物理单源。
 * （第一层=M3 Expressive 的 MotionScheme.expressive()，作用于 M3 标准件内置动画，见 Theme.kt。）
 *
 * 口径：
 * - 覆盖页（pushed 路由）进出 = 横向 1/4 屏滑入 + 快速淡入（spring 收尾，阻尼 0.9 微过冲）；
 * - 详情页（媒体消费沉浸面）= 0.92 缩放入场（「内容向你走来」的媒体语境动效）；
 * - **顶层 Tab 切换不在此规范内**：保持 snap 瞬切（壳层常驻层防闪烁机制依赖瞬时交换，
 *   spring 化会复活已根修的叠影/残留——ADR-0031 明确排除项）。
 * - 时长类 tween 只用于透明度（fade 对 spring 不敏感、快进快出即可），位移/缩放全部 spring。
 */
object QimengMotion {
    /** 覆盖页进场：右缘 1/4 屏滑入 + 160ms 淡入 */
    fun overlayEnter(): EnterTransition = slideInHorizontally(
        animationSpec = spring(dampingRatio = OVERLAY_DAMPING, stiffness = Spring.StiffnessMediumLow),
        initialOffsetX = { it / OVERLAY_SLIDE_FRACTION },
    ) + fadeIn(animationSpec = tween(OVERLAY_FADE_MS))

    /** 覆盖页退场（返回）：滑回右缘 + 140ms 淡出 */
    fun overlayPopExit(): ExitTransition = slideOutHorizontally(
        animationSpec = spring(dampingRatio = OVERLAY_DAMPING, stiffness = Spring.StiffnessMediumLow),
        targetOffsetX = { it / OVERLAY_SLIDE_FRACTION },
    ) + fadeOut(animationSpec = tween(POP_FADE_MS))

    /** 详情页进场：0.92 缩放 + 150ms 淡入（媒体沉浸面的「走来」语感） */
    fun detailEnter(): EnterTransition = scaleIn(
        animationSpec = spring(dampingRatio = DETAIL_DAMPING, stiffness = Spring.StiffnessMediumLow),
        initialScale = DETAIL_ENTER_SCALE,
    ) + fadeIn(animationSpec = tween(DETAIL_FADE_MS))

    /** 详情页退场：0.94 缩回 + 140ms 淡出 */
    fun detailPopExit(): ExitTransition = scaleOut(
        animationSpec = spring(dampingRatio = DETAIL_DAMPING, stiffness = Spring.StiffnessMediumLow),
        targetScale = DETAIL_EXIT_SCALE,
    ) + fadeOut(animationSpec = tween(POP_FADE_MS))

    /** 覆盖页滑入位移分母（1/4 屏——滑程短促，不与内容滚动混淆） */
    private const val OVERLAY_SLIDE_FRACTION = 4

    /** 覆盖页 spring 阻尼（0.9=轻微过冲一次，玻璃「落定」手感） */
    private const val OVERLAY_DAMPING = 0.9f

    /** 详情页 spring 阻尼（0.85=更有弹性的「走近」感） */
    private const val DETAIL_DAMPING = 0.85f

    /** 详情页入场初始缩放 */
    private const val DETAIL_ENTER_SCALE = 0.92f

    /** 详情页退场目标缩放（比入场略大：退出比进入克制） */
    private const val DETAIL_EXIT_SCALE = 0.94f

    /** 覆盖页淡入时长（ms；透明度走 tween，见类 KDoc 口径） */
    private const val OVERLAY_FADE_MS = 160

    /** 详情页淡入时长（ms） */
    private const val DETAIL_FADE_MS = 150

    /** 退场淡出时长（ms；快出防叠影） */
    private const val POP_FADE_MS = 140
}
