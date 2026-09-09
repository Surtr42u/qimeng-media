package media.qimeng.app.feature.detail

import android.graphics.drawable.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.Image
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.target.Target
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.feature.detail.image.ZoomImageView

/** 解码失败覆盖层文案区横向内边距（窄文案可读性；舞台中央提示不顶满宽） */
private val DECODE_ERROR_HORIZONTAL_PADDING = 24.dp

/**
 * ZoomImageView 桥接 + Coil 原图加载的公共内容件（D1 自 ImageStage 抽出）：图片舞台
 * 唯一内容件（旧第二消费者 ImageFullScreenOverlay.kt 随任务I I7 沉浸复刻退役——单击切
 * chrome 成为主形态，裁决记档见 ImageStage KDoc；本件手势/加载链零变化）。
 *
 * 口径与原 ImageStage 实现逐字一致（抽取零行为变化）：
 * - **原图不降采样（口径②）**：请求用 [AssetDetail.origUrl]（签名直链，「查看永远发原件」）
 *   且显式 `.size(Size.ORIGINAL)`——Coil 默认会按目标 View 尺寸自动降采样，必须显式关掉；
 *   GPU 上限防护在渲染侧由 ZoomImageView 分层兜底（长边>4096 走 SOFTWARE，搬运件已含）；
 * - **手势语义（冻结）**：单击 → [onSingleTap]；单指横滑 → [onSwipe](±1)；双指缩放/双击
 *   toggle 全在 ZoomImageView 内部（搬运件行为，不因宿主场景分叉）。
 *
 * 解码失败兜底（RES #27，D7 发现的清偿）：损坏原件（截断 JPEG 等）此前解码失败后
 * 舞台黑屏无任何提示。现 Target.onError 触发舞台中央中文提示 + 重试/返回——不崩、
 * 不黑屏哑失败。Coil 的 Target 不区分网络失败与解码失败，文案两者兼顾（「可能已损坏
 * 或格式不受支持」）；重试 = 重发请求（错误结果不入缓存，必然真重拉）。视频态不涉
 * （Media3 播放器错误面自成体系，且 Web 端编码兼容提示条已按 2026-09-05 用户拍板移除，
 * 无可对照口径——记档见交付报告）。
 *
 * 图片舞台（[ImageStage]）：单击=切换沉浸 chrome、横滑=兄弟切换（I7 起）。
 *
 * @param onSingleTap 单击回调（语义由宿主场景定：舞台态切沉浸 chrome）
 * @param onSwipe 左右滑切换相邻资产（方向同 ZoomImageView.onSwipe：+1=左滑下一张）
 * @param onExitDetail 解码失败覆盖层「返回」按钮（离开详情页，壳层 popBackStack 语义）
 */
@Composable
internal fun ZoomableOriginalImage(
    asset: AssetDetail,
    modifier: Modifier,
    onSingleTap: () -> Unit,
    onSwipe: (direction: Int) -> Unit,
    onExitDetail: () -> Unit = {},
) {
    // 手势回调在 factory 里只 set 一次，经此桥转发到最新动作（组合局部值变化不重建 View）
    val bridge = remember { ZoomGestureBridge() }
    bridge.onSingleTap = onSingleTap
    bridge.onSwipe = onSwipe

    val context = LocalContext.current
    var zoomView by remember { mutableStateOf<ZoomImageView?>(null) }

    // 解码失败态（RES #27）：只反映「最近一次完成的结果」——请求发起时清零，
    // onError 置位、onSuccess 清零；重试经 [retryAttempt] 递增触发请求重建
    var decodeFailed by remember { mutableStateOf(false) }
    var retryAttempt by remember { mutableIntStateOf(0) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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

        if (decodeFailed) {
            DecodeErrorOverlay(
                onRetry = {
                    decodeFailed = false
                    retryAttempt += 1
                },
                onExit = onExitDetail,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // 原图加载：请求随资产切换/重试重建；离屏（切资产 push 叠栈/返回/覆盖层关闭）dispose 取消在途
    // 请求——与预加载 Disposable 同语义（LEGACY_REQUIREMENTS E：生命周期清理）
    DisposableEffect(zoomView, asset.id, retryAttempt) {
        val view = zoomView
        val url = asset.origUrl
        if (view == null || url.isNullOrEmpty()) {
            onDispose { }
        } else {
            // 新请求在途：清旧失败态（失败态只反映最近一次完成的结果）
            decodeFailed = false
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
                            decodeFailed = false
                            val drawable = result.asDrawable(context.resources)
                            // setImageDrawable 内部做智能分层 + resetZoom（搬运件行为）
                            view.setImageDrawable(drawable)
                            // 旧版 Coil load() 的 ImageViewTarget 语义：动图（GIF）目标需显式
                            // start()，否则 AnimatedImageDrawable 停在第 0 帧（M4-3 D6 自查发现）
                            (drawable as? Animatable)?.start()
                        }

                        // 解码/加载失败（RES #27）：置失败态 → 舞台中央中文提示（Target
                        // 恒回调语义见 coil3 RealImageLoader.onError，无 error placeholder 例外）
                        override fun onError(error: Image?) {
                            decodeFailed = true
                        }
                    },
                )
                .build()
            val disposable = SingletonImageLoader.get(context).enqueue(request)
            onDispose { disposable.dispose() }
        }
    }
}

/**
 * 解码失败覆盖层（RES #27）：舞台中央中文提示 + 重试/返回两按钮。覆盖层自带纯黑底
 *（任务K K1 起舞台底色随主题/沉浸切换，黑底白面高对比改由覆盖层自身保证、不再依赖
 * 「舞台恒黑」假设；视觉与 K1 前完全一致，RES #27 口径不变），文字/按钮取纯白
 *（VideoStage 播放钮同款黑底白面前例）。
 */
@Composable
private fun DecodeErrorOverlay(
    onRetry: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.detail_image_decode_failed),
                color = Color.White,
                modifier = Modifier.padding(horizontal = DECODE_ERROR_HORIZONTAL_PADDING),
            )
            Row {
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.detail_retry), color = Color.White)
                }
                TextButton(onClick = onExit) {
                    Text(text = stringResource(R.string.detail_back), color = Color.White)
                }
            }
        }
    }
}

/** 手势回调桥：ZoomImageView 的 Kotlin 回调持一次性引用，经可变字段转发到最新动作 */
private class ZoomGestureBridge {
    var onSingleTap: () -> Unit = {}
    var onSwipe: (Int) -> Unit = {}
}
