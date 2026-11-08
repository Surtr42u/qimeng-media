package config

import (
	"strings"
	"testing"
	"time"
)

// TestMaintenanceWorkerDefaults 锁定维护任务 worker 的默认值（ADR-0054
// 2026-11-07 实现补充）：默认启用、5s 轮询、2m 租约、并发 1；这些默认值
// 同时被 main 用来装配 worker，改动会直接改变后台行为。
func TestMaintenanceWorkerDefaults(t *testing.T) {
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	worker := cfg.MaintenanceWorker
	if !worker.Enabled {
		t.Error("默认 MaintenanceWorker.Enabled 应为 true")
	}
	if worker.PollInterval != DefaultMaintenanceWorkerPollInterval {
		t.Errorf("默认 PollInterval = %v，期望 %v", worker.PollInterval, DefaultMaintenanceWorkerPollInterval)
	}
	if worker.LeaseTTL != DefaultMaintenanceWorkerLeaseTTL {
		t.Errorf("默认 LeaseTTL = %v，期望 %v", worker.LeaseTTL, DefaultMaintenanceWorkerLeaseTTL)
	}
	if worker.Concurrency != DefaultMaintenanceWorkerConcurrency {
		t.Errorf("默认 Concurrency = %d，期望 %d", worker.Concurrency, DefaultMaintenanceWorkerConcurrency)
	}
}

// TestMaintenanceWorkerConfigOverride 覆盖 yaml 覆盖、零值兜底、env 优先与
// 并发硬上限收敛。
func TestMaintenanceWorkerConfigOverride(t *testing.T) {
	path := writeYAML(t, "maintenance_worker:\n  enabled: false\n  poll_interval: 15s\n  lease_ttl: 0s\n  concurrency: 0\n")
	t.Setenv("QIMENG_MAINTENANCE_WORKER_CONCURRENCY", "3")
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.MaintenanceWorker.Enabled {
		t.Error("yaml enabled: false 未生效")
	}
	if cfg.MaintenanceWorker.PollInterval != 15*time.Second {
		t.Errorf("PollInterval = %v，期望 yaml 覆盖值 15s", cfg.MaintenanceWorker.PollInterval)
	}
	if cfg.MaintenanceWorker.LeaseTTL != DefaultMaintenanceWorkerLeaseTTL {
		t.Errorf("LeaseTTL = %v，期望 0 值兜底为 %v", cfg.MaintenanceWorker.LeaseTTL, DefaultMaintenanceWorkerLeaseTTL)
	}
	if cfg.MaintenanceWorker.Concurrency != 3 {
		t.Errorf("Concurrency = %d，期望 env 覆盖值 3", cfg.MaintenanceWorker.Concurrency)
	}

	// 清掉 env 覆盖后再验证 yaml 超上限收敛（t.Setenv 空值等价未设置）。
	t.Setenv("QIMENG_MAINTENANCE_WORKER_CONCURRENCY", "")
	capped := writeYAML(t, "maintenance_worker:\n  concurrency: 99\n")
	cfg, err = Load(capped)
	if err != nil {
		t.Fatalf("Load(capped) 报错: %v", err)
	}
	if cfg.MaintenanceWorker.Concurrency != MaxMaintenanceWorkerConcurrency {
		t.Errorf("Concurrency = %d，期望收敛到硬上限 %d",
			cfg.MaintenanceWorker.Concurrency, MaxMaintenanceWorkerConcurrency)
	}
}

// TestMaintenanceWorkerEnvInvalid 覆盖非法 env 拒绝启动（错误必须返回而非被
// 默认值悄悄顶替）。
func TestMaintenanceWorkerEnvInvalid(t *testing.T) {
	cases := []struct {
		name  string
		key   string
		value string
	}{
		{"并发为 0", "QIMENG_MAINTENANCE_WORKER_CONCURRENCY", "0"},
		{"并发超上限", "QIMENG_MAINTENANCE_WORKER_CONCURRENCY", "9"},
		{"轮询非法时长", "QIMENG_MAINTENANCE_WORKER_POLL_INTERVAL", "soon"},
		{"租约非法时长", "QIMENG_MAINTENANCE_WORKER_LEASE_TTL", "2minutes"},
		{"开关非法布尔", "QIMENG_MAINTENANCE_WORKER_ENABLED", "yes-please"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv(tc.key, tc.value)
			if _, err := Load(""); err == nil {
				t.Fatalf("非法 %s=%q 期望 Load 报错，得到 nil", tc.key, tc.value)
			} else if !strings.Contains(err.Error(), tc.key) {
				t.Fatalf("错误信息未包含环境变量名 %s: %v", tc.key, err)
			}
		})
	}
}
