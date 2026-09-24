package media.qimeng.app.feature.upload

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.component.QimengWordPillFlow
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

/**
 * 上传主通道页（M4-5）：选库 → 选目录（可新建）→ 选文件（SAF/分享接收）→ 串行队列进度。
 * 入口：①系统分享接收（壳层带分享 URI 导航至此）②App 内后续入口（设置页，M4-6 接线）。
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

    // 系统分享接收：进入本页即接手分享内容并通知壳层消费（避免重复触发）
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            viewModel.acceptUris(sharedUris)
            onSharedConsumed()
        }
    }

    // SAF 多选（图片/视频；类型校验唯一口径在服务端四道检查，前端只给选择面）。
    // C-1（批C 任务Q）持久化授权：不用 OpenMultipleDocuments contract——其 createIntent
    // 不带 FLAG_GRANT_PERSISTABLE_URI_PERMISSION（androidx.activity 1.13.0 反编译实证），
    // 返回的 URI 无 persistable grant、takePersistableUriPermission 必抛 SecurityException。
    // 自建 Intent 带该 flag，回调里立刻 take（官方时机），进程被回收后离线队列残余重试仍可读。
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = extractOpenDocumentUris(result)
        takePersistableRead(context, uris)
        if (uris.isNotEmpty()) viewModel.acceptUris(uris.map { it.toString() })
    }

    // 选文件夹（U10-6c）：同 C-1 口径自建 ACTION_OPEN_DOCUMENT_TREE Intent 并 take 树授权
    // （persist 一个 tree grant 覆盖其下全部 document URI，整树只需一个 grant——512 上限
    // 下的最省形态）；递归枚举与扩展名过滤收口在 core:data FolderScanner，UI 只把
    // treeUri 交给 ViewModel（ADR-0008 铁律 7）
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val treeUri = result.data?.data
        if (treeUri != null) {
            takePersistableRead(context, listOf(treeUri))
            viewModel.acceptFolderTree(treeUri.toString())
        }
    }

    // 通知权限（API 33+ 运行时申请；拒绝只影响可见性、不阻断上传）
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    var showCreateDirDialog by rememberSaveable { mutableStateOf(false) }

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
            onPickFiles = {
                requestNotificationPermissionIfNeeded(context, notificationPermission)
                documentPicker.launch(buildOpenDocumentIntent())
            },
            onPickFolder = { folderPicker.launch(buildOpenDocumentTreeIntent()) },
            onCreateDir = { showCreateDirDialog = true },
            onEnqueue = {
                requestNotificationPermissionIfNeeded(context, notificationPermission)
                viewModel.enqueue()
            },
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
}

/**
 * 表单滚动主体：横幅 → 目标库 → 目标目录 → 待上传 → 入队 → 队列。
 * 选择行为以回调注入（launcher 留在 [UploadScreen] 壳层），函数行数收敛到百行红线内。
 */
@Composable
private fun UploadForm(
    state: UploadUiState,
    viewModel: UploadViewModel,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onCreateDir: () -> Unit,
    onEnqueue: () -> Unit,
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
            DirTreePanel(
                tree = tree,
                selectedDirPath = state.selectedDirPath,
                onSelect = viewModel::selectDir,
                onCreateDir = onCreateDir,
            )
        }

        // —— 作者与来源（REQ §3.1：仅 capabilities.authorAttach=true 的库渲染，
        //     显隐判据在 UiState.authorAttachEnabled，本层禁止写死 kind）——
        if (state.authorAttachEnabled) {
            AuthorSection(
                state = state,
                onQueryChange = viewModel::onAuthorQueryChange,
                onPickSuggestion = viewModel::selectAuthorSuggestion,
                onCommitInput = viewModel::commitAuthorInput,
                onClear = viewModel::clearAuthor,
            )
            SourceSection(
                state = state,
                onToggle = viewModel::toggleSource,
                onAddCustom = viewModel::addCustomSource,
            )
        }

        // —— 待上传文件 ——
        SectionTitle("待上传文件（${state.pendingItems.size}）")
        PickerRow(
            onPickFiles = onPickFiles,
            onPickFolder = onPickFolder,
            scanningFolder = state.scanningFolder,
        )
        state.pendingItems.forEach { item ->
            PendingItemRow(item = item, onRemove = { viewModel.removeItem(item) })
            HorizontalDivider()
        }

        Button(
            onClick = onEnqueue,
            enabled = state.pendingItems.isNotEmpty() &&
                state.selectedLibrary != null &&
                !state.enqueueing,
            // 第 196 笔噪点横带本尊：禁用态通栏容器夜间走不透明底消 dither（W6 #49）
            colors = qimengFilledButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.enqueueing) "正在创建任务…" else "开始上传（${state.pendingItems.size} 个）")
        }

        // —— 上传队列 ——
        if (state.queue.isNotEmpty()) {
            QueueSection(queue = state.queue, summary = state.queueSummary, onCancel = viewModel::cancel)
        }

        Spacer(modifier = Modifier.height(24.dp))
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

