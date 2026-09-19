package media.qimeng.app.feature.detail

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * 全屏查看覆盖层共享外壳（D2 抽取）：**Dialog 独立窗口 + 系统栏透明口径 + 黑底铺满 + 返回退出**
 * 骨架单源——任务I I7 起仅视频（VideoFullScreenOverlay，K2 单级横屏全屏）使用（图片覆盖层
 * ImageFullScreenOverlay.kt 随沉浸复刻退役：舞台本就全出血、单击切 chrome 成为主形态，
 * 裁决记档见 ImageStage KDoc），骨架保留单源待视频专用化收拢。
 *
 * 选型依据沿用 D1 实测（D1 KDoc 已退役删除，结论留档于此）：
 * - **宿主树内 overlay 到不了真屏**：壳层 QimengNavHost 用 Scaffold padding 把内容钉在
 *   inset 收窄区内，且部分设备系统栏隐藏后 top inset 不归零，Box 覆盖层顶部永远差一条白条；
 * - **Dialog 开独立窗口**：usePlatformDefaultWidth=false 解除宽度钳制 + decorFitsSystemWindows
 *   =false 不消费 inset → 内容物理铺满整屏；
 * - **系统栏由本 Dialog 窗口自己做**（任务S S7，2026-09-19 用户拍板「主流相册 app 风格：
 *   透明的手机状态栏」，纠正批S2「播放期恒隐」旧口径——横屏全屏属视频播放链，状态栏
 *   **透明显示不隐藏**，与 Activity 窗 SystemBarsImmersiveEffect 同口径）：Dialog 取焦后
 *   默认把系统栏带回来，S7 起顺势保留——本窗口不再 hide/show systemBars，仅保证栏底
 *   透明（API<35 用弃用的 statusBarColor=TRANSPARENT，androidx enableEdgeToEdge
 *   EdgeToEdgeApi26-30 同款官方路径；API 35+ 平台忽略该参数且强制 edge-to-edge 恒透明）
 *   并设浅色图标（覆盖层恒黑底 [FULLSCREEN_OVERLAY_BACKGROUND]）；图标明暗随窗口销毁
 *   自然失效，焦点回 Activity 窗后由既有效果接管，两窗互不影响；
 * - **返回语义**：onDismissRequest=onDismiss（视频=退横屏全屏回排版态，由调用方状态机裁决）；
 *   dismissOnClickOutside=false——内容铺满窗口不存在「外部」。
 *
 * @param onDismiss 退出覆盖层（系统返回共用；内容件自管单击/手势退出）
 * @param content 覆盖层内容件（视频=BiliPlayerView 桥接）
 */
@Composable
internal fun FullscreenOverlayShell(
    onDismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 解除平台默认宽度钳制（缺省按对话窗宽度居中收窄），配合 fillMaxSize 铺满整屏
            usePlatformDefaultWidth = false,
            // 不让窗口 decor 消费系统栏 inset：内容直铺窗口全域（含状态栏/导航栏背后）
            decorFitsSystemWindows = false,
            // 内容铺满窗口不存在点击外部区域；退出统一由内容件手势/系统返回承担
            dismissOnClickOutside = false,
        ),
    ) {
        // 系统栏透明显示（S7，与详情页 Activity 窗同口径）：本窗口不 hide/show
        // systemBars——Dialog 取焦默认把栏带回来即顺势保留（透明+浅色图标），机制与
        // 依据见类 KDoc 第三条
        val view = LocalView.current
        DisposableEffect(view) {
            // Compose Dialog 的内容视图宿主实现 DialogWindowProvider（material3
            // ModalBottomSheet 同款取窗方式）
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            // API<35 须显式透明栏底（主题默认不透明）：window.statusBarColor 是 API 35+
            // 弃用参数（平台忽略），androidx activity 1.10.1 enableEdgeToEdge 的
            // EdgeToEdgeApi26-30 即此官方路径（@Suppress DEPRECATION 同款）；API 35+
            // 且 targetSdk 35+ 强制 edge-to-edge 恒透明，无须设置
            if (dialogWindow != null && Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
            }
            // 覆盖层恒黑底（FULLSCREEN_OVERLAY_BACKGROUND）→ 状态栏/导航栏图标恒浅色
            //（含导航栏同口径，X8 记档惯例）
            dialogWindow?.let { WindowCompat.getInsetsController(it, view) }?.apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            onDispose {
                // S7 后无显隐需恢复（栏恒显示）；本窗口图标明暗设定随 Dialog 窗口销毁
                // 自然失效，焦点回 Activity 窗后由 SystemBarsImmersiveEffect 的既有
                // 设定接管（其键未变则值不变，无须协调）
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FULLSCREEN_OVERLAY_BACKGROUND),
        ) {
            content()
        }
    }
}

/** 全屏覆盖层底色（黑）：letterbox 区与排版态舞台同色调，且遮死底下排版内容不透出 */
private val FULLSCREEN_OVERLAY_BACKGROUND = Color.Black
