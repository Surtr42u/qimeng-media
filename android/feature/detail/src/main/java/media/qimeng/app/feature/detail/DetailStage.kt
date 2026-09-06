package media.qimeng.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.ui.component.THUMBNAIL_ASPECT_RATIO

/**
 * 媒体舞台编排器（自 DetailSections.kt 拆出，3a 纯移动零行为变化）：
 * 负责宽高比自适应（width/height 齐备且 w>0、h>0 用真实比，否则回退 16:9 缩略比）与
 * 媒体类型分发——图片/动图 → [ImageStage]，视频 → [VideoStage]。
 * 真身替换节奏：3b 图片左右滑/沉浸、3c 视频播放器。
 */
@Composable
internal fun DetailMediaStage(asset: AssetDetail) {
    // 局部拷贝避免跨模块属性 smart cast 限制（width/height 在 :core:model）
    val w = asset.width
    val h = asset.height
    val ratio = if (w != null && h != null && w > 0 && h > 0) {
        w.toFloat() / h.toFloat()
    } else {
        THUMBNAIL_ASPECT_RATIO
    }
    // 舞台根尺寸（黑底）：由编排器算好下传，两个 Stage 桩只管各自内容渲染
    val stageModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(ratio)
        .background(Color.Black)
    if (asset.mediaType == MediaKind.VIDEO) {
        VideoStage(
            asset = asset,
            // 已看完徽标数据位（3d）：判定口径待 3b/3c 落地后接真值，桩体暂不消费
            watched = false,
            modifier = stageModifier,
            onSiblingNavigate = {},
            onToggleChrome = {},
        )
    } else {
        ImageStage(
            asset = asset,
            modifier = stageModifier,
            onSiblingNavigate = {},
            onToggleChrome = {},
        )
    }
}
