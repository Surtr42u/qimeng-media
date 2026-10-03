package media.qimeng.app.feature.all

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.PanelFeedback
import media.qimeng.app.core.model.PillSpec
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.groupByAlbumDim
import media.qimeng.app.core.model.hasActiveFilters
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengFilterSheet
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTitleRow
import media.qimeng.app.core.ui.component.QimengValuePillBlock
import media.qimeng.app.core.ui.component.TabScrollController
import media.qimeng.app.core.ui.component.qimengPinchToColumns
import media.qimeng.app.core.ui.glass.TabDockDefaults
import media.qimeng.app.core.ui.theme.QimengDimens
// 页头组件共享文案在 :core:ui（nonTransitiveRClass 下跨模块取资源须引对方 R）
import media.qimeng.app.core.ui.R as CoreUiR

/** 相册 Tab 在壳导航里的路由（双击 Tab 回顶事件的过滤键） */
private const val ALBUM_ROUTE = "all"

// 值区块钳制规格三常量（G5 收起阈值/收起行数/展开限高除数）2026-09-15 批收编进 :core:ui
// QimengValuePillBlock（三页单源），本页不再持有。

/**
 * 相册页（M4-2，原全部页；任务G G5 对齐 Web AlbumsPage 形态）：标题+统计行+列数图标（双指缩放可调）+
 * 四维芯片行 + in-flow 值区块（文档流推挤网格，超阈值收起两行可展开；展开态限高半屏可纵滚——
 * U10-2b/7 旧版 MaxHeightScrollView 行为复刻，悬浮形态未复刻）+
 * 按 activeDim 分派的分组网格 + 下拉刷新 + cursor 分页。
 * 排序不在页头（任务L L4 按用户拍板删除 G5 页头四档排序行——旧版无此行），
 * 排序唯一编辑入口回归万能筛选面板「排序方式/顺位」两段（QimengFilterSheet）。
 */
