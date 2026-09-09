package media.qimeng.app.feature.detail.video

/**
 * 视频全屏层级（任务K K2 单级横屏全屏制；纯逻辑无 Android/Compose 依赖，JVM 单测锁行为）。
 * 对齐旧版 MediaDetailFragment.kt:1163-1183（2026-09-09 用户拍板 #23 推翻 D2 两级制，
 * 用户原话「旧版能做到怎么新版本的ui做不到了」）——
 * - [NONE]：排版态（视频舞台嵌在详情页排版里）
 * - [LANDSCAPE]：横屏全屏（进入写 SCREEN_ORIENTATION_LANDSCAPE 固定锁——不用 SENSOR，
 *   防在两个横屏方向间乱闪；退出写 PORTRAIT）
 *
 * 竖屏视频（宽<=高或尺寸未知）全屏钮无反应：旧版同款。判定数据源见 [isLandscapeVideoSize]。
 */
internal enum class VideoFullscreenLevel {
    NONE,
    LANDSCAPE,
}

/**
 * 方向指令（状态机 → 执行层翻译为 ActivityInfo.SCREEN_ORIENTATION_*；不用框架常量保纯 JVM 可测）。
 */
internal enum class VideoFullscreenOrientation {
    PORTRAIT,
    LANDSCAPE,
}

/**
 * 全屏层级迁移指令：执行层无脑执行「层级镜像 + 方向写入」，裁决全在本状态机。
 *
 * @param level 迁移后的层级
 * @param orientation 需要向 Activity 请求的方向（每次迁移都带：回 NONE 也写竖屏=旧版
 *   「退出全屏回竖屏」恢复口径，restorePortraitOrientation 同向）
 */
internal data class VideoFullscreenCommand(
    val level: VideoFullscreenLevel,
    val orientation: VideoFullscreenOrientation,
)

/**
 * 横屏视频判定（K2 单级全屏的放行门槛，纯函数 JVM 可测）：宽、高齐备且宽 > 高才放行
 * 全屏；任一维度缺失（服务端未探测到/旧数据）按非横屏处理——未知尺寸不放行，宁紧勿松
 * （缺一边时横竖无法证实，半盲放行会误锁横屏）。
 * 数据源 = AssetDetail.width/height（scanner 探测落库，详情接口随行下发）。
 */
internal fun isLandscapeVideoSize(width: Int?, height: Int?): Boolean =
    width != null && height != null && width > height

/**
 * 视频单级横屏全屏状态机（任务K K2，用户 2026-09-09 拍板 #23，推翻 D2 两级制）：
 * ```
 * NONE --onFullscreenToggle(isLandscapeVideo=true)--> LANDSCAPE（写横屏固定锁）
 * NONE --onFullscreenToggle(isLandscapeVideo=false)--> null（无反应：竖屏视频旧版同款）
 * LANDSCAPE --onFullscreenToggle()--> NONE（写竖屏：覆盖层内全屏钮=退出全屏）
 * LANDSCAPE --onExitRequested()--> NONE（写竖屏：系统返回/覆盖层顶栏返回）
 * LANDSCAPE +竖屏配置 --> NONE（写竖屏：分屏等外力破锁的防御回退）
 * NONE 下任何回调 → 无操作（排版态自由旋转语义不变）
 * ```
 * D2 的 PORTRAIT 层、awaitingPortraitSettle 瞬态窗口与「配置变化升级第二级」分支整体删除：
 * 单级制下进全屏即写横屏固定锁，不存在「先落竖屏级再等 settle」的转屏瞬态；瞬态竖屏配置
 * 的忽略改由执行层承担——VideoStage 旋转监听只随配置变化评估、不随层级镜像变化评估
 * （旧实现以层级为 effect 键，进锁转屏窗口内会拿陈旧竖屏配置把刚写入的横屏锁立刻自反，
 * 正是「乱闪」根因之一），见 VideoStage K2 注。
 */
internal class VideoFullscreenStateMachine(initialLevel: VideoFullscreenLevel = VideoFullscreenLevel.NONE) {

    var level: VideoFullscreenLevel = initialLevel
        private set

    /** 全屏钮（排版态与覆盖层共用一个入口语义）：排版态仅横屏视频可进；层内=退出 */
    fun onFullscreenToggle(isLandscapeVideo: Boolean): VideoFullscreenCommand? = when (level) {
        VideoFullscreenLevel.NONE -> if (isLandscapeVideo) enterLandscape() else null
        VideoFullscreenLevel.LANDSCAPE -> exitToLayout()
    }

    /** 退出请求（覆盖层内系统返回/顶栏返回共用；排版态无覆盖层可退，返回 null） */
    fun onExitRequested(): VideoFullscreenCommand? = when (level) {
        VideoFullscreenLevel.NONE -> null
        VideoFullscreenLevel.LANDSCAPE -> exitToLayout()
    }

    /**
     * 配置变化回调（Compose 侧 LocalConfiguration 变化时喂入）。NONE 层恒 null——排版态
     * 旋转自由，不锁方向不迁移。LANDSCAPE 层遇竖屏配置=方向锁被外力破掉（分屏/自由窗口
     * 等忽略 requestedOrientation 的环境），防御回退写竖屏；横屏配置与锁一致，无操作。
     */
    fun onRotationChanged(isLandscape: Boolean): VideoFullscreenCommand? = when (level) {
        VideoFullscreenLevel.NONE -> null
        VideoFullscreenLevel.LANDSCAPE -> if (isLandscape) null else exitToLayout()
    }

    private fun enterLandscape(): VideoFullscreenCommand {
        level = VideoFullscreenLevel.LANDSCAPE
        return VideoFullscreenCommand(level, VideoFullscreenOrientation.LANDSCAPE)
    }

    private fun exitToLayout(): VideoFullscreenCommand {
        level = VideoFullscreenLevel.NONE
        // 退出全屏仍写竖屏：旧版「退出全屏回竖屏」口径（restorePortraitOrientation 同向）
        return VideoFullscreenCommand(level, VideoFullscreenOrientation.PORTRAIT)
    }
}
