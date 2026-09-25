package media.qimeng.app.feature.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.ui.component.QimengMessageCard
import media.qimeng.app.core.ui.component.QimengTopBar

// ---------- 页面文案（模块惯例：展示文案在代码常量） ----------
private const val TITLE_INBOX = "下载收件箱"
private const val SECTION_CURRENT = "收件箱"
private const val LABEL_NOT_SET = "未设置"
private const val BUTTON_SELECT_CURRENT = "选用当前文件夹"
private const val BUTTON_CLEAR = "清除收件箱"
private const val SECTION_BROWSER = "选择文件夹（含点前缀隐藏目录）"
private const val BUTTON_GO_UP = "返回上一级"
private const val LABEL_ROOT = "根目录"
private const val HINT_INBOX_SEMANTICS =
    "上传页「从收件箱导入」扫描此文件夹；上传成功后源文件移入其中的 uploaded/ 子目录归档"

// ---------- 授权引导文案（口径对齐 ServerSettingsScreen 的存储权限卡，单源复用其跳转） ----------
private const val PERM_GRANTED_TEXT =
    "已授权：可浏览主存储全部目录（含点前缀隐藏目录）"
private const val PERM_MISSING_TEXT =
    "未授权：收件箱通常是隐藏文件夹（点前缀目录），需要「所有文件访问」才能浏览选择"
private const val BUTTON_GRANT_STORAGE = "去系统设置授权"

/**
 * 下载收件箱设置子页（2026-09-25 暂存区重做：设置页「下载收件箱」入口行进本页，
 * pushed 覆盖页）：授权引导卡（MANAGE_EXTERNAL_STORAGE 未授权时整页引导，复用
 * ServerSettingsScreen 的系统授权页跳转）+ 当前选定卡 + 目录浏览器
 * （App 内纯 File API 列目录，不用系统弹窗；含点前缀隐藏目录）。
 * 选定 = 持久化到 StagingRepository；上传页「从收件箱导入」与 worker 的 uploaded/
 * 归档都以该路径为准。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InboxSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InboxSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QimengTopBar(title = TITLE_INBOX, onBack = onBack)

        if (state.loading) {
            LoadingIndicator(modifier = Modifier.padding(24.dp))
        }

        state.errorMessage?.let { message ->
            QimengMessageCard(text = message, container = MaterialTheme.colorScheme.errorContainer) {
                viewModel.dismissError()
            }
        }

        if (!state.allFilesGranted) {
            PermissionGuideCard(
                text = PERM_MISSING_TEXT,
                onGrant = { openAllFilesAccessSettings(context) },
            )
        } else {
            PermissionGuideCard(text = PERM_GRANTED_TEXT, onGrant = null)
            CurrentInboxCard(
                selectedPath = state.selectedInboxPath,
                onClear = viewModel::clearInbox,
            )
            BrowserCard(
                state = state,
                onEnter = viewModel::enter,
                onGoUp = viewModel::goUp,
                onSelect = viewModel::selectCurrentAsInbox,
            )
            Text(
                text = HINT_INBOX_SEMANTICS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 授权状态卡（未授权 = 引导按钮去系统设置；已授权 = 纯说明行） */
@Composable
private fun PermissionGuideCard(text: String, onGrant: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            if (onGrant != null) {
                Button(onClick = onGrant) { Text(BUTTON_GRANT_STORAGE) }
            }
        }
    }
}

/** 当前收件箱卡（选定路径 + 清除；未设置给占位文案） */
@Composable
private fun CurrentInboxCard(selectedPath: String?, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = SECTION_CURRENT, style = MaterialTheme.typography.titleMedium)
            Text(
                text = selectedPath ?: LABEL_NOT_SET,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedPath == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (selectedPath != null) {
                OutlinedButton(onClick = onClear) { Text(BUTTON_CLEAR) }
            }
        }
    }
}

/** 目录浏览器卡：当前路径行 + 上一级 + 子目录列表 + 选用当前文件夹 */
@Composable
private fun BrowserCard(
    state: InboxSettingsUiState,
    onEnter: (String) -> Unit,
    onGoUp: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = SECTION_BROWSER, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.browsingPath.ifEmpty { LABEL_ROOT },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onGoUp, enabled = state.browsingPath != state.storageRoot) {
                    Text(BUTTON_GO_UP)
                }
            }
            state.entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEnter(entry.path) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 点前缀隐藏目录原样展示（收件箱常落在系统相册扫不到的隐藏目录）
                    Text(
                        text = if (entry.path == state.selectedInboxPath) "● ${entry.name}" else "○ ${entry.name}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.entries.isEmpty() && !state.loading) {
                Text(
                    text = "此目录下没有子文件夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onSelect, enabled = state.browsingPath.isNotEmpty()) {
                Text(BUTTON_SELECT_CURRENT)
            }
        }
    }
}

