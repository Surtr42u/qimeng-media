package sysmon

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

// TestSnapshotBlockingWindow 验证窗口模式（CPUInterval>0）下的结构完整性与合法值。
// 用 100ms 级真实采样：阻塞窗口内 gopsutil 自读两次，返回的是有效测量而非占位。
func TestSnapshotBlockingWindow(t *testing.T) {
	c := NewCollector()
	ctx := context.Background()
	tmp := t.TempDir()

	st, err := c.Snapshot(ctx, Options{
		Mounts:      []string{tmp},
		Version:     "test-v1",
		CPUInterval: 100 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}

	// version 注入生效。
	if st.Version != "test-v1" {
		t.Errorf("Version = %q, want %q", st.Version, "test-v1")
	}

	// 内存：结构完整非零，已用不超过总量。
	if st.MemTotalBytes == 0 {
		t.Errorf("MemTotalBytes = 0, want > 0（真实机器总内存不可能为 0）")
	}
	if st.MemUsedBytes > st.MemTotalBytes {
		t.Errorf("MemUsedBytes(%d) > MemTotalBytes(%d)", st.MemUsedBytes, st.MemTotalBytes)
	}

	// 磁盘：传入的存在路径必须恰好一条，used<=total。
	if len(st.Disks) != 1 {
		t.Fatalf("len(Disks) = %d, want 1", len(st.Disks))
	}
	d := st.Disks[0]
	if d.UsedBytes > d.TotalBytes {
		t.Errorf("磁盘 %s used(%d) > total(%d)", d.Mount, d.UsedBytes, d.TotalBytes)
	}
	if d.TotalBytes == 0 {
		t.Errorf("磁盘 %s totalBytes = 0, want > 0", d.Mount)
	}

	// 网络：首次调用基线为 0（语义见 snapshot.go netSinceBase 注释）。
	if st.NetRxBytes != 0 || st.NetTxBytes != 0 {
		t.Errorf("首次快照 NetRx=%d NetTx=%d, want 0/0（基线语义）", st.NetRxBytes, st.NetTxBytes)
	}

	// uptime：首次调用 >= 0（秒粒度下可为 0）。
	if st.UptimeSeconds < 0 {
		t.Errorf("UptimeSeconds = %d, want >= 0", st.UptimeSeconds)
	}

	// CPU：整体与每核都在 0~100。空闲机器上可能是 0（合法值），只验范围与形状。
	if st.CPUPercent < 0 || st.CPUPercent > 100 {
		t.Errorf("CPUPercent = %f, want [0,100]", st.CPUPercent)
	}
	if len(st.PerCore) == 0 {
		t.Errorf("PerCore 为空, want >= 1 个核心")
	}
	for i, p := range st.PerCore {
		if p < 0 || p > 100 {
			t.Errorf("PerCore[%d] = %f, want [0,100]", i, p)
		}
	}
}

// TestSnapshotMonotonic 两次采样：网络累计单调不减、uptime 严格递增。
// sleep 1100ms 是为跨过 uptime 的秒级分辨率（int64 秒截断下 1.1s 必然 +1）。
func TestSnapshotMonotonic(t *testing.T) {
	c := NewCollector()
	ctx := context.Background()

	st1, err := c.Snapshot(ctx, Options{Version: "t", CPUInterval: 100 * time.Millisecond})
	if err != nil {
		t.Fatalf("第一次 Snapshot: %v", err)
	}
	time.Sleep(1100 * time.Millisecond)
	st2, err := c.Snapshot(ctx, Options{Version: "t", CPUInterval: 100 * time.Millisecond})
	if err != nil {
		t.Fatalf("第二次 Snapshot: %v", err)
	}

	if st2.UptimeSeconds <= st1.UptimeSeconds {
		t.Errorf("uptime 未递增: %d -> %d", st1.UptimeSeconds, st2.UptimeSeconds)
	}
	// 网络计数器理论单调；两次采样间无流量时相等也合法，只断言不回退。
	if st2.NetRxBytes < st1.NetRxBytes {
		t.Errorf("NetRxBytes 回退: %d -> %d", st1.NetRxBytes, st2.NetRxBytes)
	}
	if st2.NetTxBytes < st1.NetTxBytes {
		t.Errorf("NetTxBytes 回退: %d -> %d", st1.NetTxBytes, st2.NetTxBytes)
	}
	// 差分模式（窗口模式首端做基线后，第二次窗口模式重采）CPU 仍须合法。
	if st2.CPUPercent < 0 || st2.CPUPercent > 100 {
		t.Errorf("第二次 CPUPercent = %f, want [0,100]", st2.CPUPercent)
	}
}

// TestSnapshotNonBlockingFirstCall 验证非阻塞模式（CPUInterval=0）首次调用：
// 无基线时返回 0 而非报错（与 gopsutil Percent(0) 首调报错的行为差异是自采差分的动机）。
func TestSnapshotNonBlockingFirstCall(t *testing.T) {
	c := NewCollector()
	ctx := context.Background()

	st, err := c.Snapshot(ctx, Options{Version: "t", CPUInterval: 0})
	if err != nil {
		t.Fatalf("非阻塞首调 Snapshot: %v", err)
	}
	if st.CPUPercent != 0 {
		t.Errorf("非阻塞首调 CPUPercent = %f, want 0（无基线）", st.CPUPercent)
	}
	if len(st.PerCore) == 0 {
		t.Errorf("非阻塞首调 PerCore 为空, want >= 1")
	}
	for _, p := range st.PerCore {
		if p != 0 {
			t.Errorf("非阻塞首调 PerCore 元素 = %f, want 0", p)
		}
	}
}

// TestSnapshotNonBlockingSecondCall 非阻塞差分：两次调用间隔 200ms，
// 第二次拿到真实差分值（0~100 合法；空闲机器可能接近 0，只验范围不验下限）。
func TestSnapshotNonBlockingSecondCall(t *testing.T) {
	c := NewCollector()
	ctx := context.Background()

	if _, err := c.Snapshot(ctx, Options{Version: "t"}); err != nil {
		t.Fatalf("第一次 Snapshot: %v", err)
	}
	time.Sleep(200 * time.Millisecond)
	st, err := c.Snapshot(ctx, Options{Version: "t"})
	if err != nil {
		t.Fatalf("第二次 Snapshot: %v", err)
	}
	if st.CPUPercent < 0 || st.CPUPercent > 100 {
		t.Errorf("差分 CPUPercent = %f, want [0,100]", st.CPUPercent)
	}
	if len(st.PerCore) == 0 {
		t.Errorf("PerCore 为空, want >= 1")
	}
}

// TestSnapshotBadMount 不存在的挂载点：跳过该条目并返回聚合错误，
// 其余字段照常填充（部分失败不拖垮整张快照，语义见 Snapshot 注释）。
func TestSnapshotBadMount(t *testing.T) {
	c := NewCollector()
	ctx := context.Background()
	good := t.TempDir()
	bad := filepath.Join(t.TempDir(), "no-such-subdir")

	st, err := c.Snapshot(ctx, Options{Mounts: []string{good, bad}, Version: "t"})
	if err == nil {
		t.Fatalf("期望不存在的挂载点返回聚合错误, got nil")
	}
	if len(st.Disks) != 1 {
		t.Errorf("len(Disks) = %d, want 1（只保留成功的挂载点）", len(st.Disks))
	}
	if st.Version != "t" || st.MemTotalBytes == 0 {
		t.Errorf("单项磁盘失败不应影响其余字段: Version=%q MemTotal=%d", st.Version, st.MemTotalBytes)
	}
}

// TestSnapshotVersionFallback 未注入 opts.Version 时回落到 DefaultVersion。
func TestSnapshotVersionFallback(t *testing.T) {
	c := NewCollector()
	st, err := c.Snapshot(context.Background(), Options{})
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if st.Version != DefaultVersion {
		t.Errorf("Version = %q, want DefaultVersion %q", st.Version, DefaultVersion)
	}
}
