package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.GridSection
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.ui.theme.QimengDimens

/** 距底预加载阈值（LEGACY §H：距底 ≤6 项提前加载；数值与 RecommendPaging.PRELOAD_DISTANCE 同语义，
 *  但网格组件不依赖具体业务模块，此处独立成 UI 常量） */
private const val GRID_PRELOAD_DISTANCE = 6

/** 视频类型（时长角标仅视频渲染）——与领域 MediaKind.VIDEO 对应的本地引用 */
private val DURATION_BADGE_TYPES = setOf(MediaKind.VIDEO)

/**
 * 共享媒体网格：分组段组头（跨全列）+ 资产卡片 + 距底预载回调 + 列数可调。
 * 相册/收藏/历史/搜索/首页 COS 流共用；首页推荐流的分批展示由调用方把已揭示条目
 * 组成单个无组头段（label 空串段不渲染组头）传入。
 *
 * @param sections 分组段；组内保持列表原序
 * @param loadThumbnail 字节加载器（透传给 [QimengThumbnail]）
 * @param onNearBottom 可见末项距列表尾 ≤[GRID_PRELOAD_DISTANCE] 项时回调（去重由调用方负责）
 * @param bottomContentPadding 列表底部预留（clipToPadding=false 语义）——默认与网格间距同档；
 *   相册页传 [QimengDimens.ListBottomContentPadding]（180dp，防悬浮药丸面板遮挡末行，
 *   旧版 fragment_all_files.xml L149）
 * @param pauseThumbnailsWhileScrolling 滚动暂停缩略图加载（任务I I5，GUIDE_UI §浏览历史
 *   L394 / §收藏页 L411）：网格滚动进行中（拖拽/惯性 fling 均 true，[LazyGridState]
 *   .isScrollInProgress 口径）暂缓新缩略图请求、停滚自动恢复（门控在 [QimengThumbnail]）。
 *   默认 false——首页/搜索/全部页既有调用方行为零变化
 */
@Composable
fun QimengMediaGrid(
    sections: List<GridSection>,
    columns: Int,
    animatedUrlResolver: suspend (String) -> String?,
    modifier: Modifier = Modifier,
    listState: LazyGridState = rememberLazyGridState(),
    bottomContentPadding: Dp = QimengDimens.SpaceM,
    // 无默认空实现（审查清偿）：默认 {} 让漏传接线静默无反应（D3 同族四页 bug 根因），
    // 必传参数把漏接变成编译错误
    onAssetClick: (MediaAsset) -> Unit,
    onNearBottom: () -> Unit = {},
    pauseThumbnailsWhileScrolling: Boolean = false,
) {
    // 扁平化为 (header?, asset?) 序列：组头跨全列，卡片单列
    data class Cell(val header: String?, val asset: MediaAsset?)

    val cells = remember(sections) {
        buildList {
            sections.forEach { section ->
                if (section.label.isNotEmpty()) add(Cell(header = section.label, asset = null))
                section.items.forEach { add(Cell(header = null, asset = it)) }
            }
        }
    }
    val totalCount = cells.size

    // 距底哨兵：可见末项接近总尾即回调（LaunchedEffect 挂 listState 一次性收集快照流）
    val shouldLoadMore by remember(totalCount) {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex >= totalCount - 1 - GRID_PRELOAD_DISTANCE
        }
    }
    LaunchedEffect(shouldLoadMore, totalCount) {
        if (totalCount > 0 && shouldLoadMore) onNearBottom()
    }

    // 滚动进行中观测（derivedStateOf：仅 isScrollInProgress 翻转时才让卡片层重组）
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    val thumbnailsPaused = pauseThumbnailsWhileScrolling && scrolling

    LazyVerticalGrid(
        state = listState,
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        contentPadding = PaddingValues(
            start = QimengDimens.SpaceM,
            top = QimengDimens.SpaceM,
            end = QimengDimens.SpaceM,
            bottom = bottomContentPadding,
        ),
    ) {
        items(
            cells,
            key = { cell -> cell.asset?.id ?: "header:${cell.header}" },
            // 组头跨整行（任务J J2，台账 #36 用户拍板「对齐旧版」）：日期组头独占一行，
            // 不再占单列与首卡同行——KDoc「组头跨全列」自此与实现相符（I6/I9 实证不符项清偿）。
            // 影响全部网格页（含冻结的全部页）=H1 同款穿透豁免口径，用户已拍板授权。
            span = { cell ->
                if (cell.header != null) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
        ) { cell ->
            val header = cell.header
            if (header != null) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = QimengDimens.SpaceXS),
                )
            } else {
                val asset = cell.asset
                if (asset != null) {
                    AssetCard(
                        asset = asset,
                        animatedUrlResolver = animatedUrlResolver,
                        paused = thumbnailsPaused,
                        onClick = { onAssetClick(asset) },
                    )
                }
            }
        }
    }
}

/**
 * 资产卡片：缩略图（16:9）+ 标题一行 + 视频时长角标（旧版：纯文字时长，不使用胶囊底）+
 * meta 行（作者 + 日期，任务G G5 对齐 Web MediaCard 四层 图/标题/up/date）。
 * 详情跳转是 M4-3 交界：onClick 已预留，本批由壳层决定行为。
 * 动图（animated_image）走原件直链动画（拍板条目 9）：解析经 [animatedUrlResolver]
 * （VM 侧带内存缓存的 AssetOrigUrlResolver），解析完成前显示服务端缩略图。
 */
@Composable
private fun AssetCard(
    asset: MediaAsset,
    animatedUrlResolver: suspend (String) -> String?,
    paused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbModel = if (asset.mediaType == MediaKind.ANIMATED_IMAGE) {
        var origUrl by remember(asset.id) { mutableStateOf<String?>(asset.thumbUrl) }
        LaunchedEffect(asset.id) {
            animatedUrlResolver(asset.id)?.let { origUrl = it }
        }
        origUrl
    } else {
        asset.thumbUrl
    }
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = QimengDimens.CardTonalElevation,
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().thumbnailAspectRatio()) {
                QimengThumbnail(
                    model = thumbModel,
                    contentDescription = asset.title,
                    paused = paused,
                    modifier = Modifier.fillMaxSize(),
                )
                if (asset.mediaType in DURATION_BADGE_TYPES) {
                    val badge = formatDurationBadge(asset.durationMs)
                    if (badge != null) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(QimengDimens.SpaceXS)
                                .clip(RoundedCornerShape(QimengDimens.BadgeCornerRadius))
                                .padding(QimengDimens.SpaceXXS),
                        )
                    }
                }
            }
            Text(
                text = asset.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = QimengDimens.SpaceS, vertical = QimengDimens.SpaceXS),
            )
            Row(
                modifier = Modifier.padding(
                    start = QimengDimens.SpaceS,
                    end = QimengDimens.SpaceS,
                    bottom = QimengDimens.SpaceS,
                ),
            ) {
                val up = asset.authorNames.firstOrNull() ?: asset.source
                // 日期行（任务G G5：Web MediaCard 第四层，date=formatShortDate(modifiedAt)——
                // Android 同字段 modifiedAtMs、同格式「M-D」（[formatShortDate] 口径注释对照 Web）
                val date = formatShortDate(asset.modifiedAtMs)
                if (up != null) {
                    Text(
                        text = up,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 作者名占剩余宽：日期钉在行尾不被长名挤掉（Web card--meta 同行 flex 布局）
                        modifier = Modifier.weight(1f),
                    )
                    if (date.isNotEmpty()) Spacer(modifier = Modifier.width(QimengDimens.SpaceXS))
                }
                if (date.isNotEmpty()) {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
