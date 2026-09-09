package media.qimeng.app.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import media.qimeng.app.core.model.GridSection
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.TabScrollController
import media.qimeng.app.core.ui.theme.QimengDimens
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

/** 首页标题（GUIDE_UI §首页 顶行） */
private const val HOME_TITLE = "首页"

/** 首页在壳导航里的路由（双击 Tab 回顶事件的过滤键） */
private const val HOME_ROUTE = "home"

/** tab → 文案资源映射（文案在 strings.xml；枚举不再携带 UI 文案——2026-09-06 审查卫生项） */
private fun HomeTab.tabLabelRes(): Int = when (this) {
    HomeTab.RECOMMEND -> R.string.home_tab_recommend
    HomeTab.COS -> R.string.home_tab_cos
    HomeTab.RANK -> R.string.home_tab_rank
}

/** pager↔chip 同步对齐日志 tag（实机高频验收 grep 用，同 QimengM42 先例） */
private const val PAGER_SYNC_LOG_TAG = "QimengL37"

/**
 * 对齐判定的偏移容差（页宽分数）：snap 落点理论精确为 0，容差只滤浮点残差、防「残差≠0 →
 * 无限重试」死循环；真实卡半屏的中间偏移（如 0.4）远超此值必判未对齐。
 */
private const val SNAP_ALIGNMENT_EPSILON_FRACTION = 0.01f

/**
 * pager 与目标 tab 是否完全对齐（任务L L3 #37 拍板 A：settled 对齐）。
 * 只看 currentPage 不够——动画被打断后 pager 可停在中间偏移而 currentPage 已等于目标，
 * 对齐判定永真即永久卡半屏；补 currentPageOffsetFraction 把「停半路」与「真落定」区分开。
 * 纯函数（internal）供单测锁定契约，UI 时序本身以实机高频验收代验。
 */
internal fun isPagerAlignedWithTab(
    currentPage: Int,
    pageOffsetFraction: Float,
    targetTabOrdinal: Int,
): Boolean = currentPage == targetTabOrdinal &&
    abs(pageOffsetFraction) <= SNAP_ALIGNMENT_EPSILON_FRACTION

/**
 * pager→tab 回写门控（任务L L3 #37 拍板 B）：滚动/程序化动画在途（isScrollInProgress）时
 * 发 null=不回写 VM，落定才回写——斩断「程序化翻页途经中间页 → switchTab 中途劫持 → 反向
 * 打断动画」的 chip↔pager 同步环。为什么门必须在 snapshotFlow 求值内：落定瞬间门开触发再求值、
 * 补发最后一次 currentPage；若在 collect 侧判门，拖拽中最后一次发射被吞后 chip 永不跟随。
 * 纯函数（internal）供单测锁定契约。
 */
internal fun pagerPageForTabSync(isScrollInProgress: Boolean, currentPage: Int): Int? =
    if (isScrollInProgress) null else currentPage

