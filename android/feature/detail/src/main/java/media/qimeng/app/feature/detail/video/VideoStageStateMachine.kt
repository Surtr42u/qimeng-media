package media.qimeng.app.feature.detail.video

/**
 * 视频舞台逻辑形态（3c 状态机状态；纯逻辑无 Android/Compose 依赖，JVM 单测锁行为）。
 */
internal enum class VideoStageMode {
    /** 海报态：缩略图 + 中央播放钮，尚未起播（3a 桩视觉保留） */
    POSTER,

    /** 播放态：BiliPlayerView 接管（暂停中也属播放态——暂停不回海报，控件 G1 单击可续播） */
    PLAYING,

    /** 播完态：ENDED 强制显示控制器（G9），再点播放 seekTo(0) 重播 */
    ENDED,
}

/**
 * 起播指令（状态机 → UI 执行层；携带执行层需要的行为标记而非裸状态）。
 *
 * @param restartFromZero true = ENDED 再播：执行层先 seekTo(0)（G9「再点播放回 0」）
 */
internal data class VideoStartCommand(val restartFromZero: Boolean)

/**
 * 海报态/播放态小型状态机（3c）：
 * ```
 * POSTER --start()--> PLAYING          （restartFromZero=false）
 * PLAYING --onPlaybackEnded()--> ENDED （幂等）
 * ENDED   --start()--> PLAYING         （restartFromZero=true：再播回 0）
 * ENDED   --onPlaybackStarted()--> PLAYING （视图内再播路径的机内补账）
 * ```
 * 关键口径：**幂等起播**——PLAYING 中 start() 返回 null，执行层不得重置进度/重装源。
 */
internal class VideoStageStateMachine(initialMode: VideoStageMode = VideoStageMode.POSTER) {

    var mode: VideoStageMode = initialMode
        private set

    /**
     * 请求起播。POSTER → PLAYING（正常起播）；ENDED → PLAYING（回 0 重播）；
     * 已 PLAYING → 返回 null（幂等：调用方直接返回，不重复 prepare/seek）。
     */
    fun start(): VideoStartCommand? = when (mode) {
        VideoStageMode.POSTER -> {
            mode = VideoStageMode.PLAYING
            VideoStartCommand(restartFromZero = false)
        }
        VideoStageMode.ENDED -> {
            mode = VideoStageMode.PLAYING
            VideoStartCommand(restartFromZero = true)
        }
        VideoStageMode.PLAYING -> null
    }

    /** 播放器回调：STATE_ENDED（仅从 PLAYING 迁移，重复回调幂等） */
    fun onPlaybackEnded() {
        if (mode == VideoStageMode.PLAYING) mode = VideoStageMode.ENDED
    }

    /** 播放器回调：恢复播放（ENDED → PLAYING；覆盖「ENDED 后在控件内再播」路径，机内状态与真机一致） */
    fun onPlaybackStarted() {
        if (mode == VideoStageMode.ENDED) mode = VideoStageMode.PLAYING
    }

    /**
     * 退回海报态（任务I I7 chrome 浏览模式：GUIDE_UI §详情页 L168/L279「播放中按返回先退
     * 到 chrome 浏览模式」——海报态 + chrome 显示）。PLAYING/ENDED → POSTER；已 POSTER 幂等
     * no-op。播放器的暂停与位置保留由执行层负责（状态机只管形态迁移；同源续播不重装源）。
     */
    fun exitToPoster() {
        if (mode != VideoStageMode.POSTER) mode = VideoStageMode.POSTER
    }
}
