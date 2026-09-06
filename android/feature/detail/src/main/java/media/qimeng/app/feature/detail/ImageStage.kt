package media.qimeng.app.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil3.Image
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.target.Target
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.feature.detail.image.ZoomImageView

/**
 * 图片/动图舞台（3b 真身，替换 3a 占位桩）：ZoomImageView AndroidView 桥接（ADR-0014
 * 复杂自绘控件例外，见 [ZoomImageView] KDoc 的桥接说明与适配清单）+ Coil 原图加载。
 *
 * 口径：
 * - **原图不降采样（口径②）**：请求用 [AssetDetail.origUrl]（签名直链，「查看永远发原件」）
 *   且显式 `.size(Size.ORIGINAL)`——Coil 默认会按目标 View 尺寸自动降采样，必须显式关掉；
 *   GPU 上限防护在渲染侧由 ZoomImageView 分层兜底（长边>4096 走 SOFTWARE，搬运件已含）；
 * - **左右滑（拍板③）**：单指横滑 → onSwipe(±1) → 舞台动作（DetailScreen → VM.moveBy →
 *   push 叠栈导航）；放大态（>1.05x）横滑留给拖拽浏览不触发切换（旧版同语义）；
 * - **沉浸（口径）**：单击 → onToggleChrome 切换顶行/系统栏显隐。
 *
 * 回调下发说明（3d 解冻）：DetailStage.kt 冻结期经 LocalDetailStageActions 穿墙下发，
 * 解冻后改由 DetailMediaStage 以具名参数下发真动作（本文件仅消费形参，手势逻辑零改动）。
 *
 * @param onSiblingNavigate 左右滑切换相邻资产（DetailScreen → VM.moveBy → push 叠栈）
 * @param onToggleChrome 单击切换沉浸模式顶行/系统栏显隐
 */
@Composable
internal fun ImageStage(
    asset: AssetDetail,
    modifier: Modifier,
    onSiblingNavigate: (delta: Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    // 手势回调在 factory 里只 set 一次，经此桥转发到最新动作（组合局部值变化不重建 View）
    val bridge = remember { ZoomGestureBridge() }
    bridge.onSiblingNavigate = onSiblingNavigate
    bridge.onToggleChrome = onToggleChrome

    val context = LocalContext.current
    var zoomView by remember { mutableStateOf<ZoomImageView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ZoomImageView(ctx).also { view ->
                zoomView = view
                view.onSingleTap = { bridge.onToggleChrome() }
                // 旧版方向语义：dx<0（左滑）→ +1 = 下一张；delta 与 onSwipe 方向同义直传
                view.onSwipe = { direction -> bridge.onSiblingNavigate(direction) }
                view.contentDescription = ctx.getString(R.string.detail_image_zoom_desc)
            }
        },
    )

    // 原图加载：请求随资产切换重建；离屏（切资产 push 叠栈/返回）dispose 取消在途请求——
    // 与预加载 Disposable 同语义（LEGACY_REQUIREMENTS E：生命周期清理）
    DisposableEffect(zoomView, asset.id) {
        val view = zoomView
        val url = asset.origUrl
        if (view == null || url.isNullOrEmpty()) {
            onDispose { }
        } else {
            val request = ImageRequest.Builder(context)
                .data(url)
                // 口径②：不降采样。Size.ORIGINAL =「按原图尺寸解码」的显式表达；
                // 缺省时 Coil 会按目标 View 尺寸解析出降采样尺寸
                .size(Size.ORIGINAL)
                .target(
                    object : Target {
                        // coil3 多平台 Image → Android Drawable（asDrawable 官方转换，
                        // Coil ImageViewTarget 同款；参数为 Resources 档）
                        override fun onSuccess(result: Image) {
                            // setImageDrawable 内部做智能分层 + resetZoom（搬运件行为）
                            view.setImageDrawable(result.asDrawable(context.resources))
                        }
                    },
                )
                .build()
            val disposable = SingletonImageLoader.get(context).enqueue(request)
            onDispose { disposable.dispose() }
        }
    }
}

/** 手势回调桥：ZoomImageView 的 Kotlin 回调持一次性引用，经可变字段转发到最新舞台动作 */
private class ZoomGestureBridge {
    var onSiblingNavigate: (Int) -> Unit = {}
    var onToggleChrome: () -> Unit = {}
}
