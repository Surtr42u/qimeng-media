package media.qimeng.app.feature.manage

import android.content.Context
import android.annotation.SuppressLint
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（页标题取 hub 行名；行文案 2026-09-16 用户反馈改版自拟，语义对齐 Web BackupCard） */
private const val SCREEN_TITLE = "备份导入导出"

// ---------- 主卡三行（2026-09-16 用户反馈：导入/导出/同步统一成 Card+行 形式，
// 参考 LibraryManageScreen 卡视觉；原 ExportRow/导入拖放区/EventSyncCard 三种形态退役） ----------
private const val ROW_IMPORT_TITLE = "导入备份"
private const val ROW_IMPORT_SUBTITLE =
    "选择旧版导出的 qimeng_backup.json，确认后按唯一键合并导入（幂等不删除现有数据）"
private const val ROW_IMPORT_ACTION = "选择文件"
private const val ROW_IMPORT_ACTION_BUSY = "导入中…"
private const val ROW_EXPORT_TITLE = "导出备份"
private const val ROW_EXPORT_SUBTITLE = "全量导出当前库（文件清单/作者/标签/统计/收藏点赞）"
private const val ROW_EXPORT_ACTION = "导出"
private const val ROW_EXPORT_ACTION_BUSY = "导出中…"
private const val ROW_SYNC_TITLE = "同步浏览数据"
private const val SYNC_PENDING_TEMPLATE = "待上传 %d 条"
private const val SYNC_PENDING_UNKNOWN = "待上传 —"
private const val SYNC_SUBTITLE_SUFFIX = "断网先存本机联网自动补传"
private const val ROW_SYNC_ACTION = "立即同步"
private const val ROW_SYNC_ACTION_BUSY = "同步中…"

// ---------- 跨端同步行（2026-09-18 用户拍板：本机⇄服务器同步免来回导文件，
// App 内暂存中转；导入走既有文件导入的校验→确认→幂等导入链路） ----------
private const val ROW_STAGE_TITLE = "跨端同步"
private const val ROW_STAGE_SUBTITLE_EMPTY =
    "连着哪端就先「暂存当前库」；切换登录另一端后「导入暂存」合并（免导出文件）"
private const val STAGED_SUBTITLE_TEMPLATE = "已暂存：来自 %s · %s · %d KB；切到另一端登录后点「导入暂存」"
private const val ROW_STAGE_ACTION = "暂存当前库"
private const val ROW_STAGE_ACTION_BUSY = "暂存中…"
private const val ROW_STAGE_IMPORT = "导入暂存"
private const val ROW_STAGE_IMPORT_BUSY = "导入中…"

private const val DIALOG_CONFIRM = "导入恢复"
private const val DIALOG_CANCEL = "取消"

/** 确认弹窗标题（Web L313 逐字「导入备份「file」？」，%s=文件名） */
private const val DIALOG_TITLE_TEMPLATE = "导入备份「%s」？"
private const val WARNINGS_HEAD_TEMPLATE = "另有 %d 条迁移提示"

// ---------- 自动备份卡（2026-09-16 用户反馈：开关 + SAF 目录 + 立即备份 + 上次备份时间） ----------
private const val AUTO_TITLE = "自动备份"
private const val AUTO_SUBTITLE = "每日一次，打开应用时写入所选目录"
private const val AUTO_DIR_LABEL = "目录"
private const val AUTO_DIR_SET = "已选择目录"
private const val AUTO_DIR_UNSET = "未选择"
private const val AUTO_DIR_PICK = "选择目录"
private const val AUTO_LAST_LABEL = "上次备份"
private const val AUTO_LAST_NEVER = "未运行"
private const val AUTO_RUN_NOW = "立即备份"
private const val AUTO_RUN_BUSY = "备份中…"

/** 规则说明三条（Web L297-299 逐字；2026-09-16 用户反馈缩小置于主卡下方；2026-09-18 增跨端同步口径） */
private val RULE_LINES = listOf(
    "· 导入幂等：同一备份重复导入不翻倍（作者/标签/关联按唯一键合并）",
    "· 统计/历史按事件回放重建；mediaFiles 仅在文件名能匹配到库内文件时建立关联",
    "· 扫描源等设备本机段不迁移，导入后请核对库注册与根路径",
    "· 跨端同步：标签/作者/收藏按最新合并；浏览统计按批次回放——重新暂存（新批次）后再导入会重复累计浏览统计",
)

/** 16dp：内容水平内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/** 8dp：行卡内元素纵向节奏（LibraryManageScreen RowInnerSpacing 同值，本地自持不跨文件引用） */
private val RowInnerSpacing = 8.dp

/** 12dp：行卡四向内边距（LibraryManageScreen CardInnerPadding 同值，本地自持不跨文件引用） */
private val CardInnerPadding = 12.dp

