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
import androidx.compose.material3.FilterChip
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
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.formatBytesHumanReadable

/** 我的页入口行文案（GUIDE_UI §我的页 + M4-2 既有入口 + M4-6 上传入口） */
private const val ROW_FAVORITE = "收藏"
private const val ROW_HISTORY = "浏览历史"
private const val ROW_AUTHORS = "作者管理"
private const val ROW_UPLOAD = "上传文件"

/** 分区标题与行副文案（展示语义，GUIDE_UI §我的页/设置页口径 + C5/C6 拍板） */
private const val SECTION_FOLLOW = "关注的作者"
private const val SECTION_CACHE = "缓存"
private const val HINT_SERVER_URL = "媒体库与账号都归这台服务端管；更换地址请退出登录后重新登录"
private const val HINT_QUOTA = "重启应用后生效（缓存目录正在使用中，运行中扩缩容会损坏缓存）"
private const val EMPTY_FOLLOW = "暂无关注的作者"
private const val VERSION_UNKNOWN = "未知"

/** 写失败横幅消除按钮文案（P2-3） */
private const val WRITE_ERROR_DISMISS = "知道了"

/**
 * 「我的」Tab（M4-6 完整版，单页滚动列表，GUIDE_UI §我的页结构）：
 * 资料卡（服务器地址展示，改地址=退出重登语义）→ 关注列表（取关）→ 推荐偏好（BottomSheet
 * 四预设整行应用/当前项高亮）→ 覆盖页入口（收藏/浏览历史/作者管理）→ 上传入口 →
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

        // 资料卡：服务器地址（单机形态预留点，ADR-0015；只展示不可改）
        item { ServerUrlCard(serverUrl = state.serverUrl) }

        // 关注列表（C4）
        item { SectionTitle(text = SECTION_FOLLOW) }
        if (state.followedAuthors.isEmpty()) {
            item { EmptyText(text = if (state.followedLoading) "加载中…" else EMPTY_FOLLOW) }
        } else {
            items(state.followedAuthors.size) { index ->
                val author = state.followedAuthors[index]
                FollowedAuthorRow(
                    name = author.displayName,
                    onUnfollow = { viewModel.unfollow(author.id) },
                )
            }
        }

        // 推荐偏好（C4 BottomSheet）
        item {
            EntryRow(
                label = "推荐偏好",
                detail = state.appliedPreset?.label ?: "自定义",
                onClick = viewModel::openPrefsSheet,
            )
        }

        // 覆盖页入口（M4-2 既有三行）
        item {
            EntryRow(label = ROW_FAVORITE, onClick = onOpenFavorite)
        }
        item { EntryRow(label = ROW_HISTORY, onClick = onOpenHistory) }
        item { EntryRow(label = ROW_AUTHORS, onClick = onOpenAuthors) }

        // 上传入口（M4-5 上传页接入设置页，M4-5 遗留项）
        item { EntryRow(label = ROW_UPLOAD, onClick = onOpenUpload) }

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

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 关注作者行：名字 + 取关（取关后列表刷新该行消失，C4 验收点） */
@Composable
private fun FollowedAuthorRow(name: String, onUnfollow: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onUnfollow) { Text(text = "取关") }
        }
    }
}

/** 入口行（浅面底；detail 非空时右侧灰字；onClick=null 为纯展示行） */
@Composable
private fun EntryRow(label: String, detail: String? = null, onClick: (() -> Unit)?) {
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
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
                    FilterChip(
                        selected = current == quota,
                        onClick = { onSelectQuota(quota) },
                        label = { Text(text = quota.label) },
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
