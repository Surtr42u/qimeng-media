package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.data.prefetch.PrefetchUiState
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.component.formatBytesHumanReadable
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 页面文案（2026-09-16 新建页；批S4 两口径重排；**批S5 2026-09-19 按端分池冻结**）：
 * 缩略图缓存 = 手机 App 侧缓存，按连接来源分两池两条目——「服务器缓存」（连 NAS 时
 * 缓存的图，NAS 池）在上、「本地缓存」（连本地端时缓存的图，本地池）在下，两卡 UI 一致
 * （照搬原服务器卡条目样式）：文件数 + 实际占用 + 清空；服务器卡额外保留预取状态区
 * （预取 = 自动从服务器下载缩略图进 NAS 池，纯默认行为无按钮）。原「缓存上限」行拍板
 * 删除改为显示实际占用（字节），档位四选与「资产数/服务端生成进度」行随两卡口径重写
 * 一并退场。文件数口径（6477 vs 6341 排查结论沿用）：落盘数据文件数，同一资产多档
 * 尺寸各计一份，与资产数不同口径。
 */
private const val SCREEN_TITLE = "缩略图缓存"

// —— 条目一：服务器缓存（NAS 池：文件数/实际占用 + 预取状态 + 清空） ——
private const val SERVER_SECTION = "服务器缓存"
private const val SERVER_CLEAR = "清空服务器缓存"

// —— 条目二：本地缓存（本地池：文件数/实际占用 + 清空） ——
private const val LOCAL_SECTION = "本地缓存"
private const val LOCAL_CLEAR = "清空本地缓存"

// —— 两卡共用行语言 ——
private const val ROW_FILE_COUNT = "文件数"
private const val ROW_OCCUPIED = "实际占用"
private const val VALUE_UNKNOWN = "—"
private const val FILE_COUNT_TEMPLATE = "%d 个"
private const val CLEARING = "清空中…"

/** 口径说明（批S5 分池语义：分池存放防串图 + 文件数含多档尺寸的口径注记） */
private const val SERVER_SECTION_SUBTITLE =
    "连接服务器（NAS）时缓存的缩略图。文件数为缓存落盘数据文件数" +
        "（同一资产多档尺寸各计一份）；与本地缓存分池存放、互不共享，防止两端库内容不同导致串图"
private const val LOCAL_SECTION_SUBTITLE =
    "连接本地端（手机内嵌服务端，端口 ${ServerAddress.LOCAL_MODE_PORT}）时缓存的缩略图。" +
        "与服务器缓存分池存放、互不共享，防止两端库内容不同导致串图"

/** 预取状态区（自动行为的状态展示；Running 进度条复用 LinearProgressIndicator） */
private const val PREFETCH_SECTION = "预取"
private const val PREFETCH_IDLE_HINT = "登录后自动预取全库缩略图，之后浏览直接读本地缓存"
private const val PREFETCH_RUNNING_TEMPLATE = "已缓存 %1\$d / %2\$d（%3\$d%%）"
private const val PREFETCH_WAITING_HINT = "当前为计费网络，已暂停；切换到非计费网络后自动继续"
private const val PREFETCH_DONE_TEMPLATE = "本轮完成，已缓存 %d / %d"
private const val PREFETCH_DONE_EMPTY = "本轮完成：暂无可预取的缩略图"
private const val PREFETCH_SKIPPED_HINT = "缓存已是最新，本轮无需同步"

/** 16dp：内容水平内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/** 8dp：行卡内元素纵向节奏（LibraryManageScreen RowInnerSpacing 同值，本地自持不跨文件引用） */
private val RowInnerSpacing = 8.dp

/** 12dp：行卡四向内边距（LibraryManageScreen CardInnerPadding 同值，本地自持不跨文件引用） */
private val CardInnerPadding = 12.dp

/**
 * 缩略图缓存页（批S5 分池冻结）：条目一「服务器缓存」= NAS 池（文件数/实际占用行 +
 * 预取状态区 + 清空）；条目二「本地缓存」= 本地池（同样字段 + 清空）。两卡行语言统一
 * [cacheInfoRow]。视觉/交互基准 = 同模块 BackupScreen/LibraryManageScreen
 * （QimengTopBar + 16dp 竖滚列 + Card 卡）。业务全在 ViewModel（铁律 7）。
 */
