package media.qimeng.app.feature.detail

import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.delay
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.icon.PlayIcon
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors
import media.qimeng.app.feature.detail.video.BiliPlayerView
import media.qimeng.app.feature.detail.video.SCREEN_ORIENTATION_UNSPECIFIED
import media.qimeng.app.feature.detail.video.TimelineTagEntity
import media.qimeng.app.feature.detail.video.VideoFullscreenCommand
import media.qimeng.app.feature.detail.video.VideoFullscreenLevel
import media.qimeng.app.feature.detail.video.VideoFullscreenStateMachine
import media.qimeng.app.feature.detail.video.VideoStageMode
import media.qimeng.app.feature.detail.video.VideoStageStateMachine
import media.qimeng.app.feature.detail.video.formatDurationMs
import media.qimeng.app.feature.detail.video.isLandscapeVideoSize
import media.qimeng.app.feature.detail.video.rememberVideoPlayerState
import media.qimeng.app.feature.detail.video.resolveOrientationWrite
import media.qimeng.app.feature.detail.video.toScreenOrientation

// ---------- 页面私有尺寸档（本文件单源；来源注释随条目） ----------

/** 舞台中央播放钮直径（48dp Material 最小触控档再升一级，防误触边缘） */
private val STAGE_PLAY_BUTTON_SIZE = 64.dp

/** 播放钮半透明底透明度（黑底白图标，Web 播放按钮同观感） */
private const val STAGE_PLAY_SCRIM_ALPHA = 0.6f

/** 「已看完」徽标外边距（贴舞台左上角，8dp 通用贴边档） */
private val STAGE_WATCHED_BADGE_PADDING = 8.dp

/** 快捷标记胶囊圆角（S1c 对齐旧版快捷键胶囊 16dp 档；与芯片 100dp 胶囊语言区分，任务书冻结） */
private val TAG_QUICK_CAPSULE_CORNER_RADIUS = 16.dp

/** 快捷标记胶囊内边距（S1c 对齐旧版快捷键：横 16dp / 纵 10dp；纵 10dp 无既有 token，本文件单源） */
private val TAG_QUICK_CAPSULE_PADDING = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/** 进度轮询间隔（3d 设计从简：播放中每 1s 读一次位置喂节流策略；策略自身 5s 放行一次） */
private const val POSITION_POLL_INTERVAL_MS = 1000L

/** 海报态横滑最小距离（dp，I7）：对齐 ZoomImageView 冻结手势阈值（60dp） */
private const val POSTER_SWIPE_DISTANCE_DP = 60

/** 海报态横滑最小横向速度（px/s，I7）：对齐 ZoomImageView 冻结手势阈值（800，VelocityTracker 同单位） */
private const val POSTER_SWIPE_VELOCITY = 800f

/**
 * 播放失败轻提示自动隐藏时长（ms，2026-09-13 BUG-B 次修）：桥接件顶部指示器同档
 * （TOP_INDICATOR_DELAY=1500）再放余量——错误提示需读一句文案+给出重试指引。
 */
private const val PLAY_FAILED_HINT_DURATION_MS = 2500L

/** 播放错误 logcat 标签（Qimeng* 家族命名；真机排障 grep 用，见 handlePlayerError） */
private const val PLAYBACK_ERROR_LOG_TAG = "QimengVideoError"

/**
 * 海报态横滑切件判定（任务V V2 起 ViewPager 语义：慢拖距离达阈值 或 快甩速度达阈值，任一
 * 即切）。V1 是「距离 且 速度」双与门，慢拖（位移够、收尾速度低）被挡死不切件——用户实测
 * 复核后拍板放宽。方向取拖动符号：dragX<0=手指左滑=+1（下一件）。纯函数无 Compose 依赖，
 * 行为由 SiblingSwipePolicyTest 锁定。
 */
internal fun posterSwipeDelta(dragX: Float, velocityX: Float, swipeDistancePx: Float): Int? = when {
    abs(dragX) > swipeDistancePx || abs(velocityX) > POSTER_SWIPE_VELOCITY -> if (dragX < 0f) 1 else -1
    else -> null
}

/**
 * 详情海报分层裁决（协议批 2026-09-18「md 先行」，纯函数无 Compose 依赖，JVM 单测锁定
 * 见 VideoPosterLayeringTest）：lg 档（1024）不在服务端预生成范围，首开详情直等 lg 会
 * 触发现场 ffmpeg 生成（秒级起步）；md 档（512）与列表网格同源、开机预生成——
 * - [VideoPosterLayering.baseModel]：有 md 先挂 md（立即出图）；无 md（旧服务端/异常）
 *   退化直接 lg（现状行为）；
 * - [VideoPosterLayering.upgradeToLg]：仅当 md 已成功加载且 lg 存在且**与 md 不同 URL**
 *   才放行 lg 覆盖层（md==lg 防重复请求；md 未就绪不放行=lg 恰好只发这一次）。
 */
internal data class VideoPosterLayering(
    /** 底层海报数据源（md 优先，无 md 回退 lg；两者皆 null = 走占位） */
    val baseModel: String?,
    /** 是否挂 lg 覆盖层（md 就绪后的升级换图） */
    val upgradeToLg: Boolean,
)

/** [VideoPosterLayering] 裁决单源（口径见其 KDoc） */
internal fun videoPosterLayering(
    thumbUrlMd: String?,
    thumbUrl: String?,
    mdSucceeded: Boolean,
): VideoPosterLayering = VideoPosterLayering(
    baseModel = thumbUrlMd ?: thumbUrl,
    upgradeToLg = mdSucceeded && thumbUrlMd != null &&
        thumbUrl != null && thumbUrl != thumbUrlMd,
)

// 快捷标签字面量不再本地定义：写入用 TimelineTagColors.HEART_TAG/STAR_TAG（单源，
// 2026-09-07 审查 P2 前❤字面量三处独立定义且口径分叉，已收敛）。

