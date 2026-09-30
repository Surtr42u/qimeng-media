// qimeng 是绮梦媒体库服务端入口。
//
// 组装：配置加载 → JSON 日志 → store（SQLite + 迁移）→ 事件总线 →
// 缩略图编排器 → httpapi（全部协议端点）→ 扫描器适配器 → 优雅退出。
// 依赖注入只在 main 发生，业务包之间不互相 new（ARCHITECTURE §5）。
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/backup"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/thumbnail"
)

// version 是服务版本号的唯一来源：SSE hello 帧回显、系统面板 version
// 字段共用它（M5 镜像交付时改为 -ldflags 注入点，消费方零改动）。
const version = "0.1.0"

// shutdownTimeout 是优雅退出的最长等待时间。
// 为什么定 10s：覆盖慢客户端把响应读完 + 在途缩略图任务让出，
// 同时给容器编排（默认 30s 强杀）留出余量。
const shutdownTimeout = 10 * time.Second

// readHeaderTimeout 是客户端发完请求 header 的时间上限（Slowloris 防御：
// 恶意客户端"连接后慢慢滴 header"占住连接不做事，不设上限连接池会被
// 拖干）。只限 header、不限 body/响应体——大上传与大文件直链不受影响
// （见 main 内 http.Server 组装处"不能设全局 ReadTimeout"注释）。
// 与 shutdownTimeout 同值是两个独立决策（攻击防御上限 vs 优雅退出等待），
// 不是共享值，可各自调整。
const readHeaderTimeout = 10 * time.Second

// mediaSecretFile 是直链 HMAC 密钥的持久化文件名（DataDir 下）。
// 为什么落盘：密钥每次随机会让重启后全部存量直链立即失效（浏览器
// 已打开页面的图全裂）；持久化后"换密钥 = 吊销全部直链"的应急语义
// 也依然成立（删掉文件重启即换新）。
const mediaSecretFile = "media-secret"

