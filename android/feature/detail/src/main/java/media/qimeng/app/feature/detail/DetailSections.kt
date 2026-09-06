package media.qimeng.app.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.DetailTag
import media.qimeng.app.core.ui.component.formatBytesForDetail
import media.qimeng.app.core.ui.component.formatCount
import media.qimeng.app.core.ui.component.formatShortDate
import media.qimeng.app.core.ui.icon.BackIcon
import media.qimeng.app.core.ui.icon.StarIcon
import media.qimeng.app.core.ui.icon.ThumbUpIcon
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 页面私有尺寸档（本文件单源；来源注释随条目） ----------

/** 详情各节纵向间距（Web .detail-main 各子块 margin 的移动端近似档；推荐栏 DetailUpNextCard.kt 同档复用） */
internal val SECTION_SPACING = 12.dp

/** meta 行/互动行项间横向间距（Web .detail-meta span 间距档） */
private val META_SPACING = 12.dp

// ---------- 顶行 ----------

/** 顶行：返回箭头 + 右侧「i / N」批次序号（batchIndex<0 = 无批次上下文不显示；沉浸逻辑 3b 做） */
@Composable
internal fun DetailTopRow(batchIndex: Int, batchSize: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QimengDimens.SpaceM, vertical = QimengDimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = BackIcon,
                contentDescription = stringResource(R.string.detail_back),
            )
        }
        if (batchIndex >= 0) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                // 展示序号 1 基（内部 0 基）
                text = stringResource(R.string.detail_batch_position, batchIndex + 1, batchSize),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(end = QimengDimens.ScreenPaddingHorizontal),
            )
        }
    }
}

// ---------- 标题 / meta 行 ----------

/** 详情标题（Web .detail-title = cosWork ?? fileName，mapper 已算好 [AssetDetail.title]） */
@Composable
internal fun DetailTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = SECTION_SPACING),
    )
}

