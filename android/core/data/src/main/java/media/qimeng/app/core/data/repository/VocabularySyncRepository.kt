package media.qimeng.app.core.data.repository

/**
 * 词表同步失败的业务分类（ADR-0034）。文案映射在 feature:manage ViewModel（铁律 7：
 * UI 零业务规则，业务分类与文案分属 data 层/UI 层）。
 */
enum class VocabularySyncError {

    /** 当前为本机模式（回环 18430）或未登录——没有远端权威源可拉 */
    NoAuthoritativeSource,

    /** 远端（NAS/电脑端）词表拉取失败：网络不通 / 401 / 5xx / 响应解析失败 */
    RemoteFetchFailed,

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
 * 同步预览（阶段一产物）：两端组数对照 + 本机独有计数（确认防线的核心数字）。
 * 「本机独有」= 本机 canonical 不在远端 canonical 集合（trim + 忽略大小写对齐引擎
 * 折叠口径），同步覆盖式下发后这些组会丢失。
 */
data class VocabularySyncPreview(
    val remoteGroupCount: Int,
    val localGroupCount: Int,
    val localOnlyGroupCount: Int,
    val remoteStopWordCount: Int,
    val localStopWordCount: Int,
)

/** 同步执行结果（阶段二产物）：实际下发覆盖的组数与停用词数 */
data class VocabularySyncResult(
    val appliedGroupCount: Int,
    val appliedStopWordCount: Int,
)

/**
 * 词表同步端口（ADR-0034，2026-10-04）：App 处于**远程登录态**时，把当前连接的
 * NAS/电脑端检索词表（自定义出处组 + 停用词）单向下发覆盖写入本机内嵌库，多端一致。
 *
 * 两段式交互：
 * - [preview] 阶段一（准备+预览）：远端 GET → 拉起内嵌服务端并等就绪 → 本地 dev-login
 *   → 本地 GET → 计算对照（远端 N 组 / 本机 M 组 / 本机独有 K 组）；
 * - [apply] 阶段二（确认+执行）：重走同一链路后本地 PUT，body 显式携带远端 groups 与
 *   stopWords（停用词恒传显式数组：远端无追加层传空数组=清空，保证「本机=远端」而非
 *   协议缺省的「保持现值」）。PUT 成功即完成（服务端自动后台全库重算）。
 *
 * 全部编排收口实现内（UI 零业务规则，铁律 7）；协议零改动——只消费既有
 * GET/PUT /sources/custom-groups 两端点（ADR-0033）。本机通道走 @LocalDirectClient
 * 无拦截器客户端 + dev-login（见 core:network NetworkModule），绝不触碰全局 NAS 会话。
 */
interface VocabularySyncRepository {

    /**
     * 阶段一：拉取两端词表并计算预览对照。
     * 失败以 [VocabularySyncException] 分类（Result.failure），成功返回 [VocabularySyncPreview]。
     */
    suspend fun preview(): Result<VocabularySyncPreview>

    /**
     * 阶段二：把远端词表显式覆盖写入本机内嵌库。
     * 独立重走全链路（不消费 [preview] 的缓存态，保证下发的是调用时刻的远端最新词表）；
     * 成功返回 [VocabularySyncResult]。
     */
    suspend fun apply(): Result<VocabularySyncResult>
}