/** G6 全屏切换防抖窗口（ms）：快速连点全屏键不连续请求方向（旧版 MediaDetailFragment:1157-1160 同款语义） */
private const val FULLSCREEN_TOGGLE_DEBOUNCE_MS = 800L

/**
 * 视频舞台（3c 视频态 + 3d 全量接线；任务I I7 沉浸复刻改版）：海报态（缩略图 + 中央播放钮 +
 * 已看完徽标）单击起播 → [BiliPlayerView] 整体桥接（ADR-0014 例外③）接管触摸，进手势循环
 * （G1~G9 冻结口径）。
 *
 * 起播幂等由 [VideoStageStateMachine] 保证（已播放的重复点击直接返回，不重置进度/不重装源）；
 * ENDED 再播回 0 由控件内 togglePlayPause/startPlayback 承担（G9），状态机同步记账。
 *
 * I7 沉浸接线（GUIDE_UI §详情页 L163/L168/L279）：
 * - **海报态横滑切兄弟**：单指横滑（V2 起 ViewPager 语义：距离或速度任一达阈值即切——
 *   慢拖可切件，阈值仍对齐 ZoomImageView 60dp/800 冻结档）→ [onSiblingNavigate](±1)；
 *   播放态不接（播放器手势接管，对齐旧版「预览态可横滑」）；
 * - **播放中按返回先退 chrome 浏览模式**：BackHandler 拦截（播放器活动期 enabled）→ 暂停 +
 *   状态机退回海报态 + [onExitToChromeBrowse]（chrome 恢复显示）；播放器已 prepare 的同源
 *   媒体保留位置，再点播放走同源续播不归零（L165 同款语义）；
 * - **播放器活动态上报**：[onPlayerActiveChanged]（海报态=false，播放/暂停/ENDED=true）——
 *   DetailScreen 据此让 chrome 让位播放器（chromeEffective=false；S9 2026-09-19 拍板后
 *   播放态系统栏改经 [onPlaybackChromeChanged] 随控制条显隐——B 站竖屏两态语义，
 *   详情顶栏播放态恒不显示）；
 * - **播放态点按（S2 2026-09-19 拍板「单击显示视频的 ui 和上方的 ui；双击才是暂停」，
 *   S9 同日定稿语义：播放 ui=控制条+透明状态栏，不含详情顶栏）**：桥接件手势映射已
 *   改版（BiliPlayerView 适配点⑪）——单击切「播放器控制器」显隐并联动系统状态栏、
 *   双击播停；控制器显隐经 [onControllerVisibilityChanged]（桥接件 showController
 *   单点）→ 本舞台 [onPlaybackChromeChanged] 转发 DetailScreen 镜像，无第二事实源。
 *
 * 播放错误承接（2026-09-13 用户真机反馈 BUG-B 次修）：PlaybackException → 状态机回退
 * 海报态（全屏态先退层级，均走既有状态机 API，见 [handlePlayerError]）+ 舞台顶部轻提示
 * 「播放失败，点按重试」（detail_video_play_failed）；海报态点按即重试链路。
 *
 * 3d 接线拓扑（谁持有谁）：本舞台持有 [rememberVideoPlayerState]（ExoPlayer）与桥接视图引用；
 * VM 持节流/打点策略。回调链 = 播放器事件 → 本舞台 → DetailScreen 具名参数 → ViewModel：
 * - 续播：[startPositionMs]（VM WatchState 口径）在 prepare 时一次性生效；
 * - 进度：播放中 1s 轮询 currentPosition → [onPositionChanged] → VM 节流放行即 PUT progress；
 * - 打点：起播（onIsPlayingChanged=true）→ [onPlaybackStarted] → VM play 打点一次；
 * - 标签：[timelineTags] 映射桥接实体 → updateTimelineTags（芯片点击 seek 为控件内建行为；
 *   长按芯片/书签按钮经 [onTagLongPress]/[onBookmark] 回 Compose 侧对话框）。
 *
 * K2 单级横屏全屏（用户 2026-09-09 拍板 #23 推翻 D2 两级制，对齐旧版 MediaDetailFragment
 * :1163-1183）：全屏钮仅对横屏视频（宽>高，[isLandscapeVideoSize] 判定）生效——排版态
 * 点下即固定锁横屏（SCREEN_ORIENTATION_LANDSCAPE，非 SENSOR 防双横屏乱闪），画面由
 * [VideoFullScreenOverlay] Dialog 承接（surface 交接播放零中断）；竖屏视频点击无反应
 * （旧版同款）；退出（覆盖层全屏钮/系统返回）写 PORTRAIT 回排版态。层级真源=
 * [VideoFullscreenStateMachine]（纯 JVM 可测），方向写入集中在 [applyFullscreenCommand]；
 * 覆盖层与排版态视图共用同一 ExoPlayer（surface 交接，见 BiliPlayerView
 * setPlayer/rebindPlayer/detachPlayer）。
 *
 * @param watched 已看完徽标（3d WatchState 口径，海报态左上角显示）
 * @param startPositionMs 起播位置毫秒（VM 按断点秒×1000 计算；已看完=0 重播）
 * @param timelineTags 时间轴标签（VM 已按 timeMillis 升序拉取）
 * @param backdrop 舞台底色（任务K K1 单源：stageBackdropColor 于 DetailScreen 裁决后经
 *   [DetailMediaStage] 下发——chrome 显=主题底/沉浸或播放中=纯黑；本舞台用于海报态错误底
 *   （占位底 exp#4 起改品牌灰 secondaryContainer，见海报态内注释），舞台盒底色由调用方
 *   modifier.background(backdrop) 打底）
 * @param modifier 舞台尺寸段（I7 沉浸：调用方传 fillMaxSize+backdrop 打底，海报/播放两态共用；
 *   播放态内部 AndroidView 自行 fillMaxSize 填满舞台盒——V2 修复播放器贴顶）
 * @param onSiblingNavigate 左右滑切换相邻资产回调（海报态横滑 V2 起距离或速度任一达阈值即切，
 *   慢拖可切件；播放态不接——播放器手势接管，K3 定案）
 * @param onToggleChrome 沉浸模式 chrome 开关回调（视频态不接：海报单击=起播（L163 优先，
 *   与 L271 冲突取旧版语义并记档）；播放单击=S2 2026-09-19 拍板改切播放态 chrome（见
 *   [onPlaybackChromeChanged]）；保留参数与图片舞台签名对齐）
 * @param onPlayerActiveChanged 播放器活动态上报（I7：chrome 让位的驱动源；S9 后播放态
 *   系统栏改随 playbackChromeVisible，本链只承担 chromeEffective/底色/锁滚口径）
 * @param onPlaybackChromeChanged 播放态系统栏显隐镜像上报（S2 2026-09-19 拍板引入，
 *   S9 同日定稿语义「b 站手机竖屏的那种」：桥接件 showController 单点上报转发
 *   DetailScreen，驱动播放态系统状态栏随控制条显隐——详情顶栏不再消费本镜像（播放态
 *   恒不显示）；G9 自动隐藏/拖拽隐/长按隐/ENDED 强制显同拍同源（仅视频分支消费）
 * @param onExitToChromeBrowse 播放中按返回退 chrome 浏览模式后 chrome 恢复显示（I7，L279）
 * @param onPlaybackStarted 起播回调（打点 play 用；VM 侧幂等，重复回调安全）
 * @param onPositionChanged 播放位置 tick（秒；VM 侧节流，逐 tick 喂入安全）
 * @param onAddTimelineTag 添加时间轴标签（timeMillis=对话框打开时的播放位置毫秒）
 * @param onDeleteTimelineTag 删除时间轴标签（长按菜单入口，参数=服务端标签 id）
 */
