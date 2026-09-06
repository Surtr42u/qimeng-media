package media.qimeng.app.feature.all

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.PillSpec
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.groupByAlbumDim
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengFloatingPillPanel
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTitleRow
import media.qimeng.app.core.ui.component.TabScrollController
import media.qimeng.app.core.ui.component.qimengPinchToColumns
import media.qimeng.app.core.ui.theme.QimengDimens
// 页头组件共享文案在 :core:ui（nonTransitiveRClass 下跨模块取资源须引对方 R）
import media.qimeng.app.core.ui.R as CoreUiR

/** 相册 Tab 在壳导航里的路由（双击 Tab 回顶事件的过滤键） */
private const val ALBUM_ROUTE = "all"

/**
 * 相册页（M4-2，原全部页；M4-2A-B2 对齐旧版「全部」页形态）：标题+统计行+列数图标（双指缩放可调）+
 * 四维芯片行 + 悬浮药丸面板（叠放不推挤网格）+ 按 activeDim 分派的分组网格 + 下拉刷新 + cursor 分页。
 */
@Composable
fun AllScreen(viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val columns by viewModel.albumColumns.collectAsStateWithLifecycle()
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
                android.util.Log.d("QimengM42", "scroll-to-top route=$ALBUM_ROUTE")
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
        // 页头唯一实现于 :core:ui（任务A §5.2，B5 收藏/历史页复用）——本页只做文案/参数接线
        QimengTitleRow(
            title = stringResource(R.string.all_title),
            statLine = state.totalMatched?.let { stringResource(CoreUiR.string.ui_stat_files, it) } ?: "",
            columns = displayColumns,
            onToggleColumns = viewModel::toggleColumns,
        )

        // 维度芯片行常驻文档流（旧版在网格上方推挤布局）；「角色 | 类型」间竖分隔线=旧版
        // fragment_all_files.xml L117-118；芯片点击语义（点已激活维=切展开/折叠）在 ViewModel
        QimengChipRow(
            pills = FourDimPills.dimChips(pillModel).map { QimengPill(text = it.text, selected = it.selected) },
            onPillClick = { index -> viewModel.onDimChipClicked(AlbumDim.entries[index]) },
            dividerBeforeIndex = AlbumDim.TYPE.ordinal,
            modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
        )

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
                    // 底部预留 180dp：防悬浮药丸面板展开时遮挡末行（旧版 L149 clipToPadding=false 同款）
                    bottomContentPadding = QimengDimens.ListBottomContentPadding,
                    onNearBottom = viewModel::onNearBottom,
                )
            }
            // 悬浮药丸面板：Box 叠放不推挤网格（旧版 FrameLayout 叠放 + elevation 4dp，P9-2）
            QimengFloatingPillPanel(
                pills = activePills.map { QimengPill(text = it.text, selected = it.selected) },
                onPillClick = { index -> dispatchPill(viewModel, state.activeDim, activePills.getOrNull(index)) },
                collapsed = !state.filter.expanded,
                onCollapse = viewModel::collapsePills,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
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