/**
 * 首页（M4-2）：顶行[标题][搜索框不可聚焦→跳搜索页][网格图标] +
 * 推荐/COS/排行榜 三 tab（HorizontalPager 左右横滑切换）+ 各 tab 独立缓存 + 下拉刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val columns by viewModel.homeColumns.collectAsStateWithLifecycle()
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }

    // 点赞后返回自动重排（GUIDE_UI §下拉刷新 L89，likeVersion 指纹维度）：返回/回前台
    // （ON_RESUME，覆盖详情页 pop 返回与 App 回前台两路径）对比点赞变更指纹，变化则由 VM
    // 重拉当前 tab。上报点在详情页点赞成功处（I7 批接线，见 LikeMutationTracker KDoc）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onHomeResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 双击「首页」Tab 回顶：三个 tab 各自的滚动状态（pager 页销毁不丢数据，状态在 VM）
    val recommendListState = rememberLazyGridState()
    val cosListState = rememberLazyGridState()
    val rankListState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        TabScrollController.events.collectLatest { route ->
            if (route == HOME_ROUTE) {
                android.util.Log.d("QimengM42", "scroll-to-top route=$HOME_ROUTE")
                when (state.currentTab) {
                    HomeTab.RECOMMEND -> recommendListState.scrollToItem(0)
                    HomeTab.COS -> cosListState.scrollToItem(0)
                    HomeTab.RANK -> rankListState.scrollToItem(0)
                }
            }
        }
    }

    // chip 点击 ↔ 横滑 双向同步（任务L L3 #37 高频卡半屏修复，拍板 A+B 同做）。
    // 旧实现两根因：
    // ① chip→pager 以「currentPage == 目标」单次 animateScrollToPage——高频连点重启
    //    LaunchedEffect 打断在途动画后，pager 停在中间偏移而 currentPage 已等于目标，
    //    对齐判定永真，永久卡半屏（首页→排行榜被拽回 COS 半屏的主因）；
    // ② pager→chip 用 currentPage 无门控回写——程序化翻页途经中间页时 switchTab 被中途
    //    劫持，再反向打断动画（帮凶）。
    // 修法：② 经 pagerPageForTabSync 门控，滚动在途不回写，斩断同步环；① 对齐判定加
    // currentPageOffsetFraction（isPagerAlignedWithTab），未对齐就重试 animateScrollToPage
    // 直至落定，重试前先等 isScrollInProgress 归假（不与手指/在途滚动抢 mutator）。
    // #35 哨兵抑制（switchTab 500ms 窗口）未被触碰：回写仍走 switchTab 单点。
    val pagerState = rememberPagerState(initialPage = state.currentTab.ordinal) { HomeTab.entries.size }
    LaunchedEffect(state.currentTab) {
        val targetTabOrdinal = state.currentTab.ordinal
        while (!isPagerAlignedWithTab(
                currentPage = pagerState.currentPage,
                pageOffsetFraction = pagerState.currentPageOffsetFraction,
                targetTabOrdinal = targetTabOrdinal,
            )
        ) {
            // 手势/在途滚动（含上一轮被打断动画释放 mutator）结束前不动 pager
            snapshotFlow { pagerState.isScrollInProgress }.first { !it }
            // 等待期间可能已被落定回写等路径对齐，复核后再动
            if (isPagerAlignedWithTab(
                    currentPage = pagerState.currentPage,
                    pageOffsetFraction = pagerState.currentPageOffsetFraction,
                    targetTabOrdinal = targetTabOrdinal,
                )
            ) {
                break
            }
            pagerState.animateScrollToPage(targetTabOrdinal)
        }
        android.util.Log.d(
            PAGER_SYNC_LOG_TAG,
            "aligned tab=$targetTabOrdinal page=${pagerState.currentPage} " +
                "fraction=${pagerState.currentPageOffsetFraction}",
        )
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerPageForTabSync(pagerState.isScrollInProgress, pagerState.currentPage) }
            .collectLatest { page ->
                if (page == null) return@collectLatest
                val tab = HomeTab.entries[page]
                if (tab != state.currentTab) viewModel.switchTab(tab)
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HomeTopRow(
            columns = columns,
            onOpenSearch = onOpenSearch,
            onToggleColumns = viewModel::toggleHomeColumns,
        )
        QimengChipRow(
            pills = HomeTab.entries.map { QimengPill(text = stringResource(it.tabLabelRes()), selected = it == state.currentTab) },
            onPillClick = { index -> viewModel.switchTab(HomeTab.entries[index]) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // 台账 #37 修法③评估结论（拍板要求评估、能落地则落地——评估后维持现状）：
        // a) Modifier.height 固定预留周期行高度 = 推荐/COS 两 tab 常驻一行 chip 高度空白，
        //    偏离旧版「仅排行榜有周期行」视觉规格，不落地；
        // b) animateItem 是 LazyLayout item API，此处是普通 Column 条件挂载，不适用；
        // c) 显隐引发的视口高度突变曾误触距底哨兵（#35），已由 switchTab 的 500ms 抑制窗覆盖
        //    （本批未动）；高度重测不丢 pager 横向滚动位置，卡半屏根因是动画被打断（见上方
        //    同步块注释），已在同步层修复。故本行维持条件挂载。
        if (state.currentTab == HomeTab.RANK) {
            Spacer(modifier = Modifier.height(4.dp))
            // 排行榜周期四档（日/周/月/年；缺省日榜——拍板 B3，不用 quarter/all）
            QimengChipRow(
                pills = RankingPeriod.entries.map { period ->
                    QimengPill(text = period.label, selected = period == state.rank.period)
                },
                onPillClick = { index -> viewModel.selectPeriod(RankingPeriod.entries[index]) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        state.errorMessage?.let { message ->
            ErrorBanner(message = message, onDismiss = viewModel::clearError)
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            // 网格点击统一走：先写批次上下文（详情页 i/N 序号+3b 滑动切换数据链），再交壳层导航
            val onAssetClick: (MediaAsset) -> Unit = { asset ->
                viewModel.enterDetail(asset.id)
                onOpenAsset(asset.id)
            }
            when (HomeTab.entries[page]) {
                HomeTab.RECOMMEND -> RecommendPage(
                    state = state.recommend,
                    columns = columns,
                    listState = recommendListState,
                    animatedUrlResolver = animatedUrlResolver,
                    onRefresh = viewModel::refresh,
                    onNearBottom = viewModel::onNearBottom,
                    onAssetClick = onAssetClick,
                )
                HomeTab.COS -> CosPage(
                    state = state.cos,
                    columns = columns,
                    listState = cosListState,
                    animatedUrlResolver = animatedUrlResolver,
                    onRefresh = viewModel::refresh,
                    onNearBottom = viewModel::onNearBottom,
                    onAssetClick = onAssetClick,
                )
                HomeTab.RANK -> RankPage(
                    items = state.rank.items,
                    columns = columns,
                    listState = rankListState,
                    animatedUrlResolver = animatedUrlResolver,
                    onRefresh = viewModel::refresh,
                    onAssetClick = onAssetClick,
                )
            }
        }
    }

}

/**
 * 顶行：[标题][搜索框不可聚焦→跳搜索页][网格图标]（GUIDE_UI §首页）。
 * 筛选图标不做（2026-09-06 用户拍板落档待拍板条目 5：首页无筛选入口；媒体类型参数协议面保留）。
 */
