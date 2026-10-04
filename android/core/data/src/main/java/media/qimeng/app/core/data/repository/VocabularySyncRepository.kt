package media.qimeng.app.core.data.repository

/**
 * 词表同步失败的业务分类（ADR-0034/0035）。文案映射在 feature:manage ViewModel
 * （铁律 7：UI 零业务规则，业务分类与文案分属 data 层/UI 层）。词表维护（ADR-0035）
 * 复用本分类的本地两支——同一底层通道（LocalVocabularyChannel）、同一失败模式，
 * 不另立平行枚举。
 */
enum class VocabularySyncError {

    /** 当前为本机模式（回环 18430）或未登录——没有远端可合并 */
    NoAuthoritativeSource,

    /** 远端（NAS/电脑端）词表拉取失败：网络不通 / 401 / 5xx / 响应解析失败 */
    RemoteFetchFailed,

    /** 远端（NAS/电脑端）词表写入（PUT）失败 */
    RemoteApplyFailed,

    /** 本机内嵌服务端不可用：拉起失败 / 端口未就绪 / dev-login 失败 / 本地 GET 失败 */
    LocalServerUnavailable,

    /** 本机写入（PUT）失败 */
    LocalApplyFailed,
}

/** 词表同步领域异常（[VocabularySyncRepository] 以 Result 失败态抛出，[error] 供 VM 映射文案） */
class VocabularySyncException(
    val error: VocabularySyncError,
    cause: Throwable? = null,
) : Exception(error.name, cause)

/**
 * 合并同步结果：两端收敛到的并集规模 + 各端补入对方的增量组数（结果提示用）。
 */
data class VocabularySyncResult(
    /** 合并后两端一致的出处组数 */
    val mergedGroupCount: Int,
    /** 合并后两端一致的停用词数 */
    val mergedStopWordCount: Int,
    /** 远端补入本机的独有组数（本机原本没有的） */
    val newFromRemoteCount: Int,
    /** 本机补入远端的独有组数（远端原本没有的） */
    val newFromLocalCount: Int,
)

/**
 * 词表合并同步端口（ADR-0034 单向下发的定稿改版，ADR-0035，2026-10-04 用户拍板）：
 * **词表是只增不删的数据**——用户工作流只有补录没有删除，两端并集合并是无损增量，
 * 覆盖式同步（含双向覆盖方案）整体退役，两段式确认防线随覆盖语义一并退役（无丢失
 * 即无需防线）。
 *
 * 单键同步：远端 GET + 本机 GET → [VocabularyMerger] 并集合并 → 合并结果显式 PUT
 * 回两端（groups+stopWords 恒显式数组）。两端收敛到同一并集；同步幂等，中断后重跑
 * 即收敛。服务端/openapi/SDK 零改动——只消费既有 GET/PUT /sources/custom-groups。
 *
 * 门禁：远程登录态（本机模式没有远端可合并）。本机通道走 LocalVocabularyChannel
 * （@LocalDirectClient 无拦截器客户端 + dev-login），绝不触碰全局 NAS 会话；远端通道
 * 走标准鉴权（BusinessApiFactory + 全局 OkHttp 自带 Bearer）。
 */
interface VocabularySyncRepository {

    /**
     * 两端词表增量合并同步（无损并集；纯客户端编排，见 [VocabularyMerger] 合并规则）。
     * 失败以 [VocabularySyncException] 分类（Result.failure），成功返回 [VocabularySyncResult]。
     */
    suspend fun sync(): Result<VocabularySyncResult>
}
