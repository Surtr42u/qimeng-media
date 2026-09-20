package media.qimeng.app.feature.detail

import coil3.network.HttpException
import coil3.network.NetworkResponse
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图片加载失败归因纯函数测试（2026-09-18 文案分档；第三百六十四笔扩三档 +OOM 归因）：
 * 传输类（IOException 族）/ 内存压力（OOM）/ 解码及其他失败的三档口径锁定。
 */
class ImageLoadErrorClassifierTest {

    @Test
    fun `timeout is network failure`() {
        assertEquals(
            ImageFailureKind.NETWORK,
            classifyImageFailure(SocketTimeoutException("read timed out")),
        )
    }

    @Test
    fun `dns failure is network failure`() {
        assertEquals(
            ImageFailureKind.NETWORK,
            classifyImageFailure(UnknownHostException("host")),
        )
    }

    @Test
    fun `wrapped io exception via cause chain is network failure`() {
        val wrapped = RuntimeException(
            "decode pipeline failed",
            IllegalStateException(IOException("stream reset")),
        )
        assertEquals(ImageFailureKind.NETWORK, classifyImageFailure(wrapped))
    }

    @Test
    fun `plain decode failure is decode failure`() {
        assertEquals(
            ImageFailureKind.DECODE,
            classifyImageFailure(IllegalStateException("bitmap decode failed")),
        )
    }

    @Test
    fun `non io cause chain is decode failure`() {
        assertEquals(
            ImageFailureKind.DECODE,
            classifyImageFailure(RuntimeException(IllegalStateException("corrupt"))),
        )
    }

    @Test
    fun `self referencing cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException(a)
        a.initCause(b) // 人造自引用环：限深必须兜住不死循环
        assertEquals(ImageFailureKind.DECODE, classifyImageFailure(a))
    }

    @Test
    fun `io exception base class counts`() {
        assertEquals(ImageFailureKind.NETWORK, classifyImageFailure(IOException("generic io")))
    }

    /** 批S6：Coil 网络层 504（离线 only-if-cached 合成）等 HttpException 是 RuntimeException 非 IOException */
    @Test
    fun `coil http exception is network failure`() {
        assertEquals(
            ImageFailureKind.NETWORK,
            classifyImageFailure(HttpException(NetworkResponse(code = 504))),
        )
    }

    @Test
    fun `wrapped coil http exception via cause chain is network failure`() {
        val wrapped = RuntimeException(
            "image load failed",
            HttpException(NetworkResponse(code = 502)),
        )
        assertEquals(ImageFailureKind.NETWORK, classifyImageFailure(wrapped))
    }

    // ---------- 第三百六十四笔：OOM 三档扩档（客户端异常表实证解码链 OOM 误报成「文件损坏」） ----------

    @Test
    fun `oom is memory failure`() {
        assertEquals(
            ImageFailureKind.MEMORY,
            classifyImageFailure(OutOfMemoryError("Failed to allocate a 32 byte allocation")),
        )
    }

    @Test
    fun `wrapped oom via cause chain is memory failure`() {
        // 真实形态：Coil NetworkFetcher 读流途中抛出的 OOM 被上层包了一层
        val wrapped = RuntimeException("image load failed", OutOfMemoryError("heap exhausted"))
        assertEquals(ImageFailureKind.MEMORY, classifyImageFailure(wrapped))
    }

    @Test
    fun `network failure wins over oom in cause chain`() {
        // 链上既有传输类又有 OOM：报网络档（重试指引更有用）
        val oom = OutOfMemoryError("oom during stream read")
        oom.initCause(IOException("socket reset"))
        assertEquals(ImageFailureKind.NETWORK, classifyImageFailure(oom))
    }
}
