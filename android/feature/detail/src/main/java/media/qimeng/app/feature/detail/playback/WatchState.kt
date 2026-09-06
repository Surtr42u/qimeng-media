package media.qimeng.app.feature.detail.playback

/**
 * 已看完判定与续播起点（M4-3 3d 冻结口径，客户端推导——服务端不下发「已看完」字段）：
 *
 * - lastPositionSeconds 与 durationMs **两者非空**且 lastPositionSeconds >= durationMs/1000.0
 *   → 已看完（详情页显示「已看完」徽标，点播放从 0 重播）；
 * - 其余情况一律未看完（含任一为空），续播起点 = lastPositionSeconds ?: 0。
 *
 * 纯函数值对象：不碰 IO、不依赖时钟，边界行为由 WatchStateTest 锁定。
 */
data class WatchState(
    /** true=已看完：显示徽标 + 播放按钮文案从「续播」变「重播」（起点归 0） */
    val watched: Boolean,
    /** 续播起点秒：未看完时 = lastPositionSeconds ?: 0；已看完时恒 0（重播语义） */
    val resumeStartSeconds: Double,
) {
    companion object {
        fun of(lastPositionSeconds: Double?, durationMs: Long?): WatchState {
            val durationSeconds = durationMs?.let { it / 1000.0 }
            val watched = lastPositionSeconds != null &&
                durationSeconds != null &&
                lastPositionSeconds >= durationSeconds
            return if (watched) {
                WatchState(watched = true, resumeStartSeconds = 0.0)
            } else {
                WatchState(watched = false, resumeStartSeconds = lastPositionSeconds ?: 0.0)
            }
        }
    }
}
