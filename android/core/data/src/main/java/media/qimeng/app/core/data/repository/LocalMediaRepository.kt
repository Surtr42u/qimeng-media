package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.LocalMediaBucket
import media.qimeng.app.core.model.LocalMediaItem

/**
 * 本地媒体库数据端口（内置相册式选择器，2026-09-25 拍板）：MediaStore Images+Video 查询。
 * 实现走 ContentResolver（纯本地查询，不出网——无需 logcat 留证）；
 * 权限面（READ_MEDIA_IMAGES/READ_MEDIA_VIDEO/READ_EXTERNAL_STORAGE）由调用方在
 * 运行时申请后到达本端口，未授权时查询结果为空/抛 SecurityException。
 */
interface LocalMediaRepository {

    /**
     * 相册（bucket）聚合列表（过滤 chips 数据源）：按最近内容排序去重，取前
     * [limit] 个。API < 29 无 bucket 列，返回空列表（chips 行隐藏，仅「全部」语义）。
     * itemCount 为扫描窗口内计数（非全库精确值，选择器场景足够）。
     */
    suspend fun buckets(limit: Int): List<LocalMediaBucket>

    /**
     * 媒体条目（dateAdded 倒序，最多 [limit] 条）。[bucket] 非空 = 过滤该相册
     * （BUCKET_DISPLAY_NAME 精确匹配，与 [buckets] 返回的名字同源）。
     */
    suspend fun items(bucket: String?, limit: Int): List<LocalMediaItem>
}
