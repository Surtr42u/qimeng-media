package media.qimeng.app.core.data.events

/**
 * 单条浏览事件的发送结果（HTTP 状态码或出网 IO 失败）。statusCode 语义：
 * 2xx 全部经此透传（协议只声明 202，非 202 的 2xx 按「未声明确认」处理，见分类表）。
 */
sealed interface ViewEventSendResult {
    /** 服务端已给出 HTTP 状态码 */
    data class Http(val statusCode: Int) : ViewEventSendResult

    /** 没拿到状态码：IOException / 客户端构造失败等（含 SDK 对 1xx/3xx 抛的 UnsupportedOperationException） */
    data object IoError : ViewEventSendResult
}

/**
 * 发送结果的处置裁决（drain 依据它决定「删行收敛 / 退避重试 / 终局标记」）。
 * 任务L L5 起 RETRY 永不放弃（退避由 nextAttemptAt 控制），DISCARD 不删行（保留供导出）。
 */
enum class ViewEventSendVerdict {
    /** 服务端确认受理（2xx）：行已确认送达，删行收敛 */
    CONFIRMED,

    /** 终局失败（4xx 请求不被接受）：**不删行**，标记终局不再重试、保留供导出（用户手动补传） */
    DISCARD,

    /** 可重试失败（IO/5xx/限流）：行保留在队列，写入退避到期时刻后由下轮触发再试 */
    RETRY,
}

/**
 * 发送失败分类表（任务L L5「本地优先」口径，表驱动纯函数，单测逐行锁定；
 * 推翻 M4-4 的「毒丸连败丢弃」——重试永不放弃，最坏情形是退避到周期档慢慢磨）：
 *
 * | 结果                     | 裁决      | 为什么 |
 * |--------------------------|-----------|--------|
 * | HTTP 2xx（含声明的 202） | CONFIRMED | 发送成功才删行（拍板 #7）。协议只声明 202，其余 2xx（如 200）旧口径保守重试；L5 起每行携带 clientEventId、服务端唯一索引幂等（重发不双计），信任 2xx 不再有虚增风险，反而避免「非声明 2xx 永久重试」的无底洞 |
 * | HTTP 400/401/403/404     | DISCARD   | 请求本身不被接受（载荷/鉴权/路由问题），重发结局相同；**行保留并标记终局**，用户可经「导出未上传」手动处理（不再删除） |
 * | HTTP 429/408             | RETRY     | 限流/超时，退避后重发有意义 |
 * | HTTP 5xx                 | RETRY     | 服务端暂态故障 |
 * | HTTP 其他 4xx（如 409）  | RETRY     | 未声明的 4xx 不轻信终局（可能只是本实现的语义盲区），退避重试由服务端幂等兜底不双计 |
 * | IO / 未知状态码          | RETRY     | 没证据表明终局，保守保留 |
 *
 * 协议缺口备注：openapi.yaml 未声明 4xx/5xx 响应形状（主会话已知，记待拍板），
 * 本表按 HTTP 通用语义取码，服务端改语义时只需改此处。
 */
object ViewEventSendPolicy {

    /** 协议声明的成功状态码（openapi.yaml POST /events/view 202；协议侧改动须同步此处） */
    const val HTTP_ACCEPTED = 202

    /** 成功段下界/上界（2xx 全段=确认；幂等键使「信任 2xx」安全，见分类表首行） */
    private const val SUCCESS_RANGE_START = 200
    private const val SUCCESS_RANGE_END = 299

    /** 终局失败码表：请求被服务端明确拒绝且重发无意义（标记终局、保留供导出） */
    private val DISCARD_CODES = setOf(400, 401, 403, 404)

    /** 可重试码表：限流/请求超时 */
    private val RETRY_CODES = setOf(429, 408)

    /** 5xx 段下界（5xx 全段可重试） */
    private const val SERVER_ERROR_RANGE_START = 500

    /** 5xx 段上界（HTTP 语义上限） */
    private const val SERVER_ERROR_RANGE_END = 599

    /**
     * 退避基础档（毫秒）：首次可重试失败后 30s 内不重发。下限保护——「立即同步」
     * 手动触发连点 / 写入触发高频拉起 drain 时，故障端点不会被每条新事件打一次。
     */
    const val RETRY_BACKOFF_BASE_MS = 30_000L

    /**
     * 退避上限 = 周期兜底通道的间隔（WorkManager 系统 15min 下限）：单行最坏情形
     * 退到与周期兜底同频，与三通道节奏对齐（再往上拖没有额外收益）。
     */
    val RETRY_BACKOFF_MAX_MS: Long = EventSyncWorkSpec.PERIODIC_INTERVAL_MILLIS

    /** 指数退避档位上限（位移防溢出护栏：2^30 * 30s 已远超上限值） */
    private const val BACKOFF_MAX_SHIFT = 30

    /**
     * 指数退避：第 n 次连败 → base * 2^(n-1)，封顶 [RETRY_BACKOFF_MAX_MS]。
     * 纯函数（时间点由调用方 now+返回值拼装），单测锁定档位序列。
     */
    fun backoffDelayMs(consecutiveFailures: Int): Long {
        val shift = (consecutiveFailures - 1).coerceIn(0, BACKOFF_MAX_SHIFT)
        return (RETRY_BACKOFF_BASE_MS shl shift).coerceAtMost(RETRY_BACKOFF_MAX_MS)
    }

    /** 表驱动判定入口：唯一分类逻辑，drain 与单测共用（单一事实源） */
    fun classify(result: ViewEventSendResult): ViewEventSendVerdict {
        val code = (result as? ViewEventSendResult.Http)?.statusCode ?: return ViewEventSendVerdict.RETRY
        return when {
            code in SUCCESS_RANGE_START..SUCCESS_RANGE_END -> ViewEventSendVerdict.CONFIRMED
            code in DISCARD_CODES -> ViewEventSendVerdict.DISCARD
            code in RETRY_CODES -> ViewEventSendVerdict.RETRY
            code in SERVER_ERROR_RANGE_START..SERVER_ERROR_RANGE_END -> ViewEventSendVerdict.RETRY
            else -> ViewEventSendVerdict.RETRY
        }
    }
}
