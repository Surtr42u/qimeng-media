package media.qimeng.app.core.ui.glass

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 「流光玻璃」动效规范 v2（2026-10-03 全新重制，取代 v1 spring 转场——用户反馈
 * v1「过于生硬」，深度调研后整体换范式）。
 *
 * ## 研究基准
 * - **M3 缓动令牌族**（material-components-android theming/Motion·M3 tokens-specs）：
 *   进场 emphasized decelerate = cubic-bezier(0.05, 0.7, 0.1, 1)（高速起步→长缓收尾，
 *   元素「到达即安顿」）；退场 emphasized accelerate = cubic-bezier(0.3, 0, 0.8, 0.15)
 *   （缓起步→加速离场）。两支成对使用即 M3 共享轴的标准编排。
 * - **时长带**：页面级转场 300–400ms 为质感区；>500ms 拖沓、<200ms 生硬（移动端
 *   转场通用研究结论）。进出不对称：进 350 / 退 200——来者为主、去者让位（层级）。
 * - **v1「生硬」三根因**：低刚度 spring 起步迟滞（前段近乎静止）+ 0.9 阻尼过冲晃动
 *   + 140–160ms 线性快闪淡入。v2 转场全部改**曲线驱动**——曲线首帧即有速度
 *   （iOS 手感的来源），无迟滞无回晃。
 * - **spring 的正确位置**：仅保留给触摸微交互（按压缩放族，物理回弹=触觉语言）；
 *   空间导航（页面位移）不用弹簧——位移回弹读作「页面漂」而非「页面走」。
 * - **Tab 间保持 snap**（常驻层防叠影拍板，规范明确排除项，v2 不变）。
 *
 * 纯函数单源（铁律 3 精神）：无 IO 无状态，参数全部具名常量。
 */
object QimengMotion {

    // ── 缓动令牌（M3 emphasized 族成对）────────────────────────────────

    /** 进场曲线：高速起步→长缓收尾（元素「到达即安顿」） */
    private val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 退场曲线：缓起步→加速离场（元素「果断让位」） */
    private val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // ── 覆盖页（子页推入/返回，共享轴 X·纯位移编排）──────────────────
    // 2026-10-03 叠层残影根修（用户反馈「返回有残留视觉导致叠层，我的页最明显」）：
    // 共享轴的 fade 分量是给「各自独立成层」的页面准备的；本 App 架构=常驻层 + 半透明
    // 玻璃卡（我的页行卡 0.76 透明度），进出页面若透明度渐变，下层玻璃卡透出叠在
    // 渐隐页面上=叠层残影。iOS 返回干净的本质：**进出页面全程不透明**，下层由位移
    // 逐步揭示。故 overlay 族去除全部 fade 分量，纯位移驱动（iOS push/pop 手感）。

    /** 子页进场：30% 屏滑入（350ms 减速曲线；全程不透明，覆盖揭示下层） */
    fun overlayEnter(): EnterTransition = slideInHorizontally(
        animationSpec = tween(ENTER_MS, easing = EmphasizedDecelerate),
        initialOffsetX = { (it * ENTER_TRAVEL).toInt() },
    )

    /** 子页覆盖下的原页退场：8% 反向让位视差（200ms 加速曲线；不透明度不变） */
    fun overlayExit(): ExitTransition = slideOutHorizontally(
        animationSpec = tween(EXIT_MS, easing = EmphasizedAccelerate),
        targetOffsetX = { -(it * EXIT_TRAVEL).toInt() },
    )

    /** 返回时原页回位：从 8% 让位处滑回（350ms 减速曲线；不透明度不变） */
    fun overlayPopEnter(): EnterTransition = slideInHorizontally(
        animationSpec = tween(ENTER_MS, easing = EmphasizedDecelerate),
        initialOffsetX = { -(it * EXIT_TRAVEL).toInt() },
    )

    /** 子页返回退场：滑回右缘 30% 屏（350ms 加速曲线；全程不透明，位移揭示下层——
     *  iOS pop 手感，玻璃卡不透出无残影） */
    fun overlayPopExit(): ExitTransition = slideOutHorizontally(
        animationSpec = tween(ENTER_MS, easing = EmphasizedAccelerate),
        targetOffsetX = { (it * ENTER_TRAVEL).toInt() },
    )

    // ── 详情页（媒体消费沉浸面：缩放「走来」语感）─────────────────────

    /** 详情进场：0.94 缩放 + 淡入（320ms 减速曲线） */
    fun detailEnter(): EnterTransition = scaleIn(
        animationSpec = tween(DETAIL_ENTER_MS, easing = EmphasizedDecelerate),
        initialScale = DETAIL_ENTER_SCALE,
    ) + fadeIn(animationSpec = tween(DETAIL_ENTER_MS, easing = EmphasizedDecelerate))

    /** 详情退场：缩回 0.96 + 淡出（200ms 加速曲线，退出比进入克制） */
    fun detailPopExit(): ExitTransition = scaleOut(
        animationSpec = tween(EXIT_MS, easing = EmphasizedAccelerate),
        targetScale = DETAIL_EXIT_SCALE,
    ) + fadeOut(animationSpec = tween(EXIT_MS, easing = EmphasizedAccelerate))

    // ── 时长/位移常量（研究基准见类 KDoc；具名单源禁散写）──────────────

    /** 进场时长（ms；页面级质感区 300–400 取中上） */
    private const val ENTER_MS = 350

    /** 被覆盖页退场时长（ms；进出不对称：退比进快，让位不抢戏） */
    private const val EXIT_MS = 200

    /** 进场滑程（屏宽比例；30% 足够建立方向感又不拖沓） */
    private const val ENTER_TRAVEL = 0.30f

    /** 退场让位滑程（屏宽比例；8% 轻让位，主从层级） */
    private const val EXIT_TRAVEL = 0.08f

    /** 详情页进场时长（ms） */
    private const val DETAIL_ENTER_MS = 320

    /** 详情页入场初始缩放 */
    private const val DETAIL_ENTER_SCALE = 0.94f

    /** 详情页退场目标缩放（比入场略大：退出比进入克制） */
    private const val DETAIL_EXIT_SCALE = 0.96f
}
