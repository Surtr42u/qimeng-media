package media.qimeng.app.feature.all

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AssetSort
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.PillSpec
import media.qimeng.app.core.model.SortOrder
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.groupByAlbumDim
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengFilterSheet
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTitleRow
import media.qimeng.app.core.ui.component.QimengValuePillFlow
import media.qimeng.app.core.ui.component.TabScrollController
import media.qimeng.app.core.ui.component.qimengPinchToColumns
import media.qimeng.app.core.ui.theme.QimengDimens
// 页头组件共享文案在 :core:ui（nonTransitiveRClass 下跨模块取资源须引对方 R）
import media.qimeng.app.core.ui.R as CoreUiR

/** 相册 Tab 在壳导航里的路由（双击 Tab 回顶事件的过滤键） */
private const val ALBUM_ROUTE = "all"

/**
 * 候选值超过该数量（含「全部」胶囊）默认收起两行——对齐 Web 值行交互阈值
 * VALUE_COLLAPSE_THRESHOLD=9（web/src/pages/AlbumsPage.tsx，原型交互阈值），任务G G5。
 */
private const val ALBUM_VALUE_COLLAPSE_THRESHOLD = 9

/** 值区块收起态行数——对齐 Web .value-row 收起态 max-height:66px（两行胶囊+行距，
 *  web/src/styles/prototype.css）；以「行数」表达与胶囊实际高度解耦，视觉≈两行药丸 */
private const val ALBUM_VALUE_COLLAPSED_LINES = 2

/** 排序档（任务G G5：排序 pill 行提到页头）：文案与 Web AlbumsPage SORTS 四档逐字一致；
 *  value 映射 [media.qimeng.app.core.model.AlbumFilterState] 既有 sort+order 枚举（零协议改动） */
private data class AlbumSortChoice(@StringRes val labelRes: Int, val sort: AssetSort, val order: SortOrder)

private val ALBUM_SORT_CHOICES = listOf(
    AlbumSortChoice(R.string.all_sort_featured, AssetSort.DEFAULT, SortOrder.DESC), // 精选
    AlbumSortChoice(R.string.all_sort_newest, AssetSort.FILE_DATE, SortOrder.DESC), // 最新
    AlbumSortChoice(R.string.all_sort_oldest, AssetSort.FILE_DATE, SortOrder.ASC), // 最旧
    AlbumSortChoice(R.string.all_sort_by_name, AssetSort.NAME, SortOrder.ASC), // 按名称
)

