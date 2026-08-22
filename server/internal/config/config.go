package config

import (
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"strconv"

	"gopkg.in/yaml.v3"
)

// defaultListen 是默认监听地址。
// 为什么是 8420：与 api/openapi.yaml 的 servers 端口保持一致（协议宪法），
// 两处必须同步修改，否则客户端 SDK 连不上服务端。
const defaultListen = ":8420"

// defaultDataDir 是数据库/缩略图/回收站等服务端私有数据的根目录。
// 为什么默认相对路径 "./data"：开发期零配置即可 `go run` 起服务；
// 生产环境由 yaml 或环境变量显式指定（Docker 内为 /data）。
const defaultDataDir = "./data"

// defaultThumbnailLongSide 是缩略图最长边像素数。
// 为什么定 512：覆盖手机列表页与 Web 网格两种展示密度，文件体积约几十 KB，
// 是"清晰度"与"流量/存储成本"的折中，后续可通过配置调整。
const defaultThumbnailLongSide = 512

// ThumbnailConfig 缩略图管线配置。
type ThumbnailConfig struct {
	// Workers 是缩略图工作池大小；0 表示按 CPU 核数自动决定（交由 runtime 决策，
	// 避免在未知硬件上写死并发数导致过载）。
	Workers int `yaml:"workers"`
	// LongSide 是缩略图最长边像素（短边按比例缩放）。
	LongSide int `yaml:"long_side"`
}

// Config 是服务端全部配置的最小集。新增配置项时同步更新 Load 的 env 覆盖表。
type Config struct {
	// Listen 是 HTTP 监听地址（默认 ":8420"，见 defaultListen）。
	Listen string `yaml:"listen"`
	// DataDir 是服务端私有数据根目录（数据库/缩略图/回收站），绝不能指向媒体库目录。
	DataDir string `yaml:"data_dir"`
	// Token 是访问令牌（M0 阶段的朴素鉴权；正式签发/校验归 internal/auth 包）。
	Token string `yaml:"token"`
	// LogLevel 是日志级别：debug/info/warn/error（默认 info）。
	LogLevel string `yaml:"log_level"`
	// Thumbnail 是缩略图管线配置。
	Thumbnail ThumbnailConfig `yaml:"thumbnail"`
}

// Load 按优先级加载配置：内置默认值 < yaml 文件 < 环境变量。
// 为什么这个顺序：默认值保证零配置可跑，yaml 承载部署差异，env 用于
// 容器/临时覆盖（docker compose 与 CI 里改 env 比改文件容易得多）。
// 配置文件不存在不算错误（开发期常态），但存在却读不了/解析失败必须报错，
// 静默忽略会让"以为改了配置其实没生效"这类问题极难排查。
func Load(path string) (*Config, error) {
	cfg := &Config{
		Listen:    defaultListen,
		DataDir:   defaultDataDir,
		LogLevel:  "info",
		Thumbnail: ThumbnailConfig{Workers: 0, LongSide: defaultThumbnailLongSide},
	}

	if path != "" {
		data, err := os.ReadFile(path)
		switch {
		case errors.Is(err, fs.ErrNotExist):
			// 文件不存在：走默认值 + env，不算错误（见函数注释）。
		case err != nil:
			return nil, fmt.Errorf("读取配置文件 %s: %w", path, err)
		default:
			// yaml.Unmarshal 只覆盖文件中出现的字段，未出现的保留默认值。
			if err := yaml.Unmarshal(data, cfg); err != nil {
				return nil, fmt.Errorf("解析配置文件 %s: %w", path, err)
			}
		}
	}

	if err := applyEnv(cfg); err != nil {
		return nil, err
	}
	return cfg, nil
}

// applyEnv 用环境变量覆盖已加载的配置。
// 环境变量命名规则：QIMENG_<大写配置名>；错误必须返回而非静默忽略，
// 否则非法值会被默认值悄悄顶替，排障时无从得知。
func applyEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_LISTEN"); v != "" {
		cfg.Listen = v
	}
	if v := os.Getenv("QIMENG_DATA_DIR"); v != "" {
		cfg.DataDir = v
	}
	if v := os.Getenv("QIMENG_TOKEN"); v != "" {
		cfg.Token = v
	}
	if v := os.Getenv("QIMENG_LOG_LEVEL"); v != "" {
		cfg.LogLevel = v
	}
	if v := os.Getenv("QIMENG_THUMBNAIL_WORKERS"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_THUMBNAIL_WORKERS=%q 不是合法整数: %w", v, err)
		}
		cfg.Thumbnail.Workers = n
	}
	return nil
}

// SlogLevel 把字符串日志级别翻译成 slog.Level。
// 未知值落到 info 而非报错：日志级别不是关键配置，宽容处理避免
// 拼错一个单词就起不来服务。
func (c *Config) SlogLevel() slog.Level {
	switch c.LogLevel {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
