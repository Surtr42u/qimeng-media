package sysmon

import (
	"context"
	"errors"
	"fmt"
	"runtime"
	"sync"
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/mem"
	"github.com/shirou/gopsutil/v4/net"
)

// DefaultVersion 是未做任何注入时的构建版本占位值。
// 编译期通过 -ldflags "-X qimeng-media/server/internal/sysmon.DefaultVersion=v1.2.3" 注入；
// 运行期 Options.Version 非空时优先于它（便于测试与多实例注入不同版本号）。
var DefaultVersion = "dev"

// SystemStatus 是一次系统快照，字段与 api/openapi.yaml 的 SystemStatus schema 逐一对齐。
// 新增字段必须先改 openapi.yaml（协议先行），再同步本结构。
type SystemStatus struct {
	// CPUPercent 是整体 CPU 使用率（0~100），口径见 Collector.Snapshot。
	CPUPercent float64 `json:"cpuPercent"`
	// PerCore 是按逻辑核心的使用率明细（M2 仪表盘"按核心"面板用）。
	// 注意：openapi.yaml 的 SystemStatus 尚未声明该字段，接线 /api/v1/system/status
	// 时需同步补协议（额外字段对现有客户端向后兼容）。
	PerCore []float64 `json:"perCore"`
	// MemUsedBytes / MemTotalBytes 是系统内存已用/总量（口径见 Snapshot 内存段注释）。
	MemUsedBytes  uint64 `json:"memUsedBytes"`
	MemTotalBytes uint64 `json:"memTotalBytes"`
	// Disks 只包含 Options.Mounts 传入的挂载点/路径（媒体库与数据目录），不枚举全盘。
	Disks []DiskUsage `json:"disks"`
	// NetRxBytes / NetTxBytes 是自（首次）采集以来的全网卡累计下行/上行字节数，
	// 语义见 Snapshot 网络段注释。
	NetRxBytes uint64 `json:"netRxBytes"`
	NetTxBytes uint64 `json:"netTxBytes"`
	// UptimeSeconds 是本进程的运行秒数（不是整机 uptime——
	// OBSERVABILITY.md 表格里"运行时长"指进程 uptime，重启归零）。
	UptimeSeconds int64 `json:"uptimeSeconds"`
	// Version 是构建版本（Options.Version > DefaultVersion ldflags 注入值）。
	Version string `json:"version"`
}

// DiskUsage 是单个挂载点/路径的容量占用。
type DiskUsage struct {
	Mount      string `json:"mount"`
	UsedBytes  uint64 `json:"usedBytes"`
	TotalBytes uint64 `json:"totalBytes"`
}

// Options 是 Snapshot 的采集选项。挂载点列表由调用方（装配层）传入而非进 config——
// 媒体库路径是部署期才确定的运行时参数，采集层不关心它从哪来。
type Options struct {
	// Mounts 是要上报容量的挂载点/路径列表（如媒体库根目录、DataDir）。
	Mounts []string
	// Version 是构建版本号；空串时回落到 DefaultVersion（ldflags 注入点）。
	Version string
	// CPUInterval 是 CPU 使用率的采样窗口：
	//   - >0：阻塞该时长做两次采样（窗口精确，适合低频一次性探测/测试）；
	//   - =0：非阻塞，与上一次 Snapshot 调用差分（轮询模式：3s 轮询即得 3s 窗口均值）。
	CPUInterval time.Duration
}

// Collector 是系统快照采集器，持有差分所需的进程内基线。
// 零值不可用，必须 NewCollector 创建；并发调用 Snapshot 安全。
type Collector struct {
	startedAt time.Time

	mu sync.Mutex
	// lastCPU 是上次每核 CPU 时间快照（CPUInterval=0 差分模式的基线）。
	lastCPU []cpu.TimesStat
	// netBase* 是网络字节数基线，见 Snapshot 网络段注释。
	netBaseSet bool
	netBaseRx  uint64
	netBaseTx  uint64
}

// NewCollector 创建采集器。进程启动时刻以本调用近似（装配层在 main 里尽早调用）。
func NewCollector() *Collector {
	return &Collector{startedAt: time.Now()}
}

