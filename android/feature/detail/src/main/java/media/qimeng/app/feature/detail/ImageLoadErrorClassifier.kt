package media.qimeng.app.feature.detail

import java.io.IOException

/**
 * 图片加载失败归因（2026-09-18 真机 BUG-A 复发驱动）：「查看永远发原件」口径下，
 * 原图拉流失败绝大多数是传输类问题（超时/Wi-Fi 抖动/NAS 端风暴），重试即恢复；
 * 把它们误报成「文件无法解码/已损坏」误导用户（当日真机把传输超时报成损坏，
 * 重载后正常显示）。归因结果决定详情页失败覆盖层的文案分档：
 * 传输类 → 「网络不畅」提示；其余（真解码失败等）→ 保留「无法解码」兜底。
 *
 * 判据：cause 链上出现 [IOException]（SocketTimeoutException/UnknownHostException/
 * ConnectException/流中断等全是其子类）即判传输类；限深 [MAX_CAUSE_CHAIN_DEPTH]
 * 防止自引用链死循环。HTTP 层错误（4xx/5xx）经 OkHttp 抛 IOException 子类，同归
 * 传输/服务类——与解码失败（非 IO 异常）是清晰的两分。
 */
internal const val MAX_CAUSE_CHAIN_DEPTH = 5

internal fun isNetworkTransferFailure(cause: Throwable): Boolean {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_CHAIN_DEPTH) {
        if (current is IOException) return true
        current = current.cause
        depth++
    }
    return false
}
