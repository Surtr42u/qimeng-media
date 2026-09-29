package media.qimeng.app.core.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.data.embedded.EmbeddedServerController
import media.qimeng.app.core.data.embedded.LocalServerWarmup
import media.qimeng.app.core.network.DefaultEndpoint
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

    /** 各路径命中记录（断言登录端点未被触达等）。
     *  并发安全容器：拦截器跑在 OkHttp 线程、断言读在测试线程（跨线程基本功底，
     *  与下述登出用例的 flaky 真因相互独立）。 */
    private val hitCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 批S8 事件序记录（跨线程安全）：fake 预热件与 fake 传输拦截器共同追加，
     *  锁定「先拉起→等就绪→才发探活」的修复核心次序。 */
    private val eventLog = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val serverConfig = InMemoryServerConfig()

    private val fakeEmbeddedServerController = FakeEmbeddedServerController(eventLog)

    private val fakeLocalServerWarmup = FakeLocalServerWarmup(eventLog)

    private val repository = AuthRepositoryImpl(
        serverConfig = serverConfig,
        // 批A：工厂第二参=密钥供给源（内存槽），测试内传同一 InMemoryServerConfig（默认 null = 不带头）
        authApiFactory = SdkAuthApiFactory(fakeTransportClient(), serverConfig),
        sessionEventBus = SessionEventBus(),
        embeddedServerController = fakeEmbeddedServerController,
        localServerWarmup = fakeLocalServerWarmup,
    )

    @Before
    fun setUp() {
        healthzStatus = 200
        devLoginEnabled = true
        hitCounts.clear()
        eventLog.clear()
        fakeEmbeddedServerController.ensureCalls.clear()
        fakeLocalServerWarmup.nextResult = true
        fakeLocalServerWarmup.awaitCount = 0
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
                eventLog += "transport:$path"
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
    fun `登录成功_默认登录端同步为NAS`() = runTest {
        assertTrue(repository.login(FAKE_BASE_URL, correctPassword) is LoginResult.Success)
        // 第三百六十三笔：登录成功=实际选择了该端，默认登录选项跟随——设置页单选只预置
        // 下一次登录，登录后被实际选择覆盖（登录页预选与真实用法一致）
        assertEquals(DefaultEndpoint.NAS, serverConfig.defaultEndpoint.first())
    }

    @Test
    fun `本机登录成功_默认登录端同步为本机`() = runTest {
        assertTrue(repository.login(ServerAddress.LOCAL_MODE_PRESET, "") is LoginResult.Success)
        assertEquals(DefaultEndpoint.LOCAL, serverConfig.defaultEndpoint.first())
    }

    @Test
    fun `密码错误_返回WrongPassword且不落盘`() = runTest {
        val result = repository.login(FAKE_BASE_URL, "wrong-password")

        assertEquals(LoginResult.Failure(LoginError.WrongPassword), result)
        assertFalse(repository.isLoggedIn.first())
        assertEquals(null, serverConfig.token.first())
        // 批S3：登录失败不写地址记忆槽（记忆只跟成功登录走）
        assertEquals("", serverConfig.rememberedNasUrl.first())
        assertEquals("", serverConfig.rememberedLocalUrl.first())
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
            authApiFactory = SdkAuthApiFactory(OkHttpClient(), serverConfig),
            sessionEventBus = SessionEventBus(),
            embeddedServerController = fakeEmbeddedServerController,
            localServerWarmup = fakeLocalServerWarmup,
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

    /**
     * 登出族用例用 [runBlocking]（真时钟）而非 runTest（虚拟时钟）——flaky 真因（335 笔
     * 修正 332 笔的诊断）：runTest 在测试协程挂起等待「真线程上的 Retrofit/OkHttp 响应」
     * 时会自动推进虚拟时钟，把 [logoutRevokeTimeoutMs] 的 3s 虚拟超时瞬间烧掉，吊销请求
     * 被取消 → hitCounts 到不了 1；机器满载时 OkHttp 线程变慢则必输，空载则几乎必赢，
     * 与全部观测吻合（满载红/空载绿/两用例同源）。runBlocking 走真时钟 = 生产语义
     * （3s 真超时，fake 拦截器微秒级返回，确定性通过）。无虚拟时间依赖，其余用例不动。
     */
    @Test
    fun `退出登录_清token保留地址`() = runBlocking {
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
    fun `登出并预置地址_token清空且serverUrl为预置值`() = runBlocking {
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

    // ---------- 登录记忆分流（任务S 批S3 服务器地址固化） ----------

    @Test
    fun `登录成功按端型分流记忆_本机登录不覆盖NAS记忆`() = runBlocking {
        // 先成功登录「NAS」地址（FAKE_BASE_URL 端口非 18430 → 归 NAS 槽）
        assertEquals(LoginResult.Success, repository.login(FAKE_BASE_URL, correctPassword))
        assertEquals(FAKE_BASE_URL, serverConfig.rememberedNasUrl.first())

        // 再成功登录本机模式地址（dev-login 免密链路同为「成功登录」）
        assertEquals(LoginResult.Success, repository.login(ServerAddress.LOCAL_MODE_PRESET, ""))

        // 主地址键跟随当前端（切到本机=主键变 18430，既有语义不动）；NAS 记忆槽不被覆盖
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, serverConfig.serverUrl.first())
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, serverConfig.rememberedLocalUrl.first())
        assertEquals(FAKE_BASE_URL, serverConfig.rememberedNasUrl.first())
    }

    @Test
    fun `换址预置不写登录记忆`() = runBlocking {
        assertEquals(LoginResult.Success, repository.login(FAKE_BASE_URL, correctPassword))
        assertEquals(FAKE_BASE_URL, serverConfig.rememberedNasUrl.first())

        // logoutWithStagedUrl 是「预置下次登录带出值」，未经登录确认——不算成功登录，不动记忆槽
        repository.logoutWithStagedUrl("http://192.0.2.77")

        assertEquals("http://192.0.2.77", serverConfig.serverUrl.first())
        assertEquals(FAKE_BASE_URL, serverConfig.rememberedNasUrl.first())
        assertEquals("", serverConfig.rememberedLocalUrl.first())
    }

    // ---------- 登录前本机服务端预热（批S8 用户实测死锁修复） ----------

    @Test
    fun `本机模式登录_先拉起内嵌服务并等端口就绪再发探活`() = runTest {
        val result = repository.login(ServerAddress.LOCAL_MODE_PRESET, "")

        assertEquals(LoginResult.Success, result)
        assertEquals(listOf(ServerAddress.LOCAL_MODE_PRESET), fakeEmbeddedServerController.ensureCalls)
        assertEquals(1, fakeLocalServerWarmup.awaitCount)
        // 事件序铁证（死锁修复核心次序）：拉起 → 等就绪 → 才有探活/登录出网
        assertEquals(
            listOf("ensure", "warmup:await", "transport:/api/v1/healthz", "transport:/api/v1/auth/dev-login"),
            eventLog,
        )
    }

    @Test
    fun `远程NAS地址登录_不触发内嵌服务拉起与等待`() = runTest {
        val result = repository.login(FAKE_BASE_URL, correctPassword)

        assertEquals(LoginResult.Success, result)
        assertTrue(fakeEmbeddedServerController.ensureCalls.isEmpty())
        assertEquals(0, fakeLocalServerWarmup.awaitCount)
        // 预热零参与：事件序里只有探活+密码登录两笔传输
        assertEquals(
            listOf("transport:/api/v1/healthz", "transport:/api/v1/auth/login"),
            eventLog,
        )
    }

    @Test
    fun `本机模式登录_端口等待超时仍继续走既有登录链不造新错误`() = runTest {
        fakeLocalServerWarmup.nextResult = false // 模拟 5s 等待超时仍未就绪

        val result = repository.login(ServerAddress.LOCAL_MODE_PRESET, "")

        // 超时不是失败：继续发探活+登录（fake 服务端就绪故成功）；错误文案仍由既有链给出，
        // 本层绝不新增「等待超时」类错误（冻结口径）
        assertEquals(LoginResult.Success, result)
        assertEquals(1, fakeLocalServerWarmup.awaitCount)
        assertTrue(fakeEmbeddedServerController.ensureCalls.isNotEmpty())
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
        private val rememberedNas = MutableStateFlow("")
        private val rememberedLocal = MutableStateFlow("")
        private val defaultEndpointState = MutableStateFlow<DefaultEndpoint?>(null)
        override val serverUrl: Flow<String> = url
        override val rememberedNasUrl: Flow<String> = rememberedNas
        override val rememberedLocalUrl: Flow<String> = rememberedLocal
        override val token: Flow<String?> = tokenState
        override val defaultEndpoint: Flow<DefaultEndpoint?> = defaultEndpointState
        override suspend fun setDefaultEndpoint(endpoint: DefaultEndpoint?) { defaultEndpointState.value = endpoint }
        override fun currentToken(): String? = tokenState.value
        override fun currentServerUrl(): String? = url.value.ifEmpty { null }
        override suspend fun updateServerUrl(url: String) { this.url.value = url }
        override suspend fun updateToken(token: String) { tokenState.value = token }
        override suspend fun clearToken() { tokenState.value = null }

        // 批A：内嵌 dev 共享密钥纯内存槽（默认 null = 未拉起，devLogin 不带头等价旧行为）
        private var embeddedDevSecret: String? = null
        override fun currentEmbeddedDevSecret(): String? = embeddedDevSecret
        override fun updateEmbeddedDevSecret(value: String?) { embeddedDevSecret = value }

        // 分流判定与 DataStore 实现同口径（经 ServerAddress.isLocalModePreset；仓库级用例
        // 锁「登录成功触发记忆 + 本机登录不动 NAS 槽」，槽内部分流细则由 :core:network 锁定）
        override suspend fun rememberLoginAddress(url: String) {
            if (url.isEmpty()) return
            if (ServerAddress.isLocalModePreset(url)) {
                rememberedLocal.value = url
            } else {
                rememberedNas.value = url
            }
        }
    }

    /** [EmbeddedServerController] 测试替身：忠实回放「预设地址才触发拉起」语义并记录调用。 */
    private class FakeEmbeddedServerController(
        private val eventLog: MutableList<String> = mutableListOf(),
    ) : EmbeddedServerController {
        val ensureCalls = mutableListOf<String>()

        override fun ensureStartedIfLocalMode(serverUrl: String): Boolean {
            ensureCalls += serverUrl
            eventLog += "ensure"
            return ServerAddress.isLocalModePreset(serverUrl)
        }

        override fun ensureHealthyIfLocalMode(serverUrl: String): Boolean =
            ServerAddress.isLocalModePreset(serverUrl)

        override fun stop() = Unit
    }

    /** [LocalServerWarmup] 测试替身：记录调用、可编程返回（nextResult=false = 模拟等待超时）。 */
    private class FakeLocalServerWarmup(private val eventLog: MutableList<String>) : LocalServerWarmup {
        var awaitCount = 0
        var nextResult = true

        override suspend fun awaitReady(timeoutMs: Long, pollIntervalMs: Long): Boolean {
            awaitCount++
            eventLog += "warmup:await"
            return nextResult
        }
    }
}
