package media.qimeng.app.feature.stats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.model.*
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun `窗口汇总直接使用服务端不再从趋势反推`() = runTest {
        val repository = StatsTestRepository().apply { trendsProvider = { listOf(TrendPoint("桶", 999, 888, 777)) } }
        val vm = StatsViewModel(repository)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(listOf("7d"), repository.overviewCalls)
        assertEquals(11L, state.windowViews)
        assertEquals(7L, state.windowPlays)
        assertEquals(300L, state.windowSeconds)
        assertEquals(2.0, state.avgViewsPerFile!!, 0.0)
        assertFalse(state.loadFailed)
    }

    @Test fun `档位映射与同档不重拉`() = runTest {
        val repository = StatsTestRepository()
        val vm = StatsViewModel(repository)
        advanceUntilIdle()
        vm.onVisible()
        vm.selectRange(StatsRangeOption.SEVEN_DAYS)
        advanceUntilIdle()
        vm.selectRange(StatsRangeOption.THIRTY_DAYS)
        advanceUntilIdle()
        vm.selectRange(StatsRangeOption.ALL)
        advanceUntilIdle()
        assertEquals(listOf("7d", "day", "all"), repository.overviewCalls)
        assertEquals(listOf("7d", "day", "all"), repository.trendCalls)
        assertTrue(repository.rankingCalls.isEmpty())
        assertTrue(repository.discoverySeeds.isEmpty())
        assertTrue(repository.reviewCalls.isEmpty())
    }

    @Test fun `迟到旧档请求不得覆盖新档含忽略取消的底层请求`() = runTest {
        val gate = CompletableDeferred<List<TrendPoint>>()
        val repository = StatsTestRepository().apply {
            trendsProvider = { range -> if (range == "7d") withContext(NonCancellable) { gate.await() } else listOf(TrendPoint("新档", 5, 0, 0)) }
            overviewProvider = { range -> statsOverview(StatsTotals(if (range == "7d") 1 else 50)) }
        }
        val vm = StatsViewModel(repository)
        advanceUntilIdle()
        vm.selectRange(StatsRangeOption.THIRTY_DAYS)
        advanceUntilIdle()
        assertEquals(50L, vm.uiState.value.windowViews)
        gate.complete(listOf(TrendPoint("旧档", 1, 0, 0)))
        advanceUntilIdle()
        assertEquals("新档", vm.uiState.value.trends.single().label)
        assertEquals(50L, vm.uiState.value.windowViews)
    }

    @Test fun `每个数据口失败均为明确失败并允许同档重试`() = runTest {
        for (endpoint in listOf("overview", "trends", "authors", "tags")) {
            var broken = true
            fun fail(name: String) { if (broken && endpoint == name) throw java.io.IOException(name) }
            val repository = StatsTestRepository().apply {
                overviewProvider = { fail("overview"); statsOverview() }
                trendsProvider = { fail("trends"); emptyList() }
                rankingsProvider = { _, _ -> fail("rankings"); emptyList() }
                authorsProvider = { _, _ -> fail("authors"); emptyList() }
                tagsProvider = { _, _ -> fail("tags"); emptyList() }
            }
            val vm = StatsViewModel(repository)
            advanceUntilIdle()
            assertTrue(endpoint, vm.uiState.value.loadFailed)
            assertEquals(STATS_LOAD_FAILED_MESSAGE, vm.uiState.value.errorMessage)
            broken = false
            vm.retry()
            advanceUntilIdle()
            assertFalse(endpoint, vm.uiState.value.loadFailed)
            assertEquals(11L, vm.uiState.value.windowViews)
        }
    }

    @Test fun `真实空结果和零分母不等于失败`() = runTest {
        val repository = StatsTestRepository().apply {
            overviewProvider = { statsOverview(StatsTotals()).copy(avgViewsPerFile = null, viewedFilesCount = 0) }
        }
        val vm = StatsViewModel(repository)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.trendsEmpty)
        assertFalse(vm.uiState.value.loadFailed)
        assertNull(vm.uiState.value.avgViewsPerFile)
        assertEquals(0L, vm.uiState.value.windowViews)
    }

    @Test fun `作者标签保持服务端条目身份`() = runTest {
        val repository = StatsTestRepository().apply {
            authorsProvider = { _, _ -> listOf(TopAuthorEntry("author-id", "作者", 8)) }
            tagsProvider = { _, _ -> listOf(TopTagEntry("标签", 7)) }
        }
        val vm = StatsViewModel(repository)
        advanceUntilIdle()
        assertEquals(listOf("author-id", "标签"), vm.uiState.value.topAuthorsTagsMixed.map { it.id })
    }
}
