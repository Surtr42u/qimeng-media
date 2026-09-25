package media.qimeng.app.feature.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.LocalMediaBucket
import media.qimeng.app.core.model.LocalMediaItem
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengThumbnail
import media.qimeng.app.core.ui.component.formatDurationBadge
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

/**
 * 内置相册式选择器（2026-09-25 拍板：上传选取弃系统 SAF 选择器，改 App 内 MediaStore
 * 网格多选）：相册（bucket）过滤 chips + 缩略图网格多选 + 底部确认。
 * 全屏 Dialog 承载（选择是模态子任务，完成/取消即回上传页，不进导航返回栈——上传页
 * 本身是 pushed 路由，选择器作为栈上页面会让返回链多一层无意义节点）。
 * 缩略图 Coil 直载 content:// URI（视频帧经全局 ImageLoader 的 coil-video 解码器）；
 * 请求级磁盘缓存关闭——视频源文件绝不落盘（CoilModule「视频不落盘」口径）。
 * URI 是 content://，与旧 SAF 结果同管道（AssetUploader openInputStream 读流不变）；
 * MediaStore URI 靠媒体读权限跨进程存活，无需（也不能）takePersistableUriPermission。
 *
 * @param onConfirm 确认回传选中项（保持选择序；调用方合入上传待传列表）
 * @param onDismiss 关闭（取消键 / 系统返回 / 点外部不生效——全屏无外部）
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun MediaPickerDialog(
    onConfirm: (List<LocalMediaItem>) -> Unit,
    onDismiss: () -> Unit,
    viewModel: MediaPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                PickerHeader(selectedCount = state.selected.size, onClose = onDismiss)
                if (state.buckets.isNotEmpty()) {
                    BucketChipRow(
                        buckets = state.buckets,
                        selectedBucket = state.selectedBucket,
                        onSelect = viewModel::selectBucket,
                    )
                }
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                when {
                    state.loading -> LoadingIndicator(modifier = Modifier.padding(24.dp))
                    state.items.isEmpty() && !state.loadingItems -> EmptyHint()
                    else -> MediaGrid(
                        items = state.items,
                        selectedUris = state.selected.keys,
                        loadingItems = state.loadingItems,
                        onToggle = viewModel::toggle,
                        modifier = Modifier.weight(1f),
                    )
                }
                ConfirmBar(
                    count = state.selected.size,
                    enabled = state.selected.isNotEmpty(),
                    onConfirm = { onConfirm(state.selected.values.toList()) },
                )
            }
        }
    }
}

/** 头部：标题 + 已选计数 + 关闭（对齐 ModalBottomSheet 无完成钮惯例，返回/关闭键均可） */
@Composable
private fun PickerHeader(selectedCount: Int, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "选择媒体文件", style = MaterialTheme.typography.titleLarge)
            if (selectedCount > 0) {
                Text(
                    text = "已选 $selectedCount 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onClose) { Text("关闭") }
    }
}

/** 相册过滤 chips：「全部」恒在首位 + 最近内容的前 N 个相册（数据量控制拍板口径） */
@Composable
private fun BucketChipRow(
    buckets: List<LocalMediaBucket>,
    selectedBucket: String?,
    onSelect: (String?) -> Unit,
) {
    val pills = buildList {
        add(QimengPill(text = BUCKET_ALL_LABEL, selected = selectedBucket == null))
        buckets.forEach { bucket ->
            add(QimengPill(text = bucket.name, selected = bucket.name == selectedBucket))
        }
    }
    QimengChipRow(
        pills = pills,
        onPillClick = { index -> onSelect(if (index == 0) null else buckets[index - 1].name) },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** 缩略图网格（3 列）：点格 toggle 勾选；角标勾选态 + 视频时长角标 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun MediaGrid(
    items: List<LocalMediaItem>,
    selectedUris: Set<String>,
    loadingItems: Boolean,
    onToggle: (LocalMediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMN_COUNT),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { it.uri }) { item ->
                MediaCell(
                    item = item,
                    selected = item.uri in selectedUris,
                    onClick = { onToggle(item) },
                )
            }
        }
        if (loadingItems) {
            LoadingIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            )
        }
    }
}

/** 单元格：缩略图（content:// 直载）+ 选中描边与角标 + 视频时长角标 */
@Composable
private fun MediaCell(item: LocalMediaItem, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CELL_ASPECT_RATIO)
            .clickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.border(
                        width = SELECTED_BORDER_WIDTH.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(CELL_CORNER_RADIUS_DP.dp),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        QimengThumbnail(
            model = item.uri,
            contentDescription = item.displayName,
            diskCacheEnabled = false,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            formatDurationBadge(item.durationMs)?.let { duration ->
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = DURATION_BADGE_ALPHA), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        SelectionBadge(selected = selected, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** 勾选角标（右上角）：选中 = 主色实心圆 + ✓；未选 = 半透明描边圆 */
@Composable
private fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .height(BADGE_SIZE_DP.dp)
            .aspectRatio(1f)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = CircleShape,
            )
            .border(
                width = 1.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(text = "✓", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

@Composable
private fun EmptyHint() {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "本机没有可上传的图片/视频（或未授予媒体权限）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 底部确认条：「加入上传队列 (N)」；零选中禁用 */
@Composable
private fun ConfirmBar(count: Int, enabled: Boolean, onConfirm: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Button(
            onClick = onConfirm,
            enabled = enabled,
            colors = qimengFilledButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(if (enabled) "加入上传队列（$count）" else "加入上传队列")
        }
    }
}

/** 网格列数（手机竖屏 3 列；网格通用密度档） */
private const val GRID_COLUMN_COUNT = 3

/** 单元格宽高比（1:1 方格，相册选择器惯例） */
private const val CELL_ASPECT_RATIO = 1f

/** 选中描边圆角（dp） */
private const val CELL_CORNER_RADIUS_DP = 6

/** 选中描边宽度（dp） */
private const val SELECTED_BORDER_WIDTH = 2

/** 勾选角标直径（dp；Material 最小触控 48dp 由整格承担，角标纯视觉） */
private const val BADGE_SIZE_DP = 20

/** 时长角标底透明度 */
private const val DURATION_BADGE_ALPHA = 0.6f

/** 相册 chips「全部」标签 */
private const val BUCKET_ALL_LABEL = "全部"
