package media.qimeng.app.feature.detail.playback

import media.qimeng.app.core.model.ViewEventKind

/**
 * 行为打点组装器（M4-3 3d，纯逻辑：上报函数与时钟均经构造注入）。
 *
 * 职责（DOMAIN_RULES §5：open=进入详情页一次 / play=起播一次 / dwell=停留时长）：
 * - [onDetailEntered]：进入详情页 → open 打点一次（每实例一次），并开 dwell 会话；
 * - [onPlayStarted]：起播 → play 打点一次（每实例一次）；
 * - dwell 委托 [DwellSessionTracker]（按后台切换分段、逐条累加——服务端 dwell 不去重，
 *   真实口径出处见其类注释；engagement.go / GUIDE_API.md，openapi:951 注释有误导）；
 * - [destroy] 后所有入口无害 no-op（生命周期安全：销毁前做最后一次兜底 flush）。
 *
 * 上报为同步回调（生产接线：DetailViewModel 回调内 scope.launch → DetailRepository.reportViewEvent；
 * M4-4 起该落点写进 :core:data 的离线队列 pending_view_events，出网补传由队列三通道异步完成）。
 *
 * @param assetId 本页资产 id
 * @param sessionIdProvider 会话标识来源（App 启动生成一次的 UUID；M4-2 客户端无既有
 *   打点 sessionId 来源，本类只经 provider 取值、不负责生成与存储——生成端在接线批次落位）
 * @param nowMs 时钟（测试注入假时钟）
 * @param report 上报出口（同步回调）
 */
class DirectAnalyticsReporter(
    private val assetId: String,
    private val sessionIdProvider: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val report: (emission: ViewEventEmission) -> Unit,
) {

    /** 一次打点出网载荷（领域口径，SDK 类型封闭在 core:data） */
    data class ViewEventEmission(
        val assetId: String,
        val kind: ViewEventKind,
        val startedAtMs: Long,
        val sessionId: String,
        val dwellSeconds: Long?,
    )

    private var destroyed = false
    private var openReported = false
    private var playReported = false

    private val dwellTracker = DwellSessionTracker(nowMs) { startedAtMs, seconds ->
        emit(ViewEventKind.DWELL, startedAtMs, seconds)
    }

    /** 进入详情页：open 打点一次 + 开 dwell 会话（重复进入不重复打 open） */
    fun onDetailEntered() {
        if (destroyed) return
        if (!openReported) {
            openReported = true
            emit(ViewEventKind.OPEN, nowMs(), dwellSeconds = null)
        }
        dwellTracker.enter()
    }

    /** 起播：play 打点一次（重复触发不重复打） */
    fun onPlayStarted() {
        if (destroyed) return
        if (!playReported) {
            playReported = true
            emit(ViewEventKind.PLAY, nowMs(), dwellSeconds = null)
        }
    }

    /** 挂起（进后台/暂停）：dwell 兜底 flush */
    fun onPaused() {
        if (destroyed) return
        dwellTracker.pause()
    }

    /** 恢复：紧跟 pause 开新 dwell 段（分段累加口径，见 DwellSessionTracker） */
    fun onResumed() {
        if (destroyed) return
        dwellTracker.resume()
    }

    /** 离开详情页：dwell 兜底 flush（幂等） */
    fun onDetailLeft() {
        if (destroyed) return
        dwellTracker.leave()
    }

    /** 生命周期销毁：最后一次 dwell 兜底 flush，此后一切调用无害 no-op */
    fun destroy() {
        if (destroyed) return
        dwellTracker.dispose() // 先兜底 flush（本次 flush 仍允许出网）
        destroyed = true
    }

    private fun emit(kind: ViewEventKind, startedAtMs: Long, dwellSeconds: Long?) {
        report(
            ViewEventEmission(
                assetId = assetId,
                kind = kind,
                startedAtMs = startedAtMs,
                sessionId = sessionIdProvider(),
                dwellSeconds = dwellSeconds,
            ),
        )
    }
}
