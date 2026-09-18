package media.qimeng.app.core.network

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.qimeng.app.core.network.di.ApplicationScope
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.infrastructure.ServerException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 服务端就绪探针：对当前已配置地址做 /api/v1/healthz 快速轮询，直到服务端开始应答。
 *
 * 为什么存在：ADR-0015 单机形态（服务端内嵌手机本机）冷启动存在秒级「未就绪窗口」——
 * exec 起进程 → SQLite 迁移 → ffmpeg 自检 → 才开始监听（server/cmd/qimeng/main.go 启动序）。
 * 此前 App 侧没有就绪探活，首屏业务请求必然失败后靠固定 1.5s 间隔重试去撞窗口：
 * 服务端 200ms 就绪了，UI 也要空白干等满 1.5s（2026-09-18 用户反馈「本地登录首页过一会
 * 才显示内容」的根因之一）。探针把「等服务端」前移到 300ms 粒度的健康检查，业务请求
 * 发出时服务端大概率已就绪；探针超时则原样返回 false，调用方走既有失败/重试路径。
 *
 * 并发语义：多调用方并发 [awaitReady] 单飞共享同一次探测（首页与缩略图预取同时启动
 * 只跑一条轮询循环）；就绪结论在 [READY_FRESH_MS] 短窗内直接复用，不重复打探针。
 *
 * 为什么用 System.nanoTime 而非 SystemClock：本模块保持纯 JVM 可测（既有 core/network
 * 单测不带 Robolectric），nanoTime 单调时钟语义对「等了多久」的判定足够。
 */
@Singleton
class ServerReadinessProbe @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    private val authApiFactory: AuthApiFactory,
    @ApplicationScope private val scope: kotlinx.coroutines.CoroutineScope,
) {

    private val mutex = Mutex()

    /** 进行中的共享探测；null = 当前没有探测在跑（已完成或从未开始）。 */
    private var inFlight: Deferred<Boolean>? = null

    /** 最近一次「就绪」判定时刻（nanoTime 毫秒）；短窗内重复调用直接复用结论。 */
    @Volatile
    private var lastReadyAtElapsedMs: Long = NOT_READY_SENTINEL

    /**
     * 等待当前已配置的服务端就绪。
     *
     * @param budgetMs 等待预算（毫秒）；并发共享时以首个发起方为准（调用方都是启动期场景，取默认即可）
     * @return true = healthz 已通；false = 预算内始终未就绪（未配置地址/服务端确实不可用）——
     *   调用方应照常发起业务请求，由既有失败路径给出用户可见反馈
     */
    suspend fun awaitReady(budgetMs: Long = DEFAULT_BUDGET_MS): Boolean {
        // 哨兵前置判定不可省：Long.MIN_VALUE 参与减法会回绕成负数恒小于短窗，
        // 首调将「未经探测直接就绪」（executor A 真机自测抓到的溢出 bug，勿回退）
        if (lastReadyAtElapsedMs != NOT_READY_SENTINEL &&
            nowElapsedMs() - lastReadyAtElapsedMs < READY_FRESH_MS
        ) return true
        val deferred = mutex.withLock {
            inFlight?.takeIf { it.isActive }
                ?: scope.async { pollUntilReady(budgetMs) }.also { inFlight = it }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // pollUntilReady 按设计不抛（逐类消化）；此处兜底保证「探测异常 = 未就绪」而不是崩调用方
            false
        } finally {
            mutex.withLock {
                if (inFlight === deferred && deferred.isCompleted) inFlight = null
            }
        }
    }

    private suspend fun pollUntilReady(budgetMs: Long): Boolean {
        // 探测期间不重读地址：awaitReady 覆盖的是「登录后 → 服务端起来」的短窗口，
        // 用户中途切地址是显式登出流（壳层切登录页），探测随调用方协程取消
        val baseUrl = serverConfig.currentServerUrl() ?: return false
        val api = authApiFactory.create(baseUrl)
        val deadline = nowElapsedMs() + budgetMs
        while (nowElapsedMs() < deadline) {
            val reachable = try {
                api.probe()
                lastReadyAtElapsedMs = nowElapsedMs()
                true
            } catch (e: ClientException) {
                // 探针端点免鉴权且恒 200（openapi /api/v1/healthz 语义），4xx 说明对端
                // 不是绮梦服务端——再等也不会「就绪」，提前终止不白耗预算
                return false
            } catch (e: ServerException) {
                // 5xx：地址对但服务端自身故障，按未就绪继续等（起机瞬间的暂态可自愈）
                false
            } catch (e: IOException) {
                // 连接拒绝/不可达 = 还没监听起来，正常等待窗口内的预期状态
                false
            }
            if (reachable) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun nowElapsedMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        /** 就绪结论短窗（毫秒）：窗口内的重复 awaitReady 直接复用，避免多调用方重复打探针。 */
        const val READY_FRESH_MS = 5_000L

        /** 「从未就绪」哨兵：不可参与减法运算（Long 回绕），只作相等判定。 */
        const val NOT_READY_SENTINEL = Long.MIN_VALUE

        /** 轮询间隔（毫秒）：内嵌服务端秒级就绪（exec+迁移+自检，HomeViewModel 既有注释口径），300ms 粒度贴合窗口又不刷爆回环。 */
        const val POLL_INTERVAL_MS = 300L

        /** 默认等待预算（毫秒）：覆盖最慢的冷启动（低端机首启 ffmpeg 自检偏慢），到点放弃交还调用方失败路径。 */
        const val DEFAULT_BUDGET_MS = 10_000L
    }
}
