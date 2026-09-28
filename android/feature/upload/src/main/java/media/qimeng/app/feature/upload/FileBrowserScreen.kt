package media.qimeng.app.feature.upload

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.data.repository.BrowserFileEntry
import media.qimeng.app.core.ui.component.DirectoryBrowserCard
import media.qimeng.app.core.ui.component.DirectoryBrowserEntry
import media.qimeng.app.core.ui.component.formatBytesForDetail
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

/**
 * 浏览文件弹层（2026-09-28「浏览文件」入口）：相册选择器走 MediaStore，看不到不出现在
 * 系统相册的点前缀隐藏目录（论坛/下载器缓存常在此类目录），本弹层用纯 File API 从主
 * 存储根浏览补齐该场景。形态与风格对齐 MediaPickerDialog：全屏 Dialog 承载（选择是
 * 模态子任务，不进导航返回栈）+ 底部确认条；目录导航复用 core:ui 的
 * DirectoryBrowserCard（与收件箱设置页同款单源，禁止 feature 内再造浏览器）。
 * 前置「所有文件访问」闸门在 UploadScreen 入口（未授权弹说明 + 跳系统授权页），
 * 本弹层只在已授权时打开。确认后按绝对路径（isPathSource=true）进暂存区，
 * 去重与批次默认继承在 UploadStagingIngestor 单源。
 *
 * @param onConfirm 确认回传选中文件（保持选择序；调用方进暂存区）
 * @param onDismiss 关闭（底部取消键 / 系统返回）
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun FileBrowserDialog(
    onConfirm: (List<BrowserFileEntry>) -> Unit,
    onDismiss: () -> Unit,
    viewModel: FileBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                BrowserHeader(selectedCount = state.selected.size, onClose = onDismiss)
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                when {
                    state.loading -> LoadingIndicator(modifier = Modifier.padding(24.dp))
                    else -> BrowserContent(
                        state = state,
                        onEnter = viewModel::enter,
                        onGoUp = viewModel::goUp,
                        onToggle = viewModel::toggle,
                        modifier = Modifier.weight(1f),
                    )
                }
                BrowserConfirmBar(
                    count = state.selected.size,
                    enabled = state.selected.isNotEmpty(),
                    onConfirm = { onConfirm(state.selected.values.toList()) },
                    onCancel = onDismiss,
                )
            }
        }
    }
}

/** 头部：标题 + 已选计数 + 关闭（形态逐段对齐 MediaPickerScreen.PickerHeader） */
@Composable
private fun BrowserHeader(selectedCount: Int, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = DIALOG_TITLE, style = MaterialTheme.typography.titleLarge)
            if (selectedCount > 0) {
                Text(
                    text = "已选 $selectedCount 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onClose) { Text(BUTTON_CLOSE) }
    }
}

/** 主体：目录导航卡（core:ui 浏览器卡，只导航不选目录）+ 当前目录文件多选列表（同一滚动容器） */
@Composable
private fun BrowserContent(
    state: FileBrowserUiState,
    onEnter: (String) -> Unit,
    onGoUp: () -> Unit,
    onToggle: (BrowserFileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = KEY_DIR_CARD) {
            DirectoryBrowserCard(
                title = SECTION_DIRS_TITLE,
                currentPath = state.browsingPath,
                storageRoot = state.storageRoot,
                entries = state.dirEntries.map { DirectoryBrowserEntry(name = it.name, path = it.path) },
                onEnter = onEnter,
                onGoUp = onGoUp,
            )
        }
        item(key = KEY_FILES_TITLE) { SectionTitle(SECTION_FILES_TITLE) }
        if (state.files.isEmpty()) {
            item(key = KEY_FILES_EMPTY) {
                Text(
                    text = EMPTY_FILES_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.files, key = { it.path }) { file ->
            FileRow(
                file = file,
                selected = file.path in state.selected,
                onToggle = { onToggle(file) },
            )
        }
    }
}

/** 文件行：勾选框 + 文件名（长名省略）+ 大小；整行点选 toggle（触控热区整行） */
@Composable
private fun FileRow(file: BrowserFileEntry, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 勾选态显示件（onCheckedChange=null：点击语义由整行承担，避免双热区抢事件）
        Checkbox(checked = selected, onCheckedChange = null)
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatBytesForDetail(file.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 底部确认条：取消 + 加入暂存（N）；零选中禁用（确认文案点名落点暂存区，与队列区分） */
@Composable
private fun BrowserConfirmBar(
    count: Int,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(BUTTON_CANCEL) }
            Button(
                onClick = onConfirm,
                enabled = enabled,
                colors = qimengFilledButtonColors(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (enabled) "$BUTTON_CONFIRM（$count）" else BUTTON_CONFIRM)
            }
        }
    }
}

// ---------- 弹层文案与结构常量（弹层标题/确认按钮文案按惯例就近字面量收纳为常量单源） ----------
private const val DIALOG_TITLE = "浏览文件"
private const val SECTION_DIRS_TITLE = "文件夹（点选进入子目录，含点前缀隐藏目录）"
private const val SECTION_FILES_TITLE = "当前目录文件"
private const val EMPTY_FILES_TEXT = "此目录没有可上传的图片/视频文件"
private const val BUTTON_CONFIRM = "加入暂存"
private const val BUTTON_CANCEL = "取消"
private const val BUTTON_CLOSE = "关闭"

/** LazyColumn 固定项 key（目录卡/文件区标题/空态与文件项 path key 区分开） */
private const val KEY_DIR_CARD = "dirs"
private const val KEY_FILES_TITLE = "files-title"
private const val KEY_FILES_EMPTY = "files-empty"
