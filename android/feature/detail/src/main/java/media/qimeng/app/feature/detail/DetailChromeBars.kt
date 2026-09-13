package media.qimeng.app.feature.detail

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.ui.component.formatCount
import media.qimeng.app.core.ui.icon.BackIcon
import media.qimeng.app.core.ui.icon.FavoriteBorderIcon
import media.qimeng.app.core.ui.icon.FavoriteFilledIcon
import media.qimeng.app.core.ui.icon.ThumbUpIcon
import media.qimeng.app.core.ui.icon.ThumbUpOutlinedIcon
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 页面私有尺寸/常量档（本文件单源；来源注释随条目） ----------

/**
 * chrome 条内容淡入/淡出时长档（任务U6，2026-09-14 对齐旧版逐帧语义）：旧版
 * MediaDetailFragment.setChromeVisible 对条内容 `animate().alpha(...).setDuration(250L)`
 * 逐字同源——250ms 单档、进出同用时。沿革：U3 曾 300/220 进慢出快（真机「近瞬隐」反馈
 * 驱动），U5 统一 250 但保留 IN/OUT 双常量，U6 合并单档并随组件迁入本文件（原
 * DetailScreen.kt CHROME_FADE_IN_MS/CHROME_FADE_OUT_MS 废止）。
 */
private const val CHROME_FADE_MS = 250

/**
 * chrome 条内容淡入/淡出统一 spec（进出共用，同曲线同时长）。旧版 ViewPropertyAnimator
 * 未显式设插值器 = 默认 AccelerateDecelerateInterpolator，Compose 等价 = FastOutSlowInEasing。
 *
 * 任务U6 语义拆分（对齐旧版 setChromeVisible 逐帧行为）：**底色瞬时切换**——旧版显隐
 * 首帧 setBackgroundColor 瞬时生效（隐=TRANSPARENT、显=0xF2 纯色，同帧完成），**仅条内
 * 图标内容**走本 spec 淡出/淡入（结束置 INVISIBLE）。据此条容器（底色/系统栏避让/内边距）
 * 恒组合、不参与任何动画；此前整条（含底色）一起 fadeIn/fadeOut，背景跟着渐隐导致实测
 * 消失拖尾 325-400ms，本批清偿。
 */
private val CHROME_CONTENT_FADE = tween<Float>(
    durationMillis = CHROME_FADE_MS,
    easing = FastOutSlowInEasing,
)

/**
 * chrome 条底色不透明度（任务U U5 对齐旧版**运行时**实现）：旧版 MediaDetailFragment
 * setChromeVisible 用 `setBackgroundColor((0xF2 shl 24) or (qmColorBg and 0x00FFFFFF))`——
 * 纯色 0xF2≈95% 半透明条，**不是渐变**；仓库里的 bg_detail_top/bottom_gradient.xml 是被
 * 运行时覆盖的死资源（U5 根因：此前 Y2/I7 按 xml「逐字同源」复刻成上下渐变，正是用户
 * 反复反馈的「上下渐变视觉」源头，2026-09-13 用户终裁对齐旧版代码删除渐变）。
 */
private const val CHROME_BAR_SOLID_ALPHA = 0xF2 / 0xFF.toFloat()

/** 四胶囊图标字形边长（任务Y Y2 对齐旧版 fragment_media_detail.xml:108-144 四枚 40dp
 *  ImageView 减 9dp padding = 22dp 实际字形；全局默认 QimengDimens.IconDefaultSize=24dp
 *  不动，仅底部 chrome 四胶囊显式取本档） */
private val CHROME_GLYPH_SIZE = 22.dp

/** 四胶囊容器水平内边距（任务Y Y2 对齐旧版 fragment_media_detail.xml:103/:105
 *  detailBottomDock paddingStart/End=24dp） */
private val CHROME_DOCK_PADDING_HORIZONTAL = 24.dp

/** 四胶囊容器下内边距（任务Y Y2 对齐旧版 fragment_media_detail.xml:106 paddingBottom=10dp；
 *  上内边距 6dp = 旧版 :104 paddingTop，同 QimengDimens.SpaceS 档不另开） */
private val CHROME_DOCK_PADDING_BOTTOM = 10.dp

/**
 * 四胶囊内容水平 padding 覆盖档（修复B 2026-09-14）：等宽四槽（weight(1f)+spacedBy）下
 * 胶囊内容固有宽 = 默认 ChipHorizontalPadding 14×2 + 图标 22 + 间隙 6 + labelBold 两字
 * ≈28 ≈ 84dp，已超 411dp 宽屏的等分槽宽 (411-48-24)/4 ≈ 84.75dp 的临界，「收藏/标签/作者」
 * 实测折成两行竖排（证据 .walk/u7/04_detail_capsules.png）——压至 10dp 后内容宽 ≈76dp <
 * 槽宽，单行居中。2026-09-14 用户「均匀统一+留间隔」拍板的配套档；胶囊件默认档
 * ChipHorizontalPadding=14dp 不动（其他场景零变化）。
 */
