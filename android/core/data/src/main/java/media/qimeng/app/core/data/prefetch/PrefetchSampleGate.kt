package media.qimeng.app.core.data.prefetch

/**
 * 预取抽样核对门（纯函数，无 IO 可单测锁定，2026-09-30 缓存漂移修复）：
 * revision 跳过只比对「记录 vs 服务端」的计数器，对本机磁盘缓存实况是盲的——
 * Coil 磁盘缓存池 maxSize LRU 驱逐、系统存储紧张清掉整个 cache 目录、备份恢复把
 * 服务端 `library_revision` 一起回滚（理论上可与客户端记录撞号），这三类「记录与
 * 实际脱节」都不会动客户端记录，修订号不变则缺片永不自愈（缺陷 2 + ADR-0026 记档项）。
 *
 * 兜法：全量轮完成时从全库缩略图 URL 随机抽 [SAMPLE_SIZE] 条与修订号**同事务**落盘
 * （写侧在 [PrefetchRevisionStore.setLastDone]），SKIP 判定通过后逐条本地磁盘探测
 * （[PrefetchDiskProbe] 按稳定键直查，URL 带过期签名不影响），缺失比例达
 * [MISSING_THRESHOLD_PCT] 即降级全量补拉并在轮末写回新样本——全部失败方向落在
 * 「多拉一轮无害」。
 */
object PrefetchSampleGate {

    /** 记录端抽样条数（用户拍板值）：全库随机抽样探测缺失率，量级兼顾探测耗时与代表性 */
    const val SAMPLE_SIZE = 50

    /**
     * 缺失比例阈值（百分整数）：低于此容忍——LRU 对最旧条目的少量修剪属正常行为，
     * 不值得为一两片缺图重拉全库；高于等于视为缓存与记录脱节，降级全量补拉。
     */
    const val MISSING_THRESHOLD_PCT = 20

    /**
     * 从全库缩略图 URL 抽样：不超过 [SAMPLE_SIZE] 全量保留（含空库 → 空集），超过
     * 则随机取恰好 [SAMPLE_SIZE] 条（[toSet] 去重）。[random] 可注入固定种子做
     * 确定性测试。
     */
    fun pickSample(
        urls: List<String>,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Set<String> =
        if (urls.size <= SAMPLE_SIZE) urls.toSet() else urls.shuffled(random).take(SAMPLE_SIZE).toSet()

    /**
     * 抽样核对是否应降级全量：整数运算避免浮点比较误差，缺失百分比 ≥ 阈值即 true。
     * [sampled] == 0（空库轮/无样本）恒 false = 维持 SKIP——空库本无片可缺；「无样本」
     * 多伴随空库轮（样本与修订号同事务写，全量轮必有样本），此时维持跳过与「缺省偏
     * 多拉」不冲突：空库全量轮成本为零，下次真实变更轮末会重新写入样本。
     */
    fun shouldDowngradeToFull(sampled: Int, missing: Int): Boolean =
        sampled > 0 && missing * 100 >= sampled * MISSING_THRESHOLD_PCT
}