@UnstableApi
@Composable
internal fun VideoStage(
    asset: AssetDetail,
    watched: Boolean,
    startPositionMs: Long,
    timelineTags: List<TimelineTag>,
    /** 舞台底色（任务K K1 单源下发，见 @param backdrop） */
    backdrop: Color,
    modifier: Modifier,
    onSiblingNavigate: (delta: Int) -> Unit,
    onToggleChrome: () -> Unit,
    onPlayerActiveChanged: (Boolean) -> Unit,
    /** 播放态 chrome 显隐上报（S2 2026-09-19 拍板，见类 KDoc 与 @param 说明） */
    onPlaybackChromeChanged: (Boolean) -> Unit,
    onExitToChromeBrowse: () -> Unit,
    onPlaybackStarted: () -> Unit,
    onPositionChanged: (positionSeconds: Double) -> Unit,
    onAddTimelineTag: (timeMillis: Long, name: String) -> Unit,
    onDeleteTimelineTag: (tagId: String) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val playerState = rememberVideoPlayerState()
    val machine = remember { VideoStageStateMachine() }
    // UI 侧镜像（machine 为逻辑真源；写入点全在主线程：起播 / ENDED / 恢复播放回调）
    var stageMode by remember { mutableStateOf(VideoStageMode.POSTER) }
    // 桥接视图引用（3d：标签 update / seek / 暂停恢复 / 当前位置都走公开面）
    var playerView by remember { mutableStateOf<BiliPlayerView?>(null) }
    // 播放失败轻提示（2026-09-13 BUG-B 次修）：错误回海报态后短暂展示「播放失败，点按重试」，
    // 由 handlePlayerError 置位、下方 LaunchedEffect 到点熄灭
    var showPlayFailedHint by remember { mutableStateOf(false) }
    // 播放中镜像（进度轮询的开关；onIsPlayingChanged 主线程写入）
    var isPlaying by remember { mutableStateOf(false) }
    // G6 全屏防抖时间戳（factory 一次性闭包经 State 捕获最新值；仅算间隔不参与业务口径）
    var lastFullscreenToggleAt by remember { mutableLongStateOf(0L) }
    // K2 单级全屏：层级显式态（fullscreenMachine 为逻辑真源，镜像同 stageMode 模式）。
    // 全屏钮进/退裁决（含竖屏视频 no-op）全在状态机；方向写入集中在
    // applyFullscreenCommand（全仓唯一方向写入点语义延续）
    val fullscreenMachine = remember { VideoFullscreenStateMachine() }
    var fullscreenLevel by remember { mutableStateOf(VideoFullscreenLevel.NONE) }

    // K2 横屏视频判定（旧版「仅横屏视频可全屏」的放行门槛）：宽高取服务端探测落库的
    // AssetDetail.width/height；任一缺失按非横屏处理 → 全屏钮 no-op（未知尺寸不放行，
    // 宁紧勿松）。纯函数单源在 video 包（JVM 单测锁未知/相等尺寸口径）
    val isLandscapeVideo = isLandscapeVideoSize(asset.width, asset.height)

    /** 执行单级全屏状态机指令：层级镜像 + 方向写入（全仓唯一方向写入点语义延续）。
     *  U11 批次C：写入条件化——仅目标 ≠ 当前 requestedOrientation 才落写
     *  （resolveOrientationWrite 纯函数裁决；同值写=nubia ROM 启动旋转开关翻转
     *  主嫌疑，U10-1 三步实验），中途横竖切换跨方向必写语义不变。 */
    fun applyFullscreenCommand(command: VideoFullscreenCommand) {
        fullscreenLevel = command.level
        val activity = context.findActivity() ?: return
        // 横屏全屏固定 LANDSCAPE 而非 SENSOR（旧版 MediaDetailFragment.kt:1171 口径：固定
        // 横屏，避免 SENSOR 在两个横屏方向间切换乱闪）；退出回排版态写 PORTRAIT
        resolveOrientationWrite(
            activity.requestedOrientation,
            command.orientation.toScreenOrientation(),
        )?.let { activity.requestedOrientation = it }
    }

    /** 全屏钮（排版态与覆盖层视图共用）：800ms 防抖后交状态机裁决进/退（旧版
     *  Fragment:1157-1160 同款语义，具名常量 FULLSCREEN_TOGGLE_DEBOUNCE_MS）；
     *  竖屏视频状态机返回 null → 不产生任何方向写入（点击无反应，旧版同款） */
    fun requestFullscreenToggle() {
        val now = System.currentTimeMillis()
        if (now - lastFullscreenToggleAt >= FULLSCREEN_TOGGLE_DEBOUNCE_MS) {
            lastFullscreenToggleAt = now
            fullscreenMachine.onFullscreenToggle(isLandscapeVideo)?.let(::applyFullscreenCommand)
        }
    }

    /** 覆盖层退出请求（系统返回/覆盖层顶栏返回共用）：状态机回排版态（LANDSCAPE→NONE，写竖屏） */
    fun exitFullscreenLevel() {
        fullscreenMachine.onExitRequested()?.let(::applyFullscreenCommand)
    }

    // 播放器 → 状态机/回调同步：ENDED 记账（G9），恢复播放补账（视图内再播路径）；
    // isPlaying 翻转同时驱动打点 play（VM 侧幂等去重，重复回调安全）与进度轮询开关
    DisposableEffect(playerState) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    machine.onPlaybackEnded()
                    stageMode = machine.mode
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) {
                    machine.onPlaybackStarted()
                    stageMode = machine.mode
                    onPlaybackStarted()
                }
                isPlaying = playing
            }
        }
        playerState.player.addListener(listener)
        onDispose { playerState.player.removeListener(listener) }
    }

    // K2 配置变化监听（仅随配置变化评估——键不含层级镜像）：LANDSCAPE 锁被外力破（分屏/
    // 自由窗口等忽略 requestedOrientation 的环境转回竖屏）→ 状态机防御回退写竖屏。进入
    // 横屏锁后、系统转屏落地前的瞬态竖屏窗口内本 effect 不评估——旧实现以层级镜像为键，
    // 进锁时会拿陈旧竖屏配置把刚写入的横屏锁立刻自反（「乱闪」根因之一）；排版态（NONE）
    // 自由旋转不迁移
    LaunchedEffect(configuration) {
        fullscreenMachine.onRotationChanged(
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )?.let(::applyFullscreenCommand)
    }

    // D2 方向恢复兜底（审查两轮清偿）：requestedOrientation 是 Activity 级粘性属性——
    // 无条件写 PORTRAIT 会把「只看过排版态视频、从未进全屏」的整个 App 永久锁竖屏；
    // 改快照恢复后又发现转场窗口洞（详情页互 push 时新页先组合、旧页后 dispose，新页
    // 快照会捕到旧页 exitToNone 残留的 PORTRAIT 并代代相传，会话级锁竖屏）。全仓唯一
    // 方向写入点=本文件 applyFullscreenCommand（grep 证），manifest 不锁方向 → App 的
    // 自然基线恒 UNSPECIFIED，离场一律恢复 UNSPECIFIED：手机竖屏持机自然回竖屏
    // （「横屏全屏态退出卡横屏」修复语义保持），全屏残留锁也必然被解掉。
    // U11 批次C：恢复写同样条件化——冷启动恢复期组合抖动触发的 onDispose 常是
    // UNSPECIFIED→UNSPECIFIED 冗余写（U10-1 实验的启动触发面），裁掉；真实
    // 横屏锁残留（当前≠UNSPECIFIED）照写不误。
    DisposableEffect(Unit) {
        onDispose {
            val activity = context.findActivity()
            if (activity != null) {
                resolveOrientationWrite(
                    activity.requestedOrientation,
                    SCREEN_ORIENTATION_UNSPECIFIED,
                )?.let { activity.requestedOrientation = it }
            }
        }
    }

    // 进度轮询（3d 设计从简）：播放中每 1s 读 ExoPlayer 位置喂 VM 节流策略；
    // 暂停/ENDED 自然停轮询（isPlaying=false），补报由 VM 侧生命周期 force 承担
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            onPositionChanged(playerState.player.currentPosition / 1000.0)
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    // 播放失败轻提示自动熄灭（BUG-B 次修）：置位后到点隐藏；重复错误重复置位自然续期
    LaunchedEffect(showPlayFailedHint) {
        if (showPlayFailedHint) {
            delay(PLAY_FAILED_HINT_DURATION_MS)
            showPlayFailedHint = false
        }
    }

    // 时间轴标签（3d）：领域模型 → 桥接实体映射（timeMillis 直传；实体遗留字段
    // recordKey/fileName 填本资产标识、createdAtMillis 桥接件仅作展示来源不消费填 0；
    // timelineTagId 以列表序号占位——桥接件不消费该字段，域 id 由长按菜单经序号反查）。
    // 颜色说明（S1a 2026-09-12 拍板「对齐旧版」后口径）：芯片红/金配色由桥接件按
    // TimelineTagColors.HEART_PREFIX/STAR_PREFIX 裸前缀判定自绘（bg_timeline_tag_like/fav，
    // 裸前缀同时命中带/不带变体符写法），Compose 侧不重复注入颜色；服务端 color 已不再
    // 透传给桥接件（显示恒按前缀档，协议镜像 TimelineTag.color 保留）；TimelineTagColors
    // 亦供添加对话框快捷键（写入字面量单源）。
    val tagEntities = remember(timelineTags, asset.id) {
        timelineTags.mapIndexed { index, tag ->
            TimelineTagEntity(
                timelineTagId = index.toLong(),
                recordKey = asset.id,
                fileName = asset.fileName,
                timeMillis = tag.timeMillis,
                name = tag.name,
                createdAtMillis = 0L,
            )
        }
    }
    LaunchedEffect(playerView, tagEntities) {
        playerView?.updateTimelineTags(tagEntities)
    }

    // 标签添加对话框状态（wasPlaying 快照：打开前暂停，dismiss 后恢复——冻结口径；
    // tagPositionMs=打开瞬间位置快照，S1c 起对话框改传入不再读播放器，副标题与写入
    // timeMillis 同源——确认时再读会在 Sheet 停留/收起动画窗口内漂移取数点）
    var showTagDialog by remember { mutableStateOf(false) }
    var wasPlayingBeforeDialog by remember { mutableStateOf(false) }
    var tagPositionMs by remember { mutableLongStateOf(0L) }
    // 长按菜单目标（桥接实体；域 id 由菜单经 tagEntities 序号反查，避免实体遗留字段伪造）
    var menuTag by remember { mutableStateOf<TimelineTagEntity?>(null) }

    // factory 一次性闭包的陈旧捕获防线：经 State 在调用点取最新值
    val latestOnAdd by rememberUpdatedState(onAddTimelineTag)
    // I7 同款防线（回调参数为父级每次重组新建的 lambda，手势/Effect 键取 Unit + 最新值桥）
    val latestOnSiblingNavigate by rememberUpdatedState(onSiblingNavigate)
    val latestOnPlayerActiveChanged by rememberUpdatedState(onPlayerActiveChanged)
    val latestOnExitToChromeBrowse by rememberUpdatedState(onExitToChromeBrowse)
    // S2 播放态 chrome 上报（桥接件 factory 一次性闭包经最新值桥转发，同上防线）
    val latestOnPlaybackChromeChanged by rememberUpdatedState(onPlaybackChromeChanged)

    fun beginPlayback() {
        // 视频源 = asset.origUrl（缺直链则保持海报，理论不发生的兜底）
        val uri = asset.origUrl ?: return
        // 幂等起播：已 PLAYING 直接返回（状态机拦下重复点击）
        val command = machine.start() ?: return
        stageMode = machine.mode
        // 同源续播（I7）：播放中按返回退 chrome 浏览模式（不销毁播放器）后再次起播，播放器
        // 仍持有已 prepare 的同源媒体 → 只续播不重装源（进度不归零，GUIDE_UI L165 同款语义；
        // ENDED 态先 seek 回 0 对齐控件内 startPlayback 口径）；仅首次/换源才 prepare
        val currentUri = playerState.player.currentMediaItem?.localConfiguration?.uri?.toString()
        val sameSourcePrepared = currentUri == uri &&
            playerState.player.playbackState != Player.STATE_IDLE
        if (sameSourcePrepared) {
            if (playerState.player.playbackState == Player.STATE_ENDED) {
                playerState.player.seekTo(0L)
            }
        } else {
            // ENDED 路径 restartFromZero=true → 回 0（G9；实际该路径由控件内按钮承担，此处对齐口径）
            playerState.prepare(uri, if (command.restartFromZero) 0L else startPositionMs)
        }
        playerState.play()
    }

    /** 关标签对话框并按快照恢复播放（dismiss/添加共用；恢复走 startPlayback=ENDED 回 0 同口径） */
    fun dismissTagDialog() {
        showTagDialog = false
        if (wasPlayingBeforeDialog) playerView?.startPlayback()
    }

    /** 书签钮（排版态/覆盖层视图共用同链）：wasPlaying 快照 → 位置打开瞬间快照 → 暂停 → 开
     * 标签对话框（dismiss/添加后恢复；spec 冻结口径「打开前暂停、dismiss 后恢复」）。位置
     * 在打开瞬间捕获（S1c 对齐旧版「当前时间」副标题口径：对话框只收快照不读播放器） */
    fun handlePlayerBookmark(view: BiliPlayerView) {
        wasPlayingBeforeDialog = view.isPlaying()
        tagPositionMs = view.currentPositionMs
        view.pausePlayback()
        showTagDialog = true
    }

    /** 播放中按返回 → 退 chrome 浏览模式（I7，GUIDE_UI L168/L279：暂停 + 海报态 + chrome
     *  显示）。播放器不销毁：进度保留（同源续播不归零），海报重挂载显示缩略图 */
    fun exitToChromeBrowseMode() {
        playerState.player.pause()
        machine.exitToPoster()
        stageMode = machine.mode
        onExitToChromeBrowse()
    }

    /**
     * 播放错误承接（2026-09-13 用户真机反馈 BUG-B 次修：此前全仓无 PlaybackException
     * 处理，prepare 失败=黑屏无提示、时长恒占位）。全部走既有状态机回退路径，不绕开：
     * 全屏态先退层级（[VideoFullscreenStateMachine.onExitRequested]，LANDSCAPE→NONE 写
     * 竖屏，覆盖层经自身 onDispose 序列 detach + 交还 surface），再退海报态
     * （[VideoStageStateMachine.exitToPoster]，与返回键同链）——海报态点按即同链重试
     * （[beginPlayback] 对 IDLE 态自动重 prepare）。播放器错误后自身即 IDLE，无需暂停；
     * chrome 显隐由 stageMode 变化经 onPlayerActiveChanged 自然恢复。
     */
    fun handlePlayerError(error: PlaybackException) {
        Log.w(PLAYBACK_ERROR_LOG_TAG, "视频播放失败 code=${error.errorCodeName}", error)
        exitFullscreenLevel()
        machine.exitToPoster()
        stageMode = machine.mode
        showPlayFailedHint = true
    }

    // 播放器活动期（播放/暂停/ENDED，海报态除外）拦截系统返回：先退 chrome 浏览模式再议退出
    // 页面；全屏覆盖层打开时禁用——其 Dialog 窗口自管返回（K2 单级：LANDSCAPE→排版态，
    // 经 exitFullscreenLevel）
    BackHandler(
        enabled = stageMode != VideoStageMode.POSTER &&
            fullscreenLevel == VideoFullscreenLevel.NONE,
    ) {
        exitToChromeBrowseMode()
    }

    // 播放器活动态上报（I7）：海报态=false、播放/暂停/ENDED=true——DetailScreen
    // chromeEffective 的驱动源（chrome 让位播放器自有控制器；S9 起播放态系统栏不随本链，
    // 改随 onPlaybackChromeChanged 联动控制条）
    LaunchedEffect(stageMode) {
        onPlayerActiveChanged(stageMode != VideoStageMode.POSTER)
    }

    // 海报态横滑（I7，GUIDE_UI L163「视频预览…横滑浏览其他文件」；V2 放宽为 ViewPager
    // 语义：距离或速度任一达阈值即切——慢拖可切件，判定收敛到 posterSwipeDelta，阈值仍
    // 对齐 ZoomImageView 60dp/800 冻结档，+1=左滑下一张）；播放态不接（播放器手势接管，
    // 对齐旧版「预览态可横滑」边界）。注意 pointerInput 在 clickable 之前：横滑过 slop 后
    // 消费事件，clickable 的点击语义自然取消（拖动不误触起播）
    val swipeDistancePx = with(LocalDensity.current) {
        POSTER_SWIPE_DISTANCE_DP.dp.toPx()
    }
    val posterGestureModifier = if (stageMode == VideoStageMode.POSTER && asset.origUrl != null) {
        Modifier
            // Z1：键不含 onSiblingNavigate（父级每次重组新建 lambda，键=非稳定 lambda 会随
            // 重组重启手势协程，拖拽中重组=手势静默死亡）；回调经 :317 既有
            // latestOnSiblingNavigate 最新值桥取用，注释与实现自此一致。键留 swipeDistancePx
            //（值稳定，仅密度真变才重启，重启后阈值取新值）
            .pointerInput(swipeDistancePx) {
                var dragX = 0f
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragX = 0f
                        tracker.resetTracking()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragX += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity()
                        // V2 判定收敛到纯函数 posterSwipeDelta（距离或速度任一达阈值即切）
                        posterSwipeDelta(dragX, velocity.x, swipeDistancePx)
                            ?.let(latestOnSiblingNavigate)
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                )
            }
            .clickable { beginPlayback() }
    } else {
        Modifier
    }

    Box(modifier = modifier.then(posterGestureModifier)) {
        if (stageMode == VideoStageMode.POSTER) {
            // 海报态：视频帧 + 中央播放钮，整块可点起播（播放钮为视觉锚点）。
            // I7 整屏舞台改 Fit contain：G1a 固定比例舞台下 Crop≈Fit，整屏后 Crop 会裁掉
            // 海报边缘且与播放 letterbox 跳变——对齐旧版 ZoomImageView 预览的 contain 语义；
            // 占位（exp#4 占位翼；动效机制已随任务W W1 撤除，占位保留）：海报未就绪底由
            // backdrop（主题底，与壳层页面同色，不可感知）改为品牌灰 secondaryContainer——
            // 与网格卡 QimengThumbnail 占位/错误底同 token，未就绪期舞台以可辨「色块」形态
            // 出现；加载完成后的 letterbox 底仍是调用方打底的 backdrop（K1 单源口径不动：
            // 本占位只作用于未就绪瞬间，不参与稳态渲染）。
            // 错误底保留 backdrop（K1 既有口径：对齐旧版海报透出 qmColorBg，不在本翼扩权）。
            //
            // 海报 md 先行（协议批 2026-09-18，分层裁决单源 [videoPosterLayering]）：底层
            // 挂 md（预生成，立即出图；无 md 退化直接 lg=现状行为，占位/错误画法不变）；
            // md 成功后才挂 lg 覆盖层（同 Fit 同盒，几何逐像素对齐）——覆盖层不设
            // placeholder/error，加载中/失败都画空、底层 md 画面保持不动（不闪灰块/错误图；
            // Coil 换 model 重开会回占位，故用分层而非换 model），lg 命中磁盘缓存时解码
            // 即达=立即换上；md 失败维持既有占位/错误底不回退 lg。两层都是同 UI 状态
            // （asset）派生的纯渲染，不触碰数据链（铁律 7 不受影响）
            var mdSucceeded by remember(asset.thumbUrlMd) { mutableStateOf(false) }
            val posterLayering = videoPosterLayering(asset.thumbUrlMd, asset.thumbUrl, mdSucceeded)
            AsyncImage(
                model = posterLayering.baseModel,
                contentDescription = stringResource(R.string.detail_media_stage),
                contentScale = ContentScale.Fit,
                placeholder = ColorPainter(MaterialTheme.colorScheme.secondaryContainer),
                error = ColorPainter(backdrop),
                // md==null 时底层挂的就是 lg，成功不得记账（守卫见 videoPosterLayering）
                onSuccess = { if (asset.thumbUrlMd != null) mdSucceeded = true },
                modifier = Modifier.fillMaxSize(),
            )
            if (posterLayering.upgradeToLg) {
                AsyncImage(
                    model = asset.thumbUrl,
                    // 语义描述已由底层海报承担，读屏不重复播报（纯视觉升级层）
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = STAGE_PLAY_SCRIM_ALPHA),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(STAGE_PLAY_BUTTON_SIZE),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = PlayIcon,
                        contentDescription = stringResource(R.string.detail_play_video),
                        tint = Color.White,
                    )
                }
            }
            // 已看完徽标（3d WatchState 口径）：断点 ≥ 时长 → 左上角常驻提示，点播放即重播
            if (watched) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = STAGE_PLAY_SCRIM_ALPHA),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(STAGE_WATCHED_BADGE_PADDING),
                ) {
                    Text(
                        text = stringResource(R.string.detail_video_watched),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        } else {
            // 播放态（含暂停/ENDED）：BiliPlayerView 整体桥接，触摸全归播放器手势循环。
            // V2 修复播放器贴顶：必须显式 fillMaxSize 填满舞台盒——缺 modifier 时 AndroidView
            // 走 wrap-content 测量，PlayerView 尺寸塌缩贴顶不居中；PlayerView 默认
            // RESIZE_MODE_FIT 自行 letterbox 居中画面，外层 Box 尺寸由调用方 fillMaxSize 决定
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    BiliPlayerView(ctx).apply {
                        setPlayer(playerState.player)
                        // K2 单级全屏：本视图只上报点击，进/退由状态机裁决（横屏视频才放行；
                        // 防抖共用，覆盖层视图同键同窗）
                        onFullscreen = { requestFullscreenToggle() }
                        // 书签按钮（时间轴标签添加入口）：与覆盖层视图共用同链（快照→暂停→对话框）
                        onBookmark = { handlePlayerBookmark(this) }
                        // 长按芯片 → Compose 侧菜单（跳转/删除），域 id 由菜单对话框反查
                        onTagLongPress = { entity -> menuTag = entity }
                        // 播放错误 → 状态机回退海报态 + 轻提示（BUG-B 次修；全屏覆盖层视图
                        // 未接线，但其未挂时本视图听众仍在共享播放器上，错误同样经此承接）
                        onPlayerError = { handlePlayerError(it) }
                        // S2 播放态镜像上报（2026-09-19 拍板引入，S9 定稿语义）：控制器显隐
                        // 单点转发 DetailScreen 镜像——须先于 startPlayback 挂线（起播即
                        // showController(true) 上报，状态栏与控制条同拍显现，B 站竖屏语义）
                        onControllerVisibilityChanged = { visible ->
                            latestOnPlaybackChromeChanged(visible)
                        }
                        // 起播（内含 ENDED 回 0 口径）；此后触摸由控件手势循环接管
                        startPlayback()
                    }.also { playerView = it }
                },
                update = { view ->
                    // G6 注释修订（层级真源自 D2 起为 Compose 侧 VideoFullscreenStateMachine，
                    // K2 改单级语义）：本回写只表达「当前配置方向」→ 控件手势档与按钮图标，
                    // 全屏层级由覆盖层视图恒 setFullscreen(true) 表达、不经此处。S2（2026-09-19
                    // 拍板）后 G1/G2 双向同语义（单击切控制器/双击播停），本回写仅余按钮图标
                    // 与「进横屏默认隐控制器」差异。用 LocalConfiguration（配置变化会触发重组）
                    // 而非 context.resources——后者在旋转后不刷新，会卡死在旧方向
                    //（LocalContextConfigurationRead lint）
                    val landscape = configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                    view.setFullscreen(landscape)
                },
            )
        }

        // 播放失败轻提示（BUG-B 次修，海报态/播放态均可能短暂驻留）：置顶覆盖的轻量
        // Surface，观感对齐海报态「已看完」徽标（同黑底白字小字档）；文案点明可点按重试
        //（海报态整块 clickable 起播即重试链路）
        if (showPlayFailedHint) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = STAGE_PLAY_SCRIM_ALPHA),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = STAGE_WATCHED_BADGE_PADDING),
            ) {
                Text(
                    text = stringResource(R.string.detail_video_play_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }

    // 时间轴标签添加（3d 旧版核心体验，S1c 对齐旧版形态：底部弹出 Sheet，快捷 ❤️/⭐ 点击
    // 立即添加并收起 + 自定义输入，无「取消」按钮）；位置传打开瞬间快照 tagPositionMs——
    // 写库 timeMillis 与副标题「当前时间」同源（快照捕获见 handlePlayerBookmark）
    if (showTagDialog) {
        TimelineTagAddDialog(
            currentPositionMs = tagPositionMs,
            onAdd = { name ->
                latestOnAdd(tagPositionMs, name)
                dismissTagDialog()
            },
            onDismiss = ::dismissTagDialog,
        )
    }

    // 长按菜单（跳转 / 删除；删除经 VM → 服务端 → 重拉列表刷新芯片）
    menuTag?.let { tag ->
        TimelineTagMenuDialog(
            tag = tag,
            domainTagId = tagEntities.indexOf(tag).takeIf { it >= 0 }
                ?.let { timelineTags.getOrNull(it)?.id },
            onSeekToTag = {
                playerView?.seekToPosition(tag.timeMillis)
                menuTag = null
            },
            onDelete = { tagId ->
                onDeleteTimelineTag(tagId)
                menuTag = null
            },
            onDismiss = { menuTag = null },
        )
    }

    // K2 单级横屏全屏覆盖层（仅 LANDSCAPE 态挂载，无竖屏全屏形态）：独立 Dialog 窗口铺满
    // 整屏（选型依据同图片覆盖层，Dialog+insets 骨架共享 FullscreenOverlayShell）；同一
    // ExoPlayer 双视图交接——挂载即接管画面播放零中断，关闭时 surface 迁回排版态视图。
    // 退出回排版态（系统返回/覆盖层顶栏返回经状态机裁决，写 PORTRAIT）
    if (fullscreenLevel == VideoFullscreenLevel.LANDSCAPE) {
        VideoFullScreenOverlay(
            player = playerState.player,
            tagEntities = tagEntities,
            onFullscreenToggle = ::requestFullscreenToggle,
            onExit = ::exitFullscreenLevel,
            onBookmarkTap = ::handlePlayerBookmark,
            onTagChipLongPress = { entity -> menuTag = entity },
            onReleasePlayerSurface = { playerView?.rebindPlayer(playerState.player) },
        )
    }
}

/**
 * 时间轴标签添加 BottomSheet（3d → S1c 2026-09-13 对齐旧版添加标记形态）：底部弹出（顶部
 * 圆角 + surface 底、无拖拽把手；点外部/下滑/返回键关闭），**无「取消」按钮**。内容自上
 *而下：居中粗体标题 + 「当前时间」副标题（[currentPositionMs] =调用方打开瞬间捕获的位置
 * 快照，本组件不读播放器）+「快捷标记」两枚浅灰胶囊（点击**立即**写入完整名并收起，不再
 * 经过输入框）+「自定义标记」输入行（非空才放行「添加」，写入 trim 值）。
 * 快捷写入字面量 = [TimelineTagColors.HEART_TAG]/[STAR_TAG] + 空格 + 标签名（单源口径
 * 延续 2026-09-07 审查 P2，芯片底色按裸前缀判定兼容变体符，见 TimelineTagColors KDoc）；
 * 胶囊文字用主题 onSurface 对齐旧版浅灰胶囊（emoji 自带色，不再红/金染色）。
 * Sheet 陷阱处置沿用 DetailAuthorSheet 现口径（alpha15 短内容 sheet 三陷阱：dragHandle=
 * null + standardWindowInsets + 点外/返回可关，沿革与实证见 DetailSheets.kt KDoc）；
 * 底部导航栏遮挡由 standardWindowInsets 底部段承担（DetailSheets 既有同款，不再叠加
 * navigationBarsPadding 双计入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineTagAddDialog(
    currentPositionMs: Long,
    onAdd: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 输入态随 Sheet 销毁复位（Sheet 由调用方关闭，卸载即清，无需 onAdd 后手动清）
    var input by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 短内容 sheet 三陷阱处置同 DetailAuthorSheet（S1b 现口径），沿革见其 KDoc
        dragHandle = null,
        contentWindowInsets = { BottomSheetDefaults.standardWindowInsets },
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = true,
            shouldDismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 去 handle 后内容顶到圆角边，补顶距档（DetailAuthorSheet 同款）；底部 24dp
                // 呼吸与页面级留白同档（DETAIL_BOTTOM_SPACER 单源在 DetailScreen.kt）
                .padding(top = QimengDimens.ScreenPaddingTop)
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            // ① 标题 + ② 当前时间（Column 默认 Start 对齐小节标签，居中文本走
            // fillMaxWidth + textAlign 局部居中）
            Text(
                text = stringResource(R.string.detail_video_tag_add_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = QimengDimens.SpaceM),
            )
            Text(
                text = stringResource(
                    R.string.detail_video_tag_current_time,
                    formatDurationMs(currentPositionMs),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // ③④ 快捷标记：点击立即写入完整名并收起（旧版同款，不碰输入框）；
            // 胶囊显示文本同写入字面量（❤️/⭐ + 空格 + 名，旧版 BottomSheet 同观感）
            TagSectionLabel(text = stringResource(R.string.detail_video_tag_section_quick))
            val quickLike = stringResource(R.string.detail_video_tag_quick_like)
            val quickFavorite = stringResource(R.string.detail_video_tag_quick_favorite)
            Row(horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM)) {
                TagQuickCapsule(label = TimelineTagColors.HEART_TAG + " " + quickLike) {
                    onAdd(TimelineTagColors.HEART_TAG + " " + quickLike)
                }
                TagQuickCapsule(label = TimelineTagColors.STAR_TAG + " " + quickFavorite) {
                    onAdd(TimelineTagColors.STAR_TAG + " " + quickFavorite)
                }
            }
            // ⑤⑥ 自定义标记：非空才放行，写入 trim 值后由调用方收起
            TagSectionLabel(text = stringResource(R.string.detail_video_tag_section_custom))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
            ) {
                QimengCapsuleTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    // 胶囊输入框 label 走 placeholder 语义（G6：对齐 Web，组件不支持 label 浮动）
                    placeholder = stringResource(R.string.detail_video_tag_input_hint),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = input.isNotBlank(),
                    onClick = { onAdd(input.trim()) },
                    // 可禁用实底按钮统一入口（W6 #49：夜间禁用态防 dither 横带，禁止裸 buttonColors）
                    colors = qimengFilledButtonColors(),
                    shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
                ) {
                    Text(text = stringResource(R.string.detail_video_tag_add_action))
                }
            }
        }
    }
}

/** 添加 Sheet 小节标签（③/⑤：左对齐灰色小字，旧版 section label 同款） */
@Composable
private fun TagSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = QimengDimens.SpaceL, bottom = QimengDimens.SpaceM),
    )
}

