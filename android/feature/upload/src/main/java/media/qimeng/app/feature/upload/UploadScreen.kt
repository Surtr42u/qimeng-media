package media.qimeng.app.feature.upload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar

/**
 * 上传主通道页（M4-5）：选库 → 选目录（可新建）→ 选文件（SAF/分享接收）→ 串行队列进度。
 * 入口：①系统分享接收（壳层带分享 URI 导航至此）②App 内后续入口（设置页，M4-6 接线）。
 * UI 只做表单编排与状态渲染，业务规则在 ViewModel/core 层（ADR-0008 铁律 7）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    sharedUris: List<String>,
    onSharedConsumed: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UploadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 系统分享接收：进入本页即接手分享内容并通知壳层消费（避免重复触发）
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            viewModel.acceptUris(sharedUris)
            onSharedConsumed()
        }
    }

    // SAF 多选（图片/视频；类型校验唯一口径在服务端四道检查，前端只给选择面）
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.acceptUris(uris.map { it.toString() })
    }

    // 通知权限（API 33+ 运行时申请；拒绝只影响可见性、不阻断上传）
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    var showCreateDirDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        QimengTopBar(title = "上传", onBack = onDone)

        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.errorMessage?.let { message ->
                MessageCard(text = message, container = MaterialTheme.colorScheme.errorContainer) {
                    viewModel.dismissError()
                }
            }
            state.blockMessage?.let { message ->
                MessageCard(text = message, container = MaterialTheme.colorScheme.tertiaryContainer) {
                    viewModel.dismissBlock()
                }
            }

            // —— 目标库 ——
            SectionTitle("目标库")
            if (state.libraries.isEmpty()) {
                Text("暂无可选库", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.libraries.forEach { library ->
                        QimengSegPill(
                            text = library.name,
                            selected = state.selectedLibrary?.id == library.id,
                            onClick = { viewModel.selectLibrary(library) },
                        )
                    }
                }
            }

            // —— 目标目录 ——
            SectionTitle("目标目录")
            state.dirTree?.let { tree ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        val expanded = remember(tree) { mutableStateOf(setOf(ROOT_PATH)) }
                        // 根行固定展示（path = "" 即库根，协议口径）
                        DirRow(
                            label = DIR_ROOT_LABEL,
                            detail = "${tree.fileCount} 个文件",
                            selected = state.selectedDirPath == ROOT_PATH,
                            expanded = true,
                            hasChildren = tree.children.isNotEmpty(),
                            onClick = { viewModel.selectDir(ROOT_PATH) },
                            onToggle = { },
                        )
                        tree.children.forEach { child ->
                            DirNodeRows(
                                node = child,
                                depth = 1,
                                selectedPath = state.selectedDirPath,
                                expandedPaths = expanded.value,
                                onToggle = { path ->
                                    expanded.value =
                                        if (path in expanded.value) expanded.value - path
                                        else expanded.value + path
                                },
                                onSelect = viewModel::selectDir,
                            )
                        }
                    }
                }
                Text(
                    text = "已选目录：${state.selectedDirPath.ifEmpty { DIR_ROOT_LABEL }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { showCreateDirDialog = true }) {
                    Text("在当前目录下新建子目录")
                }
            }

            // —— 待上传文件 ——
            SectionTitle("待上传文件（${state.pendingItems.size}）")
            Button(onClick = {
                requestNotificationPermissionIfNeeded(context, notificationPermission)
                documentPicker.launch(arrayOf(MIME_IMAGE, MIME_VIDEO))
            }) {
                Text("选择文件（图片/视频）")
            }
            state.pendingItems.forEach { item ->
                PendingItemRow(item = item, onRemove = { viewModel.removeItem(item) })
                HorizontalDivider()
            }

            Button(
                onClick = {
                    requestNotificationPermissionIfNeeded(context, notificationPermission)
                    viewModel.enqueue()
                },
                enabled = state.pendingItems.isNotEmpty() &&
                    state.selectedLibrary != null &&
                    !state.enqueueing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.enqueueing) "正在创建任务…" else "开始上传（${state.pendingItems.size} 个）")
            }

            // —— 上传队列 ——
            if (state.queue.isNotEmpty()) {
                SectionTitle("上传队列")
                state.queue.forEach { entry -> QueueRow(entry) }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showCreateDirDialog) {
        CreateDirDialog(
            onConfirm = { name ->
                showCreateDirDialog = false
                viewModel.createSubDir(name)
            },
            onDismiss = { showCreateDirDialog = false },
            creating = state.creatingDir,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 拦截/错误横幅（点击关闭）：错误用 error 容器色、超限拦截用 tertiary 容器色 */
