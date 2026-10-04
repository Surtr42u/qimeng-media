package media.qimeng.app.feature.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（页标题取 hub 行名） */
private const val SCREEN_TITLE = "词表维护"
private const val STOP_WORDS_CARD_TITLE = "停用词"
private const val STOP_WORDS_EMPTY = "没有自定义停用词（内置冻结基线始终生效，这里只维护追加层）"
private const val STOP_WORDS_ADD = "添加停用词"
private const val SAVE_BUTTON = "保存修改"
private const val SAVE_BUSY = "保存中…"
private const val LOADING_NOTE = "正在读取本机词表…"

/** 对话框标题（VocabularyPrompt 单输入框机制的全部文案；构建点在本屏） */
private const val PROMPT_ADD_GROUP = "新增出处组（规范名）"
private const val PROMPT_RENAME_GROUP = "修改组规范名"
private const val PROMPT_ADD_VARIANT = "新增变体写法"
private const val PROMPT_ADD_CHARACTER = "新增角色（规范名）"
private const val PROMPT_RENAME_CHARACTER = "修改角色规范名"
private const val PROMPT_ADD_ALIAS = "新增角色别名"
private const val PROMPT_ADD_STOP_WORD = "新增停用词（兜底提取将跳过该词）"

/** 规则说明（协议语义的用户侧投影；权威口径 DOMAIN_RULES §4 / ADR-0033/0035） */
private val RULE_LINES = listOf(
    "· 编辑对象是本机内嵌库词表，保存后整体替换并自动后台重算",
    "· 组规范名与内置 130 组同名 = 并入扩词条，新名 = 追加新组",
    "· 保存时服务端自动去空去重；改组名不影响既有匹配（旧名自动并入变体）",
    "· 想把本机词条带给 NAS/电脑端：去「备份导入导出」页用「词表合并同步」，两端只增不删互不覆盖",
)

/** 16dp：内容水平内边距（备份/作者 TXT 子页同档） */
private val ScreenContentPadding = 16.dp

/** 8dp：卡内元素纵向节奏（BackupCards.BackupRowInnerSpacing 同值） */
private val CardRowSpacing = 8.dp

/** 12dp：卡四向内边距（BackupCards.BackupCardInnerPadding 同值） */
private val CardInnerPadding = 12.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/**
 * 词表维护页（ADR-0035，2026-10-04）：数据管理 hub「词表维护」子页，本机内嵌库检索词表
 * （自定义出处组 + 停用词）的直接编辑面。进页加载全量 → 内存编辑（出处组卡 + 停用词卡）
 * → 整体保存（PUT 显式 groups+stopWords）。无门禁（编辑对象恒为本机库，不涉远端）；
 * 有未保存修改时返回走放弃确认（系统返回与本页返回钮同口径）。视觉/交互基准 = 备份
 * 导入导出子页；业务全在 VocabularyEditViewModel / core:data Repository（铁律 7）。
 */
@Composable
fun VocabularyEditScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VocabularyEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingPrompt by remember { mutableStateOf<VocabularyPrompt?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // 进页自动加载；有未保存修改时系统返回同样走放弃确认（与本页返回钮同口径）
    LaunchedEffect(Unit) { viewModel.load() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    val requestBack = {
        if (state.dirty) confirmDiscard = true else onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        QimengTopBar(title = SCREEN_TITLE, onBack = requestBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceL),
        ) {
            state.errorMessage?.let { message ->
                StatusMessageCard(text = message, container = MaterialTheme.colorScheme.errorContainer) {
                    viewModel.dismissError()
                }
            }
            state.noticeMessage?.let { message ->
                StatusMessageCard(text = message, container = MaterialTheme.colorScheme.tertiaryContainer) {
                    viewModel.dismissNotice()
                }
            }
            if (state.loading) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = LOADING_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(CardInnerPadding),
                    )
                }
            }

            StopWordsCard(
                stopWords = state.stopWords,
                busy = state.loading || state.saving,
                onAdd = {
                    pendingPrompt = VocabularyPrompt(title = PROMPT_ADD_STOP_WORD) { viewModel.addStopWord(it) }
                },
                onRemove = viewModel::removeStopWord,
            )

            VocabularyGroupsCard(
                groups = state.groups,
                busy = state.loading || state.saving,
                onAddGroup = {
                    pendingPrompt = VocabularyPrompt(title = PROMPT_ADD_GROUP) { viewModel.addGroup(it) }
                },
                onRemoveGroup = viewModel::removeGroup,
                onRenameGroup = { index, current ->
                    pendingPrompt = VocabularyPrompt(title = PROMPT_RENAME_GROUP, initial = current) {
                        viewModel.renameGroup(index, it)
                    }
                },
                onAddVariant = { index ->
                    pendingPrompt = VocabularyPrompt(title = PROMPT_ADD_VARIANT) { viewModel.addVariant(index, it) }
                },
                onRemoveVariant = viewModel::removeVariant,
                onAddCharacter = { index ->
                    pendingPrompt = VocabularyPrompt(title = PROMPT_ADD_CHARACTER) { viewModel.addCharacter(index, it) }
                },
                onRemoveCharacter = viewModel::removeCharacter,
                onRenameCharacter = { groupIndex, characterIndex, current ->
                    pendingPrompt = VocabularyPrompt(title = PROMPT_RENAME_CHARACTER, initial = current) {
                        viewModel.renameCharacter(groupIndex, characterIndex, it)
                    }
                },
                onAddAlias = { groupIndex, characterIndex ->
                    pendingPrompt = VocabularyPrompt(title = PROMPT_ADD_ALIAS) {
                        viewModel.addAlias(groupIndex, characterIndex, it)
                    }
                },
                onRemoveAlias = viewModel::removeAlias,
            )

            Button(
                onClick = viewModel::save,
                enabled = state.dirty && !state.saving && !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) SAVE_BUSY else SAVE_BUTTON)
            }

            RuleNotes()

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }

    pendingPrompt?.let { prompt ->
        VocabularyPromptDialog(prompt = prompt, onDismiss = { pendingPrompt = null })
    }
    if (confirmDiscard) {
        VocabularyDiscardDialog(
            onDiscard = {
                confirmDiscard = false
                onBack()
            },
            onStay = { confirmDiscard = false },
        )
    }
}

/** 停用词卡：追加层清单（逐词可移除）+ 新增入口；内置冻结基线不在此列恒生效 */
@Composable
private fun StopWordsCard(
    stopWords: List<String>,
    busy: Boolean,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(CardRowSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = STOP_WORDS_CARD_TITLE, style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = onAdd,
                    enabled = !busy && stopWords.size < VocabularyLimits.STOP_WORDS_MAX,
                ) {
                    Text(STOP_WORDS_ADD)
                }
            }
            if (stopWords.isEmpty()) {
                SmallNote(STOP_WORDS_EMPTY)
            } else {
                stopWords.forEachIndexed { index, word ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TextButton(onClick = { onRemove(index) }, enabled = !busy) {
                            Text("移除")
                        }
                    }
                }
            }
        }
    }
}

/** 卡内小字行（加载/空态共用；与词表同步页同款） */
@Composable
private fun SmallNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 规则说明小字列表（BackupRuleNotes 同款） */
@Composable
private fun RuleNotes() {
    Column(verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXS)) {
        RULE_LINES.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
