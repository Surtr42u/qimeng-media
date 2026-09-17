package media.qimeng.app.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 万能筛选面板（M4-2A-B3）映射单测：每组单选 → AssetQuery 断言、默认值不传、
 * tagIds 传递、时间档日期区间计算（旧版滚动窗口口径，today 注入锁定边界）。
 */
class AlbumPanelFilterTest {

    /** 固定 today：避免依赖真实时钟；2026-09-07 前推 7/30/90/365 天边界全部确定性 */
    private val today = LocalDate.of(2026, 9, 7)

    private fun query(state: AlbumFilterState) = AlbumFilter.toAssetQuery(state, today = today)

    // ---------- 默认值不传 ----------

    @Test
    fun `默认态 - 面板参数全部不传，排序为默认档降序（2026-09-17 拍板）`() {
        val q = query(AlbumFilterState())
        assertEquals(AssetSort.FILE_DATE, q.sort) // 2026-09-17 307 笔：「默认」档发参=媒体文件时间
        assertEquals(SortOrder.DESC, q.order)
        assertNull(q.viewRange)
        assertNull(q.playRange)
        assertNull(q.sizeRange)
        assertNull(q.dateFrom)
        assertNull(q.dateTo)
        assertNull(q.yearFrom)
        assertNull(q.yearTo)
        assertNull(q.tagIds)
        assertNull(q.tagMode)
    }

    // ---------- 排序 / 顺位 ----------

    @Test
    fun `排序键映射 - 逐一映射到 sort（协议镜像全枚举；面板档位 2026-09-17 精简为三档但映射不变）`() {
        val cases = mapOf(
            AssetSort.DEFAULT to AssetSort.FILE_DATE, // 307 笔：「默认」休息档发参翻译为媒体文件时间
            AssetSort.FILE_DATE to AssetSort.FILE_DATE,
            AssetSort.ADDED_DATE to AssetSort.ADDED_DATE,
            AssetSort.VIEW_COUNT to AssetSort.VIEW_COUNT,
            AssetSort.PLAY_COUNT to AssetSort.PLAY_COUNT,
            AssetSort.SIZE_BYTES to AssetSort.SIZE_BYTES,
            AssetSort.NAME to AssetSort.NAME,
        )
        cases.forEach { (selected, expected) ->
            assertEquals(expected, query(AlbumFilterState(sort = selected)).sort)
        }
    }

    @Test
    fun `顺位二选 - 升序映射 ASC`() {
        assertEquals(SortOrder.ASC, query(AlbumFilterState(order = SortOrder.ASC)).order)
    }

    // ---------- 观看 / 点击次数档 ----------

    @Test
    fun `观看次数档 - 全部不传，其余四档逐一映射`() {
        assertNull(query(AlbumFilterState(viewRange = PanelCountRange.ALL)).viewRange)
        assertEquals(PanelCountRange.NONE, query(AlbumFilterState(viewRange = PanelCountRange.NONE)).viewRange)
        assertEquals(PanelCountRange.LOW, query(AlbumFilterState(viewRange = PanelCountRange.LOW)).viewRange)
        assertEquals(PanelCountRange.MID, query(AlbumFilterState(viewRange = PanelCountRange.MID)).viewRange)
        assertEquals(PanelCountRange.HIGH, query(AlbumFilterState(viewRange = PanelCountRange.HIGH)).viewRange)
    }

    @Test
    fun `点击次数档 - 全部不传，未点击为 NONE`() {
        assertNull(query(AlbumFilterState(playRange = PanelCountRange.ALL)).playRange)
        assertEquals(PanelCountRange.NONE, query(AlbumFilterState(playRange = PanelCountRange.NONE)).playRange)
        assertEquals(PanelCountRange.HIGH, query(AlbumFilterState(playRange = PanelCountRange.HIGH)).playRange)
    }

    // ---------- 大小档 ----------

    @Test
    fun `大小档 - 全部不传，四档逐一映射`() {
        assertNull(query(AlbumFilterState(sizeRange = PanelSizeRange.ALL)).sizeRange)
        assertEquals(PanelSizeRange.LT_1M, query(AlbumFilterState(sizeRange = PanelSizeRange.LT_1M)).sizeRange)
        assertEquals(PanelSizeRange.M_1_TO_10, query(AlbumFilterState(sizeRange = PanelSizeRange.M_1_TO_10)).sizeRange)
        assertEquals(PanelSizeRange.M_10_TO_50, query(AlbumFilterState(sizeRange = PanelSizeRange.M_10_TO_50)).sizeRange)
        assertEquals(PanelSizeRange.GT_50M, query(AlbumFilterState(sizeRange = PanelSizeRange.GT_50M)).sizeRange)
    }

