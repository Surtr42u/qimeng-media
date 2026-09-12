package media.qimeng.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.launch
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.formatBytesHumanReadable

/** 我的页入口行文案（GUIDE_UI §我的页 + M4-2 既有入口 + M4-6 上传入口；
 *  F 批 2026-09-09：删除「作者管理」行；X5 批 2026-09-12：作者总览由内嵌卡改回
 *  收藏同款外部入口行（用户问题8），点击经壳层 onOpenAuthors 进全部作者页） */
private const val ROW_AUTHORS = "作者总览"
private const val ROW_FAVORITE = "收藏"
private const val ROW_HISTORY = "浏览历史"
private const val ROW_UPLOAD = "上传文件"
private const val ROW_THEME = "主题色彩"
private const val ROW_PREFS = "推荐偏好"

/**
 * 入口行副文案（I4 两行化，实录 mine.txt 逐字：收藏/浏览历史/主题色彩/推荐偏好；
 * 推荐偏好行当前预设名不再展示在行上——当前项高亮已在 BottomSheet 内，GUIDE_UI L255；
 * 原作者管理行副文案随行同批删除，F 批 2026-09-09。X5 批新增作者总览行副文案：
 * 入口行不预取数据，无「N 位作者」动态口径，用固定说明文字）。
 */
private const val SUBTITLE_AUTHORS = "查看全部作者与作品"
private const val SUBTITLE_FAVORITE = "查看收藏的图片和视频"
private const val SUBTITLE_HISTORY = "查看最近打开过的图片和视频"
private const val SUBTITLE_THEME = "跟随手机白天/深色模式自动切换"
private const val SUBTITLE_PREFS = "调整首页推荐算法的权重偏好"

/** 数量卡文案（I4，实录 mine.txt 两卡「图片 N」「视频 N」；数字未就绪/读失败显「—」） */
private const val COUNT_CARD_IMAGE = "图片"
private const val COUNT_CARD_VIDEO = "视频"
private const val COUNT_UNKNOWN = "—"

/** 分区标题与行副文案（展示语义，GUIDE_UI §我的页/设置页口径 + C5/C6 拍板） */
private const val SECTION_CACHE = "缓存"
private const val HINT_SERVER_URL = "媒体库与账号都归这台服务端管；更换地址请退出登录后重新登录"
private const val HINT_QUOTA = "重启应用后生效（缓存目录正在使用中，运行中扩缩容会损坏缓存）"
private const val VERSION_UNKNOWN = "未知"

/** 写失败横幅消除按钮文案（P2-3） */
private const val WRITE_ERROR_DISMISS = "知道了"

/** 浏览数据同步卡文案（任务L L5，最小 UI：一行卡片 + 两按钮 + 一次性提示） */
private const val EVENT_SYNC_TITLE = "浏览数据"
private const val EVENT_SYNC_PENDING_PREFIX = "待上传"
private const val EVENT_SYNC_PENDING_ZERO = "待上传 0 条"
private const val EVENT_SYNC_PENDING_UNKNOWN = "待上传 —"
private const val EVENT_SYNC_SUBTITLE = "断网时打点先存本机，联网自动补传；服务端按幂等键合并不重复计数"
private const val EVENT_SYNC_BUTTON_NOW = "立即同步"
private const val EVENT_SYNC_BUTTON_EXPORT = "导出未上传"
private const val EVENT_SYNC_EXPORT_FILE_NAME = "qimeng-pending-events.json"

/**
 * 「我的」Tab（M4-6 完整版，单页滚动列表，GUIDE_UI §我的页结构 + I4 复刻清偿）：
 * 标题 → 页首数量卡（I4：图片/视频两卡，实录页首即数量卡，GUIDE_UI L252）→
 * 资料卡（服务器地址展示，改地址=退出重登语义）→ 作者总览入口行（X5 批 2026-09-12：
 * 收藏同款外部入口行，用户问题8——点击经 [onOpenAuthors] 进全部作者页；
 * 原 G2 内嵌总览卡与 VM 总览管线随本批退役，本页不再预取作者数据）→
 * 入口行族（收藏/浏览历史 → 上传入口 → 主题色彩（不可点）→
 * 推荐偏好（BottomSheet 四预设整行应用/当前项高亮））→
 * 缓存区（LRU 档位 + 清空）→ 版本信息（服务端版本，C6）→ 退出登录。
 */