@Composable
private fun HomeTopRow(
    columns: Int,
    onOpenSearch: () -> Unit,
    onToggleColumns: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = HOME_TITLE,
            style = MaterialTheme.typography.titleLarge,
        )
        // 搜索框不可聚焦（点击整块跳搜索页——规格书语义）；高度 40dp=旧版 fragment_home.xml L37
        // bg_capsule_soft 胶囊底（F 批 2026-09-09：压回旧版视觉，此前实测 48dp）
        Surface(
            shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .weight(1f)
                .height(QimengDimens.HomeSearchFieldHeight)
                .clickable(onClick = onOpenSearch),
        ) {
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "搜索",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        IconButton(onClick = onToggleColumns) {
            Text(text = "${columns}列", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 推荐流页：一次拉满 200、分批揭示；空态保持空白（规格书语义） */
@Composable
private fun RecommendPage(
    state: RecommendState,
    columns: Int,
    listState: LazyGridState,
    animatedUrlResolver: suspend (String) -> String?,
    onRefresh: () -> Unit,
    onNearBottom: () -> Unit,
    onAssetClick: (MediaAsset) -> Unit,
) {
    QimengPullToRefresh(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
        if (state.pulled.isEmpty()) {
            if (!state.isLoading) QimengEmptyState(text = "")
            return@QimengPullToRefresh
        }
        QimengMediaGrid(
            sections = listOf(
                GridSection(label = "", items = state.pulled.take(state.revealed)),
            ),
            columns = columns,
            animatedUrlResolver = animatedUrlResolver,
            listState = listState,
            onNearBottom = onNearBottom,
            onAssetClick = onAssetClick,
        )
    }
}

/** COS 流页：独立入口 cosOnly=1，cursor 分页 */
@Composable
private fun CosPage(
    state: CosState,
    columns: Int,
    listState: LazyGridState,
    animatedUrlResolver: suspend (String) -> String?,
    onRefresh: () -> Unit,
    onNearBottom: () -> Unit,
    onAssetClick: (MediaAsset) -> Unit,
) {
    QimengPullToRefresh(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
        if (state.items.isEmpty()) {
            if (!state.isLoading) QimengEmptyState(text = "")
            return@QimengPullToRefresh
        }
        QimengMediaGrid(
            sections = listOf(GridSection(label = "", items = state.items)),
            columns = columns,
            animatedUrlResolver = animatedUrlResolver,
            listState = listState,
            onNearBottom = onNearBottom,
            onAssetClick = onAssetClick,
        )
    }
}

/** 排行榜页：日/周/月/年周期（缺省日榜）；类型筛选为客户端投影 */
@Composable
private fun RankPage(
    items: List<MediaAsset>,
    columns: Int,
    listState: LazyGridState,
    animatedUrlResolver: suspend (String) -> String?,
    onRefresh: () -> Unit,
    onAssetClick: (MediaAsset) -> Unit,
) {
    QimengPullToRefresh(isRefreshing = false, onRefresh = onRefresh) {
        if (items.isEmpty()) {
            QimengEmptyState(text = "暂无数据")
            return@QimengPullToRefresh
        }
        QimengMediaGrid(
            sections = listOf(GridSection(label = "", items = items)),
            columns = columns,
            animatedUrlResolver = animatedUrlResolver,
            listState = listState,
            onAssetClick = onAssetClick,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onDismiss),
    ) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