@Composable
private fun MessageCard(text: String, container: Color, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDismiss() },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** 待上传文件行：展示名 + 大小 + 移除 */
@Composable
private fun PendingItemRow(item: UploadItem, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatBytes(item.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) { Text("移除") }
    }
}

/** 目录树行（递归渲染；深度缩进 + 展开/收起 + 点选为目标目录） */
@Composable
private fun DirNodeRows(
    node: DirNode,
    depth: Int,
    selectedPath: String,
    expandedPaths: Set<String>,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val hasChildren = node.children.isNotEmpty()
    val expanded = node.path in expandedPaths
    DirRow(
        label = node.path.substringAfterLast('/', node.path),
        detail = "${node.fileCount} 个文件",
        selected = selectedPath == node.path,
        expanded = expanded,
        hasChildren = hasChildren,
        onClick = { onSelect(node.path) },
        onToggle = { onToggle(node.path) },
        depth = depth,
    )
    if (expanded) {
        node.children.forEach { child ->
            DirNodeRows(child, depth + 1, selectedPath, expandedPaths, onToggle, onSelect)
        }
    }
}

@Composable
private fun DirRow(
    label: String,
    detail: String,
    selected: Boolean,
    expanded: Boolean,
    hasChildren: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    depth: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = (depth * INDENT_STEP_DP).dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            TextButton(onClick = onToggle) {
                Text(if (expanded) "▾" else "▸")
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
        Text(
            text = if (selected) "● $label" else "○ $label",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QueueRow(entry: UploadQueueEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
        when (entry.status) {
            UploadStatus.QUEUED ->
                StatusText("排队中（串行队列）", MaterialTheme.colorScheme.onSurfaceVariant)

            UploadStatus.UPLOADING -> {
                entry.progressPercent?.let { percent ->
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                StatusText(
                    "上传中${entry.progressPercent?.let { " $it%" } ?: "…"}",
                    MaterialTheme.colorScheme.primary,
                )
            }

            UploadStatus.SUCCEEDED -> StatusText(
                "上传成功：${entry.finalFileName ?: entry.displayName}",
                MaterialTheme.colorScheme.primary,
            )

            UploadStatus.FAILED -> StatusText(
                "失败：${entry.errorMessage ?: "未知原因"}",
                MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun CreateDirDialog(
    creating: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建子目录") },
        text = {
            // 胶囊输入框 label 走 placeholder 语义（G6：对齐 Web，组件不支持 label 浮动）
            QimengCapsuleTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "目录名",
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = !creating && name.isNotBlank(),
            ) {
                Text(if (creating) "创建中…" else "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 通知权限运行时申请（API 33+；未授权不影响上传本身，仅前台/完成通知不可见） */
private fun requestNotificationPermissionIfNeeded(context: Context, launcher: ActivityResultLauncher<String>) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** 字节数人性化展示（未知 -1 显示"大小未知"） */
private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> SIZE_UNKNOWN
    bytes < KB_UNIT -> "$bytes B"
    bytes < MB_UNIT -> "${bytes / KB_UNIT} KB"
    bytes < GB_UNIT -> String.format("%.1f MB", bytes.toDouble() / MB_UNIT)
    else -> String.format("%.2f GB", bytes.toDouble() / GB_UNIT)
}

/** 目录树根路径（协议口径：空串 = 库根） */
private const val ROOT_PATH = ""

private const val DIR_ROOT_LABEL = "（库根）"

/** 树行每层缩进（dp） */
private const val INDENT_STEP_DP = 20

private const val MIME_IMAGE = "image/*"
private const val MIME_VIDEO = "video/*"

/** 字节换算基数（1024 进位） */
private const val KB_UNIT = 1024L
private const val MB_UNIT = KB_UNIT * 1024L
private const val GB_UNIT = MB_UNIT * 1024L

private const val SIZE_UNKNOWN = "大小未知"
