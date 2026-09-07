package media.qimeng.app.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import media.qimeng.app.core.model.AssetDetail

/**
 * 图片/动图舞台：排版态在固定宽高比舞台内提供 ZoomImageView 手势与 Coil 原图加载
 * （实现下沉到公共内容件 [ZoomableOriginalImage]，D1 抽出供全屏覆盖层复用）。
 *
 * D1 交互改版——**排版态单击图片舞台 = 打开全屏查看覆盖层**（[ImageFullScreenOverlay]）：
 * 原「单击切沉浸」只隐顶行/系统栏、舞台缩在固定宽高比里不解锁全屏，用户视角是半成品全屏
 * （详情页 D1 任务根因）；沉浸显隐语义保留给视频舞台（视频两级全屏已于 D2 落地）。
 * 左右滑兄弟切换语义不变（onSwipe 直传）。
 *
 * @param onSiblingNavigate 左右滑切换相邻资产（DetailScreen → VM.moveBy → push 叠栈）
 * @param onOpenFullScreen 单击舞台打开图片全屏查看覆盖层（DetailScreen 持开关状态单源）
 */
@Composable
internal fun ImageStage(
    asset: AssetDetail,
    modifier: Modifier,
    onSiblingNavigate: (delta: Int) -> Unit,
    onOpenFullScreen: () -> Unit,
) {
    ZoomableOriginalImage(
        asset = asset,
        modifier = modifier,
        onSingleTap = onOpenFullScreen,
        onSwipe = onSiblingNavigate,
    )
}
