package media.qimeng.app.feature.home

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import media.qimeng.app.core.model.PanelFeedback
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengFilterSheet
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.glass.BackdropGlassIconButton
import media.qimeng.app.core.ui.glass.BackdropGlassPanel
import media.qimeng.app.core.ui.glass.GlassIconButton
import media.qimeng.app.core.ui.glass.GlassSurface
import media.qimeng.app.core.ui.glass.QimengBackdropState
import media.qimeng.app.core.ui.glass.TabDockDefaults
import media.qimeng.app.core.ui.glass.pressScale
import media.qimeng.app.core.ui.glass.qimengBackdropSource
import media.qimeng.app.core.ui.glass.rememberQimengBackdropState
import media.qimeng.app.core.ui.glass.rememberPressScaleSource
import media.qimeng.app.core.ui.theme.QimengShapes
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengSkeletonGrid
import media.qimeng.app.core.ui.component.QIMENG_SKELETON_GRID_ROWS
import media.qimeng.app.core.ui.component.TabScrollController
import media.qimeng.app.core.ui.icon.Grid1Icon
import media.qimeng.app.core.ui.icon.HomeFilterIcon
import media.qimeng.app.core.ui.icon.gridIconFor
import media.qimeng.app.core.ui.theme.QimengDimens
// core/ui 共享文案资源别名导入：防与 feature/home 自身 R 撞名（顶栏图标钮无障碍描述复用）
import media.qimeng.app.core.ui.R as UiR
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

/** 首页标题（GUIDE_UI §首页 顶行） */
private const val HOME_TITLE = "首页"

/** 「已到底」尾标文案（推荐/COS 流穷尽告知，2026-09-29；两页共用故提常量，语义见 QimengMediaGrid.endFooterText） */
private const val EXHAUSTED_FOOTER_TEXT = "已到底"

/** 首页在壳导航里的路由（双击 Tab 回顶事件的过滤键） */
private const val HOME_ROUTE = "home"

/** tab → 文案资源映射（文案在 strings.xml；枚举不再携带 UI 文案——2026-09-06 审查卫生项） */
private fun HomeTab.tabLabelRes(): Int = when (this) {
    HomeTab.RECOMMEND -> R.string.home_tab_recommend
    HomeTab.COS -> R.string.home_tab_cos
    HomeTab.RANK -> R.string.home_tab_rank
}