/** 选文件/选文件夹两入口并排（U10-6c）；扫描中文案换态并禁用，避免并发扫描 */
@Composable
private fun PickerRow(
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    scanningFolder: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPickFiles, modifier = Modifier.weight(1f)) {
            Text("选择文件")
        }
        OutlinedButton(
            onClick = onPickFolder,
            enabled = !scanningFolder,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (scanningFolder) "正在扫描…" else "选择文件夹")
        }
    }
    Text(
        text = "支持图片/视频常见格式；选文件夹时保留子目录结构、自动跳过其他文件",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 作者段（REQ §3.1①：单选、可留空）：未确定 = 胶囊输入框 + 联想列表（无匹配时尾部
 * 固定「新建作者」行）；已确定 = 选中胶囊（点按清除）。状态与规则全在 ViewModel，
 * 本组件只渲染回调（ADR-0008 铁律 7）。
 */
@Composable
private fun AuthorSection(
    state: UploadUiState,
    onQueryChange: (String) -> Unit,
    onPickSuggestion: (AuthorSuggestion) -> Unit,
    onCommitInput: () -> Unit,
    onClear: () -> Unit,
) {
    SectionTitle("作者（可选）")
    val committedName = state.selectedAuthor?.displayName ?: state.pendingNewAuthor
    if (committedName != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 胶囊单源（QimengSegPill）：点按即清除；新建作者前缀区分两种确定态
            QimengSegPill(
                text = if (state.selectedAuthor != null) "$committedName ✕" else "新建：$committedName ✕",
                selected = true,
                onClick = onClear,
            )
            Text(
                text = if (state.selectedAuthor != null) "已选作者" else "将新建作者",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        QimengCapsuleTextField(
            value = state.authorQuery,
            onValueChange = onQueryChange,
            placeholder = "输入作者名（联想选择，回车新建）",
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommitInput() }),
        )
        if (state.authorQuery.isNotBlank()) {
            AuthorSuggestionList(
                suggestions = state.authorSuggestions,
                query = state.authorQuery.trim(),
                onPick = onPickSuggestion,
                onCreateNew = onCommitInput,
            )
        }
    }
}

/**
 * 联想列表：命中行 = displayName + 文件数（照 AuthorScreen 作者行双行口径）；
 * 无匹配时尾部固定「新建作者 "xxx"」行（点击=回车提交同一路径）。
 * 用固定 Card+Column 而非 LazyColumn：列表上限 = 协议 limit 10 + 1 行，且外层是
 * verticalScroll 表单（嵌套滚动反向冲突），固定高度内容交给外层滚动即可。
 */
