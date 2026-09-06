package media.qimeng.app.feature.detail

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 详情页（M4-3 3a 骨架）：顶行（返回 + i/N 批次序号）+ 竖屏单列堆叠
 * 媒体舞台 → 标题 → meta 行 → 互动行 → 标签行 → 作者卡 → 接下来播放
 * （排版基准 = Web AssetDetailPage.tsx B站式布局的移动端移植，2026-09-05 拍板）。
 * 顶行沉浸逻辑、媒体手势/播放器（3b）与已看完徽标（3d）不在本批。
 *
 * @param assetId 路由参数（ViewModel 经 SavedStateHandle 同键读取；此处显式保留供预览/测试）
 * @param onBack 返回（壳层 popBackStack）
 * @param onOpenAsset 推荐栏跳转（壳层导航；VM.upNextJump 已先把批次清单换成推荐栏清单）
 */
@Composable
fun DetailScreen(
    assetId: String,
    onBack: () -> Unit,
    onOpenAsset: (assetId: String, batchIds: List<String>) -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopRow(
            batchIndex = state.batchIndex,
            batchSize = state.batchSize,
            onBack = onBack,
        )
        when {
            state.isLoading -> DetailLoadingState()
            state.asset == null -> DetailErrorState(
                message = state.errorMessage,
                onRetry = viewModel::retry,
            )
            else -> DetailLoadedContent(
                state = state,
                // 推荐栏跳转：先换批次清单（推荐栏即新清单），再交壳层导航
                onOpenAsset = { id, batchIds ->
                    viewModel.upNextJump()
                    onOpenAsset(id, batchIds)
                },
                onToggleLike = viewModel::toggleLike,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleFollow = viewModel::toggleFollow,
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
}

/** 加载成功后的滚动主体：单列堆叠各节 + 错误横幅（互动失败不退场，横幅照 home 模式可点关） */
@Composable
private fun DetailLoadedContent(
    state: DetailUiState,
    onOpenAsset: (assetId: String, batchIds: List<String>) -> Unit,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleFollow: (String) -> Unit,
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
        DetailMediaStage(asset = asset)
        DetailTitle(title = asset.title)
        DetailMetaRow(asset = asset)
        DetailInteractionRow(
            asset = asset,
            likePending = state.likePending,
            favoritePending = state.favoritePending,
            onToggleLike = onToggleLike,
            onToggleFavorite = onToggleFavorite,
        )
        DetailTagRow(tags = asset.tags, onOpenTagSheet = onOpenTagSheet)
        DetailAuthorCard(
            authors = asset.authors,
            followPending = state.followPendingAuthorId != null,
            onToggleFollow = onToggleFollow,
        )
        DetailUpNextCard(
            upNext = state.upNext,
            upNextLoading = state.upNextLoading,
            onReshuffle = onReshuffle,
            onOpenAsset = onOpenAsset,
        )
        // 底部呼吸空间（避免末节贴系统导航栏；3b 沉浸批再按需调整）
        Box(modifier = Modifier.padding(bottom = DETAIL_BOTTOM_SPACER))
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
