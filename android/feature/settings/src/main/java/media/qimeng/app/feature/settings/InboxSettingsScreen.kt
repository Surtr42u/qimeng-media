package media.qimeng.app.feature.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.ui.component.DirectoryBrowserCard
import media.qimeng.app.core.ui.component.DirectoryBrowserEntry
import media.qimeng.app.core.ui.component.QimengMessageCard
import media.qimeng.app.core.ui.component.QimengTopBar

// ---------- 页面文案（模块惯例：展示文案在代码常量） ----------
// 2026-09-28 上传归档文件夹功能：页面由单一「下载收件箱」扩为收件箱 + 上传归档文件夹
// 双设定（入口随迁数据管理页），标题与文案同步改「上传收件箱与归档」
private const val PAGE_TITLE = "上传收件箱与归档"
private const val SECTION_CURRENT = "收件箱"
private const val SECTION_ARCHIVE = "上传归档文件夹"
private const val LABEL_NOT_SET = "未设置"
private const val BUTTON_CLEAR = "清除收件箱"
private const val BUTTON_CLEAR_ARCHIVE = "清除归档文件夹"
private const val BUTTON_SELECT_INBOX = "设为收件箱"
private const val BUTTON_SELECT_ARCHIVE = "设为归档文件夹"
private const val SECTION_BROWSER = "选择文件夹（含点前缀隐藏目录）"
private const val BUTTON_RESELECT = "重新选择"
private const val HINT_INBOX_SEMANTICS =
    "上传页「从收件箱导入」扫描此文件夹；上传成功后源文件移入其中的 uploaded/ 子目录归档"
private const val HINT_ARCHIVE_SEMANTICS =
    "设置后，上传成功的文件会移动到 该文件夹/库名/ 下，用于手动复制同步到电脑；" +
        "不设置则维持原 uploaded/ 归档"

// ---------- 授权引导文案（口径对齐 ServerSettingsScreen 的存储权限卡，单源复用其跳转） ----------
private const val PERM_GRANTED_TEXT =
    "已授权：可浏览主存储全部目录（含点前缀隐藏目录）"
private const val PERM_MISSING_TEXT =
    "未授权：收件箱通常是隐藏文件夹（点前缀目录），需要「所有文件访问」才能浏览选择"
private const val BUTTON_GRANT_STORAGE = "去系统设置授权"

/**
 * 上传收件箱与归档设置子页（2026-09-25 暂存区重做；2026-09-28 归档文件夹功能扩双设定，
 * 入口随迁数据管理页）：授权引导卡（MANAGE_EXTERNAL_STORAGE 未授权时整页引导，复用
 * ServerSettingsScreen 的系统授权页跳转）+ 收件箱当前卡 + 归档文件夹当前卡 + 目录浏览器
 * （App 内纯 File API 列目录，不用系统弹窗；含点前缀隐藏目录）。
 * 浏览器导航一套共用，「设为收件箱 / 设为归档文件夹」两个按钮对同一浏览位置分别赋值——
 * 两个选定各自持久化到 StagingRepository；上传页「从收件箱导入」以收件箱路径为扫描源，
 * worker 上传成功后的归档分派以归档文件夹路径为准（未设置则维持 uploaded/ 归档）。
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
        QimengTopBar(title = PAGE_TITLE, onBack = onBack)

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
            CurrentValueCard(
                sectionTitle = SECTION_CURRENT,
                selectedPath = state.selectedInboxPath,
                clearButtonText = BUTTON_CLEAR,
                onClear = viewModel::clearInbox,
                onReselect = viewModel::reopenBrowser,
            )
            CurrentValueCard(
                sectionTitle = SECTION_ARCHIVE,
                selectedPath = state.selectedArchivePath,
                clearButtonText = BUTTON_CLEAR_ARCHIVE,
                onClear = viewModel::clearArchive,
                onReselect = viewModel::reopenBrowser,
            )
            Text(
                text = HINT_ARCHIVE_SEMANTICS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 选定成功即收起浏览器（browserVisible=false 时浏览器/双按钮行/收件箱语义提示
            // 整组隐藏，再入口在上方两张当前值卡的「重新选择」；原版单目标设计就是选定后收起）
            if (state.browserVisible) {
                // 目录浏览器（2026-09-28 上提 core:ui 单源：上传页「浏览文件」弹层复用同一组件，
                // 本页只保留标题与 ViewModel 状态注入）。收件箱与归档文件夹两个目标共用导航，
                // 组件内建「选用当前目录」单按钮容纳不下双目标（组件按红线不可改），故隐藏内建
                // 按钮、由下方双赋值按钮行承接选定动作
                DirectoryBrowserCard(
                    title = SECTION_BROWSER,
                    currentPath = state.browsingPath,
                    storageRoot = state.storageRoot,
                    entries = state.entries.map { DirectoryBrowserEntry(name = it.name, path = it.path) },
                    loading = state.loading,
                    selectedPath = null,
                    onEnter = viewModel::enter,
                    onGoUp = viewModel::goUp,
                    onSelectCurrent = null,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::selectCurrentAsInbox,
                        // 与 DirectoryBrowserCard 内建按钮同一启用口径：未进入任何目录不可选定
                        enabled = state.browsingPath.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(BUTTON_SELECT_INBOX) }
                    Button(
                        onClick = viewModel::selectCurrentAsArchive,
                        enabled = state.browsingPath.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(BUTTON_SELECT_ARCHIVE) }
                }
                Text(
                    text = HINT_INBOX_SEMANTICS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/**
 * 当前选定值卡（收件箱与上传归档文件夹共用形态）：选定路径 + 「重新选择」/清除按钮行；
 * 未设置给占位文案（未设置时浏览器必然可见，「重新选择」只在选定后出现——它是浏览器
 * 选定即收起后的再入口）。
 * 2026-09-28 归档文件夹功能：原 CurrentInboxCard 泛化（双目标同款卡片规格，参数化标题
 * 与清除文案）；同日浏览器收起改造加「重新选择」。
 */
@Composable
private fun CurrentValueCard(
    sectionTitle: String,
    selectedPath: String?,
    clearButtonText: String,
    onClear: () -> Unit,
    onReselect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = sectionTitle, style = MaterialTheme.typography.titleMedium)
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
                // 重新选择（展开浏览器、保留浏览位置）与清除并排等宽
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReselect, modifier = Modifier.weight(1f)) {
                        Text(BUTTON_RESELECT)
                    }
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                        Text(clearButtonText)
                    }
                }
            }
        }
    }
}

