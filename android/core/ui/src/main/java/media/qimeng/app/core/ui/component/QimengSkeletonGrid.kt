package media.qimeng.app.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 首屏在途骨架屏占位行数（每列）：columns × 6 行给足整屏占位（2026-09-18 首页冷启动
 * 空白修复任务定案口径）。调用方经 `columns * QIMENG_SKELETON_GRID_ROWS` 折算 item 数。
 */
const val QIMENG_SKELETON_GRID_ROWS = 6

// ---------- 几何对齐常量（值与 QimengMediaGrid 私有常量同源，两处对齐勿单改） ----------

/** 网格项间额外间距=0（对齐 QimengMediaGrid.GRID_INTER_ITEM_SPACING：旧版卡间视觉距=卡自 padding×2） */
private val SKELETON_GRID_INTER_ITEM_SPACING = 0.dp

/** 卡片外层 padding=5dp（对齐 QimengMediaGrid.CARD_OUTER_PADDING，旧版 item_media_thumbnail.xml root padding） */
private val SKELETON_CARD_OUTER_PADDING = 5.dp

/** 卡片圆角=旧版 outline 24f **像素**值非 dp（对齐 QimengMediaGrid.LEGACY_CARD_CORNER_RADIUS_PX，
 *  使用处经 [LocalDensity] 运行时 toDp() 换算，不同密度设备观感一致） */
private const val SKELETON_CARD_CORNER_RADIUS_PX = 24f

// ---------- 脉动动画参数 ----------

/** 脉动半程时长（毫秒）：0.45→1.0 与 1.0→0.45 各占 400ms，整周期 800ms（任务定案「约 800ms 循环」柔和呼吸档） */
private const val SKELETON_PULSE_HALF_CYCLE_MS = 400

/** 脉动 alpha 下限：0.45 保底可见——骨架格在深浅主题下都不至于淡出成「隐形空白」 */
private const val SKELETON_MIN_ALPHA = 0.45f

/** 脉动 alpha 上限：1.0 全不透明（呼吸顶点=占位色原色） */
private const val SKELETON_MAX_ALPHA = 1.0f

/**
 * 骨架屏网格（2026-09-18 首页冷启动空白修复，ADR-0015 单机形态）：三 tab「列表空 && 首屏
 * 在途」分支的占位渲染，替代此前 loading 期间整页空白。
 *
 * 纯渲染组件（铁律 7）：不碰 API/仓库/ViewModel，颜色走 MaterialTheme token、动画与几何
 * 参数全部具名常量。几何对齐 [QimengMediaGrid] 网格（列数/项间距/卡外距/圆角/16:9 比例
 * 同 [thumbnailAspectRatio]），数据到达后骨架→真实网格逐格同位替换不跳动。
 *
 * 占位色取 surfaceVariant（任务定案；注：[QimengThumbnail] 实际占位底是 secondaryContainer
 * ——L1/U10-3 拍板——骨架浅一档以示「未就绪」与「图片占位」之别，皆主题 token 无裸色值）。
 *
 * @param columns 网格列数（与 QimengMediaGrid 同参，切列数时骨架同步跟随）
 * @param itemCount 占位格总数（调用方按 `columns * QIMENG_SKELETON_GRID_ROWS` 折算）
 * @param modifier 外部布局修饰（骨架页根级传 fillMaxSize 语义已内建，仅需额外定位时传入）
 */
@Composable
fun QimengSkeletonGrid(
    columns: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    // 单一 InfiniteTransition 驱动全部占位格共享同相位呼吸（比逐格动画省开销、视觉整齐）
    val pulse = rememberInfiniteTransition(label = "qimengSkeletonPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = SKELETON_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SKELETON_PULSE_HALF_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qimengSkeletonPulseAlpha",
    )
    // 旧版圆角是像素值：运行时按屏幕密度换算（QimengMediaGrid.AssetCard 同款写法），
    // 组件级解析一次供全部占位格复用
    val cellShape = RoundedCornerShape(
        with(LocalDensity.current) { SKELETON_CARD_CORNER_RADIUS_PX.toDp() },
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        // 几何对齐 QimengMediaGrid：顶距走视口外 modifier padding（滚动中顶隙恒定的同款语义）、
        // 项间距 0、左右 contentPadding=SpaceM——数据落地后网格位置零漂移
        modifier = modifier
            .padding(top = QimengDimens.SpaceM)
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(SKELETON_GRID_INTER_ITEM_SPACING),
        verticalArrangement = Arrangement.spacedBy(SKELETON_GRID_INTER_ITEM_SPACING),
        contentPadding = PaddingValues(start = QimengDimens.SpaceM, end = QimengDimens.SpaceM),
    ) {
        items(itemCount) {
            SkeletonCell(shape = cellShape, pulseAlpha = pulseAlpha)
        }
    }
}

/**
 * 单个骨架占位格：5dp 外距 + 16:9 圆角矩形 + surfaceVariant 脉动底。
 * 脉动改颜色 alpha 而非 graphicsLayer 整体透明度：底色向页面底面收敛渐隐，
 * 不露出下层内容（graphicsLayer alpha 会把占位格透成「洞」）。
 */
@Composable
private fun SkeletonCell(shape: RoundedCornerShape, pulseAlpha: Float) {
    Box(
        modifier = Modifier
            .padding(SKELETON_CARD_OUTER_PADDING)
            .fillMaxWidth()
            .thumbnailAspectRatio()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = pulseAlpha)),
    )
}
