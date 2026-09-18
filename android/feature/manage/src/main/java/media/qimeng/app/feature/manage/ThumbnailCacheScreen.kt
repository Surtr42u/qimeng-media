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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.component.formatBytesHumanReadable
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 页面文案（2026-09-16 用户反馈新建页；2026-09-18 拆「服务器缩略图/本地缩略图」
 * 两分区并新增预取区块。进度文案口径「已生成 X / Y」沿用，预取口径「已缓存 X / Y」）。
 * 2026-09-18 用户拍板改名：分区标题「服务器缓存/本地缓存」明确缓存归属，
 * 服务器进度条文案明确为「已缓存 X / Y 个文件」。
 */
private const val SCREEN_TITLE = "缩略图缓存"

// —— 分区一：服务器缓存（服务端生成进度，GET /thumbnails/progress + 手动刷新） ——
private const val SERVER_SECTION = "服务器缓存"
private const val SERVER_SECTION_SUBTITLE =
    "由服务端生成并缓存，同一份图不会重复生成；新入库文件会在扫描后自动补齐"
private const val PROGRESS_TEMPLATE = "已缓存 %d / %d 个文件"
private const val PROGRESS_UNKNOWN = "已缓存 — / — 个文件"
private const val PROGRESS_REFRESH = "刷新"
private const val PROGRESS_REFRESHING = "刷新中…"

// —— 分区二：本地缓存（Coil 磁盘缓存档位/清空 + 预取） ——
private const val LOCAL_SECTION = "本地缓存"
private const val QUOTA_TITLE = "图片缓存上限"
private const val QUOTA_USED_TEMPLATE = "已用 %s"
private const val QUOTA_RESTART_NOTE = "重启应用后生效（缓存目录正在使用中，运行中扩缩容会损坏缓存）"
private const val QUOTA_CLEAR = "清空图片缓存"
private const val QUOTA_CLEARING = "清空中…"

/** 预取区块（状态/按钮文案；Running 进度条复用 LinearProgressIndicator） */
private const val PREFETCH_SECTION = "预取"
private const val PREFETCH_IDLE_HINT = "登录后自动预取全库缩略图，之后浏览直接读本地缓存"
private const val PREFETCH_RUNNING_TEMPLATE = "已缓存 %d / %d"
private const val PREFETCH_WAITING_HINT = "当前为计费网络，已暂停；切换到非计费网络后自动继续"
private const val PREFETCH_DONE_TEMPLATE = "本轮完成，已缓存 %d / %d"
private const val PREFETCH_DONE_EMPTY = "本轮完成：暂无可预取的缩略图"
private const val PREFETCH_START = "开始预取"
private const val PREFETCH_STOP = "停止"

/** 16dp：内容水平内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/** 8dp：行卡内元素纵向节奏（LibraryManageScreen RowInnerSpacing 同值，本地自持不跨文件引用） */
private val RowInnerSpacing = 8.dp

/** 12dp：行卡四向内边距（LibraryManageScreen CardInnerPadding 同值，本地自持不跨文件引用） */
private val CardInnerPadding = 12.dp

