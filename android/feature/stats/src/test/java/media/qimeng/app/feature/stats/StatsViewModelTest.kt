package media.qimeng.app.feature.stats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.RankingEntry
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 统计页 ViewModel 单测：三档→range 参数集成（I3 回改后的消费侧）、数字卡 6 指标
 * （窗口三指标=趋势桶求和随档位联动；GUIDE_UI L209-211）、快速切换防覆盖（GUIDE_UI L212 序号防重）、
 * 常看族装配/空态/降级（内容榜 /rankings + top-authors + top-tags + 均值联动；
 * 内容榜 2026-09-18 批换源：原 most-viewed metric=views）。
 */
class StatsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeStatsRepository : StatsRepository {
        val trendRequests = mutableListOf<String>()
        val overviewRangeCalls = mutableListOf<String?>() // null=无参版
        val rankingsPeriodCalls = mutableListOf<String>()
        var overviewFailure = false
        var avgViewsByRange: Map<String, Double?> = emptyMap()
        var trendsProvider: suspend (String) -> List<TrendPoint> = { emptyList() }
        var rankingsProvider: suspend (String, Int) -> List<RankingEntry> = { _, _ -> emptyList() }
        var topAuthorsProvider: suspend (String, Int) -> List<TopAuthorEntry> = { _, _ -> emptyList() }
        var topTagsProvider: suspend (String, Int) -> List<TopTagEntry> = { _, _ -> emptyList() }

        override suspend fun overview(): StatsOverviewValues = overviewRange(null)

        override suspend fun overview(range: String): StatsOverviewValues = overviewRange(range)

        private suspend fun overviewRange(range: String?): StatsOverviewValues {
            overviewRangeCalls += range
            if (overviewFailure) throw java.io.IOException("模拟总览失败")
            return StatsOverviewValues(
                totalFiles = 9,
                imageCount = 6,
                videoCount = 3,
                totalSizeBytes = 1024L,
                todayViews = 1,
                totalViews = 42L,
                sourceNormalCount = 7,
                sourceCosCount = 2,
                avgViewsPerFile = avgViewsByRange[range ?: "all"],
            )
        }

        override suspend fun trends(range: String): List<TrendPoint> {
            trendRequests += range
            return trendsProvider(range)
        }

        override suspend fun rankings(period: String, limit: Int): List<RankingEntry> {
            rankingsPeriodCalls += period
            return rankingsProvider(period, limit)
        }

        override suspend fun topAuthors(range: String, limit: Int): List<TopAuthorEntry> =
            topAuthorsProvider(range, limit)

