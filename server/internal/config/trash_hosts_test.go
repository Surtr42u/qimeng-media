package config

// 回收站生命周期与 Host 白名单的加载契约测试（2026-09-22 磁盘生命周期批）：
// 默认值、yaml/env 覆盖、零值兜底、非法值拒绝。

import (
	"testing"
	"time"
)

// TestLoadTrashDefaults 锁定零配置默认值：30 天保留 + 1h 巡检
// （与 filing.DefaultTrashRetentionDays 同值互指，见常量注释）。
func TestLoadTrashDefaults(t *testing.T) {
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load(\"\") 报错: %v", err)
	}
	if cfg.Trash.RetentionDays != DefaultTrashRetentionDays {
		t.Errorf("默认 Trash.RetentionDays = %d, 期望 %d", cfg.Trash.RetentionDays, DefaultTrashRetentionDays)
	}
	if cfg.Trash.SweepInterval != DefaultTrashSweepInterval {
		t.Errorf("默认 Trash.SweepInterval = %v, 期望 %v", cfg.Trash.SweepInterval, DefaultTrashSweepInterval)
	}
}

// TestLoadTrashOverrides yaml 与 env 双通道覆盖。
func TestLoadTrashOverrides(t *testing.T) {
	path := writeYAML(t, "trash:\n  retention_days: 7\n  sweep_interval: 30m\n")
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Trash.RetentionDays != 7 {
		t.Errorf("RetentionDays = %d, 期望 yaml 覆盖为 7", cfg.Trash.RetentionDays)
	}
	if cfg.Trash.SweepInterval != 30*time.Minute {
		t.Errorf("SweepInterval = %v, 期望 yaml 覆盖为 30m", cfg.Trash.SweepInterval)
	}

	t.Setenv("QIMENG_TRASH_RETENTION_DAYS", "14")
	t.Setenv("QIMENG_TRASH_SWEEP_INTERVAL", "2h")
	cfg, err = Load("")
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Trash.RetentionDays != 14 {
		t.Errorf("RetentionDays = %d, 期望 env 覆盖为 14", cfg.Trash.RetentionDays)
	}
	if cfg.Trash.SweepInterval != 2*time.Hour {
		t.Errorf("SweepInterval = %v, 期望 env 覆盖为 2h", cfg.Trash.SweepInterval)
	}
}

// TestLoadTrashZeroFallsBackToDefault 零值兜底：yaml 显式 0 不能把默认值
// 顶成零值直通（与 Backup.Interval 同款问题，Load 尾部兜底）。
func TestLoadTrashZeroFallsBackToDefault(t *testing.T) {
	path := writeYAML(t, "trash:\n  retention_days: 0\n  sweep_interval: 0s\n")
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Trash.RetentionDays != DefaultTrashRetentionDays {
		t.Errorf("RetentionDays = %d, 期望零值兜底回 %d", cfg.Trash.RetentionDays, DefaultTrashRetentionDays)
	}
	if cfg.Trash.SweepInterval != DefaultTrashSweepInterval {
		t.Errorf("SweepInterval = %v, 期望零值兜底回 %v", cfg.Trash.SweepInterval, DefaultTrashSweepInterval)
	}
}

// TestLoadTrashEnvInvalidRejected 非法值必须报错而非静默顶默认：
// retention=0 在判定侧是"永不清除"的防御语义，不能从配置通道达成。
func TestLoadTrashEnvInvalidRejected(t *testing.T) {
	for _, v := range []string{"0", "-3", "abc"} {
		t.Setenv("QIMENG_TRASH_RETENTION_DAYS", v)
		if _, err := Load(""); err == nil {
			t.Errorf("QIMENG_TRASH_RETENTION_DAYS=%q 应报错，得到 nil", v)
		}
	}
	t.Setenv("QIMENG_TRASH_RETENTION_DAYS", "14") // 合法值占位，排除上一键干扰
	t.Setenv("QIMENG_TRASH_SWEEP_INTERVAL", "abc")
	if _, err := Load(""); err == nil {
		t.Error("QIMENG_TRASH_SWEEP_INTERVAL=abc 应报错，得到 nil")
	}
}

// TestLoadTrustedHostsEnv Host 白名单 env 拆分：',' 与 ';' 均为分隔符，
// 空段跳过，条目原样保留（大小写在服务端比对时忽略）。
func TestLoadTrustedHostsEnv(t *testing.T) {
	t.Setenv("QIMENG_TRUSTED_HOSTS", "nas.lan.example, Backup.LAN.example;;win.lan.example")
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	want := []string{"nas.lan.example", "Backup.LAN.example", "win.lan.example"}
	if len(cfg.TrustedHosts) != len(want) {
		t.Fatalf("TrustedHosts = %v, 期望 %v（空段跳过）", cfg.TrustedHosts, want)
	}
	for i, h := range want {
		if cfg.TrustedHosts[i] != h {
			t.Errorf("TrustedHosts[%d] = %q, 期望 %q", i, cfg.TrustedHosts[i], h)
		}
	}
}