/** 2dp：行标题与副标题小字间距（同 Block 两行文本的紧凑节奏） */
private val SubtitleTopSpacing = 2.dp

/** SAF 选文件的 MIME 过滤（Web input accept=".json,application/json" 对齐；内容校验在 BackupValidator） */
private val JSON_MIME_TYPES = arrayOf("application/json")

/** 导出文件预填名（旧版固定约定 qimeng_backup.json，DATA_MIGRATION_SPEC §2；Web BACKUP_FILE_NAME 同源） */
private const val EXPORT_FILE_NAME = "qimeng_backup.json"

/** KB 换算分母（暂存行体积展示与 VM NOTICE_EXPORT 同口径） */
private const val BYTES_PER_KB = 1024.0

/**
 * 备份导入/导出页（U10-6b；2026-09-16 用户反馈改版）：主卡三行（导入/导出/同步）统一
 * Card+行 形式（视觉基准 = LibraryManageScreen 库行卡）+ 自动备份卡（开关/目录/立即备份/
 * 上次备份时间）+ 规则说明三条缩小置主卡下方。「导出未上传」按钮随改版退役（联网自动
 * 补传口径下手动导出场景不复存在）。业务全在 ViewModel（铁律 7）；SAF 文件读写/目录
 * 授权是屏幕层平台胶水（SettingsScreen 先例口径：VM 出数据、屏幕层落盘）。
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

            // 主卡：导入/导出/同步三行（2026-09-16 用户反馈统一形式）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(CardInnerPadding),
                    verticalArrangement = Arrangement.spacedBy(RowInnerSpacing),
                ) {
                    ActionRow(title = ROW_IMPORT_TITLE, subtitle = ROW_IMPORT_SUBTITLE) {
                        Button(
                            onClick = { importLauncher.launch(JSON_MIME_TYPES) },
                            enabled = !state.importing,
                        ) {
                            Text(if (state.importing) ROW_IMPORT_ACTION_BUSY else ROW_IMPORT_ACTION)
                        }
                    }
                    ActionRow(title = ROW_EXPORT_TITLE, subtitle = ROW_EXPORT_SUBTITLE) {
                        Button(
                            onClick = { exportLauncher.launch(EXPORT_FILE_NAME) },
                            enabled = !state.exporting,
                        ) {
                            Text(if (state.exporting) ROW_EXPORT_ACTION_BUSY else ROW_EXPORT_ACTION)
                        }
                    }
                    ActionRow(
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
                    // 跨端同步（2026-09-18）：暂存=导出进 App 内部存储（免 SAF 挑文件）；
                    // 导入暂存=与「选择文件」同一条校验→确认弹窗→幂等导入链路（VM 单源）
                    ActionRow(
                        title = ROW_STAGE_TITLE,
                        subtitle = when (val staged = state.staged) {
                            null -> ROW_STAGE_SUBTITLE_EMPTY
                            else -> STAGED_SUBTITLE_TEMPLATE.format(
                                staged.sourceUrl,
                                formatLastRun(staged.stagedAtMillis),
                                (staged.sizeBytes / BYTES_PER_KB).roundToInt(),
                            )
                        },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXS)) {
                            TextButton(enabled = !state.stagingBusy, onClick = viewModel::stageForSync) {
                                Text(if (state.stagingBusy) ROW_STAGE_ACTION_BUSY else ROW_STAGE_ACTION)
                            }
                            TextButton(
                                enabled = state.staged != null && !state.importing,
                                onClick = viewModel::importStaged,
                            ) {
                                Text(if (state.importing) ROW_STAGE_IMPORT_BUSY else ROW_STAGE_IMPORT)
                            }
                        }
                    }
                }
            }

            RuleNotes()

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

/**
 * 主卡动作行（2026-09-16 用户反馈统一形式）：左侧标题+副标题小字（单块两行语义），
 * 右侧动作钮。视觉基准 = LibraryManageScreen 库行卡的标题+小字节奏。
 */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    action: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowInnerSpacing),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SubtitleTopSpacing),
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

/**
 * 自动备份卡（2026-09-16 用户反馈）：行1 开关 + 行2 目录（选择/已选择）+ 行3 上次备份
 * 时间 + 立即备份。触发判定与写盘执行体在 core:data AutoBackupRunner（每日一次、
 * 打开应用时写入所选目录），本卡只做状态展示与手动触发（铁律 7）。
 */
@Composable
private fun AutoBackupCard(
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
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(RowInnerSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SubtitleTopSpacing),
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
                horizontalArrangement = Arrangement.spacedBy(RowInnerSpacing),
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
                horizontalArrangement = Arrangement.spacedBy(RowInnerSpacing),
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

/** 上次备份时间格式化（0=从未运行；SimpleDateFormat 非线程安全，每次 new 不共享实例） */
private fun formatLastRun(millis: Long): String =
    if (millis <= 0L) {
        AUTO_LAST_NEVER
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
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
