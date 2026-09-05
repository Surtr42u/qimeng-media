package media.qimeng.app.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.FourDimPillModel
import media.qimeng.app.core.model.FourDimPills
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.badgeLabel
import media.qimeng.app.core.model.groupByDateLabel
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengTopBar

/** 搜索页分区胶囊（全部缺省=显式 includeCos=1；拍板 A4，协议 /assets 缺省排除 COS） */
private val PARTITION_OPTIONS = listOf(
    "全部" to Zone.ALL,
    "常规" to Zone.REGULAR,
    "COS" to Zone.COS,
)

/** 类型档（综合=不传；「音频」不存在——MediaType 仅 image/animated_image/video） */
private val TYPE_OPTIONS = listOf(
    "综合" to null,
    "图片" to media.qimeng.app.core.model.MediaKind.IMAGE,
    "动图" to media.qimeng.app.core.model.MediaKind.ANIMATED_IMAGE,
    "视频" to media.qimeng.app.core.model.MediaKind.VIDEO,
)

/**
 * 搜索页（M4-2 覆盖页）三层状态（规格书 §搜索页）：
 * 空（推荐搜索+搜索历史含清除）/ 补全（从短到长+右侧类型徽标）/ 结果（分区+类型胶囊+日期分组网格）。
 * 返回导航：结果→补全→空由输入框状态驱动；整页返回由壳层 popBackStack 完成。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    val nowMs = remember { System.currentTimeMillis() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = media.qimeng.app.core.ui.icon.BackIcon, contentDescription = "返回")
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text(text = "搜索") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit(state.query) }),
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        enabled = state.phase == media.qimeng.app.feature.search.SearchPhase.RESULT,
                        onClickLabel = "修改搜索词",
                        onClick = viewModel::backToSuggest,
                    ),
            )
            IconButton(onClick = { viewModel.submit(state.query) }) {
                Icon(imageVector = media.qimeng.app.core.ui.icon.SearchIcon, contentDescription = "搜索")
            }
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { viewModel.onQueryChange("") }) {
                    Icon(imageVector = media.qimeng.app.core.ui.icon.ClearIcon, contentDescription = "清除")
                }
            }
        }

        when (state.phase) {
            media.qimeng.app.feature.search.SearchPhase.EMPTY -> EmptyPhase(
                recommendWords = state.recommendWords,
                history = state.history,
                onPickWord = viewModel::submit,
                onClearHistory = viewModel::clearHistory,
            )
            media.qimeng.app.feature.search.SearchPhase.SUGGEST -> SuggestPhase(
                suggestions = state.suggestions,
                onPickWord = viewModel::submit,
            )
            media.qimeng.app.feature.search.SearchPhase.RESULT -> ResultPhase(
                state = state,
                nowMs = nowMs,
                animatedUrlResolver = animatedUrlResolver,
                viewModel = viewModel,
            )
        }
    }
}

/** 空态：推荐搜索 ChipGroup + 搜索历史 ChipGroup（含清除按钮） */
@Composable
private fun EmptyPhase(
    recommendWords: List<NameSuggestion>,
    history: List<String>,
    onPickWord: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            SectionHeader(text = "推荐搜索")
            ChipWrap(
                words = recommendWords.map { it.name },
                onPick = onPickWord,
            )
        }
        if (history.isNotEmpty()) {
            item {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(text = "搜索历史")
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onClearHistory) {
                        Icon(
                            imageVector = media.qimeng.app.core.ui.icon.ClearIcon,
                            contentDescription = "清空搜索历史",
                        )
                    }
                }
                ChipWrap(words = history, onPick = onPickWord)
            }
        }
    }
}

/** 补全态：一行一条（搜索图标+文字+右侧类型徽标），从短到长已在 VM 排好 */
@Composable
private fun SuggestPhase(
    suggestions: List<NameSuggestion>,
    onPickWord: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(suggestions, key = { "${it.kind.name}:${it.name}" }) { suggestion ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickWord(suggestion.name) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = media.qimeng.app.core.ui.icon.SearchIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text(text = suggestion.name, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = suggestion.kind.badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 结果态：分区+类型胶囊 + 日期分组网格 + 下拉刷新 + 分页 */
@Composable
private fun ResultPhase(
    state: SearchUiState,
    nowMs: Long,
    animatedUrlResolver: suspend (String) -> String?,
    viewModel: SearchViewModel,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 分区胶囊（缺省全部=includeCos=1）+ 类型胶囊（综合=不传）
        QimengChipRow(
            pills = PARTITION_OPTIONS.map { (label, zone) ->
                QimengPill(text = label, selected = zone == state.partition)
            },
            onPillClick = { index -> viewModel.selectPartition(PARTITION_OPTIONS[index].second) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.padding(vertical = 2.dp))
        QimengChipRow(
            pills = TYPE_OPTIONS.map { (label, kind) ->
                QimengPill(text = label, selected = kind == state.mediaType)
            },
            onPillClick = { index -> viewModel.selectMediaType(TYPE_OPTIONS[index].second) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        QimengPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.items.isEmpty()) {
                if (!state.isLoading) QimengEmptyState(text = "没有找到相关内容")
                return@QimengPullToRefresh
            }
            QimengMediaGrid(
                sections = state.items.groupByDateLabel(nowMs) { it.modifiedAtMs },
                columns = SEARCH_RESULT_COLUMNS,
                animatedUrlResolver = animatedUrlResolver,
                onNearBottom = viewModel::onNearBottom,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** 词组胶囊流（推荐搜索/搜索历史共用；自动换行由 FlowRow 组件承担） */
@Composable
private fun ChipWrap(words: List<String>, onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        words.forEach { word ->
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onPick(word) },
            ) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 搜索结果 3 列网格（规格书 §搜索页） */
private const val SEARCH_RESULT_COLUMNS = 3
