package config

import (
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"strconv"
	"strings"
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

// DefaultTokenTTL 是签名直链默认有效期的唯一来源（docs/SECURITY.md 红线 5：6h）。
// httpapi.DefaultTokenTTL 是它的别名（代码卫生约束：同值不双写）。
const DefaultTokenTTL = 6 * time.Hour

// DefaultThumbnailWarmupDelay 是缩略图开机回填的默认静默窗（60s）：服务端
// 起监听后先等这么久再开跑批量回填。为什么默认不等零：单机形态（ADR-0015）
// 服务端起监听与 App 登录几乎同时发生，ffmpeg 批量转码若立刻开跑会与首屏
// 请求/按需缩略图抢 CPU/IO，用户感知为「登录后前几十秒整机发闷」（2026-09-18
// 反馈）；延后回填对 NAS/PC 形态无害（回填本来就是后台渐进任务）。部署方
// 显式配 0 可关闭等待（低配 NAS 若想尽早建满缩略图）。
const DefaultThumbnailWarmupDelay = time.Minute

// ThumbnailConfig 缩略图管线配置。
type ThumbnailConfig struct {
	// Workers 是缩略图工作池大小；0 表示按 CPU 核数自动决定（交由 runtime 决策，
	// 避免在未知硬件上写死并发数导致过载）。
	Workers int `yaml:"workers"`
	// LongSide 是缩略图最长边像素（网格默认档 md 的像素，短边按比例缩放）。
	// 0 = 回落 thumbnail 包默认档 SizeGrid（512）；档位像素的单一来源是
	// server/internal/thumbnail/cachekey.go 的 Size 常量，本字段只做覆盖。
	LongSide int `yaml:"long_side"`
	// FFmpegPath 是 ffmpeg 可执行文件路径；空 = 裸命令名走 PATH 自动发现
	//（缺省行为与路径配置化之前完全一致）。为什么需要显式路径：M6 单机形态
	// （ADR-0015）服务端进手机后二进制打包在 App 的 nativeLibraryDir，进程
	// PATH 未必可达，自动发现不可依赖；NAS/PC 形态缺省不配置即可。
	FFmpegPath string `yaml:"ffmpeg_path"`
	// FFprobePath 是 ffprobe 可执行文件路径；语义同 FFmpegPath（空 = PATH
	// 自动发现）。消费方除缩略图管线外还有扫描入库与上传的视频探测——
	// 三处共用 Generator 装配出的同一个解析结果（见 thumbnail.Generator）。
	FFprobePath string `yaml:"ffprobe_path"`
	// WarmupDelay 是开机回填的启动静默窗：进程启动后先等这么久才开始批量
	// 回填（扫描后补齐与周期兜底同样受窗约束——窗内的触发顺延到窗尾执行，
	// 见 httpapi/thumbnail_warmup.go waitForBootQuietWindow）。0 或负 = 不
	// 等待。时长语法与 token_ttl 同口径（yaml "60s" / env
	// QIMENG_THUMBNAIL_WARMUP_DELAY）；默认 DefaultThumbnailWarmupDelay。
	WarmupDelay time.Duration `yaml:"warmup_delay"`
}

// UploadConfig 上传管线配置（DOMAIN_RULES §9：单文件大小上限可配置）。
type UploadConfig struct {
	// MaxBytes 是单文件大小上限（字节）；0 = 用 defaultUploadMaxBytes。
	// 上限只在"下载上传流"路径强制（MaxBytesReader），不影响其他端点。
	MaxBytes int64 `yaml:"max_bytes"`
}

// 备份热备默认值（任务Q 批B v1 冻结口径）。三个常量是 backup 包与
// httpapi 调度摘要端点（GET /api/v1/backups 的 schedule 回显）的共同来源。
const (
	// DefaultBackupEnabled 自动备份默认开启：快照只在本地磁盘多占一份库
	// 副本，不停服不伤性能（VACUUM INTO 走 WAL），默认收益远大于成本；
	// 不想要由部署方显式关闭。
	DefaultBackupEnabled = true
	// DefaultBackupInterval 定时快照间隔（默认 24 小时）：一天一份足够
	// 家庭场景回滚粒度（媒体库以天为单位变化），再密只是徒增磁盘占用。
	// 单位 time.Duration；首次快照在进程启动一个间隔后触发，启动后想要
	// 立即备份走维护页手动触发（POST /api/v1/backups）。
	DefaultBackupInterval = 24 * time.Hour
	// DefaultBackupRetention 快照保留份数（默认 7 份）：一周回滚窗口，
	// 超出自动删最旧；按库体积 7 份的磁盘占用可控（库文件量级远小于
	// 媒体本身）。单位=份数。
	DefaultBackupRetention = 7
)

// BackupConfig 备份热备配置（快照 = VACUUM INTO 库文件副本，存 DataDir/backups）。
type BackupConfig struct {
	// Enabled 定时快照开关；false = 不跑定时调度，但手动触发端点
	//（POST /api/v1/backups）仍然可用（v1 口径：开关只管定时面）。
	Enabled bool `yaml:"enabled"`
	// Interval 定时快照间隔；<=0 = 用 DefaultBackupInterval。
	Interval time.Duration `yaml:"interval"`
	// Retention 快照保留份数；<=0 = 用 DefaultBackupRetention。
	Retention int `yaml:"retention"`
}

// 回收站到期清扫默认值（DOMAIN_RULES §9）。DefaultTrashRetentionDays 与
// filing.DefaultTrashRetentionDays 的关系：config 不 import filing
// （backup 三键同款惯例），两常量同值互指，改须双同步。
const (
	// DefaultTrashRetentionDays 回收站默认保留天数（30 天）：到期条目由后台
	// 清扫物理清除；filing.TrashExpired 对 <1 的 retention 永不判过期（误配
	// 兜底，见该函数注释）。
	DefaultTrashRetentionDays = 30
	// DefaultTrashSweepInterval 到期清扫巡检间隔（默认 1 小时）：一轮清扫
	// 只是回收站 meta 遍历（轻 IO），小时级巡检让「到期」与「实际清除」的
	// 误差远小于保留天数本身的粒度；过密只会在回收站极大时白跑遍历。
	DefaultTrashSweepInterval = time.Hour
)

// TrashConfig 回收站生命周期配置（到期自动物理清除，DOMAIN_RULES §9）。
type TrashConfig struct {
	// RetentionDays 回收站保留天数；<=0 = 用 DefaultTrashRetentionDays。
	RetentionDays int `yaml:"retention_days"`
	// SweepInterval 到期清扫的巡检间隔；<=0 = 用 DefaultTrashSweepInterval。
	SweepInterval time.Duration `yaml:"sweep_interval"`
}

// 本机文件夹自动同步通道默认值（ADR-0030）。Root 为空 = 通道整体关闭
// （调度不启动、状态端点报 disabled），这是该通道唯一的开关形态。
const (
	// DefaultLocalSyncInterval 同步根轮询周期（默认 30s）：一轮只是 ReadDir
	// 级轻扫描 + 待处理文件的 mtime 观测，个人库规模成本可忽略；过密只会
	// 白跑空轮。首次扫描在进程启动一个周期后触发（ticker 语义），想立即
	// 同步走 POST /api/v1/local-sync/trigger。
	DefaultLocalSyncInterval = 30 * time.Second
	// DefaultLocalSyncStableAge 文件稳定门槛（默认 60s）：文件 mtime 年龄
	// >= 此值且跨轮 size/mtime 完全不变才处理——防半截拷贝（用户往同步根
	// 拖大文件，mtime 不停刷新，未稳定前入库会搬走残缺字节）。
	DefaultLocalSyncStableAge = 60 * time.Second
)

// LocalSyncConfig 本机文件夹自动同步通道配置（ADR-0030）：同步根直接子
// 文件夹名=库名，媒体走直传同款校验与入库后源文件移入库根；同步根直接下
// 的 *.txt 走作者片段导入（keep 语义）后归档进 <同步根>/.synced/。
type LocalSyncConfig struct {
	// Root 是同步根绝对路径；空 = 通道关闭（默认）。不设 enabled 布尔开关：
	// 「不配路径」即「不启用」，少一个可互相矛盾的组合（与 Trash 无 enabled
	// 开关同一设计取舍）。
	Root string `yaml:"root"`
	// Interval 是轮询周期；<=0 = 用 DefaultLocalSyncInterval（Load 兜底）。
	Interval time.Duration `yaml:"interval"`
	// StableAge 是文件稳定门槛；<=0 = 用 DefaultLocalSyncStableAge（Load 兜底）。
	StableAge time.Duration `yaml:"stable_age"`
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
	// AuthDevSharedSecret 是 dev-login 的可选共享密钥（默认空 = 不校验，
	// 行为与未引入本字段前一致）。非空时 POST /auth/dev-login 必须携带
	// 相等密钥（X-Qimeng-Dev-Secret 请求头，恒时比对）才签发 token——
	// 内嵌形态（ADR-0015，服务端跑在 Android loopback:18430）防同机其他
	// App 走免密登录拿管理员 token：Android 拉起子进程时随机生成并注入，
	// Web/生产部署留空即零影响。说明见 docs/SECURITY.md「开发模式」节。
	AuthDevSharedSecret string `yaml:"auth_dev_shared_secret"`
	// AllowedLibraryRoots 是媒体库注册根路径白名单。
	// 为什么：注册库 = 把磁盘目录交给扫描器/直链/回收站管线，路径一旦
	// 误指（如 /、/etc、家目录）会把无关文件暴露进媒体面或被误扫。
	// 空 = 不限制（向后兼容本地零配置开发，行为与本字段引入前一致）；
	// 非空 = 每个注册库的 root_path 必须位于任一前缀之下（含前缀本身），
	// 否则 POST /api/v1/libraries 返回 400。
	AllowedLibraryRoots []string `yaml:"allowed_library_roots"`
	// TrustedHosts 是允许作为 Host 头访问本服务的域名白名单（DNS
	// rebinding 防御，docs/SECURITY.md「Host 校验」）。默认空 = 只放行
	// IP 直连与 localhost——局域网/隧道的既定访问形态全是 IP 或本机名，
	// 默认零配置零影响；需要域名访问（如 Tailscale MagicDNS 主机名）的
	// 部署把主机名加进本表（大小写不敏感）。
	TrustedHosts []string `yaml:"trusted_hosts"`
	// Backup 是备份热备（库文件在线快照）配置。
	Backup BackupConfig `yaml:"backup"`
	// Trash 是回收站生命周期（到期自动物理清除）配置。
	Trash TrashConfig `yaml:"trash"`
	// LocalSync 是本机文件夹自动同步通道配置（ADR-0030）。
	LocalSync LocalSyncConfig `yaml:"local_sync"`
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
		Thumbnail: ThumbnailConfig{Workers: 0, LongSide: 0, WarmupDelay: DefaultThumbnailWarmupDelay},
		Upload:    UploadConfig{MaxBytes: DefaultUploadMaxBytes},
		Web:       WebConfig{StaticDir: defaultWebStaticDir},
		TokenTTL:  DefaultTokenTTL,
		Backup: BackupConfig{
			Enabled:   DefaultBackupEnabled,
			Interval:  DefaultBackupInterval,
			Retention: DefaultBackupRetention,
		},
		Trash: TrashConfig{
			RetentionDays: DefaultTrashRetentionDays,
			SweepInterval: DefaultTrashSweepInterval,
		},
		LocalSync: LocalSyncConfig{
			Interval:  DefaultLocalSyncInterval,
			StableAge: DefaultLocalSyncStableAge,
		},
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
	// 备份间隔兜底：BackupConfig.Interval 的注释承诺「<=0 = 用
	// DefaultBackupInterval」，但 yaml 显式写 0 / env 传 0s 都会把默认值
	// 覆盖成零值直通出去——不在此兜底，Manager.Start 会走 interval<=0 的
	// Warn 分支把定时快照悄悄关掉，且 GET /backups 的调度回显会把 0 取整
	// 成「每 1h」误导运维（reviewer P2 清偿）。Retention 的同类兜底在
	// backup.NewManager（<=0 回落），两处注释互指。
	if cfg.Backup.Interval <= 0 {
		cfg.Backup.Interval = DefaultBackupInterval
	}
	// 回收站两键兜底：与 Backup.Interval 同款问题——yaml 显式 0 / env 传 0
	// 会把默认值覆盖成零值直通。RetentionDays 兜底后必 >=1（TrashExpired
	// 的 <1 永不过期语义留给判定侧做误配防御，不让它从配置通道触达）。
	if cfg.Trash.RetentionDays <= 0 {
		cfg.Trash.RetentionDays = DefaultTrashRetentionDays
	}
	if cfg.Trash.SweepInterval <= 0 {
		cfg.Trash.SweepInterval = DefaultTrashSweepInterval
	}
	// 本机同步两键兜底：与 Backup.Interval 同款问题——yaml 显式 0 / env 传 0
	// 会把默认值覆盖成零值直通（ticker 周期为 0 会 panic，稳定门槛为 0 会让
	// 半截拷贝直接入库）。Root 刻意不兜底：空串 = 通道关闭的合法语义。
	if cfg.LocalSync.Interval <= 0 {
		cfg.LocalSync.Interval = DefaultLocalSyncInterval
	}
	if cfg.LocalSync.StableAge <= 0 {
		cfg.LocalSync.StableAge = DefaultLocalSyncStableAge
	}
	return cfg, nil
}

// applyEnv 用环境变量覆盖已加载的配置。
// 环境变量命名规则：QIMENG_<大写配置名>；错误必须返回而非静默忽略，
// 否则非法值会被默认值悄悄顶替，排障时无从得知。
// 按配置域拆成节段函数（曾为单一 100+ 行函数，超函数警戒线；各段只碰
// 自己的 Config 子树，顺序无关）。
func applyEnv(cfg *Config) error {
	for _, section := range []func(*Config) error{
		applyBasicEnv,
		applyThumbnailEnv,
		applyUploadEnv,
		applyAuthEnv,
		applyBackupEnv,
		applyTrashEnv,
		applyLocalSyncEnv,
	} {
		if err := section(cfg); err != nil {
			return err
		}
	}
	return nil
}

// applyBasicEnv 覆盖监听/数据目录/日志/库路径/密钥/SPA 目录/直链 TTL。
func applyBasicEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_LISTEN"); v != "" {
		cfg.Listen = v
	}
	if v := os.Getenv("QIMENG_DATA_DIR"); v != "" {
		cfg.DataDir = v
	}
	if v := os.Getenv("QIMENG_LOG_LEVEL"); v != "" {
		cfg.LogLevel = v
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
	if v := os.Getenv("QIMENG_MEDIA_SECRET"); v != "" {
		cfg.MediaSecret = v
	}
	// 注意 QIMENG_WEB_STATIC_DIR 置空值（""）等于未设置、保留默认值：
	// 显式禁用 SPA 托管请走 yaml（web.static_dir: ""），env 的语义是覆盖
	// 为"默认不可见"，空串在 os.Getenv 层面无法与"未设置"区分。
	if v := os.Getenv("QIMENG_WEB_STATIC_DIR"); v != "" {
		cfg.Web.StaticDir = v
	}
	return nil
}

