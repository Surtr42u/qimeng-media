package media.qimeng.app.feature.stats

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.MostViewedEntry
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 统计详情页 ViewModel 单测（任务I I3 + N4 I3b）：模式/档位路由解析、分类型趋势多系列拼装
 * （mediaType 单值逐类型取数）、来源趋势双系列（N3 #31b）、常看文件 seconds 榜、
 * 常看作者标签双卡、分布卡类型/来源库存派生、空态。
 */
class StatsDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeStatsRepository : StatsRepository {
        /** range to (mediaType to source) 请求对 */
        val trendRequests = mutableListOf<Triple<String, String?, String?>>()
        var overviewValue: StatsOverviewValues? = null
        val trendsByType = mutableMapOf<String, List<TrendPoint>>()
        val trendsBySource = mutableMapOf<String, List<TrendPoint>>()
        var mostViewedProvider: suspend (String, String, Int) -> List<MostViewedEntry> = { _, _, _ -> emptyList() }
        var topAuthorsProvider: suspend (String, Int) -> List<TopAuthorEntry> = { _, _ -> emptyList() }
        var topTagsProvider: suspend (String, Int) -> List<TopTagEntry> = { _, _ -> emptyList() }

        override suspend fun overview(): StatsOverviewValues = overviewRange(null)

        override suspend fun overview(range: String): StatsOverviewValues = overviewRange(range)

        private fun overviewRange(@Suppress("UNUSED_PARAMETER") range: String?): StatsOverviewValues =
            overviewValue ?: throw java.io.IOException("模拟总览失败")

        override suspend fun trends(range: String): List<TrendPoint> = trends(range, null)

        override suspend fun trends(range: String, mediaType: String?): List<TrendPoint> =
            trends(range, mediaType, null)

        override suspend fun trends(range: String, mediaType: String?, source: String?): List<TrendPoint> {
            trendRequests += Triple(range, mediaType, source)
            return when {
                mediaType != null -> trendsByType[mediaType].orEmpty()
                source != null -> trendsBySource[source].orEmpty()
                else -> emptyList()
            }
        }

        override suspend fun mostViewed(range: String, metric: String, limit: Int): List<MostViewedEntry> =
            mostViewedProvider(range, metric, limit)

        override suspend fun topAuthors(range: String, limit: Int): List<TopAuthorEntry> =
            topAuthorsProvider(range, limit)

