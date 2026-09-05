package media.qimeng.app.feature.all

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
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
import media.qimeng.app.core.ui.component.TabScrollController

/** 相册页标题（2026-09-05 导航四化：原「全部」页更名相册，route all 不变） */
private const val ALBUM_TITLE = "相册"

/** 相册 Tab 在壳导航里的路由（双击 Tab 回顶事件的过滤键） */
private const val ALBUM_ROUTE = "all"

/**
 * 相册页（M4-2，原全部页）：标题+统计行+列数切换（排序组已按用户拍板移除：相册=旧版全部页完全一致）+
 * 四维胶囊（分区/作者/角色·作品/类型）+ 日期分组网格 + 下拉刷新 + cursor 分页。
 */
@Composable
fun AllScreen(viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val columns by viewModel.albumColumns.collectAsStateWithLifecycle()
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
        TitleRow(
            title = ALBUM_TITLE,
            statLine = state.totalMatched?.let { "共 $it 项" } ?: "",
            columns = columns,
            onToggleColumns = viewModel::toggleColumns,
        )

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
                // 空状态保持空白（规格书 §全部页语义）
                if (!state.isLoading) QimengEmptyState(text = "")
                return@QimengPullToRefresh
            }
            QimengMediaGrid(
                sections = state.items.groupByDateLabel(nowMs) { it.modifiedAtMs },
                columns = columns,
                animatedUrlResolver = animatedUrlResolver,
                listState = listState,
                onNearBottom = viewModel::onNearBottom,
            )
        }
    }
}

/** 标题 + 统计行 + 列数切换（列数图标旧版为矢量切换，零新依赖档用「n列」文字按钮等价表达） */
@Composable
private fun TitleRow(
    title: String,
    statLine: String,
    columns: Int,
    onToggleColumns: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = statLine,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onToggleColumns) {
            Text(text = "${columns}列", style = MaterialTheme.typography.labelLarge)
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
