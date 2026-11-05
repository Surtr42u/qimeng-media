package media.qimeng.app.feature.manage

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import media.qimeng.app.core.data.authorimport.AuthorTxtDocument
import media.qimeng.app.core.ui.component.DirectoryBrowserCard
import media.qimeng.app.core.ui.component.DirectoryBrowserEntry
import media.qimeng.app.core.ui.component.QimengSettingsCard
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.softDaylightBackground

/** Task tabs share a single root folder while retaining legacy import confirmations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(
    onBack: () -> Unit,
    onOpenUpload: () -> Unit,
    initialTab: Int = 0,
    viewModel: MaintenanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val context = LocalContext.current
    val authors: AuthorTxtImportViewModel = hiltViewModel()
    val authorState by authors.uiState.collectAsStateWithLifecycle()
    val backup: BackupViewModel = hiltViewModel()
    val backupState by backup.uiState.collectAsStateWithLifecycle()
    val blocked = state.busy || authorState.importing || authorState.removing || authorState.rebuilding || authorState.mirrorSaving ||
        backupState.importing || backupState.exporting || backupState.pendingImport != null
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var pending by remember { mutableStateOf<AuthorTxtDocument?>(null) }
    Column(Modifier.fillMaxSize().softDaylightBackground()) {
        QimengTopBar(title = "资料备份与归档", onBack = onBack)
        QimengSettingsCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("维护根目录", style = MaterialTheme.typography.titleMedium)
                Text(state.root ?: "选一次目录，统一管理作者资料、备份和上传归档", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = !blocked, onClick = {
                        if (state.granted) viewModel.browse()
                        else context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()))
                    }) { Text(if (state.root == null) "选择根目录" else "更换目录") }
                    TextButton(enabled = !blocked, onClick = viewModel::refresh) { Text("刷新") }
                    TextButton(enabled = !blocked && state.root != null, onClick = viewModel::export) {
                        Text(if (state.busy) "处理中…" else "更新全部归档")
                    }
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (state.browsing) {
            Column(Modifier.weight(1f)) {
                TextButton(enabled = !blocked, onClick = viewModel::closeBrowser) { Text("取消选择") }
                androidx.compose.foundation.lazy.LazyColumn {
                    item {
                        DirectoryBrowserCard(
                            title = "选择维护根目录",
                            currentPath = state.browsingPath,
                            storageRoot = state.storageRoot,
                            entries = if (state.busy) emptyList() else state.entries.map { DirectoryBrowserEntry(it.name, it.path) },
                            onEnter = viewModel::enter, onGoUp = viewModel::goUp,
                            loading = state.busy,
                            onSelectCurrent = if (state.busy) null else viewModel::selectCurrent,
                            selectCurrentText = "使用此目录并生成资料",
                        )
                    }
                }
            }
        } else {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("备份恢复", "作者TXT", "上传归档").forEachIndexed { index, title ->
                    Tab(selected = tab == index, enabled = !blocked, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> BackupScreen(onBack, viewModel = backup, modifier = Modifier.weight(1f), embedded = true,
                    onUnifiedExport = if (state.root != null) viewModel::export else null,
                    onPickUnifiedRoot = viewModel::browse, externalBusy = state.busy)
                1 -> Column(Modifier.weight(1f)) {
                    if (state.documents.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp)) {
                        state.documents.forEach { document ->
                            TextButton(enabled = !blocked, onClick = { pending = document }) { Text("导入 ${document.filename}") }
                        }
                    }
                    AuthorTxtImportScreen(onBack, modifier = Modifier.weight(1f), embedded = true, viewModel = authors, externalBusy = state.busy)
                }
                else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("上传归档", style = MaterialTheme.typography.titleMedium)
                    Text(state.archivePath ?: "选择维护根目录后自动设置上传归档位置。")
                    Text("上传成功的原件沿用现有流程按库归档，可在上传页查看和按库名上传。")
                    TextButton(onClick = onOpenUpload) { Text("前往上传文件") }
                }
            }
        }
    }
    pending?.let { document ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("导入作者TXT？") },
            text = { Text("${document.filename}\n将按全部已存TXT重建常规作者关联，请先同步媒体并完成扫描。") },
            confirmButton = { TextButton(onClick = { authors.onFilePicked(document.filename, document.content); pending = null }) { Text("导入") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    }
}
