package media.qimeng.app.feature.manage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（对齐 Web LibraryManagePage.tsx BackupCard L245-324 逐字；页标题取 hub 行名） */
private const val SCREEN_TITLE = "备份导入导出"
private const val CARD_TITLE = "备份导入 / 导出"
private const val CARD_NOTE = "旧版迁移格式 qimeng_backup.json"
private const val EXPORT_BUTTON = "导出全量备份"
private const val EXPORT_BUTTON_BUSY = "导出中…"
private const val EXPORT_NOTE = "当前库全量（文件清单/作者/标签/统计/收藏点赞），可回灌旧版 App 或其他实例"
private const val DROP_TITLE = "选择备份文件导入恢复"
private const val DROP_HINT = "点击选择旧版导出的 qimeng_backup.json，确认后按唯一键合并导入（不删除现有数据）"
private const val DIALOG_CONFIRM = "导入恢复"
private const val DIALOG_CANCEL = "取消"

/** 确认弹窗标题（Web L313 逐字「导入备份「file」？」，%s=文件名） */
private const val DIALOG_TITLE_TEMPLATE = "导入备份「%s」？"
private const val WARNINGS_HEAD_TEMPLATE = "另有 %d 条迁移提示"

/** 规则说明三条（Web L297-299 逐字） */
private val RULE_LINES = listOf(
    "· 导入幂等：同一备份重复导入不翻倍（作者/标签/关联按唯一键合并）",
    "· 统计/历史按事件回放重建；mediaFiles 仅在文件名能匹配到库内文件时建立关联",
    "· 扫描源等设备本机段不迁移，导入后请核对库注册与根路径",
)

/** 16dp：内容水平内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/** SAF 选文件的 MIME 过滤（Web input accept=".json,application/json" 对齐；内容校验在 BackupValidator） */
private val JSON_MIME_TYPES = arrayOf("application/json")

/** 导出文件预填名（旧版固定约定 qimeng_backup.json，DATA_MIGRATION_SPEC §2；Web BACKUP_FILE_NAME 同源） */
private const val EXPORT_FILE_NAME = "qimeng_backup.json"

/**
 * 备份导入/导出页（U10-6b）：导出钮 + 导入区卡化（点击=选文件）→ 前置校验 →
 * AlertDialog 二次确认 → 幂等导入。信息架构基准 = Web BackupCard，视觉/交互基准 =
 * App 库管理子页。业务全在 ViewModel（铁律 7）；SAF 读写是屏幕层平台胶水
 * （SettingsScreen 先例口径：VM 出数据、屏幕层落盘）。
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 导出（CreateDocument 预填 qimeng_backup.json，SettingsScreen L287-297 既有先例）：
    // uri=null=用户取消静默；拿到 uri 先出内容（VM 防重+序列化）再 SAF 落盘
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val export = viewModel.prepareExport() ?: return@launch
            val written = writeToUri(context, uri, export.json)
            viewModel.onExportWritten(if (written) export.sizeBytes else null)
        }
    }

    // 导入（OpenDocument）：读字节后进 VM 前置校验（超限/坏 JSON/格式不符不出网）；
    // uri=null=用户取消静默；读失败同作者 TXT 页口径出错误横幅
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readBytes(context, uri)
            if (bytes == null) {
                viewModel.onReadFailed()
            } else {
                val fileName = resolveDisplayName(context, uri) ?: uri.lastPathSegment ?: EXPORT_FILE_NAME
                viewModel.onFilePicked(fileName, bytes)
            }
        }
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
            if (state.warnings.isNotEmpty()) {
                WarningsCard(warnings = state.warnings, onDismiss = viewModel::dismissWarnings)
            }

            CardHead()
            ExportRow(exporting = state.exporting, onExport = { exportLauncher.launch(EXPORT_FILE_NAME) })
            ImportDropZone(
                title = DROP_TITLE,
                hint = DROP_HINT,
                importing = state.importing,
                onPick = { importLauncher.launch(JSON_MIME_TYPES) },
            )
            RuleNotes()

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }

    // 二次确认弹窗（旧版「检测到备份数据…是否导入恢复」语义；Web ConfirmDialog 同文案）
    state.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImport,
            title = { Text(DIALOG_TITLE_TEMPLATE.format(pending.summary.fileName)) },
            text = { Text(BackupValidator.summaryText(pending.summary)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) { Text(DIALOG_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissImport) { Text(DIALOG_CANCEL) }
            },
        )
    }
}

/** 卡头：Web rank-head（标题 + rank-note）同位 */
@Composable
private fun CardHead() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = QimengDimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(CARD_TITLE, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = CARD_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 导出行：导出钮（进行中禁用防重）+ 全量范围说明（Web 导出行同位逐字） */
@Composable
private fun ExportRow(exporting: Boolean, onExport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        Button(onClick = onExport, enabled = !exporting) {
            Text(if (exporting) EXPORT_BUTTON_BUSY else EXPORT_BUTTON)
        }
        Text(
            text = EXPORT_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 规则说明三条（Web rank-note 小字列表同位） */
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

/** 迁移提示卡（Web「另有 N 条迁移提示」toast.info + description 逐条展示的 App 同语义件） */
@Composable
private fun WarningsCard(warnings: List<String>, onDismiss: () -> Unit) {
    StatusMessageCard(
        text = WARNINGS_HEAD_TEMPLATE.format(warnings.size) + "\n" + warnings.joinToString("\n"),
        container = MaterialTheme.colorScheme.secondaryContainer,
        onDismiss = onDismiss,
    )
}

/** SAF 显示名解析（AuthorTxtImportScreen 同款平台胶水；查询失败返回 null 让调用方兜底） */
private fun resolveDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

/** SAF 读文件字节（屏幕层平台胶水；失败返回 null，由 VM 统一出错误横幅） */
private suspend fun readBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
    }.getOrNull()
}

/** SAF 写导出内容（UTF-8 字节；写失败/中断返回 false，由 VM 统一出错误横幅） */
private suspend fun writeToUri(context: Context, uri: Uri, json: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("openOutputStream 返回 null")
    }.isSuccess
}