// Snapshot 采集一次系统快照。
//
// 错误语义：单项采集失败不会中断其余项——失败项保持零值并把错误用 errors.Join
// 聚合返回（调用方拿到"部分数据 + 错误"比拿到"全部丢失"更有用，3 秒轮询下
// 单项抖动不应该让整个面板接口 500）。
func (c *Collector) Snapshot(ctx context.Context, opts Options) (SystemStatus, error) {
	var errs []error
	st := SystemStatus{
		UptimeSeconds: int64(time.Since(c.startedAt) / time.Second),
		Version:       opts.Version,
	}
	if st.Version == "" {
		st.Version = DefaultVersion
	}

	overall, perCore, err := c.cpuPercent(ctx, opts.CPUInterval)
	if err != nil {
		errs = append(errs, fmt.Errorf("采集 CPU: %w", err))
	}
	st.CPUPercent, st.PerCore = overall, perCore

	vm, err := mem.VirtualMemoryWithContext(ctx)
	if err != nil {
		errs = append(errs, fmt.Errorf("采集内存: %w", err))
	} else {
		// 用 gopsutil 的 Used 字段而非 Total-Free：
		//   - Windows：Used = Total - Available，Available 含 standby cache，
		//     与任务管理器"内存"占用一致；
		//   - Linux：Used = Total - Free - Buffers - Cached，与 free 命令的 used 一致。
		// Total-Free 会把 page cache 全算成已用，Linux 上虚高数十 GB，误导排障。
		st.MemUsedBytes, st.MemTotalBytes = vm.Used, vm.Total
	}

	st.Disks = make([]DiskUsage, 0, len(opts.Mounts))
	for _, mount := range opts.Mounts {
		u, err := disk.UsageWithContext(ctx, mount)
		if err != nil {
			// 循环内不打日志（OBSERVABILITY「循环内禁止逐条日志」），聚合进错误。
			errs = append(errs, fmt.Errorf("采集磁盘 %s: %w", mount, err))
			continue
		}
		st.Disks = append(st.Disks, DiskUsage{
			Mount:      u.Path,
			UsedBytes:  u.Used,
			TotalBytes: u.Total,
		})
	}

	rx, tx, err := c.netSinceBase(ctx)
	if err != nil {
		errs = append(errs, fmt.Errorf("采集网络: %w", err))
	}
	st.NetRxBytes, st.NetTxBytes = rx, tx

	return st, errors.Join(errs...)
}

// cpuPercent 计算整体与每核 CPU 使用率。
//
// 为什么自采差分而不用 cpu.Percent(interval=0)：
//  1. gopsutil 的 interval=0 模式依赖包级隐藏全局状态（lastCPUPercent），
//     首次调用返回 error 而非 0，且与其他并发调用方共享状态，行为不可控；
//  2. Collector 自持基线与网络基线是同一模式（首调返回 0），语义统一；
//  3. 锁自持，并发安全可测。
//
// Windows/Linux 行为一致性：两平台的 cpu.Times 都填 User/System/Idle（Windows 无
// nice/iowait，值为 0），busy = total - idle - iowait 公式对两平台同时成立，
// 与任务管理器/资源监视器的"CPU 使用率"口径一致；差异只在数据源
// （Windows PDH 计数器 vs Linux /proc/stat），由 gopsutil 抹平。
func (c *Collector) cpuPercent(ctx context.Context, interval time.Duration) (float64, []float64, error) {
	cur, err := cpu.TimesWithContext(ctx, true) // percpu=true：一次读取同时服务整体与每核
	if err != nil {
		return 0, nil, err
	}

	c.mu.Lock()
	base := c.lastCPU
	c.lastCPU = cur
	c.mu.Unlock()

	if interval > 0 {
		// 阻塞窗口模式：以"刚才那次读取"为基线，睡满窗口后重采。
		// 用 select 而非裸 sleep，保证调用方取消 ctx 时能及时退出。
		select {
		case <-ctx.Done():
			return 0, nil, ctx.Err()
		case <-time.After(interval):
		}
		if cur, err = cpu.TimesWithContext(ctx, true); err != nil {
			return 0, nil, err
		}
		c.mu.Lock()
		c.lastCPU = cur
		c.mu.Unlock()
		base = nil // 窗口模式忽略历史基线，窗口首尾即差分端点
	}

	if base == nil {
		// 首次调用（或窗口模式首端）：没有基线可差分，返回全 0——
		// 与网络"首次调用基线为 0"同语义，前端把 0 渲染为"采样中"。
		return 0, make([]float64, len(cur)), nil
	}

	n := len(cur)
	if len(base) < n {
		n = len(base) // CPU 热插拔导致核数变化时只对齐公共前缀，防御性处理
	}
	perCore := make([]float64, n)
	var busyDelta, totalDelta float64
	for i := 0; i < n; i++ {
		t1, b1 := busyTimes(base[i])
		t2, b2 := busyTimes(cur[i])
		perCore[i] = calcPercent(t1, b1, t2, b2)
		totalDelta += t2 - t1
		busyDelta += b2 - b1
	}
	// 整体 = 全核忙碌增量 / 全核总增量（任务管理器口径），比简单平均更准确
	//（分母含各核全部时间，自动加权）。
	overall := clampPercent(busyDelta, totalDelta)
	return overall, perCore, nil
}

