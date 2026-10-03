package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.data.repository.VocabularySyncPreview
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（页标题取 hub 行名） */
private const val SCREEN_TITLE = "词表同步"
private const val PREVIEW_CARD_TITLE = "词表预览"
private const val PREVIEW_LOADING = "正在获取两端词表…"
private const val PREVIEW_EMPTY = "还没有预览结果"
private const val PREVIEW_RETRY = "重新获取预览"
private const val PREVIEW_COUNTS_TEMPLATE = "远端 %d 组 · 本机 %d 组"
private const val PREVIEW_LOCAL_ONLY_TEMPLATE = "本机独有 %d 组（同步后将丢失）"
private const val PREVIEW_STOP_WORDS_TEMPLATE = "停用词：远端 %d 个 · 本机 %d 个"
private const val APPLY_CONFIRM = "确认同步（覆盖本机词表）"
private const val APPLY_BUSY = "同步中…"

/** 规则说明三条（协议语义的用户侧投影；权威口径 DOMAIN_RULES §4 / ADR-0033/0034） */
private val RULE_LINES = listOf(
    "· 以当前登录的 NAS/电脑端词表为准，单向覆盖本机词表",
    "· 本机独有的词条与停用词在同步后会丢失（预览中已提示数量）",
    "· 同步完成后本机自动后台重算，稍后在检索与筛选中生效",
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
 * 词表同步页（ADR-0034，2026-10-04）：数据管理 hub「词表同步」子页。进页自动预览
 * （阶段一：远端/本机组数对照 + 本机独有计数），确认按钮（阶段二）单向下发覆盖本机。
 * 视觉/交互基准 = 备份导入导出子页（QimengTopBar + 16dp 滚动列 + 卡片 + 横幅三件套）；
 * 业务全在 VocabularySyncViewModel / core:data Repository（铁律 7），本屏零业务规则。
 */
@Composable
fun VocabularySyncScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VocabularySyncViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 阶段一自动触发：进页即预览。本机模式等前置不满足时以错误横幅呈现（任务书口径
    // 「进入即提示」）；hub 入口行恒可进——门禁判定在 repository，hub 不持有登录态知识
    LaunchedEffect(Unit) {
        viewModel.preview()
    }

    Column(modifier = modifier.fillMaxSize()) {
        QimengTopBar(title = SCREEN_TITLE, onBack = onBack)

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

            PreviewCard(preview = state.preview, previewing = state.previewing)

            if (state.preview == null && !state.previewing && state.noticeMessage == null) {
                // 预览失败/未就绪的重试口（含进页即本机模式的门禁提示场景）；
                // 同步成功态不显示（预览已清空，重试口会顶着结果提示复现）
                TextButton(
                    onClick = viewModel::preview,
                    enabled = !state.applying,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(PREVIEW_RETRY)
                }
            }

            Button(
                onClick = viewModel::apply,
                enabled = state.preview != null && !state.applying,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.applying) APPLY_BUSY else APPLY_CONFIRM)
            }

            RuleNotes()

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }
}

/** 预览卡（阶段一产物）：加载态/空态/两端组数对照 + 本机独有丢失提示 + 停用词对照 */
@Composable
private fun PreviewCard(preview: VocabularySyncPreview?, previewing: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(CardRowSpacing),
        ) {
            Text(text = PREVIEW_CARD_TITLE, style = MaterialTheme.typography.titleSmall)
            when {
                previewing -> SmallNote(PREVIEW_LOADING)
                preview == null -> SmallNote(PREVIEW_EMPTY)
                else -> {
                    Text(
                        text = PREVIEW_COUNTS_TEMPLATE.format(
                            preview.remoteGroupCount,
                            preview.localGroupCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // 本机独有 = 确认防线的核心数字：>0 时以 error 色强调「同步会丢」
                    Text(
                        text = PREVIEW_LOCAL_ONLY_TEMPLATE.format(preview.localOnlyGroupCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (preview.localOnlyGroupCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = PREVIEW_STOP_WORDS_TEMPLATE.format(
                            preview.remoteStopWordCount,
                            preview.localStopWordCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 卡内小字行（加载/空态共用） */
@Composable
private fun SmallNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 规则说明三条（BackupRuleNotes 同款小字列表） */
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