private val CHROME_CAPSULE_CONTENT_H_PADDING = 10.dp

/** chrome 图标按下压缩档（GUIDE_UI L173 按下反馈 0.92→1.0，旧版 addPressAnimation 同数值） */
private const val CHROME_PRESSED_SCALE = 0.92f

/** chrome 按下反馈时长（GUIDE_UI L173：100ms AccelerateDecelerateInterpolator 的 tween 近似） */
private const val CHROME_PRESS_ANIM_MS = 100

/**
 * 顶部 chrome（任务I I7，GUIDE_UI §详情页 L171）：返回（左）/ 当前序号 n/N（中）/
 * 信息（右），上浮于媒体舞台的半透明操作层；底色=旧版运行时纯色档（背景色
 * @ [CHROME_BAR_SOLID_ALPHA]，U5 根因注见常量）；
 * 图标 tint = onBackground（L161），中央计数 18sp Bold 同旧版 detailFileName 字号档。
 * 状态栏避让沿革：V2 曾撤除（2026-09-10，前提=壳层 Scaffold innerPadding 把内容区钉在
 * 状态栏线下，再加 inset padding 属双重避让）；X1 壳层改造（2026-09-12 任务X）解除 detail
 * 路由钉位后该前提失效——舞台盒顶=屏幕顶，按旧版口径「上下操作栏各自经 WindowInsets 加
 * padding」（GUIDE_UI L272-275）恢复 [Modifier.statusBarsPadding] 自管避让，渐变底仍延伸
 * 到状态栏背后（padding 在 background 之后，edge-to-edge 观感）。
 * 任务U6 显隐拆分（对齐旧版 setChromeVisible 逐帧语义）：本件（条容器=底色+避让+内边距）
 * 由调用方恒组合，底色随 [contentVisible] 瞬时切换（不参与动画）；仅条内内容包
 * AnimatedVisibility 淡入淡出（spec 见 [CHROME_CONTENT_FADE]）。
 * 批次序号沿用旧「i/N」数据源（batchIndex 0 基展示 1 基）；无批次上下文（batchIndex<0，
 * 深链单卡=待拍板 #21）不渲染计数——翻件语义边界：禁止擅自补批次上下文基建。
 */
@Composable
internal fun DetailTopChrome(
    contentVisible: Boolean,
    batchIndex: Int,
    batchSize: Int,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 任务U6 底色瞬时切换（旧版 setBackgroundColor 同帧语义直译）：显=纯色 @0xF2，
            // 隐=全透明——直三元，不包任何 animate*AsState，零过渡
            .background(if (contentVisible) chromeBarTint() else Color.Transparent)
            // X1 恢复自管避让（2026-09-12 任务X）：壳层不再钉位，舞台盒顶=屏幕顶 y=0，
            // 不避让则返回/序号钮被状态栏时钟遮挡；顺序=渐变→inset padding（渐变铺满
            // 状态栏区域）。沉浸态 chrome 隐藏、系统栏同隐（inset 归零），互不相扰
            .statusBarsPadding()
            .padding(horizontal = QimengDimens.SpaceS, vertical = QimengDimens.SpaceXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 仅条内内容参与淡入淡出（U6）；显式全限定取顶层函数——本作用域在 Row 内，
        // 不限定会误绑 RowScope.AnimatedVisibility 扩展（默认带 expand/shrinkHorizontally）。
        // 退出完成后内容离场、容器塌为避让+内边距高：底色已透明无视觉差；显时底色
        // 先瞬时回填、内容再淡入，同旧版逐帧观感
        androidx.compose.animation.AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(animationSpec = CHROME_CONTENT_FADE),
            exit = fadeOut(animationSpec = CHROME_CONTENT_FADE),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 内层 Row 承接原内容几何（weight 居中需 RowScope，AnimatedVisibilityScope 不是）
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        // 任务Y Y2 字号对齐旧版：中央计数 TextView textSize=18sp bold
                        // （fragment_media_detail.xml:80-82 detailFileName，MediaDetailFragment L406
                        // "%d/%d" 填充同源，文本格式见 strings.xml detail_batch_position）
                        Text(
                            text = stringResource(R.string.detail_batch_position, batchIndex + 1, batchSize),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            ),
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
    }
}

