package media.qimeng.app.core.model

/**
 * 分区三态（DOMAIN_RULES §6 隔离口径）：
 * - [ALL] = 常规 ∪ COS 合并（缺省；请求侧须**显式**传 includeCos=true——/assets 服务端缺省排除 COS）
 * - [REGULAR] = 仅常规（/assets 缺省即常规：不传参）
 * - [COS] = 仅 COS（cosOnly=true；同真时服务端 cosOnly 优先）
 *
 * 到具体请求参数的映射在各页筛选状态机（[AlbumFilterState] / 搜索页分区胶囊 / 历史页），
 * 因为 /assets 与 /history 的「全部」缺省方向不同（assets 缺省排除 COS、history 缺省包含）。
 */
enum class Zone {
    ALL,
    REGULAR,
    COS,
}
