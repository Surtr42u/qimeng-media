// Package stats 实现趋势分桶与按天聚合的纯函数口径。
//
// 为什么必须是纯函数：统计口径（浏览/点赞/收藏的计数规则）会随产品演进调整，
// 数据源是只追加的 ViewEvent 事件流，任何口径变更都要求能在历史事件上全量重算——
// 这只有纯函数（无 IO、无隐藏状态）才能保证。
//
// 口径权威：docs/DOMAIN_RULES.md §5（统计口径与趋势分桶规则，逐字遵守）；
// 桶标签格式对齐旧项目 StatsFormatHelper 测试锁定值。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：趋势分桶、按天聚合口径（ViewEvent 事件流→按天物化表的
//     重建编排在 httpapi.RebuildAssetDailyStatsFromEvents，不在本包）
//   - 禁止：任何 IO（也不依赖其他业务模块）
package stats
