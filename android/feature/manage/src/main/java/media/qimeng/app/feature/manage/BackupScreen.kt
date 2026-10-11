package media.qimeng.app.feature.manage

import android.annotation.SuppressLint
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.data.backup.BackupValidator
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（页标题取 hub 行名） */
private const val SCREEN_TITLE = "备份导入导出"

// ---------- 浏览数据同步卡文案（独立功能不动，2026-09-15 批自我的页迁入；留页面原位） ----------
private const val ROW_SYNC_TITLE = "同步浏览数据"
private const val SYNC_PENDING_TEMPLATE = "待上传 %d 条"
private const val SYNC_PENDING_UNKNOWN = "待上传 —"
private const val SYNC_SUBTITLE_SUFFIX = "断网先存本机联网自动补传"
private const val ROW_SYNC_ACTION = "立即同步"
private const val ROW_SYNC_ACTION_BUSY = "同步中…"

private const val DIALOG_CONFIRM = "导入恢复"
private const val DIALOG_CANCEL = "取消"

/** 确认弹窗标题（Web L313 逐字「导入备份「file」？」，%s=文件名） */
private const val DIALOG_TITLE_TEMPLATE = "导入备份「%s」？"
private const val WARNINGS_HEAD_TEMPLATE = "另有 %d 条迁移提示"

/** 16dp：内容水平内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/**
 * 备份页（2026-09-19 任务S 两卡收敛）：卡1「导入导出」（直接读写备份目录固定名文件，
 * 不弹 SAF 选择器，对象恒=当前连接的服务端）+ 卡2「自动备份」（开关/目录/上次备份时间），
 * 浏览数据同步是独立功能留页面原位。任务R 的跨端同步暂存机制（暂存卡/跨端同步卡）随本批
 * 退役——备份目录直读直写替代。业务全在 ViewModel（铁律 7）；SAF 目录授权是屏幕层唯一
 * 平台胶水（SettingsScreen 先例口径：选完 takePersistableUriPermission 持久化授权）。
 * 2026-10-04 词表合并同步批（ADR-0035）：追加「词表合并同步」卡（用户拍板入口迁入备份页，
 * 与浏览数据同步并排=数据流转集中一页；卡自带 VocabularySyncViewModel，零业务规则）。
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 词表合并同步卡（ADR-0035）：卡自带 VM，与页级 BackupViewModel 横幅互不干扰
    val vocabularyViewModel: VocabularySyncViewModel = hiltViewModel()
    val vocabularyState by vocabularyViewModel.uiState.collectAsStateWithLifecycle()

    // 自动备份目录（OpenDocumentTree；导入导出与自动备份共用同目录——选一次即两者就绪）。
    // 选完立即 takePersistableUriPermission 持久化授权（跨进程存活）；授权失败不落 prefs
    // （存一个写不了的目录只会让备份必败），并出横幅（从前静默吞掉＝"没权限"无从区分，见下方注释）。
    // 起始位置必须由 App 给：平台禁止选受限目录（内部存储根/Download），选择器停在那里会成死路
    // ——「使用此文件夹」置灰且列表不展开（2026-10-11 用户报障），故顶到当前备份目录开局。
    val pickerStartUri = remember(state.autoBackupDirUri) {
        backupDirPickerDocumentUri(state.autoBackupDirUri)
    }
    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            // WrongConstant 假阳性：TREE_PERMISSION_FLAGS 是 const val 的 FLAG_READ or FLAG_WRITE
            // 双 flag 合法组合，AGP 9.3 lint 无法跨模块内联 Kotlin or 表达式常量（任务P P1 记档）
            @SuppressLint("WrongConstant")
            context.contentResolver.takePersistableUriPermission(uri, AutoBackupRunner.TREE_PERMISSION_FLAGS)
        }.onSuccess {
            viewModel.onAutoBackupDirPicked(uri.toString())
        }.onFailure { cause ->
            viewModel.onAutoBackupDirGrantFailed(cause)
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
            state.eventSyncNote?.let { message ->
                StatusMessageCard(text = message, container = MaterialTheme.colorScheme.tertiaryContainer) {
                    viewModel.dismissEventSyncNote()
                }
            }
            if (state.warnings.isNotEmpty()) {
                WarningsCard(warnings = state.warnings, onDismiss = viewModel::dismissWarnings)
            }

            // 卡1：导入导出（直读直写备份目录 qimeng_backup.json；确认弹窗见页尾）
            ImportExportCard(
                dirSet = state.autoBackupDirUri != null,
                fileStatus = state.backupFileStatus,
                exporting = state.exporting,
                importing = state.importing,
                onExport = viewModel::exportToBackupDir,
                onImport = viewModel::importFromBackupDir,
            )

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

            // 词表合并同步（ADR-0035）：两端词表只增不删增量合并，一键收敛到同一并集
            VocabularyMergeSyncCard(
                syncing = vocabularyState.syncing,
                errorMessage = vocabularyState.errorMessage,
                noticeMessage = vocabularyState.noticeMessage,
                onSync = vocabularyViewModel::sync,
                onDismissError = vocabularyViewModel::dismissError,
                onDismissNotice = vocabularyViewModel::dismissNotice,
            )

            BackupRuleNotes()

            // 卡2：自动备份（开关/目录/上次备份时间；「立即备份」随任务S 收敛删除）
            AutoBackupCard(
                enabled = state.autoBackupEnabled,
                dirUri = state.autoBackupDirUri,
                lastRunMillis = state.autoBackupLastRunMillis,
                onToggle = viewModel::setAutoBackupEnabled,
                onPickDir = { dirPickerLauncher.launch(pickerStartUri) },
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }

    // 二次确认弹窗（旧版「检测到备份数据…是否导入恢复」语义；Web ConfirmDialog 同文案。
    // 导入数据源=备份目录固定名文件，走与文件导入同一条校验链，单源口径）
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

// ---------- SAF 目录选择起始位置（2026-10-11 用户报障修复：选择器停在平台受限目录＝死路） ----------

/** 内部存储 DocumentsProvider 授权名（AOSP 固定值；未设备份目录时的兜底目录挂在它下面） */
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** 兜底起始文档 ID＝内部存储 /Documents（标准公共目录，且不属平台受限目录） */
private const val DEFAULT_PICKER_DOC_ID = "primary:Documents"

