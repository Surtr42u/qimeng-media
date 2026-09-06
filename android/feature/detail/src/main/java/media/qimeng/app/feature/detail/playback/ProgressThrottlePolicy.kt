package media.qimeng.app.feature.detail.playback

/**
 * 播放进度上报节流策略（M4-3 3d，纯逻辑：时间源经构造注入，测试不依赖真实时钟/delay）。
 *
 * 项目冻结口径：**5s 心跳节流**，严于协议建议的 10s（协议允许更严；具名常量见下）。
 * 暂停/离开时的「立即补报」由调用方对策略 [force] 后消费实现。
 *
 * 「节流窗口内被吞的最近位置不丢」：[observe] 每次播放 tick 都覆盖最新位置，
 * [consumeReportable] 放行时返回的**永远是最新一次 observe 的值**——被吞掉的中间位置
 * 自然被覆盖，放行即用最新值，无需调用方另存。
 *
 * 放行即记为一次上报窗口起点（[consumeReportable] 返回非 null 时更新上次上报时刻）。
 * 网络失败的重试语义：调用方自行 [force] 后再次消费（本策略不感知网络结果）。
 */
class ProgressThrottlePolicy(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private var lastReportedAtMs: Long? = null
    private var latestPositionSeconds: Double? = null
    private var forced = false

    /** 播放 tick 喂入最新位置（窗口内多次调用只保留最新，旧的被覆盖=被吞不丢最新值） */
    fun observe(positionSeconds: Double) {
        latestPositionSeconds = positionSeconds
    }

    /**
     * 距上次放行 >= [PROGRESS_REPORT_INTERVAL_MS] 才放行（首次必放行）；
     * 放行返回最新位置并把当前时刻记为窗口起点，节流窗口内返回 null（吞掉）。
     */
    fun consumeReportable(): Double? {
        val position = latestPositionSeconds ?: return null // 从未 observe 过：无值可报
        val now = nowMs()
        val last = lastReportedAtMs
        val allowed = forced || last == null || now - last >= PROGRESS_REPORT_INTERVAL_MS
        if (!allowed) return null
        forced = false
        lastReportedAtMs = now
        return position
    }

    /** 强制下次消费放行（暂停/离开立即补报、网络失败重试用） */
    fun force() {
        forced = true
    }

    /** 重置全部状态（换资产/换播放实例时调用，回到「首次必放行」初态） */
    fun reset() {
        lastReportedAtMs = null
        latestPositionSeconds = null
        forced = false
    }

    companion object {
        /**
         * 进度心跳节流间隔（毫秒）。协议建议 10s 心跳，本项目冻结 5s（更严，协议允许）：
         * NAS 局域网场景出网成本低，5s 换更强的断点精度（服务端只存最新值，无累积压力）。
         */
        const val PROGRESS_REPORT_INTERVAL_MS = 5000L
    }
}
