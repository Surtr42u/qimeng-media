package media.qimeng.app.feature.detail

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Size
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 详情页（M4-3）：3a 骨架与排版（顶行 + 竖屏单列堆叠：媒体舞台 → 批次 pager 行（G1a）→
 * 标题 → meta 行 → 互动行 → 标签行 → 作者卡 → 接下来播放；排版基准 = Web AssetDetailPage.tsx
 * B站式布局的移动端移植）；
 * 3b 补沉浸模式（chromeVisible 状态单源：顶行+底部间距 AnimatedVisibility 隐显 + 系统栏
 * 隐显）与兄弟资产滑动接线（图片手势在 ImageStage，视频 3c）；3d 补视频全量接线
 * （续播/已看完徽标/进度上报/打点/时间轴标签经具名参数逐层下发，穿墙通道已删）；
 * D1 补图片全屏查看覆盖层（imageOverlayVisible 单源：排版态单击图片舞台打开，独立 Dialog
 * 窗口铺满整屏盖住顶行/底节，单击/系统返回退出；选型与实测取舍见
 * [ImageFullScreenOverlay] KDoc）。
 *
 * @param assetId 路由参数（ViewModel 经 SavedStateHandle 同键读取；此处显式保留供预览/测试）
 * @param onBack 返回（壳层 popBackStack）
 * @param onOpenAsset 跳资产（壳层导航 push 叠栈）：推荐栏跳转先经 VM.upNextJump 换批，
 *   兄弟资产滑动不换批（批次就是当前清单）——两条路共用此回调但只有前者动批次
 * @param onOpenAuthor 跳作者集合页（任务G G1b：作者卡名字点击，壳层导航 push 叠栈）
 */
