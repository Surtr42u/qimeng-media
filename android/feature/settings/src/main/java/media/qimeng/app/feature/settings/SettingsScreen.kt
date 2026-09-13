package media.qimeng.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.launch
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QmTouchProbe
import media.qimeng.app.core.ui.component.formatBytesHumanReadable
import media.qimeng.app.core.ui.component.qmTouchProbe
import media.qimeng.app.core.ui.theme.QimengDimens

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

/** 本机模式快捷入口（任务T T3，ADR-0015）：改地址=退出重登语义的快捷化；预设地址值单源
 *  在 core ServerAddress.LOCAL_MODE_PRESET，副文案插值引用不抄字面量（故不可 const） */
private const val ROW_LOCAL_MODE = "本机模式"
private val SUBTITLE_LOCAL_MODE = "服务端跑在本机时点此切换：退出登录并预填 ${ServerAddress.LOCAL_MODE_PRESET}，在登录页确认后生效"

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

// ---------- 我的页视觉复刻旧版尺寸（2026-09-13 用户反馈「我的界面的 ui 也要和旧版一致」；
// 逐段实录旧仓库运行时代码规格，口径见各常量注释；可复用档位一律引用 QimengDimens 既有
// token（同值多源共用一档），仅旧版独有尺寸在此登记） ----------

/** 20dp：页面内容左右内边距（旧版我的页运行时内容 padding=20dp；区别于 all_files 页 16dp 档） */
private val ScreenContentPadding = 20.dp

/** 24dp：页标题「我的」与数量卡间距（旧版运行时标题下 24dp 处排卡） */
private val TitleToCardsSpacing = 24.dp

/** 16dp：首个入口行上距（旧版运行时行区首行 marginTop 16dp；行下距用 QimengDimens.SpaceL 12dp） */
private val FirstRowTopSpacing = 16.dp

/** 96dp：页首数量卡高（旧版运行时两卡等宽等高 96dp） */
private val CountCardHeight = 96.dp

/** 20dp：数量卡圆角（旧版运行时；与统计页 bg_stat_card 20dp 圆角同档异源，不并档——
 *  统计页档位在 feature/stats 私有，本页不跨 feature 引用） */
private val CountCardCornerRadius = 20.dp

// ---------- 推荐偏好 BottomSheet 尺寸（旧版运行时规格逐段实录） ----------

/** Sheet 容器 padding（旧版运行时 padding(20,18,20,28) 逐段：左右 20 / 上 18 / 下 28） */
private val PrefsSheetPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 28.dp)

/** 28dp：Sheet 顶部圆角与预设行圆角（旧版运行时同一档，容器与行同形） */
private val PrefsSheetCornerRadius = 28.dp

/** 10dp：Sheet 标题下间距（旧版运行时标题 paddingBottom 10dp，同 QimengDimens.FilterTitleBottomPadding 档语义） */
private val PrefsTitleBottomSpacing = 10.dp

/** 2dp：预设描述与名称行间距（旧版运行时描述 topPadding 2dp） */
private val PresetDescTopSpacing = 2.dp

/**
 * 推荐偏好 Sheet 文案（旧版运行时逐字；预设名在 :core:model RecommendPreset.label 单源，
 * 描述属 UI 展示层文案，四档 when 单点映射——core:model 禁加 UI 文案）。
 */
private const val PREFS_SHEET_SUBTITLE = "选择预设方案快速调整推荐策略"
private const val PREFS_GROUP_LABEL = "预设方案"
private const val PREFS_LOAD_FAILED = "偏好状态加载失败"
private const val PREFS_RETRY = "重试"
private const val PREFS_LOADING = "加载中…"

/** 预设描述逐字（旧版运行时；「均衡推荐」描述=「默认权重」） */
private fun presetDescription(preset: RecommendPreset): String = when (preset) {
    RecommendPreset.BALANCED -> "默认权重"
    RecommendPreset.MEMORY_POPULAR -> "强化浏览时效和互动热度，重温常看内容"
    RecommendPreset.DEEP_EXPLORATION -> "强化标签相关性和新发现，挖掘冷门内容"
    RecommendPreset.FRESH_FIRST -> "强化新鲜度和发现，优先展示最新入库内容"
}

