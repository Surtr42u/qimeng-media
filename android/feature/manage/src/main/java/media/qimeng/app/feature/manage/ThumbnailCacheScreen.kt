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
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.component.formatBytesHumanReadable
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 页面文案（2026-09-16 用户反馈新建页；2026-09-18 拆两分区并新增预取区块；
 * 2026-09-19 批S4 重排冻结：两条目「本地缩略图缓存（上）/服务器缓存（下）」，
 * 行语言统一 [cacheInfoRow]（占用/文件数/缓存上限同款行组件）。
 * 口径（6477 vs 6341 排查结论）：服务器侧「占用」= 缓存目录落盘文件数，含多档
 * 尺寸与已删资产遗留，与「资产数」不同口径，拆行并列不再组 X/Y 分数式展示；
 * 服务端无缓存上限概念（DOMAIN_RULES §11 永不因上限删除有效缓存），按既有口径
 * 显示「不限」。预取为纯默认自动行为，手动「开始预取/停止」按钮删除（同批拍板），
 * 仅保留状态行展示。
 */
private const val SCREEN_TITLE = "缩略图缓存"

// —— 条目一：本地缩略图缓存（本机 Coil 磁盘缓存：占用/文件数 + 上限档位/清空 + 预取状态） ——
private const val LOCAL_SECTION = "本地缩略图缓存"
private const val ROW_OCCUPIED = "占用"
private const val ROW_FILE_COUNT = "文件数"
private const val ROW_ASSETS = "资产数"
private const val ROW_CACHE_LIMIT = "缓存上限"
private const val LOCAL_OCCUPIED_TEMPLATE = "已用 %s"
private const val VALUE_UNKNOWN = "—"
private const val FILE_COUNT_TEMPLATE = "%d 个"
private const val QUOTA_TITLE = "图片缓存上限"
private const val QUOTA_RESTART_NOTE = "重启应用后生效（缓存目录正在使用中，运行中扩缩容会损坏缓存）"
private const val QUOTA_CLEAR = "清空图片缓存"
private const val QUOTA_CLEARING = "清空中…"

/** 预取状态区（自动行为的状态展示；Running 进度条复用 LinearProgressIndicator） */
private const val PREFETCH_SECTION = "预取"
private const val PREFETCH_IDLE_HINT = "登录后自动预取全库缩略图，之后浏览直接读本地缓存"
private const val PREFETCH_RUNNING_TEMPLATE = "已缓存 %d / %d"
private const val PREFETCH_WAITING_HINT = "当前为计费网络，已暂停；切换到非计费网络后自动继续"
private const val PREFETCH_DONE_TEMPLATE = "本轮完成，已缓存 %d / %d"
private const val PREFETCH_DONE_EMPTY = "本轮完成：暂无可预取的缩略图"

// —— 条目二：服务器缓存（服务端生成进度 GET /thumbnails/progress + 占用/上限行 + 手动刷新） ——
private const val SERVER_SECTION = "服务器缓存"
private const val SERVER_OCCUPIED_TEMPLATE = "%d 个文件"
private const val SERVER_ASSETS_TEMPLATE = "%d 个"
private const val SERVER_LIMIT_UNLIMITED = "不限"
private const val SERVER_SECTION_SUBTITLE =
    "占用为缓存目录文件数（含多档尺寸与已删资产遗留），与资产数口径不同；" +
        "服务端永不因上限删除有效缓存，网格档（md）生成完成后每资产一份"
private const val PROGRESS_REFRESH = "刷新"
private const val PROGRESS_REFRESHING = "刷新中…"

/** 16dp：内容水平内边距（上传子页同档） */
private val ScreenContentPadding = 16.dp

/** 24dp：滚动列尾部垫高（LibraryManageScreen 同值） */
private val ScreenBottomSpacing = 24.dp

/** 8dp：行卡内元素纵向节奏（LibraryManageScreen RowInnerSpacing 同值，本地自持不跨文件引用） */
private val RowInnerSpacing = 8.dp

/** 12dp：行卡四向内边距（LibraryManageScreen CardInnerPadding 同值，本地自持不跨文件引用） */
private val CardInnerPadding = 12.dp