// main 组合根：config→db→密钥→总线→监控→缩略图→httpapi→扫描器→serve
// 的直线装配。依赖注入只在 main 发生是模块边界的显式例外（ADR-0010
// depguard 红线的唯一豁免点），拆分会扩散组合根。
// 超函数警戒线（>100 行）理由：装配步骤顺序耦合（后者依赖前者产物），
// 无嵌套分支复杂度；优雅关闭的编排顺序（工作池→总线→库连接）属收尾
// 直线流的一部分，注释在原位。
func main() {
	// 临时 logger 兜底启动早期错误：真正的 JSON logger 要等配置加载完才能建，
	// 在此之前出错也得有地方可看（stderr 直写）。
	bootLogger := slog.New(slog.NewTextHandler(os.Stderr, nil))

	configPath := flag.String("config", "config.yaml", "配置文件路径（yaml，可不存在；环境变量始终优先于文件）")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		bootLogger.Error("加载配置失败", "error", err, "configPath", *configPath)
		os.Exit(1)
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: cfg.SlogLevel(),
	}))
	slog.SetDefault(logger)

	// SECURITY「开发模式」边界的启动期提醒：dev 免密登录等价于无凭据登录
	// 通道，而默认 listen ":8420" 绑定全部网卡（与 0.0.0.0 等价）——同网段
	// 任意设备都能换到 admin token。只提醒不阻止（本机开发脚本
	// 启动服务端.bat 的既定用法），生产/远程部署必须 auth_dev_mode=false
	// 或显式配回环地址（SECURITY.md 开发模式节 + 部署清单）。
	if cfg.AuthDevMode && !loopbackListen(cfg.Listen) {
		logger.Warn("auth_dev_mode 开启且监听地址非回环：免密登录通道暴露给整个局域网，仅限本机受信环境", "listen", cfg.Listen)
	}

	// DataDir 必须先就位（库文件/缩略图/密钥文件都落在它下面）。
	if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
		logger.Error("创建数据目录失败", "error", err, "dataDir", cfg.DataDir)
		os.Exit(1)
	}

	dbPath := cfg.DbPath
	if dbPath == "" {
		dbPath = filepath.Join(cfg.DataDir, "qimeng.db")
	}
	conn, err := store.Open(dbPath)
	if err != nil {
		logger.Error("打开数据库失败", "error", err, "dbPath", dbPath)
		os.Exit(1)
	}
	if err := store.Migrate(conn); err != nil {
		logger.Error("数据库迁移失败", "error", err)
		os.Exit(1)
	}
	queries := db.New(conn)

	secret, err := loadOrCreateMediaSecret(cfg, logger)
	if err != nil {
		logger.Error("准备直链密钥失败", "error", err)
		os.Exit(1)
	}

	bus := events.NewBus(logger, events.DefaultBuffer)
	// 系统监控采集器尽早创建：startedAt 即进程启动近似时刻，CPU/网络
	// 的差分基线也从这里起算（见 sysmon.NewCollector 注释）。
	sysCollector := sysmon.NewCollector()
	// 缩略图配置接线：Workers/LongSide 在此从 config 传入 Generator（内部建池），
	// 档位像素单一来源在 thumbnail 包（LongSide<=0 回落 SizeGrid）。
	// FFmpegPath/FFprobePath 空时 Generator 内部回退裸命令名走 PATH 自动发现
	// （缺省行为零变化）；M6 单机形态用配置指向 App 打包的二进制（ADR-0015）。
	thumbs := thumbnail.NewGenerator(cfg.DataDir, logger, thumbnail.Options{
		Workers:     cfg.Thumbnail.Workers,
		LongSide:    cfg.Thumbnail.LongSide,
		FFmpegPath:  cfg.Thumbnail.FFmpegPath,
		FFprobePath: cfg.Thumbnail.FFprobePath,
	})

	// ffmpeg/ffprobe 启动期自检（告警不阻断）：缺失=既定降级形态，提前到部署
	// 当下暴露（M6 单机形态二进制投放错误时第一时间可见，不等首次调用）。
	ffBin, fpBin, ffErr, fpErr := thumbnail.CheckBinaries(cfg.Thumbnail.FFmpegPath, cfg.Thumbnail.FFprobePath)
	if ffErr != nil || fpErr != nil {
		logger.Warn("ffmpeg/ffprobe 自检未通过：缩略图/探测将降级运行（缩略图 404 占位、视频元数据留空、下次扫描自动重探）",
			"ffmpeg", ffBin, "ffmpegErr", ffErr,
			"ffprobe", fpBin, "ffprobeErr", fpErr,
			"hint", "检查二进制投放或 config thumbnail.ffmpeg_path/ffprobe_path")
	} else {
		logger.Info("ffmpeg/ffprobe 自检通过", "ffmpeg", ffBin, "ffprobe", fpBin)
	}

	// 备份热备管理器（任务Q 批B）：快照执行器 = store.VacuumInto 适配
	// （SQL 属 store 边界，backup 包不碰数据库）；成功回调接 sysmon 指标
	//（backup_last_success_timestamp，装配期单点接线，与 SSE gauge 同款）。
	// 手动触发端点无论 enabled 与否都可用（开关只管定时面）。
	backupMgr, err := backup.NewManager(backup.Options{
		Dir: filepath.Join(cfg.DataDir, backup.DirName),
		Snapshot: func(ctx context.Context, dest string) error {
			return store.VacuumInto(conn, dest)
		},
		Retention: cfg.Backup.Retention,
		Logger:    logger,
		OnSuccess: func(at time.Time) { sysmon.Default.SetBackupLastSuccess(float64(at.Unix())) },
	})
	if err != nil {
		logger.Error("组装备份管理器失败", "error", err)
		os.Exit(1)
	}

	apiSrv, err := httpapi.New(httpapi.Deps{
		Conn:        conn,
		Queries:     queries,
		Bus:         bus,
		Cfg:         cfg,
		Thumbs:      thumbs,
		Scanner:     nil, // 下面用真扫描器适配器覆盖（先建 Server 再接 FinishScan 钩子）
		SysStatus:   newSysStatusAdapter(sysCollector, queries, cfg.DataDir, version).snapshot,
		Metrics:     sysmon.Default.Handler(),
		Backup:      backupMgr,
		MediaSecret: secret,
		TokenTTL:    cfg.TokenTTL,
		Logger:      logger,
		Version:     version,
		// 上传挂靠编排 + 作者总表镜像（ADR-0019：DI 在 main；两服务无
		// 外部依赖，缺省实例即生产实现，这里显式装配是规范形态）。
		Attach: &authorattach.Service{Logger: logger},
		Mirror: &authorattach.MirrorWriter{Logger: logger},
	})
	if err != nil {
		logger.Error("组装 HTTP 服务失败", "error", err)
		os.Exit(1)
	}

	// 真扫描器：同步实现 + 异步适配器（触发即返回，终态回写 Server）。
	// dataDir 传入做自噬防御（缩略图缓存是 webp 白名单格式，数据目录若被
	// 配置进库内绝不能扫进库）。探测函数注入 thumbs.ProbeVideo：扫描探测的
	// ffprobe 路径与缩略图管线同源（thumbnail.ffprobe_path 单点解析）。
	scan := scanner.New(queries, bus, logger, cfg.DataDir, thumbs.ProbeVideo)
	// 缩略图失效钩子接线：扫描重探测发现内容变更（size/mtime 变化）时删旧
	// 缓存，外部原地换文件后海报帧按新内容重建（生产装配单点，漏接线=
	// 缩略图陈旧不自愈，见 scanner.invalidateThumbs）。
	scan.SetThumbsInvalidator(thumbs.DeleteAssetThumbs)
	apiSrv.SetScanner(newScannerAdapter(scan, queries, apiSrv, logger))
	// 自动预生成缩略图（2026-09-15 批）：开机回填历史积压 + 周期兜底（daemon）
	apiSrv.StartThumbnailWarmup()
	// 推荐流缓存开机预热（2026-09-18 性能批二段）：后台预计算 App 首屏同键
	// 默认流（seed=1&limit=200），首条请求经单飞共享同一次计算
	apiSrv.StartRecommendPrewarm()

	handler := apiSrv.Handler()

	srv := &http.Server{
		Addr:    cfg.Listen,
		Handler: handler,
		// 读超时只限 header：媒体直链依赖长连接与大响应体，
		// 不能设全局 ReadTimeout/WriteTimeout 一刀切掐掉大文件传输。
		ReadHeaderTimeout: readHeaderTimeout,
	}

	// signal.NotifyContext：Ctrl+C / SIGTERM 时 ctx 取消，主流程进入优雅关闭。
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// 轮询兜底：定时全量扫描全部注册库（fsnotify 的 Windows 行为差异与
	// "建目录+立即写入"事件缺口由它补齐，见 scanner/watch.go 注释）。
	// M1 只挂轮询；按库 Watch 在 M2 文件管理接线时挂（新注册库动态加入）。
	go func() {
		if err := scan.StartBackground(ctx, scanner.DefaultPollInterval); err != nil && ctx.Err() == nil {
			logger.Error("轮询扫描退出", "err", err)
		}
	}()

	// 定时快照调度（任务Q 批B）：enabled=true 时按 backup.interval 周期
	// 快照（首个快照在一个间隔后触发；退出随 ctx 取消）。手动触发端点
	// 不受此开关影响。
	if cfg.Backup.Enabled {
		backupMgr.Start(ctx, cfg.Backup.Interval)
	}

	// 回收站到期清扫（DOMAIN_RULES §9，2026-09-22）：按 trash.sweep_interval
	// 周期物理清除超过 trash.retention_days 的条目并联动清缩略图
	//（退出随 ctx 取消；无 enabled 开关，见 StartTrashSweeper 注释）。
	apiSrv.StartTrashSweeper(ctx)

	// 断点续传上传会话过期清扫（ADR-0028）：无活动超 24h 的会话连同临时
	// 分片文件一起回收（退出随 ctx 取消；清扫不 bump 修订号——未产生资产）。
	apiSrv.StartUploadSweeper(ctx)

	// errCh 把 goroutine 里的监听错误传回主流程——不允许 err 悄悄丢失。
	errCh := make(chan error, 1)
	go func() {
		logger.Info("HTTP 服务启动", "listen", cfg.Listen)
		// http.ErrServerClosed 是 Shutdown 的正常返回，不算故障。
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- fmt.Errorf("HTTP 服务异常退出: %w", err)
		}
	}()

	select {
	case err := <-errCh:
		logger.Error("服务启动失败", "error", err)
		os.Exit(1)
	case <-ctx.Done():
		logger.Info("收到退出信号，开始优雅关闭")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("优雅关闭超时或失败", "error", err)
		os.Exit(1)
	}
	// 工作池先收：等在途缩略图任务排空；再关总线让 SSE 订阅者收流结束，
	// 最后关库连接——顺序反了会出现"关库后任务/总线还在投递"的竞态窗口。
	thumbs.Close()
	// 在途上传会话与临时分片文件的关停清理（ADR-0028）：尽力而为，删不掉
	// 的遗留由下轮孤儿清理兜底；须在 HTTP 已 Shutdown（无在途 PATCH）后执行。
	apiSrv.CloseUploadSessions()
	bus.Close()
	if err := conn.Close(); err != nil {
		logger.Error("关闭数据库失败", "error", err)
	}
	logger.Info("已完全退出")
}

