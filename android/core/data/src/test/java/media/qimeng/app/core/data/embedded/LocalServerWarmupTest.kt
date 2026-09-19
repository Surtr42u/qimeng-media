package media.qimeng.app.core.data.embedded

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * LocalServerWarmup 单测（批S8）：Fake 探针锁「立即就绪/轮询渐就绪/超时」三态 +
 * 真实 socket 探针锁「有监听可连/无监听拒绝」。全部走 runTest 虚拟时钟——超时用例
 * （5s 上限）秒过即证「等待不真阻塞测试」；生产语义（真时钟 200ms 轮询/5s 上限）由
 * withTimeoutOrNull/delay 挂调度器实现，同一代码路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalServerWarmupTest {

    /** 恒定态探针：ready 固定，只数调用次数。 */
    private class FixedProber(private val ready: Boolean) : LocalPortProber {
        var probeCount = 0
        override fun probe(host: String, port: Int): Boolean {
            probeCount++
            return ready
        }
    }

    /** 渐进就绪探针：前 [readyAfter] 次不通、其后通（锁轮询重试语义）。 */
    private class ReadyAfterProber(private val readyAfter: Int) : LocalPortProber {
        var probeCount = 0
        override fun probe(host: String, port: Int): Boolean = ++probeCount > readyAfter
    }

    @Test
    fun `探针立即就绪_返回true且只探测一次不空转`() = runTest {
        val prober = FixedProber(ready = true)
        val warmup = LocalServerWarmupImpl(prober, UnconfinedTestDispatcher(testScheduler))

        assertTrue(warmup.awaitReady())

        assertEquals(1, prober.probeCount)
        assertEquals(0L, testScheduler.currentTime) // 首探即中：零轮询零延迟
    }

    @Test
    fun `第三次探测才就绪_轮询重试后返回true`() = runTest {
        val prober = ReadyAfterProber(readyAfter = 2)
        val warmup = LocalServerWarmupImpl(prober, UnconfinedTestDispatcher(testScheduler))

        assertTrue(warmup.awaitReady())

        assertEquals(3, prober.probeCount)
        assertEquals(2 * localServerPollIntervalMs, testScheduler.currentTime)
    }

    @Test
    fun `探针始终不通_超时返回false且按虚拟时钟走完等待窗`() = runTest {
        val prober = FixedProber(ready = false)
        val warmup = LocalServerWarmupImpl(prober, UnconfinedTestDispatcher(testScheduler))

        assertFalse(warmup.awaitReady())

        // 恰好烧完 5s 超时窗（测试本身秒过 = 无真实阻塞）；轮询次数按 200ms 步进
        // 落在预期区间（±1 容忍超时定时器与最后一次 delay 在同一虚拟时刻的竞态序）
        assertEquals(localServerReadyTimeoutMs, testScheduler.currentTime)
        val expected = localServerReadyTimeoutMs / localServerPollIntervalMs
        assertTrue(prober.probeCount.toLong() in expected - 1..expected + 1)
    }

    @Test
    fun `超时上限与轮询间隔可注入_小窗口快速超时`() = runTest {
        val warmup = LocalServerWarmupImpl(
            FixedProber(ready = false),
            UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(warmup.awaitReady(timeoutMs = 1_000, pollIntervalMs = 100))

        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `真实socket探针_监听中可连_无监听连接拒绝`() {
        val prober = SocketLocalPortProber()
        val server = ServerSocket(0) // 临时监听：探测目标用临时端口，不依赖 18430 是否被占
        val port = server.localPort
        try {
            assertTrue(prober.probe("127.0.0.1", port))
        } finally {
            server.close()
        }
        // 无监听：回环连接拒绝是即时的（250ms 连接超时仅兜调度抖动，不会拖慢本用例）
        assertFalse(prober.probe("127.0.0.1", port))
    }
}
