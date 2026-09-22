package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow
import media.qimeng.app.core.data.coil.CachePool
import media.qimeng.app.core.model.ThumbnailCacheProgress
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.MostViewedEntry
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.TrendPoint

/**
 * GET /rankings 单条（2026-09-18 内容榜批；映射自 AssetSummary）。
 * /rankings 按热度（view+play+like 累计）降序返回，但协议不直出热度值——展示角标取
 * [viewCount]（累计浏览次数）；标题口径与列表卡一致：cosWork 优先、回退 fileName
 * （SdkMappers.toMediaAsset 同款）。
 */
data class RankingEntry(
    val assetId: String,
    /** 展示标题（AssetSummary.cosWork ?: fileName，客户端卡片标题统一口径） */
    val title: String,
    /** 累计浏览次数（kind='open' 事件计数；「N 次」角标数据源） */
    val viewCount: Int,
)

/**
 * 统计页数据端口（M4-6；任务I I3 增补 mediaType 维度趋势取数口；N4 消费批 I3b 增补
 * 常看族三端点 + overview range 维度——N3 协议批 #31 解冻的 GET /stats/most-viewed、
 * /stats/top-authors、/stats/top-tags 与 /stats/overview 的 range 参数）。
 * range 参数只经 [media.qimeng.app.core.model.StatsRangeOption.apiRange] 产出，
 * 实现层不拼字符串（C1 映射单点收口）。
 *
 * 【边界注（I3 执行披露）】mediaType 形参为本批为清偿「分类型趋势按 mediaType 单值参数取数」
 * 裁定（口径现记 docs/DOMAIN_RULES.md §5；历史裁定出处见 CHANGELOG）
 * （详情页分类型趋势「mediaType 单值三次调用拼系列——按协议 /stats/trends mediaType 参数」）
 * 增补的加参改动：core:data 原不在 I3 授权共享文件清单，已向主会话报备未获回复，
 * 按裁定 6 的明示要求以「默认方法重载」最小面落地——单参方法保留为抽象（既有实现/调用零感知），
 * 双参默认实现委托单参，仅 SdkStatsRepository 覆写实传。如裁决另议，回退仅动本文件两处。
 *
 * 【边界注（N4 I3b）】本批新增四方法沿用同款「默认方法重载」先例：既有抽象方法不动
 * （既有实现/调用零感知），新增重载给默认实现（overview(range) 委托无参版、其余返回空表），
 * 仅 SdkStatsRepository 覆写实传——feature:stats 消费端经此四口取数。
 *
 * 【边界注（2026-09-18 内容榜批）】新增 [StatsRepository.rankings] 沿用同款先例：
 * 默认空表（既有实现零感知），仅 SdkStatsRepository 覆写实传。
 */
interface StatsRepository {

    /** 总览（无参数 = 协议缺省 range=all；库存两格静态数字卡数据源） */
    suspend fun overview(): StatsOverviewValues

    /**
     * 总览带窗口（N3 #31a：avgViewsPerFile 的统计窗口由 range 决定）。
     * 默认委托无参版（无 range 感知的实现回落协议缺省行为），SDK 实现覆写实传。
     */
    suspend fun overview(range: String): StatsOverviewValues = overview()

    /** 趋势分桶（range = StatsRangeOption.apiRange；全类型聚合） */
    suspend fun trends(range: String): List<TrendPoint>

    /**
     * 趋势分桶按媒体类型过滤（协议 GET /stats/trends 的 mediaType 单值参数：
     * image/video/animated_image；null=不过滤即全类型）。
     * 默认实现委托全类型查询，保证既有自定义实现不因加参编译破坏。
     */
    suspend fun trends(range: String, mediaType: String?): List<TrendPoint> = trends(range)

    /**
     * 趋势分桶按来源桶过滤（N3 #31b：source=normal|cos，口径同 §6 分区判定；
     * null=不过滤即常规∪COS）。默认委托 mediaType 版（丢 source），SDK 实现覆写实传。
     */
    suspend fun trends(range: String, mediaType: String?, source: String?): List<TrendPoint> =
        trends(range, mediaType)

    /**
     * 常看文件（N3 #31c；metric=views|seconds，DOMAIN_RULES §5 口径：views=窗口内
     * open 次数倒序 / seconds=窗口内 dwell 秒数累计倒序）。
     * 默认空表（无该端点感知的实现给空态，不崩），SDK 实现覆写实传。
     */
    suspend fun mostViewed(range: String, metric: String, limit: Int): List<MostViewedEntry> = emptyList()