// applyThumbnailEnv 覆盖缩略图管线四键。
func applyThumbnailEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_THUMBNAIL_WORKERS"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_THUMBNAIL_WORKERS=%q 不是合法整数: %w", v, err)
		}
		cfg.Thumbnail.Workers = n
	}
	// 二进制路径是纯字符串（无非法值可判）：与 QIMENG_DB_PATH 同款直覆盖语义，
	// 空值等于未设置、保留默认空（默认空 = PATH 自动发现，行为零变化）。
	if v := os.Getenv("QIMENG_THUMBNAIL_FFMPEG_PATH"); v != "" {
		cfg.Thumbnail.FFmpegPath = v
	}
	if v := os.Getenv("QIMENG_THUMBNAIL_FFPROBE_PATH"); v != "" {
		cfg.Thumbnail.FFprobePath = v
	}
	if v := os.Getenv("QIMENG_THUMBNAIL_WARMUP_DELAY"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_THUMBNAIL_WARMUP_DELAY=%q 不是合法时长（如 60s、2m）: %w", v, err)
		}
		cfg.Thumbnail.WarmupDelay = d
	}
	return nil
}

// applyUploadEnv 覆盖上传上限。
func applyUploadEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_UPLOAD_MAX_BYTES"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		// 分支拆开：n<1 时 err==nil，%w 包 nil 会渲染成 %!w(<nil>) 畸形消息。
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_UPLOAD_MAX_BYTES=%q 不是合法正整数: %w", v, err)
		}
		if n < 1 {
			return fmt.Errorf("环境变量 QIMENG_UPLOAD_MAX_BYTES=%q 不是合法正整数（须 >=1）", v)
		}
		cfg.Upload.MaxBytes = n
	}
	return nil
}

