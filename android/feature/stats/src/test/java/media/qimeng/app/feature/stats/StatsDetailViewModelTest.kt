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
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 统计详情页 ViewModel 单测（任务I I3）：模式/档位路由解析、分类型趋势多系列拼装
 * （mediaType 单值逐类型取数）、分布卡类型库存派生、空态。
 */
class StatsDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeStatsRepository : StatsRepository {
        val trendRequests = mutableListOf<Pair<String, String?>>() // range to mediaType
        var overviewValue: StatsOverviewValues? = null
        val trendsByType = mutableMapOf<String, List<TrendPoint>>()

        override suspend fun overview(): StatsOverviewValues =
            overviewValue ?: throw java.io.IOException("模拟总览失败")

        override suspend fun trends(range: String): List<TrendPoint> = trends(range, null)

        override suspend fun trends(range: String, mediaType: String?): List<TrendPoint> {
            trendRequests += range to mediaType
            return trendsByType[mediaType].orEmpty()
        }
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
    fun `分类型趋势逐 mediaType 取数拼系列`() = runTest {
        val repository = FakeStatsRepository()
        repository.trendsByType["image"] = listOf(point("07/01", 3), point("07/02", 4))
        repository.trendsByType["video"] = listOf(point("07/01", 1), point("07/02", 2))
        // animated_image 无数据 → 不出系列
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.TYPE_TREND), repository)
        advanceUntilIdle()
        assertEquals(listOf("7d", "7d", "7d"), repository.trendRequests.map { it.first })
        assertEquals(listOf("image", "video", "animated_image"), repository.trendRequests.map { it.second })
        val state = viewModel.uiState.value
        assertEquals(listOf("图片", "视频"), state.typeSeries.map { it.name })
        assertEquals(listOf(listOf(3, 4), listOf(1, 2)), state.typeSeries.map { it.values })
        assertEquals(listOf("07/01", "07/02"), state.trendLabels)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `分布模式取 overview 类型库存`() = runTest {
        val repository = FakeStatsRepository().apply {
            overviewValue = StatsOverviewValues(
                totalFiles = 10,
                imageCount = 6,
                videoCount = 3,
                totalSizeBytes = 1024L,
                todayViews = 1,
                totalViews = 42L,
            )
        }
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.DISTRIBUTION), repository)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("图片", "视频", "其他"), state.distribution.map { it.name })
        assertEquals(listOf(6, 3, 1), state.distribution.map { it.count })
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
    }

    @Test
    fun `mediaType 协议值到中文名映射`() {
        val repository = FakeStatsRepository()
        val viewModel = StatsDetailViewModel(handle(StatsDetailMode.TYPE_TREND), repository)
        assertEquals("图片", viewModel.mediaTypeDisplayName("image"))
        assertEquals("视频", viewModel.mediaTypeDisplayName("video"))
        assertEquals("动图", viewModel.mediaTypeDisplayName("animated_image"))
        assertEquals("unknown", viewModel.mediaTypeDisplayName("unknown"))
    }
}
