package media.qimeng.app.core.data.prefetch

import coil3.disk.DiskCache
import coil3.disk.directory
import java.io.File
import media.qimeng.app.core.data.coil.CachePool
import media.qimeng.app.core.data.coil.SignedMediaCacheKeys
import media.qimeng.app.core.data.coil.SplitDiskCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 预取磁盘探测短路（第四百一十一笔）纯 JVM 行为锁定：真实 RealDiskCache + 临时目录
 * （比 fake 更贴近 DiskLruCache 锁/commit 语义）。覆盖：命中/未命中/非签名家族恒 false/
 * 探测跟随当前连接来源池（与 CachePoolBinder 分池来源化联动）。
 */
class PrefetchDiskProbeTest {

    /** 与浏览/预取同源的签名直链样本（绝对直链 → 剥 exp/sig/host 稳定键） */
    private val url = "http://192.0.2.8:8420/media/thumb/550e8400-e29b?exp=1&sig=abc&size=md"

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nasCache: DiskCache
    private lateinit var localCache: DiskCache
    private lateinit var split: SplitDiskCache
    private lateinit var probe: PrefetchDiskProbe

    @Before
    fun setUp() {
        nasCache = buildCache(tmp.newFolder("nas"))
        localCache = buildCache(tmp.newFolder("local"))
        split = SplitDiskCache(nasCache, localCache)
        probe = PrefetchDiskProbe(split)
    }

    @Test
    fun `已缓存URL探测命中`() {
        nasCache.put(SignedMediaCacheKeys.stableKey(url)!!)
        assertTrue(probe.isDiskCached(url))
    }

    @Test
    fun `未缓存URL探测未命中`() {
        assertFalse(probe.isDiskCached(url))
    }

    @Test
    fun `非签名家族URL探测恒false`() {
        // stableKey 回 null（path 不含 /media/）→ 交回调用方走原 execute 路径，探测恒 false
        assertFalse(probe.isDiskCached("https://example.com/posters/x.png"))
    }

    @Test
    fun `探测跟随当前连接来源池`() {
        nasCache.put(SignedMediaCacheKeys.stableKey(url)!!)
        // 默认/显式 NAS 来源：命中 NAS 池
        assertTrue(probe.isDiskCached(url))
        // 切本地端来源：同键路由本地池 → miss（分池拍板语义：两端缓存互不共享）
        split.updateActivePool(CachePool.LOCAL)
        assertFalse(probe.isDiskCached(url))
        // 切回 NAS：恢复命中
        split.updateActivePool(CachePool.NAS)
        assertTrue(probe.isDiskCached(url))
    }

    // ---- 基座：真实 RealDiskCache + 临时目录 ----

    private fun buildCache(dir: File): DiskCache = DiskCache.Builder()
        .directory(dir)
        .maxSizeBytes(10L * 1024 * 1024)
        .build()

    /** 直写一条缓存条目（metadata + data 双文件，commit 后对 openSnapshot 可见） */
    private fun DiskCache.put(key: String) {
        val editor = openEditor(key) ?: error("同键 editor 冲突（测试样本键不应重复）")
        fileSystem.write(editor.metadata) { writeUtf8("{}") }
        fileSystem.write(editor.data) { writeUtf8("payload") }
        editor.commit()
    }
}
