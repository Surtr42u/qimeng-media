package media.qimeng.app.feature.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.DetailTag
import media.qimeng.app.core.ui.component.QimengRankCard
import media.qimeng.app.core.ui.component.formatBytesForDetail
import media.qimeng.app.core.ui.component.formatCount
import media.qimeng.app.core.ui.component.formatShortDate
import media.qimeng.app.core.ui.icon.BackIcon
import media.qimeng.app.core.ui.icon.ChevronLeftIcon
import media.qimeng.app.core.ui.icon.ChevronRightIcon
import media.qimeng.app.core.ui.icon.DeleteIcon
import media.qimeng.app.core.ui.icon.DriveFileMoveIcon
import media.qimeng.app.core.ui.icon.StarIcon
import media.qimeng.app.core.ui.icon.StarOutlinedIcon
import media.qimeng.app.core.ui.icon.ThumbUpIcon
import media.qimeng.app.core.ui.icon.ThumbUpOutlinedIcon
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 页面私有尺寸档（本文件单源；来源注释随条目） ----------

/** 详情各节纵向间距（Web .detail-main 各子块 margin 的移动端近似档；推荐栏 DetailUpNextCard.kt 同档复用） */
internal val SECTION_SPACING = 12.dp

/** 互动行钮间横向间距（Web .detail-actions gap 12px） */
private val ACTION_SPACING = 12.dp

// ---------- 顶行 ----------

/**
 * 顶行：返回箭头。任务G G1a 移除右侧「i / N」批次序号文本——Web 顶行无计数（AssetDetailPage
 * 批次序号在媒体区旁 .asset-pager），序号随 [DetailPagerRow] 落到舞台下方；沉浸逻辑 3b 不变。
 */
@Composable
internal fun DetailTopRow(onBack: () -> Unit) {
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
    }
}

// ---------- 批次 pager 行（任务G G1a） ----------

/** pager 钮 chevron 图标边长（Web .asset-pager__btn 内 ChevronLeft size={14}） */
private val PAGER_CHEVRON_ICON_SIZE = 14.dp

/** pager 钮边界禁用透明度（Web .asset-pager__btn:disabled opacity .45 置灰档） */
private const val PAGER_DISABLED_ALPHA = 0.45f

/**
 * 批次 pager 行（任务G G1a，逐语义对齐 Web .asset-pager，舞台与标题之间）：
 * 左「上一件」· 中「n / N」计数 · 右「下一件」；边界置灰停止不循环（首件禁上一件/
 * 末件禁下一件——Web disabled 同口径）；计数沿用旧顶行 i/N 的数据源（batchIndex+1/batchSize）。
 * 无批次上下文（batchIndex<0，深链单卡）整行不渲染——Web 无 nav 态同款。
 * 切换单源复用 onSiblingNavigate(delta)（与图片横滑/全屏覆盖层同一条链）。
 */