@Composable
fun AllScreen(
    onOpenAsset: (assetId: String) -> Unit,
    viewModel: AlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val columns by viewModel.albumColumns.collectAsStateWithLifecycle()
    // 万能筛选面板（M4-2A-B3）：面板开关/草稿/标签候选都在 VM 面板流里
    val panelState by viewModel.panelState.collectAsStateWithLifecycle()
    // 双指缩放期间的瞬时列数优先展示（逐帧反馈在内存、手势结束才持久化一次——见 AlbumViewModel）
    val pinchColumns by viewModel.pinchColumns.collectAsStateWithLifecycle()
    val displayColumns = pinchColumns ?: columns
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    val listState = rememberLazyGridState()
    // nowMs 一次快照：会话内分组标签稳定，不做跨日跳动（与旧版渲染指纹同思路）
    val nowMs = remember { System.currentTimeMillis() }

    // 双击「相册」Tab 回顶（400ms 双击窗口判定在壳层，列表页只听广播）
    LaunchedEffect(Unit) {
        TabScrollController.events.collectLatest { route ->
            if (route == ALBUM_ROUTE) {
                listState.scrollToItem(0)
            }
        }
    }

    // 四维切换（分区/作品/角色/类型）后网格瞬时回顶（scrollToItem 无动画，同旧版）：
    // GUIDE_UI.md 无「切维滚动复位」条款，按旧版实录对齐——all_work_mode / all_character_mode /
    // all_type_mode 实录中切维后首组组头恒在网格视口顶部（元素级对照需首组可见），
    // 保留滚动深度会让首组标题滚出屏幕。只挂 activeDim：药丸（分区筛选）点击不改 activeDim，
    // 其滚动行为本批不动。首次组合时列表本就在顶部，scrollToItem(0) 为无操作。
    //
    // 门控 lastDimScrolledToTop（任务V V1 追修 D1，2026-09-10）：回顶只在 activeDim **真值变化**
    // 时执行——LaunchedEffect 以 key 重启，而本页从详情返回会重新进入组合（转场期 destination
    // 离开组合），effect 无条件重启即把滚动拍回顶，表现为「返回后列表重建于顶 + morph 飞向
    // 第一格」（六入口验证 3/3 复现，其余五入口无切维 effect 故滚动保持）。快照经
    // rememberSaveable 存活：返回重组时恢复旧值与当前 activeDim 相等即跳过；首次进入
    // null≠默认维，走一次无操作回顶（与原注释口径一致）。
    var lastDimScrolledToTop by rememberSaveable { mutableStateOf<AlbumDim?>(null) }
    LaunchedEffect(state.activeDim) {
        if (lastDimScrolledToTop != state.activeDim) {
            lastDimScrolledToTop = state.activeDim
            listState.scrollToItem(0)
        }
    }

    // 下拉刷新完成后瞬时回顶（2026-09-15 用户反馈「相册刷新会导致跳到之前的日期」）：
    // 刷新把列表截回第一页（AlbumViewModel.reloadAll cursor=null append=false），而滚动
    // 位置仍钉在刷新前的旧索引上——LazyGrid 把位置钳在缩水后列表的尾部，视觉=跳到旧
    // 日期区。刷新语义=回到最新，故 true→false 翻转沿回顶。门控 lastRefreshing 快照：
    // 首次组合 isRefreshing=false 无操作；从详情返回重组不误触（无翻转即无回顶）。
    var lastRefreshing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isRefreshing) {
        if (lastRefreshing && !state.isRefreshing) {
            listState.scrollToItem(0)
        }
        lastRefreshing = state.isRefreshing
    }

    val pillModel = FourDimPillModel(
        filter = state.filter,
        activeDim = state.activeDim,
        partitionOptions = state.partitionOptions,
        authorOptions = state.authorOptions,
        characterOptions = state.characterOptions,
        typeOptions = state.typeOptions,
        totalForAllPill = state.totalForAllPill,
    )
    val activePills = FourDimPills.pillsFor(pillModel)

    Column(modifier = Modifier.fillMaxSize()) {
        // 页头唯一实现于 :core:ui（任务A §5.2，B5 收藏/历史页复用）——本页只做文案/参数接线。
        // 筛选入口只在相册页标题行（按实录判读：旧版实录仅全部页标题行有 allFilterButton 图标，
        // favorite/history 实录无筛选图标；收藏/历史不传 onFilterClick 不显示，B5 接线时复核落档）
        QimengTitleRow(
            title = stringResource(R.string.all_title),
            statLine = state.totalMatched?.let { stringResource(CoreUiR.string.ui_stat_files, it) } ?: "",
            // Y3 批（2026-09-12 全局字体对齐旧版）：页标题对齐旧版 fragment_all_files.xml L31-37
            // ——28sp Bold + qmColorTextPrimary（Theme.kt qmColorTextPrimary→onSurface 槽）；
            // 行高 36sp 防 28sp 大标题行挤压（此前 titleLarge 22sp Regular 偏小即用户反馈项）
            titleStyle = MaterialTheme.typography.titleLarge.copy(
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            columns = displayColumns,
            onToggleColumns = viewModel::toggleColumns,
            onFilterClick = viewModel::openFilterSheet,
            // U10-2b：面板字段偏离默认（排序/顺位/观看/点击/大小/时间/年份/标签）才点亮软底；
            // 判定为 core:model 纯函数（hasActiveFilters），UI 不内嵌业务规则（铁律 7）
            filterActive = state.filter.hasActiveFilters(),
        )

        // 维度芯片行常驻文档流（旧版在网格上方推挤布局）；「角色 | 类型」间竖分隔线=旧版
        // fragment_all_files.xml L117-118；芯片点击语义（点已激活维=切展开/折叠）在 ViewModel
        QimengChipRow(
            pills = FourDimPills.dimChips(pillModel).map { QimengPill(text = it.text, selected = it.selected) },
            onPillClick = { index ->
                viewModel.onDimChipClicked(AlbumDim.entries[index])
            },
            dividerBeforeIndex = AlbumDim.TYPE.ordinal,
            modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
        )

        // 值区块 in-flow（任务G G5：文档流推挤网格不遮挡；2026-09-15 批改用 :core:ui
        // QimengValuePillBlock 三页单源——收藏/历史/作者集合页同款，钳制与展开钮规格随迁）
        if (state.filter.expanded) {
            QimengValuePillBlock(
                pills = activePills.map { QimengPill(text = it.text, selected = it.selected) },
                onPillClick = { index -> dispatchPill(viewModel, state.activeDim, activePills.getOrNull(index)) },
                // 切维度归位「收起两行」（Web setDim 重置 expanded 同口径）
                resetKey = state.activeDim,
            )
        }

        // 页头排序行已删除（任务L L4，用户原话 #19「那就删除 就是截图这个,分区下面地这个排序」）：
        // 旧版相册页无页头排序行（排序在万能筛选面板三档+顺位，2026-09-17 拍板精简），G5 提到页头的四档行按拍板移除；
        // 排序唯一编辑入口回归筛选面板（QimengFilterSheet 排序方式/顺位两段），列表默认排序
        // 回归协议缺省 default/desc（2026-09-17 拍板：默认选中档=「默认」，取代 2026-09-15 FILE_DATE 缺省）。

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            QimengPullToRefresh(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    // 双指缩放调列数：步进即时生效（内存），手势结束统一持久化一次（DOMAIN_RULES §8）
                    .qimengPinchToColumns(
                        onStep = viewModel::adjustColumnsLive,
                        onGestureEnd = viewModel::commitPinchColumns,
                    ),
            ) {
                if (state.items.isEmpty()) {
                    // 空状态保持空白（规格书 §全部页语义）
                    if (!state.isLoading) QimengEmptyState(text = "")
                    return@QimengPullToRefresh
                }
                QimengMediaGrid(
                    // 分组按激活维分派（P9-5）：分区/类型=日期分组，作品=source∪COS 作者，
                    // 角色=characters∪cosWork，空组键归「其他」恒末位。
                    // 修复D-1（2026-09-14 相册胶囊点击丢响应调研定案）：O(n) 分组原每次重组
                    // 裸跑，胶囊点击引发的重组全量重算拖慢帧（丢响应根因之一）——包 remember
                    // 按参与变量（items/activeDim/nowMs）缓存，重组零重算、数据或维度变化才重算
                    sections = remember(state.items, state.activeDim, nowMs) {
                        state.items.groupByAlbumDim(state.activeDim, nowMs)
                    },
                    columns = displayColumns,
                    animatedUrlResolver = animatedUrlResolver,
                    listState = listState,
                    // 2026-10-03 悬浮玻璃坞批：底栏改悬浮层后内容从坞身后滚过，让位档从
                    // 180dp 呼吸区改为坞体单源档（core:ui TabDockDefaults，含导航栏 inset）——
                    // 最后一项停在坞体上方，滚入量由旧 180dp 收敛为坞实际占位
                    bottomContentPadding = TabDockDefaults.bottomClearance(),
                    onNearBottom = viewModel::onNearBottom,
                    // 问题A（2026-09-28）：加载结束重评估信号——翻页成功但新页为空时 totalCount
                    // 不变，哨兵需靠 tick 重触发（KDoc 见 QimengMediaGrid.reloadTick）
                    reloadTick = state.reloadTick,
                    // 修复D-3：滚动暂停缩略图加载（对齐收藏/历史页任务I I5 口径——拖拽/fling
                    // 期间暂缓新缩略图请求，停滚自动恢复），滚动时帧预算让位交互响应
                    pauseThumbnailsWhileScrolling = true,
                    // 卡片点击进详情（D3 顺手修复：onAssetClick 有默认空实现漏传即静默无反应）：
                    // 先写批次清单再交壳层导航——详情页 i/N 序号与左右滑沿相册当前筛选后的
                    // 已加载清单取邻位，滑切才跟随相册筛选而非其他页面残留的旧清单
                    onAssetClick = { asset: MediaAsset ->
                        viewModel.enterDetail(asset.id)
                        onOpenAsset(asset.id)
                    },
                )
            }
        }
    }

    // 万能筛选面板（M4-2A-B3）：唯一实现在 :core:ui，本页只接线；
    // 组件收进 Column 之外保证覆盖全页（ModalBottomSheet 自带 scrim/手势关闭=丢弃草稿）；
    // 面板操作反馈（P2-1）：VM 只发结构化语义，文案在此经 strings.xml 落地传给面板
    if (panelState.visible) {
        QimengFilterSheet(
            draft = panelState.draft,
            tags = panelState.tags,
            message = panelState.message?.let { feedback ->
                when (feedback) {
                    is PanelFeedback.TagExists -> stringResource(CoreUiR.string.ui_filter_tag_exists, feedback.name)
                    PanelFeedback.OpFailed -> stringResource(CoreUiR.string.ui_filter_op_failed)
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

/** 药丸点击 → 状态机调用（分区 payload=Zone、作者/角色 payload=FacetOption?、类型 payload=MediaKind?） */
internal fun dispatchPill(
    viewModel: AlbumViewModel,
    dim: AlbumDim,
    spec: PillSpec?,
) {
    if (spec == null) return
    when (dim) {
        AlbumDim.PARTITION -> viewModel.selectPartition(spec.payload as? Zone ?: Zone.ALL)
        AlbumDim.AUTHOR -> viewModel.selectAuthor(spec.payload as? FacetOption)
        AlbumDim.CHARACTER -> viewModel.selectCharacter(spec.payload as? FacetOption)
        AlbumDim.TYPE -> viewModel.selectMediaType(spec.payload as? media.qimeng.app.core.model.MediaKind)
    }
}
