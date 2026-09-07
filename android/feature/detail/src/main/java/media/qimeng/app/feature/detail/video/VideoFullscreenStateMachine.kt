package media.qimeng.app.feature.detail.video

/**
 * 视频全屏层级（D2 两级全屏状态机；纯逻辑无 Android/Compose 依赖，JVM 单测锁行为）。
 * 覆盖 GUIDE_UI 旧句「仅横屏视频可全屏」：全类型视频均可进全屏，分级如下——
 * - [NONE]：排版态（视频舞台嵌在详情页排版里）
 * - [PORTRAIT]：第一级=竖屏全屏（视频扩至全屏覆盖层、方向保持竖屏；横屏视频 letterbox 属正常）
 * - [LANDSCAPE]：第二级=横屏全屏（方向固定锁横屏——不用 SENSOR，防在两个横屏方向间乱闪）
 */
internal enum class VideoFullscreenLevel {
    NONE,
    PORTRAIT,
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
 * @param orientation 需要向 Activity 请求的方向（每次迁移都带：NONE 退出也写竖屏=恢复口径）
 */
internal data class VideoFullscreenCommand(
    val level: VideoFullscreenLevel,
    val orientation: VideoFullscreenOrientation,
)

/**
 * 视频两级全屏状态机（D2，用户 2026-09-07 拍板冻结口径）：
 * ```
 * NONE --onFullscreenToggle()--> PORTRAIT   （写竖屏：第一级；横屏手持时系统会转回竖屏）
 * PORTRAIT --onFullscreenToggle()--> LANDSCAPE（写横屏并锁死：第二级）
 * LANDSCAPE --onFullscreenToggle()--> PORTRAIT（写竖屏：逐级回退）
 * PORTRAIT --onExitRequested()--> NONE       （写竖屏：恢复排版态方向口径，与 onDispose 兜底同向）
 * LANDSCAPE --onExitRequested()--> PORTRAIT  （写竖屏：逐级回退，不跳级）
 * PORTRAIT +横屏配置 --> LANDSCAPE          （配置变化升级分支：可达性口径见 onRotationChanged
 *                                             KDoc/待拍板 #23——第二级入口=覆盖层内全屏钮）
 * LANDSCAPE +竖屏配置 --> PORTRAIT          （分屏等外力破锁的防御回退；写竖屏）
 * NONE 下配置变化 → 无操作（排版态自由旋转语义不变）
 * ```
 *
 * **为什么需要 awaitingPortraitSettle**：进入/回退竖屏级时执行层会写
 * `requestedOrientation=PORTRAIT`，系统转屏有延迟——窗口期内 `configuration` 仍报横屏。
 * 若旋转监听不忽略这段瞬态，「横屏手持点全屏（或横屏级回退）」会被瞬态横屏配置立刻顶回
 * 横屏级（正是用户要修的「乱闪」同类根因）。规则：凡**写竖屏指令**的迁移都置位，
 * 看到竖屏配置落地才清位；置位期间的横屏配置视为瞬态不升级。
 */
internal class VideoFullscreenStateMachine(initialLevel: VideoFullscreenLevel = VideoFullscreenLevel.NONE) {

    var level: VideoFullscreenLevel = initialLevel
        private set

    /** 竖屏方向请求已发出但配置尚未落地（true 期间忽略横屏配置，见类 KDoc） */
    private var awaitingPortraitSettle = false

    /** 全屏钮（排版态与覆盖层共用一个入口语义）：NONE 进第一级，层内切级 */
    fun onFullscreenToggle(): VideoFullscreenCommand? = when (level) {
        VideoFullscreenLevel.NONE -> enterPortrait(withSettle = true)
        VideoFullscreenLevel.PORTRAIT -> enterLandscape()
        VideoFullscreenLevel.LANDSCAPE -> enterPortrait(withSettle = true)
    }

    /** 退出请求（覆盖层内返回键/顶栏返回；NONE 层无覆盖层可退，返回 null） */
    fun onExitRequested(): VideoFullscreenCommand? = when (level) {
        VideoFullscreenLevel.NONE -> null
        VideoFullscreenLevel.PORTRAIT -> exitToNone()
        VideoFullscreenLevel.LANDSCAPE -> enterPortrait(withSettle = true)
    }

    /**
     * 配置变化回调（Compose 侧 LocalConfiguration 变化时喂入）。
     *
     * **可达性口径（待拍板《待拍板-20260907.md》#23）**：PORTRAIT→LANDSCAPE 升级分支在
     * 常规全屏下物理不可达——第一级已写 PORTRAIT 方向锁，系统不响应设备旋转；该分支仅在
     * 分屏/自由窗口等忽略 requestedOrientation 的环境可达。**第二级入口=覆盖层内全屏钮**
     * （维持现状，本分支为忽略方向锁环境的防御路径，非用户常规入口）。
     * NONE 层恒 null——排版态旋转自由，不锁方向不迁移。
     */
    fun onRotationChanged(isLandscape: Boolean): VideoFullscreenCommand? = when (level) {
        VideoFullscreenLevel.NONE -> null
        VideoFullscreenLevel.PORTRAIT -> when {
            !isLandscape -> {
                // 竖屏配置落地：settle 窗口关闭，此后横屏配置=真实方向变化（可达性见方法 KDoc #23）
                awaitingPortraitSettle = false
                null
            }
            awaitingPortraitSettle -> null // 瞬态横屏：竖屏写入还在转屏中，忽略（防乱闪）
            else -> enterLandscape()
        }
        VideoFullscreenLevel.LANDSCAPE -> if (isLandscape) {
            null
        } else {
            // 防御回退（多窗/厂商覆盖破锁）：配置已是竖屏，无 settle 窗口
            enterPortrait(withSettle = false)
        }
    }

    private fun enterPortrait(withSettle: Boolean): VideoFullscreenCommand {
        level = VideoFullscreenLevel.PORTRAIT
        awaitingPortraitSettle = withSettle
        return VideoFullscreenCommand(level, VideoFullscreenOrientation.PORTRAIT)
    }

    private fun enterLandscape(): VideoFullscreenCommand {
        level = VideoFullscreenLevel.LANDSCAPE
        awaitingPortraitSettle = false
        return VideoFullscreenCommand(level, VideoFullscreenOrientation.LANDSCAPE)
    }

    private fun exitToNone(): VideoFullscreenCommand {
        level = VideoFullscreenLevel.NONE
        awaitingPortraitSettle = false
        // 退出全屏仍写竖屏：与旧版「退出全屏回竖屏」口径及 VideoStage onDispose 兜底同向
        return VideoFullscreenCommand(level, VideoFullscreenOrientation.PORTRAIT)
    }
}
