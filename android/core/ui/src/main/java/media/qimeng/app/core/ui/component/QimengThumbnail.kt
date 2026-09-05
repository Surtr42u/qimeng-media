package media.qimeng.app.core.ui.component

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * 签名直链图片渲染（Coil 3 AsyncImage）：
 * - image/视频帧类资产传服务端缩略图直链（thumbUrl，静态 WebP）；
 * - animated_image 资产传**原件签名直链**（由调用方经 AssetOrigUrlResolver 解析，
 *   2026-09-06 拍板：动图缩略图必须动画；coil-gif 解码器在 QimengApplication 装配）。
 * 内存缓存由 Coil 单例 ImageLoader 统一管理（滚动复用/同 URL 命中零开销）。
 *
 * @param model 直链字符串；null = 占位（列表请求中或服务端未返回）
 */
@Composable
fun QimengThumbnail(
    model: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier,
    )
}

/** 列表卡片缩略图统一宽高比（480x270 旧版档 = 16:9） */
const val THUMBNAIL_ASPECT_RATIO = 16f / 9f

/** 便捷修饰段：列表卡片缩略图统一比例 */
fun Modifier.thumbnailAspectRatio(): Modifier = aspectRatio(THUMBNAIL_ASPECT_RATIO)

/**
 * 时长角标文本（旧版 §缩略图加载：m:ss / h:mm:ss 纯文字；纯函数便于复用）。
 */
fun formatDurationBadge(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0) return null
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
