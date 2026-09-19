package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import media.qimeng.app.core.data.repository.StagedBackupMeta
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 备份页卡片组件（任务R 2026-09-19 四入口重做拆分自 BackupScreen——单文件 ≤500 行纪律）：
 * 通用入口卡/顶部暂存卡/自动备份卡/规则说明，全部是纯渲染件——业务全在 BackupViewModel
 * （铁律 7），视觉基准 = LibraryManageScreen 库行卡的标题+小字节奏。
 */

// ---------- 顶部暂存卡（跨端同步目标端显著位；任务R 拍板文案「待同步：来自 <sourceUrl>（时间，大小，N 文件）」） ----------
private const val STAGED_CARD_TITLE = "待同步"
private const val STAGED_BODY_TEMPLATE = "来自 %s（%s，%d KB）"
private const val STAGED_BODY_WITH_FILES_TEMPLATE = "来自 %s（%s，%d KB，%d 文件）"
private const val STAGED_IMPORT_ACTION = "导入并合并到当前端"
private const val STAGED_IMPORT_BUSY = "导入中…"

// ---------- 自动备份卡文案（2026-09-16 用户反馈：开关 + SAF 目录 + 立即备份 + 上次备份时间） ----------
private const val AUTO_TITLE = "自动备份"
private const val AUTO_SUBTITLE = "每日一次，打开应用时写入所选目录（备份当前连接的服务端）"
private const val AUTO_DIR_LABEL = "目录"
private const val AUTO_DIR_SET = "已选择目录"
private const val AUTO_DIR_UNSET = "未选择"
private const val AUTO_DIR_PICK = "选择目录"
private const val AUTO_LAST_LABEL = "上次备份"
private const val AUTO_LAST_NEVER = "未运行"
private const val AUTO_RUN_NOW = "立即备份"
private const val AUTO_RUN_BUSY = "备份中…"

/** 规则说明四条（Web L297-299 逐字；2026-09-16 置主卡下方；2026-09-18 增跨端同步口径） */
private val RULE_LINES = listOf(
    "· 导入幂等：同一备份重复导入不翻倍（作者/标签/关联按唯一键合并）",
    "· 统计/历史按事件回放重建；mediaFiles 仅在文件名能匹配到库内文件时建立关联",
    "· 扫描源等设备本机段不迁移，导入后请核对库注册与根路径",
    "· 跨端同步：标签/作者/收藏按最新合并；浏览统计按批次回放——重新暂存（新批次）后再导入会重复累计浏览统计",
)

/** 8dp：卡内元素纵向节奏（LibraryManageScreen RowInnerSpacing 同值） */
internal val BackupRowInnerSpacing = 8.dp

/** 12dp：卡四向内边距（LibraryManageScreen CardInnerPadding 同值） */
internal val BackupCardInnerPadding = 12.dp

/** 2dp：标题与副标题小字间距（同 Block 两行文本的紧凑节奏） */
internal val BackupSubtitleTopSpacing = 2.dp

/**
 * 备份入口通用卡（任务R 四入口重做）：一张卡一个入口，左标题+副标题小字、右动作钮。
 * 原「主卡四行」拆开——四入口各自成卡是信息架构收敛的核心（导入/导出对象恒=当前连接的端）。
 */
@Composable
internal fun BackupActionCard(
    title: String,
    subtitle: String,
    action: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BackupCardInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BackupRowInnerSpacing),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BackupSubtitleTopSpacing),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action()
        }
    }
}

/**
 * 顶部暂存卡（任务R：跨端同步目标端显著位）——staged 非 null 时整页最显眼的卡
 * （secondaryContainer 底色区隔于普通入口卡），展示「来自哪端/时间/大小/N 文件」+
 * 一键「导入并合并到当前端」（导入走与文件导入完全相同的校验→确认→幂等导入链路）。
 * mediaFiles=null（暂存内容未过校验）时隐藏 N 项，导入按钮仍可用——导入侧会再完整
 * 校验并给出可读错误，卡上不替用户下结论。
 */
@Composable
internal fun StagedSyncCard(
    meta: StagedBackupMeta,
    mediaFiles: Int?,
    importing: Boolean,
    onImport: () -> Unit,
) {
    // 为什么 mediaFiles 可空：摘要来自读暂存后过 BackupValidator，校验未过取不到计数
    val body = if (mediaFiles != null) {
        STAGED_BODY_WITH_FILES_TEMPLATE.format(
            meta.sourceUrl,
            formatLastRun(meta.stagedAtMillis),
            (meta.sizeBytes / BYTES_PER_KB_ROUND).roundToInt(),
            mediaFiles,
        )
    } else {
        STAGED_BODY_TEMPLATE.format(
            meta.sourceUrl,
            formatLastRun(meta.stagedAtMillis),
            (meta.sizeBytes / BYTES_PER_KB_ROUND).roundToInt(),
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BackupCardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(BackupRowInnerSpacing),
        ) {
            Text(text = STAGED_CARD_TITLE, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImport, enabled = !importing) {
                Text(if (importing) STAGED_IMPORT_BUSY else STAGED_IMPORT_ACTION)
            }
        }
    }
}

/**
 * 自动备份卡（2026-09-16 用户反馈）：行1 开关 + 行2 目录（选择/已选择）+ 行3 上次备份
 * 时间 + 立即备份。触发判定与写盘执行体在 core:data AutoBackupRunner（每日一次、
 * 打开应用时写入所选目录），本卡只做状态展示与手动触发（铁律 7）。
 */
@Composable
internal fun AutoBackupCard(
    enabled: Boolean,
    dirUri: String?,
    lastRunMillis: Long,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onPickDir: () -> Unit,
    onRunNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BackupCardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(BackupRowInnerSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BackupSubtitleTopSpacing),
                ) {
                    Text(text = AUTO_TITLE, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = AUTO_SUBTITLE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BackupRowInnerSpacing),
            ) {
                Text(
                    text = "$AUTO_DIR_LABEL：${if (dirUri != null) AUTO_DIR_SET else AUTO_DIR_UNSET}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickDir) { Text(AUTO_DIR_PICK) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BackupRowInnerSpacing),
            ) {
                Text(
                    text = "$AUTO_LAST_LABEL：${formatLastRun(lastRunMillis)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !busy, onClick = onRunNow) {
                    Text(if (busy) AUTO_RUN_BUSY else AUTO_RUN_NOW)
                }
            }
        }
    }
}

/** 规则说明四条（Web rank-note 小字列表同位） */
@Composable
internal fun BackupRuleNotes() {
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

/** 上次备份时间格式化（0=从未运行；SimpleDateFormat 非线程安全，每次 new 不共享实例） */
private fun formatLastRun(millis: Long): String =
    if (millis <= 0L) {
        AUTO_LAST_NEVER
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

/** KB 换算分母（与 VM NOTICE_EXPORT/NOTICE_STAGED 同口径；四舍五入由 roundToInt 承担） */
private const val BYTES_PER_KB_ROUND = 1024.0
