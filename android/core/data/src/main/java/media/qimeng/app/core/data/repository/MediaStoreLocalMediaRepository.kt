package media.qimeng.app.core.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.model.LocalMediaBucket
import media.qimeng.app.core.model.LocalMediaItem

/**
 * [LocalMediaRepository] 的 MediaStore 实现：单查询走 MediaStore.Files 集合
 * （MEDIA_TYPE 过滤图片+视频，一次排序一次截断，免去 Images/Video 两查询合并）。
 *
 * 官方查证（铁律 8）口径：
 * - BUCKET_DISPLAY_NAME / MediaColumns.DURATION 均 API 29+（minSdk 26，须按版本装配）；
 * - 排序与行数上限走 ContentResolver.query(uri, projection, queryArgsBundle, signal)
 *   的 QUERY_ARG_SORT_COLUMNS / QUERY_ARG_LIMIT（API 26 起文档化支持，minSdk 恰为 26）；
 * - 记录 URI = ContentUris.withAppendedId(图片/视频集合 URI, _ID)，读流
 *   （ContentResolver.openInputStream）与 Coil 直载都吃这个 content:// URI。
 */
@Singleton
class MediaStoreLocalMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalMediaRepository {

    override suspend fun buckets(limit: Int): List<LocalMediaBucket> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext emptyList()
        val projection = arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        queryCursor(projection, selection = MEDIA_TYPE_SELECTION, selectionArgs = null, limit = BUCKET_SCAN_ROW_CAP)
            .use { cursor ->
                // 最近内容优先（查询已按 dateAdded 倒序）：遭遇序去重 + 窗口内计数，
                // 集满 limit 个相册即提前收手（避免大库全表扫描）
                val counts = LinkedHashMap<String, Int>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    counts[name] = (counts[name] ?: 0) + 1
                    if (counts.size >= limit) break
                }
                counts.map { (name, count) -> LocalMediaBucket(name = name, itemCount = count) }
            }
    }

    override suspend fun items(bucket: String?, limit: Int): List<LocalMediaItem> =
        withContext(Dispatchers.IO) {
            val hasBucketColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.SIZE)
                add(MediaStore.Files.FileColumns.MEDIA_TYPE)
                if (hasBucketColumn) {
                    add(MediaStore.MediaColumns.DURATION)
                    add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                }
            }.toTypedArray()
            val bucketFilter = bucket?.takeIf { hasBucketColumn }
            val selection = if (bucketFilter == null) {
                MEDIA_TYPE_SELECTION
            } else {
                "$MEDIA_TYPE_SELECTION AND ${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?"
            }
            val selectionArgs = bucketFilter?.let { arrayOf(it) }
            queryCursor(projection, selection, selectionArgs, limit).use { cursor ->
                val reader = ItemCursorReader(cursor, projection)
                val result = mutableListOf<LocalMediaItem>()
                while (cursor.moveToNext()) result += reader.read()
                result
            }
        }

    /**
     * 统一查询骨架：Bundle 携带排序（dateAdded 倒序）与行数上限（QUERY_ARG_LIMIT）。
     * cursor 返回 null（provider 异常）按不可用领域化，调用方给错误横幅。
     */
    private fun queryCursor(
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int,
    ): Cursor {
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            // 排序方向值常量 = QUERY_SORT_DIRECTION_*（QUERY_ARG_SORT_DIRECTION 是键名）
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection,
            args,
            null,
        ) ?: throw MediaStoreUnavailableException()
    }

    /** MediaStore 不可用（provider 异常/权限被剥）的领域化信号，调用方给错误横幅。 */
    class MediaStoreUnavailableException :
        IllegalStateException("MediaStore 查询返回 null cursor")

    /**
     * 行读取器（列索引解析一次、逐行复用）：MEDIA_TYPE 定图片/视频归属集合，
     * 记录 URI 拼回各自集合（ContentUris.withAppendedId 官方口径）。
     */
    private class ItemCursorReader(private val cursor: Cursor, projection: Array<String>) {
        private val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        private val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        private val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        private val typeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        private val durationIdx = projection.indexOf(MediaStore.MediaColumns.DURATION)
        private val bucketIdx = projection.indexOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

        fun read(): LocalMediaItem {
            val isVideo = cursor.getInt(typeIdx) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            return LocalMediaItem(
                uri = ContentUris.withAppendedId(collection, cursor.getLong(idIdx)).toString(),
                displayName = cursor.getString(nameIdx) ?: FALLBACK_DISPLAY_NAME,
                sizeBytes = if (cursor.isNull(sizeIdx)) -1L else cursor.getLong(sizeIdx),
                isVideo = isVideo,
                durationMs = if (durationIdx >= 0 && !cursor.isNull(durationIdx)) cursor.getLong(durationIdx) else null,
                bucketName = if (bucketIdx >= 0) cursor.getString(bucketIdx) else null,
            )
        }
    }

    private companion object {
        /** 只查媒体两类（Files 集合还含文档/音频等，一律排除——上传白名单口径） */
        val MEDIA_TYPE_SELECTION =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

        /** 展示名兜底（DISPLAY_NAME 理论恒在；防御性） */
        const val FALLBACK_DISPLAY_NAME = "未命名"

        /**
         * 相册 chips 的扫描行数安全阀：遭遇序集满 limit 个相册即提前 break，本上限只防
         * 「海量零散单文件相册」把扫描拖成全表遍历（宁少不卡）。
         */
        const val BUCKET_SCAN_ROW_CAP = 2000
    }
}
