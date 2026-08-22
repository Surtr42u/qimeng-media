// Package sysmon 负责系统指标采集（CPU/内存/磁盘/网络/负载）。
//
// 为什么服务端自采指标：产品需求 #9 要求服务端有负载与流量监控，
// 内置仪表盘（客户端渲染）+ 标准 /metrics 端点（Prometheus 抓取）双通道输出。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：系统指标采集
package sysmon
