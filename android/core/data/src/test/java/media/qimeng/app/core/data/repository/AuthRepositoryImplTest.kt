package media.qimeng.app.core.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.network.SdkAuthApiFactory
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.SessionEventBus
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import okio.Buffer

/**
 * AuthRepository 全链路单测：真 SDK（DefaultApi + moshi 真实序列化/反序列化与错误分类）+
 * fake 传输层拦截器——连「探活必须走协议面路径 /api/v1/healthz」都被路由记录锁定（打错路径必 404→ServerUnreachable）。
 *
 * 服务器实现说明：原稿用 JDK 内置 com.sun.net.httpserver，但 Android 库 unit test 编译类路径
 * 只有 android.jar（不含 com.sun.*）。改为在 OkHttpClient 上挂 fake 传输层拦截器（OkHttp 官方
 * 测试范式）：真实走 DefaultApi/moshi/错误分类，仅 socket 层被合成响应替代，用例语义等价。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryImplTest {

    /** 正确密码（fake 服务端 handler 与测试断言共用）。 */
    private val correctPassword = "test-password-001"

    /** healthz 响应状态（默认 200；个别用例改 404 模拟「对端不是绮梦服务端」）。 */
    private var healthzStatus = 200

    /** dev-login 免密通道开关（模拟服务端 auth_dev_mode；默认开=测试/开发环境口径）。 */
    private var devLoginEnabled = true

    /** 各路径命中记录（断言登录端点未被触达等）。 */
    private val hitCounts = mutableMapOf<String, Int>()

    private val serverConfig = InMemoryServerConfig()

    private val repository = AuthRepositoryImpl(
        serverConfig = serverConfig,
        authApiFactory = SdkAuthApiFactory(fakeTransportClient()),
        sessionEventBus = SessionEventBus(),
    )

    @Before
    fun setUp() {
        healthzStatus = 200
        devLoginEnabled = true
        hitCounts.clear()
    }

    @After
    fun tearDown() {
        // 无共享外设需关闭（fake 传输层无 socket）；token 状态由各用例自足管理（原稿同口径）
    }

    /**
     * fake 服务端路由（按协议面路径分派）：
     * 只注册协议面路径——探活若打根路径 /healthz（运维别名）会 404，冻结口径被路由表本身锁定。
     */
    private fun fakeTransportClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                hitCounts[path] = (hitCounts[path] ?: 0) + 1
                when (path) {
                    "/api/v1/healthz" -> testResponse(chain, healthzStatus, body = "alive")
                    "/api/v1/auth/login" -> {
                        val buffer = Buffer()
                        request.body!!.writeTo(buffer)
                        val password = Regex("\"password\"\\s*:\\s*\"([^\"]*)\"")
                            .find(buffer.readUtf8())?.groupValues?.get(1)
                        if (password == correctPassword) {
                            testResponse(chain, 200, body = """{"token":"minted-token-1"}""")
                        } else {
                            testResponse(chain, 401, body = """{"code":"UNAUTHORIZED","message":"bad password"}""")
                        }
                    }

                    // 免密通道：开启时签发 token；关闭时恒 404 不泄露信息（对齐 server auth_dev_test 语义）
                    "/api/v1/auth/dev-login" -> {
                        if (devLoginEnabled) {
                            testResponse(chain, 200, body = """{"token":"dev-minted-token"}""")
                        } else {
                            testResponse(chain, 404, body = """{"code":"NOT_FOUND"}""")
                        }
                    }

                    // 登出吊销（ADR-0021 多会话）：幂等 204——走到端点即成功
                    "/api/v1/auth/logout" -> testResponse(chain, 204, body = "")

                    else -> testResponse(chain, 404, body = """{"code":"NOT_FOUND"}""")
                }
            },
        )
        .build()

    @Test
    fun `登录成功_探活后登录取token并持久化地址`() = runTest {
        val result = repository.login(FAKE_BASE_URL, correctPassword)

        assertEquals(LoginResult.Success, result)
        assertEquals(FAKE_BASE_URL, serverConfig.serverUrl.first())
        assertEquals("minted-token-1", serverConfig.token.first())
        assertTrue(repository.isLoggedIn.first())
    }

    @Test
    fun `密码错误_返回WrongPassword且不落盘`() = runTest {
        val result = repository.login(FAKE_BASE_URL, "wrong-password")

        assertEquals(LoginResult.Failure(LoginError.WrongPassword), result)
        assertFalse(repository.isLoggedIn.first())
        assertEquals(null, serverConfig.token.first())
    }

    @Test
    fun `空密码_走dev-login免密登录成功且不触密码端点`() = runTest {
        val result = repository.login(FAKE_BASE_URL, "")

        assertEquals(LoginResult.Success, result)
        assertEquals("dev-minted-token", serverConfig.token.first())
        assertEquals(FAKE_BASE_URL, serverConfig.serverUrl.first())
        assertEquals(1, hitCounts["/api/v1/auth/dev-login"] ?: 0)
        assertEquals(0, hitCounts["/api/v1/auth/login"] ?: 0)
    }

    @Test
    fun `空密码_dev模式未开启_404返回DevLoginUnavailable不落盘`() = runTest {
        devLoginEnabled = false

        val result = repository.login(FAKE_BASE_URL, "")

        assertEquals(LoginResult.Failure(LoginError.DevLoginUnavailable), result)
        assertFalse(repository.isLoggedIn.first())
        assertEquals(null, serverConfig.token.first())
    }

    @Test
    fun `地址不通_探活IOException返回ServerUnreachable`() = runTest {
        // 取一个空闲端口后立即关闭：连接被内核拒绝 → OkHttp 抛 IOException（不带 fake，走真实连接）
        val deadPort = ServerSocket(0).let { server -> val p = server.localPort; server.close(); p }
        val unreachableRepository = AuthRepositoryImpl(
            serverConfig = serverConfig,
            authApiFactory = SdkAuthApiFactory(OkHttpClient()),
            sessionEventBus = SessionEventBus(),
        )

        val result = unreachableRepository.login("http://127.0.0.1:$deadPort", correctPassword)

        assertEquals(LoginResult.Failure(LoginError.ServerUnreachable), result)
    }

    @Test
    fun `对端不是绮梦服务端_探活4xx返回ServerUnreachable且不试登录`() = runTest {
        healthzStatus = 404

        val result = repository.login(FAKE_BASE_URL, correctPassword)

        assertEquals(LoginResult.Failure(LoginError.ServerUnreachable), result)
        assertEquals(0, hitCounts["/api/v1/auth/login"] ?: 0)
    }

    @Test
    fun `服务端内部故障_探活5xx返回ServerUnreachable且不试登录`() = runTest {
        // P0 回归锁：SDK 对 5xx 抛 ServerException（非 IOException 子类），此前未捕获会
        // 透传到 viewModelScope 崩溃——现在必须收敛为 ServerUnreachable
        healthzStatus = 500

        val result = repository.login(FAKE_BASE_URL, correctPassword)

        assertEquals(LoginResult.Failure(LoginError.ServerUnreachable), result)
        assertEquals(0, hitCounts["/api/v1/auth/login"] ?: 0)
    }

    @Test
    fun `非法地址_不发任何请求`() = runTest {
        val result = repository.login("not a url", correctPassword)

        assertEquals(LoginResult.Failure(LoginError.InvalidAddress), result)
        assertTrue(hitCounts.isEmpty())
    }

    @Test
    fun `裸地址自动补协议后可登录`() = runTest {
        val bareAddress = "127.0.0.1:1" // fake 传输层不真正连接，端口合法即可；断言按规范化结果

        val result = repository.login(bareAddress, correctPassword)

        assertEquals(LoginResult.Success, result)
        assertEquals("http://$bareAddress", serverConfig.serverUrl.first())
    }

    @Test
    fun `退出登录_清token保留地址`() = runTest {
        repository.login(FAKE_BASE_URL, correctPassword)

        repository.logout()

        assertEquals(null, serverConfig.token.first())
        assertFalse(repository.isLoggedIn.first())
        // 「记忆上次」：地址保留给登录页回填
        assertEquals(FAKE_BASE_URL, serverConfig.serverUrl.first())
        // P1a：清 token 前对当前地址吊销了本设备会话（ADR-0021 多会话）
        assertEquals(1, hitCounts["/api/v1/auth/logout"] ?: 0)
    }

    @Test
    fun `未登录登出_无会话可吊销不发请求也不崩`() = runTest {
        repository.logout()

        assertEquals(null, serverConfig.token.first())
        assertTrue(hitCounts.isEmpty())
    }

    @Test
    fun `登出并预置地址_token清空且serverUrl为预置值`() = runTest {
        repository.login(FAKE_BASE_URL, correctPassword)

        repository.logoutWithStagedUrl(ServerAddress.LOCAL_MODE_PRESET)

        // 对外契约（任务T T3 本机模式快捷入口）：token 清空（壳层切登录页）+
        // serverUrl 精确等于预置地址（登录页「记忆上次」回填读到的就是它）
        assertEquals(null, serverConfig.token.first())
        assertFalse(repository.isLoggedIn.first())
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, serverConfig.serverUrl.first())
        // P1a：吊销发生在切地址之前（用的是旧地址上的旧会话 token）
        assertEquals(1, hitCounts["/api/v1/auth/logout"] ?: 0)
    }

    private companion object {
        /** fake 传输层不出网，URL 仅须合法（AuthApi 按此拼协议面路径；路由按 path 分派不关心端口） */
        const val FAKE_BASE_URL = "http://127.0.0.1:1"

        /** 合成 OkHttp 响应的最小构造（拦截器测试标准写法：request/protocol/code/message/body 必填） */
        fun testResponse(chain: Interceptor.Chain, status: Int, body: String): Response = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    /** :core:data 测试侧内存替身（与 :core:network 的 Fake 语义一致，测试代码不跨模块共享源码）。 */
    private class InMemoryServerConfig : ServerConfigDataSource {
        private val url = MutableStateFlow("")
        private val tokenState = MutableStateFlow<String?>(null)
        override val serverUrl: Flow<String> = url
        override val token: Flow<String?> = tokenState
        override fun currentToken(): String? = tokenState.value
        override fun currentServerUrl(): String? = url.value.ifEmpty { null }
        override suspend fun updateServerUrl(url: String) { this.url.value = url }
        override suspend fun updateToken(token: String) { tokenState.value = token }
        override suspend fun clearToken() { tokenState.value = null }
    }
}
