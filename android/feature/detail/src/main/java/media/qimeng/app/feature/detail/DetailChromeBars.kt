package media.qimeng.app.feature.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.ui.icon.BackIcon
import media.qimeng.app.core.ui.icon.StarIcon
import media.qimeng.app.core.ui.icon.StarOutlinedIcon
import media.qimeng.app.core.ui.icon.ThumbUpIcon
import media.qimeng.app.core.ui.icon.ThumbUpOutlinedIcon
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 页面私有尺寸/常量档（本文件单源；来源注释随条目） ----------

/** chrome 渐变遮罩不透明度（GUIDE_UI L171/313：从 qmColorBg 90% 不透明度渐变到透明） */
private const val CHROME_GRADIENT_ALPHA = 0.9f

/** chrome 图标按下压缩档（GUIDE_UI L173 按下反馈 0.92→1.0，旧版 addPressAnimation 同数值） */
private const val CHROME_PRESSED_SCALE = 0.92f

/** chrome 按下反馈时长（GUIDE_UI L173：100ms AccelerateDecelerateInterpolator 的 tween 近似） */
private const val CHROME_PRESS_ANIM_MS = 100

/**
 * 顶部渐变 chrome（任务I I7，GUIDE_UI §详情页 L171）：返回（左）/ 当前序号 n/N（中）/
 * 信息（右），上浮于媒体舞台的渐变遮罩操作层（[CHROME_GRADIENT_ALPHA] 同 GUIDE 渐变档）；
 * 浅底/黑底随明暗切换（L161：图标 tint = onBackground，渐变底 = background，主题自洽）。
 * 顶部不再做状态栏避让（任务V V2，2026-09-10 撤除）：壳层 Scaffold innerPadding 已把内容区
 * 钉在状态栏线下（舞台盒顶=状态栏线），chrome 再加 statusBars inset padding 属双重避让
 * （基线实测：状态栏时钟底 y=82 与 chrome 图标顶 y=310 间距 228px，其中 128px=statusBars
 * inset 被双计；撤后图标顶 182 与舞台盒几何模型吻合）；K3c 顶部背板条机制不受影响。
 * 批次序号沿用旧「i/N」数据源（batchIndex 0 基展示 1 基）；无批次上下文（batchIndex<0，
 * 深链单卡=待拍板 #21）不渲染计数——翻件语义边界：禁止擅自补批次上下文基建。
 */
@Composable
internal fun DetailTopChrome(
    batchIndex: Int,
    batchSize: Int,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(chromeTopGradient())
            // V2 不再加 statusBars inset padding（2026-09-10 前与壳层 Scaffold 双重避让，
            // 基线量测时钟底 y82↔图标顶 y310 空白 228px、撤后 310→182=恰一 inset 128px）：
            // 壳层 innerPadding 已把内容区钉在状态栏线下，舞台盒顶=状态栏线，chrome 直接
            // 贴舞台盒顶排版即可
            .padding(horizontal = QimengDimens.SpaceS, vertical = QimengDimens.SpaceXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeIconButton(
            icon = BackIcon,
            contentDescription = stringResource(R.string.detail_back),
            tint = MaterialTheme.colorScheme.onBackground,
            onClick = onBack,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (batchIndex >= 0) {
                Text(
                    text = stringResource(R.string.detail_batch_position, batchIndex + 1, batchSize),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        ChromeIconButton(
            icon = DetailInfoIcon,
            contentDescription = stringResource(R.string.detail_chrome_info),
            tint = MaterialTheme.colorScheme.onBackground,
            onClick = onOpenInfo,
        )
    }
}

/**
 * 底部渐变操作层（任务I I7，GUIDE_UI §详情页 L172）：点赞 / 收藏 / 标签 / 快速转跳
 * 四图标均匀分布居中，渐变遮罩从透明渐变到 qmColorBg 90%；收藏/点赞图标区分空心/实心态
 * （L172），激活态 = primary 主色（对齐互动行 active 语义）。底部不再做导航栏避让（任务V
 * V2，与顶部同口径：壳层内容区已钉在导航栏线下，navigationBars inset padding 双重避让撤除）。
 * 点赞/收藏与内容区互动行同链（VM toggle，乐观 disabled 同源）。
 */
@Composable
internal fun DetailBottomChrome(
    asset: AssetDetail,
    likePending: Boolean,
    favoritePending: Boolean,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenTagSheet: () -> Unit,
    onOpenJumpSheet: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(chromeBottomGradient())
            // V2 不再加 navigationBars inset padding（同顶部口径：壳层 innerPadding 已把
            // 内容区钉在导航栏线下，双重避让撤除）
            .padding(vertical = QimengDimens.SpaceXS),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeIconButton(
            icon = if (asset.likedToday) ThumbUpIcon else ThumbUpOutlinedIcon,
            contentDescription = stringResource(R.string.detail_like),
            tint = if (asset.likedToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            enabled = !likePending,
            onClick = onToggleLike,
        )
        ChromeIconButton(
            icon = if (asset.isFavorite) StarIcon else StarOutlinedIcon,
            contentDescription = stringResource(
                if (asset.isFavorite) R.string.detail_favorite_active else R.string.detail_favorite,
            ),
            tint = if (asset.isFavorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            enabled = !favoritePending,
            onClick = onToggleFavorite,
        )
        ChromeIconButton(
            icon = DetailSellIcon,
            contentDescription = stringResource(R.string.detail_chrome_tag),
            tint = MaterialTheme.colorScheme.onBackground,
            onClick = onOpenTagSheet,
        )
        ChromeIconButton(
            icon = DetailPeopleIcon,
            contentDescription = stringResource(R.string.detail_chrome_jump),
            tint = MaterialTheme.colorScheme.onBackground,
            onClick = onOpenJumpSheet,
        )
    }
}

/** 顶部渐变（背景色 90% → 透明，自上而下）——L171 `bg_detail_top_gradient` 的 Compose 等价物 */
@Composable
private fun chromeTopGradient(): Brush = chromeGradient(reversed = false)

/** 底部渐变（透明 → 背景色 90%，自上而下）——L172 `bg_detail_bottom_gradient` 的 Compose 等价物 */
@Composable
private fun chromeBottomGradient(): Brush = chromeGradient(reversed = true)

@Composable
private fun chromeGradient(reversed: Boolean): Brush {
    val tinted = MaterialTheme.colorScheme.background.copy(alpha = CHROME_GRADIENT_ALPHA)
    return if (reversed) {
        Brush.verticalGradient(listOf(Color.Transparent, tinted))
    } else {
        Brush.verticalGradient(listOf(tinted, Color.Transparent))
    }
}

/**
 * chrome 图标钮（IconButton + 按下缩放反馈）：GUIDE_UI L173 六按钮按下反馈
 * （ACTION_DOWN 0.92 → 回弹 1.0，100ms）的 Compose 表达——interactionSource 采按压态，
 * animateFloatAsState tween([CHROME_PRESS_ANIM_MS]) 驱动 graphicsLayer 缩放。
 */
@Composable
private fun ChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) CHROME_PRESSED_SCALE else 1f,
        animationSpec = tween(durationMillis = CHROME_PRESS_ANIM_MS),
        label = "chromePressScale",
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}