        override suspend fun topTags(range: String, limit: Int): List<TopTagEntry> =
            topTagsProvider(range, limit)
    }

    private fun handle(mode: StatsDetailMode, range: StatsRangeOption = StatsRangeOption.SEVEN_DAYS) =
        SavedStateHandle(
            mapOf(
                StatsDetailRoutes.KEY_MODE to mode.name,
                StatsDetailRoutes.KEY_RANGE to range.name,
            ),
        )

    private fun point(label: String, views: Int) = TrendPoint(label, views, 0, 0)

    @Test
    fun `路由参数解析模式与档位`() = runTest {
        val viewModel = StatsDetailViewModel(
            handle(StatsDetailMode.DISTRIBUTION, StatsRangeOption.ALL),
            FakeStatsRepository(),
        )
        advanceUntilIdle()
        assertEquals(StatsDetailMode.DISTRIBUTION, viewModel.mode)
        assertEquals(StatsRangeOption.ALL, viewModel.range)
    }

    @Test
    fun `非法路由值回落默认`() = runTest {
        val viewModel = StatsDetailViewModel(
            SavedStateHandle(
                mapOf(
                    StatsDetailRoutes.KEY_MODE to "HACKED",
                    StatsDetailRoutes.KEY_RANGE to "365d",
                ),
            ),
            FakeStatsRepository(),
        )
        advanceUntilIdle()
        assertEquals(StatsDetailMode.TYPE_TREND, viewModel.mode)
        assertEquals(StatsRangeOption.SEVEN_DAYS, viewModel.range)
    }

    @Test
    fun `分类型趋势逐 mediaType 取数拼系列 来源趋势双系列`() = runTest {
        val repository = FakeStatsRepository()
        repository.trendsByType["image"] = listOf(point("07/01", 3), point("07/02", 4))
        repository.trendsByType["video"] = listOf(point("07/01", 1), point("07/02", 2))
        repository.trendsBySource["normal"] = listOf(point("07/01", 8), point("07/02", 9))
        // animated_image 与 cos 无数据 → 不出系列
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.TYPE_TREND), repository)
        advanceUntilIdle()
        // 类型三路（mediaType 单值）+ 来源两路（source 单值 normal/cos）
        assertEquals(5, repository.trendRequests.size)
        assertEquals(
            listOf("image", "video", "animated_image"),
            repository.trendRequests.map { it.second }.filterNotNull(),
        )
        assertEquals(
            listOf("normal", "cos"),
            repository.trendRequests.map { it.third }.filterNotNull(),
        )
        val state = viewModel.uiState.value
        assertEquals(listOf("图片", "视频"), state.typeSeries.map { it.name })
        // cos 窗口内无数据 → 不出系列（轴以有数系列为准）
        assertEquals(listOf("常规"), state.sourceSeries.map { it.name })
        assertEquals(listOf(listOf(8, 9)), state.sourceSeries.map { it.values })
        assertEquals(listOf("07/01", "07/02"), state.trendLabels)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `常看文件模式取seconds榜`() = runTest {
        val repository = FakeStatsRepository()
        repository.mostViewedProvider = { range, metric, limit ->
            assertEquals("7d", range)
            assertEquals("seconds", metric)
            assertEquals(20, limit)
            listOf(
                MostViewedEntry("id-1", "长看.mp4", "video", null, 300),
                MostViewedEntry("id-2", "短看.jpg", "image", null, 45),
            )
        }
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.MOST_VIEWED), repository)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(2, state.secondsRanking.size)
        assertEquals("长看.mp4", state.secondsRanking.first().fileName)
        assertEquals(300, state.secondsRanking.first().value)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `常看作者标签模式双卡取数`() = runTest {
        val repository = FakeStatsRepository()
        repository.topAuthorsProvider = { range, limit ->
            assertEquals("7d", range)
            assertEquals(15, limit)
            listOf(TopAuthorEntry("cos_测试作者一", "测试作者一", 14))
        }
        repository.topTagsProvider = { range, limit ->
            assertEquals("7d", range)
            assertEquals(10, limit)
            listOf(TopTagEntry("手绘", 9))
        }
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.AUTHORS_TAGS), repository)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("测试作者一"), state.topAuthors.map { it.displayName })
        assertEquals(listOf("手绘"), state.topTags.map { it.tag })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `分布模式取 overview 类型与来源库存`() = runTest {
        val repository = FakeStatsRepository().apply {
            overviewValue = StatsOverviewValues(
                totalFiles = 10,
                imageCount = 6,
                videoCount = 3,
                totalSizeBytes = 1024L,
                todayViews = 1,
                totalViews = 42L,
                sourceNormalCount = 7,
                sourceCosCount = 3,
            )
        }
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.DISTRIBUTION), repository)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("图片", "视频", "其他"), state.distribution.map { it.name })
        assertEquals(listOf(6, 3, 1), state.distribution.map { it.count })
        // 来源构成（N3 #31b 解冻）：overview sourceCounts 派生
        assertEquals(listOf("常规", "COS"), state.sourceDistribution.map { it.name })
        assertEquals(listOf(7, 3), state.sourceDistribution.map { it.count })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `分布无其他类时不渲染其他行`() = runTest {
        val repository = FakeStatsRepository().apply {
            overviewValue = StatsOverviewValues(9, 6, 3, 1024L, 1, 42L)
        }
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.DISTRIBUTION), repository)
        advanceUntilIdle()
        assertEquals(listOf("图片", "视频"), viewModel.uiState.value.distribution.map { it.name })
    }

    @Test
    fun `全空数据走空态`() = runTest {
        val repository = FakeStatsRepository() // 趋势与总览全空
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.TYPE_TREND), repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isEmpty)
        // 分布模式：overview 失败=失败态（同样给「暂无数据」文案）
        val viewModel2 = StatsDetailViewModel(handle(StatsDetailMode.DISTRIBUTION), repository)
        advanceUntilIdle()
        assertNull(viewModel2.uiState.value.distribution.takeIf { it.isNotEmpty() })
        assertTrue(viewModel2.uiState.value.loadFailed)
        // 常看文件模式：空榜=空态（空态保留口径）
        val viewModel3 = StatsDetailViewModel(handle(StatsDetailMode.MOST_VIEWED), repository)
        advanceUntilIdle()
        assertTrue(viewModel3.uiState.value.isEmpty)
    }

    @Test
    fun `mediaType 与 source 协议值到中文名映射`() {
        val repository = FakeStatsRepository()
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.TYPE_TREND), repository)
        assertEquals("图片", viewModel.mediaTypeDisplayName("image"))
        assertEquals("视频", viewModel.mediaTypeDisplayName("video"))
        assertEquals("动图", viewModel.mediaTypeDisplayName("animated_image"))
        assertEquals("unknown", viewModel.mediaTypeDisplayName("unknown"))
        assertEquals("常规", viewModel.sourceDisplayName("normal"))
        assertEquals("COS", viewModel.sourceDisplayName("cos"))
        assertEquals("unknown", viewModel.sourceDisplayName("unknown"))
    }
}
