package config

import (
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"strconv"
	"time"

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

// defaultThumbnailLongSide 已并入 thumbnail 包单一来源：网格默认档像素
// 由 thumbnail.SizeGrid 常量定义（512），config 只负责透传覆盖值；
// LongSide 为 0 时回落 thumbnail 包默认档——档位像素绝不在此重复硬编码。

// defaultTokenTTL 是签名直链默认有效期（docs/SECURITY.md 红线 5：6h）。
const defaultTokenTTL = 6 * time.Hour

// ThumbnailConfig 缩略图管线配置。
type ThumbnailConfig struct {
	// Workers 是缩略图工作池大小；0 表示按 CPU 核数自动决定（交由 runtime 决策，
	// 避免在未知硬件上写死并发数导致过载）。
	Workers int `yaml:"workers"`
	// LongSide 是缩略图最长边像素（网格默认档 md 的像素，短边按比例缩放）。
	// 0 = 回落 thumbnail 包默认档 SizeGrid（512）；档位像素的单一来源是
	// server/internal/thumbnail/cachekey.go 的 Size 常量，本字段只做覆盖。
	LongSide int `yaml:"long_side"`
}

// UploadConfig 上传管线配置（DOMAIN_RULES §9：单文件大小上限可配置）。
type UploadConfig struct {
	// MaxBytes 是单文件大小上限（字节）；0 = 用 defaultUploadMaxBytes。
	// 上限只在"下载上传流"路径强制（MaxBytesReader），不影响其他端点。
	MaxBytes int64 `yaml:"max_bytes"`
}

// defaultUploadMaxBytes 是单文件上传上限默认值（2GB）。
// 手机拍摄视频普遍 1~4GB，2GB 覆盖绝大多数短视频/截图场景又不至于
// 让一次误传拖垮磁盘；真有超大文件需求由部署方显式调大。
const DefaultUploadMaxBytes = int64(2) << 30

// defaultWebStaticDir 是 SPA 构建产物（Web 端 web/dist）的默认目录。
// 为什么默认相对路径 "../web/dist"：server 的工作目录约定是 server/
// （Makefile server-run 与 启动服务端.bat 都 cd 进去再启动），相对路径
// 在开发期零配置即命中；容器/其他部署方式用 yaml 或环境变量显式指定。
const defaultWebStaticDir = "../web/dist"

// WebConfig Web 前端静态资源（SPA 托管）配置。
type WebConfig struct {
	// StaticDir 是 SPA 构建产物目录（含 index.html）。空字符串 = 禁用 SPA
	// 托管（M1 行为：/ 与 /index.html 回退内嵌验收页）。目录存在且
	// index.html 可读时服务端托管该目录（静态资源直发 + 非文件路径回退
	// index.html），否则同样回退内嵌验收页——本机未构建 web/dist 是常态，
	// 服务不因缺前端产物挂掉（见 httpapi/spa.go）。
	StaticDir string `yaml:"static_dir"`
}

// Config 是服务端全部配置的最小集。新增配置项时同步更新 Load 的 env 覆盖表。
type Config struct {
	// Listen 是 HTTP 监听地址（默认 ":8420"，见 defaultListen）。
	Listen string `yaml:"listen"`
	// DataDir 是服务端私有数据根目录（数据库/缩略图/回收站），绝不能指向媒体库目录。
	DataDir string `yaml:"data_dir"`
	// LogLevel 是日志级别：debug/info/warn/error（默认 info）。
	LogLevel string `yaml:"log_level"`
	// Thumbnail 是缩略图管线配置。
	Thumbnail ThumbnailConfig `yaml:"thumbnail"`
	// Upload 是上传管线配置。
	Upload UploadConfig `yaml:"upload"`
	// Web 是 Web 静态资源（SPA 托管）配置。
	Web WebConfig `yaml:"web"`
	// DbPath 是 SQLite 库文件路径；空 = DataDir/qimeng.db（M1 组装约定：
	// 数据库跟随数据目录走，显式配置可单独放置）。
	DbPath string `yaml:"db_path"`
	// TokenTTL 是签名媒体直链（/media/**）的有效期，默认 6h（SECURITY
	// 红线 5）。注意命名的 Token 指 URL 内的 exp 凭据；Bearer token
	// 无过期时间（只有显式重置一条吊销路径）。
	TokenTTL time.Duration `yaml:"token_ttl"`
	// MediaSecret 是直链 HMAC 密钥；空 = main 启动时生成并持久化到
	// DataDir 下的密钥文件（重启后既有直链仍然有效）。
	MediaSecret string `yaml:"media_secret"`
	// AuthDevMode 开发模式免密登录开关（默认 false）。开启时
	// POST /api/v1/auth/dev-login 免密码直接签发 token（未初始化自动建
	// admin 占位用户），前端据此直进 UI——用户约定：项目未完成前不要密码
	// 流程，调试 UI 用。仅限本机开发，生产必须关闭；SECURITY 红线 5 的
	// 单点例外，说明见 docs/SECURITY.md「开发模式」节。
	AuthDevMode bool `yaml:"auth_dev_mode"`
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
		Thumbnail: ThumbnailConfig{Workers: 0, LongSide: 0},
		Upload:    UploadConfig{MaxBytes: DefaultUploadMaxBytes},
		Web:       WebConfig{StaticDir: defaultWebStaticDir},
		TokenTTL:  defaultTokenTTL,
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
	if v := os.Getenv("QIMENG_DB_PATH"); v != "" {
		cfg.DbPath = v
	}
	if v := os.Getenv("QIMENG_TOKEN_TTL"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_TOKEN_TTL=%q 不是合法时长（如 6h、30m）: %w", v, err)
		}
		cfg.TokenTTL = d
	}
	if v := os.Getenv("QIMENG_UPLOAD_MAX_BYTES"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n < 1 {
			return fmt.Errorf("环境变量 QIMENG_UPLOAD_MAX_BYTES=%q 不是合法正整数: %w", v, err)
		}
		cfg.Upload.MaxBytes = n
	}
	if v := os.Getenv("QIMENG_MEDIA_SECRET"); v != "" {
		cfg.MediaSecret = v
	}
	// 注意 QIMENG_WEB_STATIC_DIR 置空值（""）等于未设置、保留默认值：
	// 显式禁用 SPA 托管请走 yaml（web.static_dir: ""），env 的语义是覆盖
	// 为"默认不可见"，空串在 os.Getenv 层面无法与"未设置"区分。
	if v := os.Getenv("QIMENG_WEB_STATIC_DIR"); v != "" {
		cfg.Web.StaticDir = v
	}
	if v := os.Getenv("QIMENG_AUTH_DEV_MODE"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_AUTH_DEV_MODE=%q 不是合法布尔值（1/true/0/false）: %w", v, err)
		}
		cfg.AuthDevMode = b
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