/**
 * 缩略图缓存页（2026-09-16 用户反馈新建；2026-09-19 批S4 重排冻结）：条目一
 * 「本地缩略图缓存」= 本机 Coil 磁盘缓存（占用/文件数行 + 档位/清空 + 预取状态行）；
 * 条目二「服务器缓存」= 服务端生成进度（占用/资产/缓存上限行 + 进度条 + 刷新）。
 * 顺序本地在上、服务器在下；两卡行语言统一 [cacheInfoRow]。视觉/交互基准 =
 * 同模块 BackupScreen/LibraryManageScreen（QimengTopBar + 16dp 竖滚列 + Card 卡）。
 * 业务全在 ViewModel/预取器（铁律 7）。
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

            // 条目一：本地缩略图缓存（本机 Coil 磁盘缓存）
            LocalThumbnailsCard(
                state = state,
                prefetchState = prefetchState,
                onSelectQuota = viewModel::setQuota,
                onClear = viewModel::clearCache,
            )

            // 条目二：服务器缓存（progress=null 显「—」降级）
            ServerCacheCard(
                state = state,
                onRefresh = viewModel::refresh,
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }
}

/** 两卡共用的信息行（批S4 冻结「样式一致/复用同款行组件」）：左标签右值 */
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

/** 缓存条目数为 null 时的占位值（读失败/未就绪降级，与进度「—」同口径） */
private fun countValue(count: Int?): String =
    if (count == null) VALUE_UNKNOWN else FILE_COUNT_TEMPLATE.format(count)

/**
 * 本地缩略图缓存条目卡：占用/文件数两行（本机 Coil 磁盘缓存口径）+ 图片缓存上限
 * 档位四选（写入 DataStore，重启生效）+ 清空按钮 + 预取状态区（自动行为展示，无按钮）。
 *
 * 【拷贝注沿革】档位/清空原 2026-09-16 从 feature:settings SettingsCards.kt QuotaCard
 * 整段拷入（原为 settings 模块 internal，跨模块不可见；manage 自持复制而非引用，
 * QuotaCard 现为第二处消费方）。2026-09-19 批S4 重排：外壳维持本页 Card 卡语言，
 * 新增占用/文件数行与预取状态区；档位/清空的文案与交互逐字保留（行为零变化）；
 * 后续出现第三处消费方时仍应上提 core:ui 收单源，禁止三处平行实现。
 */
@Composable
private fun LocalThumbnailsCard(
    state: ThumbnailCacheUiState,
    prefetchState: PrefetchUiState,
    onSelectQuota: (DiskCacheQuota) -> Unit,
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

            // —— 占用 / 文件数（批S4 新增行；本机 Coil 磁盘缓存口径） ——
            cacheInfoRow(
                label = ROW_OCCUPIED,
                value = state.cacheSizeBytes
                    ?.let { LOCAL_OCCUPIED_TEMPLATE.format(formatBytesHumanReadable(it)) }
                    ?: VALUE_UNKNOWN,
            )
            cacheInfoRow(label = ROW_FILE_COUNT, value = countValue(state.cacheFileCount))

            // —— 图片缓存上限（QuotaCard 原款，见拷贝注沿革） ——
            Text(text = QUOTA_TITLE, style = MaterialTheme.typography.bodyMedium)
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

            // —— 预取状态区（2026-09-18 新增；批S4 删手动启停按钮，仅状态展示） ——
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
        }
    }
}

/**
 * 服务器缓存条目卡（原「服务器缓存」条目保留 + 批S4 增占用/缓存上限行）：
 * 占用 = 服务端缓存目录落盘文件数（thumbsOnDisk），资产数 = 启用库资产总数
 * （totalAssets），两口径拆行并列；缓存上限按服务端既有口径显示「不限」。
 * 进度条沿用覆盖率（fraction 已在 :core:model 钳制 0~1，孤儿/多档致分子>分母时
 * 视觉饱和不越界）。
 */
@Composable
private fun ServerCacheCard(
    state: ThumbnailCacheUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
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
                TextButton(enabled = !state.progressLoading, onClick = onRefresh) {
                    Text(if (state.progressLoading) PROGRESS_REFRESHING else PROGRESS_REFRESH)
                }
            }
            LinearProgressIndicator(
                progress = { state.progress?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            cacheInfoRow(
                label = ROW_OCCUPIED,
                value = state.progress
                    ?.let { SERVER_OCCUPIED_TEMPLATE.format(it.thumbsOnDisk) }
                    ?: VALUE_UNKNOWN,
            )
            cacheInfoRow(
                label = ROW_ASSETS,
                value = state.progress
                    ?.let { SERVER_ASSETS_TEMPLATE.format(it.totalAssets) }
                    ?: VALUE_UNKNOWN,
            )
            cacheInfoRow(label = ROW_CACHE_LIMIT, value = SERVER_LIMIT_UNLIMITED)
            Text(
                text = SERVER_SECTION_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