@Composable
fun SettingsScreen(
    onOpenFavorite: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenAuthors: () -> Unit = {},
    onOpenUpload: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ScreenPadding),
    ) {
        // Z2 批（2026-09-12 我的页字体色彩对齐旧版）：页标题 28sp Bold（旧 profile.xml）
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
            )
        }

        // 写操作失败反馈（P2-3）：横幅常驻直至点按消除，避免静默失败
        state.writeError?.let { message ->
            item { WriteErrorBanner(message = message, onDismiss = viewModel::dismissWriteError) }
        }

        // 页首数量卡（I4：旧版页首即数量卡，实录 mine.txt 两卡「图片 N」「视频 N」；
        // 位置在 ServerUrlCard 之前——ServerUrlCard 为新版资料卡，数量卡承接旧版页首语义）
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountCard(
                    title = COUNT_CARD_IMAGE,
                    count = state.imageCount,
                    modifier = Modifier.weight(1f),
                )
                CountCard(
                    title = COUNT_CARD_VIDEO,
                    count = state.videoCount,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 资料卡：服务器地址（单机形态预留点，ADR-0015；只展示不可改）
        item { ServerUrlCard(serverUrl = state.serverUrl) }

        // 作者总览入口行（X5 批 2026-09-12：内嵌卡改收藏同款外部入口行，用户问题8；
        // 点击=onOpenAuthors → 壳层 Routes.AUTHORS → AuthorScreen 全部作者页）
        item {
            EntryRow(
                label = ROW_AUTHORS,
                subtitle = SUBTITLE_AUTHORS,
                onClick = onOpenAuthors,
            )
        }

        // 入口行族（F 批 2026-09-09 起行序：收藏/浏览历史 → 上传入口 → 主题色彩（不可点）→
        // 推荐偏好；原「作者管理」行按用户拍板删除，作者管理页由作者总览行承担入口）
        item {
            EntryRow(
                label = ROW_FAVORITE,
                subtitle = SUBTITLE_FAVORITE,
                onClick = onOpenFavorite,
            )
        }
        item {
            EntryRow(
                label = ROW_HISTORY,
                subtitle = SUBTITLE_HISTORY,
                onClick = onOpenHistory,
            )
        }

        // 上传入口（M4-5 上传页接入设置页，M4-5 遗留项；
        // V8 #4：补副标题对齐其他入口行两行节奏——文案走 strings 资源 settings_upload_subtitle）
        item {
            EntryRow(
                label = ROW_UPLOAD,
                subtitle = stringResource(R.string.settings_upload_subtitle),
                onClick = onOpenUpload,
            )
        }

        // 主题色彩（I4，GUIDE_UI L253+L268：不可点击纯展示行，仅跟随系统明暗模式）
        item {
            EntryRow(
                label = ROW_THEME,
                subtitle = SUBTITLE_THEME,
                onClick = null,
            )
        }

        // 推荐偏好（C4 BottomSheet；I4 两行化：副文案实录逐字，当前预设高亮在 Sheet 内）
        item {
            EntryRow(
                label = ROW_PREFS,
                subtitle = SUBTITLE_PREFS,
                onClick = viewModel::openPrefsSheet,
            )
        }

        // 浏览数据同步（任务L L5：本地优先队列的手动入口 + 导出未上传，最小 UI）
        item {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult // 用户取消，非错误
                scope.launch {
                    val export = viewModel.exportPending()
                    // SAF 写文件是平台胶水（非业务逻辑，铁律 7 不涉）：VM 出数据、屏幕层落盘
                    val written = export != null && runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(export.json.toByteArray(Charsets.UTF_8))
                        } ?: throw IOException("openOutputStream 返回 null")
                    }.isSuccess
                    viewModel.onExported(if (written) export.count else null)
                }
            }
            EventSyncCard(
                pending = state.pendingEvents,
                syncing = state.eventSyncing,
                note = state.eventSyncNote,
                onSyncNow = viewModel::syncEventsNow,
                onExport = { exportLauncher.launch(EVENT_SYNC_EXPORT_FILE_NAME) },
                onDismissNote = viewModel::dismissEventSyncNote,
            )
        }

        // 缓存区（C5）
        item { SectionTitle(text = SECTION_CACHE) }
        item {
            QuotaCard(
                current = state.cacheQuota,
                sizeBytes = state.cacheSizeBytes,
                onSelectQuota = viewModel::setCacheQuota,
                onClear = viewModel::clearCache,
            )
        }

        // 版本信息（C6：服务端版本）
        item {
            EntryRow(
                label = "版本信息",
                detail = "服务端 ${state.serverVersion ?: VERSION_UNKNOWN}",
                onClick = null,
            )
        }

        item {
            Button(
                onClick = viewModel::logout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_logout))
            }
        }
    }

    if (state.prefsSheetOpen) {
        PrefsBottomSheet(
            appliedPreset = state.appliedPreset,
            applying = state.prefsApplying,
            onApply = viewModel::applyPreset,
            onDismiss = viewModel::closePrefsSheet,
        )
    }
}

