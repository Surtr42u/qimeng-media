package media.qimeng.app.feature.stats

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.model.*
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatsDetailViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private fun handle(mode: StatsDetailMode, range: StatsRangeOption = StatsRangeOption.SEVEN_DAYS) =
        SavedStateHandle(mapOf(StatsDetailRoutes.KEY_MODE to mode.name, StatsDetailRoutes.KEY_RANGE to range.name))

    @Test fun `一次响应提供同轴类型与来源全部系列`() = runTest {
        val repository = StatsTestRepository().apply {
            trendsProvider = { listOf(
                TrendPoint("较早", 3, 0, 0, start = "2026-01-01", end = "2026-01-31",
                    mediaTypes = mapOf("image" to StatsTotals(3), "video" to StatsTotals(), "animated_image" to StatsTotals()),
                    sources = mapOf("normal" to StatsTotals(3), "cos" to StatsTotals())),
                TrendPoint("较晚", 9, 0, 0, start = "2026-02-01", end = "2026-02-28",
                    mediaTypes = mapOf("image" to StatsTotals(), "video" to StatsTotals(7), "animated_image" to StatsTotals(2)),
                    sources = mapOf("normal" to StatsTotals(2), "cos" to StatsTotals(7))),
            ) }
        }
        val vm = StatsDetailViewModel(handle(StatsDetailMode.TYPE_TREND, StatsRangeOption.ALL), repository, MediaBatchIndex())
        advanceUntilIdle()
        assertEquals(listOf("all"), repository.trendCalls)
        assertEquals(listOf("较早", "较晚"), vm.uiState.value.trendLabels)
        assertEquals(listOf(0, 7), vm.uiState.value.typeSeries.first { it.mediaType == "video" }.values)
        assertEquals(listOf(0, 7), vm.uiState.value.sourceSeries.first { it.mediaType == "cos" }.values)
        assertFalse(vm.uiState.value.loadFailed)
    }

    @Test fun `文件数量为精确值而非均值反解或截断榜长度`() = runTest {
        val repository = StatsTestRepository().apply {
            overviewProvider = { statsOverview().copy(avgViewsPerFile = null, viewedFilesCount = 64) }
            rankingsProvider = { period, limit ->
                assertEquals("week", period); assertEquals(20, limit)
                listOf(RankingEntry("hot", "热度", 5))
            }
            secondsProvider = { range, metric, _ ->
                assertEquals("7d", range); assertEquals("seconds", metric)
                listOf(MostViewedEntry("long", "停留", "video", null, 900))
            }
        }
        val batch = MediaBatchIndex()
        val vm = StatsDetailViewModel(handle(StatsDetailMode.MOST_VIEWED), repository, batch)
        advanceUntilIdle()
        assertEquals(64, vm.uiState.value.filesWithViewRecords)
        assertEquals(11, vm.uiState.value.windowViewCount)
        vm.enterDetail("hot"); assertEquals(listOf("hot"), batch.ids)
        vm.toggleFilesSort(); vm.enterDetail("long"); assertEquals(listOf("long"), batch.ids)
    }

    @Test fun `分布读互斥类型精确数量和服务端窗口汇总`() = runTest {
        val repository = StatsTestRepository()
        val vm = StatsDetailViewModel(handle(StatsDetailMode.DISTRIBUTION), repository, MediaBatchIndex())
        advanceUntilIdle()
        assertEquals(listOf("图片", "视频", "动图"), vm.uiState.value.distribution.map { it.name })
        assertEquals(listOf(5, 3, 2), vm.uiState.value.distribution.map { it.count })
        assertEquals(mapOf("image" to 6, "video" to 3, "animated_image" to 2), vm.uiState.value.typeWindowViews)
        assertEquals(mapOf("normal" to 8, "cos" to 3), vm.uiState.value.sourceWindowViews)
        assertTrue(repository.trendCalls.isEmpty())
    }

    @Test fun `作者标签摘要保留未截断总数`() = runTest {
        val repository = StatsTestRepository().apply {
            authorsProvider = { _, limit -> assertEquals(15, limit); listOf(TopAuthorEntry("a", "甲", 2)) }
            tagsProvider = { _, limit -> assertEquals(10, limit); listOf(TopTagEntry("标签", 1)) }
        }
        val vm = StatsDetailViewModel(handle(StatsDetailMode.AUTHORS_TAGS), repository, MediaBatchIndex())
        advanceUntilIdle()
        assertEquals(35, vm.uiState.value.overviewValues?.viewedAuthorsCount)
        assertEquals(27, vm.uiState.value.overviewValues?.viewedTagsCount)
        assertEquals(1, vm.uiState.value.topAuthors.size)
    }

    @Test fun `各模式失败不是空态并可同档重试`() = runTest {
        for (mode in StatsDetailMode.entries) {
            var broken = true
            val repository = StatsTestRepository().apply {
                reviewProvider = { if (broken) throw java.io.IOException("offline"); statsReview(it) }
                overviewProvider = { if (broken) throw java.io.IOException("offline"); statsOverview() }
                trendsProvider = { if (broken) throw java.io.IOException("offline"); emptyList() }
            }
            val vm = StatsDetailViewModel(handle(mode), repository, MediaBatchIndex())
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loadFailed)
            assertFalse(vm.uiState.value.isEmpty)
            broken = false; vm.retry(); advanceUntilIdle()
            assertFalse(vm.uiState.value.loadFailed)
        }
    }

    @Test fun `浏览回顾按入口时段独立加载且零记录仍显示报告`() = runTest {
        val repository = StatsTestRepository().apply {
            reviewProvider = { statsReview(it).copy(viewCount = 0, firstViews = 0, repeatViews = 0, uniqueFiles = 0, activeDays = 0, comparisonAvailable = false, hours = (0..23).map { ReviewHourValues(it, 0) }) }
        }
        val vm = StatsDetailViewModel(handle(StatsDetailMode.REVIEW, StatsRangeOption.THIRTY_DAYS), repository, MediaBatchIndex())
        advanceUntilIdle()
        assertEquals(listOf("day"), repository.reviewCalls)
        assertTrue(repository.overviewCalls.isEmpty())
        assertTrue(repository.trendCalls.isEmpty())
        assertFalse(vm.uiState.value.isEmpty)
        assertEquals(0L, vm.uiState.value.review?.viewCount)
    }

    @Test fun `空趋势为成功空态非法路由回落默认`() = runTest {
        val vm = StatsDetailViewModel(SavedStateHandle(mapOf(StatsDetailRoutes.KEY_MODE to "bad", StatsDetailRoutes.KEY_RANGE to "bad")), StatsTestRepository(), MediaBatchIndex())
        advanceUntilIdle()
        assertEquals(StatsDetailMode.TYPE_TREND, vm.mode)
        assertEquals(DEFAULT_STATS_RANGE, vm.range)
        assertTrue(vm.uiState.value.isEmpty)
        assertFalse(vm.uiState.value.loadFailed)
    }
}
