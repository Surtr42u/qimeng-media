package media.qimeng.app.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.ui.component.QimengThumbnail
import media.qimeng.app.core.ui.icon.PlayIcon

// ---------- 页面私有尺寸档（本文件单源；来源注释随条目） ----------

/** 舞台中央播放钮直径（48dp Material 最小触控档再升一级，防误触边缘） */
private val STAGE_PLAY_BUTTON_SIZE = 64.dp

/** 播放钮半透明底透明度（黑底白图标，Web 播放按钮同观感） */
private const val STAGE_PLAY_SCRIM_ALPHA = 0.6f

/**
 * 视频舞台（3a 占位桩，自 DetailSections.kt MediaStage 视频分支拆出，零行为变化）：
 * 缩略图 + 中央播放钮占位（真播放器 3c 替换，断点续播取 [AssetDetail.lastPositionSeconds]）。
 *
 * @param watched 已看完徽标数据位（3d）——**3a 桩暂不消费**，判定口径随 3b/3c 落地
 * @param onSiblingNavigate 左右滑切换相邻资产回调——**3a 桩暂不消费**：3b 接线（拍板③）
 * @param onToggleChrome 沉浸模式顶行开关回调——**3a 桩暂不消费**：3b 单击手势接线
 */
@Composable
internal fun VideoStage(
    asset: AssetDetail,
    watched: Boolean,
    modifier: Modifier,
    onSiblingNavigate: (delta: Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    Box(modifier = modifier) {
        QimengThumbnail(
            model = asset.thumbUrl,
            contentDescription = stringResource(R.string.detail_media_stage),
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = STAGE_PLAY_SCRIM_ALPHA),
            modifier = Modifier
                .align(Alignment.Center)
                .size(STAGE_PLAY_BUTTON_SIZE),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = PlayIcon,
                    contentDescription = stringResource(R.string.detail_play_video),
                    tint = Color.White,
                )
            }
        }
    }
}
