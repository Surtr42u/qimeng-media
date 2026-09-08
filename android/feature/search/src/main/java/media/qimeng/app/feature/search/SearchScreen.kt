package media.qimeng.app.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.badgeLabel
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.groupByDateLabel
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengMediaGrid
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengWordPillFlow
import media.qimeng.app.core.ui.component.qimengPinchToColumns
import media.qimeng.app.core.ui.icon.BackIcon
import media.qimeng.app.core.ui.icon.ClearIcon
import media.qimeng.app.core.ui.icon.SearchIcon
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 搜索页（M4-2A-B4 对齐旧版三态，实录 search_entry/suggest/results 逐字口径）：
 * 入口态（顶栏三件 + 推荐搜索/搜索历史两区词丸流）/ 建议态（行=icon+候选名+右侧类型标签）/
 * 结果态（仅日期分组 3 列起步网格，双指缩放 2~5 列本页生效；无任何筛选芯片）。
 * 返回族同链（镜像旧版 handleBack，SearchFragment L253-270）：系统返回与左上箭头共用同一
 * 三态分发——结果/建议态一律清词回入口态，仅入口态退页（onBack→壳层 popBackStack）；
 * 「点搜索栏回建议态（词保留）」是点击路径（interceptResultFieldTap→backToSuggest），不走返回链。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    // 双指缩放期间的瞬时列数优先展示（手势结束落为本页内存列数——见 SearchViewModel）
    val pinchColumns by viewModel.pinchColumns.collectAsStateWithLifecycle()
    val displayColumns = pinchColumns ?: gridColumns
    val animatedUrlResolver = remember(viewModel) { viewModel.origUrlResolver::origUrl }
    // nowMs 一次快照：会话内分组标签稳定，不做跨日跳动（与相册页同思路）
    val nowMs = remember { System.currentTimeMillis() }

    // 返回族同链：左上箭头与系统返回走同一分发（旧版 searchBack.setOnClickListener { handleBack() }）
    val handleBackAction = {
        if (state.phase == SearchPhase.EMPTY) {
            onBack()
        } else {
            viewModel.handleBack() // 结果/建议态：清词回入口态（旧版 setText("")+STATE_EMPTY）
        }
    }
    BackHandler(onBack = handleBackAction)

    Column(modifier = Modifier.fillMaxSize()) {
        SearchTopBar(
            query = state.query,
            phase = state.phase,
            onQueryChange = viewModel::onQueryChange,
            onSubmit = { viewModel.submit(state.query) },
            onBackClick = handleBackAction,
            onFieldTap = viewModel::backToSuggest, // 结果态点搜索栏=回建议态改词（GUIDE_UI §搜索页；点击路径）
        )
        when (state.phase) {
            SearchPhase.EMPTY -> EmptyPhase(
                recommendWords = state.recommendWords,
                history = state.history,
                onPickWord = viewModel::submit,
                onClearHistory = viewModel::clearHistory,
            )
            SearchPhase.SUGGEST -> SuggestPhase(
                suggestions = state.suggestions,
                onPickWord = viewModel::submit,
            )
            SearchPhase.RESULT -> ResultPhase(
                state = state,
                displayColumns = displayColumns,
                nowMs = nowMs,
                animatedUrlResolver = animatedUrlResolver,
                onNearBottom = viewModel::onNearBottom,
                onPinchStep = viewModel::adjustColumnsLive,
                onPinchEnd = viewModel::commitPinchColumns,
                onAssetClick = { asset -> onOpenAsset(asset.id) },
            )
        }
    }
}

/** 顶栏三件（实录：返回 icon desc「返回」+ 输入框 + 右侧文本按钮「搜索」，无清除 icon）。
 *  返回箭头与系统返回同链（旧版 searchBack 同走 handleBack，见调用方 handleBackAction）。 */
@Composable
private fun SearchTopBar(
    query: String,
    phase: SearchPhase,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBackClick: () -> Unit,
    onFieldTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QimengDimens.SpaceM, vertical = QimengDimens.SpaceXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = BackIcon,
                contentDescription = stringResource(R.string.search_back_desc),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .interceptResultFieldTap(
                    enabled = phase == SearchPhase.RESULT,
                    onTap = onFieldTap,
                ),
        ) {
            QimengCapsuleTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.search_hint),
                // Web .hist-search：软底胶囊 + 前置放大镜（decorative，placeholder 已表意）
                leadingIcon = {
                    Icon(imageVector = SearchIcon, contentDescription = null)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 旧版 searchAction=文本按钮「搜索」（实录非图标）
        TextButton(onClick = onSubmit) {
            Text(text = stringResource(R.string.search_action))
        }
    }
}

