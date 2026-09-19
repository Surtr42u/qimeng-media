package media.qimeng.app.feature.detail

import android.os.Build
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsAnimationControlListenerCompat
import androidx.core.view.WindowInsetsAnimationControllerCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 系统栏瞬时隐藏单件（任务S 批S11，2026-09-19 用户拍板「我要的是状态栏也瞬时，你搞反了」
 * ——纠正批S10 件3 方向：S10 曾把播放器控制条改 250ms 淡出去迁就系统栏慢动画，本批控制条
 * 复位瞬时显隐，改为消灭慢的一方）。
 *
 * 根因：`WindowInsetsControllerCompat.hide()` 走平台 hide 动画（约 300ms 平移渐隐），即用户
 * 真机观感「状态栏消失比下面的进度条慢」的拖尾来源。修法=`controlWindowInsetsAnimation`
 * （androidx.core 官方 API，App 自驱动画时长）**零时长**隐藏：durationMs=0，onReady 里把
 * 状态栏 inset 置 0（[instantHideTargetInsets]）后立即 finish(false)（以隐态收束）——平台
 * 动画被 0ms 会话整体短路，观感=瞬时消失。
 *
 * **compat 行为核查结论（铁律 8，2026-09-19 本地 Gradle 缓存 androidx.core 官方源码核实，
 * 非凭记忆写；core-ktx 1.19.0 在用、缓存另存 1.17.0 sources.jar 两版 API 面一致）**：
 * - API >= 30（Impl30/Impl31/Impl35）：委托平台 `WindowInsetsController.controlWindowInsetsAnimation`
 *   → 真实受控动画，durationMs=0 生效（主路径；用户真机 Android 16=API 36 走此路）；
 * - API < 30（Impl/Impl20）：`controlWindowInsetsAnimation` 为**空实现 no-op**，listener 的
 *   onReady/onCancelled 永不回调（Javadoc 原文「This method only works on API >= 30 since
 *   there is no way to control the window in the system on prior APIs」）→ backport 不支持，
 *   <30 分支**直接普通 hide()**（带平台动画，与旧行为一致，minSdk=26 的存量语义不回退）；
 * - 失败通道=onCancelled（compat 监听器接口无 onFailure；平台在「控制权立即获取失败」时
 *   无前置 onReady 直接 onCancelled）→ 兜底回退普通 hide()。
 *
 * show 不走本件：用户只反馈消失慢，显出带系统动画是正常观感（批S11 定稿口径），show 保持
 * 普通 show()（调用方：SystemBarsImmersiveEffect 播放态/放大沉浸、FullscreenOverlayShell
 * 横屏全屏 Dialog——两处 hide 全部经此通道）。
 */
internal const val INSTANT_HIDE_DURATION_MS = 0L

/**
 * 瞬时隐藏的 API 档门（纯函数，单测锁定）：API >= 30 才有平台 insets 动画控制面，<30 的
 * compat backport 是 no-op（核查结论见文件 KDoc），必须走普通 hide()。
 */
internal fun supportsInstantInsetsAnimation(sdkInt: Int): Boolean = sdkInt >= 30

/**
 * 瞬时隐藏的目标 inset（纯函数，单测锁定）：systemBars 的隐态 inset 恒为零（状态栏 top/
 * 导航栏 bottom 归零，与实时值无关）——onReady 里一步到位的置零计算单源，零值即隐态。
 */
internal fun instantHideTargetInsets(currentInsets: Insets): Insets = Insets.of(0, 0, 0, 0)

/**
 * 系统栏瞬时隐藏（口径见文件 KDoc）：API >= 30 走零时长控制会话，<30 或控制权获取失败
 * （onCancelled）回退普通 hide()。须在主线程调用（控制会话回调与 setInsetsAndAlpha 均要求
 * Looper 线程；调用方 LaunchedEffect/DisposableEffect 均在主线程）。
 *
 * @param controller 目标窗口的 WindowInsetsControllerCompat（Activity 窗或 Dialog 窗均可）
 * @param types 要隐藏的 WindowInsetsCompat.Type 位掩码（调用方现用 systemBars()）
 */
internal fun hideSystemBarsInstantly(
    controller: WindowInsetsControllerCompat,
    types: Int,
) {
    if (!supportsInstantInsetsAnimation(Build.VERSION.SDK_INT)) {
        controller.hide(types)
        return
    }
    controller.controlWindowInsetsAnimation(
        types,
        INSTANT_HIDE_DURATION_MS,
        null, // interpolator：零时长无插值语义
        null, // cancellationSignal：取消/失败统一走 onCancelled 兜底，不另设双通道
        object : WindowInsetsAnimationControlListenerCompat {
            override fun onReady(
                animationController: WindowInsetsAnimationControllerCompat,
                types: Int,
            ) {
                // inset 一步置零（隐态）后立即以「隐」收束：0ms 会话内完成，无平台动画拖尾
                animationController.setInsetsAndAlpha(
                    instantHideTargetInsets(animationController.currentInsets),
                    /* alpha = */ 1f,
                    /* fraction = */ 0f,
                )
                animationController.finish(/* show = */ false)
            }

            override fun onFinished(animationController: WindowInsetsAnimationControllerCompat) = Unit

            override fun onCancelled(animationController: WindowInsetsAnimationControllerCompat?) {
                // 控制权获取失败（被系统手势占用/请求立即失败）→ 回退普通 hide()（带平台
                // 动画，好于不隐藏；兼容 <30 语义）
                controller.hide(types)
            }
        },
    )
}
