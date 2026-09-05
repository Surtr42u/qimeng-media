package media.qimeng.app.feature.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AuthorSortOption
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTopBar

/** 作者管理胶囊（全部/常规/COS）+ 排序三项（默认/浏览数/文件数） */
private val ZONE_OPTIONS = listOf("全部" to Zone.ALL, "常规" to Zone.REGULAR, "COS" to Zone.COS)

/**
 * 作者管理页（M4-2 覆盖页）：体系胶囊 + 名字搜索 + 排序三项 + 关注 toggle
 * （GET /authors 全量 + PUT /authors/{authorId}/follow；作者文件子页 M4-3 交界）。
 */
@Composable
fun AuthorScreen(
    onBack: () -> Unit,
    viewModel: AuthorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(title = "作者管理", onBack = onBack)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = viewModel::onKeywordChange,
                placeholder = { Text(text = "按名字搜索") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        QimengChipRow(
            pills = ZONE_OPTIONS.map { (label, zone) ->
                QimengPill(text = label, selected = zone == state.zone)
            },
            onPillClick = { index -> viewModel.selectZone(ZONE_OPTIONS[index].second) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        QimengChipRow(
            pills = AuthorSortOption.entries.map { sort ->
                QimengPill(text = sort.label, selected = sort == state.sort)
            },
            onPillClick = { index -> viewModel.selectSort(AuthorSortOption.entries[index]) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
            val rows = viewModel.visibleRows(state)
            if (rows.isEmpty()) {
                if (!state.isLoading) QimengEmptyState(text = "暂无作者")
                return@QimengPullToRefresh
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { author ->
                    AuthorRow(author = author, onToggleFollow = { viewModel.toggleFollow(author) })
                }
            }
        }
    }
}

/** 作者行：显示名（COS 追加 ·COS 标识）+ 文件数 + 关注按钮 */
@Composable
private fun AuthorRow(
    author: AuthorSummary,
    onToggleFollow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行级点击留待 M4-3 作者文件子页（关注 toggle 只在右侧按钮，不放大到整行）
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (author.type == media.qimeng.app.core.model.AuthorType.COS) {
                    "${author.displayName} ·COS"
                } else {
                    author.displayName
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            author.fileCount?.let {
                Text(
                    text = "$it 个文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(100.dp),
            color = if (author.followed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.clickable(onClick = onToggleFollow),
        ) {
            Text(
                text = if (author.followed) "已关注" else "关注",
                style = MaterialTheme.typography.labelLarge,
                color = if (author.followed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
