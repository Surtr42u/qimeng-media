package media.qimeng.app.core.ui.component

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/**
 * 签名直链图片渲染（Coil 3 AsyncImage）：
 * - image/视频帧类资产传服务端缩略图直链（thumbUrl，静态 WebP）；
 * - animated_image 资产传**原件签名直链**（由调用方经 AssetOrigUrlResolver 解析，
 *   2026-09-06 拍板：动图缩略图必须动画；coil-gif 解码器在 QimengApplication 装配）。
 * 内存缓存由 Coil 单例 ImageLoader 统一管理（滚动复用/同 URL 命中零开销）。
 * 淡入关闭（任务L L1 旧版口径 crossfade(false)）：由全局单例 ImageLoader 统一配置
 * （core/data CoilModule.crossfade(false)，经 QimengApplication 的 SingletonImageLoader.Factory
 * 接入本组件）——组件内不重复配置，规格事实记档于此。
 *
 * @param model 直链字符串；null = 占位（列表请求中或服务端未返回）
 * @param paused 滚动暂停缩略图加载（任务I I5，GUIDE_UI §浏览历史 L394 / §收藏页 L411）：
 *   true 且尚未成功加载过 → 暂不下发请求只出占位底（滚动结束后恢复加载）；**已成功加载的
 *   保持画面不清空**（对齐旧版 Glide pauseOnScroll 语义：滚动中不闪白，仅推迟新请求）。
 *   默认 false（首页/搜索/全部页等既有调用方行为零变化）
 * @param diskCacheEnabled 磁盘缓存开关（2026-09-18 新增，默认 true 零行为变化）：
 *   调用方对**动图原件直链**传 false——原件不落盘（U10-5 口径外延到网格：原件体积大，
 *   落盘会挤爆 LRU 档位把小缩略图淘掉，反向造成「已缓存仍重新下载」；会话内回看由
 *   内存缓存兜底）。静态缩略图直链照常落盘不受影响。
 */
@Composable
fun QimengThumbnail(
    model: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    paused: Boolean = false,
    diskCacheEnabled: Boolean = true,
) {
    // 「成功加载过」逐 model 记账：paused 恢复后（或 model 换新）重置，重新参与暂停门控
    var loaded by remember(model) { mutableStateOf(false) }
    val context = LocalContext.current
    // 不落盘须构造请求级 ImageRequest；remember 防 recomposition 重建请求重复发起
    val uncachedModel = remember(model, diskCacheEnabled) {
        model?.takeIf { !diskCacheEnabled }?.let {
            ImageRequest.Builder(context).data(it).diskCachePolicy(CachePolicy.DISABLED).build()
        }
    }
    // 占位/错误底：旧版 ?attr/qmColorChipBg → 本项目主题角色映射 secondaryContainer
    // （Theme.kt 映射注释；日 #F0F0F2 / 夜 #2E2E2E 成对），任务L L1 起替代 surfaceVariant
    AsyncImage(
        model = when {
            paused && !loaded -> null
            uncachedModel != null -> uncachedModel
            else -> model
        },
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.secondaryContainer),
        error = ColorPainter(MaterialTheme.colorScheme.secondaryContainer),
        onSuccess = { loaded = true },
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
