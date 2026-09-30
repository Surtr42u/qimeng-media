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
 * 预取跳过判定门（纯函数，无 IO 可单测锁定）：输入服务端当前修订号与
 * 「上一轮完成时记录的修订号」，决定本轮整轮跳过还是跑全量。
 *
 * 判定规则（缺省永远偏 FULL——少拉一轮才需要关心，多拉一轮永远安全，
 * 与 GET /api/v1/library/revision 服务端语义注释同口径）：
 * - serverRevision == null → FULL：revision 端点失败（网络错/旧服务端 404/解析错），
 *   端口侧已降级 null，此时没有可信依据，照旧全量；
 * - lastDoneRevision == null → FULL：首次（本机从未完成过一轮全量）；
 * - 两者相等 → SKIP：自上轮列表拉取后资产集合无任何变更；
 * - 否则（含 server < last 的异常回退）→ FULL：库已变；回退按变更处理是安全方向。
 */
object PrefetchRevisionGate {

    fun decide(serverRevision: Long?, lastDoneRevision: Long?): PrefetchRoundDecision = when {
        serverRevision == null -> PrefetchRoundDecision.FULL
        lastDoneRevision == null -> PrefetchRoundDecision.FULL
        serverRevision == lastDoneRevision -> PrefetchRoundDecision.SKIP
        else -> PrefetchRoundDecision.FULL
    }
}
