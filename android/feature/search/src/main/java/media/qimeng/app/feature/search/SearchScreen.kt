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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
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
    initialQuery: String? = null,
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

    // 携词跳转透传（任务I I3 授权的签名+透传改动；GUIDE_UI §统计详情页 v1.15
    // 「showSearchFragment(initialQuery) 携带标签名跳转，修复打开空白搜索页」）：
    // 进页即按该词提交（记历史+切结果态），等价于点建议词搜索；页面内部逻辑不动。
    // 当前统计页可跳来源=常看标签卡（协议缺口 #31d 冻结未渲染），管道先就位供详情链使用。
    // 修复E（2026-09-14）：深链提交走 [SearchViewModel.submitFromDeepLink] 置深链标记，
    // 供返回三分支判定「深链结果态返回直接退页」。
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) viewModel.submitFromDeepLink(initialQuery)
    }

    // 返回族同链：左上箭头与系统返回走同一分发（旧版 searchBack.setOnClickListener { handleBack() }）。
    // 修复E 三分支（2026-09-14 用户反馈「标签深链搜索页返回多一层」）：
    // ① EMPTY 相位 → onBack()（原语义不变）；
    // ② 深链结果态（fromDeepLink && RESULT && 当前词==初始词，trim 后比对）→ onBack()
    //    直接退页——旧版搜索是常驻 tab（清词回入口=回 tab 本体），新版是覆盖页，深链结果态
    //    返回再「清词回入口」会停在无意义的空搜索页、返回手势多一层；
    // ③ 其余（手动搜索结果/建议态、深链后已改词）走 viewModel.handleBack()——手动搜索的
    //    清词回入口语义保留=旧版逐字。
    val handleBackAction = {
        if (state.phase == SearchPhase.EMPTY) {
            onBack()
        } else if (state.fromDeepLink &&
            state.phase == SearchPhase.RESULT &&
            initialQuery != null &&
            state.query == initialQuery.trim()
        ) {
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
                // 卡片点击先写批次上下文再交壳层导航（RES R3 清偿本页 D3 注释挂账，
                // 详情页 i/N 序号+滑动切换数据链，与首页/收藏/历史同款机制）
                onAssetClick = { asset ->
                    viewModel.enterDetail(asset.id)
                    onOpenAsset(asset.id)
                },
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
        // 旧版 searchAction=文本按钮「搜索」（实录非图标）；Z4 批：14sp Regular（旧版非 Medium）
        TextButton(onClick = onSubmit) {
            Text(
                text = stringResource(R.string.search_action),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
            )
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
                // Z4 批：候选名 14sp（旧版建议行）、类型标签 10sp Regular 次色
                Text(text = suggestion.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = suggestion.kind.badgeLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal),
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
                // 分组 O(n) 计算包 remember（镜像 AllScreen 修复D-1）：按参与变量
                // （items/nowMs/dateCounts）缓存，重组零重算、数据变化才重算——分组键=文件时间；
                // dateCounts=服务端按本地日精确计数（组头不再随分页跳增）
                sections = remember(state.items, nowMs, state.dateCounts) {
                    state.items.groupByDateLabel(nowMs, state.dateCounts) { it.modifiedAtMs }
                },
                columns = displayColumns,
                animatedUrlResolver = animatedUrlResolver,
                onNearBottom = onNearBottom,
                // 问题A（2026-09-28）：加载结束重评估信号（KDoc 见 QimengMediaGrid.reloadTick）
                reloadTick = state.reloadTick,
                // 卡片点击进详情（D3 同族顺手修复：onAssetClick 默认空实现漏传即静默无反应，
                // 镜像相册/收藏/历史三页接线；批次上下文写入已由 RES R3 补齐——见调用方注释）
                onAssetClick = onAssetClick,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    // Z4 批（2026-09-12 搜索页字体对齐旧版）：区头 13sp Regular 次色（旧 search_entry.xml）
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