/**
 * 页首数量卡（I4，GUIDE_UI L252 + 实录 mine.txt：标题在上、数字在下的两卡并排）。
 * count=null（未就绪或读失败）→ 数字位显「—」降级，不崩、不弹横幅。
 */
@Composable
private fun CountCard(title: String, count: Int?, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Z2 批：对齐旧版数量卡两行同 16sp Bold（数字不是大号字，照旧版）
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                text = count?.toString() ?: COUNT_UNKNOWN,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** 资料卡：服务器地址展示（改地址=退出重登语义，行不可点） */
@Composable
private fun ServerUrlCard(serverUrl: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = "服务器地址", style = MaterialTheme.typography.titleSmall)
            Text(
                text = serverUrl.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = HINT_SERVER_URL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 写操作失败横幅（P2-3）：errorContainer 底 + 点按消除；文案由 ViewModel 给出（中文、可重试指向） */
@Composable
private fun WriteErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(text = WRITE_ERROR_DISMISS) }
        }
    }
}

/**
 * 浏览数据同步卡（任务L L5，最小 UI）：标题 + 待上传计数副行 +「立即同步/导出未上传」
 * 两按钮 + 一次性结果提示（点按消除）。执行体全在 [media.qimeng.app.core.data.events.
 * ViewEventQueue]（三通道自动补传的同一队列），本卡只是手动触发口——设置页一行入口即
 * 可，不做大 UI（拍板口径）。
 */
@Composable
private fun EventSyncCard(
    pending: Int?,
    syncing: Boolean,
    note: String?,
    onSyncNow: () -> Unit,
    onExport: () -> Unit,
    onDismissNote: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = EVENT_SYNC_TITLE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    text = when {
                        pending == null -> EVENT_SYNC_PENDING_UNKNOWN
                        pending == 0 -> EVENT_SYNC_PENDING_ZERO
                        else -> "$EVENT_SYNC_PENDING_PREFIX $pending 条"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = EVENT_SYNC_SUBTITLE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !syncing, onClick = onSyncNow) {
                    Text(text = if (syncing) "同步中…" else EVENT_SYNC_BUTTON_NOW)
                }
                TextButton(onClick = onExport) { Text(text = EVENT_SYNC_BUTTON_EXPORT) }
            }
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onDismissNote),
                )
            }
        }
    }
}

/**
 * 入口行（浅面底；I4 两行化：label 标题 + subtitle 副文案两行结构，实录 mine.txt 逐字；
 * subtitle=null 保持单行；detail=右侧灰字（版本行等无副文案的旧形态行保留用）；
 * onClick=null 为纯展示行（主题色彩，GUIDE_UI L268）。
 */
@Composable
private fun EntryRow(
    label: String,
    subtitle: String? = null,
    detail: String? = null,
    onClick: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Z2 批：对齐旧版入口行两行同款 15sp 主色（onSurface 槽，显式指定——Surface
                // 的 contentColor 会把无色 Text 带成 onSurfaceVariant 灰，实测截图中过）
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 缓存卡：LRU 档位四选（写入 DataStore，重启生效）+ 清空按钮（清后容量归零核对） */
@Composable
private fun QuotaCard(
    current: DiskCacheQuota,
    sizeBytes: Long?,
    onSelectQuota: (DiskCacheQuota) -> Unit,
    onClear: () -> Unit,
) {
    var clearing by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "图片缓存上限", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    text = "已用 ${formatBytesHumanReadable(sizeBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiskCacheQuota.entries.forEach { quota ->
                    QimengSegPill(
                        text = quota.label,
                        selected = current == quota,
                        onClick = { onSelectQuota(quota) },
                    )
                }
            }
            Text(
                text = HINT_QUOTA,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                clearing = true
                onClear()
                clearing = false
            }) { Text(text = if (clearing) "清空中…" else "清空图片缓存") }
        }
    }
}

/**
 * 推荐偏好 BottomSheet（C4）：四预设整行点击应用、当前项高亮。
 * 预设→9 维映射在 :core:model（DOMAIN_RULES §1.3 预设表逐字）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrefsBottomSheet(
    appliedPreset: RecommendPreset?,
    applying: Boolean,
    onApply: (RecommendPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "推荐偏好", style = MaterialTheme.typography.titleMedium)
            RecommendPreset.entries.forEach { preset ->
                val applied = preset == appliedPreset
                Surface(
                    color = if (applied) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !applying) { onApply(preset) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (applied) {
                            Text(
                                text = "当前",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 缓存容量展示改走 :core:ui 共享 formatBytesHumanReadable（与统计页同源） */
