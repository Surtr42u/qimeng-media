package media.qimeng.app.feature.detail

import coil3.network.HttpException
import java.io.IOException

/**
 * 图片加载失败归因（2026-09-18 真机 BUG-A 复发驱动）：「查看永远发原件」口径下，
 * 原图拉流失败绝大多数是传输类问题（超时/Wi-Fi 抖动/NAS 端风暴），重试即恢复；
 * 把它们误报成「文件无法解码/已损坏」误导用户（当日真机把传输超时报成损坏，
 * 重载后正常显示）。归因结果决定详情页失败覆盖层的文案分档：
 * 传输类 → 「网络不畅」提示；其余（真解码失败等）→ 保留「无法解码」兜底。
 *
 * 判据：cause 链上出现 [IOException]（SocketTimeoutException/UnknownHostException/
 * ConnectException/流中断等全是其子类）即判传输类；[HttpException] 同归传输类——
 * 它是 coil3 网络层对非 2xx 响应的包装异常，继承 RuntimeException **而非 IOException**
 * （批S6 2026-09-19 断外网误报「无法解码」根因之一：Coil 默认离线判定把回环请求改写为
 * only-if-cached → 本模块 OkHttpClient 无 HTTP 缓存 → OkHttp 合成 504 Unsatisfiable
 * Request → 抛 HttpException，旧判据只认 IOException 把它误归非网络类；Coil 工厂已改
 * 恒在线判定根治，此处并入是防御层——服务端 5xx 或未来 reintroduce 离线判定时同归
 * 「网络/服务」档而非「文件损坏」，与下述 HTTP 层口径一致）。限深 [MAX_CAUSE_CHAIN_DEPTH]
 * 防止自引用链死循环。HTTP 层错误（4xx/5xx）归传输/服务类——与解码失败（非 IO 异常）
 * 是清晰的两分。
 */
internal const val MAX_CAUSE_CHAIN_DEPTH = 5

internal fun isNetworkTransferFailure(cause: Throwable): Boolean {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_CHAIN_DEPTH) {
        if (current is IOException || current is HttpException) return true
        current = current.cause
        depth++
    }
    return false
}
