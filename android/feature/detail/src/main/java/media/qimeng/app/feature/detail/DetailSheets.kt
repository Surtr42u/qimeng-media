package media.qimeng.app.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.ui.component.formatDurationBadge
import media.qimeng.app.core.ui.icon.ChevronRightIcon
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 信息 BottomSheet（任务I I7，GUIDE_UI §详情页 L169/L171）：文件名 / 出处 / 尺寸 / 时长
 * 等详细信息（区分图片和视频——时长仅视频资产有值，协议 AssetDetail.durationMs 直读，
 * 零按需解码成本；尺寸行 width/height 齐备且 >0 才渲染，与 meta 行口径一致）。
 * **无「完成」按钮**（L169：BottomSheet 下滑手势 / 点外部空白关闭即可）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailInfoSheet(asset: AssetDetail, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                // 底部呼吸空间与页面级留白同档（DETAIL_BOTTOM_SPACER 单源在 DetailScreen.kt）
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            Text(
                text = stringResource(R.string.detail_info_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            InfoRow(label = stringResource(R.string.detail_info_filename), value = asset.fileName)
            asset.source?.takeIf { it.isNotEmpty() }?.let { source ->
                InfoRow(label = stringResource(R.string.detail_info_source), value = source)
            }
            val w = asset.width
            val h = asset.height
            if (w != null && h != null && w > 0 && h > 0) {
                InfoRow(
                    label = stringResource(R.string.detail_info_resolution),
                    // 值格式与 meta 行同串资源（%1$d×%2$d），口径单源
                    value = stringResource(R.string.detail_meta_resolution, w, h),
                )
            }
            formatDurationBadge(asset.durationMs)?.let { duration ->
                InfoRow(label = stringResource(R.string.detail_info_duration), value = duration)
            }
        }
    }
}

/** 信息弹窗行（label 小字次色 + value 正文主色；旧版 addInfoRow 的两段式同构） */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(top = QimengDimens.SpaceM)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = QimengDimens.SpaceXS),
        )
    }
}

/**
 * 快速转跳 BottomSheet（任务I I7，GUIDE_UI §详情页 L172）：当前文件关联的作者列表，
 * 点击作者名跳转作者集合页（既有路由，G1b 作者卡同一条 onOpenAuthor 链——Web「快速转跳
 * 跳作者文件浏览页」的新架构对应落点）。无作者=空态提示；无「完成」按钮（同信息弹窗口径）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailJumpSheet(
    authors: List<DetailAuthor>,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            Text(
                text = stringResource(R.string.detail_jump_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (authors.isEmpty()) {
                Text(
                    text = stringResource(R.string.detail_jump_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = QimengDimens.SpaceM),
                )
            } else {
                val cosSuffix = stringResource(R.string.detail_author_cos_suffix)
                authors.forEach { author ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenAuthor(author.id, author.displayName)
                                onDismiss()
                            }
                            .padding(vertical = QimengDimens.SpaceM),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (author.isCos) author.displayName + cosSuffix else author.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = ChevronRightIcon,
                            contentDescription = null, // 行整体可点，chevron 纯装饰
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