/**
 * 底部渐变操作层——任务W W3 重排（2026-09-12 任务书拍板）：四枚 icon+文字圆角胶囊
 * 「点赞N / 收藏 / 标签 / 作者」，样式=现行胶囊件 [DetailActionButton]（单源，不另起炉灶）。
 * 任务Y Y2 可对齐属性对齐旧版 detailBottomDock（fragment_media_detail.xml:103-106）：
 * 容器内边距左右 24dp/上 6dp/下 10dp（原 vertical 4dp/水平 0），图标字形 22dp
 * （[CHROME_GLYPH_SIZE]，旧版 40dp 容器减 9dp padding），收藏字形星形换心形
 * （[FavoriteFilledIcon]/[FavoriteBorderIcon]，旧版 ic_detail_favorite(-filled) 同源）；
 * 胶囊件结构本身不动（DetailSections.kt「四胶囊样式=现行胶囊件」拍板在案）。
 * 沿革：V3 四胶囊为「点赞N/收藏/标签/整理」（原位替换旧四纯图标行）；W3「整理」退役
 * 换「作者」——整理/删除/改名/移动入口随本批从详情页退役（后果已记档待拍板台账），
 * 作者胶囊点开 [DetailAuthorSheet]（原作者卡内容移植）。遮罩底色随 U5 改旧版运行时纯色
 * （见 [chromeBarTint]/[CHROME_BAR_SOLID_ALPHA]）；导航栏避让沿革：V2 曾撤除（前提=壳层内容区已钉在导航栏线下），
 * X1 壳层改造（2026-09-12 任务X）解除 detail 钉位后按旧版口径恢复 [Modifier.navigationBarsPadding]
 * 自管避让（GUIDE_UI L272-275），渐变底延伸到导航栏背后（K3c 背板条机制不动，胶囊在
 * 既有容器内替换）。收藏/点赞图标区分空心/实心态，激活态 =
 * primary 主色实底；点赞/收藏与原下滑区互动行同链（VM toggle，乐观 disabled 同源）。
 * 「快速转跳」不进四胶囊（主代理保守裁决，待用户确认）——首屏入口随旧图标行消失，
 * DetailJumpSheet 组件保留（挂载点保留无触发点，见 DetailScreen 注释）。
 * 任务U6 显隐拆分同 [DetailTopChrome]：条容器恒组合、底色随 [contentVisible] 瞬时切换，
 * 仅四胶囊内容包 AnimatedVisibility 淡入淡出（spec 见 [CHROME_CONTENT_FADE]）。
 */
