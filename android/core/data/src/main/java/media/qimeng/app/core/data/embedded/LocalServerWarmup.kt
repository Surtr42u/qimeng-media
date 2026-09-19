package media.qimeng.app.core.data.embedded

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.network.ServerAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批S8：端口就绪等待上限。Go 服务端冷启动为亚秒级，5s 已含低端机与进程重启余量；
 * 超时不视为失败（调用方继续走既有登录/探活链报「地址不通」，不造新错误文案）。
 */
internal const val localServerReadyTimeoutMs = 5_000L

/** 批S8：端口就绪轮询间隔（冻结口径 200ms）。 */
internal const val localServerPollIntervalMs = 200L

/**
 * 登录前本机服务端预热等待器（批S8 用户实测死锁修复，M6 形态 B 主入口）。
 *
 * 死锁链（真机复现）：手机 serverUrl 主键=NAS 时停在登录页 → 点登录页「本机模式」
 * 填入 127.0.0.1:18430 → 点登录 → 旧链路里内嵌服务端只在「登录成功后
 * updateServerUrl(18430)」才被壳层 collector 拉起（MainViewModel 的 serverUrl
 * collect 自检，主键不变则永不触发），而服务没起 → 探活必败 → 登录必败 →
 * updateServerUrl(18430) 永不发生 → 18430 永无监听。「未登录态经登录页进入本机
 * 模式」（ADR-0015 形态 B 主入口）完全不可用。修法（冻结）：AuthRepositoryImpl.login
 * 构造 baseUrl 后、发登录请求前，先 ensureStartedIfLocalMode 幂等拉起 + 本器轮询
 * 等端口就绪；与壳层自检链互补不冲突（登录成功后 collector 再触发 ensure 为幂等 no-op）。
 *
 * 等待语义：轮询 TCP connect 探测 [ServerAddress.LOCAL_MODE_PORT]（间隔
 * [localServerPollIntervalMs]、上限 [localServerReadyTimeoutMs]）。就绪返回 true；
 * 超时返回 false——**超时不是失败**，调用方继续发登录请求，错误文案仍由既有探活/
 * 登录错误链给出（ServerUnreachable「地址不通」），本层不产生任何新错误分类。
 */
interface LocalServerWarmup {

    /**
     * 轮询等待本机内嵌服务端端口就绪。
     *
     * @param timeoutMs 等待上限（默认 [localServerReadyTimeoutMs]）
     * @param pollIntervalMs 轮询间隔（默认 [localServerPollIntervalMs]）
     * @return true=端口已可连；false=超时仍未就绪（调用方继续原流程，不在此造错误）
     */
    suspend fun awaitReady(
        timeoutMs: Long = localServerReadyTimeoutMs,
        pollIntervalMs: Long = localServerPollIntervalMs,
    ): Boolean
}

/**
 * [LocalServerWarmup] 实现：withTimeoutOrNull 轮询探测，探针挪到 IO 调度器
 * （login 链跑在主线程 viewModelScope，socket connect 是阻塞调用不得上主线程）。
 * 已知且无害的边界：超时取消不会中断进行中的阻塞 connect（协作式取消），最多
 * 拖延一个探针超时窗（[SocketLocalPortProber] 250ms）后才真正返回。
 */
@Singleton
class LocalServerWarmupImpl @Inject constructor(
    private val prober: LocalPortProber,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LocalServerWarmup {

    override suspend fun awaitReady(timeoutMs: Long, pollIntervalMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!probeOnce()) delay(pollIntervalMs)
            true
        } ?: false

    private suspend fun probeOnce(): Boolean = withContext(ioDispatcher) {
        prober.probe(LOOPBACK_HOST, ServerAddress.LOCAL_MODE_PORT)
    }

    private companion object {
        /** 探测目标=回环+预设端口（与 EmbeddedServerConfig.LISTEN_ADDRESS 同源，经端口单值互指常量）。 */
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}

/**
 * 端口探针抽象（JVM 单测桩替换点：立即就绪/超时两路 Fake 各锁一态）。
 */
fun interface LocalPortProber {

    /** @return 是否能在 host:port 建立 TCP 连接（阻塞调用，实现须自带连接超时）。 */
    fun probe(host: String, port: Int): Boolean
}

/**
 * 真实探针：TCP connect 探测（比 HTTP 探活轻——Go 服务端起来前连接被拒、起来后即
 * 成功，无需等待 /healthz 响应体）。回环上连接拒绝是即时的，连接超时仅覆盖调度抖动。
 */
@Singleton
class SocketLocalPortProber @Inject constructor() : LocalPortProber {

    override fun probe(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        true
    } catch (_: IOException) {
        false
    }

    private companion object {
        /** 单次连接超时（毫秒）：回环连接拒绝亚毫秒级，250ms 只兜调度抖动，不拖累轮询节奏。 */
        const val CONNECT_TIMEOUT_MS = 250
    }
}
