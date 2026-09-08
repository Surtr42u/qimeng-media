package media.qimeng.app.feature.detail

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.ui.component.QimengThumbnail
import media.qimeng.app.core.ui.icon.PlayIcon
import media.qimeng.app.feature.detail.video.BiliPlayerView
import media.qimeng.app.feature.detail.video.TimelineTagEntity
import media.qimeng.app.feature.detail.video.VideoFullscreenCommand
import media.qimeng.app.feature.detail.video.VideoFullscreenLevel
import media.qimeng.app.feature.detail.video.VideoFullscreenOrientation
import media.qimeng.app.feature.detail.video.VideoFullscreenStateMachine
import media.qimeng.app.feature.detail.video.VideoStageMode
import media.qimeng.app.feature.detail.video.VideoStageStateMachine
import media.qimeng.app.feature.detail.video.rememberVideoPlayerState

// ---------- 页面私有尺寸档（本文件单源；来源注释随条目） ----------

/** 舞台中央播放钮直径（48dp Material 最小触控档再升一级，防误触边缘） */
private val STAGE_PLAY_BUTTON_SIZE = 64.dp

/** 播放钮半透明底透明度（黑底白图标，Web 播放按钮同观感） */
private const val STAGE_PLAY_SCRIM_ALPHA = 0.6f

/** 「已看完」徽标外边距（贴舞台左上角，8dp 通用贴边档） */
private val STAGE_WATCHED_BADGE_PADDING = 8.dp

/** 进度轮询间隔（3d 设计从简：播放中每 1s 读一次位置喂节流策略；策略自身 5s 放行一次） */
private const val POSITION_POLL_INTERVAL_MS = 1000L

// 快捷标签字面量不再本地定义：写入用 TimelineTagColors.HEART_TAG/STAR_TAG（单源，
// 2026-09-07 审查 P2 前❤字面量三处独立定义且口径分叉，已收敛）。

/** G6 全屏切换防抖窗口（ms）：快速连点全屏键不连续请求方向（旧版 MediaDetailFragment:1157-1160 同款语义） */
private const val FULLSCREEN_TOGGLE_DEBOUNCE_MS = 800L

