package media.qimeng.app.core.model

/**
 * 统计页时段档位（C1 用户拍板：四档对齐 Web DataPage）。
 * UI 文案档位与 `/stats/trends` 的 range 参数解耦——映射收敛在 [apiRange]，
 * 统计页 ViewModel 只认本枚举，禁止直接摸 range 字符串。
 */
enum class StatsRangeOption(val label: String) {
    SEVEN_DAYS("7天"),
    THIRTY_DAYS("30天"),
    NINETY_DAYS("90天"),
    ALL("全部"),
}

/**
 * 档位 → `/stats/trends` range 参数映射（openapi 8 档枚举的 4 档子集）。
 *
 * 【命名陷阱】30 天档传 `day` 而非直觉的 "30d"（协议里没有 30d）：
 * 服务端口径 range=day = **近 30 天逐日** 30 个桶（DOMAIN_RULES §5 窗口表），
 * 不是「单日」——协议侧改动须同步此处，反之亦然（openapi.yaml /stats/trends range 枚举）。
 */
val StatsRangeOption.apiRange: String
    get() = when (this) {
        StatsRangeOption.SEVEN_DAYS -> "7d"
        StatsRangeOption.THIRTY_DAYS -> "day"
        StatsRangeOption.NINETY_DAYS -> "90d"
        StatsRangeOption.ALL -> "all"
    }

/** 统计页默认档位（进页先看近 7 天，与 Web 数据页默认一致） */
val DEFAULT_STATS_RANGE: StatsRangeOption = StatsRangeOption.SEVEN_DAYS
