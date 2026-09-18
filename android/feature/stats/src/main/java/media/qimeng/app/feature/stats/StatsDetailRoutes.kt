package media.qimeng.app.feature.stats

import media.qimeng.app.core.model.StatsRangeOption

/**
 * 统计详情页路由契约单源（任务I I3，[media.qimeng.app.feature.detail.DetailRoutes] 同范式）：
 * 路由串/参数键收敛在 feature:stats——feature 禁依赖 :app（ADR-0010），壳层 QimengNavHost
 * 反向引用此处合法；mode/range 取枚举 name（纯 ASCII 标识符，无需 URL 编码）。
 *
 * GUIDE_UI §统计详情页四模式（N4 消费批 I3b 全量落地——N3 协议批 #31 解冻三端点后
 * 常看文件/常看作者标签两模式由冻结转可达成，冻结口径记档于 REPLICATION_GAPS §4）。
 */
enum class StatsDetailMode(val title: String) {

    /** 分类型趋势（图片/视频/动图多系列折线 + 来源浏览趋势 常规/COS 双系列） */
    TYPE_TREND("分类型趋势"),

    /** 内容榜（2026-09-18 批更名：原「常看文件」——详情页含内容榜 /rankings + seconds 榜双档） */
    MOST_VIEWED("内容榜"),

    /** 常看作者与标签（双排行卡：常看作者 Top15 + 常看标签 Top10） */
    AUTHORS_TAGS("常看作者与标签"),

    /** 分布统计（类型库存对比 + 来源构成对比卡） */
    DISTRIBUTION("分布统计"),
}

/**
 * 统计详情页路由（入栈隐藏底栏，覆盖页语义；标题由页面按 mode+range 组装——
 * GUIDE_UI §统计详情页「动态标题（含时间范围后缀）」）。
 */
object StatsDetailRoutes {

    /** 路由参数键（路由占位符与 StatsDetailViewModel SavedStateHandle 读取同键） */
    const val KEY_MODE = "mode"
    const val KEY_RANGE = "range"

    /** 统计详情页路由模式 */
    const val STATS_DETAIL_ROUTE = "stats_detail/{$KEY_MODE}/{$KEY_RANGE}"

    /** 路由构建（防 route 字符串第二次手抄；枚举 name 无需编码） */
    fun statsDetailRoute(mode: StatsDetailMode, range: StatsRangeOption): String =
        "stats_detail/${mode.name}/${range.name}"
}
