package media.qimeng.app.feature.favorite

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.PillSpec
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.groupByDateLabel
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengFourDimSection
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTopBar

/**
 * 收藏页（M4-2 覆盖页）：favorite=true + 收藏时间倒序（favoriteAt 降序）+
 * 四维胶囊同相册口径（facets 加 favorite 子集约束）+ 日期分组 + 下拉刷新。
 * 无清空按钮语义在此不涉及；详情页返回自动刷新随 M4-3 接互动行后生效。
 */
@Composable
fun FavoriteScreen(
    onBack: () -> Unit,
    viewModel: FavoriteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    // 收藏网格固定 3 列起步（覆盖页无列数持久化需求，规格书 §收藏页 3 列网格）
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

    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(title = "收藏", onBack = onBack)

        QimengFourDimSection(
            dimChips = FourDimPills.dimChips(pillModel).map { QimengPill(text = it.text, selected = it.selected) },
            activeDimIndex = state.activeDim.ordinal,
            onDimClick = { index -> viewModel.selectDim(AlbumDim.entries[index]) },
            pills = FourDimPills.pillsFor(pillModel).map { QimengPill(text = it.text, selected = it.selected) },
            onPillClick = { index ->
                dispatchPill(viewModel, state.activeDim, FourDimPills.pillsFor(pillModel).getOrNull(index))
            },
            expanded = state.filter.expanded,
            onCollapse = viewModel::collapsePills,
            onToggleExpand = viewModel::toggleExpanded,
        )

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        QimengPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.items.isEmpty()) {
                // 空态文案区分（规格书 §收藏页：COS「没有COS收藏」/其余「还没有收藏」）
                if (!state.isLoading) QimengEmptyState(text = state.emptyText)
                return@QimengPullToRefresh
            }
            QimengMediaGrid(
                sections = state.items.groupByDateLabel(nowMs) { it.modifiedAtMs },
                columns = columns,
                animatedUrlResolver = animatedUrlResolver,
                onNearBottom = viewModel::onNearBottom,
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
