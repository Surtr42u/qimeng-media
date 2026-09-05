package media.qimeng.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定壳导航契约：四 Tab 的数量/顺序/路由唯一性（2026-09-05 夜用户拍板「导航四化」：
 * 首页/相册/数据/我的——原「全部」更名「相册」route all 不变，原 ALBUM 目的地删除）。
 * 顺序或路由被误改时此测试即红，防止后续批次接入覆盖页面时破坏 Tab 结构。
 */
class TopLevelDestinationTest {

    @Test
    fun `底部四 Tab 顺序为 首页-相册-数据-我的`() {
        val order = TopLevelDestination.entries.map { it.name }
        assertEquals(listOf("HOME", "ALL", "STATS", "SETTINGS"), order)
    }

    @Test
    fun `相册 Tab 沿用原全部页路由 all`() {
        assertEquals("all", TopLevelDestination.ALL.route)
    }

    @Test
    fun `四 Tab 路由互不相同`() {
        val routes = TopLevelDestination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
