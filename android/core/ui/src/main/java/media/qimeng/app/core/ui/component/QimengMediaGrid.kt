package media.qimeng.app.core.ui.component

import android.content.Context
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import media.qimeng.app.core.model.GridSection
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.ui.theme.QimengDimens

/** 距底预加载阈值（LEGACY §H：距底 ≤6 项提前加载；数值与 RecommendPaging.PRELOAD_DISTANCE 同语义，
 *  但网格组件不依赖具体业务模块，此处独立成 UI 常量） */
private const val GRID_PRELOAD_DISTANCE = 6

/** 网格格子：组头（跨全列）或资产卡（单列）；[flattenGridCells] 产物。internal 供 JVM 单测锁定 */
internal data class GridCell(val header: String?, val asset: MediaAsset?)

/**
 * 分组段扁平化 + 按资产 id 防御性去重（exp#5，2026-09-10 ui/expressive 分支）。
 *
 * 为什么在 key 铸造点去重：本组件用 asset.id 作 LazyVerticalGrid 项 key，服务端单响应若含
 * 重复资产即同屏双卡同 key（LazyVerticalGrid 重复 key 直接崩）。
 * 服务端各列表端点均无「单响应内 id 唯一」的协议承诺，此处按 id 收敛是最后的防御点，
 * 一处覆盖共用本组件的全部网格页（首页三流/相册/收藏/历史/搜索/作者集合）。
 *
 * 语义：顺序保持首现位（distinctBy 语义，后现重复丢弃）、跨段去重、组头不参与去重
 * （组头恒渲染）；正常数据（无重复）输出与不去重版本逐格相同——零语义变化，仅在
 * 异常数据下防崩防冲突。组头所在段即便条目全为重复也不删段（空段头保留，防御路径
 * 不做美学裁剪）。纯函数不碰 IO；internal 供 JVM 单测锁定。
 *
 * 回退（exp#5）：调用点改回内联不去重 buildList + 删本函数与 GridCell + 删单测。
 */
internal fun flattenGridCells(sections: List<GridSection>): List<GridCell> {
    val seenAssetIds = HashSet<String>()
    return buildList {
        sections.forEach { section ->
            if (section.label.isNotEmpty()) add(GridCell(header = section.label, asset = null))
            section.items.forEach { asset ->
                if (seenAssetIds.add(asset.id)) add(GridCell(header = null, asset = asset))
            }
        }
    }
}

/** 视频类型（时长角标仅视频渲染）——与领域 MediaKind.VIDEO 对应的本地引用 */
private val DURATION_BADGE_TYPES = setOf(MediaKind.VIDEO)

/** 网格项 contentType 两型（修复D-2，2026-09-14 相册胶囊丢响应调研定案）：组头与资产卡
 *  结构迥异（跨全列文本 vs 缩略图卡），分型后 Lazy 网格复用池按型匹配、同型项复用组合，
 *  滚动往复不再跨型重组合 */
private const val GRID_CELL_TYPE_HEADER = "gridHeader"
private const val GRID_CELL_TYPE_ASSET = "gridAsset"

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

// ---------- 详情海报点击预载（exp#4 预载翼；任务W W1 撤动效后保留——点击抢跑热身详情舞台首帧） ----------

/**
 * 预载产物必须写内存缓存（与详情页既有预载链 DetailScreen DisposableEffect 同款
 * CachePolicy.ENABLED）：点击进场后的详情舞台首帧要的是「同步命中内存缓存」——只落
 * 磁盘缓存对首帧无意义，这是本翼存在的全部目的。
 */
private val DETAIL_POSTER_PRELOAD_CACHE_POLICY = CachePolicy.ENABLED

