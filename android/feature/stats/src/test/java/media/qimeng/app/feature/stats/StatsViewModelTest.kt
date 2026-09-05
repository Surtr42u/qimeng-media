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
 * 统计页 ViewModel 单测：档位→range 参数集成（C1 映射的消费侧）、
 * 数字卡静态（C2）、快速切换防覆盖（GUIDE_UI §数据统计页「序号防重」）。
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

    private fun point(label: String, views: Int) = TrendPoint(label, views, 0, 0)

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
    fun `档位切换按 C1 映射发请求`() = runTest {
        val repository = FakeStatsRepository()
        val viewModel = StatsViewModel(repository)
        advanceUntilIdle()
        viewModel.selectRange(StatsRangeOption.THIRTY_DAYS)
        viewModel.selectRange(StatsRangeOption.NINETY_DAYS)
        viewModel.selectRange(StatsRangeOption.ALL)
        advanceUntilIdle()
        // day 陷阱档：30 天 → range=day（近 30 天逐日，非单日）
        assertEquals(listOf("7d", "day", "90d", "all"), repository.trendRequests)
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

        // 旧协程此刻才完成：不得覆盖 30 天档结果（序号防重）
        gate7d.complete(listOf(point("02/01", 1), point("02/02", 2)))
        advanceUntilIdle()
        assertEquals(listOf(point("03/01", 5)), viewModel.uiState.value.trends)
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
}
