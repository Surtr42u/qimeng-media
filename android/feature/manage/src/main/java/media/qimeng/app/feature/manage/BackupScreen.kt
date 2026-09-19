package media.qimeng.app.feature.manage

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（页标题取 hub 行名；任务R 2026-09-19 四入口重做：对象恒=当前连接的服务端） */
private const val SCREEN_TITLE = "备份导入导出"

// ---------- 四入口卡文案（任务R 2026-09-19 拍板：导入/导出对象恒=当前连接的服务端，
// 连 NAS 操作 NAS 数据、连手机本地操作手机本地数据；导入=合并进当前连接的端） ----------
private const val ROW_EXPORT_TITLE = "导出备份"
private const val ROW_EXPORT_SUBTITLE =
    "导出当前连接的服务端的全量数据（文件清单/作者/标签/统计/收藏点赞）为 qimeng_backup.json"
private const val ROW_EXPORT_ACTION = "导出"
private const val ROW_EXPORT_ACTION_BUSY = "导出中…"
private const val ROW_IMPORT_TITLE = "导入备份"
private const val ROW_IMPORT_SUBTITLE =
    "选择 qimeng_backup.json，校验确认后幂等合并导入当前连接的服务端（不删除现有数据）"
private const val ROW_IMPORT_ACTION = "选择文件"
private const val ROW_IMPORT_ACTION_BUSY = "导入中…"

// ---------- 浏览数据同步卡（独立功能不动，2026-09-15 批自我的页迁入；留页面原位） ----------
private const val ROW_SYNC_TITLE = "同步浏览数据"
private const val SYNC_PENDING_TEMPLATE = "待上传 %d 条"
private const val SYNC_PENDING_UNKNOWN = "待上传 —"
private const val SYNC_SUBTITLE_SUFFIX = "断网先存本机联网自动补传"
private const val ROW_SYNC_ACTION = "立即同步"
private const val ROW_SYNC_ACTION_BUSY = "同步中…"

// ---------- 跨端同步卡（任务R 2026-09-19：源端一键「同步到另一端」=既有导出+暂存；
// 目标端暂存卡见 BackupCards.StagedSyncCard；暂存只保留最新一份覆盖写语义不变） ----------
private const val ROW_STAGE_TITLE = "跨端同步"
private const val ROW_STAGE_SUBTITLE =
    "一键暂存当前连接端的全量数据（免导出文件）；切换登录另一端后回来一键导入合并（暂存只保留最新一份）"
private const val ROW_STAGE_ACTION = "同步到另一端"
private const val ROW_STAGE_ACTION_BUSY = "暂存中…"

private const val DIALOG_CONFIRM = "导入恢复"
private const val DIALOG_CANCEL = "取消"

/** 确认弹窗标题（Web L313 逐字「导入备份「file」？」，%s=文件名） */
private const val DIALOG_TITLE_TEMPLATE = "导入备份「%s」？"
private const val WARNINGS_HEAD_TEMPLATE = "另有 %d 条迁移提示"

/** 16dp：内容水平内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/** SAF 选文件的 MIME 过滤（Web input accept=".json,application/json" 对齐；内容校验在 BackupValidator） */
private val JSON_MIME_TYPES = arrayOf("application/json")

/** 导出文件预填名（旧版固定约定 qimeng_backup.json，DATA_MIGRATION_SPEC §2；Web BACKUP_FILE_NAME 同源） */
private const val EXPORT_FILE_NAME = "qimeng_backup.json"

