package media.qimeng.app.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 卡文案（语义权威口径 DOMAIN_RULES §4 / ADR-0035） */
private const val CARD_TITLE = "词表合并同步"
private const val CARD_SUBTITLE = "两端词表只增不删，增量合并互不覆盖"
private const val ACTION_SYNC = "立即合并同步"
private const val ACTION_SYNC_BUSY = "同步中…"
private const val ERROR_NOTE_LABEL = "同步失败："

/** 12dp：卡四向内边距（BackupCards.BackupCardInnerPadding 同值，本文件私有单源） */
private val CardInnerPadding = 12.dp

/** 6dp：状态行与标题区纵向节奏（BackupSubtitleTopSpacing 同档） */
private val StatusLineSpacing = 6.dp

/** 10dp/6dp：状态行内边距（横 10 纵 6，小字软底条档） */
private val StatusLineHorizontalPadding = 10.dp
private val StatusLineVerticalPadding = 6.dp

/**
 * 词表合并同步卡（ADR-0035 定稿，2026-10-04 用户拍板入口迁入备份页）：备份页第三张
 * 同步卡——与「同步浏览数据」并排（数据流转类功能集中一页，入口更明确）。一键触发
 * 两端词表并集合并（只增不删、互不覆盖、无方向选择、无确认防线——合并无损即无需
 * 防线）；错误/结果提示以卡内状态行呈现（本卡自带 VM，与页级 BackupViewModel 横幅
 * 互不干扰）。业务全在 VocabularySyncViewModel / core:data（铁律 7），本卡零业务规则。
 */
@Composable
internal fun VocabularyMergeSyncCard(
    syncing: Boolean,
    errorMessage: String?,
    noticeMessage: String?,
    onSync: () -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(StatusLineSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(StatusLineSpacing),
                ) {
                    Text(text = CARD_TITLE, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = CARD_SUBTITLE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSync, enabled = !syncing) {
                    Text(if (syncing) ACTION_SYNC_BUSY else ACTION_SYNC)
                }
            }
            errorMessage?.let { message ->
                StatusLine(
                    text = ERROR_NOTE_LABEL + message,
                    color = MaterialTheme.colorScheme.error,
                    onDismiss = onDismissError,
                )
            }
            noticeMessage?.let { message ->
                StatusLine(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onDismiss = onDismissNotice,
                )
            }
        }
    }
}

/** 卡内状态行（错误/结果共用小字软底条；点击关闭） */
@Composable
private fun StatusLine(
    text: String,
    color: Color,
    onDismiss: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = StatusLineHorizontalPadding, vertical = StatusLineVerticalPadding),
    )
}
