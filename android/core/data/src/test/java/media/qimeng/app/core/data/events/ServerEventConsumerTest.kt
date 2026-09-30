package media.qimeng.app.core.data.events

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import dagger.Lazy
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.LoginResult
import media.qimeng.app.core.network.DefaultEndpoint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端 SSE 事件消费器单测（ADR-0029 客户端半，2026-10-01）。两组：
 * ① 帧解析——用 okhttp-sse 官方 [EventSources.processResponse] 直驱真实
 *    ServerSentEventReader 读 fabricated text/event-stream 响应（MockWebServer 不在测试
 *    依赖里，官方 processResponse 入口即为此形态的注入缝），锁三类接线事件的分发与
 *    hello/未知事件的忽略；
 * ② 连接生命周期——注入 fake EventSource.Factory 捕获每次连接尝试，锁「登录即连/
 *    事件 bump/流终结退避重连（3s 起指数爬升 30s 封顶）/连接成功重置/登出即断不再
 *    重连/地址非法循环退出不空转」。协程虚拟时间推进全确定。
 */
class ServerEventConsumerTest {

    /** 退避初值/封顶（与被测具名常量同值；测试文件字面量豁免但仍具名防漂移） */
    private companion object {
        const val BACKOFF_INITIAL_MS = 3_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val SERVER_URL = "http://nas.example"
    }

    // ---------- 测试替身 ----------

    /** [AuthRepository] 最小替身：只驱动 isLoggedIn/serverUrl 两个流（SSE 消费器仅消费这两者）。 */
    private class FakeAuthRepositoryForSse(
        initialLoggedIn: Boolean = false,
        initialServerUrl: String = SERVER_URL,
    ) : AuthRepository {
        val loggedInState = MutableStateFlow(initialLoggedIn)
        val serverUrlState = MutableStateFlow(initialServerUrl)

        override val serverUrl: Flow<String> = serverUrlState
        override val rememberedNasUrl: Flow<String> = MutableStateFlow("")
        override val rememberedLocalUrl: Flow<String> = MutableStateFlow("")
        override val defaultEndpoint: Flow<DefaultEndpoint?> = MutableStateFlow(null)
        override val isLoggedIn: Flow<Boolean> = loggedInState
        override val unauthorizedEvents: Flow<Unit> = MutableSharedFlow()

        override suspend fun setDefaultEndpoint(endpoint: DefaultEndpoint?) = Unit
        override suspend fun login(rawAddress: String, password: String): LoginResult = LoginResult.Success
        override suspend fun logout() = Unit
        override suspend fun logoutWithStagedUrl(url: String) = Unit
    }

    /** fake 连接：只记录 cancel 次数（断言「登出即断」）。 */
    private class FakeEventSource(private val request: Request) : EventSource {
        var cancelCount = 0
        override fun request(): Request = request
        override fun cancel() {
            cancelCount++
        }
    }

    /** fake 工厂：捕获每次连接尝试的请求与监听器，由测试驱动回调（替代真实网络）。 */
    private class FakeEventSourceFactory : EventSource.Factory {

        inner class Attempt(val request: Request, val listener: EventSourceListener) {
            val source = FakeEventSource(request)
        }

        val attempts = mutableListOf<Attempt>()

        override fun newEventSource(request: Request, listener: EventSourceListener): EventSource =
            Attempt(request, listener).also { attempts += it }.source
    }