/**
 * 备份页（任务R 2026-09-19 四入口重做）：导出备份/导入备份/跨端同步/自动备份四入口
 * 各自成卡，导入/导出对象恒=当前连接的服务端；有暂存时顶部显著展示暂存卡（目标端
 * 一键导入合并）。浏览数据同步是独立功能留页面原位。业务全在 ViewModel（铁律 7）；
 * SAF 文件读写/目录授权是屏幕层平台胶水（SettingsScreen 先例口径：VM 出数据、屏幕层落盘）。
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

    // 自动备份目录（OpenDocumentTree；UploadScreen 即用即弃口径的反面——自动备份要跨进程
    // 存活，选完立即 takePersistableUriPermission 持久化授权）；授权失败不落 prefs
    // （存一个写不了的目录只会让备份必败）
    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            // WrongConstant 假阳性：TREE_PERMISSION_FLAGS 是 const val 的 FLAG_READ or FLAG_WRITE
            // 双 flag 合法组合，AGP 9.3 lint 无法跨模块内联 Kotlin or 表达式常量（任务P P1 记档）
            @SuppressLint("WrongConstant")
            context.contentResolver.takePersistableUriPermission(uri, AutoBackupRunner.TREE_PERMISSION_FLAGS)
        }.onSuccess { viewModel.onAutoBackupDirPicked(uri.toString()) }
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
            // 顶部暂存卡（任务R：跨端同步目标端显著位，先于各入口卡）
            state.staged?.let { staged ->
                StagedSyncCard(
                    meta = staged,
                    mediaFiles = state.stagedMediaFiles,
                    importing = state.importing,
                    onImport = viewModel::importStaged,
                )
            }

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
            state.eventSyncNote?.let { message ->
                StatusMessageCard(text = message, container = MaterialTheme.colorScheme.tertiaryContainer) {
                    viewModel.dismissEventSyncNote()
                }
            }
            if (state.warnings.isNotEmpty()) {
                WarningsCard(warnings = state.warnings, onDismiss = viewModel::dismissWarnings)
            }

            // 入口 1：导出备份（对象=当前连接的服务端；SAF 写文件既有链路）
            BackupActionCard(title = ROW_EXPORT_TITLE, subtitle = ROW_EXPORT_SUBTITLE) {
                Button(
                    onClick = { exportLauncher.launch(EXPORT_FILE_NAME) },
                    enabled = !state.exporting,
                ) {
                    Text(if (state.exporting) ROW_EXPORT_ACTION_BUSY else ROW_EXPORT_ACTION)
                }
            }

            // 入口 2：导入备份（SAF 选文件→Validator→确认弹窗→幂等导入当前连接的端）
            BackupActionCard(title = ROW_IMPORT_TITLE, subtitle = ROW_IMPORT_SUBTITLE) {
                Button(
                    onClick = { importLauncher.launch(JSON_MIME_TYPES) },
                    enabled = !state.importing,
                ) {
                    Text(if (state.importing) ROW_IMPORT_ACTION_BUSY else ROW_IMPORT_ACTION)
                }
            }

            // 浏览数据同步（独立功能不动，留页面原位——原主卡内第三行）
            BackupActionCard(
                title = ROW_SYNC_TITLE,
                subtitle = listOf(
                    when (val pending = state.pendingEvents) {
                        null -> SYNC_PENDING_UNKNOWN
                        else -> SYNC_PENDING_TEMPLATE.format(pending)
                    },
                    SYNC_SUBTITLE_SUFFIX,
                ).joinToString(" · "),
            ) {
                TextButton(enabled = !state.eventSyncing, onClick = viewModel::syncEventsNow) {
                    Text(if (state.eventSyncing) ROW_SYNC_ACTION_BUSY else ROW_SYNC_ACTION)
                }
            }

            // 入口 3：跨端同步（源端一键暂存；目标端走顶部暂存卡导入，旧散装按钮已收敛）
            BackupActionCard(title = ROW_STAGE_TITLE, subtitle = ROW_STAGE_SUBTITLE) {
                Button(
                    onClick = viewModel::stageForSync,
                    enabled = !state.stagingBusy,
                ) {
                    Text(if (state.stagingBusy) ROW_STAGE_ACTION_BUSY else ROW_STAGE_ACTION)
                }
            }

            BackupRuleNotes()

            // 入口 4：自动备份（开关/目录/上次运行/立即备份，既有保留）
            AutoBackupCard(
                enabled = state.autoBackupEnabled,
                dirUri = state.autoBackupDirUri,
                lastRunMillis = state.autoBackupLastRunMillis,
                busy = state.autoBackupBusy,
                onToggle = viewModel::setAutoBackupEnabled,
                onPickDir = { dirPickerLauncher.launch(null) },
                onRunNow = viewModel::writeAutoBackupNow,
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }

    // 二次确认弹窗（旧版「检测到备份数据…是否导入恢复」语义；Web ConfirmDialog 同文案。
    // 文件导入与暂存导入共用——importStaged 走与 onFilePicked 同一条校验链，单源口径）
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
