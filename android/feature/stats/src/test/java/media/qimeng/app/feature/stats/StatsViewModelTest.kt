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
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 统计页 ViewModel 单测：三档→range 参数集成（I3 回改后的消费侧）、数字卡 6 指标
 * （窗口三指标=趋势桶求和随档位联动；GUIDE_UI L209-211）、快速切换防覆盖（GUIDE_UI L212 序号防重）。
 */
class StatsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeStatsRepository : StatsRepository {
        val trendRequests = mutableListOf<String>()
        var overviewCalls = 0
        var overviewFailure = false
        var trendsProvider: suspend (String) -> List<TrendPoint> = { emptyList() }

        override suspend fun overview(): StatsOverviewValues {
            overviewCalls++
            if (overviewFailure) throw java.io.IOException("模拟总览失败")
            return StatsOverviewValues(9, 6, 3, 1024L, 1, 42L)
        }

        override suspend fun trends(range: String): List<TrendPoint> {
            trendRequests += range
            return trendsProvider(range)
        }
    }

    private fun point(label: String, views: Int, plays: Int = 0, seconds: Int = 0) =
        TrendPoint(label, views, plays, seconds)

    @Test
    fun `init 加载总览与默认7天档`() = runTest {
        val repository = FakeStatsRepository()
        val viewModel = StatsViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, repository.overviewCalls)
        assertEquals(listOf("7d"), repository.trendRequests)
        assertEquals(StatsRangeOption.SEVEN_DAYS, viewModel.uiState.value.selectedRange)
        assertFalse(viewModel.uiState.value.overviewLoading)
        assertFalse(viewModel.uiState.value.trendsLoading)
    }

    @Test
    fun `档位切换按三档映射发请求`() = runTest {
        val repository = FakeStatsRepository()
        val viewModel = StatsViewModel(repository)
        advanceUntilIdle()
        viewModel.selectRange(StatsRangeOption.THIRTY_DAYS)
        viewModel.selectRange(StatsRangeOption.ALL)
        advanceUntilIdle()
        // day 陷阱档：30 天 → range=day（近 30 天逐日，非单日）
        assertEquals(listOf("7d", "day", "all"), repository.trendRequests)
    }

    @Test
    fun `同档不重拉`() = runTest {
        val repository = FakeStatsRepository()
        val viewModel = StatsViewModel(repository)
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
        val viewModel = StatsViewModel(repository)
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
        val viewModel = StatsViewModel(repository)
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
        val viewModel = StatsViewModel(repository)
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
        val viewModel = StatsViewModel(repository)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.overview)
        // 总览失败不影响趋势渲染（空态文案走起）
        assertTrue(viewModel.uiState.value.trendsEmpty)
    }

    @Test
    fun `趋势失败窗口指标归零不出错`() = runTest {
        val repository = FakeStatsRepository()
        repository.trendsProvider = { emptyList() }
        val viewModel = StatsViewModel(repository)
        advanceUntilIdle()
        assertEquals(0L, viewModel.uiState.value.windowViews)
        assertEquals(0L, viewModel.uiState.value.windowSeconds)
        assertTrue(viewModel.uiState.value.trendsEmpty)
    }
}