/**
 * 结果态搜索栏点按拦截（GUIDE_UI §搜索页「结果状态下点击搜索栏：切回补全状态」）。
 * 为什么不用 Modifier.clickable：OutlinedTextField 内部光标/选区逻辑在 Main pass 先消费点按，
 * 同链 clickable 收不到完整手势（实机验证未触发）；这里仿 core/ui qimengPinchToColumns 的
 * Initial-pass 父层前置拦截——父层在子层看到事件前消费整段点按，抬起后回调一次。
 * [onTap] 须传稳定回调（读 VM 当前态，如 backToSuggest），避免 pointerInput(Unit) 闭包捕获旧值。
 */
private fun Modifier.interceptResultFieldTap(enabled: Boolean, onTap: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var anyPressed = true
                while (anyPressed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    anyPressed = event.changes.any { it.pressed }
                }
                onTap()
            }
        }
    }

/** 入口态：推荐搜索词丸流 + 搜索历史词丸流（有历史才显示，区头右侧清除 icon desc「清除历史」） */
@Composable
private fun EmptyPhase(
    recommendWords: List<NameSuggestion>,
    history: List<String>,
    onPickWord: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
    ) {
        item {
            SectionHeader(text = stringResource(R.string.search_section_recommend))
            WordPills(words = recommendWords.map { it.name }, onPick = onPickWord)
        }
        if (history.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(text = stringResource(R.string.search_section_history))
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onClearHistory) {
                        Icon(
                            imageVector = ClearIcon,
                            contentDescription = stringResource(R.string.search_clear_history_desc),
                        )
                    }
                }
                WordPills(words = history, onPick = onPickWord)
            }
        }
    }
}

/** 建议态：一行一条（左 icon+候选名+右侧类型徽标五维），从短到长已在 VM 排好 */
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
                    .padding(
                        horizontal = QimengDimens.ScreenPaddingHorizontal,
                        vertical = QimengDimens.SpaceL,
                    ),
            ) {
                Icon(
                    imageVector = SearchIcon,
                    contentDescription = stringResource(R.string.search_suggest_icon_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.padding(start = QimengDimens.SpaceM))
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

/**
 * 结果态：仅日期分组网格（组头「周四  N 项」/「2026-09-05  N 项」，groupByDateLabel 同口径），
 * 无任何芯片行/下拉刷新（实录 search_results）；双指缩放调列挂网格容器。
 */
@Composable
private fun ResultPhase(
    state: SearchUiState,
    displayColumns: Int,
    nowMs: Long,
    animatedUrlResolver: suspend (String) -> String?,
    onNearBottom: () -> Unit,
    onPinchStep: (Int) -> Unit,
    onPinchEnd: () -> Unit,
    onAssetClick: (MediaAsset) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .qimengPinchToColumns(onStep = onPinchStep, onGestureEnd = onPinchEnd),
    ) {
        if (state.items.isEmpty()) {
            if (!state.isLoading) {
                QimengEmptyState(text = stringResource(R.string.search_empty_result))
            }
        } else {
            QimengMediaGrid(
                sections = state.items.groupByDateLabel(nowMs) { it.modifiedAtMs },
                columns = displayColumns,
                animatedUrlResolver = animatedUrlResolver,
                onNearBottom = onNearBottom,
                // 卡片点击进详情（D3 同族顺手修复：onAssetClick 默认空实现漏传即静默无反应，
                // 镜像相册/收藏/历史三页接线；搜索页暂无批次上下文写入，缺口同记待办）
                onAssetClick = onAssetClick,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = QimengDimens.SpaceM),
    )
}

/** 词丸流接线（推荐搜索/搜索历史共用 core:ui 词丸组件，页面只做参数接线——§5 组件单源） */
@Composable
private fun WordPills(words: List<String>, onPick: (String) -> Unit) {
    QimengWordPillFlow(
        pills = words.map { QimengPill(text = it) },
        onPillClick = { index -> onPick(words[index]) },
        modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
    )
}
