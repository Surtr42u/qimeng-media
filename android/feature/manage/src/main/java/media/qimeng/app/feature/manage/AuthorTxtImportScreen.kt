package media.qimeng.app.feature.manage

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（对齐 Web LibraryManagePage.tsx TxtAuthorImportCard L84-187 逐字） */
private const val SCREEN_TITLE = "作者 TXT 导入"
private const val SECTION_COUNT_TEMPLATE = "%d 份片段"
private const val DROP_TITLE = "选择 TXT 导入"

/** 导入中态标题（Web .upload-drop pending 文案逐字；两子页导入区共用单一来源） */
internal const val DROP_TITLE_IMPORTING = "导入中…"
private const val DROP_HINT_EMPTY = "点击选择旧项目导出的作者清单 .txt，开始重建作者关联"
private const val DROP_HINT_MORE = "点击选择新的 .txt 清单，同名文件自动覆盖"
private const val REBUILD_BUTTON = "重新匹配"
private const val REBUILD_BUTTON_BUSY = "重放中…"
private const val ROW_ACTION_REMOVE = "移除"
private const val ROW_ANONYMOUS = "（匿名导入）"
private const val LIST_EMPTY = "还没有导入片段——从上方选择 .txt 开始。"
private const val LIST_LOADING = "加载中…"

/** 规则说明三条（Web L137-139 逐字） */
private val RULE_LINES = listOf(
    "· 格式 A / B / C 自动识别",
    "· 同名文件重复导入为覆盖",
    "· 删除某份后按「剩余片段全部」重算作者与文件关联，作者行保留",
)

/** 16dp：内容水平内边距与导入区内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/** 1dp：导入区描边宽（Web .upload-drop 1px dashed 的 App 近似——Compose 无虚线描边
 *  一等件，solid 细描边保「可点导入区」语义，视觉差记档） */
private val DropZoneBorderWidth = 1.dp

/** SAF 选文件的 MIME 过滤（Web input accept=".txt,text/plain" 对齐；扩展名硬校验在 VM） */
private val TXT_MIME_TYPES = arrayOf("text/plain")

/**
 * 作者 TXT 导入页（U10-6b）：片段列表 + 虚线导入区卡化（点击=选文件）+ 三行规则说明
 * + 重新匹配钮。信息架构基准 = Web TxtAuthorImportCard，视觉/交互基准 = App 库管理子页。
 * 业务全在 ViewModel（铁律 7）；SAF 读文件是屏幕层平台胶水（SettingsScreen 先例口径）。
 */
@Composable
fun AuthorTxtImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthorTxtImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // SAF 选文件（OpenDocument）：选择器回调里读文件名与 UTF-8 内容（拍板：只支持
    // UTF-8，BOM 不特判——与 Web readAsText 默认 UTF-8 同口径，Web 也无 BOM 剥离），
    // 业务校验/出网全在 VM；uri=null=用户取消，静默
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 读文件走 IO（平台胶水），完成后回主线程进 VM
            val filename = resolveDisplayName(context, uri) ?: uri.lastPathSegment ?: ""
            val content = readUtf8(context, uri)
            if (content == null) {
                // 读取失败文案复用 Web reader.onerror 逐字（走错误横幅通道）
                viewModel.onReadFailed()
            } else {
                viewModel.onFilePicked(filename, content)
            }
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

            SectionHead(count = state.files.size)
            ImportDropZone(
                title = DROP_TITLE,
                hint = if (state.files.isEmpty()) DROP_HINT_EMPTY else DROP_HINT_MORE,
                importing = state.importing,
                onPick = { pickTxtLauncher.launch(TXT_MIME_TYPES) },
            )
            RuleNotes()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::rebuild, enabled = state.canRebuild) {
                    Text(if (state.rebuilding) REBUILD_BUTTON_BUSY else REBUILD_BUTTON)
                }
            }

            when {
                state.loading -> EmptyText(LIST_LOADING)
                state.files.isEmpty() -> EmptyText(LIST_EMPTY)
                else -> state.files.forEach { filename ->
                    ImportedFileRow(
                        displayName = if (filename.isEmpty()) ROW_ANONYMOUS else filename,
                        removing = state.removing,
                        onRemove = { viewModel.remove(filename) },
                    )
                }
            }

            AuthorMirrorCard(
                mirrorPath = state.mirrorPath,
                mirrorFragment = state.mirrorFragmentFilename,
                saving = state.mirrorSaving,
                onPathChange = viewModel::onMirrorPathChange,
                onFragmentChange = viewModel::onMirrorFragmentChange,
                onSave = viewModel::saveMirror,
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }
}