/** pager↔chip 同步对齐日志 tag（实机高频验收 grep 用，同 QimengM42 先例；L6 起改功能域名，原批次台账号 QimengL37 退役） */
private const val PAGER_SYNC_LOG_TAG = "QimengHomePagerSync"

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
 * 首页（M4-2）：顶行[标题][搜索框不可聚焦→跳搜索页][筛选图标钮][列数图标钮] +
 * 推荐/COS/排行榜 三 tab（HorizontalPager 左右横滑切换）+ 各 tab 独立缓存 + 下拉刷新。
 * Y4a（2026-09-12）：顶栏控件图标化对齐旧版（原「N列」文字钮退役）。
 * Y4b（2026-09-12）：筛选面板接线（范式=AllScreen/AlbumViewModel，状态与展开单源 core/model）——
 * 顶栏筛选钮直连 [HomeViewModel.openFilterSheet]（Y4a 的上抛回调拆除，壳层无需经手）；
 * 面板筛选只作用于 COS tab（/assets 参数面；推荐/排行榜协议无筛选参数，记档交付报告）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    // 性能联动（2026-10-03 帧实测批）：true=玻璃材质档（顶行走真采样管线）；
    // false=纯色/经典档（零捕获，顶行走静态玻璃面）——由壳层按底栏材质传入
    glassEnabled: Boolean = true,
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
    // 修法：② 经 pagerPageForTabSync 门控，滚动在途不回写，斩断同步环；① Y6 批
    // （2026-09-12 用户拍板「tab 切换视觉走旧版那种」）把补页动作从 animateScrollToPage 换成
    // scrollToPage 瞬时跳页（对齐旧版 HomeFragment.setTab 无动画换页）——滑动过渡不再存在，
    // 「动画被打断停半屏」失去载体，未对齐重试循环保留为兜底（手势拖拽中途点芯片：先等
    // isScrollInProgress 归假让路，落定后瞬时对齐，无死循环——scrollToPage 落定即精确对齐，
    // 退出条件必然满足）；HorizontalPager 手势横滑保留=与旧版（无手势滑动）的已记档差异。
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
            // Y6（2026-09-12 用户拍板对齐旧版 setTab）：瞬时跳页，无滑动中间帧；
            // scrollToPage 落定即 currentPage==目标且 fraction==0，循环一轮即退出
            pagerState.scrollToPage(targetTabOrdinal)
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

    // 顶行真采样玻璃源（2026-10-03 质感对齐批；帧实测批加性能门控）：捕获源=下方 pager
    // （顶行的兄弟子树，合法兄弟采样）。玻璃材质档才创建捕获——纯色/经典档 null=顶行
    // 静态玻璃 + pager 不挂捕获层，零采样开销
    val homeBackdrop = if (glassEnabled) {
        rememberQimengBackdropState(baseColor = MaterialTheme.colorScheme.background)
    } else {
        null
    }
    Column(modifier = Modifier.fillMaxSize()) {
        HomeTopRow(
            columns = columns,
            backdrop = homeBackdrop,
            onOpenSearch = onOpenSearch,
            // Y4b：面板唯一实现在 :core:ui（QimengFilterSheet），开关/草稿都在 VM 筛选态里
            onOpenFilter = viewModel::openFilterSheet,
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
        // 刷新成功但内容与刷新前一致的轻提示（「COS 下拉无效」反馈修复；自动消退，
        // 语义见 HomeUiState.infoMessage KDoc——与失败横幅 ErrorBanner 分流）
        state.infoMessage?.let { message ->
            InfoBanner(message = message)
        }
        HorizontalPager(
            state = pagerState,
            // 顶行真采样捕获源：pager 子树记录进 homeBackdrop（玻璃档才挂；消费方=顶行）
            modifier = Modifier
                .weight(1f)
                .then(
                    if (homeBackdrop != null) {
                        Modifier.qimengBackdropSource(homeBackdrop)
                    } else {
                        Modifier
                    },
                ),
        ) { page ->
            // 网格点击统一走：先写批次上下文（详情页 i/N 序号+3b 滑动切换数据链），再交壳层导航
            val onAssetClick: (MediaAsset) -> Unit = { asset ->
                viewModel.enterDetail(asset.id)
                onOpenAsset(asset.id)
            }
            when (HomeTab.entries[page]) {
                HomeTab.RECOMMEND -> RecommendPage(
                    state = state.recommend,
                    hasError = state.errorMessage != null,
                    columns = columns,
                    listState = recommendListState,
                    animatedUrlResolver = animatedUrlResolver,
                    onRefresh = viewModel::refresh,
                    onNearBottom = viewModel::onNearBottom,
                    onAssetClick = onAssetClick,
                )
                HomeTab.COS -> CosPage(
                    state = state.cos,
                    hasError = state.errorMessage != null,
                    columns = columns,
                    listState = cosListState,
                    animatedUrlResolver = animatedUrlResolver,
                    onRefresh = viewModel::refresh,
                    onNearBottom = viewModel::onNearBottom,
                    onAssetClick = onAssetClick,
                )
                HomeTab.RANK -> RankPage(
                    state = state.rank,
                    hasError = state.errorMessage != null,
                    columns = columns,
                    listState = rankListState,
                    animatedUrlResolver = animatedUrlResolver,
                    onRefresh = viewModel::refresh,
                    onAssetClick = onAssetClick,
                )
            }
        }
    }

    // 万能筛选面板（任务Y Y4b）：唯一实现在 :core:ui，本页只接线（范式=AllScreen 同款——
    // 组件收进 Column 之外保证覆盖全页，ModalBottomSheet 自带 scrim/手势关闭=丢弃草稿）；
    // 面板操作反馈：VM 只发结构化语义，文案在此经 strings.xml 落地传给面板
    if (state.filterPanel.visible) {
        QimengFilterSheet(
            draft = state.filterPanel.draft,
            tags = state.filterPanel.tags,
            message = state.filterPanel.message?.let { feedback ->
                when (feedback) {
                    is PanelFeedback.TagExists -> stringResource(UiR.string.ui_filter_tag_exists, feedback.name)
                    PanelFeedback.OpFailed -> stringResource(UiR.string.ui_filter_op_failed)
                }
            },
            onDraftChange = viewModel::updatePanelDraft,
            onReset = viewModel::resetPanelDraft,
            onApply = viewModel::applyPanelDraft,
            onAddTag = viewModel::addTag,
            onDeleteTag = viewModel::deleteTag,
            onDismiss = viewModel::dismissFilterSheet,
        )
    }
}

