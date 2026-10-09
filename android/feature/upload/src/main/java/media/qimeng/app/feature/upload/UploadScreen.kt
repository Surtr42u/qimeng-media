package media.qimeng.app.feature.upload

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengMessageCard
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 上传主通道页（M4-5；2026-09-29 直传化；2026-10-07 加回文件名编辑）：
 * 页面自上而下按动线「选库 → 批次默认 → 添加文件」单列贯通——
 * 目标库（必选）→ 目标目录 → 批次默认（作者/来源，新进文件自动继承）→ 添加文件入口
 * → 选中文件预览卡（逐项编辑落库基名、扩展名锁定，见 [UploadNaming]）→「开始上传」
 * 入队 → 串行队列进度。暂存区已退役（2026-09-29 用户拍板），文件名编辑以轻量形态加回：
 * SAF 选完先进 selectedFiles 预览（逐项基名编辑 + 移除），点「开始上传」才入队；
 * 系统分享接收仍直传不编辑（见 [UploadViewModel.submitUris]）。批次默认仍持久化
 * （杀进程重启不丢），页面只 collect 持久流渲染。
 * 未选库时「开始上传」不传，横幅提示先选目标库（uploadSelectedFiles 门禁；分享路径
 * 仍为 submitUris 门禁，见 [UploadViewModel.submitUris]）。
 * 入口：①系统分享接收（壳层带分享 URI 导航至此）②App 内数据管理页入口。
 * 选文件：唯一入口「系统文件」（SAF 文档选择器多选，MIME 限 image 与 video 通配两类，
 * 天然能见点前缀隐藏目录且不需要任何存储权限——2026-09-29 用户拍板精简，一口覆盖
 * 原相册选择器/收件箱导入/浏览文件三入口的全部场景，三入口及其选择器/弹层/权限链退役）。
 * 挂靠批：批次默认（作者联想 + 来源多选）随载荷入队，新进文件自动继承；挂靠执行在
 * worker 的 201 之后（mode=append，失败不重试，队列行落「已入库但挂靠失败」专项态）。
 * UI 只做表单编排与状态渲染，业务规则在 ViewModel/core 层（ADR-0008 铁律 7）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UploadScreen(
    sharedUris: List<String>,
    onSharedConsumed: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UploadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 系统分享接收：进入本页即接手分享内容并通知壳层消费（避免重复触发）；选完即传
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            viewModel.submitUris(sharedUris)
            onSharedConsumed()
        }
    }

    // 新建目录弹层（saveable：进程重建后关闭态恢复，与页面弹窗同语义）
    var showCreateDirDialog by rememberSaveable { mutableStateOf(false) }

    // 归档一键上传确认弹层（2026-10-01；同 saveable 恢复语义）
    var showArchiveBatchDialog by rememberSaveable { mutableStateOf(false) }

    // 通知权限（API 33+ 运行时申请；拒绝只影响可见性、不阻断上传）
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // 「系统文件」唯一选文件入口（2026-09-29 用户拍板精简：相册/收件箱导入/浏览文件
    // 三入口退役，SAF 一口覆盖——隐藏目录可见 + 免存储授权 + 多选）：SAF
    // ACTION_OPEN_DOCUMENT 多选，系统文档选择器天然能见点前缀隐藏目录，读授权由
    // DocumentsUI 随结果授予，本 App 不需要任何存储权限（manifest 零改动）。
    // 官方查证（铁律 8）：OpenMultipleDocuments = Contract<String[], List<Uri>>（launch
    // 入参即 MIME 类型数组，androidx.activity 1.13.0 字节码核实，底层 ACTION_OPEN_DOCUMENT），
    // 结果 URI 带 FLAG_GRANT_READ_URI_PERMISSION + FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    // （项目先例：BackupScreen 的 OpenDocumentTree 同链路 takePersistableUriPermission）。
    // 逐 URI takePersistableUriPermission 的理由：选完即传，但队列在 WorkManager 持久
    // （断网重试/进程重启后续跑），真正读流在 worker 上传时（AssetUploader
    // openInputStream）——DocumentsUI 的临时授权撑不到上传时刻，持久化后才能跨进程存活。
    // 个别 provider 不给持久授权时降级照收（摄取期 describe 仍可读，上传读流失败走 worker
    // 既有重试兜底），不因授权失败丢弃用户选择。
    val systemFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        requestNotificationPermissionIfNeeded(context, notificationPermission)
        viewModel.onFilesSelected(uris.map { it.toString() })
    }

    Column(modifier = modifier.fillMaxSize()) {
        QimengTopBar(title = "上传", onBack = onDone)

        if (state.loading) {
            // V6：expressive LoadingIndicator 替换（仅控件替换，padding 原样）。
            // V8 #5 口径：页级加载无并排文字，保持组件默认 48dp（ButtonLoadingIndicatorSize
            // 20dp 档仅限按钮内嵌 loading，页级不共用）
            LoadingIndicator(modifier = Modifier.padding(24.dp))
        }

        UploadForm(
            state = state,
            viewModel = viewModel,
            onSystemFiles = { systemFilesLauncher.launch(SYSTEM_FILES_MIME_TYPES) },
            onCreateDir = { showCreateDirDialog = true },
            onArchiveBatchUpload = { showArchiveBatchDialog = true },
        )
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

    // 归档一键上传确认弹窗（2026-10-01）：确认后 VM 走既有入队管道，轻提示走既有横幅
    if (showArchiveBatchDialog) {
        ArchiveBatchConfirmDialog(
            state = state,
            onConfirm = {
                showArchiveBatchDialog = false
                viewModel.onArchiveBatchUpload()
            },
            onDismiss = { showArchiveBatchDialog = false },
        )
    }
}

