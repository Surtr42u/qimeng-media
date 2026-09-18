package media.qimeng.app.core.data.diagnostics

import android.util.Log
import coil3.EventListener
import coil3.request.ErrorResult
import coil3.request.ImageRequest

/**
 * Coil 全局图片加载错误监听：把加载失败送进客户端异常上报通道（2026-09-18 真机
 * BUG-A 复发排查驱动——详情原图拉流失败被 UI 误报「无法解码」但服务端无任何记录，
 * 本机设备 logcat 不可用，上报通道是唯一现场来源）。
 *
 * 签名口径（coil 3.6.2 字节码核实，EventListener.onError(request, ErrorResult)）。
 * 级别取 warn：图片失败不致命且滑动场景可能连发（队列容量 200 兜底防涨）；
 * message 只带异常类名+消息（签名 URL 长且含凭据参数，不入日志）。
 */
class CoilErrorLogListener(
    private val recorder: ClientLogRecorder,
) : EventListener() {

    override fun onError(request: ImageRequest, result: ErrorResult) {
        val cause = result.throwable
        Log.w(LOG_TAG, "Image load failed throwable=${cause.javaClass.name}")
        recorder.record(
            level = ClientLogLevel.WARN,
            message = "Image load failed: ${cause::class.java.name}: ${cause.message}",
            stack = cause.stackTraceToString(),
            page = PAGE_IMAGE,
        )
    }

    companion object {
        private const val LOG_TAG = "CoilErrorLog"

        /** 图片加载错误条目的 page 定位标记 */
        const val PAGE_IMAGE = "image"
    }
}
