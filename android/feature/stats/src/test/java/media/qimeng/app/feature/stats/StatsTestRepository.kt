package media.qimeng.app.feature.stats

import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.*

internal fun statsOverview(totals: StatsTotals = StatsTotals(11, 7, 300)) = StatsOverviewValues(
    totalFiles = 10, imageCount = 7, stillImageCount = 5, animatedImageCount = 2, videoCount = 3,
    totalSizeBytes = 1024, todayViews = 2, totalViews = 100, avgViewsPerFile = 2.0,
    viewedFilesCount = 64, viewedAuthorsCount = 35, viewedTagsCount = 27,
    sourceNormalCount = 7, sourceCosCount = 3, windowTotals = totals,
    mediaTypeWindowTotals = mapOf("image" to StatsTotals(6), "video" to StatsTotals(3), "animated_image" to StatsTotals(2)),
    sourceWindowTotals = mapOf("normal" to StatsTotals(8), "cos" to StatsTotals(3)),
)

internal class StatsTestRepository : StatsRepository {
    val overviewCalls = mutableListOf<String>()
    val trendCalls = mutableListOf<String>()
    val rankingCalls = mutableListOf<String>()
    var overviewProvider: suspend (String) -> StatsOverviewValues = { statsOverview() }
    var trendsProvider: suspend (String) -> List<TrendPoint> = { emptyList() }
    var rankingsProvider: suspend (String, Int) -> List<RankingEntry> = { _, _ -> emptyList() }
    var secondsProvider: suspend (String, String, Int) -> List<MostViewedEntry> = { _, _, _ -> emptyList() }
    var authorsProvider: suspend (String, Int) -> List<TopAuthorEntry> = { _, _ -> emptyList() }
    var tagsProvider: suspend (String, Int) -> List<TopTagEntry> = { _, _ -> emptyList() }
    override suspend fun overview() = overview("all")
    override suspend fun overview(range: String): StatsOverviewValues { overviewCalls += range; return overviewProvider(range) }
    override suspend fun trends(range: String): List<TrendPoint> { trendCalls += range; return trendsProvider(range) }
    override suspend fun trends(range: String, mediaType: String?, source: String?): List<TrendPoint> = error("不应逐维请求")
    override suspend fun rankings(period: String, limit: Int): List<RankingEntry> { rankingCalls += period; return rankingsProvider(period, limit) }
    override suspend fun mostViewed(range: String, metric: String, limit: Int) = secondsProvider(range, metric, limit)
    override suspend fun topAuthors(range: String, limit: Int) = authorsProvider(range, limit)
    override suspend fun topTags(range: String, limit: Int) = tagsProvider(range, limit)
}
