package media.qimeng.app.feature.home

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
import kotlinx.coroutines.launch
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

    // chip 点击 ↔ 横滑 双向同步：state 变化驱动 pager，pager 翻页驱动 VM
    val pagerState = rememberPagerState(initialPage = state.currentTab.ordinal) { HomeTab.entries.size }
    LaunchedEffect(state.currentTab) {
        if (pagerState.currentPage != state.currentTab.ordinal) {
            pagerState.animateScrollToPage(state.currentTab.ordinal)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
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
        // 搜索框不可聚焦（点击整块跳搜索页——规格书语义）
        Surface(
            shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenSearch),
        ) {
            Text(
                text = "搜索",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
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
