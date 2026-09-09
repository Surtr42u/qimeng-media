package media.qimeng.app.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberToggleOnTap
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerController
import media.qimeng.app.core.model.MostViewedEntry
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.detailTitleSuffix
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengRankCard
import media.qimeng.app.core.ui.component.QimengTopBar

/**
 * 统计详情页（任务I I3 + N4 I3b 全量接线；GUIDE_UI §统计详情页 L225-234，StatsDetailRoutes）：
 * - 顶栏动态标题 = 模式标题 + 时间范围后缀（「· 近7天/近30天/全部」，StatsRange.detailTitleSuffix）；
 * - TYPE_TREND：「类型浏览趋势」卡 +「来源浏览趋势」卡（N3 #31b 解冻：常规/COS 双系列，
 *   mediaType 单值/source 单值逐次取数拼系列），调用点自组图例行（色圆点+文字）；
 *   点击气泡含系列名（rememberTrendValueMarker）；
 * - MOST_VIEWED：「常看文件（按时长）」榜——most-viewed metric=seconds Top20（QimengRankCard 形态）；
 * - AUTHORS_TAGS：「常看作者」Top15 +「常看标签」Top10 双排行卡（GUIDE_UI v1.16 拆卡口径）；
 * - DISTRIBUTION：「类型分布对比」卡 +「来源构成对比」卡（overview sourceCounts，N3 #31b 解冻）——
 *   QimengRankCard 形态 + 相对第一名的进度条（GUIDE_UI L229）+ 前三名排名数字高亮（L247 同节）；
 * - 空态「暂无数据」（L247）。
 * 详情页跳转链（任务J J1，GUIDE_UI L218-224；REPLICATION_GAPS §3.3 裁定 7 清偿）：
 * seconds 榜条目→详情页（榜单作批次上下文，[StatsDetailViewModel.enterDetail] 写 Top20
 * 快照清单）；常看作者条目→作者集合页（真实 authorId）；常看标签条目→搜索页携词——
 * 经回调上抛壳层导航。分布行无协议内跳转落点，维持不可点击（原记档口径不变）。
 */
@Composable
fun StatsDetailScreen(
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit,
    onOpenTagSearch: (tag: String) -> Unit,
    viewModel: StatsDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(
            title = "${viewModel.mode.title} · ${viewModel.range.detailTitleSuffix}",
            onBack = onBack,
        )
        when {
            state.loading -> Text(
                text = LOADING_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            )
            state.loadFailed || state.isEmpty -> QimengEmptyState(text = EMPTY_TEXT)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Dimens.ScreenPadding,
                    vertical = Dimens.ScreenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.ScreenPadding),
            ) {
                when (viewModel.mode) {
                    StatsDetailMode.TYPE_TREND -> {
                        item { TypeTrendCard(series = state.typeSeries, labels = state.trendLabels) }
                        item {
                            SourceTrendCard(series = state.sourceSeries, labels = state.trendLabels)
                        }
                    }
                    StatsDetailMode.MOST_VIEWED -> {
                        item {
                            SecondsRankingCard(
                                entries = state.secondsRanking,
                                onEntryClick = { entry ->
                                    // 榜单作批次上下文（J1）：Top20 快照清单先写再导航
                                    viewModel.enterDetail(entry.assetId)
                                    onOpenAsset(entry.assetId)
                                },
                            )
                        }
                    }
                    StatsDetailMode.AUTHORS_TAGS -> {
                        item {
                            AuthorsRankingCard(
                                entries = state.topAuthors,
                                onEntryClick = { entry -> onOpenAuthor(entry.authorId, entry.displayName) },
                            )
                        }
                        item {
                            TagsRankingCard(
                                entries = state.topTags,
                                onEntryClick = { entry -> onOpenTagSearch(entry.tag) },
                            )
                        }
                    }
                    StatsDetailMode.DISTRIBUTION -> {
                        item { TypeDistributionCard(entries = state.distribution) }
                        item { SourceDistributionCard(entries = state.sourceDistribution) }
                    }
                }
            }
        }
    }
}

/**
 * 「类型浏览趋势」卡：多系列折线 + 调用点自组图例行。
 * 系列色 = M3 scheme primary/tertiary/error 三档（图片/视频/动图固定按序取色，
 * 图例与折线同源同一色表，气泡系列名反查同表）。
 */
