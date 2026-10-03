package media.qimeng.app.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberToggleOnTap
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerController
import media.qimeng.app.core.model.MediaTypeKeys
import media.qimeng.app.core.model.SourceKeys
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.detailTitleSuffix
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.icon.BackIcon

/**
 * 统计详情页（2026-09-13 视觉复刻批：对齐旧仓库 StatsDetailFragment + fragment_stats_detail.xml
 * 运行时形态；GUIDE_UI §统计详情页 L225-234，路由契约见 StatsDetailRoutes）：
 * - 顶栏自绘 56dp（旧版自定义 LinearLayout，非 M3 TopAppBar）：返回钮 32dp + 标题 18sp Bold；
 *   动态标题 = 模式标题 +「 · 近7天/近30天/全部」，分布统计详情不带后缀（陷阱#8）；
 * - 摘要卡：2 列白卡网格（旧版 renderSummary/createSummaryCard），每格独立 20dp 圆角卡；
 * - 洞察卡：「数据洞察」+「· 文案」条目（旧版 renderInsights；空列表不出卡）；
 * - TYPE_TREND：类型/来源两张趋势卡（胶囊多选 + 图例 + 240dp 折线）；
 * - MOST_VIEWED：「内容榜/常看排行」卡 + 按热度/按时长排序胶囊（按热度=内容榜 GET /rankings，
 *   2026-09-18 批换源；按时长=seconds 榜，[StatsDetailViewModel.toggleFilesSort] 切换）；
 * - AUTHORS_TAGS：常看作者 Top15 + 常看标签 Top10 双排行卡；
 * - DISTRIBUTION：类型/来源两张分布对比卡（行标签 + 数量/大小/浏览三指标）；
 * - 排行行 = 独立白卡：名次列 28dp 前三名 primary 高亮 + 进度条 accent 灰（陷阱#7）；
 *   空态占位行名次「—」不可点（陷阱#12）。
 * 跳转链（任务J J1）不变：榜单条目回调上抛壳层导航，批次快照由 enterDetail 随排序档写入。
 *
 * 超文件警戒线理由：五模式（类型趋势/来源趋势/常看文件/作者标签/分布）详情页强内聚——
 * 共用同一 LazyColumn 骨架、行组件与摘要格，模式差异只是 item 段装配；按模式拆文件会
 * 把共享组件拖成跨文件 internal 面，拆分收益为负，警戒线特此记档。
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
    val filesSortByHeat by viewModel.filesSortByHeat.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(title = detailTitle(viewModel.mode, viewModel.range), onBack = onBack)
        when {
            state.loading -> Text(
                text = LOADING_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            )
            state.loadFailed || state.isEmpty -> QimengEmptyState(text = EMPTY_TEXT)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP.dp),
            ) {
                when (viewModel.mode) {
                    StatsDetailMode.TYPE_TREND -> {
                        item {
                            DetailTrendCard(
                                title = TYPE_TREND_CARD_TITLE,
                                dimensions = TYPE_TREND_DIMENSIONS,
                                seriesValuesByKey = state.typeSeries.associate { it.mediaType to it.values },
                                labels = state.trendLabels,
                            )
                        }
                        item {
                            DetailTrendCard(
                                title = SOURCE_TREND_CARD_TITLE,
                                dimensions = SOURCE_TREND_DIMENSIONS,
                                seriesValuesByKey = state.sourceSeries.associate { it.mediaType to it.values },
                                labels = state.trendLabels,
                            )
                        }
                    }
                    StatsDetailMode.MOST_VIEWED -> {
                        item { SummaryGrid(cells = mostViewedSummaryCells(state)) }
                        item { InsightCard(lines = mostViewedInsightLines(state, viewModel.range)) }
                        item {
                            RankingCard(
                                // 标题随排序档切换：按热度档=内容榜（2026-09-18 批换源）、按时长档保留旧「常看排行」
                                title = if (filesSortByHeat) CONTENT_RANK_CARD_TITLE else FILES_RANK_CARD_TITLE,
                                subtitle = "共 ${state.filesWithViewRecords} 个有浏览记录的文件",
                                rows = mostViewedRankRows(state, filesSortByHeat) { assetId ->
                                    // 榜单作批次上下文（J1）：先写快照清单再导航
                                    viewModel.enterDetail(assetId)
                                    onOpenAsset(assetId)
                                },
                                sortToggleText = if (filesSortByHeat) SORT_BY_HEAT_TEXT else SORT_BY_SECONDS_TEXT,
                                onSortToggle = viewModel::toggleFilesSort,
                            )
                        }
                    }
                    StatsDetailMode.AUTHORS_TAGS -> {
                        item { SummaryGrid(cells = authorsTagsSummaryCells(state)) }
                        item { InsightCard(lines = authorsTagsInsightLines(state)) }
                        item {
                            RankingCard(
                                title = AUTHORS_CARD_TITLE,
                                subtitle = AUTHORS_CARD_SUBTITLE,
                                rows = authorRankRows(state) { entry -> onOpenAuthor(entry.authorId, entry.displayName) },
                            )
                        }
                        item {
                            RankingCard(
                                title = TAGS_CARD_TITLE,
                                subtitle = TAGS_CARD_SUBTITLE,
                                rows = tagRankRows(state) { entry -> onOpenTagSearch(entry.tag) },
                            )
                        }
                    }
                    StatsDetailMode.DISTRIBUTION -> {
                        item { SummaryGrid(cells = distributionSummaryCells(state)) }
                        item { InsightCard(lines = distributionInsightLines(state, viewModel.range)) }
                        item {
                            DistributionCard(
                                title = TYPE_DISTRIBUTION_CARD_TITLE,
                                subtitle = TYPE_DISTRIBUTION_CARD_SUBTITLE,
                                rows = typeDistributionRows(state),
                            )
                        }
                        item {
                            DistributionCard(
                                title = SOURCE_DISTRIBUTION_CARD_TITLE,
                                subtitle = SOURCE_DISTRIBUTION_CARD_SUBTITLE,
                                rows = sourceDistributionRows(state),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 顶栏（旧版 fragment_stats_detail.xml L14-38 同构） ====================

/** 顶栏：高 56dp 水平内边距 16dp；返回钮 32x32dp（tint=onSurface 档）；标题 18sp Bold 左距 8dp。
 *  壳层 NavHost 已 padding+consume 状态栏 inset（QimengNavHost 官方范式），此处无需再让。 */
