// Package libraryrevision 维护全局库内容修订号（library revision）。
//
// 背景：客户端缩略图预取器等消费方每轮启动都要全量分页拉资产列表，大库
// 下这是唯一的流量大头。引入全局单计数器（不分库，GET /api/v1/library/
// revision）后，客户端记录上一轮拉取时的 revision，本轮读值未变即可整轮
// 跳过列表拉取；值变大则全量重拉。
//
// 职责（唯一真相源）：
//   - revision 的持久化（kv_settings 键 library_revision，migration 0003
//     的通用 KV 表，不新增 migration）与进程内读缓存；
//   - 原子自增（单条 UPDATE ... CAST(value AS INTEGER)+1，并发不丢增量）；
//   - 基线引导（键缺失时以 COUNT(assets)+1 播种，恒 ≥1，避免 0 与"客户端
//     无记录"语义混淆）。
//
// 自增触发点（bump 链）收口在两处，见 httpapi/revision.go：
//   - library.changed 事件订阅（覆盖扫描/watch/relink/enrich、上传、回收站
//     移入/恢复、整理移动、库删除与开关——凡发布该事件的写入路径）；
//   - 不发事件的写入路径显式调用（回收站物理删除/清空/到期清扫、
//     qimeng-backup 导入）。
//
// 边界（docs/ARCHITECTURE.md §5，ADR-0010）：
//   - 只依赖 store（kv 读写），被 httpapi 调用，禁止反向依赖 httpapi
//     （depguard 红线）；不感知扫描器（扫描侧经事件总线解耦）。
//   - 修订号是弱一致性信号：丢一次自增的后果是客户端多做一轮本可跳过的
//     同步（安全方向的失败），因此全部自增路径失败只告警、不阻塞业务。
package libraryrevision
