package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.data.backup.BackupFileStatus
import media.qimeng.app.core.ui.component.QimengSettingsCard
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 备份页卡片组件（2026-09-19 任务S 两卡收敛重构自任务R 四入口版）：导入导出卡/自动备份卡/
 * 通用入口卡/规则说明，全部是纯渲染件——业务全在 BackupViewModel（铁律 7），视觉基准 =
 * LibraryManageScreen 库行卡的标题+小字节奏。
 */

// ---------- 导入导出卡文案（任务S 2026-09-19 拍板：直接读写备份目录的固定名文件，
// 不弹 SAF 选择器；作用于当前连接的端） ----------
private const val IMPORT_EXPORT_TITLE = "导入导出"
private val IMPORT_EXPORT_SUBTITLE =
    "备份当前连接的服务端，读写所选备份目录的 ${AutoBackupRunner.BACKUP_FILE_NAME}；导出会覆盖该文件。"
private const val IMPORT_EXPORT_EXPORT = "导出"
private const val IMPORT_EXPORT_EXPORT_BUSY = "导出中…"
private const val IMPORT_EXPORT_IMPORT = "导入"
private const val IMPORT_EXPORT_IMPORT_BUSY = "导入中…"
private const val FILE_STATUS_TEMPLATE = "%s · %s · %s"
private const val FILE_STATUS_NO_DIR = "备份目录未设置"
private const val FILE_STATUS_EMPTY = "备份目录还没有备份文件"
private const val FILE_SIZE_TEMPLATE = "%d KB"
private const val FILE_TIME_UNKNOWN = "—"

// ---------- 自动备份卡文案（2026-09-16 用户反馈：开关 + SAF 目录 + 上次备份时间；
// 任务S：「立即备份」按钮删除——与卡1「导出」重复） ----------
private const val AUTO_TITLE = "备份目录与自动备份"
private const val AUTO_SUBTITLE = "每日一次，打开应用时写入所选目录（备份当前连接的服务端）"
private const val AUTO_DIR_LABEL = "目录"
private const val AUTO_DIR_SET = "已选择目录"
private const val AUTO_DIR_UNSET = "未选择"
private const val AUTO_DIR_PICK = "选择目录"
private const val AUTO_LAST_LABEL = "上次备份"
private const val AUTO_LAST_NEVER = "未运行"

/** 目录提示（2026-10-11 用户报障）：系统选择器禁止选受限目录，且停在那里连列表都不展开——
 *  这条约束在 App 里无处可见，只能写在这（选择器本身只给「为保护您的隐私」一句） */
private const val AUTO_DIR_HINT = "受系统隐私限制：只能选子目录，不能选内部存储根目录 / Download"

/** 文案消费当前 v2 规则（docs/domain/migration.md），不再沿用 v1 聚合回放提示。 */
private val RULE_LINES = listOf(
    "· 仅支持本项目 v2 备份；备份不包含媒体原件",
    "· 浏览记录按事件身份合并，同一事件往返导入不重复累计",
    "· 文件按身份与路径信息匹配，未匹配或有歧义的条目跳过并提示",
    "· 标签组与作者关联按各自合并规则处理，导入后请核对结果",
)

/** 8dp：卡内元素纵向节奏（LibraryManageScreen RowInnerSpacing 同值） */
internal val BackupRowInnerSpacing = 8.dp

/** 12dp：卡四向内边距（LibraryManageScreen CardInnerPadding 同值） */
internal val BackupCardInnerPadding = 12.dp

/** 2dp：标题与副标题小字间距（同 Block 两行文本的紧凑节奏） */
internal val BackupSubtitleTopSpacing = 2.dp

/**
 * 备份入口通用卡（浏览数据同步等单行入口复用）：标题/说明在上，独立动作在下，窄屏不挤压正文。
 */
@Composable
internal fun BackupActionCard(
    title: String,
    subtitle: String,
    action: @Composable () -> Unit,
) {
    QimengSettingsCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(BackupCardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(BackupRowInnerSpacing),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.align(Alignment.End)) { action() }
        }
    }
}

/**
 * 卡1「导入导出」（任务S 2026-09-19 两卡收敛核心）：状态行 + 导出/导入两钮。导出/导入
 * 直接读写备份目录的固定名文件（执行体在 core:data AutoBackupRunner + BackupDirAccess，
 * 不弹 SAF 选择器），对象恒=当前连接的服务端。状态行：有文件显示 大小 · 修改时间；
 * 目录未设/无文件显空态提示（与导入的横幅文案口径一致，单源见 VM 常量）。
 */
@Composable
internal fun ImportExportCard(
    dirSet: Boolean,
    fileStatus: BackupFileStatus?,
    exporting: Boolean,
    importing: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val statusText = when {
        !dirSet -> FILE_STATUS_NO_DIR
        fileStatus == null -> FILE_STATUS_EMPTY
        else -> FILE_STATUS_TEMPLATE.format(
            AutoBackupRunner.BACKUP_FILE_NAME,
            FILE_SIZE_TEMPLATE.format((fileStatus.sizeBytes / BYTES_PER_KB_ROUND).roundToInt()),
            formatTimestamp(fileStatus.lastModifiedMillis, FILE_TIME_UNKNOWN),
        )
    }
    QimengSettingsCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BackupCardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(BackupRowInnerSpacing),
        ) {
            Text(text = IMPORT_EXPORT_TITLE, style = MaterialTheme.typography.titleSmall)
            Text(
                text = IMPORT_EXPORT_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(BackupRowInnerSpacing),
            ) {
                Button(onClick = onExport, enabled = dirSet && !exporting && !importing) {
                    Text(if (exporting) IMPORT_EXPORT_EXPORT_BUSY else IMPORT_EXPORT_EXPORT)
                }
                Button(onClick = onImport, enabled = dirSet && !exporting && !importing) {
                    Text(if (importing) IMPORT_EXPORT_IMPORT_BUSY else IMPORT_EXPORT_IMPORT)
                }
            }
        }
    }
}

/**
 * 自动备份卡（2026-09-16 用户反馈）：行1 开关 + 行2 目录（选择/已选择）+ 行3 上次备份
 * 时间。触发判定与写盘执行体在 core:data AutoBackupRunner（每日一次、打开应用时写入
 * 所选目录），本卡只做状态展示（铁律 7）；「立即备份」按钮已随任务S 收敛删除（与
 * 卡1「导出」同一写路径，重复入口）。
 */
@Composable
internal fun AutoBackupCard(
    enabled: Boolean,
    dirUri: String?,
    lastRunMillis: Long,
    onToggle: (Boolean) -> Unit,
    onPickDir: () -> Unit,
) {
    QimengSettingsCard(modifier = Modifier.fillMaxWidth()) {
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
            Text(
                text = AUTO_DIR_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$AUTO_LAST_LABEL：${formatTimestamp(lastRunMillis, AUTO_LAST_NEVER)}",
                style = MaterialTheme.typography.bodyMedium,
            )
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

/** 时间戳格式化（yyyy-MM-dd HH:mm；SimpleDateFormat 非线程安全，每次 new 不共享实例） */
private fun formatTimestamp(millis: Long, emptyText: String): String =
    if (millis <= 0L) {
        emptyText
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

/** KB 换算分母（与 VM NOTICE_EXPORT 同口径；四舍五入由 roundToInt 承担） */
private const val BYTES_PER_KB_ROUND = 1024.0
