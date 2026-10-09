package media.qimeng.app.core.model

/**
 * 日期分组（DOMAIN_RULES §8 逐字口径；Web lib/format.ts dateLabel 同源复刻）：
 * 今天 / 昨天 / 距今 2~6 天→周X（周一~周日）/ 更早→yyyy-MM-dd / 无时间→未知日期。
 *
 * @param epochMs 时间戳（毫秒）；null/负值视为「未知日期」
 * @param nowMs 当前时间（由调用方注入，纯函数可测——不偷读系统时钟）
 */
fun dateLabel(epochMs: Long?, nowMs: Long): String {
    if (epochMs == null || epochMs < 0) return UNKNOWN_DATE_LABEL
    val today = startOfDay(nowMs)
    val day = startOfDay(epochMs)
    val diffDays = Math.round((today - day) / MS_PER_DAY.toDouble())
    return when {
        diffDays == 0L -> "今天"
        diffDays == 1L -> "昨天"
        diffDays in 2..6 -> WEEKDAY_LABELS[dayOfWeekIndex(day)]
        else -> formatYmd(day)
    }
}

/** 「未知日期」组标签（无时间的条目归此组；DOMAIN_RULES §8，恒排最后） */
const val UNKNOWN_DATE_LABEL = "未知日期"

/**
 * 组头格式（旧版实录逐字：`2026-09-05  2 项` / `尼尔 机械纪元  48 项`——
 * 标签与计数间两个空格；M4-2A-B2 拍板四页同款，分组函数内统一加后缀）。
 */
fun groupHeaderLabel(label: String, count: Int): String = "$label  $count 项"

/** 网格分组段：组头渲染一次、组内保持列表原序 */
data class GridSection(
    val label: String,
    val items: List<MediaAsset>,
)

/**
 * 按日期标签分组（纯函数）：同标签归并同组（组头只渲染一次），
 * 组间按组首时间降序，「未知日期」组恒排最后（Web 相册页 groups 同款语义，
 * 组键用什么时间由调用方决定：相册/收藏/搜索=modifiedAt，历史页=lastViewedAt）。
 * 组头带「N 项」后缀（[groupHeaderLabel]，旧版实录逐字口径）。
 *
 * @param dateCounts 服务端按**本地日历日**聚合的精确计数（协议 GET /assets
 *   dateCounts=true；key=[localDayKey] 口径）。命中即用真实总数——分页只加载
 *   前若干条时，组头数字不再随滚动跳增（2026-10-09 真机实测：今天 120→125、
 *   周三 66→282，都是「已加载条数」冒充总数）。未命中（未请求/翻页未带/
 *   「未知日期」组/服务端未覆盖的日）回退组内已加载条数——绝不显示比实际
 *   已加载更小的数字。
 */