// applyAuthEnv 覆盖鉴权/安全四键（dev 免密+共享密钥、库根白名单、Host 白名单）。
func applyAuthEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_AUTH_DEV_MODE"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_AUTH_DEV_MODE=%q 不是合法布尔值（1/true/0/false）: %w", v, err)
		}
		cfg.AuthDevMode = b
	}
	// dev-login 共享密钥是敏感明文：空值 = 未设置、保留 yaml/默认（空 =
	// 不校验）。与 QIMENG_MEDIA_SECRET 同款直覆盖语义（纯字符串，无非法
	// 值可判）——Android 内嵌形态靠它把随机密钥传给拉起的子进程。
	if v := os.Getenv("QIMENG_AUTH_DEV_SHARED_SECRET"); v != "" {
		cfg.AuthDevSharedSecret = v
	}
	// 库根白名单是路径列表：空值 = 未设置、保留 yaml/默认（空 = 不限制）。
	// 分隔符同时接受 ';'（Windows 路径列表习惯，也是本平台 PathListSeparator）
	// 与 os.PathListSeparator（Unix 为 ':'）——跨平台 compose 只需记住一种写法。
	if v := os.Getenv("QIMENG_ALLOWED_LIBRARY_ROOTS"); v != "" {
		cfg.AllowedLibraryRoots = splitPathList(v)
	}
	// Host 白名单是域名列表：分隔符用 ',' 与 ';'（不用 splitPathList——
	// Unix 上它额外按 ':' 切，会误切带端口的写法）。空值 = 未设置、保留
	// yaml/默认（空 = 只放行 IP 直连与 localhost）。
	if v := os.Getenv("QIMENG_TRUSTED_HOSTS"); v != "" {
		cfg.TrustedHosts = splitList(v, ",;")
	}
	return nil
}

