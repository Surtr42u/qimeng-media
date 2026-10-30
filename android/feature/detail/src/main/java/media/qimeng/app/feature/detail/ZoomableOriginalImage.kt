package media.qimeng.app.feature.detail

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
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
 *   GPU 上限防护在渲染侧由 ZoomImageView 分层兜底（长边>4096 走 SOFTWARE，搬运件已含）。
 *   堆水位对齐：口径成立的前提是 largeHeap（旧版 manifest 同款声明，2026-09-20 迁移
 *   缺失致 OOM 复发已补回）；
 * - **手势语义（冻结）**：单击 → [onSingleTap]；单指横滑 → [onSwipe](±1)；双指缩放/双击
 *   toggle 全在 ZoomImageView 内部（搬运件行为，不因宿主场景分叉）。
 *
 * 解码失败兜底（RES #27，D7 发现的清偿）：损坏原件（截断 JPEG 等）此前解码失败后
 * 舞台黑屏无任何提示。现 Target.onError 触发舞台中央中文提示 + 重试/返回——不崩、
 * 不黑屏哑失败。Coil 的 Target 不区分网络失败与解码失败（onError 只回调 null Image），
 * 归因走请求级 listener(onError) 的 ErrorResult.throwable（[classifyImageFailure]
 * cause 链三档判别）：传输类 → 「网络不畅」（重试即恢复的多数派，2026-09-18 真机
 * BUG-A 复发把超时误报成「文件已损坏」后分档）、OOM → 「内存不足」（第三百六十四笔
 * 扩档：客户端异常表实证解码链 OOM 被误报成损坏）、其余 → 「无法解码」兜底。
 * 重试 = 重发请求（错误结果不入缓存，必然真重拉）。视频态不涉（Media3 播放器错误面
 * 自成体系，且 Web 端编码兼容提示条已按 2026-09-05 用户拍板移除，无可对照口径——
 * 记档见交付报告）。
 *
 * 图片舞台（[ImageStage]）：单击=切换沉浸 chrome、横滑=兄弟切换（I7 起）。缩放沉浸
 * （2026-09-13 用户反馈驱动，非旧版对齐）：放大跨过阈值经 [onZoomImmersiveChanged] 上报，
 * 宿主据此隐藏上下 chrome 渐变层与系统栏——bridge 转发链同手势回调（factory 只 set 一次）。
 *
 * @param onSingleTap 单击回调（语义由宿主场景定：舞台态切沉浸 chrome）
 * @param onSwipe 左右滑切换相邻资产（方向同 ZoomImageView.onSwipe：+1=左滑下一张）
 * @param onZoomImmersiveChanged 缩放沉浸上报（ZoomImageView 只读回调直转：true=跨过放大
 *   阈值、false=收束点回落；非对称滞回在搬运件 emitZoomImmersive 内，本层原样透传）
 * @param onExitDetail 解码失败覆盖层「返回」按钮（离开详情页，壳层 popBackStack 语义）
 */
