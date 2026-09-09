package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.model.GridSection
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.ui.motion.qimengAssetPosterSharedBounds
import media.qimeng.app.core.ui.theme.QimengDimens

/** 距底预加载阈值（LEGACY §H：距底 ≤6 项提前加载；数值与 RecommendPaging.PRELOAD_DISTANCE 同语义，
 *  但网格组件不依赖具体业务模块，此处独立成 UI 常量） */
private const val GRID_PRELOAD_DISTANCE = 6

/** 视频类型（时长角标仅视频渲染）——与领域 MediaKind.VIDEO 对应的本地引用 */
private val DURATION_BADGE_TYPES = setOf(MediaKind.VIDEO)

// ---------- 旧版极简卡视觉参数（任务L L1，2026-09-09 拍板；来源=旧仓库 item_media_thumbnail.xml，勿改值） ----------

/** 旧版卡片外层 padding=5dp（item_media_thumbnail.xml root padding；5dp 不在既有间距档内，独立常量） */
private val CARD_OUTER_PADDING = 5.dp

/** 网格项间额外间距=0（任务L L1 补齐拍板：旧版 RecyclerView 项间无间距，卡间视觉距=
 *  卡片自 padding 5dp×2=10dp；此前 spacedBy(SpaceM) 使总距 18dp 偏疏。卡片自 padding
 *  由 [CARD_OUTER_PADDING] 提供，此处归零即可回归旧版密度） */
private val GRID_INTER_ITEM_SPACING = 0.dp

/** 旧版圆角 outline 24f——**像素**值非 dp（item_media_thumbnail.xml outline radius 24f），
 *  使用处经 [LocalDensity] 运行时 toDp() 换算，不同密度设备观感一致 */
private const val LEGACY_CARD_CORNER_RADIUS_PX = 24f

/** 时长角标文字样式：白字 12sp + 阴影、无胶囊底（旧版 §缩略图口径；G5 的 clip 胶囊底已删）。
 *  阴影保证浅色画面上可读——规格只要求「有阴影」未定参数，取常规柔和档：
 *  黑 60% + 纵向偏移 1px + 模糊 4px（Shadow 单位=像素，与旧版 shadowDx/Dy/Radius 同口径） */
private val DurationBadgeTextStyle = TextStyle(
    fontSize = 12.sp,
    color = Color.White,
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.6f),
        offset = Offset(0f, 1f),
        blurRadius = 4f,
    ),
)

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
        horizontalArrangement = Arrangement.spacedBy(GRID_INTER_ITEM_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_INTER_ITEM_SPACING),
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
 * 资产卡片：旧版极简卡（任务L L1，2026-09-09 拍板，覆盖 G5「Web MediaCard 四层」）——
 * 结构 = 16:9 缩略图 + 视频时长纯文字角标，**无标题 / 无作者 / 无日期**
 * （旧版 item_media_thumbnail.xml 实测口径）。
 * - 外层 padding [CARD_OUTER_PADDING]（旧版 root padding=5dp）；
 * - 圆角 [LEGACY_CARD_CORNER_RADIUS_PX] 为旧版**像素**值，经 [LocalDensity] 运行时换算 dp；
 * - 占位/错误底 = 旧版 qmColorChipBg 等价主题 token（在 [QimengThumbnail] 内，secondaryContainer）；
 * - 角标 [DurationBadgeTextStyle] 白字 12sp + 阴影、右下 8dp、无胶囊底；
 * - 无按下缩放动画（旧版无 scale/press 效果，保持 [clickable] 默认点击态即可，禁止再加缩放修饰）。
 * 动图（animated_image）走原件直链动画（拍板条目 9）：解析经 [animatedUrlResolver]
 * （VM 侧带内存缓存的 AssetOrigUrlResolver），解析完成前显示服务端缩略图。
 * 详情跳转是 M4-3 交界：onClick 由壳层接线。
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
    // 旧版圆角是像素值：运行时按屏幕密度换算（KDoc 规格要求的 toDp() 写法）
    val cornerRadius = with(LocalDensity.current) { LEGACY_CARD_CORNER_RADIUS_PX.toDp() }
    Box(
        modifier = modifier
            .padding(CARD_OUTER_PADDING)
            .fillMaxWidth()
            .thumbnailAspectRatio()
            // exp#3 合入（任务V V1，2026-09-10）：与详情页舞台同 key 配对；scope 缺位
            // （非网格路由页面/壳层未包 SharedTransitionLayout）时原样返回，渲染零变化。
            // 回退=删此行（回退 exp#3 见 motion/QimengSharedTransition.kt 头注释）
            .qimengAssetPosterSharedBounds(asset.id)
            // 先 clip 后 clickable：ripple 限定在圆角内；仅默认点击态，无缩放/按压动画
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick),
    ) {
        QimengThumbnail(
            model = thumbModel,
            contentDescription = asset.title,
            paused = paused,
            modifier = Modifier.fillMaxSize(),
        )
        if (asset.mediaType in DURATION_BADGE_TYPES) {
            formatDurationBadge(asset.durationMs)?.let { badge ->
                Text(
                    text = badge,
                    style = DurationBadgeTextStyle,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(QimengDimens.SpaceM), // 旧版角标距右下 8dp（SpaceM 同档）
                )
            }
        }
    }
}