/**
 * 「我的」Tab（M4-6 完整版，单页滚动列表，GUIDE_UI §我的页结构 + I4 复刻清偿；
 * 2026-09-13 视觉复刻批：整页对齐旧版运行时——数量卡 96dp/20dp 圆角纯白 surface、
 * 入口行 QimengProfileRow 规格（72dp 高/18dp 水平 padding/16dp 圆角纯白卡/单块两行
 * 15sp 主文字色）、推荐偏好 Sheet 旧版规格（28dp 圆角/描边选中态/四预设恒渲染 +
 * 加载失败重试态））：
 * 标题 → 页首数量卡（I4：图片/视频两卡）→ 资料卡（服务器地址展示，改地址=退出重登语义）→
 * 入口行族（本机模式 → 作者总览 → 收藏/浏览历史 → 上传入口 → 主题色彩（不可点）→
 * 推荐偏好（BottomSheet 四预设整行应用/当前项高亮））→ 浏览数据同步卡 →
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
            // 旧版页面底色 = qm_bg（background 槽，浅 #FAFAFA / 夜 #1A1A1A）；壳层已涂底，
            // 显式声明防宿主容器换底后页面漏色
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = ScreenContentPadding)
            // U7 触摸诊断桩 QM_TOUCH（根因定位后撤除）：我的根探针（观察不消费，跨页对照）
            .qmTouchProbe("SETTINGS_ROOT"),
        // U7 诊断桩结束
    ) {
        // Z2 批（2026-09-12 我的页字体色彩对齐旧版）：页标题 28sp Bold（旧 profile.xml）
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                // 旧版标题下 24dp 处排数量卡
                modifier = Modifier.padding(bottom = TitleToCardsSpacing),
            )
        }

        // 写操作失败反馈（P2-3）：横幅常驻直至点按消除，避免静默失败
        state.writeError?.let { message ->
            item {
                WriteErrorBanner(
                    message = message,
                    onDismiss = viewModel::dismissWriteError,
                    modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
                )
            }
        }

        // 页首数量卡（I4：旧版页首即数量卡，实录 mine.txt 两卡「图片 N」「视频 N」；
        // 视觉复刻批：96dp 高/20dp 圆角/纯白 surface 底/两行「图片\nN」16sp Bold 居中，
        // 数据源 GET /stats/overview imageCount/videoCount，失败显「—」）
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM)) {
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

        // 资料卡：服务器地址（单机形态预留点，ADR-0015；只展示不可改）。
        // 不带 bottom：行区首行上距 16dp（FirstRowTopSpacing）按规格原值生效，不叠出双倍间距
        item {
            ServerUrlCard(serverUrl = state.serverUrl, modifier = Modifier.padding(top = QimengDimens.SpaceL))
        }

        // 本机模式快捷入口（任务T T3，ADR-0015 单点预留兑现；只加入口行不加页面——
        // 点按=退出登录+预填本机预设地址，登录页带出确认后走既有登录流程）
        item {
            EntryRow(
                label = ROW_LOCAL_MODE,
                subtitle = SUBTITLE_LOCAL_MODE,
                onClick = viewModel::fillLocalModeForNextLogin,
                // 行区首行上距 16dp（旧版运行时规格）
                modifier = Modifier.padding(top = FirstRowTopSpacing, bottom = QimengDimens.SpaceL),
            )
        }

        // 作者总览入口行（X5 批 2026-09-12：内嵌卡改收藏同款外部入口行，用户问题8；
        // 点击=onOpenAuthors → 壳层 Routes.AUTHORS → AuthorScreen 全部作者页）
        item {
            EntryRow(
                label = ROW_AUTHORS,
                subtitle = SUBTITLE_AUTHORS,
                onClick = onOpenAuthors,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }

        // 入口行族（F 批 2026-09-09 起行序：收藏/浏览历史 → 上传入口 → 主题色彩（不可点）→
        // 推荐偏好；原「作者管理」行按用户拍板删除，作者管理页由作者总览行承担入口）
        item {
            EntryRow(
                label = ROW_FAVORITE,
                subtitle = SUBTITLE_FAVORITE,
                onClick = onOpenFavorite,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }
        item {
            EntryRow(
                label = ROW_HISTORY,
                subtitle = SUBTITLE_HISTORY,
                onClick = onOpenHistory,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }

        // 上传入口（M4-5 上传页接入设置页，M4-5 遗留项；
        // V8 #4：补副标题对齐其他入口行两行节奏——文案走 strings 资源 settings_upload_subtitle）
        item {
            EntryRow(
                label = ROW_UPLOAD,
                subtitle = stringResource(R.string.settings_upload_subtitle),
                onClick = onOpenUpload,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }

        // 主题色彩（I4，GUIDE_UI L253+L268：不可点击纯展示行，仅跟随系统明暗模式）
        item {
            EntryRow(
                label = ROW_THEME,
                subtitle = SUBTITLE_THEME,
                onClick = null,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }

        // 推荐偏好（C4 BottomSheet；I4 两行化：副文案实录逐字，当前预设高亮在 Sheet 内；
        // 2026-09-13 修复「点击无反应」：Sheet 显式持有 sheetState + 预设四行恒渲染，
        // 偏好状态加载失败进「加载失败/重试」态而非空壳）
        item {
            EntryRow(
                label = ROW_PREFS,
                subtitle = SUBTITLE_PREFS,
                onClick = viewModel::openPrefsSheet,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
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
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }

        // 缓存区（C5）
        item {
            SectionTitle(
                text = SECTION_CACHE,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceM),
            )
        }
        item {
            QuotaCard(
                current = state.cacheQuota,
                sizeBytes = state.cacheSizeBytes,
                onSelectQuota = viewModel::setCacheQuota,
                onClear = viewModel::clearCache,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }

        // 版本信息（C6：服务端版本）
        item {
            EntryRow(
                label = "版本信息",
                detail = "服务端 ${state.serverVersion ?: VERSION_UNKNOWN}",
                onClick = null,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
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

    // Sheet 开关只看 prefsSheetOpen——预设四行本身不依赖网络，prefsValues 加载失败
    // 也在 Sheet 内给「加载失败/重试」态，绝不因数据未就绪而点行无响应
    if (state.prefsSheetOpen) {
        PrefsBottomSheet(
            appliedPreset = state.appliedPreset,
            applying = state.prefsApplying,
            loading = state.prefsLoading,
            loadFailed = state.prefsLoadFailed,
            onRetryLoad = viewModel::retryLoadPrefs,
            onApply = viewModel::applyPreset,
            onDismiss = viewModel::closePrefsSheet,
        )
    }
}

/**
 * 页首数量卡（I4，GUIDE_UI L252 + 实录 mine.txt；视觉复刻批对齐旧版运行时：
 * 高 96dp、圆角 20dp、纯白 surface 底、单块两行文本「图片\nN」16sp Bold 双向居中）。
 * count=null（未就绪或读失败）→ 数字位显「—」降级，不崩、不弹横幅。
 */
