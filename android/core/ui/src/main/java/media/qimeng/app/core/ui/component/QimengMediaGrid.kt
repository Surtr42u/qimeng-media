package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.GridSection
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind

/** 距底预加载阈值（LEGACY §H：距底 ≤6 项提前加载；数值与 RecommendPaging.PRELOAD_DISTANCE 同语义，
 *  但网格组件不依赖具体业务模块，此处独立成 UI 常量） */
private const val GRID_PRELOAD_DISTANCE = 6

/** 网格横向间距 */
private val GRID_SPACING = 8.dp

/** 卡片圆角（旧版 §UI 约束：卡片 16dp） */
private val CARD_CORNER_RADIUS = 16.dp

/** 视频类型（时长角标仅视频渲染）——与领域 MediaKind.VIDEO 对应的本地引用 */
private val DURATION_BADGE_TYPES = setOf(MediaKind.VIDEO)

/**
 * 共享媒体网格：日期分组段组头（跨全列）+ 资产卡片 + 距底预载回调 + 列数可调。
 * 相册/收藏/历史/搜索/首页 COS 流共用；首页推荐流的分批展示由调用方把已揭示条目
 * 组成单个无组头段（label 空串段不渲染组头）传入。
 *
 * @param sections 分组段；组内保持列表原序
 * @param loadThumbnail 字节加载器（透传给 [QimengThumbnail]）
 * @param onNearBottom 可见末项距列表尾 ≤[GRID_PRELOAD_DISTANCE] 项时回调（去重由调用方负责）
 */
@Composable
fun QimengMediaGrid(
    sections: List<GridSection>,
    columns: Int,
    animatedUrlResolver: suspend (String) -> String?,
    modifier: Modifier = Modifier,
    listState: LazyGridState = rememberLazyGridState(),
    onAssetClick: (MediaAsset) -> Unit = {},
    onNearBottom: () -> Unit = {},
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

    LazyVerticalGrid(
        state = listState,
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GRID_SPACING),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(GRID_SPACING),
    ) {
        items(cells, key = { cell -> cell.asset?.id ?: "header:${cell.header}" }) { cell ->
            val header = cell.header
            if (header != null) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            } else {
                val asset = cell.asset
                if (asset != null) {
                    AssetCard(asset = asset, animatedUrlResolver = animatedUrlResolver, onClick = { onAssetClick(asset) })
                }
            }
        }
    }
}

/**
 * 资产卡片：缩略图（16:9）+ 标题一行 + 视频时长角标（旧版：纯文字时长，不使用胶囊底）。
 * 详情跳转是 M4-3 交界：onClick 已预留，本批由壳层决定行为。
 * 动图（animated_image）走原件直链动画（拍板条目 9）：解析经 [animatedUrlResolver]
 * （VM 侧带内存缓存的 AssetOrigUrlResolver），解析完成前显示服务端缩略图。
 */
@Composable
private fun AssetCard(
    asset: MediaAsset,
    animatedUrlResolver: suspend (String) -> String?,
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
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().thumbnailAspectRatio()) {
                QimengThumbnail(
                    model = thumbModel,
                    contentDescription = asset.title,
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
                                .padding(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .padding(2.dp),
                        )
                    }
                }
            }
            Text(
                text = asset.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Row(modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
                val meta = asset.authorNames.firstOrNull() ?: asset.source
                if (meta != null) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}
