package media.qimeng.app.feature.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.DetailTag
import media.qimeng.app.core.ui.component.formatBytesForDetail
import media.qimeng.app.core.ui.component.formatCount
import media.qimeng.app.core.ui.component.formatShortDate
import media.qimeng.app.core.ui.icon.BackIcon
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 页面私有尺寸档（本文件单源；来源注释随条目） ----------

/** 详情各节纵向间距（Web .detail-main 各子块 margin 的移动端近似档） */
internal val SECTION_SPACING = 12.dp

// ---------- 顶行 ----------

/**
 * 顶行：返回箭头。任务G G1a 移除右侧「i / N」批次序号文本（Web 顶行无计数）；i/N 已全归
 * 顶部渐变 chrome（DetailTopChrome，V2 删舞台下 pager 行后为唯一承担者）；沉浸逻辑 3b 不变。
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

// ---------- 互动钮（胶囊样式件） ----------

/** 互动钮点击弹跳压缩档（G1a 方案口径 scale 1→0.92→1；Web .detail-act:active transform scale(0.94)
 *  按下缩放 + act-bounce 弹跳动画的 Compose 合并近似——snap 到压缩档再弹回） */
private const val ACT_BOUNCE_PRESSED_SCALE = 0.92f

/** 互动钮回弹 spring 刚度（Web act-bounce .35s ease-out 的 Compose 惯用换算档：MediumBouncy
 *  阻尼过冲 + MediumLow 刚度，回弹时长感与 .35s 相当） */
private const val ACT_BOUNCE_SPRING_STIFFNESS = Spring.StiffnessMediumLow

/**
 * 互动按钮（胶囊；请求进行中 disabled——Web toggleLike.isPending 同语义）。
 * G1a 对齐 Web .detail-act：active=primary 主色实底+onPrimary 反色字（primaryContainer
 * 软底退役）；点击 bounce 缩放动效（点击瞬间 snap 到 [ACT_BOUNCE_PRESSED_SCALE]，MediumBouncy
 * spring 回弹到 1，过冲即 Web act-bounce 的弹跳感）。
 * 任务G G1b 补 danger 档：破坏性操作未激活态用 error 色系（常驻视觉语义提示）。
 * 任务V V3：原下滑区互动行（DetailInteractionRow）整行退役，点赞/收藏/标签/整理四胶囊
 * 上移首屏（DetailBottomChrome 原位替换旧四图标行）——本件由文件私有提为模块内 internal
 * 供 DetailChromeBars 复用，样式口径不变（用户拍板：四胶囊样式=现行胶囊件）。
 */
@Composable
internal fun DetailActionButton(
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
 * 标签行（Web .detail-tags）：当前标签只读胶囊展示。
 * 任务V V3：原「管理 / + 添加标签」入口胶囊删除（用户拍板下滑区 chips 只读）——编辑
 * 入口收敛到首屏「标签」胶囊（DetailBottomChrome → DetailTagManageSheet 既有链）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailTagRow(tags: List<DetailTag>) {
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