    /** 统一构造被测消费者（appScope 挂共享 testScheduler 的 Unconfined 调度器，虚拟时间全确定）。 */
    private fun consumer(
        auth: FakeAuthRepositoryForSse,
        factory: FakeEventSourceFactory,
        signal: DataFreshnessSignal,
        scheduler: TestCoroutineScheduler,
    ): ServerEventConsumer = ServerEventConsumer(
        authRepository = auth,
        eventSourceFactory = Lazy<EventSource.Factory> { factory },
        freshnessSignal = signal,
        appScope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + SupervisorJob()),
    )

    /** 服务端响应替身（onOpen/onFailure 回调参数；本消费器不读其内容）。 */
    private fun acceptedResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

    /** 组装 SSE 帧文本（每事件以空行终结，WHATWG 语法；空行不能带空白字符）。 */
    private fun sseFrames(vararg events: List<String>): String =
        events.joinToString(separator = "\n\n", postfix = "\n\n") { it.joinToString("\n") }

    // ---------- ① 帧解析（真实 ServerSentEventReader 直驱） ----------

    @Test
    fun `SSE帧解析 - 三类接线事件计数 hello与未接线事件忽略 onOpen三计数bump`() = runTest {
        val signal = DataFreshnessSignal()
        val consumer = consumer(FakeAuthRepositoryForSse(), FakeEventSourceFactory(), signal, testScheduler)
        val opened = CompletableDeferred<Boolean>()
        val closed = CompletableDeferred<Unit>()

        val frames = sseFrames(
            listOf("event: hello", "data: {\"version\":\"dev\"}", "id: 0"),
            listOf("event: library.changed", "data: {}", "id: 1"),
            listOf("event: favorite.changed", "data: {\"assetId\":\"a1\"}", "id: 2"),
            listOf("event: scan.progress", "data: {}", "id: 3"),
            listOf("event: like.changed", "data: {\"assetId\":\"a2\"}", "id: 4"),
        )
        val response = Response.Builder()
            .request(Request.Builder().url("$SERVER_URL/api/v1/events").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(frames.toResponseBody("text/event-stream".toMediaType()))
            .build()

        // 官方 processResponse 入口：同步读尽 body（EOF onClosed），驱动完整监听器链路
        EventSources.processResponse(response, consumer.streamListener(opened, closed))

        // onOpen 一次（三计数 bump）+ library/favorite/like 各一条；hello 与 scan.progress 忽略
        assertEquals(FreshnessSnapshot(library = 2, favorite = 2, like = 2), signal.snapshot())
        assertTrue(opened.await()) // 连接建立半边 = true
        assertTrue(closed.isCompleted) // EOF 走 onClosed（非失败半边）
    }

    // ---------- ② 连接生命周期（fake 工厂 + 虚拟时间） ----------

    @Test
    fun `生命周期 - 登录即连 事件bump 流终结退避重连 登出即断不再重连`() = runTest {
        val auth = FakeAuthRepositoryForSse()
        val factory = FakeEventSourceFactory()
        val signal = DataFreshnessSignal()
        val consumer = consumer(auth, factory, signal, testScheduler)

        consumer.onAppCreate()
        advanceUntilIdle()
        assertEquals(0, factory.attempts.size) // 未登录不连

        auth.loggedInState.value = true // 登录 → 立即连
        advanceUntilIdle()
        assertEquals(1, factory.attempts.size)
        assertEquals("$SERVER_URL/api/v1/events", factory.attempts[0].request.url.toString())

        // 连接建立：onOpen 三计数 bump（含首次连接）
        val a0 = factory.attempts[0]
        a0.listener.onOpen(a0.source, acceptedResponse(a0.request))
        assertEquals(FreshnessSnapshot(library = 1, favorite = 1, like = 1), signal.snapshot())

        // 事件到达：like.changed bump like 计数
        a0.listener.onEvent(a0.source, id = "1", type = "like.changed", data = "{\"assetId\":\"x\"}")
        assertEquals(FreshnessSnapshot(library = 1, favorite = 1, like = 2), signal.snapshot())

        // 流终结（服务端关闭）：初值 3s 退避后重连
        a0.listener.onClosed(a0.source)
        advanceTimeBy(BACKOFF_INITIAL_MS - 1)
        assertEquals(1, factory.attempts.size) // 退避窗口内不重连（防风暴）
        advanceUntilIdle()
        assertEquals(2, factory.attempts.size) // 越过 3s 重连

        // 登出：连接取消 + 循环停止，之后不再有任何重连
        auth.loggedInState.value = false
        advanceUntilIdle()
        assertEquals(1, factory.attempts[1].source.cancelCount)
        val attemptsAtLogout = factory.attempts.size
        advanceTimeBy(10 * 60_000L)
        advanceUntilIdle()
        assertEquals(attemptsAtLogout, factory.attempts.size)
    }

    @Test
    fun `生命周期 - 连续未建立指数退避3s起2x爬升30s封顶 连接成功重置回3s`() = runTest {
        val auth = FakeAuthRepositoryForSse(initialLoggedIn = true)
        val factory = FakeEventSourceFactory()
        val signal = DataFreshnessSignal()
        val consumer = consumer(auth, factory, signal, testScheduler)

        consumer.onAppCreate()
        advanceUntilIdle()
        assertEquals(1, factory.attempts.size)

        // a0 失败 → 3s 后 a1
        factory.attempts[0].listener.onFailure(factory.attempts[0].source, IOException("503"), null)
        advanceTimeBy(BACKOFF_INITIAL_MS - 1)
        assertEquals(1, factory.attempts.size)
        advanceUntilIdle()
        assertEquals(2, factory.attempts.size)

        // a1 失败 → 6s；a2 失败 → 12s；a3 失败 → 24s
        listOf(1, 2, 3).forEachIndexed { idx, attemptIdx ->
            val backoff = BACKOFF_INITIAL_MS shl (idx + 1) // 6s/12s/24s
            val attempt = factory.attempts[attemptIdx]
            attempt.listener.onFailure(attempt.source, IOException("flap"), null)
            advanceTimeBy(backoff - 1)
            assertEquals(attemptIdx + 1, factory.attempts.size) // 退避窗口内不重连
            advanceUntilIdle()
            assertEquals(attemptIdx + 2, factory.attempts.size)
        }

        // a4 失败 → 封顶 30s（2x=48s 超上限钳制）
        factory.attempts[4].listener.onFailure(factory.attempts[4].source, IOException("down"), null)
        advanceTimeBy(BACKOFF_MAX_MS - 1)
        assertEquals(5, factory.attempts.size)
        advanceUntilIdle()
        assertEquals(6, factory.attempts.size)

        // a5 连接成功后流终结：退避重置回初值 3s
        val a5 = factory.attempts[5]
        a5.listener.onOpen(a5.source, acceptedResponse(a5.request))
        a5.listener.onClosed(a5.source)
        advanceTimeBy(BACKOFF_INITIAL_MS - 1)
        assertEquals(6, factory.attempts.size)
        advanceUntilIdle()
        assertEquals(7, factory.attempts.size)
    }

    @Test
    fun `生命周期 - 地址未配置循环退出不空转 下次登录重挂`() = runTest {
        val auth = FakeAuthRepositoryForSse(initialServerUrl = "")
        val factory = FakeEventSourceFactory()
        val signal = DataFreshnessSignal()
        val consumer = consumer(auth, factory, signal, testScheduler)

        consumer.onAppCreate()
        advanceUntilIdle()

        // 已登录但地址从未配置（异常态）：连接循环构造请求失败即退出，不退避空转
        auth.loggedInState.value = true
        advanceUntilIdle()
        assertEquals(0, factory.attempts.size)
        advanceTimeBy(10 * 60_000L)
        advanceUntilIdle()
        assertEquals(0, factory.attempts.size)

        // 登出→登录翻转重挂观察：地址就绪后正常连接
        auth.loggedInState.value = false
        advanceUntilIdle()
        auth.serverUrlState.value = SERVER_URL
        auth.loggedInState.value = true
        advanceUntilIdle()
        assertEquals(1, factory.attempts.size)
        assertEquals("$SERVER_URL/api/v1/events", factory.attempts[0].request.url.toString())
    }
}
