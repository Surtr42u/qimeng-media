package media.qimeng.app.feature.author

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.PillSpec
import media.qimeng.app.core.model.groupByDateLabel
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengFloatingPillPanel
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.component.qimengPinchToColumns
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页头计数行前缀（Web CollectionPage page-head 副行「作者 · N 个文件」的 kindLabel 段） */
private const val COUNT_ROW_PREFIX = "作者"

/** 空态文案（Web CollectionPage「该${kindLabel}下暂无内容。」author 分支逐字） */
private const val EMPTY_COLLECTION = "该作者下暂无内容。"

/**
 * 作者集合页（任务G G1b 建，任务I I6 补四维芯片体系）：顶栏标题 = 作者名 + 计数行 +
 * 维度子集芯片栏（常规作者=作品/角色/类型、COS 作者=角色/类型——GUIDE_UI §芯片栏配置
 * 对比 L68-69；「角色 | 类型」间竖分隔线与相册/收藏页同款）+ 悬浮药丸面板（拍板⑨：
 * 列表族悬浮形态保留；进页默认收起、切维度行强制展开——拍板②）+ 按日期分组网格 +
 * 下拉刷新 + cursor 分页 + 双指缩放 2~5 列（列数共用全部页档持久化，GUIDE_UI L36+L149）。
 * 数据 = GET /assets authorId 固定收窄 + includeCos 恒 true（ViewModel 注释口径）。
 */
@Composable
fun AuthorCollectionScreen(
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    viewModel: AuthorCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 网格列数：共用全部页档（grid_columns_all），初始值读档、手势结束持久化（见 VM）
    val columns by viewModel.gridColumns.collectAsStateWithLifecycle()
    // 双指缩放期间的瞬时列数优先展示（逐帧反馈在内存、手势结束才持久化一次——镜像 AllScreen 接线）
    val pinchColumns by viewModel.pinchColumns.collectAsStateWithLifecycle()
    val displayColumns = pinchColumns ?: columns
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    // nowMs 一次快照：会话内分组标签稳定（与相册/收藏页同思路，不跨日跳动）
    val nowMs = remember { System.currentTimeMillis() }
    // 维度子集（常规=作品/角色/类型；COS=角色/类型）——单源在 VM（路由 authorId 前缀判定）
    val dims = viewModel.dims

    val pillModel = FourDimPillModel(
        filter = state.filter,
        activeDim = state.activeDim,
        // 分区维不在本页维度子集（作者维度已锁定）：候选恒空，dimChips 也不会渲染分区芯片
        partitionOptions = emptyList(),
        authorOptions = state.authorOptions,
        characterOptions = state.characterOptions,
        typeOptions = state.typeOptions,
        // 「全部 (N)」药丸计数 = 服务端列表首响 totalMatched（当前其他维选择下的总数，
        // 与相册页分区栏 all 桶同口径，零额外请求）
        totalForAllPill = state.totalMatched,
    )
    val activePills = FourDimPills.pillsFor(pillModel, state.activeDim)

    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(title = viewModel.authorName, onBack = onBack)

        // 计数行（Web page-head 副行「作者 · N 个文件」；N=服务端列表首响 totalMatched，
        // 拉取前缺省 0——Web `?? 0` 同口径）
        Text(
            text = "$COUNT_ROW_PREFIX · ${state.totalMatched ?: 0} 个文件",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
        )

        // 维度芯片行常驻文档流（收藏/历史页同款）；「角色 | 类型」间竖分隔线对齐全/收页
        // （divider 落在子集内「类型」的下标——三芯片行在作品/角色之后、两芯片行在角色之后）
        QimengChipRow(
            pills = FourDimPills.dimChips(pillModel, dims).map { QimengPill(text = it.text, selected = it.selected) },
            onPillClick = { index -> viewModel.onDimChipClicked(dims[index]) },
            dividerBeforeIndex = dims.indexOf(AlbumDim.TYPE),
            modifier = Modifier
                // 计数行与芯片行原竖向 0 间距显挤（2026-09-14 用户反馈「作者文件数和胶囊
                // 挤在一起」）：补 SpaceM=8dp——token 出处即「芯片行上距」（fragment_all_files
                // .xml L72），语义正合，不另开档
                .padding(top = QimengDimens.SpaceM)
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
        )

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    // 芯片行与错误文案原竖向 0 间距，同类拥挤问题一并规范到 ≥8dp（同上口径）
                    .padding(top = QimengDimens.SpaceM)
                    .padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            QimengPullToRefresh(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    // 双指缩放调列数 2~5（GUIDE_UI §导航结构 L36 + §全部页 L149）：步进即时
                    // 生效（内存），手势结束统一持久化一次到共用全部页档（镜像 AllScreen 既有接线）
                    .qimengPinchToColumns(
                        onStep = viewModel::adjustColumnsLive,
                        onGestureEnd = viewModel::commitPinchColumns,
                    ),
            ) {
                if (state.items.isEmpty()) {
                    // 首载中不占位（作者管理页同口径）；无内容给空态文案
                    if (!state.isLoading) QimengEmptyState(text = EMPTY_COLLECTION)
                    return@QimengPullToRefresh
                }
                QimengMediaGrid(
                    sections = state.items.groupByDateLabel(nowMs) { it.modifiedAtMs },
                    columns = displayColumns,
                    animatedUrlResolver = animatedUrlResolver,
                    // 底部预留防悬浮药丸面板展开时遮挡末行（收藏/相册页同款）
                    bottomContentPadding = QimengDimens.ListBottomContentPadding,
                    onNearBottom = viewModel::onNearBottom,
                    // 卡片点击先写批次上下文再交壳层导航（RES R4：详情页 i/N 序号+滑动切换
                    // 数据链，与首页/收藏/搜索同款机制）
                    onAssetClick = { asset: MediaAsset ->
                        viewModel.enterDetail(asset.id)
                        onOpenAsset(asset.id)
                    },
                )
            }
            // 悬浮药丸面板：Box 叠放不推挤网格（拍板⑨：列表族悬浮形态保留；进页默认收起
            // ——filter.expanded 缺省 false，切维度行强制展开由 VM 状态机承接）
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

/** 药丸点击 → 状态机调用（与收藏/相册页同一套 payload 约定；分区维不在本页子集不分支） */
private fun dispatchPill(
    viewModel: AuthorCollectionViewModel,
    dim: AlbumDim,
    spec: PillSpec?,
) {
    if (spec == null) return
    when (dim) {
        AlbumDim.PARTITION -> Unit // 不可达：集合页维度子集无分区芯片（防御性 no-op）
        AlbumDim.AUTHOR -> viewModel.selectAuthor(spec.payload as? FacetOption)
        AlbumDim.CHARACTER -> viewModel.selectCharacter(spec.payload as? FacetOption)
        AlbumDim.TYPE -> viewModel.selectMediaType(spec.payload as? MediaKind)
    }
}
