package media.qimeng.app.feature.favorite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.PillSpec
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.groupByDateLabel
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTitleRow
import media.qimeng.app.core.ui.component.QimengValuePillBlock
import media.qimeng.app.core.ui.component.qimengPinchToColumns
import media.qimeng.app.core.ui.theme.QimengDimens
// 页头组件共享文案在 :core:ui（nonTransitiveRClass 下跨模块取资源须引对方 R）
import media.qimeng.app.core.ui.R as CoreUiR

/**
 * 收藏页（M4-2 覆盖页；M4-2A-B5 头部随相册页形态重排）：favorite=true + 收藏时间倒序
 * （favoriteAt 降序）+ 四维芯片行 + in-flow 值区块（2026-09-15 批用户反馈「收藏和浏览记录
 * 的胶囊没和相册的对齐」：悬浮面板退役，改 :core:ui QimengValuePillBlock 三页单源，与相册页
 * 呈现完全一致）+ 日期分组 + 下拉刷新。
 * 头部形态照旧版实录 favorite.txt：返回 + 标题 + 芯片行 + 统计行「N 文件」（无筛选/列数图标——
 * 实录两页头部均无，主会话裁定 1/2）；无清空按钮语义在此不涉及；
 * 任务I I5：双指缩放调列数 2~5（列数图标豁免不覆盖手势，R2）+ 列数持久化共用全部页档
 * （GUIDE_UI §全部页 L149 updateGridColumnsAll 口径）；详情页返回刷新经任务V V1（2026-09-10）
 * 收窄为收藏变更指纹门控（仅详情收藏变更后的返回重拉，纯浏览返回保持原样）。
 */
@Composable
fun FavoriteScreen(
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    viewModel: FavoriteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 网格列数：共用全部页档（grid_columns_all），初始值读档、手势结束持久化（见 VM）
    val columns by viewModel.gridColumns.collectAsStateWithLifecycle()
    // 双指缩放期间的瞬时列数优先展示（逐帧反馈在内存、手势结束才持久化一次——镜像 AllScreen 接线）
    val pinchColumns by viewModel.pinchColumns.collectAsStateWithLifecycle()
    val displayColumns = pinchColumns ?: columns
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    val nowMs = remember { System.currentTimeMillis() }

    // 详情页返回按收藏变更指纹门控刷新（任务V V1，2026-09-10）：返回/回前台（ON_RESUME）
    // 由 VM 对比 FavoriteMutationTracker 指纹，仅详情收藏变更过才重拉——纯浏览返回不刷新
    // （用户拍板「返回时…应该是原来的不变」，无条件重拉曾致返回 morph 期间列表整体重显）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        // 页头唯一实现于 :core:ui（任务A §5.2）：返回+标题+统计行；统计行沿用本页现计数
        // totalForAllPill（当前筛选下「全部」桶计数，与实录 favoriteCount「0 文件」同位）
        QimengTitleRow(
            title = stringResource(R.string.favorite_title),
            statLine = state.totalForAllPill?.let { stringResource(CoreUiR.string.ui_stat_files, it) } ?: "",
            onBack = onBack,
            // Y3 批（2026-09-12 全局字体对齐旧版）：页标题对齐旧版 fragment_favorite.xml L36-45
            // ——22sp Bold + qmColorTextPrimary（onSurface 槽）；字号 22sp 与 titleLarge 同值，
            // 只补 Bold 与主文字色
            titleStyle = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
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

        // 值区块 in-flow（2026-09-15 批与相册页统一：:core:ui QimengValuePillBlock 三页单源；
        // 整块显隐仍由 filter.expanded 的 D3 拍板语义控制，钳制/展开钮规格随组件收编）
        if (state.filter.expanded) {
            QimengValuePillBlock(
                pills = activePills.map { QimengPill(text = it.text, selected = it.selected) },
                onPillClick = { index -> dispatchPill(viewModel, state.activeDim, activePills.getOrNull(index)) },
                // 切维度归位「收起两行」（Web setDim 重置 expanded 同口径）
                resetKey = state.activeDim,
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
                    // 双指缩放调列数 2~5（GUIDE_UI §公共UI工具 L300）：步进即时生效（内存），
                    // 手势结束统一持久化一次到共用全部页档（镜像 AllScreen 既有接线）
                    .qimengPinchToColumns(
                        onStep = viewModel::adjustColumnsLive,
                        onGestureEnd = viewModel::commitPinchColumns,
                    ),
            ) {
                if (state.items.isEmpty()) {
                    // 空态文案两分支（旧仓库 FavoriteFragment.kt L370-380 逐字，资源在 strings.xml）：
                    // COS「没有COS收藏」/其余双行「还没有收藏\n在详情页点击收藏按钮添加」
                    if (!state.isLoading) QimengEmptyState(text = stringResource(state.emptyTextRes))
                    return@QimengPullToRefresh
                }
                QimengMediaGrid(
                    sections = state.items.groupByDateLabel(nowMs) { it.modifiedAtMs },
                    columns = displayColumns,
                    animatedUrlResolver = animatedUrlResolver,
                    // 底部预留沿用旧版 fragment_all_files.xml L149 的 180dp 档：悬浮面板退役后
                    // 已无遮挡末行之忧，保留作列表底部呼吸区（与相册页 G5 后口径一致）
                    bottomContentPadding = QimengDimens.ListBottomContentPadding,
                    onNearBottom = viewModel::onNearBottom,
                    // 滚动暂停缩略图加载（任务I I5，GUIDE_UI §收藏页 L411）：拖拽/fling 期间
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