/**
 * 顶行：[标题][搜索框不可聚焦→跳搜索页][筛选图标钮][列数图标钮]（GUIDE_UI §首页）。
 * Y4a（2026-09-12 用户拍板「首页搜索地旁边行列不是图标然后筛选没有」）：顶栏控件图标化+筛选入口落地，
 * **反转 2026-09-06「筛选不做」旧拍板**（落档待拍板条目 5 作废；媒体类型筛选参数协议面保留给 Y4b 面板）。
 * 布局逐项对齐旧版 fragment_home.xml 实录：标题 marginEnd=10dp（L31）/搜索框 weight=1 高 40dp（L33-44，
 * F 批已对齐）/筛选钮 40dp 胶囊 marginStart=10dp marginEnd=6dp（L48-52）/列数钮 40dp 胶囊（L53-59）；
 * 筛选在左、列数在右（实录次序）。列数钮图标随列数换（旧版 HomeFragment.toggleColumns L366-374 同款：
 * 1→ic_grid_1、2→ic_grid_2；1 档为 Y4a 补齐，[gridIconFor] 既有 2..5 档 clamp 语义不动故先特判 1）。
 */
@Composable
private fun HomeTopRow(
    columns: Int,
    backdrop: QimengBackdropState?,
    onOpenSearch: () -> Unit,
    onOpenFilter: () -> Unit,
    onToggleColumns: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = HOME_TITLE,
            // Y3 批（2026-09-12 全局字体对齐旧版）：首页标题对齐旧版 fragment_home.xml L25-32
            // ——24sp Bold + qmColorTextPrimary（onSurface 槽；此前 titleLarge 22sp Regular 偏小）
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            // 旧版 L31 marginEnd=10dp（此前 spacedBy 8dp 均一间距，Y4a 逐项实录化）
            modifier = Modifier.padding(end = 10.dp),
        )
        // 搜索框不可聚焦（点击整块跳搜索页——规格书语义）；高度 40dp=旧版 fragment_home.xml L37
        // 2026-10-03 质感对齐批：实色胶囊底 → 玻璃面。玻璃材质档走真采样管线（与坞同
        // Backdrop 库：vibrancy+blur 采样兄弟 pager）；纯色/经典档 backdrop=null 走静态
        // GlassSurface（零捕获零开销，帧实测批的性能门控）。按压 spring 缩放即反馈
        val searchInteraction = rememberPressScaleSource()
        if (backdrop != null) {
            BackdropGlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .weight(1f)
                    .height(QimengDimens.HomeSearchFieldHeight)
                    .pressScale(searchInteraction)
                    .clickable(
                        interactionSource = searchInteraction,
                        indication = null,
                        onClick = onOpenSearch,
                    ),
            ) {
                HomeSearchFieldContent()
            }
        } else {
            GlassSurface(
                shape = QimengShapes.pill,
                modifier = Modifier
                    .weight(1f)
                    .height(QimengDimens.HomeSearchFieldHeight)
                    .pressScale(searchInteraction)
                    .clickable(
                        interactionSource = searchInteraction,
                        indication = null,
                        onClick = onOpenSearch,
                    ),
            ) {
                HomeSearchFieldContent()
            }
        }
        // 筛选钮（左）与列数钮（右）：无障碍文案复用 core/ui 共享资源（QimengTitleRow 同款语义；
        // 别名导入防与 feature R 撞名）。玻璃档=BackdropGlassIconButton 真采样，纯色档=GlassIconButton
        if (backdrop != null) {
            BackdropGlassIconButton(
                icon = HomeFilterIcon,
                contentDescription = stringResource(UiR.string.ui_filter_icon_desc),
                onClick = onOpenFilter,
                backdrop = backdrop,
                tint = MaterialTheme.colorScheme.primary,
                // 旧版 L49-50 marginStart/End=10/6dp
                modifier = Modifier.padding(start = 10.dp, end = 6.dp),
            )
            BackdropGlassIconButton(
                icon = if (columns == 1) Grid1Icon else gridIconFor(columns),
                contentDescription = stringResource(UiR.string.ui_columns_icon_desc),
                onClick = onToggleColumns,
                backdrop = backdrop,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            GlassIconButton(
                icon = HomeFilterIcon,
                contentDescription = stringResource(UiR.string.ui_filter_icon_desc),
                onClick = onOpenFilter,
                tint = MaterialTheme.colorScheme.primary,
                // 旧版 L49-50 marginStart/End=10/6dp
                modifier = Modifier.padding(start = 10.dp, end = 6.dp),
            )
            GlassIconButton(
                icon = if (columns == 1) Grid1Icon else gridIconFor(columns),
                contentDescription = stringResource(UiR.string.ui_columns_icon_desc),
                onClick = onToggleColumns,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 搜索框文案内容（真采样/静态两个玻璃分支共用，避免内联重复） */
@Composable
private fun HomeSearchFieldContent() {
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

/**
 * 三 tab 共用的「列表空」分支（2026-09-18 首页冷启动空白修复）：首屏在途 → [QimengSkeletonGrid]
 * 骨架屏，替代此前 loading 期间整页空白（旧实现 loading 中什么都不渲染）。
 * 骨架条件 = 未 loaded 且无真错误：同时覆盖「探针等待 + 请求在途 + 静默退避间隙」整段窗口
 * （三者 errorMessage 恒 null、loaded 恒 false），骨架↔空态不在退避节奏里来回闪；
 * 预算耗尽/用户动作失败亮横幅（hasError）后回落空态，不伪装加载中；已 loaded 且真库空 →
 * 空态（规格书语义）。数据在单次 state copy 中原子落地，骨架→网格一次切换不闪烁；
 * 下拉刷新已有数据走网格分支，不出现骨架。
 */
@Composable
private fun EmptyOrSkeleton(isLoaded: Boolean, hasError: Boolean, columns: Int, emptyText: String) {
    if (!isLoaded && !hasError) {
        QimengSkeletonGrid(columns = columns, itemCount = columns * QIMENG_SKELETON_GRID_ROWS)
    } else {
        QimengEmptyState(text = emptyText)
    }
}

/** 推荐流页：一次拉满 200、分批揭示；空态保持空白（规格书语义）；首屏在途出骨架屏（2026-09-18） */
@Composable
private fun RecommendPage(
    state: RecommendState,
    hasError: Boolean,
    columns: Int,
    listState: LazyGridState,
    animatedUrlResolver: suspend (String) -> String?,
    onRefresh: () -> Unit,
    onNearBottom: () -> Unit,
    onAssetClick: (MediaAsset) -> Unit,
) {
    QimengPullToRefresh(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
        if (state.pulled.isEmpty()) {
            EmptyOrSkeleton(isLoaded = state.loaded, hasError = hasError, columns = columns, emptyText = "")
            return@QimengPullToRefresh
        }
        QimengMediaGrid(
            sections = listOf(
                GridSection(label = "", items = state.pulled.take(state.revealed)),
            ),
            columns = columns,
            animatedUrlResolver = animatedUrlResolver,
            listState = listState,
            // 2026-10-03 悬浮玻璃坞批：底栏改悬浮层后内容从坞身后滚过，网格最后一项
            // 须让位到坞体上方（core:ui 单源常量，含导航栏 inset；三页流共用同一档）
            bottomContentPadding = TabDockDefaults.bottomClearance(),
            onNearBottom = onNearBottom,
            // 问题A（2026-09-28）：加载结束重评估信号——换轮成功但 fresh==0（或空页追加）时
            // totalCount 不变，哨兵需靠 tick 重触发（含 fresh==0 续轮后的穷尽停手，由 VM 拦截兜底）
            reloadTick = state.reloadTick,
            // 穷尽到底告知（2026-09-29「下滑不会继续加载」反馈）：换轮穷尽后到底尾标，
            // 与「坏了」区分（语义见 QimengMediaGrid.endFooterText）
            endFooterText = if (state.exhausted) EXHAUSTED_FOOTER_TEXT else null,
            onAssetClick = onAssetClick,
        )
    }
}

/** COS 流页：独立入口 cosOnly=1，cursor 分页；首屏在途出骨架屏（2026-09-18，口径同推荐流） */
@Composable
private fun CosPage(
    state: CosState,
    hasError: Boolean,
    columns: Int,
    listState: LazyGridState,
    animatedUrlResolver: suspend (String) -> String?,
    onRefresh: () -> Unit,
    onNearBottom: () -> Unit,
    onAssetClick: (MediaAsset) -> Unit,
) {
    QimengPullToRefresh(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
        if (state.items.isEmpty()) {
            EmptyOrSkeleton(isLoaded = state.loaded, hasError = hasError, columns = columns, emptyText = "")
            return@QimengPullToRefresh
        }
        QimengMediaGrid(
            sections = listOf(GridSection(label = "", items = state.items)),
            columns = columns,
            animatedUrlResolver = animatedUrlResolver,
            listState = listState,
            // 2026-10-03 悬浮玻璃坞批：底栏改悬浮层后内容从坞身后滚过，网格最后一项
            // 须让位到坞体上方（core:ui 单源常量，含导航栏 inset；三页流共用同一档）
            bottomContentPadding = TabDockDefaults.bottomClearance(),
            onNearBottom = onNearBottom,
            // 问题A（2026-09-28）：加载结束重评估信号——翻页成功但新页为空时 totalCount 不变，
            // 哨兵需靠 tick 重触发（KDoc 见 QimengMediaGrid.reloadTick）
            reloadTick = state.reloadTick,
            // 翻页穷尽到底告知（口径同 RecommendPage，2026-09-29）
            endFooterText = if (state.exhausted) EXHAUSTED_FOOTER_TEXT else null,
            onAssetClick = onAssetClick,
        )
    }
}

/** 排行榜页：日/周/月/年周期（缺省日榜）；类型筛选为客户端投影；首屏在途出骨架屏（2026-09-18） */
@Composable
private fun RankPage(
    state: RankState,
    hasError: Boolean,
    columns: Int,
    listState: LazyGridState,
    animatedUrlResolver: suspend (String) -> String?,
    onRefresh: () -> Unit,
    onAssetClick: (MediaAsset) -> Unit,
) {
    // 2026-09-29「排行榜下拉显示不对」修复：此前写死 false——下拉手势确实触发刷新，
    // 但转圈指示器永远不出现，观感是「下拉没反应」；接线真实状态（RankState.isRefreshing）
    QimengPullToRefresh(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
        if (state.items.isEmpty()) {
            // 改动前加载中也显「暂无数据」的误导空态，2026-09-18 起在途窗口由骨架屏接管
            EmptyOrSkeleton(isLoaded = state.loaded, hasError = hasError, columns = columns, emptyText = "暂无数据")
            return@QimengPullToRefresh
        }
        QimengMediaGrid(
            sections = listOf(GridSection(label = "", items = state.items)),
            columns = columns,
            animatedUrlResolver = animatedUrlResolver,
            listState = listState,
            // 2026-10-03 悬浮玻璃坞批：底栏改悬浮层后内容从坞身后滚过，网格最后一项
            // 须让位到坞体上方（core:ui 单源常量，含导航栏 inset；三页流共用同一档）
            bottomContentPadding = TabDockDefaults.bottomClearance(),
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

/**
 * 刷新成功但内容未变的轻提示横幅（HomeUiState.infoMessage 的渲染壳，2026-09-29）。
 * 与 [ErrorBanner] 的分流：这是**成功路径**的告知（中性色 secondaryContainer，非
 * errorContainer），不可点击——自动消退由 VM 侧计时（INFO_HINT_AUTO_CLEAR_MS），
 * 无操作语义。
 */
@Composable
private fun InfoBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
