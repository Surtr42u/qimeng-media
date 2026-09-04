package media.qimeng.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定壳导航契约：五 Tab 的数量/顺序/路由唯一性（规格 = 旧仓库 GUIDE_UI §导航结构）。
 * 顺序或路由被误改时此测试即红，防止后续批次接入覆盖页面时破坏 Tab 结构。
 */
class TopLevelDestinationTest {

    @Test
    fun `底部五 Tab 顺序为 首页-全部-相册-数据-我的`() {
        val order = TopLevelDestination.entries.map { it.name }
        assertEquals(listOf("HOME", "ALL", "ALBUM", "STATS", "SETTINGS"), order)
    }

    @Test
    fun `五 Tab 路由互不相同`() {
        val routes = TopLevelDestination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
