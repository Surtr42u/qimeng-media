package media.qimeng.app.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import media.qimeng.app.core.model.AssetDetail

/**
 * 图片/动图舞台：整屏黑底舞台内提供 ZoomImageView 手势与 Coil 原图加载
 * （实现下沉到公共内容件 [ZoomableOriginalImage]）。
 *
 * 任务I I7 交互改版——**单击图片舞台 = 切换沉浸 chrome**（GUIDE_UI §沉浸浏览 L271-276：
 * 单击显隐 App 顶/底操作层 + 系统栏，chrome 即沉浸交互主形态）。旧 D1「单击开全屏查看
 * 覆盖层」随基准切回 GUIDE_UI 退役（裁决记档：沉浸结构下媒体舞台本就 edge-to-edge 全出血，
 * D1 覆盖层的存在理由——排版态舞台缩 68vh 的「半成品全屏」——已消失；保留入口反而与
 * 「单击切 chrome」手势冲突，故 ImageFullScreenOverlay.kt 整件退役、ZoomableOriginalImage
 * 保留为舞台唯一内容件）。左右滑兄弟切换语义不变（onSwipe 直传）。
 *
 * @param onSiblingNavigate 左右滑切换相邻资产（DetailScreen → VM.moveBy → push 叠栈）
 * @param onToggleChrome 单击舞台切换沉浸 chrome 显隐（DetailScreen 持开关状态单源）
 */
@Composable
internal fun ImageStage(
    asset: AssetDetail,
    modifier: Modifier,
    onSiblingNavigate: (delta: Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    ZoomableOriginalImage(
        asset = asset,
        modifier = modifier,
        onSingleTap = onToggleChrome,
        onSwipe = onSiblingNavigate,
    )
}