    // ---------- 时间档 ----------

    @Test
    fun `时间档日期区间 - 今天为当日闭区间`() {
        val q = query(AlbumFilterState(dateRange = PanelDateRange.TODAY))
        assertEquals(today, q.dateFrom)
        assertEquals(today, q.dateTo)
    }

    @Test
    fun `时间档日期区间 - 本周为过去7天滚动窗口（旧版口径，非自然周）`() {
        val q = query(AlbumFilterState(dateRange = PanelDateRange.WEEK))
        assertEquals(LocalDate.of(2026, 8, 31), q.dateFrom)
        assertEquals(today, q.dateTo)
    }

    @Test
    fun `时间档日期区间 - 本月为过去30天滚动窗口（旧版口径）`() {
        val q = query(AlbumFilterState(dateRange = PanelDateRange.MONTH))
        assertEquals(LocalDate.of(2026, 8, 8), q.dateFrom)
        assertEquals(today, q.dateTo)
    }

    @Test
    fun `时间档日期区间 - 近三月为过去90天滚动窗口（旧版口径，非自然月）`() {
        val q = query(AlbumFilterState(dateRange = PanelDateRange.QUARTER))
        assertEquals(LocalDate.of(2026, 6, 9), q.dateFrom)
        assertEquals(today, q.dateTo)
    }

    @Test
    fun `时间档日期区间 - 本年为过去365天滚动窗口（旧版口径，非自然年）`() {
        val q = query(AlbumFilterState(dateRange = PanelDateRange.YEAR))
        assertEquals(LocalDate.of(2025, 9, 7), q.dateFrom)
        assertEquals(today, q.dateTo)
    }

    @Test
    fun `时间档 - 全部与按年份不产生日期区间参数`() {
        assertNull(query(AlbumFilterState(dateRange = PanelDateRange.ALL)).dateFrom)
        assertNull(query(AlbumFilterState(dateRange = PanelDateRange.ALL)).dateTo)
        assertNull(query(AlbumFilterState(dateRange = PanelDateRange.YEAR_RANGE)).dateFrom)
        assertNull(query(AlbumFilterState(dateRange = PanelDateRange.YEAR_RANGE)).dateTo)
    }

    // ---------- 按年份 ----------

    @Test
    fun `按年份 - 仅该档传 yearFrom-yearTo 且起止交叉归一（旧版 footer 口径）`() {
        val q = query(
            AlbumFilterState(dateRange = PanelDateRange.YEAR_RANGE, yearFrom = 2020, yearTo = 2023),
        )
        assertEquals(2020, q.yearFrom)
        assertEquals(2023, q.yearTo)
        val reversed = query(
            AlbumFilterState(dateRange = PanelDateRange.YEAR_RANGE, yearFrom = 2023, yearTo = 2020),
        )
        assertEquals(2020, reversed.yearFrom)
        assertEquals(2023, reversed.yearTo)
    }

    @Test
    fun `按年份 - 起止不齐备或非该档时不传年份参数`() {
        val missingEnd = query(AlbumFilterState(dateRange = PanelDateRange.YEAR_RANGE, yearFrom = 2020))
        assertNull(missingEnd.yearFrom)
        assertNull(missingEnd.yearTo)
        val notYearRange = query(AlbumFilterState(dateRange = PanelDateRange.MONTH, yearFrom = 2020, yearTo = 2023))
        assertNull(notYearRange.yearFrom)
        assertNull(notYearRange.yearTo)
    }

    // ---------- 标签 ----------

    @Test
    fun `标签 - 选中即传 tagIds 与 tagMode，模糊默认`() {
        val q = query(
            AlbumFilterState(tagIds = listOf("a", "b"), tagMode = PanelTagMode.FUZZY),
        )
        assertEquals(listOf("a", "b"), q.tagIds)
        assertEquals(PanelTagMode.FUZZY, q.tagMode)
    }

    @Test
    fun `标签 - 精确模式映射 EXACT，空选中则 tagMode 一并不传`() {
        assertEquals(
            PanelTagMode.EXACT,
            query(AlbumFilterState(tagIds = listOf("a"), tagMode = PanelTagMode.EXACT)).tagMode,
        )
        val empty = query(AlbumFilterState(tagIds = emptyList(), tagMode = PanelTagMode.EXACT))
        assertNull(empty.tagIds)
        assertNull(empty.tagMode)
    }