/**
 * 视频舞台（3c 视频态 + 3d 全量接线）：海报态（缩略图 + 中央播放钮 + 已看完徽标）单击起播 →
 * [BiliPlayerView] 整体桥接（ADR-0014 例外③）接管触摸，进手势循环（G1~G9 冻结口径）。
 *
 * 起播幂等由 [VideoStageStateMachine] 保证（已播放的重复点击直接返回，不重置进度/不重装源）；
 * ENDED 再播回 0 由控件内 togglePlayPause/startPlayback 承担（G9），状态机同步记账。
 *
 * 3d 接线拓扑（谁持有谁）：本舞台持有 [rememberVideoPlayerState]（ExoPlayer）与桥接视图引用；
 * VM 持节流/打点策略。回调链 = 播放器事件 → 本舞台 → DetailScreen 具名参数 → ViewModel：
 * - 续播：[startPositionMs]（VM WatchState 口径）在 prepare 时一次性生效；
 * - 进度：播放中 1s 轮询 currentPosition → [onPositionChanged] → VM 节流放行即 PUT progress；
 * - 打点：起播（onIsPlayingChanged=true）→ [onPlaybackStarted] → VM play 打点一次；
 * - 标签：[timelineTags] 映射桥接实体 → updateTimelineTags（芯片点击 seek 为控件内建行为；
 *   长按芯片/书签按钮经 [onTagLongPress]/[onBookmark] 回 Compose 侧对话框）。
 *
 * D2 两级全屏（用户 2026-09-07 拍板，覆盖 GUIDE_UI 旧句「仅横屏视频可全屏」）：全屏钮
 * → 第一级=竖屏全屏覆盖层（[VideoFullScreenOverlay]，方向保持竖屏，横屏视频 letterbox
 * 属正常）→ 覆盖层内再点全屏钮 → 第二级=横屏全屏（固定 LANDSCAPE；「旋转设备进第二级」
 * 在常规全屏下被第一级方向锁堵死，配置变化升级分支仅分屏/自由窗口等忽略方向锁环境可达，
 * 可达性口径待拍板 #23）；退出逐级回退（横屏级→竖屏级→排版态），任何退出路径经组合离场
 * 兜底强制回竖屏。层级真源=[VideoFullscreenStateMachine]（纯 JVM 可测），方向写入集中在
 * [applyFullscreenCommand]；覆盖层与排版态视图共用同一 ExoPlayer（surface 交接，见
 * BiliPlayerView setPlayer/rebindPlayer/detachPlayer）。
 *
 * @param watched 已看完徽标（3d WatchState 口径，海报态左上角显示）
 * @param startPositionMs 起播位置毫秒（VM 按断点秒×1000 计算；已看完=0 重播）
 * @param timelineTags 时间轴标签（VM 已按 timeMillis 升序拉取）
 * @param onSiblingNavigate 左右滑切换相邻资产回调（视频由播放器手势接管，本批不消费：
 *   视频 chrome=播放器自有控制器，无第二套手势面；保留参数与图片舞台签名对齐）
 * @param onToggleChrome 沉浸模式顶行开关回调（同上，视频态无可接线对象，保留签名对齐）
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
    modifier: Modifier,
    onSiblingNavigate: (delta: Int) -> Unit,
    onToggleChrome: () -> Unit,
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
    // 播放中镜像（进度轮询的开关；onIsPlayingChanged 主线程写入）
    var isPlaying by remember { mutableStateOf(false) }
    // G6 全屏防抖时间戳（factory 一次性闭包经 State 捕获最新值；仅算间隔不参与业务口径）
    var lastFullscreenToggleAt by remember { mutableLongStateOf(0L) }
    // D2 两级全屏：层级显式态（fullscreenMachine 为逻辑真源，镜像同 stageMode 模式）。
    // 旧口径「Compose 侧不持状态、以设备方向为全屏真源」只支持单级横屏全屏；两级需要
    // 层级态裁决「全屏钮点击该进/退哪级」与「配置变化升/降级（可达性见状态机 KDoc #23）」
    val fullscreenMachine = remember { VideoFullscreenStateMachine() }
    var fullscreenLevel by remember { mutableStateOf(VideoFullscreenLevel.NONE) }

    /** 执行两级全屏状态机指令：层级镜像 + 方向写入（全仓唯一方向写入点语义延续） */
    fun applyFullscreenCommand(command: VideoFullscreenCommand) {
        fullscreenLevel = command.level
        // 横屏级固定 LANDSCAPE 而非 SENSOR（旧版 MediaDetailFragment.kt:1171 口径：固定
        // 横屏，避免 SENSOR 在两个横屏方向间切换乱闪）；进竖屏级/退出恢复写 PORTRAIT
        context.findActivity()?.requestedOrientation = when (command.orientation) {
            VideoFullscreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            VideoFullscreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /** 全屏钮（排版态与覆盖层视图共用）：800ms 防抖后交状态机裁决进/退哪一级（旧版
     *  Fragment:1157-1160 同款语义，具名常量 FULLSCREEN_TOGGLE_DEBOUNCE_MS） */
    fun requestFullscreenToggle() {
        val now = System.currentTimeMillis()
        if (now - lastFullscreenToggleAt >= FULLSCREEN_TOGGLE_DEBOUNCE_MS) {
            lastFullscreenToggleAt = now
            fullscreenMachine.onFullscreenToggle()?.let(::applyFullscreenCommand)
        }
    }

    /** 覆盖层退出请求（系统返回/覆盖层顶栏返回共用）：状态机逐级回退（横屏级→竖屏级→排版态） */
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

    // D2 配置变化监听：竖屏级遇横屏配置 → 锁横屏升第二级；竖屏写入后的瞬态横屏配置由
    // 状态机 settle 窗口忽略（防转屏延迟顶回横屏级）。可达性口径（待拍板 #23）：常规全屏
    // 被第一级 PORTRAIT 方向锁堵死，本分支仅分屏/自由窗口等忽略 requestedOrientation 的
    // 环境可达，第二级入口=覆盖层内全屏钮。NONE 层恒 no-op——排版态自由旋转语义不变
    LaunchedEffect(configuration, fullscreenLevel) {
        fullscreenMachine.onRotationChanged(
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )?.let(::applyFullscreenCommand)
    }

    // D2 方向恢复兜底（审查清偿：快照恢复替代无条件锁竖屏）：requestedOrientation 是
    // Activity 级粘性属性，无条件写 PORTRAIT 会把「只看过排版态视频、从未进全屏」的整个
    // App 永久锁竖屏（横屏平板致命，且违背「排版态自由旋转语义不变」）。改为恢复进入本
    // 组合时的快照：常规快照=UNSPECIFIED（manifest 不锁方向）→ 退出回自由旋转，手机竖屏
    // 持机自然回竖屏——「横屏全屏态退出卡横屏」的修复语义保持；逐级退出的 PORTRAIT 写入
    // （全屏钮路径）不受影响，离场时仍由本兜底解掉残留锁
    DisposableEffect(Unit) {
        val orientationOnEnter =
            context.findActivity()?.requestedOrientation
                ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            context.findActivity()?.requestedOrientation = orientationOnEnter
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

    // 时间轴标签（3d）：领域模型 → 桥接实体映射（timeMillis 直传；实体遗留字段
    // recordKey/fileName 填本资产标识、createdAtMillis 桥接件仅作展示来源不消费填 0；
    // timelineTagId 以列表序号占位——桥接件不消费该字段，域 id 由长按菜单经序号反查）。
    // 颜色说明：芯片红/金配色由桥接件按 TimelineTagColors.HEART_PREFIX/STAR_PREFIX 裸前缀
    // 判定自绘（bg_timeline_tag_like/fav，改动最小面；裸前缀同时命中带/不带变体符写法），
    // Compose 侧不重复注入颜色；TimelineTagColors 亦供添加对话框快捷键（写入字面量单源）。
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

    // 标签添加对话框状态（wasPlaying 快照：打开前暂停，dismiss 后恢复——冻结口径）
    var showTagDialog by remember { mutableStateOf(false) }
    var wasPlayingBeforeDialog by remember { mutableStateOf(false) }
    // 长按菜单目标（桥接实体；域 id 由菜单经 tagEntities 序号反查，避免实体遗留字段伪造）
    var menuTag by remember { mutableStateOf<TimelineTagEntity?>(null) }

    // factory 一次性闭包的陈旧捕获防线：经 State 在调用点取最新值
    val latestTagEntities by rememberUpdatedState(tagEntities)
    val latestOnAdd by rememberUpdatedState(onAddTimelineTag)

    fun beginPlayback() {
        // 视频源 = asset.origUrl（缺直链则保持海报，理论不发生的兜底）
        val uri = asset.origUrl ?: return
        // 幂等起播：已 PLAYING 直接返回（状态机拦下重复点击）
        val command = machine.start() ?: return
        stageMode = machine.mode
        // ENDED 路径 restartFromZero=true → 回 0（G9；实际该路径由控件内按钮承担，此处对齐口径）
        playerState.prepare(uri, if (command.restartFromZero) 0L else startPositionMs)
        playerState.play()
    }

    /** 关标签对话框并按快照恢复播放（dismiss/确认共用；恢复走 startPlayback=ENDED 回 0 同口径） */
    fun dismissTagDialog() {
        showTagDialog = false
        if (wasPlayingBeforeDialog) playerView?.startPlayback()
    }

    /** 书签钮（排版态/覆盖层视图共用同链）：wasPlaying 快照 → 暂停 → 开标签对话框
     *（dismiss/确认后恢复；spec 冻结口径「打开前暂停、dismiss 后恢复」） */
    fun handlePlayerBookmark(view: BiliPlayerView) {
        wasPlayingBeforeDialog = view.isPlaying()
        view.pausePlayback()
        showTagDialog = true
    }

    /** 快速转跳钮（排版态/覆盖层视图共用同链）：跳当前播放位置之后的下一个标签（无则
     *  在后的标签→回卷第一个；无标签 no-op）。芯片本体点击 seek 是控件内建行为
     *（createTagChip → seekTo），不经此回调 */
    fun handlePlayerJump(view: BiliPlayerView) {
        val entities = latestTagEntities
        val next = entities.firstOrNull { it.timeMillis > view.currentPositionMs }
            ?: entities.firstOrNull()
        next?.let { view.seekToPosition(it.timeMillis) }
    }

    val posterClickable = stageMode == VideoStageMode.POSTER && asset.origUrl != null

    Box(
        modifier = modifier.then(
            if (posterClickable) Modifier.clickable { beginPlayback() } else Modifier
        ),
    ) {
        if (stageMode == VideoStageMode.POSTER) {
            // 海报态：缩略图 + 中央播放钮，整块可点起播（播放钮为视觉锚点）
            QimengThumbnail(
                model = asset.thumbUrl,
                contentDescription = stringResource(R.string.detail_media_stage),
                modifier = Modifier.fillMaxSize(),
            )
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
            // 播放态（含暂停/ENDED）：BiliPlayerView 整体桥接，触摸全归播放器手势循环
            AndroidView(
                factory = { ctx ->
                    BiliPlayerView(ctx).apply {
                        setPlayer(playerState.player)
                        // D2 两级全屏：本视图只上报点击，进/退哪级由状态机裁决（防抖共用，
                        // 覆盖层视图同键同窗）
                        onFullscreen = { requestFullscreenToggle() }
                        // 书签按钮（时间轴标签添加入口）：与覆盖层视图共用同链（快照→暂停→对话框）
                        onBookmark = { handlePlayerBookmark(this) }
                        // 快速转跳按钮：与覆盖层视图共用同链（见 handlePlayerJump KDoc）
                        onJump = { handlePlayerJump(this) }
                        // 长按芯片 → Compose 侧菜单（跳转/删除），域 id 由菜单对话框反查
                        onTagLongPress = { entity -> menuTag = entity }
                        // 起播（内含 ENDED 回 0 口径）；此后触摸由控件手势循环接管
                        startPlayback()
                    }.also { playerView = it }
                },
                update = { view ->
                    // G6 注释修订（D2 两级全屏）：全屏**层级**真源已改为 Compose 侧
                    // VideoFullscreenStateMachine（「Compose 侧不持状态」旧口径只支持单级
                    // 横屏全屏）；本回写只表达「当前配置方向」→ 控件手势档（G1 竖屏单击播停/
                    // 横屏单击显隐控制器）与按钮图标，全屏层级由覆盖层视图恒 setFullscreen(true)
                    // 表达、不经此处。用 LocalConfiguration（配置变化会触发重组）而非
                    // context.resources——后者在旋转后不刷新，会卡死在旧方向
                    //（LocalContextConfigurationRead lint）
                    val landscape = configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                    view.setFullscreen(landscape)
                },
            )
        }
    }

    // 时间轴标签添加对话框（3d 旧版核心体验：快捷 ❤️/⭐ + 自定义输入）
    if (showTagDialog) {
        TimelineTagAddDialog(
            onConfirm = { name ->
                val positionMs = playerView?.currentPositionMs ?: 0L
                latestOnAdd(positionMs, name)
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
            onJump = {
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

    // D2 两级全屏覆盖层（第一级竖屏/第二级横屏同窗口）：独立 Dialog 窗口铺满整屏
    // （选型依据同图片覆盖层，Dialog+insets 骨架共享 FullscreenOverlayShell）；同一
    // ExoPlayer 双视图交接——挂载即接管画面播放零中断，关闭时 surface 迁回排版态视图。
    // 退出逐级回退：横屏级→竖屏级→排版态（系统返回/覆盖层顶栏返回经状态机裁决）
    if (fullscreenLevel != VideoFullscreenLevel.NONE) {
        VideoFullScreenOverlay(
            player = playerState.player,
            tagEntities = tagEntities,
            onFullscreenToggle = ::requestFullscreenToggle,
            onExit = ::exitFullscreenLevel,
            onBookmarkTap = ::handlePlayerBookmark,
            onJumpTap = ::handlePlayerJump,
            onTagChipLongPress = { entity -> menuTag = entity },
            onReleasePlayerSurface = { playerView?.rebindPlayer(playerState.player) },
        )
    }
}

/**
 * 时间轴标签添加对话框（3d）：快捷 ❤️/⭐ 两键填入前缀（可再补文字，如「❤️ 白丝」）+
 * 自定义输入；确认按钮非空才可点。快捷键颜色按 [TimelineTagColors] 前缀映射（红/金）。
 */
@Composable
private fun TimelineTagAddDialog(
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val darkTheme = isSystemInDarkTheme()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_video_tag_add_title)) },
        text = {
            Column {
                // 快捷键 = 前缀填入输入框（可再补文字，如「❤️ 名字」）。写入字面量与
                // 芯片底色判定均收敛在 TimelineTagColors 单源：写入用 HEART_TAG/STAR_TAG
                // 完整字面量，判定用裸前缀（兼容带/不带变体符的既有数据）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { input = TimelineTagColors.HEART_TAG }) {
                        Text(
                            text = TimelineTagColors.HEART_TAG,
                            color = TimelineTagColors.colorFor(
                                TimelineTagColors.HEART_TAG, darkTheme, MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                    TextButton(onClick = { input = TimelineTagColors.STAR_TAG }) {
                        Text(
                            text = TimelineTagColors.STAR_TAG,
                            color = TimelineTagColors.colorFor(
                                TimelineTagColors.STAR_TAG, darkTheme, MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.detail_video_tag_input_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = { onConfirm(input) },
            ) { Text(stringResource(R.string.detail_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_cancel)) }
        },
    )
}

/**
 * 时间轴标签长按菜单（3d 旧版语义「跳转/删除」而非直接删）：
 * [domainTagId] 为空（实体映射失配，理论不发生的兜底）时删除键置灰。
 */
@Composable
private fun TimelineTagMenuDialog(
    tag: TimelineTagEntity,
    domainTagId: String?,
    onJump: () -> Unit,
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
            TextButton(onClick = onJump) { Text(stringResource(R.string.detail_video_tag_menu_jump)) }
        },
        dismissButton = {
            TextButton(
                enabled = domainTagId != null,
                onClick = { domainTagId?.let(onDelete) },
            ) { Text(stringResource(R.string.detail_video_tag_menu_delete)) }
        },
    )
}