@Composable
private fun TypeTrendCard(series: List<TypeTrendSeries>, labels: List<String>) {
    val seriesColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
    )
    // 气泡系列名：折线色 → 系列名（Point.color 逐点携带系列色，反查同表）
    val marker = rememberTrendValueMarker(
        seriesNamesByColor = series.mapIndexedNotNull { index, typeSeries ->
            seriesColors.getOrNull(index)?.let { color -> color to typeSeries.name }
        }.toMap(),
        valueSuffix = MARKER_VALUE_SUFFIX_VIEWS,
    )
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "类型浏览趋势", style = MaterialTheme.typography.titleMedium)
            // 图例行（调用点自组：色点+系列名；封装无图例能力，H3 记档的轻方案）
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                series.forEachIndexed { index, typeSeries ->
                    LegendEntry(
                        color = seriesColors[index % seriesColors.size],
                        name = typeSeries.name,
                    )
                }
            }
            QimengTrendLineChart(
                series = series.map { QimengTrendSeries(values = it.values) },
                seriesColors = seriesColors,
                xLabels = labels,
                marker = marker,
                markerController = CartesianMarkerController.Companion.rememberToggleOnTap(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DETAIL_TREND_CHART_HEIGHT_DP.dp),
            )
        }
    }
}

/** 图例单项：系列色圆点 + 系列名 */
@Composable
private fun LegendEntry(color: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(LEGEND_DOT_SIZE_DP.dp)
                .background(color = color, shape = CircleShape),
        )
        Text(text = name, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 「类型分布对比」卡（分布模式）：QimengRankCard 榜单卡形态，逐行类型库存 +
 * 相对第一名的进度条（GUIDE_UI L229）+ 前三名排名数字主题色高亮（L248）。
 * 跳转链记档见类 KDoc——分布行无协议内跳转落点，行不可点击。
 */
@Composable
private fun TypeDistributionCard(entries: List<TypeStockEntry>) {
    QimengRankCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "类型分布对比", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        RankRows(entries)
    }
}

/**
 * 「来源构成对比」卡（分布模式，N3 #31b 解冻）：overview 的 sourceNormalCount/sourceCosCount
 * 常规/COS 库存对比（DOMAIN_RULES §6 分区判定口径），形态与类型卡同构。
 */
@Composable
private fun SourceDistributionCard(entries: List<TypeStockEntry>) {
    QimengRankCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "来源构成对比", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        RankRows(entries)
    }
}

/** 排行行组（相对第一名进度条；空表显示空态行——卡不因空数据只剩标题） */
@Composable
private fun RankRows(entries: List<TypeStockEntry>) {
    if (entries.isEmpty() || entries.all { it.count == 0 }) {
        Text(
            text = EMPTY_TEXT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val maxCount = entries.maxOf { it.count }.coerceAtLeast(1)
    entries.forEachIndexed { index, entry ->
        RankRow(
            rank = index + 1,
            name = entry.name,
            count = entry.count,
            progress = entry.count.toFloat() / maxCount,
        )
    }
}

/**
 * 「常看文件（按时长）」榜（MOST_VIEWED 模式）：most-viewed metric=seconds Top20——
 * 值=窗口内 dwell 秒数累计（formatDurationSeconds 人读化），相对第一名进度条；
 * 条目可点击进详情（J1 跳转链，榜单作批次上下文）。
 */
@Composable
private fun SecondsRankingCard(
    entries: List<MostViewedEntry>,
    onEntryClick: (MostViewedEntry) -> Unit,
) {
    QimengRankCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "常看文件（按时长）", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                text = EMPTY_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@QimengRankCard
        }
        val maxValue = entries.maxOf { it.value }.coerceAtLeast(1)
        entries.forEachIndexed { index, entry ->
            RankRow(
                rank = index + 1,
                name = entry.fileName,
                count = entry.value,
                progress = entry.value.toFloat() / maxValue,
                countText = formatDurationSeconds(entry.value.toLong()),
                onClick = { onEntryClick(entry) },
            )
        }
    }
}

