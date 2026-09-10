package media.qimeng.app.feature.history

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
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.MediaAsset
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
import media.qimeng.app.core.ui.component.qimengPinchToColumns
import media.qimeng.app.core.ui.theme.QimengDimens
// 页头组件共享文案在 :core:ui（nonTransitiveRClass 下跨模块取资源须引对方 R）
import media.qimeng.app.core.ui.R as CoreUiR

/**
 * 浏览历史页（M4-2 覆盖页；M4-2A-B5 头部随相册页形态重排）：GET /history 每资产一条
 * （lastViewedAt 倒序，服务端口径）+ 分区/作品/角色/类型四维筛选（「作品」行=出处分组
 * 多选，N2 #30 source 数组位；N4 消费批接线）+ 按浏览日期分组 + 下拉刷新。
 * 头部形态照旧版实录 history.txt：返回 + 标题 + 芯片行 + 统计行「N 文件」（无筛选/列数图标——
 * 实录两页头部均无，主会话裁定 1/2）。
 * **无清空按钮**（2026-09-05 拍板 2B：协议无 DELETE /history，砍交互；协议缺口落档见交付报告）。
 * 任务I I5：双指缩放调列数 2~5（列数图标豁免不覆盖手势，R2）+ 列数持久化共用全部页档
 * （GUIDE_UI §全部页 L149 updateGridColumnsAll 口径）。任务V V1（2026-09-10）：详情返回
 * ON_RESUME 自动重拉已移除（用户拍板「返回时不要刷新界面」；lastViewedAt 重排滞后靠
 * 下拉刷新/下次冷启动收敛，取舍详见 HistoryViewModel 类 KDoc）。
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 网格列数：共用全部页档（grid_columns_all），初始值读档、手势结束持久化（见 VM）
    val columns by viewModel.gridColumns.collectAsStateWithLifecycle()
    // 双指缩放期间的瞬时列数优先展示（逐帧反馈在内存、手势结束才持久化一次——镜像 AllScreen 接线）
    val pinchColumns by viewModel.pinchColumns.collectAsStateWithLifecycle()
    val displayColumns = pinchColumns ?: columns
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    val nowMs = remember { System.currentTimeMillis() }

    // 任务V V1（2026-09-10）：已移除「详情返回 ON_RESUME 自动重拉」——无条件重拉使返回
    // 共享元素 morph（缩略图飞回）期间列表整体重显，用户拍板「返回时不要刷新界面…
    // 应该是原来的不变」。代价=刚浏览条目的 lastViewedAt 重排滞后，靠下拉刷新/下次
    // 冷启动收敛（详见 HistoryViewModel 类 KDoc 与 CHANGELOG 第一百八十八笔）。

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
        // totalForAllPill（当前筛选下「全部」桶计数，与实录 historyCount「435 文件」同位）
        QimengTitleRow(
            title = stringResource(R.string.history_title),
            statLine = state.totalForAllPill?.let { stringResource(CoreUiR.string.ui_stat_files, it) } ?: "",
            onBack = onBack,
        )

        // 维度芯片行常驻文档流（维度子集=分区/作品/角色/类型，GUIDE_UI §浏览历史 L386 顺序）；
        // 「角色 | 类型」间竖分隔线与相册页同款（实录 history.txt 芯片行 角色→类型 间隙 51px>18px）；
        // 芯片点击语义（点已激活维=切展开/折叠）在 ViewModel
        QimengChipRow(
            pills = FourDimPills.dimChips(pillModel, state.dims)
                .map { QimengPill(text = it.text, selected = it.selected) },
            onPillClick = { index -> viewModel.onDimChipClicked(state.dims[index]) },
            dividerBeforeIndex = state.dims.indexOf(AlbumDim.TYPE),
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
                    // 双指缩放调列数 2~5（GUIDE_UI §公共UI工具 L300）：步进即时生效（内存），
                    // 手势结束统一持久化一次到共用全部页档（镜像 AllScreen 既有接线）
                    .qimengPinchToColumns(
                        onStep = viewModel::adjustColumnsLive,
                        onGestureEnd = viewModel::commitPinchColumns,
                    ),
            ) {
                if (state.items.isEmpty()) {
                    // 空态文案两分支（规格书 §浏览历史，资源在 strings.xml）：
                    // COS「没有COS浏览记录」/其余「没有浏览记录」
                    if (!state.isLoading) QimengEmptyState(text = stringResource(state.emptyTextRes))
                    return@QimengPullToRefresh
                }
                // 历史页按浏览时间分组（lastViewedAt），非文件时间
                QimengMediaGrid(
                    sections = state.items.groupByDateLabel(nowMs) { it.lastViewedAtMs },
                    columns = displayColumns,
                    animatedUrlResolver = animatedUrlResolver,
                    // 底部预留 180dp：防悬浮药丸面板展开时遮挡末行（与相册页同款，旧版
                    // fragment_all_files.xml L149 clipToPadding=false 场景）
                    bottomContentPadding = QimengDimens.ListBottomContentPadding,
                    onNearBottom = viewModel::onNearBottom,
                    // 滚动暂停缩略图加载（任务I I5，GUIDE_UI §浏览历史 L394）：拖拽/fling 期间
                    // 暂缓新缩略图请求、停滚恢复（门控在 QimengThumbnail；QimengMediaGrid 开关）
                    pauseThumbnailsWhileScrolling = true,
                    // 卡片点击进详情（D3 同族顺手修复：onAssetClick 默认空实现漏传即静默无反应，
                    // 镜像 AllScreen/HomeScreen 接线）；点击统一走：先写批次上下文（详情页 i/N
                    // 序号+滑动切换数据链，2026-09-09 拍板对齐首页机制），再交壳层导航
                    onAssetClick = { asset: MediaAsset ->
                        viewModel.enterDetail(asset.id)
                        onOpenAsset(asset.id)
                    },
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

/** 药丸点击 → 状态机调用（分区/作品/角色/类型四维；payload 约定同相册页） */
private fun dispatchPill(
    viewModel: HistoryViewModel,
    dim: AlbumDim,
    spec: PillSpec?,
) {
    if (spec == null) return
    when (dim) {
        AlbumDim.PARTITION -> viewModel.selectPartition(spec.payload as? Zone ?: Zone.ALL)
        AlbumDim.AUTHOR -> viewModel.selectAuthor(spec.payload as? FacetOption)
        AlbumDim.CHARACTER -> viewModel.selectCharacter(spec.payload as? FacetOption)
        AlbumDim.TYPE -> viewModel.selectMediaType(spec.payload as? MediaKind)
    }
}