/**
 * meta 行（逐项对照 Web detail-meta：浏览/播放/大小/宽×高/日期/出处）。
 * 口径：浏览/播放恒显（Web formatCount(null)="0"）；宽×高仅两者齐备且宽>0（Web d.width truthy）；
 * 日期/出处空值不渲染（Web 空串 span 无视觉贡献）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailMetaRow(asset: AssetDetail) {
    // 局部拷贝避免跨模块属性 smart cast 限制（width/height 在 :core:model）
    val w = asset.width
    val h = asset.height
    val resolution = stringResource(R.string.detail_meta_resolution, w ?: 0, h ?: 0)
    val items = buildList {
        add(stringResource(R.string.detail_meta_views, formatCount(asset.viewCount)))
        add(stringResource(R.string.detail_meta_plays, formatCount(asset.playCount)))
        add(formatBytesForDetail(asset.sizeBytes))
        if (w != null && h != null && w > 0) add(resolution)
        formatShortDate(asset.modifiedAtMs).takeIf { it.isNotEmpty() }?.let { add(it) }
        asset.source?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(META_SPACING),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXS),
    ) {
        items.forEach { item ->
            Text(
                text = item,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- 互动行 ----------

/** 互动行（Web .detail-actions）：点赞（ThumbUp+计数，likedToday 高亮）+ 收藏（Star+文案，isFavorite 高亮） */
@Composable
internal fun DetailInteractionRow(
    asset: AssetDetail,
    likePending: Boolean,
    favoritePending: Boolean,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = QimengDimens.ScreenPaddingHorizontal,
                vertical = SECTION_SPACING,
            ),
        horizontalArrangement = Arrangement.spacedBy(META_SPACING),
    ) {
        DetailActionButton(
            active = asset.likedToday,
            enabled = !likePending,
            contentDescription = stringResource(R.string.detail_like),
            onClick = onToggleLike,
        ) {
            Icon(imageVector = ThumbUpIcon, contentDescription = null)
            Text(
                text = formatCount(asset.likeCount),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        DetailActionButton(
            active = asset.isFavorite,
            enabled = !favoritePending,
            contentDescription = stringResource(
                if (asset.isFavorite) R.string.detail_favorite_active else R.string.detail_favorite,
            ),
            onClick = onToggleFavorite,
        ) {
            Icon(imageVector = StarIcon, contentDescription = null)
            Text(
                text = stringResource(
                    if (asset.isFavorite) R.string.detail_favorite_active else R.string.detail_favorite,
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 互动按钮（胶囊；active=主色容器高亮；请求进行中 disabled——Web toggleLike.isPending 同语义） */
@Composable
private fun DetailActionButton(
    active: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = QimengDimens.ChipHorizontalPadding,
                vertical = QimengDimens.SpaceS,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
            content = content,
        )
    }
}

// ---------- 标签行 ----------

/**
 * 标签行（Web .detail-tags）：当前标签只读胶囊 +「管理 / + 添加标签」入口。
 * 点击入口开标签管理弹窗（整体替换保存的语义在弹窗内说明）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailTagRow(tags: List<DetailTag>, onOpenTagSheet: () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = QimengDimens.ScreenPaddingHorizontal,
                vertical = QimengDimens.SpaceS,
            ),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
    ) {
        tags.forEach { tag ->
            DisplayPill(text = tag.name)
        }
        // 入口胶囊（Web .detail-tag-manage：有标签=「管理」，无标签=「+ 添加标签」）
        Surface(
            onClick = onOpenTagSheet,
            shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                text = stringResource(
                    if (tags.isEmpty()) R.string.detail_tag_add else R.string.detail_tag_manage,
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(
                    horizontal = QimengDimens.ChipHorizontalPadding,
                    vertical = QimengDimens.SpaceS,
                ),
            )
        }
    }
}

/**
 * 只读展示胶囊（当前标签展示用；trailing 可挂清除图标——弹窗「当前标签」勾选移除）。
 * 出处：标签行与标签管理弹窗（DetailTagSheet.kt）两节共用，故提为文件级 internal 共享。
 */
@Composable
internal fun DisplayPill(
    text: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = QimengDimens.ChipHorizontalPadding,
                end = if (trailing != null) QimengDimens.SpaceS else QimengDimens.ChipHorizontalPadding,
                top = QimengDimens.SpaceS,
                bottom = QimengDimens.SpaceS,
            ),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
            trailing?.invoke(this)
        }
    }
}

// ---------- 作者卡 ----------

/**
 * 作者卡（Web AuthorCard）：无作者不渲染；每行 displayName（isCos 追加「 ·COS」，
 * Web authorDisplayName 同口径）+ 关注按钮。作者名跳转不做——App 尚无作者文件页（交付报告记缺口）。
 */
@Composable
internal fun DetailAuthorCard(
    authors: List<DetailAuthor>,
    followPending: Boolean,
    onToggleFollow: (String) -> Unit,
) {
    if (authors.isEmpty()) return
    val cosSuffix = stringResource(R.string.detail_author_cos_suffix)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = QimengDimens.ScreenPaddingHorizontal,
                vertical = SECTION_SPACING,
            ),
    ) {
        Text(text = stringResource(R.string.detail_authors_title), style = MaterialTheme.typography.titleMedium)
        authors.forEach { author ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = QimengDimens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (author.isCos) author.displayName + cosSuffix else author.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                FollowButton(
                    followed = author.followed,
                    enabled = !followPending,
                    onClick = { onToggleFollow(author.id) },
                )
            }
        }
    }
}

/** 关注按钮（Web .follow-btn：已关注=灰底、「+ 关注」=主色底） */
@Composable
private fun FollowButton(followed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        color = if (followed) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
        contentColor = if (followed) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
    ) {
        Text(
            text = stringResource(if (followed) R.string.detail_followed else R.string.detail_follow),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(
                horizontal = QimengDimens.ChipHorizontalPadding,
                vertical = QimengDimens.SpaceS,
            ),
        )
    }
}
