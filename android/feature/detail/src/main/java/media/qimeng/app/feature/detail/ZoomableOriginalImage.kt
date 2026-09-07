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
 * ZoomImageView 桥接 + Coil 原图加载的公共内容件（D1 自 ImageStage 抽出）：排版态图片舞台与
 * 图片全屏查看覆盖层（[ImageFullScreenOverlay]）两处复用同一手势/加载链，杜绝复制分叉。
 *
 * 口径与原 ImageStage 实现逐字一致（抽取零行为变化）：
 * - **原图不降采样（口径②）**：请求用 [AssetDetail.origUrl]（签名直链，「查看永远发原件」）
 *   且显式 `.size(Size.ORIGINAL)`——Coil 默认会按目标 View 尺寸自动降采样，必须显式关掉；
 *   GPU 上限防护在渲染侧由 ZoomImageView 分层兜底（长边>4096 走 SOFTWARE，搬运件已含）；
 * - **手势语义（冻结）**：单击 → [onSingleTap]；单指横滑 → [onSwipe](±1)；双指缩放/双击
 *   toggle 全在 ZoomImageView 内部（搬运件行为，不因宿主场景分叉）。
 *
 * 排版态（[ImageStage]）：单击=打开全屏覆盖层、横滑=兄弟切换（D1 起）；
 * 全屏态（[ImageFullScreenOverlay]）：单击=退出覆盖层、横滑=兄弟切换直传同一回调链。
 *
 * @param onSingleTap 单击回调（语义由宿主场景定：排版态开全屏 / 全屏态退出）
 * @param onSwipe 左右滑切换相邻资产（方向同 ZoomImageView.onSwipe：+1=左滑下一张）
 */
@Composable
internal fun ZoomableOriginalImage(
    asset: AssetDetail,
    modifier: Modifier,
    onSingleTap: () -> Unit,
    onSwipe: (direction: Int) -> Unit,
) {
    // 手势回调在 factory 里只 set 一次，经此桥转发到最新动作（组合局部值变化不重建 View）
    val bridge = remember { ZoomGestureBridge() }
    bridge.onSingleTap = onSingleTap
    bridge.onSwipe = onSwipe

    val context = LocalContext.current
    var zoomView by remember { mutableStateOf<ZoomImageView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ZoomImageView(ctx).also { view ->
                zoomView = view
                view.onSingleTap = { bridge.onSingleTap() }
                // 旧版方向语义：dx<0（左滑）→ +1 = 下一张；delta 与 onSwipe 方向同义直传
                view.onSwipe = { direction -> bridge.onSwipe(direction) }
                view.contentDescription = ctx.getString(R.string.detail_image_zoom_desc)
            }
        },
    )

    // 原图加载：请求随资产切换重建；离屏（切资产 push 叠栈/返回/覆盖层关闭）dispose 取消在途
    // 请求——与预加载 Disposable 同语义（LEGACY_REQUIREMENTS E：生命周期清理）
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

/** 手势回调桥：ZoomImageView 的 Kotlin 回调持一次性引用，经可变字段转发到最新动作 */
private class ZoomGestureBridge {
    var onSingleTap: () -> Unit = {}
    var onSwipe: (Int) -> Unit = {}
}
