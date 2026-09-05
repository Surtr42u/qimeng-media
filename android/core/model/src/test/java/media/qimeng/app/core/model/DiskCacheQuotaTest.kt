package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Coil 磁盘缓存 LRU 档位（C5：512MB/1GB/2GB/5GB，默认 1GB；持久化走 MB 整数） */
class DiskCacheQuotaTest {

    @Test
    fun `四档字节值换算`() {
        assertEquals(512L * 1024 * 1024, DiskCacheQuota.MB512.bytes)
        assertEquals(1024L * 1024 * 1024, DiskCacheQuota.GB1.bytes)
        assertEquals(2L * 1024 * 1024 * 1024, DiskCacheQuota.GB2.bytes)
        assertEquals(5L * 1024 * 1024 * 1024, DiskCacheQuota.GB5.bytes)
    }

    @Test
    fun `默认档1GB`() {
        assertEquals(DiskCacheQuota.GB1, DiskCacheQuota.DEFAULT)
    }

    @Test
    fun `MB往返映射`() {
        DiskCacheQuota.entries.forEach { quota ->
            assertEquals(quota, DiskCacheQuota.fromMb((quota.bytes / DiskCacheQuota.BYTES_PER_MB).toInt()))
        }
    }

    @Test
    fun `非法持久化值回落默认档`() {
        assertEquals(DiskCacheQuota.DEFAULT, DiskCacheQuota.fromMb(777))
        assertEquals(DiskCacheQuota.DEFAULT, DiskCacheQuota.fromMb(0))
    }
}
