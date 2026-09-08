package media.qimeng.app.feature.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import media.qimeng.app.core.model.AssetDetail

/**
 * 图片全屏查看覆盖层（D1）：排版态单击图片舞台打开，铺满整屏盖住顶行/底节——
 * **排版基准不动摇**：排版态原样保留，全屏只是其上的一次叠加（独立窗口，不重排排版布局）。
 *
 * 实现选型（冻结口径二选一取「Compose 全屏 Dialog」，弃宿主 Box overlay；D2 起骨架下沉到
 * 共享外壳 [FullscreenOverlayShell]，图片/视频覆盖层单源复用）：
 * - **宿主树内 overlay 到不了真屏**（01:15 模拟器实测推翻内联 Box 初版）：壳层
 *   QimengNavHost 用 Scaffold{ NavHost(Modifier.padding(innerPadding)) } 把内容钉在
 *   系统栏 inset 收窄区内，且该模拟器 override inset 在系统栏隐藏后 top 不归零——Box
 *   覆盖层顶部永远差一条状态栏高白条（证据 d1-fullscreen.png），真实设备同形态风险同源；
 * - **Dialog 开独立窗口**：窗口 frame=整屏（同 Activity 窗实测 [0,0][1080,2400]），
 *   usePlatformDefaultWidth=false 解除平台宽度钳制 + decorFitsSystemWindows=false 不消费
 *   inset → 内容物理铺满整屏（实测截图 d1-fullscreen-final.png，节点 bounds [0,0][1080,2400]）；
 * - **系统栏隐藏须由本 Dialog 窗口自己做**（01:21 实测修正初版「单源在 Activity 窗」的
 *   错误假设）：系统栏可见性跟随**焦点窗口**的请求——Dialog 取焦后默认把系统栏带回来
 *   （证据 d1-fullscreen-v2.png 顶/底栏可见），故在窗口上按既有沉浸语义
 *   （BEHAVIOR_DEFAULT + hide/show systemBars）隐藏，onDispose 恢复；Activity 窗的
 *   SystemBarsImmersiveEffect（overlay 开关已并入其 chrome 表达式）继续管排版态显隐，
 *   两窗各管焦点期显隐、不互相打架；
 * - **返回语义**：Dialog dismissOnBackPress → onDismissRequest=退出回排版态（与 BackHandler
 *   同用户语义，且天然先于路由级返回消费）；dismissOnClickOutside=false——内容铺满窗口
 *   不存在「外部」，单击退出统一由 ZoomImageView 单击手势走 onDismiss。
 *
 * @param asset 当前资产（随兄弟切换 push 新路由实例，本覆盖层实例不换资产）
 * @param onSiblingNavigate 左右滑兄弟切换（与排版态同一回调链直传，DetailScreen 单源）
 * @param onDismiss 退出覆盖层（单击/系统返回共用）
 */
@Composable
internal fun ImageFullScreenOverlay(
    asset: AssetDetail,
    onSiblingNavigate: (delta: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    FullscreenOverlayShell(onDismiss = onDismiss) {
        ZoomableOriginalImage(
            asset = asset,
            modifier = Modifier.fillMaxSize(),
            onSingleTap = onDismiss,
            onSwipe = onSiblingNavigate,
        )
    }
}
