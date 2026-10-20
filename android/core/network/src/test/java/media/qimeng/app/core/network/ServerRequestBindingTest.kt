package media.qimeng.app.core.network

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRequestBindingTest {
    private val serverA = "https://server-a.invalid/"
    private val serverB = "https://server-b.invalid/"

    @Test fun `绑定客户端拒绝跨域及根路径之外的请求`() = runTest {
        val base = serverA + "root/"
        val config = FakeServerConfigDataSource(base, "synthetic-a")
        var sent = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(config, SessionEventBus(), backgroundScope))
            .addInterceptor { sent++; response(it, 200) }
            .build().boundToServer(base)
        for (url in listOf(serverB + "root/api", serverA + "root-other/api", "https://server-a.invalid:8443/root/api")) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().close()
                throw AssertionError("请求地址越过绑定边界")
            } catch (_: IOException) { }
        }
        assertEquals(0, sent)
    }

    @Test fun `换端后旧任务不会发出请求也不会泄露新端 token`() = runTest {
        val config = FakeServerConfigDataSource(serverA, "synthetic-a")
        var sent = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(config, SessionEventBus(), backgroundScope))
            .addInterceptor { sent++; response(it, 200) }
            .build().boundToServer(serverA)
        config.updateServerUrl(serverB)
        config.updateToken("synthetic-b")
        try {
            client.newCall(Request.Builder().url(serverA + "api/v1/uploads").build()).execute()
            throw AssertionError("旧目标应被拒绝")
        } catch (_: IOException) { }
        assertEquals(0, sent)
        assertEquals("synthetic-b", config.currentToken())
    }

    @Test fun `匹配目标携带正确 token 且禁止自动重定向`() = runTest {
        val config = FakeServerConfigDataSource(serverA, "synthetic-a")
        var header: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(config, SessionEventBus(), backgroundScope))
            .addInterceptor { header = it.request().header("Authorization"); response(it, 200) }
            .build().boundToServer(serverA)
        client.newCall(Request.Builder().url(serverA + "api/v1/uploads").build()).execute().close()
        assertEquals("Bearer synthetic-a", header)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test fun `旧端迟到401不清除新端会话`() = runTest {
        val config = FakeServerConfigDataSource(serverA, "synthetic-a")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(config, SessionEventBus(), scope))
            .addInterceptor {
                runBlocking { config.updateServerUrl(serverB); config.updateToken("synthetic-b") }
                response(it, 401)
            }.build().boundToServer(serverA)
        client.newCall(Request.Builder().url(serverA + "api/v1/uploads").build()).execute().close()
        assertEquals("synthetic-b", config.currentToken())
    }

    @Test fun `地址身份规范化但不同根路径和端口不会混同`() {
        assertTrue(ServerRequestBinding("https://example.invalid").matches("https://example.invalid/"))
        assertFalse(ServerRequestBinding(serverA).matches(serverB))
        assertFalse(ServerRequestBinding(serverA).matches(null))
        assertFalse(ServerRequestBinding(serverA).matches("https://server-a.invalid:8443/"))
        assertFalse(ServerRequestBinding("https://example.invalid/a/").matches("https://example.invalid/b/"))
    }

    private fun response(chain: Interceptor.Chain, status: Int): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
        .body("".toResponseBody()).build()
}
