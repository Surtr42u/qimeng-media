package media.qimeng.app.feature.detail.playback

/**
 * dwell 停留会话状态机（M4-3 3d，纯逻辑：时间源经构造注入）。
 *
 * 口径出处（**服务端真实口径**）：
 * - server/internal/httpapi/engagement.go（PostApiV1EventsView 函数头）：open/play 按
 *   assetId+kind+sessionId+当日 会话级去重；**dwell 例外——不去重、逐条插入累加**
 *   （「停留时长每次都有效（时长是累加量而非计数，去重会丢真实停留时间），逐条插入
 *   并把秒数累加进物化表」）；
 * - docs/GUIDE_API.md「行为上报」行：「open/play 按 assetId+kind+sessionId+当日去重，
 *   dwell 不去重——秒数每次有效」；
 * - 注意：openapi.yaml:951 sessionId 注释笼统写「按 assetId+kind+sessionId+当日 会话级
 *   去重」未标明 dwell 例外，是误导源（勘误已上报待拍板；口径以服务端实现为准）。
 *
 * 因此同一次详情停留按「后台切换」**切段逐条上报**（不去重，服务端逐条累加秒数）：
 * - [enter]：开新会话（已在会话中则幂等 no-op，不重置计时）；
 * - [pause]：flush 当前段（防后台被杀丢数）并**结束会话**；
 * - [resume]：开新会话（新 startedAt）——仅紧跟 pause 才生效；从未 enter 过 / 已 leave
 *   的 resume 无害 no-op（防凭空开段）；
 * - [leave]/[dispose]：flush 当前段后关闭会话；重复 leave/destroy 不产第二条（段内幂等）；
 * - [flush]：段内幂等——重复调用只发一次，直到下一段开始。
 *
 * seconds 语义：`(now - start) / 1000` 长整型除法**截断**（不足 1 秒不计入，
 * 与服务端逐秒聚合粒度对齐），非四舍五入。
 */
class DwellSessionTracker(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onDwell: (startedAtMs: Long, seconds: Long) -> Unit,
) {

    private class Session(val startedAtMs: Long) {
        var flushed = false
    }

    private var session: Session? = null

    /** 会话是否因 pause 挂起（resume 只补开这种会话；从未 enter / 已 leave 的 resume 无效） */
    private var awaitingResume = false

    /** 进入详情页：开会话；已在会话中则幂等 no-op */
    fun enter() {
        if (session == null) {
            session = Session(nowMs())
        }
        awaitingResume = false
    }

    /** 挂起（进后台/暂停播放）：flush 当前段并结束会话（resume 再开新段，秒数由服务端累加） */
    fun pause() {
        flush()
        // 守卫（2026-09-07 审查 P3）：仅当本次 pause 真实结束了一段（session 非空）才置
        // 挂起位。连续第二次 pause 时 session 已为 null，若直写 "awaitingResume = session != null"
        // 会把第一次 pause 置好的挂起位清掉，其后 resume 变 no-op，丢一段计时。
        if (session != null) {
            awaitingResume = true
        }
        session = null
    }

    /** 恢复：紧跟 pause 才开新段（新 startedAt）；从未 enter / 已 leave 的 resume 无害 no-op */
    fun resume() {
        if (awaitingResume) {
            session = Session(nowMs())
            awaitingResume = false
        }
    }

    /** 离开详情页：flush 当前段（幂等）后关会话；未 enter 过则无害 no-op */
    fun leave() {
        flush()
        session = null
        awaitingResume = false
    }

    /** 生命周期销毁：与 [leave] 同义（兜底 flush 已有段，销毁后重复调用无害） */
    fun dispose() {
        leave()
    }

    /**
     * 上报当前段停留（幂等：一段至多发一条，重复调用直到下一段前都被吞）。
     * 无会话时无害 no-op。
     */
    fun flush() {
        val current = session ?: return
        if (current.flushed) return
        current.flushed = true
        // TODO(M4-4): dwell 打点当前直连上报，改走离线队列（弱网/退后台不丢）
        onDwell(current.startedAtMs, (nowMs() - current.startedAtMs) / 1000L)
    }
}
