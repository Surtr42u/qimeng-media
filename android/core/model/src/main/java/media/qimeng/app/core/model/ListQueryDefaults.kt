package media.qimeng.app.core.model

/**
 * 列表页共享常量（2026-09-07 审查 P3 收敛单源）：相册/收藏/历史/搜索/首页此前
 * 各自复制同款常量，现统一落本文件——feature 间禁止互相依赖（ADR-0014 单向依赖），
 * core:model 经 core:ui api 传递对全部 feature 可见，是唯一合法共享层。
 */

/**
 * 列表分页大小：协议 GET /assets、GET /history 的 limit 缺省 60（上限 200）。
 * 协议默认值联动——改动须同步 openapi.yaml 的 limit 定义。
 */
const val LIST_PAGE_SIZE = 60

/**
 * 「全部」分区桶 key：协议 Partition 枚举字面值（enum [all, regular, cos]，openapi.yaml
 * components/schemas/Partition）。协议默认值联动——改动须同步 openapi.yaml。
 */
const val PARTITION_KEY_ALL = "all"

/**
 * 下拉刷新列表页通用加载失败文案（相册/收藏/历史/首页共用；文案含「下拉重试」仅适用于
 * 支持下拉刷新的列表场景，页面专属文案如搜索「请重试」仍留在各自 VM）。
 */
const val LIST_LOAD_FAILED_MESSAGE = "加载失败，请下拉重试"

/**
 * 下拉刷新成功但内容与刷新前完全一致的提示文案（2026-09-29「COS 下拉无效」反馈：
 * 刷新确实执行且成功，服务端返回同页同序数据、界面零变化，用户无法区分「没反应」
 * 还是「刷新了但没新的」——命中该情况时轻提示一次）。首页三流共用，单源落
 * core:model（feature 间禁止互相依赖，共享常量唯一合法层，见本文件头注）。
 */
const val LIST_UP_TO_DATE_MESSAGE = "已是最新，内容没有变化"

/**
 * 翻页（append）网络失败自动重试延迟序列（毫秒，2026-09-28 问题A「翻页失败即卡死」修复）：
 * 第 1 次失败后延迟 1s 重试一次、再失败延迟 3s 重试一次，仍失败才亮错误横幅停手。
 * 为什么只重试翻页不重试首载/刷新：首载已有各 VM 的首屏静默重试机制，刷新是用户显式
 * 动作（失败应立即反馈，横幅里已有「下拉重试」指引）；翻页失败的特殊性在于用户往往
 * 钉在列表底部等待，不重试就必须「上滚 6 项再回滚」才能再触发，体验断崖。
 * 延迟取值：1s 覆盖局域网 NAS 瞬时抖动（连接复用失败重建），3s 兜服务端扫描/整理
 * 造成的短窗繁忙；两次封顶防持续故障下无限锤击请求。
 * 相册/收藏/历史/搜索/作者集合/首页 COS 六处共用，单源落 core:model（feature 间禁止
 * 互相依赖，共享常量唯一合法层，见本文件头注）。
 */
val APPEND_RETRY_DELAYS_MS = listOf(1_000L, 3_000L)
