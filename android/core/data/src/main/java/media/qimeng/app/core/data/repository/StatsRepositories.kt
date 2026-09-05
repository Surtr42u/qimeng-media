package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.TrendPoint

/**
 * 统计页数据端口（M4-6）：总览 + 趋势。
 * range 参数只经 [media.qimeng.app.core.model.StatsRangeOption.apiRange] 产出，
 * 实现层不拼字符串（C1 映射单点收口）。
 */
interface StatsRepository {

    /** 总览（无参数；C2 静态数字卡数据源） */
    suspend fun overview(): StatsOverviewValues

    /** 趋势分桶（range = StatsRangeOption.apiRange） */
    suspend fun trends(range: String): List<TrendPoint>
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
 * Coil 磁盘缓存运行时操作端口（C5：清空按钮 + 容量核对）。
 * 实现委托给全局单例 ImageLoader 的 DiskCache（clear 非 suspend，IO 调用方自挪线程）。
 */
interface CoilCacheManager {

    /** 清空磁盘缓存（Coil DiskCache.clear()；耗时 IO，调用方须在 IO 线程调用） */
    fun clear()

    /** 当前已用字节数（DiskCache.size；DiskCache 未装配时为 null） */
    fun sizeBytes(): Long?

    /**
     * 档位上限字节数。Coil 3 的 DiskCache 接口不暴露 maxSize（只有 Builder 有），
     * 容量口径取当前持久化档位字节——与 ImageLoader 装配时读的是同一个 DataStore 键。
     */
    fun capacityBytes(): Long?
}
