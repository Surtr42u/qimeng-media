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
import coil3.request.CachePolicy
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
) {
    // 手势回调在 factory 里只 set 一次，经此桥转发到最新动作（组合局部值变化不重建 View）
    val bridge = remember { ZoomGestureBridge() }
    bridge.onSingleTap = onSingleTap
    bridge.onSwipe = onSwipe
    bridge.onZoomImmersiveChanged = onZoomImmersiveChanged

    val context = LocalContext.current
    var zoomView by remember { mutableStateOf<ZoomImageView?>(null) }

    // 解码失败态（RES #27）：只反映「最近一次完成的结果」——请求发起时清零，
    // onError 置位、onSuccess 清零；重试经 [retryAttempt] 递增触发请求重建
    var decodeFailed by remember { mutableStateOf(false) }
    // 失败归因（2026-09-18 文案分档；第三百六十四笔扩三档 +内存档）：Target.onError
    // 拿不到异常对象（coil3 只回调 null Image），归因走请求级 listener(onError) 的
    // ErrorResult.throwable（coil 3.6.2 字节码核实）
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
                    zoomView = view
                    view.onSingleTap = { bridge.onSingleTap() }
                    // 旧版方向语义：dx<0（左滑）→ +1 = 下一张；delta 与 onSwipe 方向同义直传
                    view.onSwipe = { direction -> bridge.onSwipe(direction) }
                    // 缩放沉浸只读回调同桥转发（直持 lambda 会捕获过期引用，纪律同上）
                    view.onZoomImmersiveChanged = { zoomed -> bridge.onZoomImmersiveChanged(zoomed) }
                    view.contentDescription = ctx.getString(R.string.detail_image_zoom_desc)
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

    // 原图加载：请求随资产切换/重试重建；离屏（切资产 push 叠栈/返回/覆盖层关闭）dispose 取消在途
    // 请求——与预加载 Disposable 同语义（LEGACY_REQUIREMENTS E：生命周期清理）
    DisposableEffect(zoomView, asset.id, retryAttempt) {
        val view = zoomView
        val url = asset.origUrl
        if (view == null || url.isNullOrEmpty()) {
            onDispose { }
        } else {
            // 新请求在途：清旧失败态（失败态只反映最近一次完成的结果）；占位翼已撤
            // （修复B记档见上），加载期透出调用方 backdrop，旧版同款不闪白
            decodeFailed = false
            failureKind = ImageFailureKind.DECODE
            val request = ImageRequest.Builder(context)
                .data(url)
                // 口径②：不降采样（LEGACY_REQUIREMENTS 用户拍板；堆水位对齐旧版由
                // manifest largeHeap 承担——2026-09-20 OOM 复发根因即迁移时丢失该声明）
                .size(Size.ORIGINAL)
                // 任务U10-5（2026-09-14 用户拍板）：原图即看即取**不落盘**——
                // 磁盘缓存只留给缩略图；原件体积大，落盘会让缓存无谓膨胀且损耗存储，
                // 联网观看的预期是「即看即取」而非「下载留存」（对齐旧版本地直读体验）。
                // 会话内回看由内存缓存兜底（memoryCachePolicy 全局 ENABLED）。
                .diskCachePolicy(CachePolicy.DISABLED)
                // 失败归因（文案分档）：异常经 cause 链判传输类（见 isNetworkTransferFailure）
                .listener(
                    onError = { _, result ->
                        failureKind = classifyImageFailure(result.throwable)
                    },
                )
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
