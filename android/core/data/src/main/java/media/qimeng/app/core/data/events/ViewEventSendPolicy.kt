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

/** 发送结果的处置裁决（drain 依据它决定「已删行不再回队 / 回队重试」） */
enum class ViewEventSendVerdict {
    /** 服务端确认受理：行已删，正常收敛 */
    CONFIRMED,

    /** 终局失败：行已删且不回队（重试也没用，丢弃=宁少计不虚增） */
    DISCARD,

    /** 可重试失败：行已删但回队尾保留，下次补传再试 */
    RETRY,
}

/**
 * 发送失败分类表（M4-4 冻结口径，表驱动纯函数，单测逐行锁定）：
 *
 * | 结果                     | 裁决     | 为什么 |
 * |--------------------------|----------|--------|
 * | HTTP 202                 | CONFIRMED | 协议唯一声明的成功码（openapi POST /events/view） |
 * | HTTP 400/401/403/404     | DISCARD   | 请求本身不被接受（载荷/鉴权/路由问题），重发结局相同 |
 * | HTTP 429/408             | RETRY     | 限流/超时，退避后重发有意义 |
 * | HTTP 5xx                 | RETRY     | 服务端暂态故障 |
 * | HTTP 其他 2xx（如 200）  | RETRY     | 协议只声明 202：非声明确认不信任，重发由服务端会话级去重兜底（open/play 去重、dwell 累加），宁少计不虚增 |
 * | IO / 未知状态码          | RETRY     | 没证据表明终局，保守保留 |
 *
 * 协议缺口备注：openapi.yaml 未声明 4xx/5xx 响应形状（主会话已知，记待拍板），
 * 本表按 HTTP 通用语义取码，服务端改语义时只需改此处。
 */
object ViewEventSendPolicy {

    /** 毒丸连败阈值（次）：同一事件连续可重试失败达此数即丢弃（冻结口径 ≥3 丢弃+日志） */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /** 协议声明的唯一成功状态码（openapi.yaml POST /events/view 202；协议侧改动须同步此处） */
    const val HTTP_ACCEPTED = 202

    /** 终局失败码表：请求被服务端明确拒绝且重发无意义 */
    private val DISCARD_CODES = setOf(400, 401, 403, 404)

    /** 可重试码表：限流/请求超时 */
    private val RETRY_CODES = setOf(429, 408)

    /** 5xx 段下界（5xx 全段可重试） */
    private const val SERVER_ERROR_RANGE_START = 500

    /** 5xx 段上界（HTTP 语义上限） */
    private const val SERVER_ERROR_RANGE_END = 599

    /** 表驱动判定入口：唯一分类逻辑，drain 与单测共用（单一事实源） */
    fun classify(result: ViewEventSendResult): ViewEventSendVerdict {
        val code = (result as? ViewEventSendResult.Http)?.statusCode ?: return ViewEventSendVerdict.RETRY
        return when {
            code == HTTP_ACCEPTED -> ViewEventSendVerdict.CONFIRMED
            code in DISCARD_CODES -> ViewEventSendVerdict.DISCARD
            code in RETRY_CODES -> ViewEventSendVerdict.RETRY
            code in SERVER_ERROR_RANGE_START..SERVER_ERROR_RANGE_END -> ViewEventSendVerdict.RETRY
            else -> ViewEventSendVerdict.RETRY
        }
    }
}