/**
 * 网格卡点击瞬间对目标资产海报 URL 发 Coil 预载（发射后不管：不挂组合生命周期，
 * 离场不取消——取消就失去了「导航前抢跑」的意义；产物进内存缓存由单例 ImageLoader 管理）。
 *
 * 为什么：详情舞台海报的加载请求只在详情页组合后才发起，慢于进场瞬间时首帧是空舞台
 * （占位灰）。点击即入队把加载提前到导航前，详情页首帧大概率直接命中内存缓存。详情侧
 * 既有预载链只覆盖「邻位切换窗口」，不含「点击进场」这第一步，本预载与之互补不替代。
 * （原为 exp#4 前进转场竞态修复·预载翼，任务W W1 撤动效后保留。）
 *
 * 边界诚实记档：图片资产详情舞台渲的是**原图直链**（签名直链须详情侧解析，网格只持
 * 缩略图直链），对这类资产本预载只热身缩略图、原图仍由详情侧加载——详情舞台的 exp#4
 * 灰色占位翼已撤（2026-09-14 用户反馈深色模式灰块突兀 + 旧版无此形态，撤除记档见
 * ZoomableOriginalImage 注释），首帧可见性仅剩本预载的缓存热身、尽力而为；视频（海报
 * 帧=缩略图直链）与动图（卡上已持解析后的原件直链）两端 URL 同源，仍是本翼的主受益
 * 形态。请求形状对齐详情预载链（视频海报帧=
 * 默认档、图片/动图=Size.ORIGINAL 不降采样），同形才能复用同一条内存缓存键。
 *
 * 回退（exp#4 预载翼）：删 AssetCard onClick 内 preloadDetailPoster(...) 调用 +
 * 本函数与本节常量，其余零改动。
 */
