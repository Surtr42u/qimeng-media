package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import media.qimeng.app.core.data.backup.BackupFileStatus
import media.qimeng.app.core.ui.component.QimengSettingsCard
import media.qimeng.app.core.ui.icon.BackupRestoreIcon
import media.qimeng.app.core.ui.icon.HistoryRecentIcon

/** Maintenance cards render existing operations without owning backup rules. */
@Composable
internal fun BackupActionCard(title: String, subtitle: String, action: @Composable () -> Unit) {
    QimengSettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.align(Alignment.End)) { action() }
        }
    }
}

@Composable
internal fun ImportExportCard(
    dirSet: Boolean,
    fileStatus: BackupFileStatus?,
    exporting: Boolean,
    importing: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onImportAutomatic: () -> Unit,
    onImportOlder: (() -> Unit)? = null,
    rolling: Boolean = false,
) {
    val busy = exporting || importing
    val locale = LocalConfiguration.current.locales[0]
    QimengSettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(BackupRestoreIcon, null, Modifier.size(24.dp))
                Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
            }
            Text("保存作者、头像、标签和浏览记录。媒体原件请单独保留。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (rolling) "最新备份 · 自动保留三代" else "手动备份", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when {
                        !dirSet -> "先设置维护目录"
                        fileStatus == null -> "还没有保存备份"
                        else -> formatTimestamp(fileStatus.lastModifiedMillis, "时间未知")
                    }, style = MaterialTheme.typography.bodyMedium)
                    fileStatus?.let {
                        Text(String.format(locale, "%.1f MB · v2 完整资料", it.sizeBytes / 1048576.0),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onExport, enabled = dirSet && !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Text(if (exporting) "保存中…" else "导出备份")
                }
                OutlinedButton(onClick = onImport, enabled = dirSet && !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Text(if (importing) "恢复中…" else "导入恢复")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onImportAutomatic, enabled = dirSet && !busy) {
                    Icon(HistoryRecentIcon, null, Modifier.padding(end = 8.dp).size(18.dp))
                    Text(if (rolling) "恢复上次" else "从自动备份恢复")
                }
                onImportOlder?.let { restore ->
                    TextButton(onClick = restore, enabled = dirSet && !busy) { Text("恢复上上次") }
                }
            }
        }
    }
}

@Composable
internal fun AutoBackupCard(
    enabled: Boolean,
    dirUri: String?,
    lastRunMillis: Long,
    onToggle: (Boolean) -> Unit,
    onPickDir: () -> Unit,
    showDirectory: Boolean = true,
) {
    QimengSettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("自动备份", style = MaterialTheme.typography.titleSmall)
                    Text("每天一次，打开影库时保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Text("最近完成  ${formatTimestamp(lastRunMillis, "尚未运行")}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showDirectory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (dirUri == null) "尚未选择目录" else "备份目录已连接", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onPickDir) { Text("选择目录") }
                }
            }
        }
    }
}

@Composable
internal fun BackupRuleNotes() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "导入前保存本机资料并纳入三代轮换，保存失败会停止恢复。",
            "浏览记录按事件身份合并，同一备份重复导入不会翻倍。",
            "先同步媒体并扫描，再检查匹配率和迁移提示。",
            "统一目录备份附完整性摘要，手动与自动合计保留最新、上次、上上次。",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun formatTimestamp(millis: Long, emptyText: String): String =
    if (millis <= 0L) emptyText else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