/** 「常看作者」Top15 排行卡（AUTHORS_TAGS 模式）；条目点击→作者集合页（J1 跳转链） */
@Composable
private fun AuthorsRankingCard(
    entries: List<TopAuthorEntry>,
    onEntryClick: (TopAuthorEntry) -> Unit,
) {
    QimengRankCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "常看作者", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                text = EMPTY_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@QimengRankCard
        }
        val maxViews = entries.maxOf { it.views }.coerceAtLeast(1)
        entries.forEachIndexed { index, entry ->
            RankRow(
                rank = index + 1,
                name = entry.displayName,
                count = entry.views,
                progress = entry.views.toFloat() / maxViews,
                countText = entry.views.toDisplayText() + MARKER_VALUE_SUFFIX_VIEWS,
                onClick = { onEntryClick(entry) },
            )
        }
    }
}

/** 「常看标签」Top10 排行卡（AUTHORS_TAGS 模式）；条目点击→搜索页携词（J1 跳转链） */
@Composable
private fun TagsRankingCard(
    entries: List<TopTagEntry>,
    onEntryClick: (TopTagEntry) -> Unit,
) {
    QimengRankCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "常看标签", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                text = EMPTY_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@QimengRankCard
        }
        val maxViews = entries.maxOf { it.views }.coerceAtLeast(1)
        entries.forEachIndexed { index, entry ->
            RankRow(
                rank = index + 1,
                name = entry.tag,
                count = entry.views,
                progress = entry.views.toFloat() / maxViews,
                countText = entry.views.toDisplayText() + MARKER_VALUE_SUFFIX_VIEWS,
                onClick = { onEntryClick(entry) },
            )
        }
    }
}

/**
 * 「来源浏览趋势」卡（TYPE_TREND 模式，N3 #31b 解冻）：常规/COS 双系列折线，
 * 渲染与图例和类型卡同构（seriesColors 二档 primary/tertiary）。
 */
@Composable
private fun SourceTrendCard(series: List<TypeTrendSeries>, labels: List<String>) {
    if (series.isEmpty()) return // 窗口内常规与 COS 均无浏览：不出卡（类型卡同口径）
    val seriesColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
    )
    val marker = rememberTrendValueMarker(
        seriesNamesByColor = series.mapIndexedNotNull { index, sourceSeries ->
            seriesColors.getOrNull(index)?.let { color -> color to sourceSeries.name }
        }.toMap(),
        valueSuffix = MARKER_VALUE_SUFFIX_VIEWS,
    )
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "来源浏览趋势", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                series.forEachIndexed { index, sourceSeries ->
                    LegendEntry(
                        color = seriesColors[index % seriesColors.size],
                        name = sourceSeries.name,
                    )
                }
            }
            QimengTrendLineChart(
                series = series.map { QimengTrendSeries(values = it.values) },
                seriesColors = seriesColors,
                xLabels = labels,
                marker = marker,
                markerController = CartesianMarkerController.Companion.rememberToggleOnTap(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DETAIL_TREND_CHART_HEIGHT_DP.dp),
            )
        }
    }
}

/**
 * 排行行：排名数字（前三名主题色高亮）+ 名称 + 数值 + 相对第一名进度条（countText 缺省=千分位）；
 * [onClick] 非空时整行可点击（J1 跳转链：文件→详情/作者→集合页/标签→搜索），
 * 分布行缺省 null 维持不可点击（无协议内落点）。
 */
@Composable
private fun RankRow(
    rank: Int,
    name: String,
    count: Int,
    progress: Float,
    countText: String = count.toDisplayText(),
    onClick: (() -> Unit)? = null,
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (rank <= TOP_RANK_HIGHLIGHT) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = countText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

/** 详情页趋势图固定高度（GUIDE_UI：详情页折线图 240dp 档） */
private const val DETAIL_TREND_CHART_HEIGHT_DP = 240

/** 图例色点直径（视觉调参） */
private const val LEGEND_DOT_SIZE_DP = 8

/** 排名前三名高亮阈值（GUIDE_UI §交互设计「排行榜前三名排名数字高亮（主题色）」） */
private const val TOP_RANK_HIGHLIGHT = 3

/** 加载中文案 */
private const val LOADING_TEXT = "加载中…"

/** 空态文案（GUIDE_UI L247「暂无数据」） */
private const val EMPTY_TEXT = "暂无数据"
