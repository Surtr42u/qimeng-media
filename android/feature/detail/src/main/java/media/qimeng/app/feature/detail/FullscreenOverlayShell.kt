package media.qimeng.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 全屏查看覆盖层共享外壳（D2 抽取）：**Dialog 独立窗口 + insets 隐显 + 黑底铺满 + 返回退出**
 * 骨架单源——任务I I7 起仅视频（VideoFullScreenOverlay，K2 单级横屏全屏）使用（图片覆盖层
 * ImageFullScreenOverlay.kt 随沉浸复刻退役：舞台本就全出血、单击切 chrome 成为主形态，
 * 裁决记档见 ImageStage KDoc），骨架保留单源待视频专用化收拢。
 *
 * 选型依据沿用 D1 实测（D1 KDoc 已退役删除，结论留档于此）：
 * - **宿主树内 overlay 到不了真屏**：壳层 QimengNavHost 用 Scaffold padding 把内容钉在
 *   inset 收窄区内，且部分设备系统栏隐藏后 top inset 不归零，Box 覆盖层顶部永远差一条白条；
 * - **Dialog 开独立窗口**：usePlatformDefaultWidth=false 解除宽度钳制 + decorFitsSystemWindows
 *   =false 不消费 inset → 内容物理铺满整屏；
 * - **系统栏由本 Dialog 窗口自己做**：系统栏可见性跟随焦点窗口——Dialog 取焦后默认把系统栏
 *   带回来，故本组件按详情页既有沉浸语义（BEHAVIOR_DEFAULT + hide/show systemBars）在窗口上
 *   隐藏、onDispose 恢复；Activity 窗的 SystemBarsImmersiveEffect 继续管排版态显隐，两窗
 *   各管焦点期显隐、不互相打架；
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
        // 焦点窗口决定系统栏可见性：本窗口隐藏 systemBars，语义与详情页既有沉浸一致
        //（BEHAVIOR_DEFAULT：下滑可临时呼出）
        val view = LocalView.current
        var insetsController by remember { mutableStateOf<WindowInsetsControllerCompat?>(null) }
        DisposableEffect(view) {
            // Compose Dialog 的内容视图宿主实现 DialogWindowProvider（material3
            // ModalBottomSheet 同款取窗方式）
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            insetsController = dialogWindow?.let { WindowCompat.getInsetsController(it, view) }
            insetsController?.apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                // 关覆盖层先恢复本窗请求，焦点回到 Activity 窗后由既有沉浸 effect 接管显隐
                insetsController?.show(WindowInsetsCompat.Type.systemBars())
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