    // ---------- 草稿拷贝往返 ----------

    @Test
    fun `草稿语义 - withPanelDraft 只覆盖面板字段，四维芯片与展开态保留`() {
        val applied = AlbumFilterState(
            partition = Zone.COS,
            mediaType = MediaKind.VIDEO,
            expanded = false,
            sort = AssetSort.VIEW_COUNT,
            viewRange = PanelCountRange.NONE,
            tagIds = listOf("a"),
        )
        val next = applied.withPanelDraft(
            AlbumPanelDraft(sort = AssetSort.NAME, viewRange = PanelCountRange.ALL, tagIds = emptyList()),
        )
        assertEquals(Zone.COS, next.partition)
        assertEquals(MediaKind.VIDEO, next.mediaType)
        assertEquals(false, next.expanded)
        assertEquals(AssetSort.NAME, next.sort)
        assertEquals(PanelCountRange.ALL, next.viewRange)
        assertEquals(emptyList<String>(), next.tagIds)
    }

    @Test
    fun `草稿语义 - panelDraft 拷贝已应用面板值且与源独立`() {
        val applied = AlbumFilterState(sort = AssetSort.NAME, tagIds = listOf("a"))
        val draft = applied.panelDraft()
        assertEquals(AssetSort.NAME, draft.sort)
        assertEquals(listOf("a"), draft.tagIds)
        // 草稿改动不回写源（面板内只改草稿）
        val mutated = applied.copy(sort = AssetSort.SIZE_BYTES)
        assertEquals(AssetSort.NAME, draft.sort)
        assertEquals(AssetSort.SIZE_BYTES, mutated.sort)
    }

    @Test
    fun `草稿语义 - 年份字段 panelDraft 与 withPanelDraft 往返保真`() {
        val applied = AlbumFilterState(
            dateRange = PanelDateRange.YEAR_RANGE,
            yearFrom = 2020,
            yearTo = 2026,
        )
        val roundTrip = AlbumFilterState().withPanelDraft(applied.panelDraft())
        assertEquals(PanelDateRange.YEAR_RANGE, roundTrip.dateRange)
        assertEquals(2020, roundTrip.yearFrom)
        assertEquals(2026, roundTrip.yearTo)
    }

    // ---------- 筛选钮点亮判定（U10-2b：hasActiveFilters 默认全假、逐字段置真各一） ----------

    @Test
    fun `点亮判定 - 默认态不点亮`() {
        assertFalse(AlbumFilterState().hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 排序偏离默认点亮`() {
        // 2026-09-17 拍板变更：默认=DEFAULT（协议缺省档），偏离档用观看次数
        assertTrue(AlbumFilterState(sort = AssetSort.VIEW_COUNT).hasActiveFilters())
        assertFalse(AlbumFilterState(sort = AssetSort.DEFAULT).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 顺位偏离默认点亮`() {
        assertTrue(AlbumFilterState(order = SortOrder.ASC).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 观看档非全部点亮`() {
        assertTrue(AlbumFilterState(viewRange = PanelCountRange.LOW).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 点击档非全部点亮`() {
        assertTrue(AlbumFilterState(playRange = PanelCountRange.HIGH).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 大小档非全部点亮`() {
        assertTrue(AlbumFilterState(sizeRange = PanelSizeRange.LT_1M).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 时间档非全部点亮`() {
        assertTrue(AlbumFilterState(dateRange = PanelDateRange.TODAY).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 年份置值点亮`() {
        assertTrue(AlbumFilterState(yearFrom = PANEL_MIN_YEAR).hasActiveFilters())
        assertTrue(AlbumFilterState(yearTo = 2026).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 标签非空点亮`() {
        assertTrue(AlbumFilterState(tagIds = listOf("tag-1")).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 仅改标签匹配模式不点亮（默认档不改变查询结果）`() {
        assertFalse(AlbumFilterState(tagMode = PanelTagMode.EXACT).hasActiveFilters())
    }

    @Test
    fun `点亮判定 - 四维芯片与展开态不点亮（面板字段专属语义）`() {
        val state = AlbumFilterState(
            partition = Zone.COS,
            mediaType = MediaKind.VIDEO,
            expanded = true,
        )
        assertFalse(state.hasActiveFilters())
    }
}
