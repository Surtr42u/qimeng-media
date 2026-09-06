package media.qimeng.app.feature.favorite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.PillSpec
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.groupByDateLabel
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengFloatingPillPanel
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTitleRow
import media.qimeng.app.core.ui.theme.QimengDimens
// 页头组件共享文案在 :core:ui（nonTransitiveRClass 下跨模块取资源须引对方 R）
import media.qimeng.app.core.ui.R as CoreUiR

/**
 * 收藏页（M4-2 覆盖页；M4-2A-B5 头部随相册页形态重排）：favorite=true + 收藏时间倒序
 * （favoriteAt 降序）+ 四维芯片行 + 悬浮药丸面板（叠放不推挤网格）+ 日期分组 + 下拉刷新。
 * 头部形态照旧版实录 favorite.txt：返回 + 标题 + 芯片行 + 统计行「N 文件」（无筛选/列数图标——
 * 实录两页头部均无，主会话裁定 1/2）；无清空按钮语义在此不涉及；
 * 详情页返回自动刷新随 M4-3 接互动行后生效。
 */
@Composable
fun FavoriteScreen(
    onBack: () -> Unit,
    viewModel: FavoriteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    // 收藏网格固定 3 列起步（覆盖页无列数持久化需求，规格书 §收藏页 3 列网格；
    // 头部不传列数控件——实录两页标题行无列数图标，主会话 2026-09-07 裁定 2）
    val columns = FAVORITE_COLUMNS
    val nowMs = remember { System.currentTimeMillis() }

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
        // 页头唯一实现于 :core:ui（任务A §5.2）：返回+标题+统计行；统计行沿用本页现计数
        // totalForAllPill（当前筛选下「全部」桶计数，与实录 favoriteCount「0 文件」同位）
        QimengTitleRow(
            title = stringResource(R.string.favorite_title),
            statLine = state.totalForAllPill?.let { stringResource(CoreUiR.string.ui_stat_files, it) } ?: "",
            onBack = onBack,
        )

        // 维度芯片行常驻文档流（旧版在网格上方推挤布局）；「角色 | 类型」间竖分隔线与相册页
        // 同款（实录两页芯片行 角色→类型 间隙均 51px>18px，判读同 fragment_all_files.xml L117-118）；
        // 芯片点击语义（点已激活维=切展开/折叠）在 ViewModel
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
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.items.isEmpty()) {
                    // 空态文案两分支（旧仓库 FavoriteFragment.kt L370-380 逐字，资源在 strings.xml）：
                    // COS「没有COS收藏」/其余双行「还没有收藏\n在详情页点击收藏按钮添加」
                    if (!state.isLoading) QimengEmptyState(text = stringResource(state.emptyTextRes))
                    return@QimengPullToRefresh
                }
                QimengMediaGrid(
                    sections = state.items.groupByDateLabel(nowMs) { it.modifiedAtMs },
                    columns = columns,
                    animatedUrlResolver = animatedUrlResolver,
                    // 底部预留 180dp：防悬浮药丸面板展开时遮挡末行（与相册页同款，旧版
                    // fragment_all_files.xml L149 clipToPadding=false 场景）
                    bottomContentPadding = QimengDimens.ListBottomContentPadding,
                    onNearBottom = viewModel::onNearBottom,
                )
            }
            // 悬浮药丸面板：Box 叠放不推挤网格（与相册页同款——旧版 FrameLayout 叠放 + elevation 4dp，P9-2）
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

/** 药丸点击 → 状态机调用（与相册页同一套 payload 约定） */
private fun dispatchPill(
    viewModel: FavoriteViewModel,
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

/** 收藏页网格列数（旧版 §收藏页 3 列网格语义） */
private const val FAVORITE_COLUMNS = 3
