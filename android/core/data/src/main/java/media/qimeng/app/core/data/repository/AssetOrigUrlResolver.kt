package media.qimeng.app.core.data.repository

import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AssetOrigUrlResolver] 实现：内存 LRU 缓存解析结果（同一资产只出网一次），
 * 失败/缺失返回 null（UI 回退服务端静态缩略图，不重试风暴）。
 */
@Singleton
class CachingAssetOrigUrlResolver @Inject constructor(
    private val mediaRepository: MediaRepository,
) : AssetOrigUrlResolver {

    private val cache = object : LruCache<String, String>(CACHE_MAX_ENTRIES) {}

    override suspend fun origUrl(assetId: String): String? {
        cache.get(assetId)?.let { return it }
        val url = runCatching { mediaRepository.assetOrigUrl(assetId) }.getOrNull()
        if (url != null) cache.put(assetId, url)
        return url
    }

    companion object {
        /** 缓存条目上限（动图在网格中同屏量级小，500 足够覆盖滚动会话） */
        const val CACHE_MAX_ENTRIES = 500
    }
}
