package media.qimeng.app.feature.settings

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AuthorOverview
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.model.displayLabel
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengRankCard
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.formatBytesHumanReadable
import media.qimeng.app.core.ui.theme.QimengDimens

/** 我的页入口行文案（GUIDE_UI §我的页 + M4-2 既有入口 + M4-6 上传入口；
 *  F 批 2026-09-09：删除「作者管理」行——用户拍板「我的界面暂时只需要删除外部的作者管理，
 *  因为已经有了一个作者管理」，唯一入口=作者总览卡内「管理」（onOpenAuthors 仍被该卡使用） */
private const val ROW_FAVORITE = "收藏"
private const val ROW_HISTORY = "浏览历史"
private const val ROW_UPLOAD = "上传文件"
private const val ROW_THEME = "主题色彩"
private const val ROW_PREFS = "推荐偏好"

/**
 * 入口行副文案（I4 两行化，实录 mine.txt 逐字：收藏/浏览历史/主题色彩/推荐偏好；
 * 推荐偏好行当前预设名不再展示在行上——当前项高亮已在 BottomSheet 内，GUIDE_UI L255；
 * 原作者管理行副文案随行同批删除，F 批 2026-09-09）。
 */
private const val SUBTITLE_FAVORITE = "查看收藏的图片和视频"
private const val SUBTITLE_HISTORY = "查看最近打开过的图片和视频"
private const val SUBTITLE_THEME = "跟随手机白天/深色模式自动切换"
private const val SUBTITLE_PREFS = "调整首页推荐算法的权重偏好"

/** 数量卡文案（I4，实录 mine.txt 两卡「图片 N」「视频 N」；数字未就绪/读失败显「—」） */
private const val COUNT_CARD_IMAGE = "图片"
private const val COUNT_CARD_VIDEO = "视频"
private const val COUNT_UNKNOWN = "—"

/** 分区标题与行副文案（展示语义，GUIDE_UI §我的页/设置页口径 + C5/C6 拍板 + G2 作者总览） */
private const val SECTION_CACHE = "缓存"
private const val HINT_SERVER_URL = "媒体库与账号都归这台服务端管；更换地址请退出登录后重新登录"
private const val HINT_QUOTA = "重启应用后生效（缓存目录正在使用中，运行中扩缩容会损坏缓存）"
private const val VERSION_UNKNOWN = "未知"

/** 作者总览卡文案（G2：Web DataPage 作者总览卡同形态——头行/计数副行/管理入口/空态） */
private const val OVERVIEW_TITLE = "作者总览"
private const val OVERVIEW_MANAGE = "管理"
private const val OVERVIEW_LOADING = "加载中…"
private const val OVERVIEW_EMPTY = "暂无作者"

/** 写失败横幅消除按钮文案（P2-3） */
private const val WRITE_ERROR_DISMISS = "知道了"

/**
 * 「我的」Tab（M4-6 完整版，单页滚动列表，GUIDE_UI §我的页结构 + I4 复刻清偿）：
 * 标题 → 页首数量卡（I4：图片/视频两卡，实录页首即数量卡，GUIDE_UI L252）→
 * 资料卡（服务器地址展示，改地址=退出重登语义）→ 作者总览卡（G2：Web DataPage 形态——
 * 计数头注 + 文件数 Top5 + 管理入口；关注/取关操作移作者管理页；F 批 2026-09-09 起
 * 卡内「管理」=唯一作者管理入口，外部入口行删除）→
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
        item { Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall) }

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

        // 作者总览卡（G2：替换 C4 关注列表展示——Web DataPage 作者总览卡形态，
        // 计数头注 + 文件数 Top5 + 管理入口；取关操作移作者管理页承担）
        item {
            AuthorOverviewCard(
                overview = state.authorOverview,
                loading = state.authorsLoading,
                onManage = onOpenAuthors,
                onOpenAuthor = onOpenAuthors,
            )
        }

        // 入口行族（F 批 2026-09-09 起行序：收藏/浏览历史 → 上传入口 → 主题色彩（不可点）→
        // 推荐偏好；原「作者管理」行按用户拍板删除——作者总览卡内「管理」即唯一作者管理入口）
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

        // 上传入口（M4-5 上传页接入设置页，M4-5 遗留项）
        item {
            EntryRow(
                label = ROW_UPLOAD,
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
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = count?.toString() ?: COUNT_UNKNOWN,
                style = MaterialTheme.typography.headlineSmall,
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
 * 作者总览卡（G2，Web DataPage 作者总览卡同形态）：「作者总览 + 管理」头行 →
 * 「N 位作者 · 已关注 M」计数副行 → 按文件数 Top5 行（作者名 ·COS 标记 + 「N 个文件」）。
 * 展示口径单源在 :core:model [toAuthorOverview]（计数与排序纯函数，单测锁定）。
 * 总览未就绪 → 「加载中…」；读失败既有总览保持原状、横幅走 writeError（卡内不重复报错）；
 * 全量作者为 0 → 「暂无作者」空态。
 * 【接线核实（I4）】原注释「行点击进作者集合页待 G1b 批接线」已过时——G1b 实际接线的是
 * 作者管理页（AuthorScreen 行点击 → AuthorCollectionRoutes，QimengNavHost 实证），本卡
 * Top5 行此前无 onClick。I4 补接线：行点击走 onOpenAuthor；当前传 onOpenAuthors（进作者
 * 管理页，经其行点击继续进作者集合页，链路闭环）——直达作者集合页需壳层为 SettingsScreen
 * 增配 authorId 回调（QimengNavHost 属共享文件，本批只读红线，见交付报告需共享窗口项）。
 */
@Composable
private fun AuthorOverviewCard(
    overview: AuthorOverview?,
    loading: Boolean,
    onManage: () -> Unit,
    onOpenAuthor: () -> Unit,
) {
    QimengRankCard(modifier = Modifier.fillMaxWidth()) {
        // 头行：标题 + 管理入口（Web .rank-head：h3 + a.rank-more 小字次色，点进作者管理页）
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = OVERVIEW_TITLE,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = OVERVIEW_MANAGE,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onManage),
            )
        }
        if (overview == null) {
            // 未就绪：首次加载中给占位；读失败不占位（横幅已反馈，避免「暂无作者」误读）
            if (loading) CardPlaceholder(text = OVERVIEW_LOADING)
        } else {
            // 计数副行（Web .rank-note：「N 位作者 · 已关注 M」——N=全量、M=followed 计数）
            Text(
                text = "${overview.totalAuthors} 位作者 · 已关注 ${overview.followedCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (overview.totalAuthors == 0) {
                CardPlaceholder(text = OVERVIEW_EMPTY)
            } else {
                overview.topByFileCount.forEachIndexed { index, author ->
                    // 行间分隔线（Web .rank-card li border-bottom，末行无线）
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = QimengDimens.RankCardRowDividerThickness,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    OverviewAuthorRow(
                        name = author.displayLabel,
                        fileCount = author.fileCount ?: 0,
                        onClick = onOpenAuthor,
                    )
                }
            }
        }
    }
}

/** 总览行（Web RankRowList li：.rank-name 首行 + .rank-sub2 副标题「N 个文件」；I4 行点击接线见卡注释） */
@Composable
private fun OverviewAuthorRow(name: String, fileCount: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Web .rank-card li 纵向内边距 6px（横段 2px 由卡内边距承担，不重复施加）
            .padding(vertical = QimengDimens.SpaceS),
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "$fileCount 个文件",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 卡内占位文案（Web .a-empty/.rank-note 空数据口径：次色小字） */
@Composable
private fun CardPlaceholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