/** 快捷标记胶囊（③：浅灰圆角胶囊 + 主题色文字；emoji 自带色不染色，对齐旧版） */
@Composable
private fun TagQuickCapsule(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(TAG_QUICK_CAPSULE_CORNER_RADIUS),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(TAG_QUICK_CAPSULE_PADDING),
        )
    }
}

/**
 * 时间轴标签长按菜单（3d 旧版语义「跳转/删除」而非直接删）：
 * [domainTagId] 为空（实体映射失配，理论不发生的兜底）时删除键置灰。
 */
@Composable
private fun TimelineTagMenuDialog(
    tag: TimelineTagEntity,
    domainTagId: String?,
    onSeekToTag: () -> Unit,
    onDelete: (tagId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_video_tag_menu_title)) },
        text = {
            // 文案展示桥接件同款「时间 + 名」（formatMs internal 于 video 包，此处复刻最小展示）
            Text(text = "${tag.timeMillis / 1000}s ${tag.name}")
        },
        confirmButton = {
            TextButton(onClick = onSeekToTag) { Text(stringResource(R.string.detail_video_tag_menu_jump)) }
        },
        dismissButton = {
            TextButton(
                enabled = domainTagId != null,
                onClick = { domainTagId?.let(onDelete) },
            ) { Text(stringResource(R.string.detail_video_tag_menu_delete)) }
        },
    )
}
