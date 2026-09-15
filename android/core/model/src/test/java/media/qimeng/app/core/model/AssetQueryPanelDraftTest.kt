package media.qimeng.app.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AssetQuery.withPanelDraft（任务Y Y4b 单源化）映射单测：面板字段展开口径与
 * AlbumFilter.toAssetQuery（相册页路径，经同一函数）一致，另锁「调用方字段不触碰」——
 * 首页 COS 流依赖覆写保留 cursor/limit/cosOnly。
 */
class AssetQueryPanelDraftTest {

    /** 固定 today：与 AlbumPanelFilterTest 同口径，滚动窗口边界确定性 */
    private val today = LocalDate.of(2026, 9, 7)

    // ---------- 调用方字段保留（首页 COS 流关键契约） ----------

    @Test
    fun `覆写不触碰调用方字段 - cursor-limit-cosOnly-includeCos-mediaType 原样保留`() {
        val base = AssetQuery(
            cursor = "cur-1",
            limit = 60,
            cosOnly = true,
            includeCos = null,
            mediaType = MediaKind.IMAGE,
            q = "关键词",
        )
        val next = base.withPanelDraft(AlbumPanelDraft(), today = today)
        assertEquals("cur-1", next.cursor)
        assertEquals(60, next.limit)
        assertEquals(true, next.cosOnly)
        assertNull(next.includeCos)
        assertEquals(MediaKind.IMAGE, next.mediaType)
        assertEquals("关键词", next.q)
    }

    // ---------- 默认草稿 = 协议缺省（首页 Y4b 前行为不变锚点） ----------

    @Test
    fun `默认草稿 - 面板参数全部不传，排序为文件时间降序（2026-09-15 拍板）`() {
        val next = AssetQuery(cosOnly = true).withPanelDraft(AlbumPanelDraft(), today = today)
        assertEquals(AssetSort.FILE_DATE, next.sort) // 2026-09-15 拍板变更
        assertEquals(SortOrder.DESC, next.order)
        assertNull(next.viewRange)
        assertNull(next.playRange)
        assertNull(next.sizeRange)
        assertNull(next.dateFrom)
        assertNull(next.dateTo)
        assertNull(next.yearFrom)
        assertNull(next.yearTo)
        assertNull(next.tagIds)
        assertNull(next.tagMode)
    }

    // ---------- 档位展开（与相册页 toAssetQuery 同口径抽验） ----------

    @Test
    fun `大小与观看档 - 非全部档传入，全部档归 null`() {
        val next = AssetQuery().withPanelDraft(
            AlbumPanelDraft(sizeRange = PanelSizeRange.LT_1M, viewRange = PanelCountRange.NONE),
            today = today,
        )
        assertEquals(PanelSizeRange.LT_1M, next.sizeRange)
        assertEquals(PanelCountRange.NONE, next.viewRange)
        val all = AssetQuery().withPanelDraft(
            AlbumPanelDraft(sizeRange = PanelSizeRange.ALL, viewRange = PanelCountRange.ALL),
            today = today,
        )
        assertNull(all.sizeRange)
        assertNull(all.viewRange)
    }

    @Test
    fun `排序顺位 - 草稿值覆写进查询包`() {
        val next = AssetQuery().withPanelDraft(
            AlbumPanelDraft(sort = AssetSort.FILE_DATE, order = SortOrder.ASC),
            today = today,
        )
        assertEquals(AssetSort.FILE_DATE, next.sort)
        assertEquals(SortOrder.ASC, next.order)
    }

    @Test
    fun `时间档 - 周档展开为过去7天滚动窗口（旧版口径）`() {
        val next = AssetQuery().withPanelDraft(AlbumPanelDraft(dateRange = PanelDateRange.WEEK), today = today)
        assertEquals(LocalDate.of(2026, 8, 31), next.dateFrom)
        assertEquals(today, next.dateTo)
    }

    @Test
    fun `按年份 - 起止交叉归一，年份不全不传`() {
        val normalized = AssetQuery().withPanelDraft(
            AlbumPanelDraft(dateRange = PanelDateRange.YEAR_RANGE, yearFrom = 2023, yearTo = 2020),
            today = today,
        )
        assertEquals(2020, normalized.yearFrom)
        assertEquals(2023, normalized.yearTo)
        val missing = AssetQuery().withPanelDraft(
            AlbumPanelDraft(dateRange = PanelDateRange.YEAR_RANGE, yearFrom = 2020),
            today = today,
        )
        assertNull(missing.yearFrom)
        assertNull(missing.yearTo)
    }

    @Test
    fun `标签 - 非空传 tagIds+tagMode，空则两者一并不传`() {
        val tagged = AssetQuery().withPanelDraft(
            AlbumPanelDraft(tagIds = listOf("a", "b"), tagMode = PanelTagMode.EXACT),
            today = today,
        )
        assertEquals(listOf("a", "b"), tagged.tagIds)
        assertEquals(PanelTagMode.EXACT, tagged.tagMode)
        val untagged = AssetQuery().withPanelDraft(
            AlbumPanelDraft(tagIds = emptyList(), tagMode = PanelTagMode.EXACT),
            today = today,
        )
        assertNull(untagged.tagIds)
        assertNull(untagged.tagMode)
    }
}