/**
 * 缩略图缓存页（2026-09-16 用户反馈新建；2026-09-18 拆两分区）：分区一「服务器缩略图」
 * = 服务端生成进度（原样搬入 + 分区标题）；分区二「本地缩略图」= Coil 磁盘缓存档位/
 * 清空（原样搬入）+ 预取区块（状态直读 ViewModel 透出的 [PrefetchUiState]）。
 * 视觉/交互基准 = 同模块 BackupScreen/LibraryManageScreen
 * （QimengTopBar + 16dp 竖滚列 + Card 卡）。业务全在 ViewModel/预取器（铁律 7）。
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
            state.writeError?.let { message ->
                StatusMessageCard(text = message, container = MaterialTheme.colorScheme.errorContainer) {
                    viewModel.dismissWriteError()
                }
            }

            // 分区一：服务器缓存（进度条 + 已缓存文件计数 + 刷新；progress=null 显「—」降级）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(CardInnerPadding),
                    verticalArrangement = Arrangement.spacedBy(RowInnerSpacing),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = SERVER_SECTION,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = !state.progressLoading, onClick = viewModel::refresh) {
                            Text(if (state.progressLoading) PROGRESS_REFRESHING else PROGRESS_REFRESH)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { state.progress?.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = when (val progress = state.progress) {
                            null -> PROGRESS_UNKNOWN
                            else -> PROGRESS_TEMPLATE.format(progress.thumbsOnDisk, progress.totalAssets)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = SERVER_SECTION_SUBTITLE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 分区二：本地缩略图（缓存上限/清空原样搬入 + 预取区块）
            LocalThumbnailsCard(
                state = state,
                prefetchState = prefetchState,
                onSelectQuota = viewModel::setQuota,
                onClear = viewModel::clearCache,
                onStartPrefetch = viewModel::startPrefetch,
                onStopPrefetch = viewModel::stopPrefetch,
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }
}

/**
 * 本地缩略图分区卡：上半 = 磁盘缓存 LRU 档位四选（写入 DataStore，重启生效）+ 清空按钮；
 * 下半 = 预取区块（状态行 + 进行中进度条 + 开始/停止按钮）。
 *
 * 【拷贝注沿革】原 2026-09-16 从 feature:settings SettingsCards.kt QuotaCard 整段拷入
 * （原为 settings 模块 internal，跨模块不可见；manage 自持复制而非引用，QuotaCard
 * 现为第二处消费方）。2026-09-18 拆分区时外壳由 Surface 归一为本页 Card 卡语言、
 * 并入分区二，档位/清空的文案与交互逐字保留（行为零变化）；后续出现第三处消费方时
 * 仍应上提 core:ui 收单源，禁止三处平行实现。
 */
@Composable
private fun LocalThumbnailsCard(
    state: ThumbnailCacheUiState,
    prefetchState: PrefetchUiState,
    onSelectQuota: (DiskCacheQuota) -> Unit,
    onClear: () -> Unit,
    onStartPrefetch: () -> Unit,
    onStopPrefetch: () -> Unit,
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

            // —— 图片缓存上限（QuotaCard 原款，见拷贝注沿革） ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = QUOTA_TITLE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    text = QUOTA_USED_TEMPLATE.format(formatBytesHumanReadable(state.cacheSizeBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiskCacheQuota.entries.forEach { quota ->
                    QimengSegPill(
                        text = quota.label,
                        selected = state.quota == quota,
                        onClick = { onSelectQuota(quota) },
                    )
                }
            }
            Text(
                text = QUOTA_RESTART_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                clearing = true
                onClear()
                clearing = false
            }) { Text(text = if (clearing) QUOTA_CLEARING else QUOTA_CLEAR) }

            // —— 预取区块（2026-09-18 新增） ——
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                val inFlight = prefetchState is PrefetchUiState.Running ||
                    prefetchState is PrefetchUiState.WaitingNetwork
                TextButton(onClick = if (inFlight) onStopPrefetch else onStartPrefetch) {
                    Text(text = if (inFlight) PREFETCH_STOP else PREFETCH_START)
                }
            }
        }
    }
}

/** 预取状态行文案（Failed 直接显预取器给的中文原因） */
private fun prefetchStatusText(state: PrefetchUiState): String = when (state) {
    PrefetchUiState.Idle -> PREFETCH_IDLE_HINT
    is PrefetchUiState.Running -> PREFETCH_RUNNING_TEMPLATE.format(state.done, state.total)
    PrefetchUiState.WaitingNetwork -> PREFETCH_WAITING_HINT
    is PrefetchUiState.Done ->
        if (state.total > 0) PREFETCH_DONE_TEMPLATE.format(state.done, state.total) else PREFETCH_DONE_EMPTY
    is PrefetchUiState.Failed -> state.reason
}
