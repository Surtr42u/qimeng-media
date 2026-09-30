package media.qimeng.app.core.data.events

import android.util.Log
import dagger.Lazy
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.network.di.ApplicationScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/** SSE 消费日志 tag（logcat 文本证据协议；挂在 core:data events 包名下） */
private const val LOG_TAG = "QimengSse"

/**
 * 服务端 SSE 事件消费器（ADR-0029 客户端半，2026-10-01）：登录期间与 `GET /api/v1/events`
 * 保持一条长流，把三类变更事件喂进 [DataFreshnessSignal] 信号汇——收藏/点赞跳过门的
 * 统一新鲜度信号源（跨端改动从「至多 5 分钟 TTL 陈旧」收敛到秒级感知）。
 *
 * 连接生命周期（接线范式 = ThumbnailPrefetcher.onAppCreate/CachePoolBinder 同款）：
 * QimengApplication.onCreate 调 [onAppCreate] 观察 [AuthRepository.isLoggedIn]——
 * 登录即连、登出即断（取消连接与重连循环）；断线指数退避重连（[RECONNECT_BACKOFF_INITIAL_MS]
 * 起、×2 爬升、[RECONNECT_BACKOFF_MAX_MS] 封顶，具名常量），连接成功即重置退避——失败
 * 方向是「晚点再连」而非风暴，永不向上抛错误（SSE 是尽力而为通道，断线窗口由 TTL 兜底，
 * ADR-0029「事件丢失窗口=回到 TTL 兜底」口径）。
 *
 * 后台不断开（有意取舍）：进程退后台不取消连接，ROM 杀进程由既有事实接受（ADR-0026
 * 「国产 ROM 进程存活不可靠」同口径）——进程死了连接随之消失，重登录/杀后重启经
 * [onAppCreate] 重新挂观察自愈；为后台保活引入 ForegroundService 反而违背「尽力而为
 * 信号 + TTL 兜底」的分层（信号断了最多多陈旧一个 TTL，代价有界）。
 *
 * 事件分发：`library.changed`/`favorite.changed`/`like.changed` 分别 bump 对应计数器
 * （映射见 [ServerEventTopics]）；`hello` 首帧与未知事件按 SSE 规范忽略（对未接线的
 * 事件零害，未来服务端加新事件本类零改动）。载荷（{"assetId"}）是变更信号不是状态面，
 * 不解析内容只计数（ADR-0029 轻载荷取舍）。
 */
