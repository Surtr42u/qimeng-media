package media.qimeng.app.core.data.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直传/分片阈值分流两分支锁定（ADR-0028 Android 接入批）：纯函数无 IO。
 * 分流在 worker 入口读一次即定通道——分错通道的代价是「小文件多四步握手」或
 * 「大文件放弃续传」，两分支边界（恰达阈值、子目录不改变判定）必须钉死。
 */
class UploadRoutingTest {

    @Test
    fun `小于阈值走直传`() {
        assertFalse(UploadRouting.useChunkedSession(UploadRouting.CHUNKED_THRESHOLD_BYTES - 1))
    }

    @Test
    fun `恰达阈值走分片`() {
        assertTrue(UploadRouting.useChunkedSession(UploadRouting.CHUNKED_THRESHOLD_BYTES))
    }

    @Test
    fun `超大文件走分片`() {
        assertTrue(UploadRouting.useChunkedSession(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `目录串空白仍按size判定`() {
        // 空白目录串 = 库根目标，不参与分流判定（dir 不再是分流变量）
        assertTrue(UploadRouting.useChunkedSession(UploadRouting.CHUNKED_THRESHOLD_BYTES))
    }

    @Test
    fun `子目录目标同样走分片`() {
        // dir 已入分片协议（CreateUploadRequest.dir，与直传同语义、complete 按
        // 其落位）——初版「有 dir 一律直传」的临时限制随协议补齐撤销
        assertTrue(UploadRouting.useChunkedSession(UploadRouting.CHUNKED_THRESHOLD_BYTES))
        assertTrue(UploadRouting.useChunkedSession(Long.MAX_VALUE))
    }
}
