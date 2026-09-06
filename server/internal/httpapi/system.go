// system.go：系统面板与 Prometheus 指标端点（sysmon 采集层的 httpapi 接线）。
// 采集实现与指标注册全在 sysmon 包，本层只做"调用 + 协议类型映射"。
package httpapi

import (
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/sysmon"
)

// GetApiV1SystemStatus 返回一次系统快照（CPU/内存/磁盘/网络/运行时长）。
//
// 部分采集失败（如单个挂载点失联）不构成 500：sysmon.Snapshot 的契约是
// "部分数据 + 聚合错误"，面板拿到零值项渲染"该项采集失败"比整页报错
// 有用（OBSERVABILITY 面板可用性优先）。
func (s *Server) GetApiV1SystemStatus(w http.ResponseWriter, r *http.Request) {
	if s.sysStatus == nil {
		writeErr(w, http.StatusServiceUnavailable, codeSysmonUnavailable, "系统监控未装配")
		return
	}
	st, err := s.sysStatus(r.Context())
	if err != nil {
		s.logger.Warn("系统快照部分采集失败", "err", err)
	}
	writeJSON(w, http.StatusOK, toGenSystemStatus(st))
}

// GetMetrics 代理 Prometheus 文本输出。指标集的注册与命名全在 sysmon
// （OBSERVABILITY.md 指标表），本层零加工——指标语义集中一处维护。
func (s *Server) GetMetrics(w http.ResponseWriter, r *http.Request) {
	if s.metrics == nil {
		writeErr(w, http.StatusServiceUnavailable, codeSysmonUnavailable, "系统监控未装配")
		return
	}
	s.metrics.ServeHTTP(w, r)
}

// genDisk 与 gen.SystemStatus.Disks 元素的内联结构体形状一致（类型别名，
// 两者可互换），让映射代码不必逐处重复匿名结构体字面量。
type genDisk = struct {
	Mount      *string `json:"mount,omitempty"`
	TotalBytes *int64  `json:"totalBytes,omitempty"`
	UsedBytes  *int64  `json:"usedBytes,omitempty"`
}

// toGenSystemStatus 把 sysmon 采集结构映射为协议生成类型。
//
// 为什么逐字段映射而不让 sysmon 直接输出协议 JSON：gen 类型是协议的
// 编译期表示，协议改动（加字段/改类型）在这里变成编译错误显式暴露，
// 而不是运行期 JSON 悄悄丢字段——字段对齐由编译器兜底。
func toGenSystemStatus(st sysmon.SystemStatus) gen.SystemStatus {
	disks := make([]genDisk, len(st.Disks))
	for i, d := range st.Disks {
		mount := d.Mount
		used, total := int64(d.UsedBytes), int64(d.TotalBytes)
		disks[i] = genDisk{Mount: &mount, UsedBytes: &used, TotalBytes: &total}
	}
	perCore := make([]float32, len(st.PerCore))
	for i, v := range st.PerCore {
		perCore[i] = float32(v)
	}
	cpu := float32(st.CPUPercent)
	memUsed, memTotal := int64(st.MemUsedBytes), int64(st.MemTotalBytes)
	rx, tx := int64(st.NetRxBytes), int64(st.NetTxBytes)
	uptime := st.UptimeSeconds
	version := st.Version
	return gen.SystemStatus{
		CpuPercent:    &cpu,
		PerCore:       &perCore,
		MemUsedBytes:  &memUsed,
		MemTotalBytes: &memTotal,
		Disks:         &disks,
		NetRxBytes:    &rx,
		NetTxBytes:    &tx,
		UptimeSeconds: &uptime,
		Version:       &version,
	}
}
