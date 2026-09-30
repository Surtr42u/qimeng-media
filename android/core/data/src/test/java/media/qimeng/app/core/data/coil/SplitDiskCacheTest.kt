package media.qimeng.app.core.data.coil

import java.io.File
import media.qimeng.app.core.network.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 批S5 缩略图缓存按端分池的纯 JVM 口径锁定：
 * 1. [resolveCachePool] 键→池路由（真实 [ServerAddress.isLocalModePreset] 判定 +
 *    假谓词两路）——NAS URL / 本机预设 URL / localhost 变体 / 非 http 键兜底；
 * 2. [migrateLegacyImageCacheDir] 存量迁移幂等（TemporaryFolder 真实文件系统）。
 */
class SplitDiskCacheTest {

    // ---- 路由口径：真实 isLocalModePreset（回环 host + 端口 18430） ----

    @Test
    fun `服务器URL路由NAS池`() {
        val key = "http://192.0.2.8:8420/media/thumb/550e8400?size=md"
        assertEquals(CachePool.NAS, resolveCachePool(key, ServerAddress::isLocalModePreset))
    }

    @Test
    fun `本机预设URL路由本地池`() {
        val key = "http://127.0.0.1:18430/media/thumb/550e8400?size=md"
        assertEquals(CachePool.LOCAL, resolveCachePool(key, ServerAddress::isLocalModePreset))
    }

    @Test
    fun `localhost变体路由本地池`() {
        // isLocalModePreset 接受 localhost 与 127.0.0.1 两种回环写法（reviewer P3-3 既有口径）
        val key = "http://localhost:18430/media/thumb/550e8400"
        assertEquals(CachePool.LOCAL, resolveCachePool(key, ServerAddress::isLocalModePreset))
    }

    @Test
    fun `https远程URL路由NAS池`() {
        val key = "https://nas.example.com/media/thumb/550e8400"
        assertEquals(CachePool.NAS, resolveCachePool(key, ServerAddress::isLocalModePreset))
    }

    @Test
    fun `本地端口但非回环主机路由NAS池`() {
        // 端口命中 18430 但 host 非回环 → isLocalModePreset 判 false（端口单值互指红线：
        // 自定义 host 的 18430 不是内嵌预设地址），归 NAS 池——键族判定跟随既有谓词不另立口径
        val key = "http://192.0.2.8:18430/media/thumb/550e8400"
        assertEquals(CachePool.NAS, resolveCachePool(key, ServerAddress::isLocalModePreset))
    }

    @Test
    fun `模拟器别名回环路由NAS池_锁现状口径`() {
        // 10.0.2.2:18430 = 模拟器视角的宿主机本地端，但 isLocalModePreset 只认
        // 127.0.0.1/localhost（其语义是内嵌服务生命周期判定）——按批S5 冻结口径
        // 「路由判定复用 isLocalModePreset」，此形态归 NAS 池。本测试锁现状，若日后
        // 用户报模拟器本地端串池，此处是决策点（扩谓词须先拍板，禁止静默改）。
        val key = "http://10.0.2.2:18430/media/thumb/550e8400"
        assertEquals(CachePool.NAS, resolveCachePool(key, ServerAddress::isLocalModePreset))
    }

    // ---- 路由口径：非 http 键跟随当前连接来源（第四百一十一笔 分池来源化） ----

    @Test
    fun `非http键默认来源NAS池`() {
        // 默认 activePool=NAS（进程冷启动接线落地前/登出态）：数值上与历史兜底口径一致
        assertEquals(CachePool.NAS, resolveCachePool("file:///data/cache/x", ServerAddress::isLocalModePreset))
        assertEquals(CachePool.NAS, resolveCachePool("data:image/png;base64,xxxx", ServerAddress::isLocalModePreset))
        assertEquals(CachePool.NAS, resolveCachePool("android.resource://pkg/raw/img", ServerAddress::isLocalModePreset))
        assertEquals(CachePool.NAS, resolveCachePool("", ServerAddress::isLocalModePreset))
    }

    @Test
    fun `剥host稳定键跟随当前来源路由`() {
        // U10-5 剥 host 后签名直链稳定键不含来源信息——路由由当前连接来源驱动
        //（CachePoolBinder 写入 SplitDiskCache.updateActivePool）。修复前此处恒 NAS
        //（本地池被架空：真机实证 image_cache_local 仅 1 文件）。
        val stableKey = "/media/thumb/550e8400?size=md"
        assertEquals(CachePool.NAS, resolveCachePool(stableKey, ServerAddress::isLocalModePreset, CachePool.NAS))
        assertEquals(CachePool.LOCAL, resolveCachePool(stableKey, ServerAddress::isLocalModePreset, CachePool.LOCAL))
    }