// applyBackupEnv 覆盖备份热备三键：空值 = 未设置、保留 yaml/默认。
func applyBackupEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_BACKUP_ENABLED"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_BACKUP_ENABLED=%q 不是合法布尔值（1/true/0/false）: %w", v, err)
		}
		cfg.Backup.Enabled = b
	}
	if v := os.Getenv("QIMENG_BACKUP_INTERVAL"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_BACKUP_INTERVAL=%q 不是合法时长（如 24h、12h30m）: %w", v, err)
		}
		cfg.Backup.Interval = d
	}
	if v := os.Getenv("QIMENG_BACKUP_RETENTION"); v != "" {
		n, err := strconv.Atoi(v)
		// 同 QIMENG_UPLOAD_MAX_BYTES：拆分支防 %w 包 nil。
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_BACKUP_RETENTION=%q 不是合法正整数: %w", v, err)
		}
		if n < 1 {
			return fmt.Errorf("环境变量 QIMENG_BACKUP_RETENTION=%q 不是合法正整数（须 >=1）", v)
		}
		cfg.Backup.Retention = n
	}
	return nil
}

// applyTrashEnv 覆盖回收站生命周期两键：空值 = 未设置、保留 yaml/默认。
func applyTrashEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_TRASH_RETENTION_DAYS"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_TRASH_RETENTION_DAYS=%q 不是合法正整数: %w", v, err)
		}
		// 须 >=1：0 在判定侧是「永不清除」的防御语义（filing.TrashExpired），
		// 不该能从配置通道达成——想不清除请配超大天数。
		if n < 1 {
			return fmt.Errorf("环境变量 QIMENG_TRASH_RETENTION_DAYS=%q 不是合法正整数（须 >=1）", v)
		}
		cfg.Trash.RetentionDays = n
	}
	if v := os.Getenv("QIMENG_TRASH_SWEEP_INTERVAL"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_TRASH_SWEEP_INTERVAL=%q 不是合法时长（如 1h、30m）: %w", v, err)
		}
		cfg.Trash.SweepInterval = d
	}
	return nil
}

