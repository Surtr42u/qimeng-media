package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// DefaultScannerPollInterval 网络盘可能静默漏事件，零配置仍保持 5 分钟兜底。
const DefaultScannerPollInterval = 5 * time.Minute

// ScannerConfig 扫描调度配置，启动生效；不改变手动扫描或上传即时入库。
type ScannerConfig struct {
	PollInterval time.Duration `yaml:"poll_interval"`
	WatchEnabled bool          `yaml:"watch_enabled"`
}

func applyScannerEnv(cfg *Config) error {
	if value := os.Getenv("QIMENG_SCANNER_POLL_INTERVAL"); value != "" {
		interval, err := time.ParseDuration(value)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_SCANNER_POLL_INTERVAL 不是合法时长: %w", err)
		}
		cfg.Scanner.PollInterval = interval
	}
	if value := os.Getenv("QIMENG_SCANNER_WATCH_ENABLED"); value != "" {
		enabled, err := strconv.ParseBool(value)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_SCANNER_WATCH_ENABLED 不是合法布尔值: %w", err)
		}
		cfg.Scanner.WatchEnabled = enabled
	}
	return nil
}
