package media.qimeng.app.feature.stats

import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry

// 只把服务端结果映射为展示行/文案，加载与错误状态仍由StatsDetailViewModel持有。
// 页面继续独占趋势选择和排序交互，禁止在本文件发请求或重算领域统计。
internal data class SummaryCellUi(val label: String, val value: String)

internal data class RankRowUi(
    val rank: Int?,
    val title: String,
    val subtitle: String?,
    val valueText: String?,
    val progressValue: Long,
    val progressMax: Long,
    val onClick: (() -> Unit)? = null,
)

internal data class DistributionMetricUi(val name: String, val valueText: String, val percent: Int)

internal data class DistributionRowUi(val label: String, val metrics: List<DistributionMetricUi>)

// ==================== 模式内容装配（旧版 renderFavorites/renderAuthorsTags/renderDistribution 数据口径） ====================

/** 档位 → 洞察行「近X」用字（旧版 rangeLabel 逐字：7天/30天/全部时间，注意「全部时间」非「全部」） */
private fun rangeLabel(range: StatsRangeOption): String = when (range) {
    StatsRangeOption.SEVEN_DAYS -> "7天"
    StatsRangeOption.THIRTY_DAYS -> "30天"
    StatsRangeOption.ALL -> "全部时间"
}

/** 常看文件摘要格（旧版 renderFavorites renderSummary 四格：总浏览/浏览时长/常看文件/平均浏览次数） */
internal fun mostViewedSummaryCells(state: StatsDetailUiState): List<SummaryCellUi> = listOf(
    SummaryCellUi("总浏览", formatCountDetail(state.windowViewCount)),
    SummaryCellUi("浏览时长", formatDurationDetail(state.windowSeconds)),
    SummaryCellUi("常看文件", formatCountDetail(state.filesWithViewRecords)),
    SummaryCellUi(
        "平均浏览次数",
        // 旧版恒 "%.1f"（无数据给 0.0）；协议分母 0 → null，与主页同语义给「—」占位
        state.overviewValues?.avgViewsPerFile?.let { formatAvgViewsDetail(it) } ?: DETAIL_UNAVAILABLE_TEXT,
    ),
)

/** 常看文件洞察行（旧版逐字：「近X共浏览 N 次，停留 D」） */
internal fun mostViewedInsightLines(state: StatsDetailUiState, range: StatsRangeOption): List<String> = listOf(
    "近${rangeLabel(range)}共浏览 ${state.windowViewCount} 次，" +
        "停留 ${formatDurationDetail(state.windowSeconds)}",
)

/**
 * 常看文件排行行（热度/时长双档同构）：
 * 按热度=内容榜 GET /rankings（2026-09-18 批换源：原 most-viewed views 榜——值=累计浏览次数
 * 「N 次」，副标题「浏览 N 次 · 停留 D」，热度排序口径=浏览+播放+点赞累计）；按时长=seconds 榜
 * （值=人读时长，副标题「浏览 N 次」）。停留时长按 assetId 从 seconds 榜 join——协议无单文件
 * dwell 直出，Top20 之外的文件 join 不到则省略「· 停留」段（记档交付报告）。
 * 回调只携带 assetId：双榜条目类型不同（RankingEntry vs MostViewedEntry），跳转只需 id。
 */
internal fun mostViewedRankRows(
    state: StatsDetailUiState,
    sortByHeat: Boolean,
    onEntryClick: (String) -> Unit,
): List<RankRowUi> {
    val dwellByAsset = state.secondsRanking.associate { it.assetId to it.value }
    val viewsByAsset = state.contentRanking.associate { it.assetId to it.viewCount }
    return if (sortByHeat) {
        val maxValue = state.contentRanking.firstOrNull()?.viewCount ?: 0
        state.contentRanking.mapIndexed { index, entry ->
            val dwell = dwellByAsset[entry.assetId]
            RankRowUi(
                rank = index + 1,
                title = entry.title,
                subtitle = "浏览 ${entry.viewCount} 次" +
                    dwell?.takeIf { it > 0 }?.let { " · 停留 ${formatDurationDetail(it.toLong())}" }.orEmpty(),
                valueText = "${entry.viewCount} 次",
                progressValue = entry.viewCount.toLong(),
                progressMax = maxValue.toLong(),
                onClick = { onEntryClick(entry.assetId) },
            )
        }
    } else {
        val maxValue = state.secondsRanking.firstOrNull()?.value ?: 0
        state.secondsRanking.mapIndexed { index, entry ->
            RankRowUi(
                rank = index + 1,
                title = entry.fileName,
                subtitle = "浏览 ${viewsByAsset[entry.assetId] ?: 0} 次",
                valueText = formatDurationDetail(entry.value.toLong()),
                progressValue = entry.value.toLong(),
                progressMax = maxValue.toLong(),
                onClick = { onEntryClick(entry.assetId) },
            )
        }
    }
}

