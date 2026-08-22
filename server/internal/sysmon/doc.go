// Package sysmon 负责系统与业务监控的采集层（纯数据层，不接路由）。
//
// 两块职责（docs/OBSERVABILITY.md）：
//   - 系统指标：Collector.Snapshot 用 gopsutil 采集 CPU/内存/磁盘/网络快照，
//     输出结构对齐 api/openapi.yaml 的 SystemStatus schema（供 /api/v1/system/status）。
//   - 业务指标：BusinessMetrics 用 prometheus client_golang 承载请求/媒体流量/
//     扫描/上传/回收站埋点，Handler() 输出 Prometheus 文本（供 /metrics）。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：系统指标采集 + 业务指标暴露
//   - 不做：HTTP 路由接线、鉴权、采样调度（由 httpapi 与装配层驱动）
//
// 日志规范（OBSERVABILITY「循环内禁止逐条日志」）：本包采集与指标更新路径
// 不打日志，单项采集失败以聚合错误返回，由调用方决定如何记录。
package sysmon
