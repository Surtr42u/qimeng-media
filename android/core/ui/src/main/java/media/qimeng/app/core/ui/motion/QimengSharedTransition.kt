package media.qimeng.app.core.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 共享元素转场接线单点（分支提案 exp#3，ui/expressive 分支，2026-09-09）。
 *
 * 为什么走 CompositionLocal 而非官方参数直传（SharedTransitionScope/AnimatedVisibilityScope
 * 逐层传参）：壳层 SharedTransitionLayout 包住整个 NavHost，两个 scope 若走参数要穿过
 * HomeScreen→QimengMediaGrid→AssetCard 三层签名，全部调用方连带改动；本地局部量让
 * AssetCard（core:ui）与 DetailScreen（feature:detail）零签名变化接入，试点回退面最小。
 * 代价是隐式依赖——由「哪些 destination 提供 [LocalNavAnimatedVisibilityScope]」决定参与
 * 范围：exp#3 试点只锁首页族，exp#6 起铺开到全部网格路由（相册/收藏/历史/搜索/作者集合，
 * 壳层逐 destination 包 provider），非网格路由读到 null 自动不参与，组件侧零改动。
 *
 * 试点红线（任务书）：顶层四 Tab 的 NavHost enter/exit 转场保持 None 不动（L2 拍板）；
 * 共享元素只连续化「网格卡→详情舞台」的边界，不改 K 卷沉浸结构与排版。
 */

/** 壳层 SharedTransitionLayout 的 scope（QimengNavHost 在 NavHost 外层 provide 一次） */
val LocalNavSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** 当前 destination 的转场 scope（壳层在网格路由与详情页的 composable 内 provide——exp#6 起
 *  覆盖全部网格路由，非网格路由缺位自动不参与） */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 网格卡 ↔ 详情舞台共享元素 key 前缀（单源；两端经 [qimengAssetPosterSharedBounds] 同源拼 key） */
private const val ASSET_POSTER_SHARED_KEY_PREFIX = "asset-poster:"

/**
 * 首页网格卡 ↔ 详情页媒体舞台的共享边界（exp#3 试点）。
 *
 * 任一 scope 缺位（非试点页面 / 壳层未包 SharedTransitionLayout）时原样返回 this——
 * 组件在非试点上下文渲染零变化，这就是「铺开=壳层多 provide、回退=壳层删 provide」的单点。
 * 用 sharedBounds 而非 sharedElement：两端内容不同构（卡=16:9 crop 缩略图+角标，详情舞台=
 * contain 全屏海报/播放器），sharedBounds 画两个占位并交叉淡入淡出，ContentScale 差异
 * （官方限制：不参与动画）以首帧跳变形式存在，试点口径=可接受（任务书明示）。
 */
@Composable
fun Modifier.qimengAssetPosterSharedBounds(assetId: String): Modifier {
    val sharedScope = LocalNavSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(key = ASSET_POSTER_SHARED_KEY_PREFIX + assetId),
            animatedVisibilityScope = animatedScope,
        )
    }
}
