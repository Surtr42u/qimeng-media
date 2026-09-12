package media.qimeng.app.feature.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import media.qimeng.app.feature.detail.video.BiliPlayerView
import media.qimeng.app.feature.detail.video.TimelineTagEntity

/**
 * 视频全屏覆盖层内容件（任务K K2 单级横屏全屏）：横屏视频排版态播放中点全屏钮（状态机
 * LANDSCAPE 态）挂载，独立 Dialog 窗口铺满整屏盖住详情页排版（外壳选型与实测依据见
 * [FullscreenOverlayShell]）。仅横屏态挂载，无竖屏全屏形态（D2 两级制已推翻，拍板 #23）。
 *
 * **画面交接（同一 ExoPlayer 双视图）**：播放器由 VideoStage 持有，本覆盖层创建第二个
 * BiliPlayerView 以 `adoptCurrentState=true` 挂同一播放器——surface 随挂载迁移、播放零中断，
 * 音量/倍速先快照后回写（新视图镜像=默认值，直挂会把用户已调状态打回默认，见
 * BiliPlayerView.setPlayer）。关闭时退场序列 `detachPlayer → onReleasePlayerSurface`
 * 把 surface 迁回排版态视图（不迁移则排版态只留末帧=画面假死）。
 *
 * 覆盖层视图恒 `setFullscreen(true)`：全屏态手势统一走全屏档（单击显隐控制器/
 * 双击播停，G1/G2 横屏语义），全屏钮图标=退出全屏、控制器默认隐藏、底栏避让导航栏。
 *
 * @param player VideoStage 持有的 ExoPlayer（唯一播放实例，覆盖层只挂不建）
 * @param tagEntities 时间轴标签（与排版态同源，变化经 LaunchedEffect 同步到本视图）
 * @param onFullscreenToggle 全屏钮点击（VideoStage 经防抖+状态机裁决：LANDSCAPE 态=
 *   退出全屏回排版态，写 PORTRAIT）
 * @param onExit 退出覆盖层（系统返回 Dialog onDismissRequest 与顶栏返回共用；
 *   LANDSCAPE→排版态由 VideoStage 状态机裁决）
 * @param onBookmarkTap 书签钮（与排版态同链：暂停+开标签对话框；AlertDialog 窗口后建者
 *   置顶，z 序天然盖过本覆盖层，对话框可正常交互；入参=本覆盖层桥接视图，共享播放器语义
 *   经视图公开面执行）
 * @param onTagChipLongPress 长按标签芯片（与排版态同链，菜单对话框同上盖过覆盖层）
 * @param onReleasePlayerSurface 退场时把播放器 surface 迁回排版态视图（VideoStage 实现）
 */
@UnstableApi
@Composable
internal fun VideoFullScreenOverlay(
    player: ExoPlayer,
    tagEntities: List<TimelineTagEntity>,
    onFullscreenToggle: () -> Unit,
    onExit: () -> Unit,
    onBookmarkTap: (BiliPlayerView) -> Unit,
    onTagChipLongPress: (TimelineTagEntity) -> Unit,
    onReleasePlayerSurface: () -> Unit,
) {
    // 桥接视图引用（退场清理 + 标签同步用；factory 一次性闭包经 State 捕获）
    var bridgeView by remember { mutableStateOf<BiliPlayerView?>(null) }

    // 退场序列：先摘听众/断引用，再交还 surface（顺序无关二重保险：rebind 直接底层重绑
    // 画面目标，detach 的置空只作用于本退场视图，见 BiliPlayerView 两方法 KDoc）
    DisposableEffect(player) {
        onDispose {
            bridgeView?.detachPlayer()
            onReleasePlayerSurface()
            bridgeView = null
        }
    }

    FullscreenOverlayShell(onDismiss = onExit) {
        AndroidView(
            factory = { ctx ->
                BiliPlayerView(ctx).apply {
                    setPlayer(player, adoptCurrentState = true)
                    // 覆盖层恒为全屏态（K2 单级横屏全屏）：按钮图标=退出全屏、控制器默认隐藏、
                    // 底栏避让导航栏；层内不随配置回写，方向变化只影响窗口尺寸
                    setFullscreen(true)
                    // ENDED 态换 surface 不产生新帧（解码器已释放）→ 覆盖层黑屏；
                    // 同位 seek 强制重渲染末帧（G9 口径内：ENDED 本就停在末帧位置）
                    if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(player.currentPosition)
                    }
                    onFullscreen = onFullscreenToggle
                    onBack = onExit
                    onBookmark = { onBookmarkTap(this) }
                    onTagLongPress = onTagChipLongPress
                }.also { bridgeView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // 时间轴标签与排版态同源同步（覆盖层内添加对话框确认后 tagEntities 变化直达本视图）
    LaunchedEffect(bridgeView, tagEntities) {
        bridgeView?.updateTimelineTags(tagEntities)
    }
}