@Composable
private fun CountCard(title: String, count: Int?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(CountCardCornerRadius),
        modifier = modifier.height(CountCardHeight),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                // 旧版是单 TextView 两行文案「图片\nN」（非标题/数字两块），同 16sp Bold
                text = "$title\n${count?.toString() ?: COUNT_UNKNOWN}",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 资料卡：服务器地址展示（改地址=退出重登语义，行不可点）；卡底对齐全页纯白 16dp 圆角卡语言 */
@Composable
private fun ServerUrlCard(serverUrl: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = modifier.fillMaxWidth(),
    ) {
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
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 写操作失败横幅（P2-3）：errorContainer 底 + 点按消除；文案由 ViewModel 给出（中文、可重试指向） */
@Composable
private fun WriteErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = modifier.fillMaxWidth(),
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
 * 可，不做大 UI（拍板口径）。卡底对齐全页纯白 16dp 圆角卡语言。
 */
@Composable
private fun EventSyncCard(
    pending: Int?,
    syncing: Boolean,
    note: String?,
    onSyncNow: () -> Unit,
    onExport: () -> Unit,
    onDismissNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = modifier.fillMaxWidth(),
    ) {
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
 * 入口行（视觉复刻批对齐旧版 QimengProfileRow 运行时规格：高 72dp、水平 padding 18dp
 * 垂直居中、背景=16dp 圆角纯白 surface 卡、无图标无分隔线；行文字=单块两行文本
 * 「标题\n副标题」15sp 主文字色——旧版副标题与标题同字号同色；subtitle=null 保持单行；
 * detail=右侧灰字（版本行等无副文案的旧形态行保留用）；
 * onClick=null 为纯展示行（主题色彩，GUIDE_UI L268）。
 * 高度用 min 而非定值：本机模式行副文案长（任务T 文案），定值 72dp 会截断三行以上文本，
 * 其余短文案行渲染高度与旧版 72dp 完全一致。
 */
@Composable
private fun EntryRow(
    label: String,
    subtitle: String? = null,
    detail: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = QimengDimens.ProfileRowHeight)
                .padding(horizontal = QimengDimens.ProfileRowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 单块两行文本（旧版单个 TextView：标题+副标题同字号同色，非两块 Text）
            Text(
                text = if (subtitle != null) "$label\n$subtitle" else label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
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

/** 缓存卡：LRU 档位四选（写入 DataStore，重启生效）+ 清空按钮（清后容量归零核对）；卡底对齐全页纯白卡语言 */
@Composable
private fun QuotaCard(
    current: DiskCacheQuota,
    sizeBytes: Long?,
    onSelectQuota: (DiskCacheQuota) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearing by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = modifier.fillMaxWidth(),
    ) {
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
 * 推荐偏好 BottomSheet（C4；2026-09-13 视觉复刻批对齐旧版运行时规格）：
 * - 容器：顶部圆角 28dp、surface 底、padding(20,18,20,28)；
 * - 标题 18sp Bold 居中（pb10）、副文 13sp 次色（pb12）、分组标签「预设方案」12sp 次色（t12/b6）；
 * - 四预设行：上下 padding 12dp 左右 16dp、行距 6dp、名称 14sp 主色、描述 12sp 次色（上距 2dp）；
 * - 选中态（陷阱#5 以运行时代码为准）：28dp 圆角 surface 填充 + 1dp 描边——选中描边 primary、
 *   未选描边 divider（outlineVariant 槽），非胶囊实底反白；
 * - 「点击无反应」修复：显式持有 sheetState（material3 1.5.0-alpha28 上未显式传 state 的
 *   ModalBottomSheet 点击行后不弹出——S3 Step3 升级引入的 expressive motion 回归区；
 *   同库 QimengFilterSheet/DetailTagSheet 显式传 state 均正常，与其对齐）；
 *   预设四行恒渲染（选项不依赖网络），appliedPreset 依赖偏好状态，加载失败进重试态。
 * 预设→9 维映射在 :core:model（DOMAIN_RULES §1.3 预设表逐字）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrefsBottomSheet(
    appliedPreset: RecommendPreset?,
    applying: Boolean,
    loading: Boolean,
    loadFailed: Boolean,
    onRetryLoad: () -> Unit,
    onApply: (RecommendPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = PrefsSheetCornerRadius, topEnd = PrefsSheetCornerRadius),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PrefsSheetPadding),
        ) {
            Text(
                text = ROW_PREFS,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = PrefsTitleBottomSpacing),
            )
            Text(
                text = PREFS_SHEET_SUBTITLE,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
            Text(
                text = PREFS_GROUP_LABEL,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = QimengDimens.SpaceL, bottom = QimengDimens.SpaceS),
            )
            // 偏好状态（当前项高亮的数据源）加载失败：显「加载失败/重试」态——四预设行仍可
            // 点击应用（应用链路 PUT 不依赖 GET 结果），重试重新拉 GET；加载中防重
            if (loadFailed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = QimengDimens.SpaceS),
                ) {
                    Text(
                        text = PREFS_LOAD_FAILED,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = !loading, onClick = onRetryLoad) {
                        Text(text = if (loading) PREFS_LOADING else PREFS_RETRY)
                    }
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
            ) {
                RecommendPreset.entries.forEach { preset ->
                    val applied = preset == appliedPreset
                    PresetRow(
                        preset = preset,
                        applied = applied,
                        enabled = !applying,
                        onApply = onApply,
                    )
                }
            }
        }
    }
}

/** 单个预设行（旧版运行时规格：28dp 圆角 surface 填充 + 1dp 描边选中态；整行点击应用） */
@Composable
private fun PresetRow(
    preset: RecommendPreset,
    applied: Boolean,
    enabled: Boolean,
    onApply: (RecommendPreset) -> Unit,
) {
    val rowShape = RoundedCornerShape(PrefsSheetCornerRadius)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = rowShape,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                border = BorderStroke(width = QimengDimens.DividerThickness, color = presetBorderColor(applied)),
                shape = rowShape,
            )
            .clickable(enabled = enabled) { onApply(preset) },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = preset.label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = presetDescription(preset),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PresetDescTopSpacing),
            )
        }
    }
}

/** 选中态描边色：选中=primary（旧版选中描边主色）、未选=divider（outlineVariant 槽=旧 qm_divider） */
@Composable
private fun presetBorderColor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

/** 缓存容量展示改走 :core:ui 共享 formatBytesHumanReadable（与统计页同源） */