/**
 * 相册页（M4-2，原全部页；任务G G5 对齐 Web AlbumsPage 形态）：标题+统计行+列数图标（双指缩放可调）+
 * 四维芯片行 + in-flow 值区块（文档流推挤网格，超阈值收起两行可展开）+ 常驻排序行（四档）+
 * 按 activeDim 分派的分组网格 + 下拉刷新 + cursor 分页。
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
    // 值区块两行钳制的展开态（任务G G5）：以 activeDim 为键——切维度归位「收起两行」
    // （Web setDim 重置 expanded 同口径）；旋转/进程重建经 rememberSaveable 存活。
    // 与 filter.expanded（整块显隐，D3 拍板语义）是两层不同的展开：前者管「区块内两行/全部」
    var valuesExpanded by rememberSaveable(state.activeDim) { mutableStateOf(false) }

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
    LaunchedEffect(state.activeDim) {
        listState.scrollToItem(0)
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
            columns = displayColumns,
            onToggleColumns = viewModel::toggleColumns,
            onFilterClick = viewModel::openFilterSheet,
        )

        // 维度芯片行常驻文档流（旧版在网格上方推挤布局）；「角色 | 类型」间竖分隔线=旧版
        // fragment_all_files.xml L117-118；芯片点击语义（点已激活维=切展开/折叠）在 ViewModel
        QimengChipRow(
            pills = FourDimPills.dimChips(pillModel).map { QimengPill(text = it.text, selected = it.selected) },
            onPillClick = { index -> viewModel.onDimChipClicked(AlbumDim.entries[index]) },
            dividerBeforeIndex = AlbumDim.TYPE.ordinal,
            modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
        )

        // 值区块 in-flow（任务G G5：替代悬浮药丸面板——进文档流推挤网格，不再叠放遮挡；
        // 对齐 Web .value-row 形态）。整块显隐仍由 D3 拍板语义控制（filter.expanded：
        // 进页默认收起/点已激活维 toggle/切维展开），本批只改「值」的呈现容器。
        if (state.filter.expanded) {
            Column(
                modifier = Modifier.padding(
                    start = QimengDimens.ScreenPaddingHorizontal,
                    end = QimengDimens.ScreenPaddingHorizontal,
                    // Web .value-row margin-top 10px 的近似 token 档（8dp）
                    top = QimengDimens.SpaceM,
                ),
            ) {
                QimengValuePillFlow(
                    pills = activePills.map { QimengPill(text = it.text, selected = it.selected) },
                    onPillClick = { index -> dispatchPill(viewModel, state.activeDim, activePills.getOrNull(index)) },
                    // 收起=钳制两行（≈Web max-height 66px）；展开=全部值推挤网格
                    maxLines = if (valuesExpanded) Int.MAX_VALUE else ALBUM_VALUE_COLLAPSED_LINES,
                )
                // 展开钮只在候选超阈值时出现（含「全部」胶囊的计数口径与 Web 一致）
                if (activePills.size > ALBUM_VALUE_COLLAPSE_THRESHOLD) {
                    Text(
                        text = stringResource(
                            if (valuesExpanded) R.string.all_values_collapse else R.string.all_values_expand,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            // Web .expand-btn margin-top 8px
                            .padding(top = QimengDimens.SpaceM)
                            .clip(RoundedCornerShape(QimengDimens.PillCornerRadius))
                            .clickable { valuesExpanded = !valuesExpanded }
                            // 触区补边：纯文字钮按下反馈区过窄（Web 有 hover 态、触屏无）
                            .padding(
                                horizontal = QimengDimens.SpaceXS,
                                vertical = QimengDimens.SpaceXS,
                            ),
                    )
                }
            }
        }

        // 排序行（任务G G5：常驻页头，对齐 Web sort-row——上分隔线 + 「排序」标签 + 四档单选胶囊；
        // 切档走 AlbumViewModel.selectSort 既有请求链，点当前档不重发）
        HorizontalDivider(
            modifier = Modifier.padding(
                // Web .sort-row margin-top/padding-top 12px + border-top 1px
                top = QimengDimens.SpaceL,
                start = QimengDimens.ScreenPaddingHorizontal,
                end = QimengDimens.ScreenPaddingHorizontal,
            ),
            thickness = QimengDimens.DividerThickness,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = QimengDimens.SpaceL,
                    start = QimengDimens.ScreenPaddingHorizontal,
                    end = QimengDimens.ScreenPaddingHorizontal,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.all_sort_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Web .sort-label margin-right 2px
                modifier = Modifier.padding(end = QimengDimens.SpaceXXS),
            )
            QimengChipRow(
                pills = ALBUM_SORT_CHOICES.map { choice ->
                    QimengPill(
                        text = stringResource(choice.labelRes),
                        selected = choice.sort == state.filter.sort && choice.order == state.filter.order,
                    )
                },
                onPillClick = { index ->
                    ALBUM_SORT_CHOICES[index].let { viewModel.selectSort(it.sort, it.order) }
                },
                modifier = Modifier.weight(1f),
            )
        }

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
                    // 角色=characters∪cosWork，空组键归「其他」恒末位
                    sections = state.items.groupByAlbumDim(state.activeDim, nowMs),
                    columns = displayColumns,
                    animatedUrlResolver = animatedUrlResolver,
                    listState = listState,
                    // 底部预留沿用旧版 fragment_all_files.xml L149 的 180dp 档：悬浮面板退役后
                    // 已无遮挡末行之忧，保留作列表底部呼吸区（行为不变项零改动，任务G G5）
                    bottomContentPadding = QimengDimens.ListBottomContentPadding,
                    onNearBottom = viewModel::onNearBottom,
                    // 卡片点击进详情（D3 顺手修复：onAssetClick 有默认空实现漏传即静默无反应；
                    // 相册页暂无 Home 式批次上下文写入，详情 i/N 滑动链缺口另记待办）
                    onAssetClick = { asset: MediaAsset -> onOpenAsset(asset.id) },
                )
            }
            // 悬浮药丸面板已退役（任务G G5：值区块改 in-flow 见页头；QimengFloatingPillPanel
            // 组件本体保留在 :core:ui——收藏/历史页仍在用）
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
