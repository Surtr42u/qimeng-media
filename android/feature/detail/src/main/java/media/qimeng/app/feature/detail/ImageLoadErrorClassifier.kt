package media.qimeng.app.feature.detail

import coil3.network.HttpException
import java.io.IOException

/**
 * 图片加载失败归因（2026-09-18 真机 BUG-A 复发驱动；第三百六十四笔扩为三档）：
 * 「查看永远发原件」口径下，原图拉流失败的归因决定详情页失败覆盖层的文案分档——
 * 归因错了就会误导用户（历史上三轮实证：传输超时报成「已损坏」、断外网 504 报成
 * 「无法解码」、OOM 报成「无法解码」，各自根因不同但症状一致）。
 *
 * 三档：
 * - [ImageFailureKind.NETWORK] 传输/服务类（超时/断流/HTTP 非 2xx）：重试即恢复的
 *   多数派 →「网络不畅」。
 * - [ImageFailureKind.MEMORY] 内存压力（[OutOfMemoryError]）：设备解码内存不足 →
 *   「内存不足」提示，与「文件损坏」严格区分（2026-09-20 客户端异常表实证：详情原图
 *   解码链 OOM 被旧两分法误报成损坏）。
 * - [ImageFailureKind.DECODE] 其余（真解码失败等）→「无法解码」兜底。
 *
 * 判据：cause 链上出现 [IOException]（SocketTimeoutException/UnknownHostException/
 * ConnectException/流中断等全是其子类）即 NETWORK；[HttpException] 同归 NETWORK——
 * 它是 coil3 网络层对非 2xx 响应的包装异常，继承 RuntimeException **而非 IOException**
 * （批S6 2026-09-19 断外网误报根因之一；Coil 工厂已改恒在线判定根治，此处并入是
 * 防御层）；链上出现 [OutOfMemoryError] 即 MEMORY（优先级低于 NETWORK：拉流途中
 * 因堆满抛 OOM 时若链上另有传输类痕迹，报网络档的重试指引更有用）。限深
 * [MAX_CAUSE_CHAIN_DEPTH] 防止自引用链死循环。
 */
internal const val MAX_CAUSE_CHAIN_DEPTH = 5

internal enum class ImageFailureKind { NETWORK, MEMORY, DECODE }

internal fun classifyImageFailure(cause: Throwable): ImageFailureKind {
    var current: Throwable? = cause
    var depth = 0
    var sawMemory = false
    while (current != null && depth < MAX_CAUSE_CHAIN_DEPTH) {
        if (current is IOException || current is HttpException) return ImageFailureKind.NETWORK
        if (current is OutOfMemoryError) sawMemory = true
        current = current.cause
        depth++
    }
    return if (sawMemory) ImageFailureKind.MEMORY else ImageFailureKind.DECODE
}
