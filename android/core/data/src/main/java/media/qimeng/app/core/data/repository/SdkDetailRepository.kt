package media.qimeng.app.core.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.events.EventSyncScheduler
import media.qimeng.app.core.data.events.ViewEventQueue
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.models.ApiV1AssetsAssetIdFavoritePutRequest
import media.qimeng.sdk.models.ApiV1AssetsAssetIdTagsPutRequest
import media.qimeng.sdk.models.ApiV1TagsPostRequest
import media.qimeng.sdk.models.MoveRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DetailRepository] 的生成 SDK 实现（照 SdkMediaRepository 范式）：
 * BusinessApiFactory 按当前地址构造、全部调用挪 IO 线程（SDK 同步 execute）、
 * 每个出网请求打一行 logcat（验收证据协议 = 文本证据，HANDOVER_APP §4.7）。
 */
@Singleton
class SdkDetailRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
    private val viewEventQueue: ViewEventQueue,
    private val syncScheduler: EventSyncScheduler,
) : DetailRepository {

    override suspend fun assetDetail(assetId: String): AssetDetail {
        val api = apiFactory.create()
        logRequest("GET /assets/$assetId", "detail")
        val detail = withContext(Dispatchers.IO) {
            // 协议路径参数为 UUID；列表 DTO 给出的 id 字符串在此回解析（非法 id 走调用方 catch 兜底）
            api.apiV1AssetsAssetIdGet(UUID.fromString(assetId))
        }
        return SdkDetailMappers.toAssetDetail(detail, apiFactory.currentBaseUrl())
    }

    override suspend fun toggleLike(assetId: String): LikeToggleResult {
        val api = apiFactory.create()
        logRequest("PUT /assets/$assetId/like", "toggle")
        val state = withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdLikePut(UUID.fromString(assetId))
        }
        return SdkDetailMappers.toLikeToggleResult(state)
    }

    override suspend fun setFavorite(assetId: String, favorite: Boolean) {
        val api = apiFactory.create()
        logRequest("PUT /assets/$assetId/favorite", "favorite=$favorite")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdFavoritePut(
                assetId = UUID.fromString(assetId),
                apiV1AssetsAssetIdFavoritePutRequest = ApiV1AssetsAssetIdFavoritePutRequest(favorite = favorite),
            )
        }
    }

    override suspend fun allTags(): List<TagChip> {
        val api = apiFactory.create()
        logRequest("GET /tags", "pool")
        val tags = withContext(Dispatchers.IO) {
            api.apiV1TagsGet()
        }
        return tags.map(SdkDetailMappers::toTagChip)
    }

    override suspend fun createTag(name: String): TagChip {
        val api = apiFactory.create()
        logRequest("POST /tags", "name=$name")
        val tag = withContext(Dispatchers.IO) {
            api.apiV1TagsPost(ApiV1TagsPostRequest(name = name))
        }
        return SdkDetailMappers.toTagChip(tag)
    }

    override suspend fun replaceAssetTags(assetId: String, tagIds: List<String>) {
        val api = apiFactory.create()
        logRequest("PUT /assets/$assetId/tags", "tagIds=${tagIds.size}")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTagsPut(
                assetId = UUID.fromString(assetId),
                apiV1AssetsAssetIdTagsPutRequest = ApiV1AssetsAssetIdTagsPutRequest(tagIds = tagIds),
            )
        }
    }

    override suspend fun unbindTag(assetId: String, tagName: String) {
        // N3 #32 逐条删端点（{tag}=URL 编码后的标签名，SDK 自动转义）；幂等 204/404 语义见接口注释
        val api = apiFactory.create()
        logRequest("DELETE /assets/$assetId/tags/$tagName", "unbind")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTagsTagDelete(
                assetId = UUID.fromString(assetId),
                tag = tagName,
            )
        }
    }

    override suspend fun upNext(seed: Long, limit: Int, mediaType: MediaKind?, cosOnly: Boolean): List<MediaAsset> {
        val api = apiFactory.create()
        logRequest("GET /recommendations", "upnext seed=$seed limit=$limit mediaType=$mediaType cosOnly=$cosOnly offset=0")
        val items = withContext(Dispatchers.IO) {
            // offset 恒 0：推荐栏一次取一页，翻页=换 seed（Web useUpNextList 同参数语义）
            api.apiV1RecommendationsGet(
                seed = seed,
                limit = limit,
                offset = UP_NEXT_OFFSET,
                mediaType = mediaType?.toSdk(),
                cosOnly = cosOnly,
            )
        }
        // 列表 DTO 映射复用 SdkMappers（同模块 internal 可见，零改动零复制）
        val baseUrl = apiFactory.currentBaseUrl()
        return items.map { SdkMappers.toMediaAsset(it, baseUrl) }
    }

    override suspend fun reportProgress(assetId: String, positionSeconds: Double) {
        val api = apiFactory.create()
        logRequest("PUT /assets/$assetId/progress", "position=$positionSeconds")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdProgressPut(
                assetId = UUID.fromString(assetId),
                progressUpdate = SdkDetailMappers.toProgressUpdate(positionSeconds),
            )
        }
    }

    /**
     * 行为打点入队（M4-4 起不再是直连出网）：事件写进 Room 离线队列（pending_view_events，
     * 入队+FIFO 上限淘汰同一事务），**写成功即返回**——出网补传由队列三通道异步完成
     * （①写入即触发（本方法末尾）/②回前台 ON_START/③周期兜底，见 events/ 包）。
     * dwell 毫秒 = 停留秒×1000（队列口径存 ms，出网时 movePointLeft(3) 无损换算回秒）；
     * open/play 恒 0 不带 seconds。写失败原样抛异常：调用方（DetailViewModel）据此收窄
     * 静默口径为「写队列失败才静默」。
     */
    override suspend fun reportViewEvent(
        assetId: String,
        kind: ViewEventKind,
        startedAtMs: Long,
        sessionId: String,
        dwellSeconds: Long?,
    ) {
        val durationMs = (dwellSeconds?.coerceAtLeast(MIN_DWELL_SECONDS) ?: MIN_DWELL_SECONDS) * MS_PER_SECOND
        viewEventQueue.enqueue(assetId, kind, startedAtMs, durationMs, sessionId)
        logRequest("enqueue /events/view", "kind=$kind seconds=$dwellSeconds session=$sessionId")
        // 通道①：写入即入队补传（KEEP 合并瞬时多次触发；断网时任务照样跑、发送失败留在队列）
        syncScheduler.requestSyncNow()
    }

    override suspend fun timelineTags(assetId: String): List<TimelineTag> {
        val api = apiFactory.create()
        logRequest("GET /assets/$assetId/timeline-tags", "list")
        val tags = withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTimelineTagsGet(UUID.fromString(assetId))
        }
        return SdkDetailMappers.toTimelineTags(tags)
    }

    override suspend fun addTimelineTag(assetId: String, timeMillis: Long, name: String): TimelineTag {
        val api = apiFactory.create()
        logRequest("POST /assets/$assetId/timeline-tags", "timeMillis=$timeMillis")
        val tag = withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTimelineTagsPost(
                assetId = UUID.fromString(assetId),
                apiV1AssetsAssetIdTimelineTagsPostRequest =
                    SdkDetailMappers.toAddTimelineTagRequest(timeMillis, name),
            )
        }
        return SdkDetailMappers.toTimelineTag(tag)
    }

    override suspend fun deleteTimelineTag(assetId: String, tagId: String) {
        val api = apiFactory.create()
        logRequest("DELETE /assets/$assetId/timeline-tags/$tagId", "delete")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTimelineTagsTagIdDelete(
                assetId = UUID.fromString(assetId),
                tagId = tagId,
            )
        }
    }

    // ---------- 文件操作（任务G G1b：POST /move + DELETE=回收站） ----------

    override suspend fun moveAsset(assetId: String, targetDir: String, newName: String?) {
        val api = apiFactory.create()
        logRequest("POST /assets/$assetId/move", "targetDir=$targetDir newName=$newName")
        try {
            withContext(Dispatchers.IO) {
                api.apiV1AssetsAssetIdMovePost(
                    assetId = UUID.fromString(assetId),
                    moveRequest = MoveRequest(targetDir = targetDir, newName = newName),
                )
            }
        } catch (e: ClientException) {
            // 409 = 目标位置已有同名文件（服务端不覆盖）——领域化上抛（TagNameConflictException 同范式）
            if (e.statusCode == HTTP_CONFLICT) throw MoveConflictException()
            throw e
        }
    }

    override suspend fun deleteAsset(assetId: String) {
        val api = apiFactory.create()
        logRequest("DELETE /assets/$assetId", "trash")
        withContext(Dispatchers.IO) {
            // 铁律 4：DELETE 语义 = 移入回收站（可在维护页恢复），非物理删除
            api.apiV1AssetsAssetIdDelete(UUID.fromString(assetId))
        }
    }

    private fun logRequest(endpoint: String, params: String) {
        Log.d(SdkMediaRepository.LOG_TAG, "$endpoint $params")
    }

    companion object {
        /** 推荐栏取数偏移恒 0（换一批=换 seed 重取，同 seed 可复现——DOMAIN_RULES §1.1 禁纯随机） */
        private const val UP_NEXT_OFFSET = 0

        /** dwell 秒→毫秒换算系数（队列口径存 ms，见 reportViewEvent 注释） */
        private const val MS_PER_SECOND = 1000L

        /** dwell 秒数下界（open/play 的 dwellSeconds=null 归 0，即 durationMs 恒 0） */
        private const val MIN_DWELL_SECONDS = 0L

        /** HTTP 409：POST /move 目标位置同名（服务端不覆盖，领域化为 [MoveConflictException]） */
        private const val HTTP_CONFLICT = 409
    }
}
