package media.qimeng.app.feature.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.LibraryScanState
import media.qimeng.app.core.model.LibrarySummary
import media.qimeng.app.core.model.displayLabel
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（对齐 Web 文件管理页库表区块：媒体库 N 个库/空态提示/行操作两钮/删除确认弹窗） */
private const val SCREEN_TITLE = "库管理"
private const val LIBRARIES_SECTION = "媒体库"
private const val LIBRARIES_COUNT_TEMPLATE = "%d 个库"
private const val LIBRARIES_EMPTY = "还没有库，先在下方注册一个媒体文件夹。"
private const val ROW_ACTION_RESCAN = "重新扫描"
private const val ROW_ACTION_DELETE = "删除"
private const val ROW_FILE_COUNT_TEMPLATE = "%d 个文件"
private const val DELETE_TITLE_TEMPLATE = "删除库「%s」？"

/** 删除确认正文（Web ConfirmDialog description 逐字：索引清除、磁盘文件不动） */
private const val DELETE_BODY_TEMPLATE = "该库 %d 个文件的索引将被清除，磁盘文件不受影响。"
private const val DELETE_CONFIRM = "删除"
private const val DIALOG_CANCEL = "取消"

/** 24dp：页级加载进度件上距（上传子页 LoadingIndicator padding 同值） */
private val ContentLoadingPadding = 24.dp

/** 16dp：内容水平内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（末卡不贴底缘，上传子页同值） */
private val ScreenBottomSpacing = 24.dp

/** 8dp：行卡内元素纵向节奏 */
private val RowInnerSpacing = 8.dp

/** 12dp：行卡/横幅四向内边距（上传子页 MessageCard padding 同档） */
private val CardInnerPadding = 12.dp

/** 徽标内边距（dp）：kind 徽标水平 10 / 垂直 2——labelMedium 12sp 胶囊的最小可读密度 */
private val KindBadgePaddingHorizontal = 10.dp
private val KindBadgePaddingVertical = 2.dp

/**
 * 库管理页（U10-6）：库表（名称/kind 徽标/路径/文件数/scanState/启用 Switch/重扫/删除）
 * + 注册新库表单卡。信息架构基准 = Web 文件管理页（LibraryManagePage.tsx），视觉/交互
 * 基准 = App 上传子页。业务全在 ViewModel（铁律 7）。
 *
 * scanState 刷新策略（最小实现）：SSE 未接，页面 ON_START 重查（DisposableEffect 观察
 * 生命周期事件）+ 每个写操作成功后 refresh()；实时进度走 SSE 列入后续（U10-6 记档）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryManageScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryManageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ON_START 重查（扫描中离开再回页时 scanState 拉平；机制见本函数 KDoc 刷新策略）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        QimengTopBar(title = SCREEN_TITLE, onBack = onBack)

        if (state.loading) {
            // V6/V8 口径同上传页：页级加载无并排文字，组件默认 48dp 档
            LoadingIndicator(modifier = Modifier.padding(ContentLoadingPadding))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenContentPadding)
                .imePadding(),
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

            SectionHead(count = state.libraries.size)
            if (!state.loading && state.libraries.isEmpty()) {
                EmptyText(LIBRARIES_EMPTY)
            }
            state.libraries.forEach { library ->
                LibraryRow(
                    library = library,
                    onRescan = { viewModel.rescan(library) },
                    onRequestDelete = { viewModel.requestDelete(library) },
                    onToggleEnabled = { viewModel.setEnabled(library, it) },
                )
            }

            RegisterLibraryCard(
                name = state.formName,
                rootPath = state.formRootPath,
                kind = state.formKind,
                registering = state.registering,
                canSubmit = state.canSubmitRegister,
                onNameChange = viewModel::updateFormName,
                onRootPathChange = viewModel::updateFormRootPath,
                onKindChange = viewModel::updateFormKind,
                onSubmit = viewModel::registerAndScan,
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }

    // 删除二次确认（非 danger：索引清除、磁盘文件不动——Web W-2 同语义）
    state.deleteTarget?.let { target ->
        DeleteConfirmDialog(
            target = target,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

/** 库表区块头：区块名 + 「N 个库」计数（Web rank-head/rank-note 同位） */
@Composable
private fun SectionHead(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = QimengDimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(LIBRARIES_SECTION, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = LIBRARIES_COUNT_TEMPLATE.format(count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 空态提示（Web grid-empty 文案逐字） */
@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 库行卡（Web 库表一行的卡化）：名称 + kind 徽标 / 路径小字（可换行，Web 路径列同义）/
 * scanState + 文件数 / 启用 Switch + 重扫/删除两操作。文案映射单源在 core:model
 * （displayLabel，与 Web 双写同步）。
 */
@Composable
private fun LibraryRow(
    library: LibrarySummary,
    onRescan: () -> Unit,
    onRequestDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(RowInnerSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                KindBadge(label = library.kind.displayLabel)
            }
            Text(
                text = library.rootPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = library.scanState.displayLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = scanStateColor(library.scanState),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = ROW_FILE_COUNT_TEMPLATE.format(library.fileCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = library.enabled, onCheckedChange = onToggleEnabled)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRescan) { Text(ROW_ACTION_RESCAN) }
                TextButton(onClick = onRequestDelete) { Text(ROW_ACTION_DELETE) }
            }
        }
    }
}

/**
 * scanState → 颜色语义槽（Composable：MaterialTheme 只许组合期读取）。
 * 扫描中=主色、异常=error、空闲=次级灰——Web 状态列的分档语义，无硬编码色值。
 */
@Composable
private fun scanStateColor(state: LibraryScanState): Color = when (state) {
    LibraryScanState.SCANNING -> MaterialTheme.colorScheme.primary
    LibraryScanState.ERROR -> MaterialTheme.colorScheme.error
    LibraryScanState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 库类型徽标（Web「类型」列卡化）：胶囊软底小徽标，文案走 core:model displayLabel
 * （cos=COS、其余=常规，Web 行内三元同口径）。
 */
@Composable
private fun KindBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = KindBadgePaddingHorizontal,
                vertical = KindBadgePaddingVertical,
            ),
        )
    }
}

/**
 * 删除二次确认弹窗（Web ConfirmDialog 同文案语义：标题带库名、正文带文件数与
 * 「索引清除/磁盘文件不动」承诺）。
 */
@Composable
private fun DeleteConfirmDialog(
    target: LibrarySummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DELETE_TITLE_TEMPLATE.format(target.name)) },
        text = { Text(DELETE_BODY_TEMPLATE.format(target.fileCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(DELETE_CONFIRM) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(DIALOG_CANCEL) }
        },
    )
}
