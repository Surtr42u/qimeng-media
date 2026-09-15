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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.component.formatBytesHumanReadable
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（2026-09-16 用户反馈新建页；进度文案自拟对齐「已生成 X / Y」口径） */
private const val SCREEN_TITLE = "缩略图缓存"
private const val PROGRESS_SECTION = "生成进度"
private const val PROGRESS_TEMPLATE = "已生成 %d / %d"
private const val PROGRESS_UNKNOWN = "已生成 — / —"
private const val PROGRESS_SUBTITLE = "服务端在后台自动预生成，浏览无需等待；新入库文件会在扫描后自动补齐"
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
 * 缩略图缓存页（2026-09-16 用户反馈）：缩略图生成进度 + 磁盘缓存上限合并一页，
 * 数据管理 hub 加行入口。视觉/交互基准 = 同模块 BackupScreen/LibraryManageScreen
 * （QimengTopBar + 16dp 竖滚列 + Card 卡）。业务全在 ViewModel（铁律 7）。
 */
@Composable
fun ThumbnailCacheScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThumbnailCacheViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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

            // 卡1：生成进度（进度条 + 已生成计数 + 刷新；progress=null 显「—」降级）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(CardInnerPadding),
                    verticalArrangement = Arrangement.spacedBy(RowInnerSpacing),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = PROGRESS_SECTION,
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
                        text = PROGRESS_SUBTITLE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 卡2：缓存上限（SettingsCards.QuotaCard 原款拷贝，见下方注）
            QuotaCard(
                current = state.quota,
                sizeBytes = state.cacheSizeBytes,
                onSelectQuota = viewModel::setQuota,
                onClear = viewModel::clearCache,
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }
}

/**
 * 缓存卡：LRU 档位四选（写入 DataStore，重启生效）+ 清空按钮（清后容量归零核对）；卡底对齐全页纯白卡语言。
 *  缓存容量展示改走 :core:ui 共享 formatBytesHumanReadable（与统计页同源，原文件尾注记档随迁）
 *
 * 【2026-09-16 用户反馈拷贝注】本件从 feature:settings SettingsCards.kt QuotaCard 整段拷入
 * （原为 settings 模块 internal，跨模块不可见；manage 模块自持复制而非引用——与 DataManageScreen
 * HubEntryRow 的「第三处消费方应上提 core:ui」注同款裁量，QuotaCard 现为第二处）。文案/尺寸档/
 * 交互逐字随迁，行为零变化；后续出现第三处消费方时应上提 core:ui 收单源，禁止三处平行实现。
 */
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
                text = "重启应用后生效（缓存目录正在使用中，运行中扩缩容会损坏缓存）",
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
