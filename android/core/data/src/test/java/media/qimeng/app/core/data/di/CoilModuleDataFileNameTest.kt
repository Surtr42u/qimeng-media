package media.qimeng.app.core.data.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磁盘缓存「数据文件」文件名谓词锁定（批S4 2026-09-19 本地缓存文件数口径）。
 * Coil 3.6.2 磁盘缓存布局（官方源码 DiskLruCache.kt/RealDiskCache.kt 核实）：
 * 每条目 = `{key}.0`（元数据）+ `{key}.1`（数据）+ 写盘中转 `{key}.N.tmp`，
 * 日志文件 journal / journal.tmp / journal.bkp。一条数据文件 = 一张缓存图。
 */
class CoilModuleDataFileNameTest {

    @Test
    fun `数据文件后缀点1命中`() {
        assertTrue(isDiskCacheDataFileName("3400330d1dfc7f3f7f4b8d4d803dfcf6.1"))
    }

    @Test
    fun `元数据文件点0不命中`() {
        assertFalse(isDiskCacheDataFileName("3400330d1dfc7f3f7f4b8d4d803dfcf6.0"))
    }

    @Test
    fun `写盘中转tmp文件不命中`() {
        assertFalse(isDiskCacheDataFileName("3400330d1dfc7f3f7f4b8d4d803dfcf6.1.tmp"))
        assertFalse(isDiskCacheDataFileName("3400330d1dfc7f3f7f4b8d4d803dfcf6.0.tmp"))
    }

    @Test
    fun `日志文件不命中`() {
        assertFalse(isDiskCacheDataFileName("journal"))
        assertFalse(isDiskCacheDataFileName("journal.tmp"))
        assertFalse(isDiskCacheDataFileName("journal.bkp"))
    }
}
