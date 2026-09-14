package media.qimeng.app.feature.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.ui.icon.BackIcon
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 顶行 ----------

/**
 * 顶行：返回箭头。任务G G1a 移除右侧「i / N」批次序号文本（Web 顶行无计数）；i/N 已全归
 * 顶部渐变 chrome（DetailTopChrome，V2 删舞台下 pager 行后为唯一承担者）；沉浸逻辑 3b 不变。
 * X1 壳层改造（2026-09-12 任务X）后详情内容全屏铺开，本行挂加载/错误态首位（无舞台盒
 * chrome 浮层），需自管 statusBarsPadding 防返回钮被状态栏遮挡（同 DetailTopChrome X1 口径）。
 */
@Composable
internal fun DetailTopRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
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

// ---------- 互动钮（胶囊样式件） ----------

/** 互动钮点击弹跳压缩档（G1a 方案口径 scale 1→0.92→1；Web .detail-act:active transform scale(0.94)
 *  按下缩放 + act-bounce 弹跳动画的 Compose 合并近似——snap 到压缩档再弹回） */
private const val ACT_BOUNCE_PRESSED_SCALE = 0.92f

/** 互动钮回弹 spring 刚度（Web act-bounce .35s ease-out 的 Compose 惯用换算档：MediumBouncy
 *  阻尼过冲 + MediumLow 刚度，回弹时长感与 .35s 相当） */
private const val ACT_BOUNCE_SPRING_STIFFNESS = Spring.StiffnessMediumLow

/**
 * 胶囊文案单行档（修复B 2026-09-14 等宽四槽防折行回归修复）：[DetailActionButton] 收到
 * `singleLine=true`（chrome 等宽四槽场景）时发布 true，胶囊内文案经 [CapsuleText] 读档
 * 强制 maxLines=1 + softWrap=false。经组件发布而非调用点直写 Text 参数，是因为文案 Text
 * 在调用方 content lambda 内、组件改不到——单行决策仍由胶囊件单点承担；默认 false =
 * 默认参数 Text，非 chrome 调用方零变化。
 */
internal val LocalCapsuleSingleLine = compositionLocalOf { false }

/**
 * 互动按钮（胶囊；请求进行中 disabled——Web toggleLike.isPending 同语义）。
 * G1a 对齐 Web .detail-act：active=primary 主色实底+onPrimary 反色字（primaryContainer
 * 软底退役）；点击 bounce 缩放动效（点击瞬间 snap 到 [ACT_BOUNCE_PRESSED_SCALE]，MediumBouncy
 * spring 回弹到 1，过冲即 Web act-bounce 的弹跳感）。
 * 任务G G1b 补 danger 档：破坏性操作未激活态用 error 色系（常驻视觉语义提示）。
 * 任务V V3：原下滑区互动行（DetailInteractionRow）整行退役，点赞/收藏/标签/整理四胶囊
 * 上移首屏（DetailBottomChrome 原位替换旧四图标行）——本件由文件私有提为模块内 internal
 * 供 DetailChromeBars 复用，样式口径不变（用户拍板：四胶囊样式=现行胶囊件）。
 * 修复A（2026-09-14 用户拍板「四胶囊等宽槽+间隔」）：补 [modifier] 参数（并入既有
 * graphicsLayer/semantics 链之前，调用方经 Modifier.weight(1f) 划等宽四槽）；内部内容
 * Row 改 fillMaxWidth + spacedBy(SpaceS, CenterHorizontally)——胶囊在槽内撑满、
 * icon+文字组合居中且 icon-文字 6dp 间隙保留（居中用 spacedBy 的 alignment 重载，
 * 不丢原 spacedBy 间隙）。
 * 修复B（2026-09-14 等宽四槽折行回归修复）：补 [singleLine]/[contentHorizontalPadding]
 * 两个可选参数，默认值=修复A前口径（非 chrome 复用方零变化）——singleLine=true 经
 * [LocalCapsuleSingleLine] 发布单行档（文案侧消费见 [CapsuleText]，maxLines=1 +
 * softWrap=false）；contentHorizontalPadding 覆盖内容 Row 水平 padding（chrome 传
 * 10dp 压内容固有宽至等分槽宽之下，配套档见 DetailChromeBars
 * CHROME_CAPSULE_CONTENT_H_PADDING，组件默认 ChipHorizontalPadding 不动）。
 */
