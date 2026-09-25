package media.qimeng.app.feature.upload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengMessageCard
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

/**
 * 上传主通道页（M4-5；2026-09-25 流程重排）：先选文件进暂存区 → 在暂存区配置
 * （目标库必选 → 目标目录 → 批次默认 → 逐项编辑）→ 串行队列进度。
 * 空态（无待传项）只有选文件入口 + 引导文案，不展示任何配置项；未选库时开始上传
 * 禁用并提示（enqueue 侧保留必填兜底，见 [UploadViewModel.enqueue]）。
 * 入口：①系统分享接收（壳层带分享 URI 导航至此）②App 内数据管理页入口。
 * 选文件（2026-09-25 拍板）：弃系统 SAF 选择器，改 App 内置相册式选择器
 * （[MediaPickerDialog]，MediaStore 网格多选；媒体读权限运行时申请，未授权不进选择器）。
 * 挂靠批：暂存区批次默认（作者联想 + 来源多选 + 应用到全部；新进项自动继承）+ 逐项
 * 展开编辑（作品名/作者/来源，复用 core:ui 无状态段组件）；挂靠执行在 worker 的 201
 * 之后（mode=append，失败不重试，队列行落「已入库但挂靠失败」专项态）。
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

    // 选择器/新建目录弹层（saveable：进程重建后关闭态恢复，与页面弹窗同语义）
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showCreateDirDialog by rememberSaveable { mutableStateOf(false) }

    // 通知权限（API 33+ 运行时申请；拒绝只影响可见性、不阻断上传）
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // 媒体读权限（内置相册选择器数据面前置）：API 33+ 细分图片/视频两权限，
    // 低版本 READ_EXTERNAL_STORAGE（manifest 声明 maxSdkVersion=32）。任一授予即进选择器
    // （只授图片也能选图片）；全拒 = 留在本页（错误横幅由选择器空态承载，不另弹窗）。
    val mediaPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) showPicker = true
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
            onPickMedia = {
                requestNotificationPermissionIfNeeded(context, notificationPermission)
                openPickerOrRequestPermission(context, mediaPermission) { showPicker = true }
            },
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

    if (showPicker) {
        MediaPickerDialog(
            onConfirm = { picked ->
                showPicker = false
                viewModel.acceptPickedItems(picked)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * 表单滚动主体：横幅 → 选文件入口 →（空态引导 | 暂存区配置块与待传项/入队）→ 队列。
 * 2026-09-25 流程重排：目标库/目录/批次默认全部移入暂存区，库为配置块首行必选项；
 * 未选库时开始上传禁用并提示（门禁口径在 [UploadUiState.canEnqueue]）。
 * 选择行为以回调注入（launcher/权限留在本壳层），函数行数收敛到百行红线内。
 */
@Composable
private fun UploadForm(
    state: UploadUiState,
    viewModel: UploadViewModel,
    onPickMedia: () -> Unit,
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

        if (state.pendingItems.isEmpty()) {
            // —— 空态：只有选文件入口 + 引导文案，不展示任何配置项 ——
            PickerRow(hint = EMPTY_STATE_HINT, onPickMedia = onPickMedia)
        } else {
            // —— 待上传区（暂存态）——
            SectionTitle("待上传文件（${state.pendingItems.size}）")
            PickerRow(hint = PICKER_FORMAT_HINT, onPickMedia = onPickMedia)
            // 配置块：目标库（必选）→ 目标目录 → 批次作者 → 批次来源 → 应用到全部
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
            // 批次默认区（挂靠批）：新进项自动继承；已有待传项可「应用到全部」
            BatchDefaultSection(state = state, viewModel = viewModel)
            state.pendingItems.forEach { item ->
                PendingItemRow(
                    item = item,
                    editing = state.editingUri == item.uri,
                    itemAuthorQuery = state.itemAuthorQuery,
                    itemAuthorSuggestions = state.itemAuthorSuggestions,
                    sourceOptions = state.sourceOptions,
                    viewModel = viewModel,
                )
                HorizontalDivider()
            }

            Button(
                onClick = onEnqueue,
                enabled = state.canEnqueue,
                // 第 196 笔噪点横带本尊：禁用态通栏容器夜间走不透明底消 dither（W6 #49）
                colors = qimengFilledButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.enqueueing) "正在创建任务…" else "开始上传（${state.pendingItems.size} 个）")
            }
            state.enqueueGateHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // —— 上传队列 ——
        if (state.queue.isNotEmpty()) {
            QueueSection(queue = state.queue, summary = state.queueSummary, onCancel = viewModel::cancel)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 目标库选择（暂存区配置块首行，必选；复用页面原库 pills 控件与 VM 数据源） */
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

/**
 * 选媒体入口（内置相册选择器，2026-09-25 拍板替代 SAF）；提示文案随页面状态切换
 * （空态引导 / 暂存态格式说明）。
 */
@Composable
private fun PickerRow(hint: String, onPickMedia: () -> Unit) {
    Button(onClick = onPickMedia, modifier = Modifier.fillMaxWidth()) {
        Text("从相册选择")
    }
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

// ---------- 媒体读权限与内置相册选择器入口（2026-09-25 拍板，替代 SAF 选择器） ----------
// 官方查证（铁律 8）：MediaStore 图片/视频读取 API 33+ 走 READ_MEDIA_IMAGES/READ_MEDIA_VIDEO
// 细分权限（READ_EXTERNAL_STORAGE 在 33+ 失效），26~32 走 READ_EXTERNAL_STORAGE。
// MediaStore 记录 URI 的跨进程读授权随这些运行时权限存活，无需 takePersistableUriPermission
// （该 API 只作用于 SAF grant，对 MediaStore URI 本就不适用）。

/** 本选择器需要的媒体读权限集（按系统版本；官方粒度最小化口径） */
private fun requiredMediaPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/**
 * 媒体读权限判定。MANAGE_EXTERNAL_STORAGE（「所有文件访问」，ADR-0015 本机模式用户
 * 可能已授予）视为已授权——All-Files-Access 隐含 MediaStore 读，无需重复弹细分授权。
 */
private fun hasMediaReadPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
        return true
    }
    return requiredMediaPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}

/** 已授权直接进选择器；否则发起运行时申请（回调里任一授予即打开，见 [UploadScreen]） */
private fun openPickerOrRequestPermission(
    context: Context,
    launcher: ActivityResultLauncher<Array<String>>,
    onOpen: () -> Unit,
) {
    if (hasMediaReadPermission(context)) {
        onOpen()
    } else {
        launcher.launch(requiredMediaPermissions().toTypedArray())
    }
}

/** 目录树根路径（协议口径：空串 = 库根） */
private const val ROOT_PATH = ""

/** 空态引导文案（2026-09-25 流程重排：选文件是第一步，配置项在暂存区出现） */
private const val EMPTY_STATE_HINT = "选择手机里的图片/视频，上传前可编辑作品名与作者"

/** 暂存态选文件入口的格式说明（超限拦截口径在 VM/服务端） */
private const val PICKER_FORMAT_HINT = "支持图片/视频常见格式；类型与大小校验在服务端，超限项本地拦截"

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
