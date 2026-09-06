package media.qimeng.app.feature.detail.video

/**
 * 播放器纯逻辑（3c，无 Android/View 依赖，JVM 单测锁行为）：
 * G4 拖进度自适应上限 + G7 倍速档位表。冻结口径来源 = 旧版 BiliPlayerView 实测参数
 * （手势 G1~G9，任务书冻结件）；BiliPlayerView 桥接件内调用本文件，保证单一事实源。
 */

/**
 * G4 水平拖进度自适应上限（毫秒）——三档：
 * 时长 ≤30s → 上限=时长（短视频一拖到底）；30s~2min → 60s；>2min → 120s。
 * duration 非 READY（≤0，含 C.TIME_UNSET 折算 0）→ 返回 0，调用方忽略本次手势 seek
 * （旧版 v1.15 修复口径：无时长信息禁止 seekTo(0)）。
 */
internal fun gestureSeekCapMs(durationMs: Long): Long = when {
    durationMs <= 0L -> 0L
    durationMs <= 30_000L -> durationMs
    durationMs <= 120_000L -> 60_000L
    else -> 120_000L
}

/** G7 倍速菜单档位（menuLabel = 弹出菜单行文案；顺序自上而下 2x → 0.5x，与旧控件一致） */
internal data class PlayerSpeedTier(val speed: Float, val menuLabel: String)

/**
 * 倍速档位表（0.5/1/1.5/2 四档）——旧控件 discreteSpeeds/speedLabels 逐字搬运为数据。
 * 注意口径：菜单行 1x 显示「1x」，倍速**按钮**才显示「倍速」（见 [speedButtonText]），与旧版一致。
 */
internal val PLAYER_SPEED_TIERS: List<PlayerSpeedTier> = listOf(
    PlayerSpeedTier(2f, "2x"),
    PlayerSpeedTier(1.5f, "1.5x"),
    PlayerSpeedTier(1f, "1x"),
    PlayerSpeedTier(0.5f, "0.5x"),
)

/** 倍速按钮文案（G7：1x 显示「倍速」，其余档「N倍」；非标档回退「Nx」） */
internal fun speedButtonText(speed: Float): String = when (speed) {
    0.5f -> "0.5倍"
    1f -> "倍速"
    1.5f -> "1.5倍"
    2f -> "2倍"
    else -> "${speed}x"
}