@Composable
internal fun DetailActionButton(
    active: Boolean,
    enabled: Boolean,
    contentDescription: String,
    danger: Boolean = false,
    singleLine: Boolean = false,
    contentHorizontalPadding: Dp = QimengDimens.ChipHorizontalPadding,
    modifier: Modifier = Modifier,
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
        modifier = modifier
            .graphicsLayer {
                scaleX = bounceScale.value
                scaleY = bounceScale.value
            }
            .semantics { this.contentDescription = contentDescription },
    ) {
        // 修复B：singleLine 发布给 content 内文案（CapsuleText 消费）；默认 false 时与
        // 直挂 Row 等价（compositionLocalOf 无静态默认开销，见 LocalCapsuleSingleLine 注）
        CompositionLocalProvider(LocalCapsuleSingleLine provides singleLine) {
            Row(
                // 修复A：槽内撑满 + 内容组居中（spacedBy alignment 重载，icon-文字间隙 SpaceS 保留）；
                // 修复B：水平 padding 走 [contentHorizontalPadding] 覆盖档
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = contentHorizontalPadding,
                        vertical = QimengDimens.SpaceS,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS, Alignment.CenterHorizontally),
                content = content,
            )
        }
    }
}

/**
 * 胶囊内文案（修复B 2026-09-14）：读 [LocalCapsuleSingleLine]，true 时 maxLines=1 +
 * softWrap=false 强制单行（等宽四槽防折行，机制见 [DetailActionButton]）。样式固定
 * labelLarge+Bold = chrome 四胶囊文案现行口径（当前唯一使用方），出现新样式需求再参数化。
 */
@Composable
internal fun CapsuleText(text: String) {
    val singleLine = LocalCapsuleSingleLine.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        softWrap = !singleLine,
    )
}

/**
 * 标签 chip 文字覆盖档（U10-2 D2）：12sp Regular（旧 styles.xml:33 QimengTagChip textSize 12sp）。
 * labelLarge 基座上仅覆盖字号/字重，字面量单点在此——DetailTagSheet「其他标签」chip 同源引用。
 */
internal val TagChipTextStyleOverride = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)

/**
 * 只读展示胶囊（当前标签展示用；trailing 可挂清除图标——弹窗「当前标签」勾选移除）。
 * 出处：标签行与标签管理弹窗（DetailTagSheet.kt）两节共用，故提为文件级 internal 共享。
 * U10-2 对齐旧版标签弹窗 chip（D1/D2/D3）：selected=true 实底主色反白（旧 TagSheetHelper.kt:74-76
 * qmColorPrimary 实底 + 反白文字/图标）；定高 30dp 取代纵向 padding 撑高的近似（旧 styles.xml
 * chipMinHeight 30dp = QimengDimens.ChipHeight）；文字 12sp Regular（见 [TagChipTextStyleOverride]）。
 */
@Composable
internal fun DisplayPill(
    text: String,
    selected: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(QimengDimens.ChipHeight)
                .padding(
                    start = QimengDimens.ChipHorizontalPadding,
                    end = if (trailing != null) QimengDimens.SpaceS else QimengDimens.ChipHorizontalPadding,
                ),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.merge(TagChipTextStyleOverride),
                // Unspecified = Text 参数默认值（未选中行为零变化）；选中实底反白（D1）
                color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.Unspecified,
            )
            trailing?.invoke(this)
        }
    }
}
