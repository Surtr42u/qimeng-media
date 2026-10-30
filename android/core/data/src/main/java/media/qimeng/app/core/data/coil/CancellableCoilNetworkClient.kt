package media.qimeng.app.core.data.coil

import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.asNetworkClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.Call

/** 复用Coil的HTTP映射；取消绑定覆盖整个响应体消费期，而非只等响应头。 */
internal class CancellableCoilNetworkClient(private val callFactory: Call.Factory) : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (NetworkResponse) -> T,
    ): T = coroutineScope {
        val requestJob = coroutineContext.job
        val call = AtomicReference<Call?>()
        val completed = AtomicBoolean(false)
        val delegate = Call.Factory { httpRequest ->
            callFactory.newCall(httpRequest).also {
                call.set(it)
                // 取消可能先于newCall；补齐这一交接窗口，不能留下无人取消的socket。
                if (!requestJob.isActive) it.cancel()
            }
        }.asNetworkClient()
        // 响应体可能阻塞IO线程；取消观察者放Default池，及时关闭底层Call来打断读取。
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!completed.get()) call.get()?.cancel()
            }
        }
        try {
            delegate.executeRequest(request, block)
        } catch (failure: Exception) {
            // Call.cancel打断阻塞读通常抛SocketException；已取消时必须保留取消语义，
            // 不能变成图片加载失败。仍活跃的请求照常传播真实I/O错误。
            requestJob.ensureActive()
            throw failure
        } finally {
            completed.set(true)
            cancellation.cancel()
        }
    }
}
