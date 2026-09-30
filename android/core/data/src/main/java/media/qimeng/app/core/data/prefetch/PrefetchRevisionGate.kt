package media.qimeng.app.core.data.prefetch

/**
 * 预取轮的修订号判定结论（纯函数产出，2026-09-30 revision 跳过批）。
 */
enum class PrefetchRoundDecision {

    /** 库未变：整轮跳过（不拉全量列表、不逐条探测，直接进 [PrefetchUiState.Skipped] 终态） */
    SKIP,

    /** 需要全量轮（首次 / 降级 / 库已变），沿用原「拉列表 + 4 并发预取」路径 */
    FULL,
}

/**
 * 预取跳过判定门（纯函数，无 IO 可单测锁定）：输入服务端当前修订号、「上一轮完成
 * 时记录的三元组（服务器标识+修订号，[PrefetchRevisionRecord]）」与本轮连接的服务器
 * 标识，决定本轮整轮跳过还是跑全量。
 *
 * 判定规则（缺省永远偏 FULL——少拉一轮才需要关心，多拉一轮永远安全，
 * 与 GET /api/v1/library/revision 服务端语义注释同口径）：
 * - serverRevision == null → FULL：revision 端点失败（网络错/旧服务端 404/解析错），
 *   端口侧已降级 null，此时没有可信依据，照旧全量；
 * - record == null → FULL：首次（本机从未完成过一轮全量）、已失效（清池失效）、
 *   或存量迁移数据（只有旧版修订号键）——都按无记录处理；
 * - record.serverKey != serverKey → FULL：连接的服务器与记录时不一致。本地端与 NAS
 *   是**独立**修订号计数器、播种基线同为 `COUNT(assets)+1`，库规模相近时修订号可能
 *   撞号——不比服务器键会被错误 SKIP，换端后永远不预取（2026-09-30 撞号修复）；
 * - record.revision == serverRevision → SKIP：同一服务器且自上轮列表拉取后资产集合
 *   无任何变更（SKIP 后仍要过抽样核对，见 [PrefetchSampleGate]）；
 * - 其余（含 server < last 的异常回退）→ FULL：库已变；回退按变更处理是安全方向。
 */
object PrefetchRevisionGate {

    fun decide(
        serverRevision: Long?,
        record: PrefetchRevisionRecord?,
        serverKey: String,
    ): PrefetchRoundDecision = when {
        serverRevision == null -> PrefetchRoundDecision.FULL
        record == null -> PrefetchRoundDecision.FULL
        record.serverKey != serverKey -> PrefetchRoundDecision.FULL
        record.revision == serverRevision -> PrefetchRoundDecision.SKIP
        else -> PrefetchRoundDecision.FULL
    }
}