/**
 * 表单滚动主体（2026-09-29 直传化；2026-10-07 加回文件名编辑）：横幅 → 选库（目标库/
 * 目标目录）→ 批次默认（作者/来源，新进文件自动继承）→ 添加文件入口 → 选中文件预览卡
 * （逐项基名编辑 + 移除 +「开始上传」）→ 队列。配置区进入页面即显示（2026-09-28 固化
 * 布局延续）；暂存列表随暂存区退役，选中文件卡只在有选中文件时出现。
 * 选择行为以回调注入（launcher/权限留在本壳层），函数行数收敛到百行红线内。
 */
@Composable
private fun UploadForm(
    state: UploadUiState,
    viewModel: UploadViewModel,
    onSystemFiles: () -> Unit,
    onCreateDir: () -> Unit,
    onArchiveBatchUpload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.errorMessage?.let { message ->
            QimengMessageCard(text = message, container = MaterialTheme.colorScheme.errorContainer) {
                viewModel.dismissError()
            }
        }
        state.noticeMessage?.let { message ->
            QimengMessageCard(text = message, container = MaterialTheme.colorScheme.tertiaryContainer) {
                viewModel.dismissNotice()
            }
        }
        state.blockMessage?.let { message ->
            QimengMessageCard(text = message, container = MaterialTheme.colorScheme.tertiaryContainer) {
                viewModel.dismissBlock()
            }
        }

        // —— 选库：目标库（必选）→ 目标目录（选库后展示目录树），常驻 ——
        LibrarySection(state = state, viewModel = viewModel)
        state.dirTree?.let { tree ->
            SectionTitle("目标目录")
            DirTreePanel(
                tree = tree,
                selectedDirPath = state.selectedDirPath,
                onSelect = viewModel::selectDir,
                onCreateDir = onCreateDir,
            )
        }

        // —— 批次默认区（挂靠批）：新进文件自动继承 ——
        BatchDefaultSection(state = state, viewModel = viewModel)

        // —— 放文件：唯一入口「系统文件」（SAF，2026-09-29 用户拍板精简：相册/收件箱
        // 导入/浏览文件三入口退役——SAF 隐藏目录可见 + 免存储授权，一口全覆盖），选完
        // 先进预览编辑文件名（2026-10-07 加回）再上传 ——
        SectionTitle("添加文件")
        AddSourcesRow(onSystemFiles = onSystemFiles)
        DirectUploadGuide()

        // —— 选中文件预览/编辑文件名（2026-10-07 加回文件名编辑）：选文件后展示待传列表，
        // 用户可编辑落库基名（扩展名锁定），点击「开始上传」后入队 ——
        if (state.describing) {
            Text(
                text = "正在读取文件信息…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.selectedFiles.isNotEmpty()) {
            SelectedFilesCard(state = state, viewModel = viewModel)
        }

        // —— 归档一键上传（2026-10-01）：归档根已配置时展示扫描摘要与一键入口，确认弹窗
        // 在壳层（与新建目录弹层同位置）；扫描/匹配/入队规则全在 core 与 VM ——
        ArchiveBatchSection(state = state, onUploadClick = onArchiveBatchUpload)

        // —— 上传队列 ——
        if (state.queue.isNotEmpty()) {
            QueueSection(queue = state.queue, summary = state.queueSummary, onCancel = viewModel::cancel)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * 选文件入口（唯一管道「系统文件」，常驻；2026-09-29 用户拍板精简，原相册选择器/
 * 收件箱导入/浏览文件三入口退役）：SAF 文档选择器多选——能见点前缀隐藏目录且免
 * 「所有文件访问」授权，一口覆盖三个退役入口的全部场景。
 */
@Composable
private fun AddSourcesRow(onSystemFiles: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSystemFiles, modifier = Modifier.fillMaxWidth()) {
            Text(SYSTEM_FILES_BUTTON_TEXT)
        }
        Text(
            text = SYSTEM_FILES_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = ADD_FILES_FORMAT_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 直传说明（2026-09-29 直传化：暂存区退役，原「暂存空态引导」改为添加文件区常驻说明）：
 * 选完文件立即上传，自动继承批次作者与来源。
 */
@Composable
private fun DirectUploadGuide() {
    Text(
        text = DIRECT_UPLOAD_GUIDE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 选中文件列表卡（2026-10-07 加回文件名编辑）：展示用户通过 SAF 选中的待传文件，
 * 每项可编辑落库基名（扩展名锁定后缀）+ 移除；底部「开始上传」按钮触发入队。
 * UI 只做渲染与 VM 调用，业务规则全在 UploadViewModel（ADR-0008 铁律 7）。
 */
@Composable
private fun SelectedFilesCard(state: UploadUiState, viewModel: UploadViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "待上传文件（${state.selectedFiles.size} 项）",
                style = MaterialTheme.typography.titleSmall,
            )
            state.selectedFiles.forEach { item ->
                SelectedFileRow(
                    item = item,
                    suggestions = if (state.editingFileUri == item.uri) state.nameSuggestions else emptyList(),
                    viewModel = viewModel,
                )
            }
            Button(
                onClick = viewModel::uploadSelectedFiles,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            ) {
                Text(if (state.submitting) "上传中…" else "开始上传（${state.selectedFiles.size} 项）")
            }
        }
    }
}

/**
 * 选中文件行：基名输入框（扩展名锁定后缀）+ 作品名序号联想推荐 + 移除按钮。
 * 基名编辑即时同步 UploadItem.uploadBaseName，落库名由 effectiveUploadName 单源拼装。
 */
@Composable
private fun SelectedFileRow(
    item: UploadItem,
    suggestions: List<String>,
    viewModel: UploadViewModel,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QimengCapsuleTextField(
                value = item.currentBaseName,
                onValueChange = { viewModel.updateSelectedFileName(item, it) },
                placeholder = UPLOAD_NAME_PLACEHOLDER,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (item.extension.isNotEmpty()) {
                Text(
                    text = item.extension,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = LOCKED_BADGE_TEXT,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { viewModel.removeSelectedFile(item) }) {
                Text("移除")
            }
        }
        if (suggestions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "推荐文件名（点击采用）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { base ->
                            QimengSegPill(
                                text = base + item.extension,
                                selected = false,
                                onClick = { viewModel.pickFileNameSuggestion(item, base) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 目标库选择（页面配置区首行，必选、常驻；复用页面原库 pills 控件与 VM 数据源） */
@Composable
private fun LibrarySection(state: UploadUiState, viewModel: UploadViewModel) {
    SectionTitle("目标库（必选）")
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
}

/** 目标目录面板：目录树卡片 + 已选目录提示 + 新建子目录入口（目录树已加载时展示） */
@Composable
private fun DirTreePanel(
    tree: DirNode,
    selectedDirPath: String,
    onSelect: (String) -> Unit,
    onCreateDir: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            val expanded = remember(tree) { mutableStateOf(setOf(ROOT_PATH)) }
            // 根行固定展示（path = "" 即库根，协议口径）
            DirRow(
                label = DIR_ROOT_LABEL,
                detail = "${tree.fileCount} 个文件",
                selected = selectedDirPath == ROOT_PATH,
                expanded = true,
                hasChildren = tree.children.isNotEmpty(),
                onClick = { onSelect(ROOT_PATH) },
                onToggle = { },
            )
            tree.children.forEach { child ->
                DirNodeRows(
                    node = child,
                    depth = 1,
                    selectedPath = selectedDirPath,
                    expandedPaths = expanded.value,
                    onToggle = { path ->
                        expanded.value =
                            if (path in expanded.value) expanded.value - path
                            else expanded.value + path
                    },
                    onSelect = onSelect,
                )
            }
        }
    }
    Text(
        text = "已选目录：${selectedDirPath.ifEmpty { DIR_ROOT_LABEL }}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCreateDir) {
        Text("在当前目录下新建子目录")
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
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
        onClick = {
            // V7：点行 = 选中并进入（有子级且未展开时同时展开）——
            // COS 等层级库点行直达子文件夹，收起仍走 ▾ 箭头（规则见 UploadRules）
            onSelect(node.path)
            if (UploadRules.shouldExpandOnSelect(hasChildren, expanded)) onToggle(node.path)
        },
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
            // V8 #1：折叠箭头不再用 TextButton——M3 TextButton 自带约 40dp 最小高度，
            // 会把「有子级的行」撑到叶子行（Spacer 占位）的约 1.3 倍，COS 树与 normal 树
            // 切换时密度跳变。改为与叶子占位同宽、与整行约等高（20sp 行高+12dp 行距）
            // 的可点击区，行高仍与叶子行同档、由行内文字+padding 单源决定
            // （点击语义不变：仍只在此区域触发折叠）。
            Box(
                modifier = Modifier
                    .size(width = DIR_TOGGLE_AREA_WIDTH_DP.dp, height = DIR_TOGGLE_AREA_HEIGHT_DP.dp)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Spacer(modifier = Modifier.width(DIR_TOGGLE_AREA_WIDTH_DP.dp))
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

// —— 相册选择器（MediaPickerScreen，含媒体读权限申请）与浏览文件弹层（FileBrowserScreen，
//    含「所有文件访问」闸门）已随 2026-09-29 入口精简退役，文件整体删除（死代码零残留）——
//    暂存列表/逐项编辑器（StagedItemRow/StagedItemEditor）同日随暂存区退役删除——
//    选完文件即传，无暂存、无逐项校对、无「开始上传」按钮。

/** 目录树根路径（协议口径：空串 = 库根） */
private const val ROOT_PATH = ""

// ---------- 「系统文件」唯一入口（2026-09-29）：SAF 文档选择器，隐藏目录可见 + 免存储授权 ----------
// launcher 与持久授权链见 UploadScreen 内 systemFilesLauncher 注释；管道落点 submitUris
// （与系统分享同一条直传管道，UploadViewModel.submitUris）。

/** SAF 多选 MIME 限定（图片 + 视频；白名单校验仍在服务端，此处只收窄选择器可见类型） */
private val SYSTEM_FILES_MIME_TYPES = arrayOf("image/*", "video/*")

private const val SYSTEM_FILES_BUTTON_TEXT = "系统文件"

/** 「系统文件」入口副文案（选文件唯一入口；免授权也能见隐藏目录是它的覆盖面来源） */
private const val SYSTEM_FILES_HINT =
    "「系统文件」用系统文档选择器：可直接选到点前缀隐藏目录里的媒体，无需「所有文件访问」授权"

/** 直传说明（2026-10-08 体验优化：选文件后可修改文件名，消除多作者强绑定的心理负担） */
private const val DIRECT_UPLOAD_GUIDE =
    "选择文件后可修改落库文件名，点击「开始上传」入队传输；多作者或杂图无需预设，上传后可随时整理"

/** 作品名输入占位符（SelectedFileRow 基名编辑输入框） */
private const val UPLOAD_NAME_PLACEHOLDER = "作品名"

/** 扩展名锁定角标 */
private const val LOCKED_BADGE_TEXT = "锁定"

/** 添加文件入口的格式说明（超限拦截口径在 VM/服务端） */
private const val ADD_FILES_FORMAT_HINT = "支持图片/视频常见格式；类型与大小校验在服务端，超限项本地拦截"

private const val DIR_ROOT_LABEL = "（库根）"

/** 树行每层缩进（dp） */
private const val INDENT_STEP_DP = 20

/** 目录树行折叠区宽度（dp）：有子级的箭头区与叶子占位同宽，两树文字起点对齐（V8 #1） */
private const val DIR_TOGGLE_AREA_WIDTH_DP = 48

/**
 * 目录树行折叠区高度（dp）：与整行约等高（bodyMedium 20sp 行高+上下各 6dp 行距≈32dp），
 * 不高于叶子行——行高与叶子行同档、不被折叠区撑破（V8 #1）；V8 二轮从 20 提到 32：
 * 点击热区翻倍至 48×32（Material 密集列表 32dp 档），清偿「收起热区低于最小触摸目标」审查回退。
 */
private const val DIR_TOGGLE_AREA_HEIGHT_DP = 32
