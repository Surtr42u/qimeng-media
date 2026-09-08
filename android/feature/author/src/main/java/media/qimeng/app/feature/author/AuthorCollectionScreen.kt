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
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.groupByDateLabel
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 集合网格固定 3 列（覆盖页无列数持久化需求——收藏页 FAVORITE_COLUMNS 同口径档） */
private const val COLLECTION_COLUMNS = 3

/** 页头计数行前缀（Web CollectionPage page-head 副行「作者 · N 个文件」的 kindLabel 段） */
private const val COUNT_ROW_PREFIX = "作者"

/** 空态文案（Web CollectionPage「该${kindLabel}下暂无内容。」author 分支逐字） */
private const val EMPTY_COLLECTION = "该作者下暂无内容。"

/**
 * 作者集合页（任务G G1b，Web /app/collection/author/{name} 的 Android 等价物——精简版）：
 * 顶栏标题 = 作者名（路由参数 URL 解码原文）+ 计数行 + 按日期分组网格（DOMAIN_RULES §8
 * dateLabel 口径，[groupByDateLabel] 与相册/收藏页同源）+ 下拉刷新 + cursor 分页
 * （onNearBottom 增量）。无四维筛选（Web collection 页胶囊栏本批不做，交付报告记差异）。
 * 数据 = GET /assets authorId 精确过滤 + includeCos=true（ViewModel 注释口径）。
 */
@Composable
fun AuthorCollectionScreen(
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    viewModel: AuthorCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    // nowMs 一次快照：会话内分组标签稳定（与相册页同思路，不跨日跳动）
    val nowMs = remember { System.currentTimeMillis() }

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
                    // 首载中不占位（作者管理页同口径）；无内容给空态文案
                    if (!state.isLoading) QimengEmptyState(text = EMPTY_COLLECTION)
                    return@QimengPullToRefresh
                }
                QimengMediaGrid(
                    sections = state.items.groupByDateLabel(nowMs) { it.modifiedAtMs },
                    columns = COLLECTION_COLUMNS,
                    animatedUrlResolver = animatedUrlResolver,
                    bottomContentPadding = QimengDimens.ListBottomContentPadding,
                    onNearBottom = viewModel::onNearBottom,
                    onAssetClick = { asset: MediaAsset -> onOpenAsset(asset.id) },
                )
            }
        }
    }
}
