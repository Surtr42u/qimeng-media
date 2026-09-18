package media.qimeng.app.feature.detail

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片加载失败归因纯函数测试（2026-09-18 文案分档）：传输类（IOException 族）与
 * 解码/其他失败的两分口径锁定。
 */
class ImageLoadErrorClassifierTest {

    @Test
    fun `timeout is network failure`() {
        assertTrue(isNetworkTransferFailure(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `dns failure is network failure`() {
        assertTrue(isNetworkTransferFailure(UnknownHostException("host")))
    }

    @Test
    fun `wrapped io exception via cause chain is network failure`() {
        val wrapped = RuntimeException(
            "decode pipeline failed",
            IllegalStateException(IOException("stream reset")),
        )
        assertTrue(isNetworkTransferFailure(wrapped))
    }

    @Test
    fun `plain decode failure is not network failure`() {
        assertFalse(isNetworkTransferFailure(IllegalStateException("bitmap decode failed")))
    }

    @Test
    fun `non io cause chain is not network failure`() {
        assertFalse(
            isNetworkTransferFailure(RuntimeException(IllegalStateException("corrupt"))),
        )
    }

    @Test
    fun `self referencing cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException(a)
        a.initCause(b) // 人造自引用环：限深必须兜住不死循环
        assertFalse(isNetworkTransferFailure(a))
    }

    @Test
    fun `io exception base class counts`() {
        assertTrue(isNetworkTransferFailure(IOException("generic io")))
    }
}