/** 区块头：标题 + 「N 份片段」计数（Web rank-head/rank-note 同位） */
@Composable
private fun SectionHead(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = QimengDimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SCREEN_TITLE, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = SECTION_COUNT_TEMPLATE.format(count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 导入区（Web .upload-drop 虚线导入区卡化）：pending 复用禁点态并拦截点击（Web 同口径）。
 * internal：与 BackupScreen 的导入区同构件——同模块单一来源（卫生约束：第 2 次出现即抽共享），
 * 标题/提示文案由调用方注入，进行中态文案单一来源。
 */
@Composable
internal fun ImportDropZone(title: String, hint: String, importing: Boolean, onPick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        border = BorderStroke(DropZoneBorderWidth, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !importing, onClick = onPick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ScreenContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (importing) DROP_TITLE_IMPORTING else title,
                style = MaterialTheme.typography.titleSmall,
                color = if (importing) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = QimengDimens.SpaceS),
            )
        }
    }
}

/** 规则说明三条（Web rank-note 小字列表同位） */
@Composable
private fun RuleNotes() {
    Column(verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXS)) {
        RULE_LINES.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 片段行卡：文件名（空串=匿名导入）+ 行内「移除」（删除进行中全行禁用，Web 同口径） */
@Composable
private fun ImportedFileRow(displayName: String, removing: Boolean, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenContentPadding, vertical = QimengDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRemove, enabled = !removing) {
                Text(ROW_ACTION_REMOVE)
            }
        }
    }
}

/** 空态/加载提示（Web grid-empty 文案逐字） */
@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 拦截/结果横幅（点击关闭；LibraryManageScreen 同款件的模块内复用版——该文件私有
 * 不可见，第三处出现时应收口 core:ui，暂不在本批动既有文件）。
 * internal：BackupScreen 同模块跨文件复用。
 */
@Composable
internal fun StatusMessageCard(text: String, container: Color, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDismiss() },
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(QimengDimens.SpaceL),
        )
    }
}

/** SAF 显示名解析（OpenDocument 回调只给 uri；查询失败返回 null 让调用方兜底） */
private fun resolveDisplayName(
    context: android.content.Context,
    uri: Uri,
): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** SAF 读文件 → UTF-8 字符串（屏幕层平台胶水；失败返回 null。只支持 UTF-8，拍板口径） */
private suspend fun readUtf8(context: android.content.Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}

/**
 * 作者总表镜像配置卡（对齐 Web AuthorMirrorCard.tsx）：
 * path = 镜像文件绝对路径（留空=关闭）；fragmentFilename = 镜像目标片段（留空=最近导入片段）。
 * 保存成功即生效并尽力而为尝试一次落盘刷新。
 */
@Composable
private fun AuthorMirrorCard(
    mirrorPath: String,
    mirrorFragment: String,
    saving: Boolean,
    onPathChange: (String) -> Unit,
    onFragmentChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        ) {
            Text("作者总表镜像", style = MaterialTheme.typography.titleMedium)
            Text(
                "服务端把作者总表自动实时镜像到该本地 txt 文件（保存即生效）。每次为媒体添加或修改作者，该文件都会自动更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("镜像文件路径（绝对路径）", style = MaterialTheme.typography.labelMedium)
            QimengCapsuleTextField(
                value = mirrorPath,
                onValueChange = onPathChange,
                placeholder = "例如 /storage/emulated/0/Download/authors.txt",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "须为本地文件系统绝对路径；留空 = 关闭自动镜像",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("镜像目标片段（可选）", style = MaterialTheme.typography.labelMedium)
            QimengCapsuleTextField(
                value = mirrorFragment,
                onValueChange = onFragmentChange,
                placeholder = "留空 = 最近导入的片段",
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (saving) "保存中…" else "保存镜像配置")
            }
        }
    }
}