/**
 * 目录选择器起始位置的「文档 URI」：优先当前备份目录（树 URI → 该树根文档 URI），不可用时退回
 * 内部存储的 Documents。
 *
 * 为什么显式给起始位置（2026-10-11 用户报障）：Android 11+ 起平台禁止 ACTION_OPEN_DOCUMENT_TREE
 * 授权受限目录（内部存储根目录 / Download / Android/data / Android/obb），选择器停在那里时
 * 「使用此文件夹」置灰、用户点不动；而此前 launch(null) 把起始位置完全交给系统选择器自己的记忆，
 * 很容易开局就落在那里。顶到当前备份目录开局即可避开（androidx OpenDocumentTree 契约把入参 Uri
 * 写进 EXTRA_INITIAL_URI，见 activity-1.13.0 字节码实证）。
 *
 * 附记（诚实记档，CHANGELOG 第五百三十笔修正条）：当日同一批报障中还有一次「子目录也被判无法使用、
 * 列表空白」，用户重启后自愈——那是系统侧选择器/存储提供者状态损坏，与本处的受限目录规则是两件事，
 * 本函数的起始位置修复对两种情况都成立（不落在受限目录、也不依赖选择器记忆）。
 */
private fun backupDirPickerDocumentUri(storedDirUri: String?): Uri? {
    val documentUri = storedDirUri?.let {
        runCatching {
            val tree = it.toUri()
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull()
    }
    if (documentUri != null) return documentUri
    return runCatching {
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, DEFAULT_PICKER_DOC_ID)
    }.getOrNull()
}
