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