@Composable
internal fun DetailBottomChrome(
    contentVisible: Boolean,
    asset: AssetDetail,
    likePending: Boolean,
    favoritePending: Boolean,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenTagSheet: () -> Unit,
    onOpenAuthorSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 任务U6 底色瞬时切换（同 DetailTopChrome，旧版 setBackgroundColor 同帧语义直译）
            .background(if (contentVisible) chromeBarTint() else Color.Transparent)
            // X1 恢复自管避让（2026-09-12 任务X）：壳层不再钉位，舞台盒底=屏幕底，胶囊
            // 若再不避让会压在手势导航栏上；顺序=渐变→inset padding（渐变铺满导航栏区域，
            // 旧版 bottom 渐变同观感）。沉浸态 chrome 隐藏、系统栏同隐（inset 归零），互不相扰。
            // Y2 内边距值对齐旧版 detailBottomDock（24/6/24/10，注释见常量档）
            .navigationBarsPadding()
            .padding(
                start = CHROME_DOCK_PADDING_HORIZONTAL,
                top = QimengDimens.SpaceS,
                end = CHROME_DOCK_PADDING_HORIZONTAL,
                bottom = CHROME_DOCK_PADDING_BOTTOM,
            ),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 仅条内内容参与淡入淡出（U6，全限定防误绑 RowScope 扩展，理由同 DetailTopChrome）
        androidx.compose.animation.AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(animationSpec = CHROME_CONTENT_FADE),
            exit = fadeOut(animationSpec = CHROME_CONTENT_FADE),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 内层 Row 承接胶囊排布几何（AnimatedVisibilityScope 不是 RowScope）。
            // 修复A（2026-09-14 用户拍板「均匀统一排布+之间留间隔做隔断」）：旧版
            // detailBottomDock 四图标 layout_weight=1 等宽四槽 + 第3/4枚 marginStart=20dp
            // 隔断；SpaceEvenly 的均分空隙观感为「挤在一起、间隙不统一」，改回旧版等宽槽
            // 形态=四胶囊各 weight(1f)（等宽 ⇔ 旧版 weight=1）+ spacedBy 固定间隔；
            // 20dp 无既有 token 档，曾取 SpaceL=12dp 近似。
            // 修复B（2026-09-14 折行回归修复）：SpaceL 下槽宽被挤压，「收藏/标签/作者」
            // 内容固有宽≈84dp 超槽宽实测折行——间隔降为 SpaceM=8dp 给内容让宽（窄屏
            // 防折行优先于隔断强度；内容 padding 同步压档，见
            // [CHROME_CAPSULE_CONTENT_H_PADDING]），槽内余量 + 8dp 构成胶囊间可视空隙。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 点赞N（icon+计数文字胶囊；激活 primary 实底，content 沿用原互动行点赞钮结构）
                DetailActionButton(
                    active = asset.likedToday,
                    enabled = !likePending,
                    contentDescription = stringResource(R.string.detail_like),
                    // 修复B：等宽四槽防折行（档位注释见常量与 CapsuleText）
                    singleLine = true,
                    contentHorizontalPadding = CHROME_CAPSULE_CONTENT_H_PADDING,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleLike,
                ) {
                    Icon(
                        imageVector = if (asset.likedToday) ThumbUpIcon else ThumbUpOutlinedIcon,
                        contentDescription = null,
                        modifier = Modifier.size(CHROME_GLYPH_SIZE),
                    )
                    CapsuleText(text = formatCount(asset.likeCount))
                }
                // 收藏（icon+文字；isFavorite 高亮；Y2 星形换心形对齐旧版字形）
                DetailActionButton(
                    active = asset.isFavorite,
                    enabled = !favoritePending,
                    contentDescription = stringResource(
                        if (asset.isFavorite) R.string.detail_favorite_active else R.string.detail_favorite,
                    ),
                    singleLine = true,
                    contentHorizontalPadding = CHROME_CAPSULE_CONTENT_H_PADDING,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleFavorite,
                ) {
                    Icon(
                        imageVector = if (asset.isFavorite) FavoriteFilledIcon else FavoriteBorderIcon,
                        contentDescription = null,
                        modifier = Modifier.size(CHROME_GLYPH_SIZE),
                    )
                    CapsuleText(
                        text = stringResource(
                            if (asset.isFavorite) R.string.detail_favorite_active else R.string.detail_favorite,
                        ),
                    )
                }
                // 标签（V3 新增胶囊=原黑圈标签/管理入口：开 DetailTagManageSheet 编辑链）
                DetailActionButton(
                    active = false,
                    enabled = true,
                    contentDescription = stringResource(R.string.detail_chrome_tag),
                    singleLine = true,
                    contentHorizontalPadding = CHROME_CAPSULE_CONTENT_H_PADDING,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenTagSheet,
                ) {
                    Icon(
                        imageVector = DetailSellIcon,
                        contentDescription = null,
                        modifier = Modifier.size(CHROME_GLYPH_SIZE),
                    )
                    CapsuleText(text = stringResource(R.string.detail_chrome_tag))
                }
                // 作者（W3 新增胶囊，替换退役的「整理」：原作者卡内容移植进 DetailAuthorSheet）
                DetailActionButton(
                    active = false,
                    enabled = true,
                    contentDescription = stringResource(R.string.detail_authors_title),
                    singleLine = true,
                    contentHorizontalPadding = CHROME_CAPSULE_CONTENT_H_PADDING,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenAuthorSheet,
                ) {
                    Icon(
                        imageVector = DetailAuthorIcon,
                        contentDescription = null,
                        modifier = Modifier.size(CHROME_GLYPH_SIZE),
                    )
                    CapsuleText(text = stringResource(R.string.detail_authors_title))
                }
            }
        }
    }
}

/**
 * chrome 条底色（任务U U5，2026-09-13 用户终裁）：主题背景色纯色 @ [CHROME_BAR_SOLID_ALPHA]——
 * 旧版运行时 `setBackgroundColor((0xF2 shl 24) | qmColorBg)` 逐字同构，昼夜随主题槽位
 * 自动适配（旧版 qmColorBg 日 #FAFAFA / 夜 #1A1A1A，与新主题 background 同值）。
 * 沿革：Y2/I7 曾按旧仓库 bg_detail_top/bottom_gradient.xml 复刻为上下渐变——该 drawable
 * 是运行时被覆盖的死资源，渐变观感即用户反复反馈的不适源头，本批删除。
 */
@Composable
internal fun chromeBarTint(): Color =
    MaterialTheme.colorScheme.background.copy(alpha = CHROME_BAR_SOLID_ALPHA)

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