private fun preloadDetailPoster(context: Context, mediaType: MediaKind, posterUrl: String?) {
    if (posterUrl.isNullOrEmpty()) return
    val request = ImageRequest.Builder(context)
        .data(posterUrl)
        .memoryCachePolicy(DETAIL_POSTER_PRELOAD_CACHE_POLICY)
        .apply {
            if (mediaType != MediaKind.VIDEO) {
                size(Size.ORIGINAL)
                // 原件不落盘（2026-09-18 对齐 DetailScreen 预载链/U10-5 口径）：本预载
                // 目的=内存热身（见 DETAIL_POSTER_PRELOAD_CACHE_POLICY 注），原件落盘
                // 只会挤爆 LRU 档位把小缩略图淘掉；视频海报帧=小缩略图，保持落盘省流量
                diskCachePolicy(CachePolicy.DISABLED)
            }
        }
        .build()
    SingletonImageLoader.get(context).enqueue(request)
}

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
 * @param tightenLeadingHeader 收紧列表首个组头的上内边距（修复C，2026-09-14 作者页用户
 *   反馈「下面的文件时间离胶囊过远」）：默认 false=组头恒 18dp 上距（旧版
 *   GroupedMediaAdapter setPadding(4,18,4,10) 对齐档，语义=与上一组末卡隔断）；
 *   true=列表首格恰为组头时该 18dp 让位——列表顶部无「上一组末卡」，此 18dp 属冗余层，
 *   胶囊/标题行→首组头间距收敛为网格自带顶距 SpaceM=8dp（4-8dp 档）。仅作者集合页开启。
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
    tightenLeadingHeader: Boolean = false,
) {
    // 扁平化为 (header?, asset?) 序列：组头跨全列，卡片单列（含按 id 防御性去重，见函数 KDoc）
    val cells = remember(sections) { flattenGridCells(sections) }
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
        // 顶部间距=视口外（任务X X6，2026-09-12 用户问题9）：旧版各网格 RecyclerView 是
        // layout_marginTop（视口外，滚动全程顶部间隙恒定，clipToPadding=true 语义）；此前
        // 误放 contentPadding(top)——Lazy 系 contentPadding 是视口内 padding，内容可滚入
        // padding 带，下滑后「胶囊行→缩略图」间隙归零（观感=空白消失）。挪到 modifier
        // padding 后内容在网格视口顶边被裁剪，间隙恒定=对齐旧版滚动语义；值保持 SpaceM
        // 不变=静止几何不变，只改滚动语义。bottom 仍留 contentPadding（clipToPadding=false
        // 防遮挡语义，相册/收藏/历史/作者集合页 180dp 档刻意滚入，本批不动）。
        modifier = modifier.padding(top = QimengDimens.SpaceM).fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(GRID_INTER_ITEM_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_INTER_ITEM_SPACING),
        contentPadding = PaddingValues(
            start = QimengDimens.SpaceM,
            end = QimengDimens.SpaceM,
            bottom = bottomContentPadding,
        ),
    ) {
        itemsIndexed(
            cells,
            key = { _, cell -> cell.asset?.id ?: "header:${cell.header}" },
            // 组头跨整行（任务J J2，台账 #36 用户拍板「对齐旧版」）：日期组头独占一行，
            // 不再占单列与首卡同行——KDoc「组头跨全列」自此与实现相符（I6/I9 实证不符项清偿）。
            // 影响全部网格页（含冻结的全部页）=H1 同款穿透豁免口径，用户已拍板授权。
            span = { _, cell ->
                if (cell.header != null) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
            // 修复D-2：contentType 两型（组头/资产卡，常量见上），同型复用组合
            contentType = { _, cell ->
                if (cell.header != null) GRID_CELL_TYPE_HEADER else GRID_CELL_TYPE_ASSET
            },
        ) { index, cell ->
            val header = cell.header
            if (header != null) {
                Text(
                    text = header,
                    // Y3 批（2026-09-12 全局字体对齐旧版）：组头样式对齐旧版 GroupedMediaAdapter.kt
                    // L184-188——16f + DEFAULT_BOLD（titleSmall 14sp Medium 组件级覆盖字号/字重）+
                    // 色 qmColorPrimary（Theme.kt colorPrimary→primary 槽，浅 #3A3A3A/夜 #C8C8C8；
                    // 此前 onSurfaceVariant 浅灰不符）+ 内边距 setPadding(4,18,4,10)（此前仅
                    // top=SpaceXS=4dp，与旧版 18dp 上距/10dp 下距不符）。
                    // 修复C（2026-09-14）：[tightenLeadingHeader] 开启时列表首格组头 18dp 上距
                    // 让位（作者页芯片行→首组头 26dp→8dp，记档见参数 KDoc）；中段组头语义不变。
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 4.dp,
                            top = if (index == 0 && tightenLeadingHeader) 0.dp else 18.dp,
                            end = 4.dp,
                            bottom = 10.dp,
                        ),
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
    // 动图原件解析（拍板条目 9：动图卡必须动画）：解析完成前先渲服务端缩略图。
    // animatedOrigUrl=null 表示静态图，或动图尚未解析完成（此刻 model 仍是缩略图直链）
    var animatedOrigUrl by remember(asset.id) { mutableStateOf<String?>(null) }
    if (asset.mediaType == MediaKind.ANIMATED_IMAGE) {
        LaunchedEffect(asset.id) {
            animatedOrigUrl = animatedUrlResolver(asset.id)
        }
    }
    val thumbModel = animatedOrigUrl ?: asset.thumbUrl
    // 原件不落盘（2026-09-18，U10-5 口径外延到网格）：动图卡一旦切到原件直链即关磁盘
    // 缓存——原件体积大，落盘会挤爆 LRU 档位把小缩略图淘掉（用户实测本地缓存 1.7GB vs
    // 服务端缩略图仅 155MB 的主因）；缩略图阶段/静态图照常落盘
    val diskCacheEnabled = animatedOrigUrl == null
    // 旧版圆角是像素值：运行时按屏幕密度换算（KDoc 规格要求的 toDp() 写法）
    val cornerRadius = with(LocalDensity.current) { LEGACY_CARD_CORNER_RADIUS_PX.toDp() }
    // exp#4 预载翼的入队上下文（单例 ImageLoader 经 context 取，与详情预载链同源）
    val context = LocalContext.current
    Box(
        modifier = modifier
            .padding(CARD_OUTER_PADDING)
            .fillMaxWidth()
            .thumbnailAspectRatio()
            // 先 clip 后 clickable：ripple 限定在圆角内；仅默认点击态，无缩放/按压动画
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(onClick = {
                // exp#4 预载翼：导航前抢跑海报加载（为什么见 preloadDetailPoster KDoc）；
                // 卡上持有的正是详情舞台将渲的同一 URL（视频/动图），预载→转场首帧命中
                preloadDetailPoster(context, asset.mediaType, thumbModel)
                onClick()
            }),
    ) {
        QimengThumbnail(
            model = thumbModel,
            contentDescription = asset.title,
            paused = paused,
            diskCacheEnabled = diskCacheEnabled,
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
