package media.qimeng.app.core.data.coil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SignedMediaCacheKeys.stableKey] 行为锁定（任务U10-5 缓存键漂移根治）。
 *
 * 用例 URL 形态与服务端实际生成一致（`assets_media_url.go signedMediaURL`）：
 * `<base>/media/thumb/<uuid>?exp=<unix秒>&sig=<hex>&size=<sm|md|lg>`、
 * `<base>/media/orig/<uuid>?exp=..&sig=..`。
 */
class SignedMediaCacheKeysTest {

    // ---- 核心口径：同 path 不同 sig/exp → 同键（漂移免疫） ----

    @Test
    fun `缩略图URL仅exp与sig轮换时键稳定`() {
        val first = "http://192.0.2.8:8420/media/thumb/550e8400-e29b-41d4?exp=1000000000&sig=aaaa&size=md"
        val second = "http://192.0.2.8:8420/media/thumb/550e8400-e29b-41d4?exp=1000002000&sig=bbbb&size=md"
        assertEquals(SignedMediaCacheKeys.stableKey(first), SignedMediaCacheKeys.stableKey(second))
    }

    @Test
    fun `原图URL仅exp与sig轮换时键稳定`() {
        val first = "http://192.0.2.8:8420/media/orig/550e8400-e29b-41d4?exp=1000000000&sig=aaaa"
        val second = "http://192.0.2.8:8420/media/orig/550e8400-e29b-41d4?exp=1000002000&sig=bbbb"
        assertEquals(SignedMediaCacheKeys.stableKey(first), SignedMediaCacheKeys.stableKey(second))
    }

    // ---- size 维度不碰撞：剥签名但保留 size ----

    @Test
    fun `同资产不同尺寸缩略图键互异且size保留`() {
        val base = "http://10.0.2.2:8421/media/thumb/550e8400-e29b-41d4?exp=1&sig=x"
        val sm = SignedMediaCacheKeys.stableKey("$base&size=sm")!!
        val md = SignedMediaCacheKeys.stableKey("$base&size=md")!!
        val lg = SignedMediaCacheKeys.stableKey("$base&size=lg")!!
        // size 参数原样保留在键尾（与服务端 query 形态一致，避免错尺寸命中）
        assertEquals("http://10.0.2.2:8421/media/thumb/550e8400-e29b-41d4?size=md", md)
        assertNotEquals(sm, md)
        assertNotEquals(md, lg)
        assertNotEquals(sm, lg)
    }

    // ---- 不同 path → 不同键 ----

    @Test
    fun `不同资产与不同家族path键互异`() {
        val thumb = "http://h:8420/media/thumb/asset-a?exp=1&sig=x"
        val orig = "http://h:8420/media/orig/asset-a?exp=1&sig=x"
        val otherAsset = "http://h:8420/media/thumb/asset-b?exp=1&sig=x"
        val a = SignedMediaCacheKeys.stableKey(thumb)!!
        assertNotEquals(a, SignedMediaCacheKeys.stableKey(orig))
        assertNotEquals(a, SignedMediaCacheKeys.stableKey(otherAsset))
    }

    @Test
    fun `不同服务器同资产path键互异含host`() {
        val serverA = SignedMediaCacheKeys.stableKey("http://192.0.2.8:8420/media/thumb/a?exp=1&sig=x")
        val serverB = SignedMediaCacheKeys.stableKey("http://10.0.2.2:8421/media/thumb/a?exp=1&sig=x")
        assertNotEquals(serverA, serverB)
    }

    // ---- 无 query URL 原样 ----

    @Test
    fun `无query的URL原样返回`() {
        assertEquals(
            "http://192.0.2.8:8420/media/thumb/asset-a",
            SignedMediaCacheKeys.stableKey("http://192.0.2.8:8420/media/thumb/asset-a"),
        )
    }

    @Test
    fun `query仅含exp与sig时键为纯path`() {
        assertEquals(
            "http://h:8420/media/orig/asset-a",
            SignedMediaCacheKeys.stableKey("http://h:8420/media/orig/asset-a?exp=1&sig=abc"),
        )
    }

    // ---- 参数顺序与未知参数：只剥 exp/sig，其余原样保序 ----

    @Test
    fun `exp与sig位置不同仍剥净且未知参数保序`() {
        val stripped = SignedMediaCacheKeys.stableKey("http://h/media/thumb/a?size=md&exp=1&sig=x&v=2")
        assertEquals("http://h/media/thumb/a?size=md&v=2", stripped)
        assertEquals(
            SignedMediaCacheKeys.stableKey("http://h/media/thumb/a?exp=1&sig=x&size=md"),
            SignedMediaCacheKeys.stableKey("http://h/media/thumb/a?size=md&exp=1&sig=x"),
        )
    }

    // ---- 非 http(s) 与非签名家族：回 null 交回 Coil 默认键 ----

    @Test
    fun `file与content协议返回null`() {
        assertNull(SignedMediaCacheKeys.stableKey("file:///storage/emulated/0/media/pic.jpg"))
        assertNull(SignedMediaCacheKeys.stableKey("content://media/external/images/1?exp=1&sig=x"))
    }

    @Test
    fun `非media家族的httpURL返回null`() {
        assertNull(SignedMediaCacheKeys.stableKey("https://example.com/api/v1/assets?exp=1&sig=x"))
        assertNull(SignedMediaCacheKeys.stableKey("https://example.com/page"))
    }

    // ---- 空/异常输入安全兜底 ----

    @Test
    fun `空串与blank返回null`() {
        assertNull(SignedMediaCacheKeys.stableKey(""))
        assertNull(SignedMediaCacheKeys.stableKey("   "))
    }

    @Test
    fun `畸形输入不抛异常`() {
        // 非 media 家族无 query → null（交回默认键，行为等价于原样）
        assertNull(SignedMediaCacheKeys.stableKey("http://"))
        assertNull(SignedMediaCacheKeys.stableKey("?"))
        // 只有分隔符没有参数值也不炸
        assertEquals("http://h/media/thumb/a?", SignedMediaCacheKeys.stableKey("http://h/media/thumb/a?"))
    }
}