/** 常看作者与标签摘要格（旧版 renderAuthorsTags 四格：作者/标签/浏览总次数/Top 作者） */
internal fun authorsTagsSummaryCells(state: StatsDetailUiState): List<SummaryCellUi> = listOf(
    // 全量窗口计数由服务端给出，与展示的 Top N 条数分开。
    SummaryCellUi("作者", formatCountDetail(state.overviewValues?.viewedAuthorsCount ?: 0)),
    SummaryCellUi("标签", formatCountDetail(state.overviewValues?.viewedTagsCount ?: 0)),
    SummaryCellUi("浏览总次数", formatCountDetail(state.windowViewCount)),
    SummaryCellUi("Top 作者", state.topAuthors.firstOrNull()?.displayName ?: DETAIL_UNAVAILABLE_TEXT),
)

/** 常看作者与标签洞察行（旧版逐字：「最常看作者「X」，浏览 N 次」/「最常看标签「Y」，浏览 M 次」） */
internal fun authorsTagsInsightLines(state: StatsDetailUiState): List<String> = buildList {
    state.topAuthors.firstOrNull()?.let { add("最常看作者「${it.displayName}」，浏览 ${it.views} 次") }
    state.topTags.firstOrNull()?.let { add("最常看标签「${it.tag}」，浏览 ${it.views} 次") }
}

/** 常看作者排行行（旧版同构：副标题「作者」、值「N 次」、进度相对第一名） */
internal fun authorRankRows(
    state: StatsDetailUiState,
    onEntryClick: (TopAuthorEntry) -> Unit,
): List<RankRowUi> {
    val maxValue = state.topAuthors.firstOrNull()?.views ?: 0
    return state.topAuthors.mapIndexed { index, entry ->
        RankRowUi(
            rank = index + 1,
            title = entry.displayName,
            subtitle = "作者",
            valueText = "${entry.views} 次",
            progressValue = entry.views.toLong(),
            progressMax = maxValue.toLong(),
            onClick = { onEntryClick(entry) },
        )
    }
}

/** 常看标签排行行（旧版同构：副标题「标签」） */
internal fun tagRankRows(
    state: StatsDetailUiState,
    onEntryClick: (TopTagEntry) -> Unit,
): List<RankRowUi> {
    val maxValue = state.topTags.firstOrNull()?.views ?: 0
    return state.topTags.mapIndexed { index, entry ->
        RankRowUi(
            rank = index + 1,
            title = entry.tag,
            subtitle = "标签",
            valueText = "${entry.views} 次",
            progressValue = entry.views.toLong(),
            progressMax = maxValue.toLong(),
            onClick = { onEntryClick(entry) },
        )
    }
}

/** 分布摘要格 8 项（旧版 renderDistribution renderSummary 逐字：图片/视频/动图各含整数百分比、
 *  常规 / COS、图片总大小、视频总大小、总占用、窗口浏览；百分比=整数除法 count*100/total） */
internal fun distributionSummaryCells(state: StatsDetailUiState): List<SummaryCellUi> {
    val overview = state.overviewValues ?: return emptyList()
    val total = overview.totalFiles.coerceAtLeast(1)
    val animatedCount = overview.animatedImageCount
    val windowViews = overview.windowTotals.viewCount
    return listOf(
        SummaryCellUi(IMAGE_DISPLAY_NAME, "${overview.stillImageCount} (${overview.stillImageCount * 100 / total}%)"),
        SummaryCellUi(VIDEO_DISPLAY_NAME, "${overview.videoCount} (${overview.videoCount * 100 / total}%)"),
        SummaryCellUi(ANIMATED_DISPLAY_NAME, "$animatedCount (${animatedCount * 100 / total}%)"),
        SummaryCellUi(
            "常规 / COS",
            "${overview.sourceNormalCount} / ${overview.sourceCosCount}",
        ),
        // 分类型大小直出（2026-09-14 协议批 per_type_size_bytes；image 不含动图）
        SummaryCellUi("图片总大小", formatSizeDetail(overview.imageSizeBytes)),
        SummaryCellUi("视频总大小", formatSizeDetail(overview.videoSizeBytes)),
        SummaryCellUi("总占用", formatSizeDetail(overview.totalSizeBytes)),
        SummaryCellUi("窗口浏览", formatCountDetail(windowViews)),
    )
}