fun List<MediaAsset>.groupByDateLabel(
    nowMs: Long,
    dateCounts: Map<String, Int> = emptyMap(),
    timestamp: (MediaAsset) -> Long?,
): List<GridSection> {
    val byLabel = LinkedHashMap<String, MutableList<MediaAsset>>()
    // 标签 → 本地日键（同标签必同日：标签由日界唯一确定；「未知日期」无键）
    val dayKeyByLabel = HashMap<String, String>()
    for (asset in this) {
        val ts = timestamp(asset)
        val label = dateLabel(ts, nowMs)
        byLabel.getOrPut(label) { mutableListOf() }.add(asset)
        if (ts != null && ts >= 0) {
            dayKeyByLabel.getOrPut(label) { localDayKey(ts) }
        }
    }
    return byLabel.entries
        .map { (label, assets) -> label to assets }
        .sortedWith(
            compareBy<Pair<String, List<MediaAsset>>> { it.first == UNKNOWN_DATE_LABEL }
                .thenByDescending { pair ->
                    pair.second.maxOfOrNull { timestamp(it) ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
                },
        )
        .map { (label, assets) ->
            val total = dayKeyByLabel[label]?.let { dateCounts[it] } ?: assets.size
            GridSection(groupHeaderLabel(label, total), assets)
        }
}

/**
 * 本地日历日键（yyyy-MM-dd，设备时区）——服务端 dateCounts 分桶键的客户端
 * 镜像：服务端按请求 tzOffsetMinutes 把 UTC 的 mtime 折算成本地日，客户端
 * 按同一设备时区折算，两侧同键才能对齐（:core:data 的 SDK 边界用同一个
 * 偏移量构造 tzOffsetMinutes，见 SdkMediaRepository.assets）。
 */
fun localDayKey(epochMs: Long): String = formatYmd(startOfDay(epochMs))

/**
 * 设备当前时区相对 UTC 的偏移分钟数（东八区=480）——协议 GET /assets 的
 * tzOffsetMinutes 取值来源。dateCounts 分桶与 dateFrom/dateTo 的本地日
 * 解释共用同一个值（服务端只认偏移量，不猜客户端时区）。
 * 每请求现算而不是缓存：DST 切换后立刻生效。
 */
fun deviceTzOffsetMinutes(nowMs: Long = System.currentTimeMillis()): Int =
    java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000


/** 一天的起点（本地时区；与 Web new Date(y,m,d) 同义） */
private fun startOfDay(epochMs: Long): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = epochMs
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** Calendar.DAY_OF_WEEK（周日=1）→ WEEKDAY_LABELS 下标（周一开头） */
private fun dayOfWeekIndex(startOfDayMs: Long): Int {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = startOfDayMs
    return (calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
}

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private const val MS_PER_DAY = 86_400_000L

private fun formatYmd(startOfDayMs: Long): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = startOfDayMs
    val month = (calendar.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    return "${calendar.get(java.util.Calendar.YEAR)}-$month-$day"
}

/**
 * 相册页四模式分组（M4-2A-B2 拍板口径，纯函数单测锁定；旧版「全部」页 MediaGroupHelper 语义）：
 * - 分区/类型模式：沿用日期分组（DOMAIN_RULES §8）；
 * - 作品模式：常规文件按 `source` 分组（空→「其他」）、COS 文件按 COS 作者名分组
 *   （authorNames 首个，空→「其他」）。协议 AssetSummary 无 isCos 字段（零协议改动约束），
 *   COS 判定用字段代理：authorNames 非空 → 按 COS 作者归组——服务端对未匹配出处的
 *   常规文件与 COS 资产的 source 都填字面「其他」（非 null，不能以 source 有无判 COS），
 *   而 authorNames 仅 COS 资产非空（DOMAIN_RULES §4 作者行 = 常规出处分组 ∪ COS 作者），
 *   作者/文件平铺结构的 COS 资产（无作品子目录、cosWork=null）靠此归位作者组；
 *   已知近似：常规资产若带 TXT 作者（真实载荷 authorNames 恒空，仅合成数据会出现）
 *   会落入其作者组而非「其他」（该口径无协议字段可判，测试锁定所选规则）；
 * - 角色模式：分组键 = characters∪cosWork（裁决 P9-5；与服务端角色行同口径，不以 source
 *   有无作 COS 判别——source 为空但带 characters 的常规资产照常归角色组，不落「其他」）：
 *   characters 首个优先（服务端 asset_characters 一行=该资产全部角色 "+" 拼接的规范名，
 *   DOMAIN_RULES §4，故首个即完整组名），无角色按 `cosWork`，全空→「其他」（拍板条目 5）；
 * 组间按组首元素位置序（服务端 default 降序原序，LinkedHashMap 保序），「其他」组恒排末位。
 *
 * @param dateCounts 仅日期分组（分区/类型模式）消费：服务端按本地日聚合的
 *   精确计数，透传给 [groupByDateLabel]（口径见其 KDoc）。
 */
fun List<MediaAsset>.groupByAlbumDim(
    dim: AlbumDim,
    nowMs: Long,
    facetCounts: Map<String, Int> = emptyMap(),
    dateCounts: Map<String, Int> = emptyMap(),
): List<GridSection> = when (dim) {
    AlbumDim.PARTITION, AlbumDim.TYPE -> groupByDateLabel(nowMs, dateCounts) { it.modifiedAtMs }
    AlbumDim.AUTHOR -> groupByFirstOccurrence(facetCounts) { authorGroupKey(it) }
    AlbumDim.CHARACTER -> groupByFirstOccurrence(facetCounts) { characterGroupKey(it) }
}

/**
 * 作品模式组键（判别序：authorNames 首个非空 → COS 作者组；否则 source 非空 → 出处组；
 * 否则「其他」）。为什么 COS 作者优先：authorNames 仅 COS 资产非空（DOMAIN_RULES §4
 * 作者行 = 常规出处分组 ∪ COS 作者），而 source 恒非空——服务端对未匹配出处的常规文件
 * 与 COS 资产都填字面「其他」（非 null，/assets?includeCos=true 实测），source 有无不能作
 * COS 判别依据；旧「source 优先」判别把全部资产吞进「其他」组、与服务端 facets 作者行
 * 劈叉（P1 修复，真实载荷口径由测试锁定）。
 */
private fun authorGroupKey(asset: MediaAsset): String =
    asset.authorNames.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: asset.source?.takeIf { it.isNotBlank() }
        ?: OTHER_BUCKET_NAME

/**
 * 角色模式组键（裁决 P9-5：角色分组键 = characters∪cosWork，服务端角色行同口径）：
 * characters 首个优先（服务端 asset_characters 一行=该资产全部角色 "+" 拼接的规范名，
 * DOMAIN_RULES §4，故首个即完整组名），无角色按 cosWork，全空归「其他」。
 * 为什么不再以 source 有无作 COS 判别：COS 资产恒无角色行（characters 为空），
 * characters 优先不会把 COS 资产错挂常规角色组；而 source 缺失的常规资产
 * （有 characters）按旧判别会错落「其他」——判别式只看数据行本身。
 */
private fun characterGroupKey(asset: MediaAsset): String =
    asset.characters.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: asset.cosWork?.takeIf { it.isNotBlank() }
        ?: OTHER_BUCKET_NAME

/**
 * 按组键分组：同键归并同组（组头只渲染一次），组内保持列表原序，
 * 组间按组首元素位置序，「其他」组恒排末位（旧版「其他」药丸/组头置底同源口径）。
 * 优先使用 facetCounts（服务端排自身全量精确统计项）作为组头计数，避免分页加载中只显示
 * 局部内存数量导致「滑动中跳增递变」；无对应统计项（或「其他」桶）回退至 assets.size。
 */
private fun List<MediaAsset>.groupByFirstOccurrence(
    facetCounts: Map<String, Int> = emptyMap(),
    key: (MediaAsset) -> String,
): List<GridSection> {
    val byKey = LinkedHashMap<String, MutableList<MediaAsset>>()
    for (asset in this) {
        byKey.getOrPut(key(asset)) { mutableListOf() }.add(asset)
    }
    return byKey.entries
        .sortedWith(compareBy { it.key == OTHER_BUCKET_NAME })
        .map { (label, assets) ->
            val totalCount = facetCounts[label] ?: assets.size
            GridSection(groupHeaderLabel(label, totalCount), assets)
        }
}
