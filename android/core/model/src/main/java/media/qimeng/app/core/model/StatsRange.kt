package media.qimeng.app.core.model

/**
 * 统计页时段档位（任务I I3 回改：2026-09-08「完全复刻」拍板覆盖旧 C1 四档，
 * 回 GUIDE_UI §数据统计页三档 7天/30天/全部；90 天档为任务G 对齐 Web 产物，废止）。
 * UI 文案档位与 `/stats/trends` 的 range 参数解耦——映射收敛在 [apiRange]，
 * 统计页 ViewModel 只认本枚举，禁止直接摸 range 字符串。
 */
enum class StatsRangeOption(val label: String) {
    SEVEN_DAYS("7天"),
    THIRTY_DAYS("30天"),
    ALL("全部"),
}

/**
 * 档位 → `/stats/trends` range 参数映射（openapi 8 档枚举的 3 档子集）。
 *
 * 【命名陷阱】30 天档传 `day` 而非直觉的 "30d"（协议里没有 30d）：
 * 服务端口径 range=day = **近 30 天逐日** 30 个桶（DOMAIN_RULES §5 窗口表），
 * 不是「单日」——协议侧改动须同步此处，反之亦然（openapi.yaml /stats/trends range 枚举）。
 */
val StatsRangeOption.apiRange: String
    get() = when (this) {
        StatsRangeOption.SEVEN_DAYS -> "7d"
        StatsRangeOption.THIRTY_DAYS -> "day"
        StatsRangeOption.ALL -> "all"
    }

/**
 * 档位 → `/rankings` period 参数映射（openapi 6 档枚举 day/week/month/quarter/year/all
 * 的 3 档子集；2026-09-18 内容榜批，数据页「常看文件」榜换源 /rankings 时新增）。
 *
 * 【命名陷阱】7 天档传 `week` 而非趋势侧直觉的 "7d"（/rankings period 没有 7d）——
 * 且 period 在此端点**只做准入过滤**（week=近 7 天内有浏览/播放/点赞记录的资产才进榜），
 * 排序仍按累计热度（view+play+like），不是窗口内增量——Web 数据页同款口径。
 * 协议侧改动须同步此处，反之亦然（openapi.yaml /rankings period 枚举）。
 */
val StatsRangeOption.rankingsPeriod: String
    get() = when (this) {
        StatsRangeOption.SEVEN_DAYS -> "week"
        StatsRangeOption.THIRTY_DAYS -> "month"
        StatsRangeOption.ALL -> "all"
    }

/** 统计页默认档位（进页先看近 7 天，与 Web 数据页默认一致） */
val DEFAULT_STATS_RANGE: StatsRangeOption = StatsRangeOption.SEVEN_DAYS

/**
 * 统计详情页标题的时间范围后缀（GUIDE_UI §统计详情页：动态标题含
 * 「· 近7天/近30天/全部」——与主页胶囊文案（7天/30天/全部）不同字，勿混用）。
 */
val StatsRangeOption.detailTitleSuffix: String
    get() = when (this) {
        StatsRangeOption.SEVEN_DAYS -> "近7天"
        StatsRangeOption.THIRTY_DAYS -> "近30天"
        StatsRangeOption.ALL -> "全部"
    }
