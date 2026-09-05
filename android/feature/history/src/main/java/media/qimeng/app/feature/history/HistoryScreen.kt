package media.qimeng.app.feature.history

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
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.MediaKind
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
 * 浏览历史页（M4-2 覆盖页）：GET /history 每资产一条（lastViewedAt 倒序，服务端口径）+
 * 分区/角色·作品/类型筛选（作者行无协议参数，见 VM 注释）+ 按浏览日期分组 + 下拉刷新。
 * **无清空按钮**（2026-09-05 拍板 2B：协议无 DELETE /history，砍交互）。
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    val nowMs = remember { System.currentTimeMillis() }

    val pillModel = FourDimPillModel(
        filter = state.filter,
        activeDim = state.activeDim,
        partitionOptions = state.partitionOptions,
        authorOptions = emptyList(),
        characterOptions = state.characterOptions,
        typeOptions = state.typeOptions,
        totalForAllPill = state.totalForAllPill,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(title = "浏览历史", onBack = onBack)

        QimengFourDimSection(
            dimChips = FourDimPills.dimChips(pillModel, state.dims)
                .map { QimengPill(text = it.text, selected = it.selected) },
            activeDimIndex = state.dims.indexOf(state.activeDim),
            onDimClick = { index -> viewModel.selectDim(state.dims[index]) },
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
                // 空态文案区分（规格书 §浏览历史：COS「没有COS浏览记录」/其余「没有浏览记录」）
                if (!state.isLoading) QimengEmptyState(text = state.emptyText)
                return@QimengPullToRefresh
            }
            // 历史页按浏览时间分组（lastViewedAt），非文件时间
            QimengMediaGrid(
                sections = state.items.groupByDateLabel(nowMs) { it.lastViewedAtMs },
                columns = HISTORY_COLUMNS,
                animatedUrlResolver = animatedUrlResolver,
                onNearBottom = viewModel::onNearBottom,
            )
        }
    }
}

/** 药丸点击 → 状态机调用（历史页无分区作者/类型以外的维度；payload 约定同相册页） */
private fun dispatchPill(
    viewModel: HistoryViewModel,
    dim: AlbumDim,
    spec: PillSpec?,
) {
    if (spec == null) return
    when (dim) {
        AlbumDim.PARTITION -> viewModel.selectPartition(spec.payload as? Zone ?: Zone.ALL)
        AlbumDim.CHARACTER -> viewModel.selectCharacter(spec.payload as? FacetOption)
        AlbumDim.TYPE -> viewModel.selectMediaType(spec.payload as? MediaKind)
        // 历史页不出现作者行；防御性忽略
        AlbumDim.AUTHOR -> Unit
    }
}

/** 历史页网格列数（旧版 §浏览历史 3 列网格语义） */
private const val HISTORY_COLUMNS = 3