@Composable
fun ThumbnailCacheScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThumbnailCacheViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefetchState by viewModel.prefetchState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        QimengTopBar(title = SCREEN_TITLE, onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceL),
        ) {
            // 条目一：服务器缓存（NAS 池；预取进度条保留在本卡——预取热身的就是 NAS 池）
            ServerCacheCard(
                state = state,
                prefetchState = prefetchState,
                onClear = viewModel::clearNasCache,
            )

            // 条目二：本地缓存（本地池）
            LocalCacheCard(
                state = state,
                onClear = viewModel::clearLocalCache,
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }
}

/** 两卡共用的信息行（批S4 冻结「样式一致/复用同款行组件」，批S5 沿用）：左标签右值 */
@Composable
private fun cacheInfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 缓存条目数为 null 时的占位值（读失败/未就绪降级，与原进度「—」同口径） */
private fun countValue(count: Int?): String =
    if (count == null) VALUE_UNKNOWN else FILE_COUNT_TEMPLATE.format(count)

/** 实际占用字节为 null 时的占位值（读失败/未就绪降级） */
private fun occupiedValue(bytes: Long?): String =
    bytes?.let(::formatBytesHumanReadable) ?: VALUE_UNKNOWN

/**
 * 服务器缓存条目卡（NAS 池，批S5）：文件数/实际占用两行 + 预取状态区（自动行为展示，
 * 无按钮——预取热身的正是 NAS 池，故保留在本卡）+ 清空按钮（只清本池）。
 */
@Composable
private fun ServerCacheCard(
    state: ThumbnailCacheUiState,
    prefetchState: PrefetchUiState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearing by remember { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(RowInnerSpacing),
        ) {
            Text(text = SERVER_SECTION, style = MaterialTheme.typography.titleSmall)

            cacheInfoRow(label = ROW_FILE_COUNT, value = countValue(state.nasFileCount))
            cacheInfoRow(label = ROW_OCCUPIED, value = occupiedValue(state.nasSizeBytes))

            // —— 预取状态区（自动行为展示，无按钮；批S5 自原本地卡移入——热身对象是 NAS 池） ——
            Text(text = PREFETCH_SECTION, style = MaterialTheme.typography.titleSmall)
            Text(
                text = prefetchStatusText(prefetchState),
                style = MaterialTheme.typography.bodyMedium,
                color = if (prefetchState is PrefetchUiState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            val running = prefetchState as? PrefetchUiState.Running
            if (running != null && running.total > 0) {
                LinearProgressIndicator(
                    progress = { running.done.toFloat() / running.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TextButton(onClick = {
                clearing = true
                onClear()
                clearing = false
            }) { Text(text = if (clearing) CLEARING else SERVER_CLEAR) }

            Text(
                text = SERVER_SECTION_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 本地缓存条目卡（本地池，批S5）：文件数/实际占用两行 + 清空按钮（只清本池） */
@Composable
private fun LocalCacheCard(
    state: ThumbnailCacheUiState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearing by remember { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(RowInnerSpacing),
        ) {
            Text(text = LOCAL_SECTION, style = MaterialTheme.typography.titleSmall)

            cacheInfoRow(label = ROW_FILE_COUNT, value = countValue(state.localFileCount))
            cacheInfoRow(label = ROW_OCCUPIED, value = occupiedValue(state.localSizeBytes))

            TextButton(onClick = {
                clearing = true
                onClear()
                clearing = false
            }) { Text(text = if (clearing) CLEARING else LOCAL_CLEAR) }

            Text(
                text = LOCAL_SECTION_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 预取状态行文案（Failed 直接显预取器给的中文原因；Running 带实时百分比） */
private fun prefetchStatusText(state: PrefetchUiState): String = when (state) {
    PrefetchUiState.Idle -> PREFETCH_IDLE_HINT
    is PrefetchUiState.Running -> {
        val percent = if (state.total > 0) state.done * 100 / state.total else 0
        PREFETCH_RUNNING_TEMPLATE.format(state.done, state.total, percent)
    }
    PrefetchUiState.WaitingNetwork -> PREFETCH_WAITING_HINT
    is PrefetchUiState.Done ->
        if (state.total > 0) PREFETCH_DONE_TEMPLATE.format(state.done, state.total) else PREFETCH_DONE_EMPTY
    PrefetchUiState.Skipped -> PREFETCH_SKIPPED_HINT
    is PrefetchUiState.Failed -> state.reason
}
