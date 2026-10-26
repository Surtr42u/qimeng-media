package media.qimeng.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.isQimengDarkTheme
import media.qimeng.app.core.ui.theme.softDaylightShadow

/**
 * 设置页大卡区块（U10-4 减重批从 SettingsScreen.kt 原样迁出，行为零变化——SettingsScreen
 * 超 500 行警戒线，入口行族与页面骨架留主文件，独立卡片/Sheet 落本文件）：
 * 页首数量卡、写失败横幅、推荐偏好 BottomSheet。
 * 原私有可见性改 internal（Kotlin 文件级 private 跨文件不可见；模块内同包引用所需，
 * 模块外仍不可见）。文案常量与尺寸档逐字随迁，注释口径不变。
 * （浏览数据同步卡 2026-09-15 批、缓存配额卡与分区标题 2026-09-16 用户反馈先后迁出：
 * 均去往数据管理→备份导入导出页/缩略图缓存页，本文件不再承载。）
 */

// ---------- 数量卡文案（I4，实录 mine.txt 两卡「图片 N」「视频 N」；数字未就绪/读失败显「—」） ----------
internal const val COUNT_CARD_IMAGE = "图片"
internal const val COUNT_CARD_VIDEO = "视频"
private const val COUNT_UNKNOWN = "—"

/** 写失败横幅消除按钮文案（P2-3） */
private const val WRITE_ERROR_DISMISS = "知道了"

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

/** 服务端库存计数；未取得值显示缺省，不把失败伪装成零。 */
@Composable
internal fun CountCard(title: String, count: Int?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = if (isQimengDarkTheme()) 1.dp else 0.dp,
        modifier = modifier.heightIn(min = CountCardMinHeight).softDaylightShadow(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = count?.toString() ?: COUNT_UNKNOWN,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
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

/** 推荐偏好沿用四预设和原应用链路；弹层自身承载加载/保存/失败反馈。
 * 显式 sheetState 保持现有 Material3 弹层行为，大字体和横屏可滚动查看全部选项。
 * 预设权重仍由 core:model 单点定义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrefsBottomSheet(
    appliedPreset: RecommendPreset?,
    applying: Boolean,
    loading: Boolean,
    loadFailed: Boolean,
    writeError: String?,
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
                .verticalScroll(rememberScrollState())
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
            if (applying) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("正在保存推荐偏好…", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = QimengDimens.SpaceM))
            }
            writeError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = QimengDimens.SpaceM))
            }
            Column(
                modifier = Modifier.selectableGroup(),
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
            .selectable(selected = applied, enabled = enabled, role = Role.RadioButton) { onApply(preset) },
    ) {
        Row(
            modifier = Modifier.heightIn(min = QimengDimens.ProfileRowHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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
            RadioButton(selected = applied, onClick = null, enabled = enabled)
        }
    }
}

/** 选中态描边色：选中=primary（旧版选中描边主色）、未选=divider（outlineVariant 槽=旧 qm_divider） */
@Composable
private fun presetBorderColor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

/** 库存卡最小高度，大字体可随内容增长。 */
private val CountCardMinHeight = 88.dp