// loopbackListen 判断监听地址是否只绑回环（SECURITY 开发模式边界提醒的
// 判定输入）：host 为空 = 全部网卡（等价 0.0.0.0/[::]），不算回环；
// 无法解析的地址按不安全处理。
func loopbackListen(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return false
	}
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// loadOrCreateMediaSecret 取直链 HMAC 密钥：配置显式指定 > DataDir 下
// 的密钥文件（无则生成 32 字节随机并以 0600 落盘）。
func loadOrCreateMediaSecret(cfg *config.Config, logger *slog.Logger) ([]byte, error) {
	if cfg.MediaSecret != "" {
		return []byte(cfg.MediaSecret), nil
	}
	path := filepath.Join(cfg.DataDir, mediaSecretFile)
	if b, err := os.ReadFile(path); err == nil && len(b) >= 32 {
		return b, nil
	} else if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return nil, fmt.Errorf("读取直链密钥文件 %s: %w", path, err)
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return nil, fmt.Errorf("生成直链密钥: %w", err)
	}
	hexKey := []byte(hex.EncodeToString(buf))
	// 0600：密钥文件只有服务进程可读（同机其他用户不可窥探）。
	if err := os.WriteFile(path, hexKey, 0o600); err != nil {
		return nil, fmt.Errorf("持久化直链密钥 %s: %w", path, err)
	}
	logger.Info("已生成新的直链签名密钥（旧直链若有则全部失效）", "file", mediaSecretFile)
	return hexKey, nil
}
