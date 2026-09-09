package media.qimeng.app.core.ui.component

import media.qimeng.app.core.model.GridSection
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网格扁平化/去重纯函数单测（exp#5，2026-09-10 ui/expressive 分支）：
 * 服务端单响应若含重复资产 id，同屏双卡会同时撞 LazyVerticalGrid 项 key 约束（直接崩）
 * 与共享元素同 key 冲突（exp#3）——[flattenGridCells] 在 key 铸造点按 id 收敛。
 * 锁定口径：顺序保持首现位、跨段去重、组头不参与去重、正常数据输出零变化。
 * 全部纯 JVM，无 Android 依赖（core:ui 铁律）。
 */
class QimengMediaGridCellsTest {

    private fun asset(id: String) = MediaAsset(
        id = id,
        fileName = "$id.jpg",
        title = id,
        mediaType = MediaKind.IMAGE,
        thumbUrl = null,
        source = null,
        characters = emptyList(),
        isFavorite = false,
        authorNames = emptyList(),
        modifiedAtMs = null,
        durationMs = null,
        viewCount = null,
        playCount = null,
        lastViewedAtMs = null,
    )

    private fun assetIds(cells: List<GridCell>): List<String> =
        cells.mapNotNull { it.asset?.id }

    @Test
    fun `重复id输入 - 输出唯一且保持首现位`() {
        val sections = listOf(
            GridSection(label = "", items = listOf(asset("a"), asset("b"), asset("a"))),
        )
        val cells = flattenGridCells(sections)
        assertEquals(listOf("a", "b"), assetIds(cells))
    }

    @Test
    fun `跨段去重 - 组头恒渲染且后段重复丢弃`() {
        val sections = listOf(
            GridSection(label = "2026-09-09  2 项", items = listOf(asset("a"), asset("b"))),
            GridSection(label = "2026-09-08  2 项", items = listOf(asset("b"), asset("c"))),
        )
        val cells = flattenGridCells(sections)
        // 组头两个 + 去重后 a、b、c 三卡（后段的 b 是重复，丢弃；c 保留）
        assertEquals(5, cells.size)
        assertEquals(listOf("a", "b", "c"), assetIds(cells))
        assertEquals("2026-09-09  2 项", cells[0].header)
        assertEquals("2026-09-08  2 项", cells[3].header)
    }

    @Test
    fun `无重复数据 - 输出与不去重版本逐格相同（零语义变化）`() {
        val sections = listOf(
            GridSection(label = "组头", items = listOf(asset("a"), asset("b"))),
            GridSection(label = "", items = listOf(asset("c"))),
        )
        val cells = flattenGridCells(sections)
        // 组头 + a、b + （空 label 段无组头）+ c
        assertEquals(4, cells.size)
        assertEquals("组头", cells[0].header)
        assertEquals(listOf("a", "b", "c"), assetIds(cells))
    }

    @Test
    fun `空段与全重复段 - 组头仍保留（防御路径不裁剪）`() {
        val sections = listOf(
            GridSection(label = "空段", items = emptyList()),
            GridSection(label = "全重复段", items = listOf(asset("a"))),
            GridSection(label = "尾段", items = listOf(asset("a"))),
        )
        val cells = flattenGridCells(sections)
        // 空段组头 + 全重复段组头 + a（首现）+ 尾段组头（尾段的 a 重复丢弃）= 4 格
        assertEquals(4, cells.size)
        assertEquals(listOf("a"), assetIds(cells))
        assertEquals("空段", cells[0].header)
        assertEquals("全重复段", cells[1].header)
        assertEquals("a", cells[2].asset?.id)
        assertEquals("尾段", cells[3].header)
    }
}