// busyTimes 把一次 CPU 时间采样折算成 (total, busy)。
// busy = total - idle - iowait，与 gopsutil 内部 getAllBusy 算法一致；
// Linux 的 guest 时间已被内核计入 user，需减去避免重复（Windows 上 guest 恒 0，
// 减 0 无影响——保证两平台同一公式）。
func busyTimes(t cpu.TimesStat) (total, busy float64) {
	total = t.User + t.System + t.Idle + t.Nice + t.Iowait + t.Irq + t.Softirq +
		t.Steal + t.Guest + t.GuestNice
	if runtime.GOOS == "linux" {
		total -= t.Guest + t.GuestNice
	}
	busy = total - t.Idle - t.Iowait
	return total, busy
}

// calcPercent 计算单个核心的区间使用率；两次采样无间隔（总增量为 0）时返回 0
// 而非 NaN/Inf，避免面板闪出脏值。
func calcPercent(total1, busy1, total2, busy2 float64) float64 {
	return clampPercent(busy2-busy1, total2-total1)
}

func clampPercent(busyDelta, totalDelta float64) float64 {
	if totalDelta <= 0 {
		return 0
	}
	p := busyDelta / totalDelta * 100
	if p < 0 {
		return 0
	}
	if p > 100 {
		return 100
	}
	return p
}

// netSinceBase 返回自基线以来的全网卡累计 rx/tx 字节数。
//
// 语义：openapi 把 netRxBytes 描述为"自启动累计"，实现上以**首次采集时刻**的
// 网卡计数为基线（NewCollector 时同步读网卡可能失败，且服务装配完成前流量无意义），
// 首次调用输出 0，之后为相对基线的增量——对用户呈现的仍是"本次进程运行期间"
// 的流量，重启归零。聚合口径：IOCounters(false) 返回全部网卡（含回环）的总和，
// 单用户内网场景先报总量，将来按网卡分维需扩 openapi。
func (c *Collector) netSinceBase(ctx context.Context) (rx, tx uint64, err error) {
	counters, err := net.IOCountersWithContext(ctx, false)
	if err != nil {
		return 0, 0, err
	}
	if len(counters) == 0 {
		return 0, 0, errors.New("网卡计数为空")
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	curRx, curTx := counters[0].BytesRecv, counters[0].BytesSent
	if !c.netBaseSet {
		c.netBaseRx, c.netBaseTx = curRx, curTx
		c.netBaseSet = true
	}
	// saturating 减法：网卡重置/驱动重装会让计数器变小，下溢成天文数字比报 0 更糟。
	return satSub(curRx, c.netBaseRx), satSub(curTx, c.netBaseTx), nil
}

func satSub(cur, base uint64) uint64 {
	if cur < base {
		return 0
	}
	return cur - base
}