@Composable
fun DetailScreen(
    assetId: String,
    onBack: () -> Unit,
    onOpenAsset: (assetId: String, batchIds: List<String>) -> Unit,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit = { _, _ -> },
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 沉浸模式 chrome 显隐（3b；媒体单击切换，默认可见；每屏独立——兄弟 push 是新路由实例）
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    // 图片全屏查看覆盖层开关（D1）：排版态单击图片舞台开，单击图片/系统返回关；
    // rememberSaveable 与 chromeVisible 同语义（每屏独立，进程重建后恢复态一致）
    var imageOverlayVisible by rememberSaveable { mutableStateOf(false) }
    // D1：覆盖层打开=强制沉浸（隐藏系统栏），与 chrome 可见性同走一套 WindowInsetsController
    // 显隐语义（复用不另开通道）；关闭即回落到排版态自身 chrome 态
    SystemBarsImmersiveEffect(chromeVisible = chromeVisible && !imageOverlayVisible)

    // 3d 生命周期接线：onPause → dwell 当前段兜底 flush + 进度 force 补报；onResume → dwell
    // 开新段（分段累加口径，见 DwellSessionTracker）；onDispose（组合离场，先于 VM onCleared
    // 且 viewModelScope 存活，是补报的可靠送达路径）→ dwell leave flush + 进度 force 补报
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenDisposed()
        }
    }

    // 3b 预载链消费（拍板③）：VM 只发目标清单，Coil 预取入队/取消在 UI 层——图片按原图
    // 尺寸（口径②：.size(Size.ORIGINAL) 不降采样），视频海报帧是小缩略图按默认档；
    // memoryCachePolicy 显式 ENABLED 确保预取产物写内存缓存（邻位切换首帧即出图）。
    // key=目标清单：清单变化（新邻位 url 就绪）或离场时，先 dispose 全部旧 Disposable
    // 再入新（对照旧版 preloadDisposables 按 key 取消语义；Map<id,Disposable> 兼防
    // 清单内重复 id 重复入队；LEGACY_REQUIREMENTS E 生命周期清理）
    DisposableEffect(state.preloadTargets) {
        val disposables = mutableMapOf<String, Disposable>()
        state.preloadTargets.forEach { target ->
            if (target.assetId in disposables) return@forEach // 防御：重复 id 不重复入队
            val request = ImageRequest.Builder(context)
                .data(target.url)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .apply { if (!target.isVideo) size(Size.ORIGINAL) }
                .build()
            disposables[target.assetId] = SingletonImageLoader.get(context).enqueue(request)
        }
        onDispose { disposables.values.forEach { it.dispose() } }
    }

    // 兄弟资产切换回调单源（拍板③：目标解析失败（越界/无批次/缺参）静默不动；不走
    // upNextJump 换批——批次清单就是当前清单；push 叠栈 = 浏览历史语义）。
    // D1：排版态舞台与全屏覆盖层共用同一条链（覆盖层直传，不另开通道）
    val onSiblingNavigate: (Int) -> Unit = { delta ->
        viewModel.moveBy(delta)?.let { targetId -> onOpenAsset(targetId, emptyList()) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶行随沉浸隐显（3b）：AnimatedVisibility 收放，返回键始终可用（系统返回）。
        // G1a：顶行不再带 i/N 批次序号（Web 顶行无计数）——序号随 DetailPagerRow 落舞台下方
        AnimatedVisibility(visible = chromeVisible) {
            DetailTopRow(onBack = onBack)
        }
        when {
            state.isLoading -> DetailLoadingState()
            state.asset == null -> DetailErrorState(
                message = state.errorMessage,
                onRetry = viewModel::retry,
            )
            else -> DetailLoadedContent(
                state = state,
                chromeVisible = chromeVisible,
                // 舞台动作具名下发（3d 解冻：原 DetailStageActions 穿墙通道已删）
                onSiblingNavigate = onSiblingNavigate,
                onToggleChrome = { chromeVisible = !chromeVisible },
                // D1：排版态单击图片舞台打开全屏覆盖层（开关状态单源在本屏）
                onOpenFullScreen = { imageOverlayVisible = true },
                // 推荐栏跳转：先换批次清单（推荐栏即新清单），再交壳层导航
                onOpenAsset = { id, batchIds ->
                    viewModel.upNextJump()
                    onOpenAsset(id, batchIds)
                },
                onOpenAuthor = onOpenAuthor,
                onPlaybackStarted = viewModel::onPlaybackStarted,
                onPositionChanged = viewModel::onPositionChanged,
                onAddTimelineTag = viewModel::addTimelineTag,
                onDeleteTimelineTag = viewModel::deleteTimelineTag,
                onToggleLike = viewModel::toggleLike,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleFollow = viewModel::toggleFollow,
                // 文件操作（任务G G1b）：互动行右端两钮只开关弹窗，提交走 VM（铁律 7 UI 零网络）
                onOpenMoveDialog = viewModel::openMoveSheet,
                onOpenDeleteDialog = viewModel::openDeleteConfirm,
                onOpenTagSheet = viewModel::openTagSheet,
                onReshuffle = viewModel::reshuffleUpNext,
                onDismissError = viewModel::clearError,
                onToggleTag = viewModel::toggleTagSelection,
                onCreateTag = viewModel::createAndSelectTag,
                onSaveTags = viewModel::saveTags,
                onDismissTagSheet = viewModel::dismissTagSheet,
            )
        }
    }

    // 文件操作弹窗（任务G G1b）：成功 toast 挂系统层（android.widget.Toast，离开本页仍可见
    // ——Web 根 Toaster 的 Android 等价物；App 无 snackbar 基建，Toast 是平台标准件）。
    // 删除成功后 onBack() 离开已删资产（Web navigate(-1) 同收尾）；列表数据刷新依赖
    // 返回后的自然重取（Compose 列表无 TanStack 缓存，返回即重拉——VM 无须通知列表页）
    val fileOpsAsset = state.asset
    if (state.moveSheetOpen && fileOpsAsset != null) {
        DetailMoveDialog(
            assetId = fileOpsAsset.id,
            currentDir = fileOpsAsset.directory.orEmpty(),
            currentName = fileOpsAsset.fileName,
            pending = state.fileOpsPending,
            errorMessage = state.moveError,
            onSubmit = { targetDir, newName ->
                viewModel.moveAsset(targetDir, newName) { moved, renamed ->
                    val message = when {
                        moved && renamed -> context.getString(
                            R.string.detail_move_toast_both,
                            targetDir.ifEmpty { context.getString(R.string.detail_move_root_dir) },
                            newName.orEmpty(),
                        )
                        moved -> context.getString(
                            R.string.detail_move_toast_moved,
                            targetDir.ifEmpty { context.getString(R.string.detail_move_root_dir) },
                        )
                        else -> context.getString(R.string.detail_move_toast_renamed, newName.orEmpty())
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = viewModel::dismissMoveSheet,
        )
    }
    if (state.deleteConfirmOpen && fileOpsAsset != null) {
        DetailDeleteConfirmDialog(
            fileName = fileOpsAsset.fileName,
            pending = state.fileOpsPending,
            onConfirm = {
                viewModel.deleteAsset {
                    Toast.makeText(
                        context,
                        context.getString(R.string.detail_delete_toast, fileOpsAsset.fileName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onBack()
                }
            },
            onDismiss = viewModel::dismissDeleteConfirm,
        )
    }
    // 图片全屏查看覆盖层（D1）：仅资产就绪时挂载。Dialog 独立窗口铺满整屏（不受壳层
    // Scaffold padding 约束，选型理由见组件 KDoc）；单击/系统返回退出回排版态。
    // 排版基准不动摇：Column 排版主体零改动，全屏只是叠加态
    state.asset?.let { asset ->
        if (imageOverlayVisible) {
            ImageFullScreenOverlay(
                asset = asset,
                onSiblingNavigate = onSiblingNavigate,
                onDismiss = { imageOverlayVisible = false },
            )
        }
    }
}

/** 加载成功后的滚动主体：单列堆叠各节 + 错误横幅（互动失败不退场，横幅照 home 模式可点关） */
@Composable
private fun DetailLoadedContent(
    state: DetailUiState,
    chromeVisible: Boolean,
    onSiblingNavigate: (delta: Int) -> Unit,
    onToggleChrome: () -> Unit,
    onOpenFullScreen: () -> Unit,
    onOpenAsset: (assetId: String, batchIds: List<String>) -> Unit,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit,
    onPlaybackStarted: () -> Unit,
    onPositionChanged: (positionSeconds: Double) -> Unit,
    onAddTimelineTag: (timeMillis: Long, name: String) -> Unit,
    onDeleteTimelineTag: (tagId: String) -> Unit,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleFollow: (String) -> Unit,
    onOpenMoveDialog: () -> Unit,
    onOpenDeleteDialog: () -> Unit,
    onOpenTagSheet: () -> Unit,
    onReshuffle: () -> Unit,
    onDismissError: () -> Unit,
    onToggleTag: (String) -> Unit,
    onCreateTag: (name: String, onCreated: () -> Unit) -> Unit,
    onSaveTags: () -> Unit,
    onDismissTagSheet: () -> Unit,
) {
    val asset = requireNotNull(state.asset)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        state.errorMessage?.let { message ->
            DetailErrorBanner(message = message, onDismiss = onDismissError)
        }
        DetailMediaStage(
            asset = asset,
            watched = state.videoWatched,
            startPositionMs = state.videoStartPositionMs,
            timelineTags = state.timelineTags,
            onSiblingNavigate = onSiblingNavigate,
            onToggleChrome = onToggleChrome,
            onOpenFullScreen = onOpenFullScreen,
            onPlaybackStarted = onPlaybackStarted,
            onPositionChanged = onPositionChanged,
            onAddTimelineTag = onAddTimelineTag,
            onDeleteTimelineTag = onDeleteTimelineTag,
        )
        // 批次 pager 行（任务G G1a，Web .asset-pager 对齐）：舞台与标题之间；无批次上下文
        // （batchIndex<0 深链单卡）DetailPagerRow 内部整行不渲染
        DetailPagerRow(
            batchIndex = state.batchIndex,
            batchSize = state.batchSize,
            onSiblingNavigate = onSiblingNavigate,
        )
        DetailTitle(title = asset.title)
        DetailMetaRow(asset = asset)
        DetailInteractionRow(
            asset = asset,
            likePending = state.likePending,
            favoritePending = state.favoritePending,
            fileOpsPending = state.fileOpsPending,
            onToggleLike = onToggleLike,
            onToggleFavorite = onToggleFavorite,
            onOpenMoveDialog = onOpenMoveDialog,
            onOpenDeleteDialog = onOpenDeleteDialog,
        )
        DetailTagRow(tags = asset.tags, onOpenTagSheet = onOpenTagSheet)
        DetailAuthorCard(
            authors = asset.authors,
            followPending = state.followPendingAuthorId != null,
            onToggleFollow = onToggleFollow,
            onOpenAuthor = onOpenAuthor,
        )
        DetailUpNextCard(
            upNext = state.upNext,
            upNextLoading = state.upNextLoading,
            onReshuffle = onReshuffle,
            onOpenAsset = onOpenAsset,
        )
        // 底部呼吸空间（避免末节贴系统导航栏；轻量档），随沉浸隐显（3b chrome 开关）
        AnimatedVisibility(visible = chromeVisible) {
            Box(modifier = Modifier.padding(bottom = DETAIL_BOTTOM_SPACER))
        }
    }
    // 标签管理弹窗挂滚动主体之外（ModalBottomSheet 自带遮罩层，不随内容滚动）
    if (state.tagSheetOpen) {
        DetailTagManageSheet(
            pool = state.tagPool,
            selectedTagIds = state.selectedTagIds,
            savingTags = state.savingTags,
            onToggleTag = onToggleTag,
            onCreateTag = onCreateTag,
            onSave = onSaveTags,
            onDismiss = onDismissTagSheet,
        )
    }
}

/** 页面级底部预留（轻量档；与网格页 180dp 防遮挡档语义不同。标签弹窗底部留白同档复用） */
internal val DETAIL_BOTTOM_SPACER = 24.dp

/**
 * 系统栏沉浸效果（3b，旧版语义）：chrome 可见=显示 statusBars+navigationBars，隐藏=隐藏
 * （下滑临时呼出=BEHAVIOR_DEFAULT 平台默认）。**只控显隐不触发布局重排**——图片不因系统栏
 * 切换重新居中；decorFitsSystemWindows(false) 恒成立由 MainActivity.enableEdgeToEdge 全局
 * 保证（等价于 WindowCompat.setDecorFitsSystemWindows(window,false)，此处不重复设置、
 * 也不在离开时恢复 true，避免整窗重排）。
 * 退出沉浸 = 再次单击（LaunchedEffect 翻转）或返回/兄弟 push 换屏（onDispose 恢复系统栏；
 * LEGACY_REQUIREMENTS E：controller 判空 + 生命周期清理）。
 */
@Composable
private fun SystemBarsImmersiveEffect(chromeVisible: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    val controller = remember(activity, view) {
        activity?.window?.let { window -> WindowCompat.getInsetsController(window, view) }
    }
    LaunchedEffect(controller, chromeVisible) {
        controller?.let { insets ->
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            val bars = WindowInsetsCompat.Type.systemBars()
            if (chromeVisible) insets.show(bars) else insets.hide(bars)
        }
    }
    DisposableEffect(controller) {
        onDispose {
            // 离开详情页（返回/推入下一资产）恢复系统栏，不留沉浸态给其他页面
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** 错误横幅纵向内边距（home 的 ErrorBanner 同款 4dp 轻贴边档） */
private val ERROR_BANNER_VERTICAL_PADDING = 4.dp

/** 加载态：居中「加载中…」（Web 首屏 grid-empty 同文案） */
@Composable
private fun DetailLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.detail_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 错误态（无内容可恢复：加载失败/路由缺参）：错误文案 + 重试 */
@Composable
private fun DetailErrorState(message: String?, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message ?: stringResource(R.string.detail_error_generic),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = QimengDimens.SpaceM)) {
                Text(text = stringResource(R.string.detail_retry))
            }
        }
    }
}

/** 错误横幅（home 的 ErrorBanner 同款：errorContainer 底 + 点击关闭；仅已加载内容态出现） */
@Composable
private fun DetailErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = ERROR_BANNER_VERTICAL_PADDING)
            .clickable(onClick = onDismiss),
    ) {
        Box(modifier = Modifier.padding(QimengDimens.SpaceM)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
