package media.qimeng.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.formatBytesHumanReadable
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 设置页大卡区块（U10-4 减重批从 SettingsScreen.kt 原样迁出，行为零变化——SettingsScreen
 * 超 500 行警戒线，入口行族与页面骨架留主文件，独立卡片/Sheet 落本文件）：
 * 页首数量卡、写失败横幅、浏览数据同步卡、缓存配额卡、推荐偏好 BottomSheet。
 * 原私有可见性改 internal（Kotlin 文件级 private 跨文件不可见；模块内同包引用所需，
 * 模块外仍不可见）。文案常量与尺寸档逐字随迁，注释口径不变。
 */

// ---------- 数量卡文案（I4，实录 mine.txt 两卡「图片 N」「视频 N」；数字未就绪/读失败显「—」） ----------
internal const val COUNT_CARD_IMAGE = "图片"
internal const val COUNT_CARD_VIDEO = "视频"
private const val COUNT_UNKNOWN = "—"

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

/** 导出文件名（设置页 SAF 落盘 launch 用，主文件引用） */
internal const val EVENT_SYNC_EXPORT_FILE_NAME = "qimeng-pending-events.json"

/** 配额卡提示（C5：重启生效口径） */
private const val HINT_QUOTA = "重启应用后生效（缓存目录正在使用中，运行中扩缩容会损坏缓存）"

// ---------- 页首数量卡尺寸（旧版我的页运行时规格实录，随 CountCard 迁入） ----------

/** 96dp：页首数量卡高（旧版运行时两卡等宽等高 96dp） */
private val CountCardHeight = 96.dp

/** 20dp：数量卡圆角（旧版运行时；与统计页 bg_stat_card 20dp 圆角同档异源，不并档——
 *  统计页档位在 feature/stats 私有，本页不跨 feature 引用） */
private val CountCardCornerRadius = 20.dp

// ---------- 推荐偏好 BottomSheet 尺寸与文案（旧版运行时规格逐段实录，随 Sheet 迁入） ----------

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
 * 页首数量卡（I4，GUIDE_UI L252 + 实录 mine.txt；视觉复刻批对齐旧版运行时：
 * 高 96dp、圆角 20dp、纯白 surface 底、单块两行文本「图片\nN」16sp Bold 双向居中）。
 * count=null（未就绪或读失败）→ 数字位显「—」降级，不崩、不弹横幅。
 */
@Composable
internal fun CountCard(title: String, count: Int?, modifier: Modifier = Modifier) {
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

/** 分区标题（展示语义，GUIDE_UI §我的页/设置页口径 + C5/C6 拍板） */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 写操作失败横幅（P2-3）：errorContainer 底 + 点按消除；文案由 ViewModel 给出（中文、可重试指向） */
@Composable
internal fun WriteErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
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
internal fun EventSyncCard(
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

/** 缓存卡：LRU 档位四选（写入 DataStore，重启生效）+ 清空按钮（清后容量归零核对）；卡底对齐全页纯白卡语言。
 *  缓存容量展示改走 :core:ui 共享 formatBytesHumanReadable（与统计页同源，原文件尾注记档随迁） */
@Composable
internal fun QuotaCard(
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
 * 标题复用主文件 ROW_PREFS（入口行与 Sheet 标题同串「推荐偏好」单源，同包 internal）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrefsBottomSheet(
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
