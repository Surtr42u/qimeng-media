package media.qimeng.app.feature.detail.video

import kotlin.math.abs

/**
 * 播放器纯逻辑（3c，无 Android/View 依赖，JVM 单测锁行为）：
 * G4 拖进度自适应上限 + G7 倍速档位表 + W5 #50 时长格式化/总时长赋值判定。
 * 冻结口径来源 = 旧版 BiliPlayerView 实测参数（手势 G1~G9，任务书冻结件）；
 * BiliPlayerView 桥接件内调用本文件，保证单一事实源。
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

/**
 * 时长格式化（W5 #50 冻结件三件套：原 BiliPlayerView.formatMs 方法体逐行搬移为纯函数，
 * 行为不变；BiliPlayerView.formatMs 改为委托本函数，调用面零变动）。
 * 口径：非拉丁 locale 下也恒定输出拉丁数字（Locale.US），负时长带 "-" 前缀，
 * 有小时输出 h:mm:ss、否则 m:ss（分钟不补零）。
 */
internal fun formatDurationMs(ms: Long): String {
    val absMs = abs(ms)
    val totalSec = absMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val prefix = if (ms < 0) "-" else ""
    // 固定使用 Locale.US，确保时长始终输出拉丁数字（避免非拉丁 locale 下显示异常字符）
    val locale = java.util.Locale.US
    return if (h > 0) "$prefix$h:${String.format(locale, "%02d", m)}:${String.format(locale, "%02d", s)}"
    else "$prefix${m}:${String.format(locale, "%02d", s)}"
}

/**
 * 总时长文本赋值判定（W5 #50 修复的判定口径）：时长可用（>0，含 C.TIME_UNSET 折算 0 的
 * 排除）才产出格式化文本，否则返回 null=调用方不赋值、保持现状。
 * 背景：全屏覆盖层新视图 adopt 挂同一播放器时 STATE_READY 转换早已发生（Listener 只报
 * 状态*变化*），原先「仅 READY 分支赋值」在全屏视图上永不触发 → 总时长恒 00:00；
 * 现挂载即按本判定补同步，判定口径与 READY 分支同源（单测锁定）。
 */
internal fun totalDurationText(durationMs: Long): String? =
    if (durationMs > 0L) formatDurationMs(durationMs) else null

/** S2 点按动作（2026-09-19 拍板冻结件）：控制器显隐切换 / 播放暂停切换 */
internal enum class PlayerTapAction {
    /** 单击：切换「播放器控制条+顶栏」显隐（Compose 侧详情顶栏经显隐上报同拍联动） */
    TOGGLE_CONTROLLER,

    /** 双击：播放/暂停切换（播停语义自单击让位给双击） */
    TOGGLE_PLAY_PAUSE,
}

/**
 * 播放器点按动作映射（S2 2026-09-19 用户拍板「单击变成显示 ui 就是视频的 ui 和上方的
 * ui……双击才是暂停」=解冻令，纯函数无 Android 依赖，JVM 单测锁定）：
 * 单击确认 → 切控制器显隐；双击 → 播停；**排版态与全屏态同语义**——
 * 推翻 GUIDE_UI L185-186 旧口径（竖屏单击播停/横屏单击显隐控制器/竖屏双击无功能），
 * 冲突优先级「用户最新要求 > 规格书」（CHANGELOG 第三百四十七笔记档）。
 * [isFullscreen] 现参与但两档同语义：入参保留使「双向同语义」冻结口径在调用点显式可见，
 * 未来若要分档必先改本函数与单测（防调用点静默分叉）。
 */
internal fun resolvePlayerTapAction(isFullscreen: Boolean, isDoubleTap: Boolean): PlayerTapAction =
    if (isDoubleTap) PlayerTapAction.TOGGLE_PLAY_PAUSE else PlayerTapAction.TOGGLE_CONTROLLER