@Singleton
class ServerEventConsumer @Inject constructor(
    private val authRepository: AuthRepository,
    private val eventSourceFactory: Lazy<EventSource.Factory>,
    private val freshnessSignal: DataFreshnessSignal,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /**
     * 事件源工厂经 [Lazy] 注入（dagger.Lazy，CachePoolBinder→SplitDiskCache 同款拍板）：
     * 未登录会话里首次构造被推迟到真正登录连流时，避免无关启动路径背上工厂装配成本。
     */
    private val transitionMutex = Mutex()

    /** 进行中的连接循环；null = 空闲。单飞语义：同一时刻至多一条循环（一条循环内单连接）。 */
    private var connectionJob: Job? = null

    /** 当前活跃连接（循环内赋值；循环出口 finally 兜底 cancel，登出线程与 OkHttp 线程跨线程访问） */
    private val activeSource = AtomicReference<EventSource?>(null)

    /** App 启动接线（QimengApplication.onCreate 调一次）：观察登录态驱动连接起停 */
    fun onAppCreate() {
        appScope.launch {
            authRepository.isLoggedIn.collect { loggedIn ->
                transitionMutex.withLock {
                    if (loggedIn) startConnectionLocked() else stopConnectionLocked()
                }
            }
        }
    }

    /** 持锁调用。单飞：已有一条循环在跑则复用不叠加（ThumbnailPrefetcher.startRoundLocked 同款裁量）。 */
    private fun startConnectionLocked() {
        if (connectionJob?.isActive == true) return
        connectionJob = appScope.launch { runConnectionLoop() }
    }

    /** 持锁调用。取消循环即取消连接（cancel 经循环 finally 收口），全部状态单点管理。 */
    private fun stopConnectionLocked() {
        connectionJob?.cancel()
        connectionJob = null
    }

    /**
     * 连接 + 退避重连主循环：每次尝试建一条 EventSource，onOpen 后等流终结（服务端关闭/
     * 网络错误/登出取消）；流终结或未建立连接统一退避后重连，连接成功重置退避到初值。
     * 登出经 Job 取消穿透所有挂起点，循环自然退出。
     */
    private suspend fun runConnectionLoop() {
        var backoffMs = RECONNECT_BACKOFF_INITIAL_MS
        while (coroutineContext.isActive) {
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Unit>()
            val source = try {
                eventSourceFactory.get().newEventSource(connectRequest(), streamListener(opened, closed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 请求构造失败（地址未配置/非法等）：登出态或异常配置，退出循环等下次登录重挂，
                // 不带退避空转——重试风暴红线（登录态流转 false→true 会重新拉起本循环）
                Log.w(LOG_TAG, "SSE 连接请求构造失败，停止本轮消费循环（下次登录自愈）", e)
                return
            }
            activeSource.set(source)
            try {
                when (opened.await()) {
                    true -> {
                        // 连接成功：退避复位（健康链路恢复秒级重连能力），等流终结（终结本身
                        // 不区分正常关闭与网络错误——两者都走「退避后重连」，失败方向弱）
                        backoffMs = RECONNECT_BACKOFF_INITIAL_MS
                        Log.i(LOG_TAG, "SSE 已连接 ${source.request().url}")
                        closed.await()
                        Log.i(LOG_TAG, "SSE 流终结")
                    }
                    false -> {
                        // 未-open 即失败（401/403/非 event-stream 响应等）。401 会同时经
                        // AuthInterceptor 清 token 广播登出，isLoggedIn 翻 false 取消本循环——
                        // 此处退避只兜「服务端瞬态拒绝」
                        Log.w(LOG_TAG, "SSE 连接未建立")
                    }
                }
            } finally {
                // 取消（登出）或循环体任何出口都释放底层连接；cancel 幂等
                activeSource.compareAndSet(source, null)
                source.cancel()
            }
            if (!coroutineContext.isActive) break
            // 流终结/连接失败统一退避后重连。为什么开过的流终结也要退避：okhttp-sse 忽略
            // 服务端 retry 提示帧（RealEventSource.onRetryChange 空实现，官方 sources jar
            // 实读确认），重连节奏全靠客户端自带——服务端「接了就断」的抖动若无退避会
            // 空转成风暴（重试风暴红线）
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
        }
    }

    /**
     * 连接请求：地址取 [AuthRepository.serverUrl]（ServerConfigDataSource 单点流转，ADR-0015，
     * 与业务 API 同源），路径 [SSE_EVENTS_PATH]（openapi.yaml /api/v1/events，协议侧改动须
     * 同步此处）；Authorization 头**不在本处拼接**——@SseClient 客户端共享 AuthInterceptor
     * （NetworkModule KDoc），token 读取逻辑全 App 只有拦截器一份（禁止第二份的纪律红线）。
     */
    private suspend fun connectRequest(): Request {
        val baseUrl = authRepository.serverUrl.first()
        val url = baseUrl.toHttpUrlOrNull()?.resolve(SSE_EVENTS_PATH)
            ?: error("SSE 连接地址不可用（未登录或地址非法）：$baseUrl")
        return Request.Builder().url(url).build()
    }

    /**
     * 流监听器：桥接 okhttp-sse 回调到协程原语与信号汇。internal 可见性 = 本模块单测用
     * okhttp-sse 官方 EventSources.processResponse 直驱真实 ServerSentEventReader 帧解析的
     * 注入缝（生产恒经 [okhttp3.sse.EventSource.Factory] 挂接，见 [onAppCreate] 接线）。
     */
    internal fun streamListener(opened: CompletableDeferred<Boolean>, closed: CompletableDeferred<Unit>): EventSourceListener =
        object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                opened.complete(true)
                // 对齐 Web 端 onOpen 语义（第四百一十六笔）：首次连接与每次重连成功都触发
                // 全量重新校验——断线窗口错过的事件以「门整体失效一次」补偿
                freshnessSignal.onStreamOpened()
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    ServerEventTopics.LIBRARY_CHANGED -> freshnessSignal.onLibraryChanged()
                    ServerEventTopics.FAVORITE_CHANGED -> freshnessSignal.onFavoriteChanged()
                    ServerEventTopics.LIKE_CHANGED -> freshnessSignal.onLikeChanged()
                    // hello 首帧与本门未接线的事件（scan/thumbnail/upload 族）：忽略零害
                }
            }

            override fun onClosed(eventSource: EventSource) {
                closed.complete(Unit)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!opened.isCompleted) opened.complete(false)
                closed.complete(Unit)
            }
        }

    private companion object {
        /**
         * SSE 端点路径（openapi.yaml /api/v1/events；GUIDE_API「实时推送」行同源）。与协议
         * 联动的字符串：协议侧路径改动须同步此处，反之亦然（代码卫生约束 3）。
         */
        const val SSE_EVENTS_PATH = "/api/v1/events"

        /**
         * 重连退避初值（毫秒）：与服务端 SSE 首帧 retry 提示同量级（events.DefaultRetryMS
         * =3s，双向同步责任记档）——服务端重启窗口秒级，3s 起步让常见瞬态一次重连即恢复。
         */
        const val RECONNECT_BACKOFF_INITIAL_MS = 3_000L

        /**
         * 重连退避封顶（毫秒）：服务端长时间下线时不把空转重连刷成风暴（30s 一次的心跳级
         * 尝试对「尽力而为信号」足够；服务端恢复后至多 30s 内回连）。
         */
        const val RECONNECT_BACKOFF_MAX_MS = 30_000L
    }
}
