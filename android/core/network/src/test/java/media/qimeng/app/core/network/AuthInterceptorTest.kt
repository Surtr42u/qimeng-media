package media.qimeng.app.core.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * 401 事件流单测：真 OkHttpClient 拦截器链 + fake 传输层拦截器（零新测试依赖、零真实网络）。
 * 锁定四条行为——Bearer 注入、无 token 不注入、401 清 token+发事件、200 不触发。
 *
 * 服务器实现说明：原稿用 JDK 内置 com.sun.net.httpserver，但 Android 库 unit test 编译类路径
 * 只有 android.jar（不含 com.sun.*）。「AuthInterceptor 之后补一个 fake 传输层拦截器、直接合成
 * Response」是 OkHttp 官方测试范式——被测拦截器注入的 Authorization 头由 fake 记录，等价锁定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthInterceptorTest {

    /** fake 传输层记录到的 Authorization 头（被测拦截器注入后的结果；null = 未携带）。 */
    private val recordedAuthHeaders = mutableListOf<String?>()

    /** fake 传输层的响应状态码（默认 200，测试内按需改 401）。 */
    private var respondStatus = 200

    private val serverConfig = FakeServerConfigDataSource(initialToken = "token-abc")
    private val sessionEvents = SessionEventBus()

    @Before
    fun setUp() {
        recordedAuthHeaders.clear()
        respondStatus = 200
    }

    @After
    fun tearDown() {
        // 字段级 Fake 跨用例共享 token 内存态：清掉防止用例间串扰（clearToken 为 suspend，用 runBlocking）
        runBlocking { serverConfig.clearToken() }
    }

    /** fake 传输层：置于被测拦截器之后，记录其注入结果并按 respondStatus 合成响应（不出 OkHttp 进程）。 */
    private fun fakeTransport() = Interceptor { chain ->
        recordedAuthHeaders += chain.request().header(HEADER_AUTHORIZATION)
        testResponse(chain, respondStatus, body = "")
    }

    private fun newClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    serverConfig = serverConfig,
                    sessionEvents = sessionEvents,
                    // Unconfined 让 401 清 token 的 launch 在请求线程立即执行，断言无需等待
                    appScope = CoroutineScope(UnconfinedTestDispatcher()),
                ),
            )
            .addInterceptor(fakeTransport())
            .build()

    @Test
    fun `有 token 时请求携带 Bearer 头`() = runTest {
        newClient().newCall(Request.Builder().url(TEST_URL).build()).execute().use {}

        assertEquals(listOf<String?>("Bearer token-abc"), recordedAuthHeaders)
    }

    @Test
    fun `无 token 时不携带 Authorization 头`() = runTest {
        serverConfig.clearToken()

        newClient().newCall(Request.Builder().url(TEST_URL).build()).execute().use {}

        assertEquals(listOf<String?>(null), recordedAuthHeaders)
    }

    @Test
    fun `收到 401 时清 token 并发一次鉴权失效事件`() = runTest {
        var eventCount = 0
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sessionEvents.unauthorized.collect { eventCount++ }
        }
        respondStatus = HTTP_UNAUTHORIZED

        newClient().newCall(Request.Builder().url(TEST_URL).build()).execute().use {}

        collector.cancel()
        // 一次 401 恰好一次事件，token 同步被清（token 流语义由 DataStore 实现测试锁定）
        assertEquals(1, eventCount)
        assertNull(serverConfig.currentToken())
    }

    @Test
    fun `收到 200 时不清 token 不发事件`() = runTest {
        var eventCount = 0
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sessionEvents.unauthorized.collect { eventCount++ }
        }

        newClient().newCall(Request.Builder().url(TEST_URL).build()).execute().use {}

        collector.cancel()
        assertEquals(0, eventCount)
        assertEquals("token-abc", serverConfig.currentToken())
    }

    @Test
    fun `无订阅者时 401 处理仍安全（事件溢出丢弃不抛异常，token 照清）`() = runTest {
        respondStatus = HTTP_UNAUTHORIZED

        newClient().newCall(Request.Builder().url(TEST_URL).build()).execute().use {}

        assertNull(serverConfig.currentToken())
    }

    @Test(expected = IOException::class)
    fun `服务端不可达时原样抛出 IOException（登录流程据此判地址不通）`() {
        // 取一个空闲端口后立即关闭：连接被内核拒绝 → OkHttp 抛 IOException（不挂 fake，走真实连接）
        val deadPort = ServerSocket(0).let { server -> val p = server.localPort; server.close(); p }

        OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(serverConfig, sessionEvents, CoroutineScope(UnconfinedTestDispatcher())),
            )
            .build()
            .newCall(Request.Builder().url("http://127.0.0.1:$deadPort/api/v1/healthz").build())
            .execute()
            .use {}
    }

    private companion object {
        /** 被测拦截器按协议名注入的鉴权头（与 AuthInterceptor 的私有常量同值，改动须两处同步） */
        const val HEADER_AUTHORIZATION = "Authorization"

        /** 401 状态码（与 AuthInterceptor 的私有常量同值，改动须两处同步） */
        const val HTTP_UNAUTHORIZED = 401

        /** fake 传输层场景下 URL 不出网（被拦截器截停）；仅须为合法 http(s) URL */
        const val TEST_URL = "http://127.0.0.1:1/api/v1/assets"

        /** 合成 OkHttp 响应的最小构造（拦截器测试标准写法：request/protocol/code/message/body 必填） */
        fun testResponse(chain: Interceptor.Chain, status: Int, body: String): Response = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()
    }
}