/** 分布洞察行（旧版逐字：类型浏览行恒出；COS 行仅 cosCount>0 时出） */
internal fun distributionInsightLines(state: StatsDetailUiState, range: StatsRangeOption): List<String> {
    val overview = state.overviewValues ?: return emptyList()
    val total = overview.totalFiles.coerceAtLeast(1)
    val imageViews = state.typeWindowViews[KEY_MEDIA_TYPE_IMAGE] ?: 0
    val videoViews = state.typeWindowViews[KEY_MEDIA_TYPE_VIDEO] ?: 0
    val animatedViews = state.typeWindowViews[KEY_MEDIA_TYPE_ANIMATED] ?: 0
    return buildList {
        add("近${rangeLabel(range)}图片被浏览 $imageViews 次，视频 $videoViews 次，动图 $animatedViews 次")
        if (overview.sourceCosCount > 0) {
            add(
                "COS 文件 ${overview.sourceCosCount} 个（${overview.sourceCosCount * 100 / total}%），" +
                    "窗口浏览 ${state.sourceWindowViews[KEY_SOURCE_COS] ?: 0} 次",
            )
        }
    }
}

/** 类型分布行（图片/视频/动图 × 数量/大小/浏览；最大值=行内三档各自取最大，旧版 maxCount/maxViews 同口径） */
internal fun typeDistributionRows(state: StatsDetailUiState): List<DistributionRowUi> {
    val overview = state.overviewValues ?: return emptyList()
    val animatedCount = overview.animatedImageCount
    return buildDistributionRows(
        listOf(
            DistRowInput(IMAGE_DISPLAY_NAME, overview.stillImageCount, overview.imageSizeBytes, state.typeWindowViews[KEY_MEDIA_TYPE_IMAGE] ?: 0),
            DistRowInput(VIDEO_DISPLAY_NAME, overview.videoCount, overview.videoSizeBytes, state.typeWindowViews[KEY_MEDIA_TYPE_VIDEO] ?: 0),
            DistRowInput(ANIMATED_DISPLAY_NAME, animatedCount, overview.animatedImageSizeBytes, state.typeWindowViews[KEY_MEDIA_TYPE_ANIMATED] ?: 0),
        ),
    )
}

/** 来源分布行（常规/COS × 数量/大小/浏览，旧版来源卡同构） */
internal fun sourceDistributionRows(state: StatsDetailUiState): List<DistributionRowUi> {
    val overview = state.overviewValues ?: return emptyList()
    return buildDistributionRows(
        listOf(
            DistRowInput(SOURCE_NORMAL_DISPLAY_NAME, overview.sourceNormalCount, overview.normalSizeBytes, state.sourceWindowViews[KEY_SOURCE_NORMAL] ?: 0),
            DistRowInput(SOURCE_COS_DISPLAY_NAME, overview.sourceCosCount, overview.cosSizeBytes, state.sourceWindowViews[KEY_SOURCE_COS] ?: 0),
        ),
    )
}

/**
 * 分布行装配：数量/浏览/大小进度均相对本卡（组内）最大值。大小指标走协议
 * per_type/per_source_size_bytes 直出（2026-09-14 协议批）。
 * 偏离记档：旧版（StatsDetailFragment.kt L685/L693）来源行进度 maxBytes 用
 * 全库总占用，两行比例失真（各自最多只到总占比的百分位）；本版改组内最大值
 * 口径 = 与数量/浏览档及排行卡「相对第一名」一致。
 */
private data class DistRowInput(val label: String, val count: Int, val sizeBytes: Long, val views: Int)

private fun buildDistributionRows(entries: List<DistRowInput>): List<DistributionRowUi> {
    val maxCount = entries.maxOf { it.count }.coerceAtLeast(1)
    val maxSize = entries.maxOf { it.sizeBytes }.coerceAtLeast(1)
    val maxViews = entries.maxOf { it.views }.coerceAtLeast(1)
    return entries.map { (label, count, sizeBytes, views) ->
        DistributionRowUi(
            label = label,
            metrics = listOf(
                DistributionMetricUi("数量", count.toString(), rankProgressPercent(count, maxCount)),
                DistributionMetricUi("大小", formatSizeDetail(sizeBytes), rankProgressPercent(sizeBytes, maxSize)),
                DistributionMetricUi("浏览", views.toString(), rankProgressPercent(views, maxViews)),
            ),
        )
    }
}

