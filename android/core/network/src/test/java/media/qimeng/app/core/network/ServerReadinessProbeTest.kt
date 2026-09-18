package media.qimeng.app.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.infrastructure.ServerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ServerReadinessProbe] 单测：以可编排失败序列的探针替身驱动轮询循环。
 * 用真实 delay（毫秒级预算），不引入 coroutines-test——本模块既有测试不带该依赖。
 */
class ServerReadinessProbeTest {

    /** 可编程 probe 行为的 AuthApi 替身：只实现探针路径，其余操作测试不会触达。 */
    private class FakeAuthApi(private val script: () -> Unit) : AuthApi {
        override suspend fun probe() = script()
        override suspend fun login(password: String): String = throw IOException("unused")
        override suspend fun devLogin(): String = throw IOException("unused")
        override suspend fun logout() = throw IOException("unused")
    }

    private class FakeAuthApiFactory(private val api: FakeAuthApi) : AuthApiFactory {
        override fun create(baseUrl: String) = api
    }

    private fun probe(api: FakeAuthApi, config: FakeServerConfigDataSource = FakeServerConfigDataSource(TEST_URL)) =
        ServerReadinessProbe(config, api.apiFactory(), CoroutineScope(Dispatchers.Default))

    private fun FakeAuthApi.apiFactory() = FakeAuthApiFactory(this)

    @Test
    fun `首枪即通_立即就绪`() = runBlocking {
        val calls = AtomicInteger()
        val api = FakeAuthApi {
            calls.incrementAndGet()
            // probe() 成功 = 不抛
        }
        val started = System.nanoTime()
        assertTrue(probe(api).awaitReady())
        assertEquals(1, calls.get())
        assertTrue("首枪即通不应有任何轮询等待", System.nanoTime() - started < 200_000_000L)
    }

    @Test
    fun `失败两次后就绪_轮询自愈`() = runBlocking {
        val calls = AtomicInteger()
        val api = FakeAuthApi {
            if (calls.incrementAndGet() <= 2) throw IOException("not listening yet")
        }
        assertTrue(probe(api).awaitReady())
        assertEquals(3, calls.get())
    }

    @Test
    fun `预算耗尽_返回未就绪而非死等`() = runBlocking {
        val api = FakeAuthApi { throw IOException("never ready") }
        val started = System.nanoTime()
        assertFalse(probe(api).awaitReady(budgetMs = 400L))
        val waitedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("预算 400ms 应在 3s 内放弃（实际 ${waitedMs}ms）", waitedMs < 3_000L)
    }

    @Test
    fun `探针4xx_说明对端不是绮梦服务端_立即放弃`() = runBlocking {
        val calls = AtomicInteger()
        val api = FakeAuthApi {
            calls.incrementAndGet()
            throw ClientException("not a qimeng server", 404)
        }
        assertFalse(probe(api).awaitReady(budgetMs = 5_000L))
        assertEquals("4xx 不应继续轮询", 1, calls.get())
    }

    @Test
    fun `探针5xx_按未就绪继续等待`() = runBlocking {
        val calls = AtomicInteger()
        val api = FakeAuthApi {
            if (calls.incrementAndGet() == 1) throw ServerException("warming up")
        }
        assertTrue(probe(api).awaitReady())
        assertEquals(2, calls.get())
    }

    @Test
    fun `未配置地址_直接未就绪`() = runBlocking {
        val api = FakeAuthApi { throw IOException("should not be called") }
        val probe = ServerReadinessProbe(
            FakeServerConfigDataSource(initialServerUrl = ""),
            api.apiFactory(),
            CoroutineScope(Dispatchers.Default),
        )
        assertFalse(probe.awaitReady())
    }

    @Test
    fun `就绪结论短窗复用_不重复打探针`() = runBlocking {
        val calls = AtomicInteger()
        val api = FakeAuthApi { calls.incrementAndGet() }
        val p = probe(api)
        assertTrue(p.awaitReady())
        assertTrue(p.awaitReady())
        assertEquals("短窗内第二次调用应复用结论", 1, calls.get())
    }

    private companion object {
        const val TEST_URL = "http://127.0.0.1:18430"
    }
}