    /**
     * 内容榜（2026-09-18 内容榜批：GET /rankings，Web 数据页同款口径——热度=浏览+播放+点赞
     * 累计倒序、period 只做准入过滤、排除 COS；数据页顶卡与详情页「按热度」档数据源，
     * 替换原 most-viewed metric=views 榜）。
     * period 只经 [media.qimeng.app.core.model.StatsRangeOption.rankingsPeriod] 产出，
     * 实现层不拼字符串（C1 映射单点收口，同 range 纪律）。
     * 默认空表（无该端点感知的实现给空态，不崩），SDK 实现覆写实传。
     */
    suspend fun rankings(period: String, limit: Int): List<RankingEntry> = emptyList()

    /** 常看作者（N3 #31d；窗口内作者关联资产 open 次数倒序）。默认空表，SDK 实现覆写实传。 */
    suspend fun topAuthors(range: String, limit: Int): List<TopAuthorEntry> = emptyList()

    /** 常看标签（N3 #31d；窗口内带标签资产 open 次数倒序）。默认空表，SDK 实现覆写实传。 */
    suspend fun topTags(range: String, limit: Int): List<TopTagEntry> = emptyList()
}

/** 推荐偏好端口（C4：GET/PUT /recommendations/prefs 9 维载荷） */
interface RecommendPrefsRepository {

    suspend fun prefs(): RecommendPrefsValues

    suspend fun putPrefs(values: RecommendPrefsValues)
}

/** 系统信息端口（C6：版本信息显示服务端版本） */
interface SystemInfoRepository {

    /** `GET /system/status` 的 version 字段；服务端未返回时为 null */
    suspend fun serverVersion(): String?
}

/**
 * Coil 磁盘缓存档位持久化端口（C5：DataStore 键值，重启生效——Coil 官方口径
 * 同目录多 DiskCache 实例并发会损坏缓存，运行中重建 ImageLoader 不做）。
 */
interface DiskCachePrefsRepository {

    /** 当前档位（字节）；未持久化时发射默认档 */
    val quota: Flow<DiskCacheQuota>

    suspend fun setQuota(quota: DiskCacheQuota)
}

/**
 * Coil 磁盘缓存运行时操作端口（C5 引入：清空 + 容量核对；批S5 2026-09-19 分池化——
 * 缩略图缓存按连接来源分「服务器（NAS）池/本地端池」两池（[CachePool]），统计与
 * 清空一律按池操作。两池内容按来源隔离、互不共享：两端库内容不同，共享会串图
 * （用户拍板冻结）。原聚合口径 clear/sizeBytes/fileCount/capacityBytes 随消费方
 * （缓存页两卡改分池展示）一并退役——「缓存上限」行拍板改为显示实际占用）。
 * 实现委托全局单例 [media.qimeng.app.core.data.coil.SplitDiskCache]
 * （clear 非 suspend，IO 调用方自挪线程）。
 */
interface CoilCacheManager {

    /** 清空指定池（子池 DiskCache.clear()；耗时 IO，调用方须在 IO 线程调用） */
    fun clearPool(pool: CachePool)

    /** 指定池当前已用字节数（子池 DiskCache.size；磁盘扫描属 IO，调用方自挪线程） */
    fun poolSizeBytes(pool: CachePool): Long

    /**
     * 指定池当前缓存条目数 = 已缓存图片张数（批S4 口径沿用、批S5 起分池）。
     * Coil 的 DiskCache 接口不暴露条目计数（3.6.2 官方 API 只有 size/maxSize/directory），
     * 口径取**池目录**里的数据文件数——每张缓存图恰一个数据文件（coil 3.6.2
     * DiskLruCache.kt Entry：每条目 = key.0 元数据文件 + key.1 数据文件，
     * RealDiskCache.kt valueCount=2/ENTRY_DATA=1；journal 与 .tmp 中转文件天然不匹配）。
     * 谓词单源与目录布局注记见 di 包 [media.qimeng.app.core.data.di.isDiskCacheDataFileName]。
     */
    fun poolFileCount(pool: CachePool): Int
}

/** 自动备份持久化态（2026-09-15 批：备份导入导出页的自动备份卡数据源） */
data class BackupAutoPrefs(
    val enabled: Boolean,
    val dirUri: String?,
    val lastRunMillis: Long,
)

/** 自动备份持久化端口（client_prefs DataStore，键值三件：开关/目录/上次运行） */
interface BackupAutoPrefsRepository {

    val state: Flow<BackupAutoPrefs>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setDirUri(uri: String?)

    suspend fun setLastRunMillis(millis: Long)
}

/** 缩略图覆盖进度端口（GET /thumbnails/progress 单值端口，缓存进度页轮询） */
interface ThumbnailProgressRepository {

    /** 读取失败（网络/服务端旧版本无此端点）返回 null，UI 显「—」降级 */
    suspend fun progress(): ThumbnailCacheProgress?
}