@Composable
internal fun ZoomableOriginalImage(
    asset: AssetDetail,
    modifier: Modifier,
    onSingleTap: () -> Unit,
    onSwipe: (direction: Int) -> Unit,
    onZoomImmersiveChanged: (Boolean) -> Unit = {},
    onExitDetail: () -> Unit = {},
    onImageLoading: () -> Unit = {},
    onImageLoaded: (Int, Int, Double?) -> Unit = { _, _, _ -> },
) {
    // 手势回调在 factory 里只 set 一次，经此桥转发到最新动作（组合局部值变化不重建 View）
    val bridge = remember { ZoomGestureBridge() }
    bridge.onSingleTap = onSingleTap
    bridge.onSwipe = onSwipe
    bridge.onZoomImmersiveChanged = onZoomImmersiveChanged

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLoading by rememberUpdatedState(onImageLoading)
    val currentOnLoaded by rememberUpdatedState(onImageLoaded)
    // 请求属于页面，而非AndroidView实例；布局重建View不能重启同一原件传输。
    var displayedDrawable by remember(asset.id, asset.origUrl) { mutableStateOf<Drawable?>(null) }

    // 解码失败态（RES #27）：只反映「最近一次完成的结果」——请求发起时清零，
    // onError 置位、onSuccess 清零；重试经 [retryAttempt] 递增触发请求重建
    var decodeFailed by remember { mutableStateOf(false) }
    // 失败归因保留传输/解码/内存三档，直接使用执行结果的ErrorResult.throwable。
    var failureKind by remember { mutableStateOf(ImageFailureKind.DECODE) }
    var retryAttempt by remember { mutableIntStateOf(0) }

    // 加载期底色记档（修复B，2026-09-14）：exp#4 的整屏 secondaryContainer 灰色占位翼
    // 撤除——用户反馈深色模式下「上下条先显示、中间固定一块灰加载区」突兀，且旧版无此
    // 形态（旧 MediaDetailFragment.kt:441-442/:486-490 加载期透明底 + 保留上一张画面、
    // 切换不闪白，GUIDE_UI L177 同口径）。撤除后加载期舞台透出调用方打底的 backdrop
    // （K1 单源口径不动），与旧版观感一致；imageReady 状态随之退役，decodeFailed
    // 覆盖层与 Coil 加载链其余部分不变。VideoStage 的海报占位属视频预览链，另行裁量不动。

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ZoomImageView(ctx).also { view ->
                    view.onSingleTap = { bridge.onSingleTap() }
                    // 旧版方向语义：dx<0（左滑）→ +1 = 下一张；delta 与 onSwipe 方向同义直传
                    view.onSwipe = { direction -> bridge.onSwipe(direction) }
                    // 缩放沉浸只读回调同桥转发（直持 lambda 会捕获过期引用，纪律同上）
                    view.onZoomImmersiveChanged = { zoomed -> bridge.onZoomImmersiveChanged(zoomed) }
                    view.contentDescription = ctx.getString(R.string.detail_image_zoom_desc)
                }
            },
            onReset = null,
            onRelease = { view ->
                view.setImageDrawable(null)
            },
            update = { view ->
                if (view.drawable !== displayedDrawable) {
                    view.setImageDrawable(displayedDrawable)
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        (view.drawable as? Animatable)?.start()
                    }
                }
            },
        )

        if (decodeFailed) {
            DecodeErrorOverlay(
                failureKind = failureKind,
                onRetry = {
                    decodeFailed = false
                    retryAttempt += 1
                },
                onExit = onExitDetail,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // 单个页面持有一次加载。repeatOnLifecycle串行取消/恢复，View更新只消费结果。
    LaunchedEffect(asset.id, asset.origUrl, retryAttempt, lifecycleOwner) {
        val url = asset.origUrl ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                if (displayedDrawable == null) {
                    currentOnLoading()
                    decodeFailed = false
                    failureKind = ImageFailureKind.DECODE
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        // 查看保持原始像素、原件不落盘；会话内回看沿用全局内存缓存。
                        .size(Size.ORIGINAL)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build()
                    val result = SingletonImageLoader.get(context).execute(request)
                    // 网络/解码返回时页面可能已经暂停或换件，取消结果不能写回界面。
                    ensureActive()
                    if (result is SuccessResult) {
                        val image = result.image
                        displayedDrawable = image.asDrawable(context.resources)
                        val bitmap = (image as? BitmapImage)?.bitmap
                        val luminance = bitmap?.let { sampleTopRegionLuminance(it.width, it.height, it::getPixel) }
                        currentOnLoaded(image.width, image.height, luminance)
                    } else {
                        failureKind = classifyImageFailure((result as ErrorResult).throwable)
                        decodeFailed = true
                    }
                }
                (displayedDrawable as? Animatable)?.start()
                // 成功后的结果继续由本页持有；暂停只停动图，恢复不再次下载。
                awaitCancellation()
            } finally {
                (displayedDrawable as? Animatable)?.stop()
            }
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
    failureKind: ImageFailureKind,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                // 文案分档（2026-09-18 两档；第三百六十四笔扩 +内存档）：传输类提示网络
                // 问题、OOM 提示内存不足（均与「文件损坏」严格区分），其余保留「无法解码」兜底
                text = stringResource(
                    when (failureKind) {
                        ImageFailureKind.NETWORK -> R.string.detail_image_network_failed
                        ImageFailureKind.MEMORY -> R.string.detail_image_memory_failed
                        ImageFailureKind.DECODE -> R.string.detail_image_decode_failed
                    },
                ),
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

/** 手势/状态回调桥：ZoomImageView 的 Kotlin 回调持一次性引用，经可变字段转发到最新动作 */
private class ZoomGestureBridge {
    var onSingleTap: () -> Unit = {}
    var onSwipe: (Int) -> Unit = {}
    var onZoomImmersiveChanged: (Boolean) -> Unit = {}
}
