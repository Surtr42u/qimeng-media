package media.qimeng.app.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.ui.component.QimengRankCard
import media.qimeng.app.core.ui.component.QimengThumbnail
import media.qimeng.app.core.ui.component.THUMBNAIL_ASPECT_RATIO
import media.qimeng.app.core.ui.component.formatDurationBadge
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 页面私有尺寸档（本文件单源；来源注释随条目） ----------

/** 推荐栏小缩略图宽度（Web .upnext-thumb 固定小图档；比例统一 16:9 = THUMBNAIL_ASPECT_RATIO） */
private val UPNEXT_THUMB_WIDTH = 120.dp

/** 推荐栏时长角标半透明底透明度（网格卡角标同观感） */
private const val BADGE_SCRIM_ALPHA = 0.6f

/** 推荐栏标题两行截断（Web .upnext-title 双行钳制） */
private const val UPNEXT_TITLE_MAX_LINES = 2

/** 推荐栏副行单行截断 */
private const val UPNEXT_SUBTITLE_MAX_LINES = 1

/**
 * 推荐栏（Web UpNextList；自 DetailSections.kt 拆出，纯移动零行为变化）：
 * 标题 +「换一批」+ 行式列表（16:9 小缩略图 + 视频时长角标 +
 * 两行文本：上行=标题、下行=作者[0]??出处）。点击行 → 壳层 onOpenAsset（VM 已换批次清单）。
 * 空态「暂无推荐」。
 * G1a 卡片化：仅换外壳——内部结构（标题行/换一批钮/缩略图行）不动，套 [QimengRankCard]
 * 描边卡盒（Web .detail-side 推荐栏复用 .rank-card 卡盒同语义），卡内边距由卡盒统一施加。
 */
@Composable
internal fun DetailUpNextCard(
    upNext: List<MediaAsset>,
    upNextLoading: Boolean,
    onReshuffle: () -> Unit,
    onOpenAsset: (assetId: String, batchIds: List<String>) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = QimengDimens.ScreenPaddingHorizontal,
                // SECTION_SPACING 单源在 DetailSections.kt（各节统一纵向间距档）
                vertical = SECTION_SPACING,
            ),
    ) {
        QimengRankCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.detail_upnext_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !upNextLoading, onClick = onReshuffle) {
                    Text(text = stringResource(R.string.detail_upnext_shuffle))
                }
            }
            if (upNext.isEmpty()) {
                Text(
                    text = stringResource(R.string.detail_upnext_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = QimengDimens.SpaceM),
                )
            } else {
                val batchIds = upNext.map { it.id }
                upNext.forEach { item ->
                    UpNextRow(item = item, onClick = { onOpenAsset(item.id, batchIds) })
                }
            }
        }
    }
}

/** 推荐栏行：小缩略图（16:9）+ 时长角标（仅 VIDEO 且有时长，Web 同条件）+ 两行文本 */
@Composable
private fun UpNextRow(item: MediaAsset, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = QimengDimens.SpaceS)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        Box(
            modifier = Modifier
                .width(UPNEXT_THUMB_WIDTH)
                .aspectRatio(THUMBNAIL_ASPECT_RATIO),
        ) {
            QimengThumbnail(
                model = item.thumbUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.mediaType == MediaKind.VIDEO) {
                formatDurationBadge(item.durationMs)?.let { badge ->
                    Surface(
                        color = Color.Black.copy(alpha = BADGE_SCRIM_ALPHA),
                        shape = RoundedCornerShape(QimengDimens.BadgeCornerRadius),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(QimengDimens.SpaceXS),
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(
                                horizontal = QimengDimens.SpaceXS,
                                vertical = QimengDimens.SpaceXXS,
                            ),
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = UPNEXT_TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.authorNames.firstOrNull() ?: item.source.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = UPNEXT_SUBTITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