    @Test
    fun `http键优先按键判定无视当前来源`() {
        // 含 host 的完整 URL 键（非签名家族等）来源信息自足，不受 activePool 影响
        assertEquals(
            CachePool.NAS,
            resolveCachePool("http://192.0.2.8:8420/media/thumb/x", ServerAddress::isLocalModePreset, CachePool.LOCAL),
        )
        assertEquals(
            CachePool.LOCAL,
            resolveCachePool("http://127.0.0.1:18430/media/thumb/x", ServerAddress::isLocalModePreset, CachePool.NAS),
        )
    }

    @Test
    fun `族外非http键同样跟随当前来源`() {
        assertEquals(CachePool.LOCAL, resolveCachePool("file:///x", ServerAddress::isLocalModePreset, CachePool.LOCAL))
        assertEquals(CachePool.NAS, resolveCachePool("file:///x", ServerAddress::isLocalModePreset, CachePool.NAS))
    }

    // ---- 路由口径：谓词注入侧（路由判定与具体谓词解耦） ----

    @Test
    fun `路由跟随注入谓词判定`() {
        val localPredicate: (String) -> Boolean = { it.contains("18430") }
        assertEquals(CachePool.LOCAL, resolveCachePool("http://a:18430/x", localPredicate))
        assertEquals(CachePool.NAS, resolveCachePool("http://a:8420/x", localPredicate))
    }

    @Test
    fun `isHttpCacheKey只认http与https前缀`() {
        assertTrue(isHttpCacheKey("http://a/x"))
        assertTrue(isHttpCacheKey("https://a/x"))
        assertFalse(isHttpCacheKey("HTTP://a/x"))
        assertFalse(isHttpCacheKey("ftp://a/x"))
        assertFalse(isHttpCacheKey(""))
    }

    // ---- 存量迁移：幂等 ----

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `存量混合目录整体迁移为NAS池目录`() {
        val root = tmp.newFolder("cache")
        val legacy = File(root, LEGACY_DISK_CACHE_DIR).apply { mkdirs() }
        val sentinel = File(legacy, "abcdef.1").apply { writeText("x") }
        val expected = sentinel.readText()

        assertTrue(migrateLegacyImageCacheDir(root))

        // 旧目录整体消失、内容原样出现在 NAS 池目录（renameTo 语义）
        assertFalse(File(root, LEGACY_DISK_CACHE_DIR).exists())
        assertTrue(File(root, NAS_DISK_CACHE_DIR).isDirectory)
        assertEquals(expected, File(root, "$NAS_DISK_CACHE_DIR/abcdef.1").readText())
    }

    @Test
    fun `迁移幂等二次调用不再动`() {
        val root = tmp.newFolder("cache")
        File(root, LEGACY_DISK_CACHE_DIR).apply { mkdirs() }
        assertTrue(migrateLegacyImageCacheDir(root))
        // 迁移后旧目录已不存在 → 二次调用空转（返回 false，目录布局不变）
        assertFalse(migrateLegacyImageCacheDir(root))
        assertTrue(File(root, NAS_DISK_CACHE_DIR).isDirectory)
        assertFalse(File(root, LEGACY_DISK_CACHE_DIR).exists())
    }

    @Test
    fun `无存量目录时迁移为空操作`() {
        val root = tmp.newFolder("cache")
        assertFalse(migrateLegacyImageCacheDir(root))
        assertFalse(File(root, LEGACY_DISK_CACHE_DIR).exists())
        assertFalse(File(root, NAS_DISK_CACHE_DIR).exists())
        assertFalse(File(root, LOCAL_DISK_CACHE_DIR).exists())
    }

    @Test
    fun `两目录并存时保持现状不合并`() {
        // 理论不可达态（迁移完成后旧目录不存在）；防御 renameTo 对非空目标目录失败
        val root = tmp.newFolder("cache")
        val legacy = File(root, LEGACY_DISK_CACHE_DIR).apply { mkdirs() }
        File(root, NAS_DISK_CACHE_DIR).apply { mkdirs() }

        assertFalse(migrateLegacyImageCacheDir(root))
        assertTrue(legacy.isDirectory)
        assertTrue(File(root, NAS_DISK_CACHE_DIR).isDirectory)
    }
}