@Composable
internal fun DetailPagerRow(
    batchIndex: Int,
    batchSize: Int,
    onSiblingNavigate: (delta: Int) -> Unit,
) {
    if (batchIndex < 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 纵向 8dp：Web .asset-pager margin-top --qm-space-2=8px 的近似档
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = QimengDimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
        // Web .asset-pager gap --qm-space-2=8px
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        PagerButton(
            text = stringResource(R.string.detail_pager_prev),
            icon = ChevronLeftIcon,
            enabled = batchIndex > 0,
            onClick = { onSiblingNavigate(-1) },
        )
        Text(
            // 展示序号 1 基（内部 0 基）；Web asset-pager__count tabular-nums 档（Compose 缺省数字字体近似）
            text = stringResource(R.string.detail_batch_position, batchIndex + 1, batchSize),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PagerButton(
            text = stringResource(R.string.detail_pager_next),
            icon = ChevronRightIcon,
            enabled = batchIndex < batchSize - 1,
            iconTrailing = true,
            onClick = { onSiblingNavigate(1) },
        )
    }
}

/**
 * pager 钮（Web .asset-pager__btn：1dp 描边 + 12px 圆角 + 页面底色小钮，上一件 chevron
 * 前置 / 下一件 chevron 后置；禁用=半透明置灰不可点）。
 */
@Composable
private fun PagerButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    iconTrailing: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        // Web --qm-radius = var(--radius) = 12px，与 12dp 卡圆角同档（RankCardCornerRadius 复用）
        shape = RoundedCornerShape(QimengDimens.RankCardCornerRadius),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(QimengDimens.DividerThickness, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.alpha(if (enabled) 1f else PAGER_DISABLED_ALPHA),
    ) {
        Row(
            // Web padding 4px 10px 的近似档（现有间距档取近：纵向 4dp / 横向 8dp）
            modifier = Modifier.padding(
                horizontal = QimengDimens.SpaceM,
                vertical = QimengDimens.SpaceXS,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXS), // Web 图标-文字 gap 4px
        ) {
            if (!iconTrailing) {
                Icon(
                    imageVector = icon,
                    contentDescription = null, // 文本已表意，chevron 纯装饰
                    modifier = Modifier.size(PAGER_CHEVRON_ICON_SIZE),
                )
            }
            Text(text = text, style = MaterialTheme.typography.bodySmall)
            if (iconTrailing) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(PAGER_CHEVRON_ICON_SIZE),
                )
            }
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

/** meta 行相邻可见项「·」分隔符（Web .detail-meta span+span::before content "·"） */
private const val META_ITEM_SEPARATOR = "·"

/**
 * meta 行（逐项对照 Web detail-meta：浏览/播放/大小/宽×高/日期/出处）。
 * 口径：浏览/播放恒显（Web formatCount(null)="0"）；宽×高仅两者齐备且宽>0（Web d.width truthy）；
 * 日期/出处空值不渲染（Web 空串 span 无视觉贡献）。
 * G1a：相邻可见项之间补「·」分隔（对齐 Web span+span::before）；items 构建期已滤空值，
 * 故分隔只出现在两可见项之间——与 Web 同口径（FlowRow 换行时分隔符可能落行首，CSS
 * flex-wrap 有同款边角，不特殊处理）。
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
        // Web 分隔符 margin 0 8px 的近似档：分隔符两侧各 6dp（SpaceS）+ 字符宽
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXS),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Text(
                    text = META_ITEM_SEPARATOR,
                    style = MaterialTheme.typography.bodySmall,
                    // Web var(--faint)「最浅次色：分隔」的近似档
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Text(
                text = item,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- 互动行 ----------

/** 互动钮点击弹跳压缩档（G1a 方案口径 scale 1→0.92→1；Web .detail-act:active transform scale(0.94)
 *  按下缩放 + act-bounce 弹跳动画的 Compose 合并近似——snap 到压缩档再弹回） */
private const val ACT_BOUNCE_PRESSED_SCALE = 0.92f

/** 互动钮回弹 spring 刚度（Web act-bounce .35s ease-out 的 Compose 惯用换算档：MediumBouncy
 *  阻尼过冲 + MediumLow 刚度，回弹时长感与 .35s 相当） */
private const val ACT_BOUNCE_SPRING_STIFFNESS = Spring.StiffnessMediumLow

/**
 * 互动行（Web .detail-actions）：点赞（ThumbUp+计数，likedToday 高亮）+ 收藏（Star+文案，isFavorite 高亮）。
 * G1a：图标形态随激活切换（active=filled 实底 / 未激活=outlined 描边——Web .detail-act.active
 * svg fill:currentColor 同语义）。
 * 任务G G1b：右端追加文件操作两钮「整理 / 删除」（Web FileOpsButton 同位置同语义——
 * 详情页有 assetId/directory 完整上下文，是文件三操作的挂载点；删除 = 移入回收站，铁律 4）。
 */
@Composable
internal fun DetailInteractionRow(
    asset: AssetDetail,
    likePending: Boolean,
    favoritePending: Boolean,
    fileOpsPending: Boolean,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenMoveDialog: () -> Unit,
    onOpenDeleteDialog: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = QimengDimens.ScreenPaddingHorizontal,
                vertical = SECTION_SPACING,
            ),
        horizontalArrangement = Arrangement.spacedBy(ACTION_SPACING),
    ) {
        DetailActionButton(
            active = asset.likedToday,
            enabled = !likePending,
            contentDescription = stringResource(R.string.detail_like),
            onClick = onToggleLike,
        ) {
            Icon(
                imageVector = if (asset.likedToday) ThumbUpIcon else ThumbUpOutlinedIcon,
                contentDescription = null,
            )
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
            Icon(
                imageVector = if (asset.isFavorite) StarIcon else StarOutlinedIcon,
                contentDescription = null,
            )
            Text(
                text = stringResource(
                    if (asset.isFavorite) R.string.detail_favorite_active else R.string.detail_favorite,
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        // 文件操作两钮推到行右端（基准说明「互动行右端」；窄屏与点赞/收藏同行换行时
        // FlowRow 未用——Web 同一行 flex，超宽由内容自适应，暂保持 Row 语义）
        Spacer(modifier = Modifier.weight(1f))
        DetailActionButton(
            active = false,
            enabled = !fileOpsPending,
            contentDescription = stringResource(R.string.detail_file_ops_move),
            onClick = onOpenMoveDialog,
        ) {
            Icon(
                imageVector = DriveFileMoveIcon,
                contentDescription = null,
            )
            Text(
                text = stringResource(R.string.detail_file_ops_move),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        DetailActionButton(
            active = false,
            enabled = !fileOpsPending,
            danger = true,
            contentDescription = stringResource(R.string.detail_file_ops_delete),
            onClick = onOpenDeleteDialog,
        ) {
            Icon(
                imageVector = DeleteIcon,
                contentDescription = null,
            )
            Text(
                text = stringResource(R.string.detail_file_ops_delete),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 互动按钮（胶囊；请求进行中 disabled——Web toggleLike.isPending 同语义）。
 * G1a 对齐 Web .detail-act：active=primary 主色实底+onPrimary 反色字（primaryContainer
 * 软底退役）；点击 bounce 缩放动效（点击瞬间 snap 到 [ACT_BOUNCE_PRESSED_SCALE]，MediumBouncy
 * spring 回弹到 1，过冲即 Web act-bounce 的弹跳感；点赞/收藏两钮共用同款，Web 点赞弹跳/
 * 通用 :active 按下缩放的合并表达）。
 * 任务G G1b 补 danger 档：删除钮未激活态用 error 色系（危险操作的常驻视觉语义；
 * Web 的 danger 语义在确认弹窗，此处按钮先行着色提示）。
 */
@Composable
private fun DetailActionButton(
    active: Boolean,
    enabled: Boolean,
    contentDescription: String,
    danger: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    // bounce 触发计数（每次点击自增重触发动画）；Animatable 初值 1（无动画时原尺寸）
    var bounceTrigger by remember { mutableStateOf(0) }
    val bounceScale = remember { Animatable(1f) }
    LaunchedEffect(bounceTrigger) {
        if (bounceTrigger > 0) {
            bounceScale.snapTo(ACT_BOUNCE_PRESSED_SCALE)
            bounceScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = ACT_BOUNCE_SPRING_STIFFNESS,
                ),
            )
        }
    }
    Surface(
        onClick = {
            bounceTrigger++
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        color = if (active) {
            // Web .detail-act.active：background var(--qm-primary) 主色实底
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (active) {
            // Web .detail-act.active：color var(--invert) 反色字
            MaterialTheme.colorScheme.onPrimary
        } else if (danger) {
            // danger 档（G1b 删除钮）：错误色文字提示破坏性语义
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .graphicsLayer {
                scaleX = bounceScale.value
                scaleY = bounceScale.value
            }
            .semantics { this.contentDescription = contentDescription },
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
 * Web authorDisplayName 同口径）+ 关注按钮。作者名点击进作者集合页（任务G G1b 接线——
 * Web AuthorCard 名字是链接；回调 (id, 原始名) 双参，原始名不带 ·COS 后缀，
 * Web 跳转用原始名同语义）。G1a 卡片化：套 [QimengRankCard] 描边卡盒（Web .detail-side
 * 作者卡复用 .rank-card 卡盒同语义），卡内边距由卡盒统一施加（RankCardInnerPadding），
 * 外层只留屏幕边距与节间距。
 */
@Composable
internal fun DetailAuthorCard(
    authors: List<DetailAuthor>,
    followPending: Boolean,
    onToggleFollow: (String) -> Unit,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit = { _, _ -> },
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
        QimengRankCard(modifier = Modifier.fillMaxWidth()) {
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
                        // 名字即链接（Web 同语义）：主色 + 可点，触区补竖向内边距防误触邻行
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clipToBounds()
                            .clickable { onOpenAuthor(author.id, author.displayName) }
                            .padding(vertical = QimengDimens.SpaceXS),
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
