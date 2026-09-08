package media.qimeng.app.core.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.models.RecommendPrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统计/偏好/系统信息三端口的生成 SDK 实现（M4-6）。
 * 出网请求打 logcat 的约定与 [SdkMediaRepository] 一致（验收证据协议 = 文本证据）。
 */
@Singleton
class SdkStatsRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : StatsRepository {

    override suspend fun overview(): StatsOverviewValues {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /stats/overview")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        val overview = withContext(Dispatchers.IO) { api.apiV1StatsOverviewGet() }
        return StatsOverviewValues(
            totalFiles = overview.totalFiles ?: 0,
            imageCount = overview.imageCount ?: 0,
            videoCount = overview.videoCount ?: 0,
            totalSizeBytes = overview.totalSizeBytes ?: 0L,
            todayViews = overview.todayViews ?: 0,
            totalViews = overview.totalViews ?: 0L,
        )
    }

    override suspend fun trends(range: String): List<TrendPoint> = trends(range, null)

    override suspend fun trends(range: String, mediaType: String?): List<TrendPoint> {
        // range 只认 StatsRangeOption.apiRange 的产出（7d/day/all）；mediaType 只认协议三值
        // （image/video/animated_image），null=不过滤；此处打日志即验收证据
        Log.d(SdkMediaRepository.LOG_TAG, "GET /stats/trends range=$range mediaType=$mediaType")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        val sdkRange = when (range) {
            "7d" -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet._7d
            "day" -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet.day
            "90d" -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet._90d
            "all" -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet.all
            else -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet.month
        }
        val sdkMediaType = when (mediaType) {
            "image" -> media.qimeng.sdk.models.MediaType.image
            "video" -> media.qimeng.sdk.models.MediaType.video
            "animated_image" -> media.qimeng.sdk.models.MediaType.animated_image
            else -> null
        }
        val buckets = withContext(Dispatchers.IO) {
            api.apiV1StatsTrendsGet(range = sdkRange, mediaType = sdkMediaType)
        }
        return buckets.map { bucket ->
            TrendPoint(
                label = bucket.label.orEmpty(),
                viewCount = bucket.viewCount ?: 0,
                playCount = bucket.playCount ?: 0,
                seconds = bucket.seconds ?: 0,
            )
        }
    }
}

/** [RecommendPrefsRepository] 实现（GET 走生成 SDK；PUT 走 okhttp 直发，原因见类注释） */
@Singleton
class SdkRecommendPrefsRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
    private val okHttpClient: OkHttpClient,
) : RecommendPrefsRepository {

    override suspend fun prefs(): RecommendPrefsValues {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /recommendations/prefs")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        val prefs = withContext(Dispatchers.IO) { api.apiV1RecommendationsPrefsGet() }
        return prefs.toValues()
    }

    override suspend fun putPrefs(values: RecommendPrefsValues) {
        Log.d(SdkMediaRepository.LOG_TAG, "PUT /recommendations/prefs ${values.toLogString()}")
        val baseUrl = apiFactory.currentBaseUrl().trimEnd('/')
        // 数字字面量直拼（org.json 平台内置，零新依赖）：值域 [0,1] 权重，Double.toString 即合法 JSON number
        val json = org.json.JSONObject().apply {
            put("tagRelevance", values.tagRelevance)
            put("tagCollection", values.tagCollection)
            put("engagement", values.engagement)
            put("recency", values.recency)
            put("likeScore", values.likeScore)
            put("discovery", values.discovery)
            put("freshness", values.freshness)
            put("browseDepth", values.browseDepth)
            put("maxRandom", values.maxRandom)
        }.toString()
        val request = okhttp3.Request.Builder()
            .url(baseUrl + PUT_PREFS_PATH)
            .put(json.toRequestBody("application/json".toMediaType()))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("PUT prefs 失败: HTTP ${response.code}")
                }
            }
        }
    }

    private fun RecommendPrefs.toValues(): RecommendPrefsValues = RecommendPrefsValues(
        tagRelevance = tagRelevance?.toDouble() ?: 0.0,
        tagCollection = tagCollection?.toDouble() ?: 0.0,
        engagement = engagement?.toDouble() ?: 0.0,
        recency = recency?.toDouble() ?: 0.0,
        likeScore = likeScore?.toDouble() ?: 0.0,
        discovery = discovery?.toDouble() ?: 0.0,
        freshness = freshness?.toDouble() ?: 0.0,
        browseDepth = browseDepth?.toDouble() ?: 0.0,
        maxRandom = maxRandom?.toDouble() ?: 0.0,
    )

    private fun RecommendPrefsValues.toLogString(): String =
        "tagRel=$tagRelevance tagColl=$tagCollection engage=$engagement recency=$recency " +
            "like=$likeScore discov=$discovery fresh=$freshness depth=$browseDepth random=$maxRandom"

    companion object {
        /**
         * 协议路径直写（协议侧改动须同步此处，反之亦然；openapi.yaml /recommendations/prefs PUT）。
         * 为什么绕开生成 SDK：生成物 BigDecimalAdapter 把 number 序列化为 JSON 字符串
         * （`"0.1"` 带引号），服务端 *float32 拒收返回 400——生成物禁手改（ADR-0009），
         * 根治需 openapi schema 增 format: double 后重新 make sdk（已入交付报告存疑点）。
         * Bearer 注入仍走全局 OkHttpClient 的 AuthInterceptor，鉴权单点不破坏。
         */
        const val PUT_PREFS_PATH = "/api/v1/recommendations/prefs"
    }
}

/** [SystemInfoRepository] SDK 实现（C6：version 字段单值端口） */
@Singleton
class SdkSystemInfoRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : SystemInfoRepository {

    override suspend fun serverVersion(): String? {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /system/status")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        val status = withContext(Dispatchers.IO) { api.apiV1SystemStatusGet() }
        return status.version
    }
}

/** [DiskCachePrefsRepository] DataStore 实现（与搜索历史同一个 client_prefs 文件，不同键） */
@Singleton
class DataStoreDiskCachePrefsRepository @Inject constructor(
    @media.qimeng.app.core.data.di.ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) : DiskCachePrefsRepository {

    override val quota: Flow<DiskCacheQuota> = dataStore.data.map { prefs ->
        DiskCacheQuota.fromMb(prefs[KEY_QUOTA_MB] ?: DEFAULT_MB_UNSET)
    }

    override suspend fun setQuota(quota: DiskCacheQuota) {
        dataStore.edit { prefs ->
            prefs[KEY_QUOTA_MB] = (quota.bytes / DiskCacheQuota.BYTES_PER_MB).toInt()
        }
    }

    private companion object {
        /** 未持久化标记值（null 走 fromMb 的默认回落，这里显式用 -1 与任何合法 MB 值区分） */
        const val DEFAULT_MB_UNSET = -1

        val KEY_QUOTA_MB = intPreferencesKey("disk_cache_quota_mb")
    }
}