        override suspend fun topTags(range: String, limit: Int): List<TopTagEntry> =
            topTagsProvider(range, limit)
    }

    private fun point(label: String, views: Int, plays: Int = 0, seconds: Int = 0) =
        TrendPoint(label, views, plays, seconds)

    @Test
    fun `init 加载总览与默认7天档`() = runTest {
        val repository = FakeStatsRepository()
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        // 库存格 overview() 一次 + 均值联动 overview(7d) 一次
        assertEquals(listOf<String?>(null, "7d"), repository.overviewRangeCalls)
        assertEquals(listOf("7d"), repository.trendRequests)
        assertEquals(StatsRangeOption.SEVEN_DAYS, viewModel.uiState.value.selectedRange)
        assertFalse(viewModel.uiState.value.overviewLoading)
        assertFalse(viewModel.uiState.value.trendsLoading)
    }

    @Test
    fun `档位切换按三档映射发请求`() = runTest {
        val repository = FakeStatsRepository()
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        viewModel.selectRange(StatsRangeOption.THIRTY_DAYS)
        viewModel.selectRange(StatsRangeOption.ALL)
        advanceUntilIdle()
        // day 陷阱档：30 天 → range=day（近 30 天逐日，非单日）
        assertEquals(listOf("7d", "day", "all"), repository.trendRequests)
        // 均值窗口随档位联动（overview(range) 通道）
        assertEquals(listOf<String?>(null, "7d", "day", "all"), repository.overviewRangeCalls)
    }

    @Test
    fun `同档不重拉`() = runTest {
        val repository = FakeStatsRepository()
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        viewModel.selectRange(StatsRangeOption.SEVEN_DAYS)
        advanceUntilIdle()
        assertEquals(1, repository.trendRequests.size)
    }

    @Test
    fun `窗口三指标等于趋势桶求和`() = runTest {
        val repository = FakeStatsRepository()
        repository.trendsProvider = {
            listOf(
                point("07/01", views = 3, plays = 1, seconds = 60),
                point("07/02", views = 4, plays = 2, seconds = 90),
                point("07/03", views = 5, plays = 3, seconds = 30),
            )
        }
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(12L, state.windowViews)
        assertEquals(6L, state.windowPlays)
        assertEquals(180L, state.windowSeconds)
    }

    @Test
    fun `窗口指标随档位联动刷新`() = runTest {
        val repository = FakeStatsRepository()
        repository.trendsProvider = { range ->
            if (range == "7d") listOf(point("07/01", 3)) else listOf(point("06/01", 10), point("06/02", 20))
        }
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        assertEquals(3L, viewModel.uiState.value.windowViews)
        viewModel.selectRange(StatsRangeOption.THIRTY_DAYS)
        advanceUntilIdle()
        // 数字卡窗口指标与趋势同请求通道：切档后随新响应一并联动（GUIDE_UI L208 全局联动）
        assertEquals(30L, viewModel.uiState.value.windowViews)
    }

    @Test
    fun `晚完成的旧档响应不覆盖新档结果`() = runTest {
        val repository = FakeStatsRepository()
        val gate7d = CompletableDeferred<List<TrendPoint>>()
        val gate30 = CompletableDeferred<List<TrendPoint>>()
        repository.trendsProvider = { range -> if (range == "7d") gate7d.await() else gate30.await() }
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle() // 首请求挂起在 gate7d
        viewModel.selectRange(StatsRangeOption.THIRTY_DAYS)
        advanceUntilIdle() // 次请求挂起在 gate30

        gate30.complete(listOf(point("03/01", 5)))
        advanceUntilIdle()
        assertEquals(listOf(point("03/01", 5)), viewModel.uiState.value.trends)
        assertEquals(5L, viewModel.uiState.value.windowViews)

        // 旧协程此刻才完成：不得覆盖 30 天档结果（序号防重，含窗口指标派生源）
        gate7d.complete(listOf(point("02/01", 1), point("02/02", 2)))
        advanceUntilIdle()
        assertEquals(listOf(point("03/01", 5)), viewModel.uiState.value.trends)
        assertEquals(5L, viewModel.uiState.value.windowViews)
        assertFalse(viewModel.uiState.value.trendsLoading)
    }

    @Test
    fun `总览失败数字卡置空而非崩溃`() = runTest {
        val repository = FakeStatsRepository().apply { overviewFailure = true }
        repository.trendsProvider = { emptyList() }
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.overview)
        // 总览失败不影响趋势渲染（空态文案走起）
        assertTrue(viewModel.uiState.value.trendsEmpty)
    }

    @Test
    fun `趋势失败窗口指标归零不出错`() = runTest {
        val repository = FakeStatsRepository()
        repository.trendsProvider = { emptyList() }
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        assertEquals(0L, viewModel.uiState.value.windowViews)
        assertEquals(0L, viewModel.uiState.value.windowSeconds)
        assertTrue(viewModel.uiState.value.trendsEmpty)
    }

    // ---------- N4 I3b：常看族装配/空态/降级（内容榜 2026-09-18 批换源 /rankings） ----------

    @Test
    fun `常看族装配 - 卡数据随档位联动 均值取overview窗口值`() = runTest {
        val repository = FakeStatsRepository()
        repository.rankingsProvider = { period, limit ->
            assertEquals("week", period) // 陷阱档：7 天 → period=week（/rankings 无 7d）
            assertEquals(TOP_CARD_LIMIT, limit)
            listOf(
                RankingEntry("id-1", "A.mp4", 12),
                RankingEntry("id-2", "B.jpg", 6),
            )
        }
        repository.topAuthorsProvider = { _, limit ->
            assertEquals(TOP_CARD_LIMIT, limit)
            listOf(TopAuthorEntry("a1", "作者甲", 8), TopAuthorEntry("a2", "作者乙", 3))
        }
        repository.topTagsProvider = { _, _ -> listOf(TopTagEntry("塞尔达", 5)) }
        repository.avgViewsByRange = mapOf("7d" to 3.5, "day" to 2.0)
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.rankings.size)
        assertEquals("A.mp4", state.rankings.first().title)
        // 混合 Top3：作者甲(8) > 塞尔达(5) > 作者乙(3)
        assertEquals(listOf("作者甲", "塞尔达", "作者乙"), state.topAuthorsTagsMixed.map { it.name })
        assertEquals(3.5, state.avgViewsPerFile!!, 0.0001)
        assertFalse(state.rankingsEmpty)
        assertFalse(state.topAuthorsTagsEmpty)

        // 切档：常看族与均值都随新档重拉（period 轴独立于 trends 的 range 轴）
        viewModel.selectRange(StatsRangeOption.THIRTY_DAYS)
        advanceUntilIdle()
        assertEquals(2.0, viewModel.uiState.value.avgViewsPerFile!!, 0.0001)
        assertEquals(listOf("week", "month"), repository.rankingsPeriodCalls)
    }

    @Test
    fun `常看族空态 - 默认空表卡片空态不隐藏`() = runTest {
        val repository = FakeStatsRepository() // 常看族全走默认空表
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state.rankingsEmpty)
        assertTrue(state.topAuthorsTagsEmpty)
        assertTrue(state.topAuthorsTagsMixed.isEmpty())
    }

    @Test
    fun `常看族降级 - 单口失败不影响其余通道`() = runTest {
        val repository = FakeStatsRepository()
        repository.rankingsProvider = { _, _ -> throw java.io.IOException("rankings 失败") }
        repository.topAuthorsProvider = { _, _ -> throw java.io.IOException("top-authors 失败") }
        repository.topTagsProvider = { _, _ -> listOf(TopTagEntry("只有标签", 2)) }
        repository.trendsProvider = { listOf(point("07/01", 4)) }
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        val state = viewModel.uiState.value
        // 失败口降级空表（卡内空态），趋势与标签照常落地——不互相拖垮
        assertTrue(state.rankings.isEmpty())
        assertTrue(state.topAuthors.isEmpty())
        assertEquals(listOf("只有标签"), state.topAuthorsTagsMixed.map { it.name })
        assertEquals(4L, state.windowViews)
    }

    @Test
    fun `均值降级 - overview窗口失败或null置占位`() = runTest {
        val repository = FakeStatsRepository().apply {
            overviewFailure = true
        }
        repository.trendsProvider = { listOf(point("07/01", 1)) }
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()
        // 库存格失败（overview null）+ 均值口失败（null）→ 占位语义，不崩溃
        assertNull(viewModel.uiState.value.overview)
        assertNull(viewModel.uiState.value.avgViewsPerFile)
        assertFalse(viewModel.uiState.value.avgViewsLoading)
        // 分母 0 → 协议 null：同走「—」占位（加载完成 + null 值分支）
        val repository2 = FakeStatsRepository().apply { avgViewsByRange = mapOf("7d" to null) }
        val viewModel2 = StatsViewModel(repository2, MediaBatchIndex())
        advanceUntilIdle()
        assertNull(viewModel2.uiState.value.avgViewsPerFile)
        assertFalse(viewModel2.uiState.value.avgViewsLoading)
    }

    // ---------- 任务J J1：详情页跳转链（GUIDE_UI L218-224） ----------

    @Test
    fun `内容榜条目点击写批次上下文 - 快照等于当前榜单清单`() = runTest {
        val repository = FakeStatsRepository()
        repository.rankingsProvider = { _, _ ->
            listOf(
                RankingEntry("id-1", "A.mp4", 12),
                RankingEntry("id-2", "B.jpg", 6),
                RankingEntry("id-3", "C.mp4", 3),
            )
        }
        val batchIndex = MediaBatchIndex()
        val viewModel = StatsViewModel(repository, batchIndex)
        advanceUntilIdle()

        viewModel.enterDetail("id-2")
        // 批次上下文 = 当前内容榜卡榜单整体（「已加载=当前显示清单」口径，快照式整体替换）
        assertEquals(listOf("id-1", "id-2", "id-3"), batchIndex.ids)
        assertEquals(1, batchIndex.indexOf("id-2")) // 详情页 i/N 序号定位正确
    }

    @Test
    fun `混合卡条目意图构造 - 作者带真实id标签带词`() = runTest {
        val repository = FakeStatsRepository()
        repository.topAuthorsProvider = { _, _ ->
            listOf(TopAuthorEntry("author-uuid-1", "作者甲", 8), TopAuthorEntry("author-uuid-2", "作者乙", 3))
        }
        repository.topTagsProvider = { _, _ -> listOf(TopTagEntry("塞尔达", 5)) }
        val viewModel = StatsViewModel(repository, MediaBatchIndex())
        advanceUntilIdle()

        val mixed = viewModel.uiState.value.topAuthorsTagsMixed
        // 作者甲(8) > 塞尔达(5) > 作者乙(3)；作者条目 kind=AUTHOR 且 id=真实 authorId
        //（/stats/top-authors 响应字段，跳作者集合页取数键），标签条目 kind=TAG 且 id=词
        assertEquals(
            listOf(TopAuthorTagEntry.Kind.AUTHOR, TopAuthorTagEntry.Kind.TAG, TopAuthorTagEntry.Kind.AUTHOR),
            mixed.map { it.kind },
        )
        assertEquals(listOf("author-uuid-1", "塞尔达", "author-uuid-2"), mixed.map { it.id })
        assertEquals(listOf("作者甲", "塞尔达", "作者乙"), mixed.map { it.name })
    }
}