// applyLocalSyncEnv 覆盖本机同步通道三键：Root 空值 = 未设置、保留 yaml/默认
// （空 = 通道关闭的合法语义，与 QIMENG_WEB_STATIC_DIR 同款）；两个时长键与
// QIMENG_BACKUP_INTERVAL 同款 duration 解析。
func applyLocalSyncEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_LOCAL_SYNC_ROOT"); v != "" {
		// TrimSpace：部署来源常见「配置值带首尾空白」（compose 引号/缩进），
		// 同步根路径带尾空格在 Windows 上指向不存在的目录，宁可提前清。
		cfg.LocalSync.Root = strings.TrimSpace(v)
	}
	if v := os.Getenv("QIMENG_LOCAL_SYNC_INTERVAL"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_LOCAL_SYNC_INTERVAL=%q 不是合法时长（如 30s、2m）: %w", v, err)
		}
		cfg.LocalSync.Interval = d
	}
	if v := os.Getenv("QIMENG_LOCAL_SYNC_STABLE_AGE"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_LOCAL_SYNC_STABLE_AGE=%q 不是合法时长（如 60s、5m）: %w", v, err)
		}
		cfg.LocalSync.StableAge = d
	}
	return nil
}

// splitList 按给定单字节分隔符集合切列表，空段与首尾空白跳过/裁剪
// （避免把 "" 或 " b" 当成有效条目）。splitPathList 的通用底座；
// Host 白名单等非路径列表复用。
func splitList(v string, seps string) []string {
	in := map[byte]bool{}
	for i := 0; i < len(seps); i++ {
		in[seps[i]] = true
	}
	var out []string
	start := 0
	flush := func(end int) {
		p := strings.TrimSpace(v[start:end])
		if p != "" {
			out = append(out, p)
		}
	}
	for i := 0; i < len(v); i++ {
		if in[v[i]] {
			flush(i)
			start = i + 1
		}
	}
	flush(len(v))
	return out
}

// splitPathList 把环境变量里的路径列表拆成切片。
// 同时按 ';' 与 os.PathListSeparator 切分：Windows 上两者同为 ';'，
// 不会误切盘符冒号；Unix 上额外接受 ':'，与 PATH 同款习惯。
// 空段（如 "a;;b"）跳过，避免把 "" 当成「当前目录」白名单根。
func splitPathList(v string) []string {
	// Windows 上 os.PathListSeparator 就是 ';'，map 字面量不能写两个相同键；
	// 先放 ';'，再无条件加入 PathListSeparator（同值时覆盖无害）。
	seps := map[byte]bool{';': true}
	seps[os.PathListSeparator] = true
	var out []string
	start := 0
	flush := func(end int) {
		p := v[start:end]
		if p != "" {
			out = append(out, p)
		}
	}
	for i := 0; i < len(v); i++ {
		if seps[v[i]] {
			flush(i)
			start = i + 1
		}
	}
	flush(len(v))
	return out
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