@Composable
private fun AuthorSuggestionList(
    suggestions: List<AuthorSuggestion>,
    query: String,
    onPick: (AuthorSuggestion) -> Unit,
    onCreateNew: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            suggestions.forEach { suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = suggestion.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${suggestion.fileCount} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 无匹配时的回车新建固定尾行（REQ §3.1①）
            if (suggestions.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCreateNew)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "新建作者 \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 来源段（REQ §3.1②：多选、可留空）：已选胶囊流 + 快捷词表横滚胶囊 + 自由输入行。
 * 未选作者时整段禁用/降透明并提示「先选择作者」（来源仅指定作者时合法，协议 400 口径；
 * 误触另有 VM 门槛双保险）。
 */
@Composable
private fun SourceSection(
    state: UploadUiState,
    onToggle: (String) -> Unit,
    onAddCustom: (String) -> Unit,
) {
    SectionTitle("来源（可选）")
    val enabled = state.hasAuthor
    Column(
        modifier = if (enabled) {
            Modifier
        } else {
            Modifier.alpha(DISABLED_SECTION_ALPHA)
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!enabled) {
            Text(
                text = "先选择作者",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.sources.isNotEmpty()) {
            QimengWordPillFlow(
                pills = state.sources.map { name -> QimengPill(text = name, selected = true) },
                onPillClick = { index -> onToggle(state.sources[index]) },
            )
        }
        if (state.sourceOptions.isNotEmpty()) {
            // 快捷词表横滚胶囊（点击 toggle；选中态由 VM 状态驱动，组件本身受控）
            val names = state.sourceOptions.map { it.name }
            QimengChipRow(
                pills = names.map { name -> QimengPill(text = name, selected = name in state.sources) },
                onPillClick = { index -> onToggle(names[index]) },
            )
        }
        var customInput by rememberSaveable { mutableStateOf("") }
        QimengCapsuleTextField(
            value = customInput,
            onValueChange = { customInput = it },
            placeholder = "输入新站点或 URL，回车加入",
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    onAddCustom(customInput)
                    customInput = ""
                },
            ),
        )
    }
}

/**
 * 队列区：聚合行「共 N 个 · 成功 X · 失败 Y」+ 逐条状态行 + 取消控件（C-2）。
 * 取消交互对齐 Web 上传队列：点击即取消、无二次确认。
 */
@Composable
private fun QueueSection(
    queue: List<UploadQueueEntry>,
    summary: String?,
    onCancel: (UploadQueueEntry) -> Unit,
) {
    SectionTitle("上传队列")
    summary?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    queue.forEach { entry -> QueueRow(entry, onCancel) }
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

/** 待上传文件行：展示名 + 相对子目录（选文件夹上传时）+ 大小 + 移除 */
@Composable
private fun PendingItemRow(item: UploadItem, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
            if (item.relativeDir.isNotEmpty()) {
                Text(
                    text = "子目录：${item.relativeDir}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/** 队列行：文件名 + 状态 + 取消控件（排队中/上传中可取消；C-2 冻结语义「皆可取消」） */
@Composable
private fun QueueRow(entry: UploadQueueEntry, onCancel: (UploadQueueEntry) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when (entry.status) {
                UploadStatus.QUEUED, UploadStatus.UPLOADING -> TextButton(onClick = { onCancel(entry) }) {
                    Text(QUEUE_ACTION_CANCEL)
                }

                else -> Unit
            }
        }
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

            // 取消不计入失败（与 FAILED 分列；errorMessage=「已取消」仅诊断用，UI 文案定版）
            UploadStatus.CANCELLED -> StatusText(
                QUEUE_CANCELLED_TEXT,
                MaterialTheme.colorScheme.onSurfaceVariant,
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

// ---------- C-1 content:// 持久化授权（批C 任务Q）：自建选择器 Intent 与 persistable grant ----------
// 官方查证（铁律 8）：takePersistableUriPermission 只能持久化「以 FLAG_GRANT_PERSISTABLE_
// URI_PERMISSION 授予」的 grant（ContentResolver.takePersistableUriPermission 文档）；
// androidx 的 OpenMultipleDocuments/OpenDocumentTree contract 均不带该 flag（1.13.0 反编译
// 实证），故自建 Intent。grant 上限 512/package（AOSP UriGrantsManagerService
// MAX_PERSISTED_URI_GRANTS），超限系统按 persistedTime 自动淘汰最旧、take 不抛异常——
// v1 不做 releasePersistableUriPermission 的取舍依据（授权随卸载回收，泄漏无害；见交付报告）。

/** 构建文件多选 Intent：ACTION_OPEN_DOCUMENT + 多选 + 图片/视频过滤 + persistable flag（对齐原 contract 行为） */
private fun buildOpenDocumentIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT)
        .setType(MIME_ANY)
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME_IMAGE, MIME_VIDEO))
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        .addFlags(PERSISTABLE_GRANT_FLAGS)

/** 构建文件夹选择 Intent：ACTION_OPEN_DOCUMENT_TREE + persistable flag（树 grant 覆盖整树） */
private fun buildOpenDocumentTreeIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(PERSISTABLE_GRANT_FLAGS)

/** 从 SAF 结果提取 URI 列表（多选走 clipData，部分选择器单选只给 data） */
private fun extractOpenDocumentUris(result: ActivityResult): List<Uri> {
    val data = result.data ?: return emptyList()
    val clip = data.clipData
    return when {
        clip != null && clip.itemCount > 0 -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        data.data != null -> listOf(data.data!!)
        else -> emptyList()
    }
}

/**
 * SAF 返回即 take 持久读授权（官方时机：拿到 URI 后立刻 take）。
 * 失败不阻断本会话：本会话读权限已由 grant 生效（原 M4-5 行为不变），持久化失败只
 * 意味着进程回收后重试窗口的兜底失效——记日志留证（文本证据协议），交既有 retry 上限兜底。
 */
private fun takePersistableRead(context: Context, uris: List<Uri>) {
    val resolver = context.contentResolver
    uris.forEach { uri ->
        try {
            // flag 组合对齐授予权限的读侧（写授权从未申请/从未使用）
            resolver.takePersistableUriPermission(uri, PERSIST_READ_FLAG)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "takePersistableUriPermission 失败（不阻断本会话）uri=$uri", e)
        }
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

/** 目录树行折叠区宽度（dp）：有子级的箭头区与叶子占位同宽，两树文字起点对齐（V8 #1） */
private const val DIR_TOGGLE_AREA_WIDTH_DP = 48

/**
 * 目录树行折叠区高度（dp）：与整行约等高（bodyMedium 20sp 行高+上下各 6dp 行距≈32dp），
 * 不高于叶子行——行高与叶子行同档、不被折叠区撑破（V8 #1）；V8 二轮从 20 提到 32：
 * 点击热区翻倍至 48×32（Material 密集列表 32dp 档），清偿「收起热区低于最小触摸目标」审查回退。
 */
private const val DIR_TOGGLE_AREA_HEIGHT_DP = 32

private const val MIME_IMAGE = "image/*"
private const val MIME_VIDEO = "video/*"

/** 自建 ACTION_OPEN_DOCUMENT Intent 的 setType 兜底值（真实过滤走 EXTRA_MIME_TYPES） */
private const val MIME_ANY = "*/*"

/** logcat 证据标签（C-1 take 失败留证；与 UploadWorker.LOG_TAG 同为 grep 锚点） */
private const val LOG_TAG = "QimengUpload"

/**
 * 选择器 Intent 的 flag 组合（C-1，官方查证）：
 * - READ：上传只需要读流（原 grant 行为）；
 * - PERSISTABLE：takePersistableUriPermission 的前提——只有以该 flag 授予的 grant 可持久化。
 */
private const val PERSISTABLE_GRANT_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

/** takePersistableUriPermission 的 modeFlags（只持读侧） */
private const val PERSIST_READ_FLAG = Intent.FLAG_GRANT_READ_URI_PERMISSION

/** 队列行取消控件文案（对齐 Web 上传队列：取消无需二次确认） */
private const val QUEUE_ACTION_CANCEL = "取消"

/** 来源段未选作者时的降透明系数（整段禁用的视觉表达；点击由 VM 门槛兜底） */
private const val DISABLED_SECTION_ALPHA = 0.5f

/** 队列行取消态文案（与 FAILED 分列：用户取消不计失败） */
private const val QUEUE_CANCELLED_TEXT = "已取消"

/** 字节换算基数（1024 进位） */
private const val KB_UNIT = 1024L
private const val MB_UNIT = KB_UNIT * 1024L
private const val GB_UNIT = MB_UNIT * 1024L

private const val SIZE_UNKNOWN = "大小未知"
