package config

import (
	"strings"
	"testing"
	"time"
)

func TestScannerConfig(t *testing.T) {
	t.Setenv("QIMENG_SCANNER_POLL_INTERVAL", "")
	t.Setenv("QIMENG_SCANNER_WATCH_ENABLED", "")
	cfg, err := Load("")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Scanner.PollInterval != DefaultScannerPollInterval || !cfg.Scanner.WatchEnabled {
		t.Fatalf("默认扫描配置: %+v", cfg.Scanner)
	}
	path := writeYAML(t, "scanner:\n  poll_interval: 30m\n  watch_enabled: false\n")
	cfg, err = Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Scanner.PollInterval != 30*time.Minute || cfg.Scanner.WatchEnabled {
		t.Fatalf("YAML 扫描配置: %+v", cfg.Scanner)
	}
	t.Setenv("QIMENG_SCANNER_POLL_INTERVAL", "1h")
	t.Setenv("QIMENG_SCANNER_WATCH_ENABLED", "true")
	cfg, err = Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Scanner.PollInterval != time.Hour || !cfg.Scanner.WatchEnabled {
		t.Fatalf("环境覆盖扫描配置: %+v", cfg.Scanner)
	}
}

func TestScannerConfigInvalidAndFallback(t *testing.T) {
	for _, interval := range []string{"0s", "-1m"} {
		t.Run(interval, func(t *testing.T) {
			t.Setenv("QIMENG_SCANNER_POLL_INTERVAL", interval)
			cfg, err := Load("")
			if err != nil || cfg.Scanner.PollInterval != DefaultScannerPollInterval {
				t.Fatalf("非正间隔须兜底: cfg=%+v err=%v", cfg, err)
			}
		})
	}
	for _, key := range []string{"QIMENG_SCANNER_POLL_INTERVAL", "QIMENG_SCANNER_WATCH_ENABLED"} {
		t.Run(key, func(t *testing.T) {
			t.Setenv(key, "invalid")
			if _, err := Load(""); err == nil || !strings.Contains(err.Error(), key) {
				t.Fatalf("非法配置须明确报错: %v", err)
			}
		})
	}
	path := writeYAML(t, "scanner:\n  poll_interval: 0s\n")
	cfg, err := Load(path)
	if err != nil || cfg.Scanner.PollInterval != DefaultScannerPollInterval {
		t.Fatalf("YAML 零间隔须兜底: cfg=%+v err=%v", cfg, err)
	}
}