@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT_DP.dp)
            .padding(horizontal = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(BACK_BUTTON_SIZE_DP.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BackIcon,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = title,
            fontSize = TOP_BAR_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 顶栏动态标题：模式标题 + 档位后缀；分布统计详情不带后缀（陷阱#8，旧版 observeDistributionMode 逐字）。
 *  TYPE_TREND 标题「类型浏览趋势」逐字取旧版（enum.title「分类型趋势」是入口卡文案，与旧版顶栏不同字） */
private fun detailTitle(mode: StatsDetailMode, range: StatsRangeOption): String = when (mode) {
    StatsDetailMode.TYPE_TREND -> "$TYPE_TREND_CARD_TITLE · ${range.detailTitleSuffix}"
    StatsDetailMode.MOST_VIEWED -> "${mode.title} · ${range.detailTitleSuffix}"
    StatsDetailMode.AUTHORS_TAGS -> "${mode.title} · ${range.detailTitleSuffix}"
    StatsDetailMode.DISTRIBUTION -> DISTRIBUTION_DETAIL_TITLE
}

// ==================== 通用卡容器与摘要/洞察（旧版 bg_stat_card + renderSummary/renderInsights） ====================

/** 统计卡容器：纯白 surface 槽位（=旧 qmColorSurface #FFFFFF，Theme.kt 映射）+ 20dp 圆角 + 12dp 内边距 */
@Composable
private fun StatCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(STAT_CARD_CORNER_RADIUS_DP),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

/** 摘要格数据（旧版 SummaryItem 同构） */
private data class SummaryCellUi(val label: String, val value: String)

/** 摘要卡：2 列网格，列间 4dp 行间 8dp；每格独立白卡（旧版 renderSummary chunked(2) 同构） */
@Composable
private fun SummaryGrid(cells: List<SummaryCellUi>) {
    if (cells.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(SUMMARY_ROW_SPACING_DP.dp)) {
        cells.chunked(SUMMARY_GRID_COLUMNS).forEach { rowCells ->
            Row(horizontalArrangement = Arrangement.spacedBy(SUMMARY_COLUMN_SPACING_DP.dp)) {
                rowCells.forEach { cell -> SummaryCellCard(cell = cell, modifier = Modifier.weight(1f)) }
                // 奇数项末行右侧占位（旧版 placeholder weight=1 同款，保持左格等宽）
                if (rowCells.size < SUMMARY_GRID_COLUMNS) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 摘要格：数值 18sp Bold primary 色 + 标签 11sp 次色（旧版 createSummaryCard 同款） */
@Composable
private fun SummaryCellCard(cell: SummaryCellUi, modifier: Modifier = Modifier) {
    StatCard(modifier = modifier) {
        Text(
            text = cell.value,
            fontSize = SUMMARY_VALUE_SP.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = cell.label,
            fontSize = SUMMARY_LABEL_SP.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 洞察卡：标题 14sp Bold +「· 文案」12sp 次色条目间 6dp（旧版 renderInsights 同款；空列表不出卡） */
@Composable
private fun InsightCard(lines: List<String>) {
    if (lines.isEmpty()) return
    StatCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = INSIGHT_TITLE,
            fontSize = CARD_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        lines.forEach { line ->
            Text(
                text = "· $line",
                fontSize = INSIGHT_ITEM_SP.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = INSIGHT_ITEM_SPACING_DP.dp),
            )
        }
    }
}

// ==================== 趋势卡（旧版 buildTrendCard/buildTrendCapsuleRow 同构） ====================

/** 趋势维度（key=协议值，色=旧版 trendColor 运行时值逐字：图片/视频/动图/常规/COS 五档） */
private data class TrendDimension(val key: String, val label: String, val color: Color)

/**
 * 趋势图系列色（旧版 trendColor 运行时值逐字，值不变纯命名化）。
 * 色值暂未双主题化，明暗双套另立批次。
 */
private val TREND_COLOR_IMAGE = Color(0xFF4FC3F7)
private val TREND_COLOR_VIDEO = Color(0xFFFF8A65)
private val TREND_COLOR_ANIMATED = Color(0xFFAED581)
private val TREND_COLOR_SOURCE_NORMAL = Color(0xFF7986CB)
private val TREND_COLOR_SOURCE_COS = Color(0xFFF06292)

/** 类型趋势卡维度与系列顺序（图片→视频→动图，旧版 buildTypeSeries 逐字） */
private val TYPE_TREND_DIMENSIONS = listOf(
    TrendDimension(KEY_MEDIA_TYPE_IMAGE, IMAGE_DISPLAY_NAME, TREND_COLOR_IMAGE),
    TrendDimension(KEY_MEDIA_TYPE_VIDEO, VIDEO_DISPLAY_NAME, TREND_COLOR_VIDEO),
    TrendDimension(KEY_MEDIA_TYPE_ANIMATED, ANIMATED_DISPLAY_NAME, TREND_COLOR_ANIMATED),
)

/** 来源趋势卡维度与系列顺序（常规→COS，旧版 buildSourceSeries 逐字） */
private val SOURCE_TREND_DIMENSIONS = listOf(
    TrendDimension(KEY_SOURCE_NORMAL, SOURCE_NORMAL_DISPLAY_NAME, TREND_COLOR_SOURCE_NORMAL),
    TrendDimension(KEY_SOURCE_COS, SOURCE_COS_DISPLAY_NAME, TREND_COLOR_SOURCE_COS),
)

/**
 * 趋势卡：标题 14sp Bold → 胶囊行（topMargin 8dp、等宽 34dp 高、选中=主色实底 onPrimary 字 /
 * 未选=完全透明底次色字，陷阱#6）→ 图例行（色块 8x8 方形 + 间距 4dp + 10sp 次色，图宽 1/n 均分居中）
 * → 240dp 折线图（topMargin 8dp，色随维度固定，气泡系列名按色反查）。
 * 胶囊多选可叠加对比（旧版 toggleTrend 同语义：至少保留一项防空图）。
 */
@Composable
private fun DetailTrendCard(
    title: String,
    dimensions: List<TrendDimension>,
    seriesValuesByKey: Map<String, List<Int>>,
    labels: List<String>,
) {
    if (seriesValuesByKey.isEmpty()) return // 该卡所有维度窗口内均无数据：不出卡（轴以有数卡为准）
    // 多选态默认全选（旧版 typeSelections/sourceSelections 初始全量同款）
    var selectedKeys by remember { mutableStateOf(seriesValuesByKey.keys.toSet()) }
    val capsuleDimensions = dimensions.filter { it.key in seriesValuesByKey.keys }
    val activeDimensions = capsuleDimensions.filter { it.key in selectedKeys }
    StatCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = CARD_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(TREND_CAPSULE_HEIGHT_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(TREND_CAPSULE_GAP_DP.dp),
        ) {
            capsuleDimensions.forEach { dimension ->
                val selected = dimension.key in selectedKeys
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(PILL_CORNER_RADIUS_DP))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable {
                            // 该卡至少保留一个维度（旧版 toggleTrend size==1 守卫同款）
                            if (selected && selectedKeys.size <= 1) return@clickable
                            selectedKeys = if (selected) selectedKeys - dimension.key else selectedKeys + dimension.key
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dimension.label,
                        fontSize = TREND_CAPSULE_TEXT_SP.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            activeDimensions.forEach { dimension ->
                // 各系列在图宽 1/n 均分、内容居中（旧版 LineChartView.drawLegend centerX=(i+0.5)/n 同构）
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(LEGEND_SWATCH_SIZE_DP.dp)
                            .background(dimension.color),
                    )
                    Spacer(modifier = Modifier.width(LEGEND_GAP_DP.dp))
                    Text(
                        text = dimension.label,
                        fontSize = LEGEND_TEXT_SP.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val marker = rememberTrendValueMarker(
            seriesNamesByColor = activeDimensions.associate { it.color to it.label },
            valueSuffix = MARKER_VALUE_SUFFIX_VIEWS,
        )
        QimengTrendLineChart(
            series = activeDimensions.map { QimengTrendSeries(values = seriesValuesByKey[it.key].orEmpty()) },
            seriesColors = activeDimensions.map { it.color },
            xLabels = labels,
            marker = marker,
            markerController = CartesianMarkerController.Companion.rememberToggleOnTap(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(DETAIL_TREND_CHART_HEIGHT_DP.dp),
        )
    }
}

// ==================== 排行卡（旧版 rankListContainer + item_rank_list.xml 同构） ====================

/** 排行行数据（占位行 rank=null；进度百分比渲染时经 [rankProgressPercent] 统一算） */
private data class RankRowUi(
    val rank: Int?,
    val title: String,
    val subtitle: String?,
    val valueText: String?,
    val progressValue: Long,
    val progressMax: Long,
    val onClick: (() -> Unit)? = null,
)

/** 空态占位行（陷阱#12：名次「—」+「暂无数据」+ 无数值 + 不可点，旧版 EMPTY_PLACEHOLDER 同构） */
private val RANK_EMPTY_ROW = RankRowUi(
    rank = null,
    title = EMPTY_TEXT,
    subtitle = null,
    valueText = null,
    progressValue = 0,
    progressMax = 0,
)

/**
 * 排行卡：标题行（14sp Bold + 可选排序胶囊）→ 副标题 12sp 次色（topMargin 2dp）→
 * 行列表（topMargin 8dp、行间 6dp）；每行独立白卡（旧版 item_rank_list 根节点 bg_stat_card 同构）。
 */
@Composable
private fun RankingCard(
    title: String,
    subtitle: String,
    rows: List<RankRowUi>,
    sortToggleText: String? = null,
    onSortToggle: (() -> Unit)? = null,
) {
    StatCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = CARD_TITLE_SP.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (sortToggleText != null && onSortToggle != null) {
                SortCapsule(text = sortToggleText, onClick = onSortToggle)
            }
        }
        Text(
            text = subtitle,
            fontSize = CARD_SUBTITLE_SP.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(RANK_ROW_SPACING_DP.dp),
        ) {
            (rows.ifEmpty { listOf(RANK_EMPTY_ROW) }).forEach { row -> RankRow(row = row) }
        }
    }
}

/**
 * 排行行：名次列 28dp 居中 16sp Bold（前三名 primary 其余次色，占位行不高亮）→
 * 标题 14sp 主文字色单行省略 + 副标题 11sp 次色（空则不占位）+ 进度条（3dp 高、
 * 轨道 surfaceVariant / 进度 accent 灰，陷阱#7）→ 右侧数值 14sp Bold primary（marginStart 8dp）。
 */
@Composable
private fun RankRow(row: RankRowUi) {
    val isPlaceholder = row.rank == null
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(STAT_CARD_CORNER_RADIUS_DP),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (row.onClick != null) Modifier.clickable(onClick = row.onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.rank?.toString() ?: RANK_PLACEHOLDER_TEXT,
                fontSize = RANK_TEXT_SP.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (!isPlaceholder && row.rank <= TOP_RANK_HIGHLIGHT) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(RANK_COLUMN_WIDTH_DP.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = row.title,
                    fontSize = ROW_TITLE_SP.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!row.subtitle.isNullOrEmpty()) {
                    Text(
                        text = row.subtitle,
                        fontSize = ROW_SUBTITLE_SP.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                MiniProgressBar(
                    percent = rankProgressPercent(row.progressValue, row.progressMax),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (row.valueText != null) {
                Text(
                    text = row.valueText,
                    fontSize = ROW_VALUE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * 细进度条：高 3dp、圆角 2dp、轨道=旧 qmColorSurfaceSoft 槽位（surfaceVariant）；
 * 进度=旧 qmColorAccent 槽位——Theme.kt 把 qm_accent 映射进 secondary（浅 #6A6A6A/夜 #A8A8A8），
 * 陷阱#7：进度条是 accent 灰不是主色。M3 LinearProgressIndicator 自带圆头与轨道间隙，
 * 3dp 细条下形变失真，故用双 Box 平铺（旧版 layer-list clip 同构）。
 */
@Composable
private fun MiniProgressBar(percent: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RANK_PROGRESS_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(RANK_PROGRESS_RADIUS_DP))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (percent > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / PROGRESS_SCALE.toFloat())
                    .height(RANK_PROGRESS_HEIGHT_DP.dp)
                    .clip(RoundedCornerShape(RANK_PROGRESS_RADIUS_DP))
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
    }
}

/** 排序切换胶囊：软灰胶囊底（surfaceVariant）+ paddingH 10dp/paddingV 4dp + 11sp primary 色 */
@Composable
private fun SortCapsule(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(PILL_CORNER_RADIUS_DP))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, fontSize = SORT_TOGGLE_TEXT_SP.sp, color = MaterialTheme.colorScheme.primary)
    }
}

// ==================== 分布对比卡（旧版 createDistributionCard/createDistributionRow 同构） ====================

/** 分布单指标（percent 渲染前算好；数量/浏览用 [rankProgressPercent] 同一口径） */
private data class DistributionMetricUi(val name: String, val valueText: String, val percent: Int)

/** 分布行：行标签 + 三指标（数量/大小/浏览）等重横排 */
private data class DistributionRowUi(val label: String, val metrics: List<DistributionMetricUi>)

/**
 * 分布对比卡：标题 14sp Bold + 副标题 11sp 次色（topMargin 2dp）→ 逐行
 * 行标签 13sp Bold（topMargin 12dp）→ 指标行（topMargin 6dp、等重间距 6dp）→
 * 单指标 = 名称 10sp 次色 + 进度条（topMargin 4dp，同排行规格）+ 数值 12sp Bold primary（topMargin 2dp）。
 */
@Composable
private fun DistributionCard(title: String, subtitle: String, rows: List<DistributionRowUi>) {
    StatCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = CARD_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            fontSize = CARD_SUBTITLE_SECONDARY_SP.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        rows.forEach { row ->
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = row.label,
                    fontSize = DISTRIBUTION_LABEL_SP.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(DISTRIBUTION_METRIC_GAP_DP.dp),
                ) {
                    row.metrics.forEach { metric ->
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = metric.name,
                                fontSize = DISTRIBUTION_METRIC_NAME_SP.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MiniProgressBar(
                                percent = metric.percent,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                text = metric.valueText,
                                fontSize = DISTRIBUTION_METRIC_VALUE_SP.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 模式内容装配（旧版 renderFavorites/renderAuthorsTags/renderDistribution 数据口径） ====================

/** 档位 → 洞察行「近X」用字（旧版 rangeLabel 逐字：7天/30天/全部时间，注意「全部时间」非「全部」） */
private fun rangeLabel(range: StatsRangeOption): String = when (range) {
    StatsRangeOption.SEVEN_DAYS -> "7天"
    StatsRangeOption.THIRTY_DAYS -> "30天"
    StatsRangeOption.ALL -> "全部时间"
}

/** 常看文件摘要格（旧版 renderFavorites renderSummary 四格：总浏览/浏览时长/常看文件/平均浏览次数） */
private fun mostViewedSummaryCells(state: StatsDetailUiState): List<SummaryCellUi> = listOf(
    SummaryCellUi("总浏览", formatCountDetail(state.windowViewCount + state.windowPlayCount)),
    SummaryCellUi("浏览时长", formatDurationDetail(state.windowSeconds)),
    SummaryCellUi("常看文件", formatCountDetail(state.filesWithViewRecords)),
    SummaryCellUi(
        "平均浏览次数",
        // 旧版恒 "%.1f"（无数据给 0.0）；协议分母 0 → null，与主页同语义给「—」占位
        state.overviewValues?.avgViewsPerFile?.let { formatAvgViewsDetail(it) } ?: DETAIL_UNAVAILABLE_TEXT,
    ),
)

/** 常看文件洞察行（旧版逐字：「近X共浏览 N 次，停留 D」） */
private fun mostViewedInsightLines(state: StatsDetailUiState, range: StatsRangeOption): List<String> = listOf(
    "近${rangeLabel(range)}共浏览 ${state.windowViewCount + state.windowPlayCount} 次，" +
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
private fun mostViewedRankRows(
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
private fun authorsTagsSummaryCells(state: StatsDetailUiState): List<SummaryCellUi> = listOf(
    // 作者/标签计数=榜条数（协议只回 Top N，窗口内全量计数无直出字段——不足记档交付报告）
    SummaryCellUi("作者", formatCountDetail(state.topAuthors.size)),
    SummaryCellUi("标签", formatCountDetail(state.topTags.size)),
    SummaryCellUi("浏览总次数", formatCountDetail(state.windowViewCount + state.windowPlayCount)),
    SummaryCellUi("Top 作者", state.topAuthors.firstOrNull()?.displayName ?: DETAIL_UNAVAILABLE_TEXT),
)

/** 常看作者与标签洞察行（旧版逐字：「最常看作者「X」，浏览 N 次」/「最常看标签「Y」，浏览 M 次」） */
private fun authorsTagsInsightLines(state: StatsDetailUiState): List<String> = buildList {
    state.topAuthors.firstOrNull()?.let { add("最常看作者「${it.displayName}」，浏览 ${it.views} 次") }
    state.topTags.firstOrNull()?.let { add("最常看标签「${it.tag}」，浏览 ${it.views} 次") }
}

/** 常看作者排行行（旧版同构：副标题「作者」、值「N 次」、进度相对第一名） */
private fun authorRankRows(
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
private fun tagRankRows(
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
private fun distributionSummaryCells(state: StatsDetailUiState): List<SummaryCellUi> {
    val overview = state.overviewValues ?: return emptyList()
    val total = overview.totalFiles.coerceAtLeast(1)
    val animatedCount = (overview.totalFiles - overview.imageCount - overview.videoCount).coerceAtLeast(0)
    val windowViews = state.typeWindowViews.values.sum()
    return listOf(
        SummaryCellUi(IMAGE_DISPLAY_NAME, "${overview.imageCount} (${overview.imageCount * 100 / total}%)"),
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
private fun distributionInsightLines(state: StatsDetailUiState, range: StatsRangeOption): List<String> {
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
private fun typeDistributionRows(state: StatsDetailUiState): List<DistributionRowUi> {
    val overview = state.overviewValues ?: return emptyList()
    val animatedCount = (overview.totalFiles - overview.imageCount - overview.videoCount).coerceAtLeast(0)
    return buildDistributionRows(
        listOf(
            DistRowInput(IMAGE_DISPLAY_NAME, overview.imageCount, overview.imageSizeBytes, state.typeWindowViews[KEY_MEDIA_TYPE_IMAGE] ?: 0),
            DistRowInput(VIDEO_DISPLAY_NAME, overview.videoCount, overview.videoSizeBytes, state.typeWindowViews[KEY_MEDIA_TYPE_VIDEO] ?: 0),
            DistRowInput(ANIMATED_DISPLAY_NAME, animatedCount, overview.animatedImageSizeBytes, state.typeWindowViews[KEY_MEDIA_TYPE_ANIMATED] ?: 0),
        ),
    )
}

/** 来源分布行（常规/COS × 数量/大小/浏览，旧版来源卡同构） */
private fun sourceDistributionRows(state: StatsDetailUiState): List<DistributionRowUi> {
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

// ==================== 常量（除注明外均对齐旧仓库 fragment_stats_detail.xml / StatsDetailFragment.kt 运行时值） ====================

/** 顶栏高（旧版 L15 56dp） */
private const val TOP_BAR_HEIGHT_DP = 56

/** 返回钮边长（旧版 L20-21 32x32dp） */
private const val BACK_BUTTON_SIZE_DP = 32

/** 顶栏标题字号（旧版 L34 18sp） */
private const val TOP_BAR_TITLE_SP = 18

/** 摘要格数值字号（旧版 createSummaryCard 18sp） */
private const val SUMMARY_VALUE_SP = 18

/** 摘要格标签字号（旧版 11sp） */
private const val SUMMARY_LABEL_SP = 11

/** 摘要网格列数（旧版 chunked(2)） */
private const val SUMMARY_GRID_COLUMNS = 2

/** 摘要网格列间距（任务规格 4dp；旧版双 margin 叠加视觉为 8dp，按规格取 4） */
private const val SUMMARY_COLUMN_SPACING_DP = 4

/** 摘要网格行间距（旧版 topMargin 8dp） */
private const val SUMMARY_ROW_SPACING_DP = 8

/** 洞察条目字号（旧版 12sp） */
private const val INSIGHT_ITEM_SP = 12

/** 洞察条目间距（旧版 topMargin 6dp） */
private const val INSIGHT_ITEM_SPACING_DP = 6

/** 卡标题字号（趋势卡/排行卡/分布卡/洞察卡统一 14sp Bold） */
private const val CARD_TITLE_SP = 14

/** 排行卡副标题字号（旧版 listSubtitleText 12sp） */
private const val CARD_SUBTITLE_SP = 12

/** 分布卡副标题字号（旧版 createDistributionCard subtitle 11sp） */
private const val CARD_SUBTITLE_SECONDARY_SP = 11

/** 趋势胶囊高（旧版 L242 34dp） */
private const val TREND_CAPSULE_HEIGHT_DP = 34

/** 趋势胶囊非首项左距（旧版 L245 marginStart 6dp） */
private const val TREND_CAPSULE_GAP_DP = 6

/** 趋势胶囊文字字号（旧版 L238 12sp） */
private const val TREND_CAPSULE_TEXT_SP = 12

/** 胶囊圆角（旧版 bg_capsule_primary/bg_capsule_soft radius 100dp；Dp 非 primitive 只能用 val） */
private val PILL_CORNER_RADIUS_DP = 100.dp

/** 图例色块边长（旧版 LineChartView swatch 8x8dp 方形） */
private const val LEGEND_SWATCH_SIZE_DP = 8

/** 图例色块与文字间距（旧版 gap 4dp） */
private const val LEGEND_GAP_DP = 4

/** 图例文字字号（旧版 legendPaint textSize 10sp） */
private const val LEGEND_TEXT_SP = 10

/** 详情页趋势图固定高（旧版 L215 240dp） */
private const val DETAIL_TREND_CHART_HEIGHT_DP = 240

/** 卡片圆角（旧版 bg_stat_card corners 20dp；Dp 非 primitive 只能用 val） */
private val STAT_CARD_CORNER_RADIUS_DP = 20.dp

/** 区块间距（旧版各容器 marginTop 12dp） */
private const val SECTION_SPACING_DP = 12

/** 排行行间距（旧版 item_rank_list marginBottom 6dp） */
private const val RANK_ROW_SPACING_DP = 6

/** 名次列宽（旧版 rankText layout_width 28dp） */
private const val RANK_COLUMN_WIDTH_DP = 28

/** 名次字号（旧版 16sp Bold） */
private const val RANK_TEXT_SP = 16

/** 行标题字号（旧版 titleText 14sp） */
private const val ROW_TITLE_SP = 14

/** 行副标题字号（旧版 subtitleText 11sp） */
private const val ROW_SUBTITLE_SP = 11

/** 行数值字号（旧版 valueText 14sp Bold） */
private const val ROW_VALUE_SP = 14

/** 排序胶囊文字字号（旧版 sortToggleText 11sp） */
private const val SORT_TOGGLE_TEXT_SP = 11

/** 进度条高（旧版 progressBar layout_height 3dp） */
private const val RANK_PROGRESS_HEIGHT_DP = 3

/** 进度条圆角（旧版 bg_rank_progress corners 2dp；Dp 非 primitive 只能用 val） */
private val RANK_PROGRESS_RADIUS_DP = 2.dp

/** 进度百分比满刻度（旧版 ProgressBar max=100） */
private const val PROGRESS_SCALE = 100

/** 排行行分布行标签字号（旧版 createDistributionRow label 13sp Bold） */
private const val DISTRIBUTION_LABEL_SP = 13

/** 分布指标横排间距（任务规格 6dp；旧版双 margin 叠加视觉为 9dp，按规格取 6） */
private const val DISTRIBUTION_METRIC_GAP_DP = 6

/** 分布指标名称字号（旧版 10sp 次色） */
private const val DISTRIBUTION_METRIC_NAME_SP = 10

/** 分布指标数值字号（旧版 12sp Bold primary） */
private const val DISTRIBUTION_METRIC_VALUE_SP = 12

/** 排名前三名高亮阈值（旧版 RankListAdapter position < 3） */
private const val TOP_RANK_HIGHLIGHT = 3

/** 分布模式协议 mediaType key（openapi MediaType 枚举三值，键单源 MediaTypeKeys） */
private const val KEY_MEDIA_TYPE_IMAGE = MediaTypeKeys.IMAGE
private const val KEY_MEDIA_TYPE_VIDEO = MediaTypeKeys.VIDEO
private const val KEY_MEDIA_TYPE_ANIMATED = MediaTypeKeys.ANIMATED_IMAGE

/** 分布模式协议 source key（openapi /stats/trends source 枚举 normal|cos，键单源 SourceKeys） */
private const val KEY_SOURCE_NORMAL = SourceKeys.NORMAL
private const val KEY_SOURCE_COS = SourceKeys.COS

/** 类型趋势卡标题（旧版顶栏与卡标题同字） */
private const val TYPE_TREND_CARD_TITLE = "类型浏览趋势"

/** 来源趋势卡标题（旧版 buildTrendCard 逐字） */
private const val SOURCE_TREND_CARD_TITLE = "来源浏览趋势"

/** 分布对比卡标题与副标题（旧版 createDistributionCard 调用点逐字） */
private const val TYPE_DISTRIBUTION_CARD_TITLE = "类型分布对比"
private const val TYPE_DISTRIBUTION_CARD_SUBTITLE = "库存数量/大小 vs 窗口浏览"
private const val SOURCE_DISTRIBUTION_CARD_TITLE = "来源分布对比"
private const val SOURCE_DISTRIBUTION_CARD_SUBTITLE = "常规 vs COS"

/** 分布模式顶栏标题（陷阱#8：不带档位后缀，旧版 observeDistributionMode 逐字） */
private const val DISTRIBUTION_DETAIL_TITLE = "分布统计详情"

/** 常看排行卡标题——按时长（seconds）档保留旧版逐字（2026-09-18 批：按热度档改用内容榜标题） */
private const val FILES_RANK_CARD_TITLE = "常看排行"

/** 内容榜卡标题（2026-09-18 批：按热度档数据源换 GET /rankings，标题随档与主页顶卡同字） */
private const val CONTENT_RANK_CARD_TITLE = "内容榜"

/** 作者/标签排行卡标题（旧版 tagListTitleText/listTitleText 逐字） */
private const val AUTHORS_CARD_TITLE = "常看作者"
private const val TAGS_CARD_TITLE = "常看标签"

/** 作者/标签排行卡副标题（旧版逐字「按浏览聚合 · Top 15/10」） */
private const val AUTHORS_CARD_SUBTITLE = "按浏览聚合 · Top 15"
private const val TAGS_CARD_SUBTITLE = "按浏览聚合 · Top 10"

/** 排序胶囊两档文案（旧版 setupFilesSortToggle 逐字） */
private const val SORT_BY_HEAT_TEXT = "按热度"
private const val SORT_BY_SECONDS_TEXT = "按时长"

/** 洞察卡标题（旧版 renderInsights 逐字） */
private const val INSIGHT_TITLE = "数据洞察"

/** 名次占位符（旧版空态行 rankText 逐字） */
private const val RANK_PLACEHOLDER_TEXT = "—"

/** 协议缺字段占位（与主页冻结占位「—」同语义） */
private const val DETAIL_UNAVAILABLE_TEXT = "—"

/** 加载中文案 */
private const val LOADING_TEXT = "加载中…"

/** 空态文案（GUIDE_UI L247「暂无数据」） */
private const val EMPTY_TEXT = "暂无数据"
