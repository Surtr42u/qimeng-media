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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import media.qimeng.app.core.ui.icon.FolderManagedIcon
import media.qimeng.app.core.ui.icon.TuneSlidersIcon
import media.qimeng.app.core.ui.icon.ArchiveBoxIcon
import media.qimeng.app.core.ui.icon.CloudUploadIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
        backupState.importing || backupState.exporting || backupState.pendingImport != null || state.pendingDocument != null
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var directoryDetails by rememberSaveable { mutableStateOf(false) }
    var archiveDetails by rememberSaveable { mutableStateOf(false) }
    val chooseRoot: () -> Unit = {
        if (state.granted) viewModel.browse()
        else context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()))
    }
    Column(Modifier.fillMaxSize().softDaylightBackground()) {
        QimengTopBar(title = "资料维护", onBack = onBack)
        QimengSettingsCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(FolderManagedIcon, null, Modifier.padding(10.dp).size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(state.root?.substringAfterLast('/') ?: "集中管理你的资料", style = MaterialTheme.typography.titleSmall)
                        Text(if (state.root == null) "一个目录，保存备份、作者与归档" else "维护目录已连接", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (state.root != null) IconButton(enabled = !blocked, onClick = { directoryDetails = !directoryDetails }) {
                        Icon(TuneSlidersIcon, "目录选项")
                    }
                }
                if (state.root == null) Button(enabled = !blocked, onClick = chooseRoot, shape = RoundedCornerShape(12.dp)) { Text("选择维护目录") }
                if (directoryDetails && state.root != null) {
                    Text(state.root.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(enabled = !blocked, onClick = chooseRoot) { Text("更换目录") }
                        TextButton(enabled = !blocked, onClick = viewModel::refresh) { Text("刷新目录") }
                    }
                }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("备份恢复", "作者主表", "原件归档", "设备领取").forEachIndexed { index, title ->
                    FilterChip(selected = tab == index, enabled = !blocked, onClick = { tab = index },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(14.dp),
                        label = { Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> BackupScreen(onBack, viewModel = backup, modifier = Modifier.weight(1f), embedded = true,
                    onUnifiedExport = if (state.root != null) viewModel::export else null,
                    onPickUnifiedRoot = viewModel::browse, externalBusy = state.busy)
                1 -> AuthorTxtImportScreen(onBack, modifier = Modifier.weight(1f), embedded = true, viewModel = authors,
                    externalBusy = state.busy, documents = state.documents, onImportDocument = viewModel::prepareDocument)
                2 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QimengSettingsCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(ArchiveBoxIcon, null, Modifier.size(24.dp))
                                Text("上传后的原件", style = MaterialTheme.typography.titleMedium)
                            }
                            Text("上传成功后按媒体库分目录保存，之后可按库再次上传。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("保存位置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (state.root != null) "维护目录 / 上传归档" else if (state.archivePath != null) "当前归档目录" else "尚未设置", style = MaterialTheme.typography.bodyMedium)
                                    if (archiveDetails) Text(state.archivePath.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Button(enabled = !blocked, onClick = onOpenUpload, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Icon(CloudUploadIcon, null, Modifier.padding(end = 8.dp).size(18.dp))
                                Text("前往上传文件")
                            }
                            if (state.archivePath != null) TextButton(onClick = { archiveDetails = !archiveDetails }) {
                                Text(if (archiveDetails) "收起完整路径" else "查看完整路径")
                            }
                        }
                    }
                }
                else -> DeviceApplyScreen(Modifier.weight(1f))
            }
        }
    }
    state.pendingDocument?.let { document ->
        AlertDialog(onDismissRequest = viewModel::dismissDocument, title = { Text("导入作者TXT？") },
            text = { Text("${document.filename}\n将按全部已存TXT重建常规作者关联，请先同步媒体并完成扫描。") },
            confirmButton = { TextButton(onClick = { authors.onFilePicked(document.filename, document.content); viewModel.dismissDocument() }) { Text("导入") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDocument) { Text("取消") } })
    }
}
