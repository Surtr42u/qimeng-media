package media.qimeng.app.core.data.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 业务失败可呈现；取消必须继续传播，不能落入失败提示或自动重试。 */
suspend inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    val value = block()
    currentCoroutineContext().ensureActive()
    Result.success(value)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    Result.failure(e)
}
