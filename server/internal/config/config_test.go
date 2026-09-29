package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// writeYAML 把内容写进临时目录下的配置文件，返回文件路径。
// 用 t.TempDir() 保证测试之间互不污染、结束后自动清理。
func writeYAML(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("写临时配置文件失败: %v", err)
	}
	return path
}

// TestLoadDefaults 锁定零配置默认值，尤其是 listen 必须与 api/openapi.yaml 的
// servers 端口一致（":8420"）——这是客户端 SDK 能否连上服务端的契约。
func TestLoadDefaults(t *testing.T) {
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load(\"\") 报错: %v", err)
	}
	if cfg.Listen != ":8420" {
		t.Errorf("默认 Listen = %q, 期望 %q（与 openapi.yaml servers 端口一致）", cfg.Listen, ":8420")
	}
	if cfg.DataDir == "" {
		t.Error("默认 DataDir 不应为空")
	}
	if cfg.LogLevel != "info" {
		t.Errorf("默认 LogLevel = %q, 期望 %q", cfg.LogLevel, "info")
	}
	if cfg.Thumbnail.LongSide != 0 {
		t.Errorf("默认 Thumbnail.LongSide = %d, 期望 0（0=回落 thumbnail 包默认档 SizeGrid，档位像素单一来源不在 config）", cfg.Thumbnail.LongSide)
	}
}

// TestLoadYAMLOverride 验证 yaml 文件能覆盖默认值，且未写的字段保留默认值。
func TestLoadYAMLOverride(t *testing.T) {
	path := writeYAML(t, "listen: \":9999\"\ndb_path: \"/tmp/test.db\"\n")
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Listen != ":9999" {
		t.Errorf("Listen = %q, 期望被 yaml 覆盖为 :9999", cfg.Listen)
	}
	if cfg.DbPath != "/tmp/test.db" {
		t.Errorf("DbPath = %q, 期望 %q", cfg.DbPath, "/tmp/test.db")
	}
	// 未在 yaml 中出现的字段必须保留默认值（yaml 只覆盖出现的字段）。
	if cfg.LogLevel != "info" {
		t.Errorf("LogLevel = %q, 期望保留默认值 info", cfg.LogLevel)
	}
}

// TestLoadEnvPriority 锁定优先级契约：环境变量 > yaml 文件 > 默认值。
// 这是部署行为的关键约定，改了会让 docker compose 的 env 覆盖悄悄失效。
func TestLoadEnvPriority(t *testing.T) {
	path := writeYAML(t, "listen: \":9999\"\ndata_dir: \"/from-yaml\"\n")
	t.Setenv("QIMENG_LISTEN", ":8888")
	t.Setenv("QIMENG_DATA_DIR", "/from-env")

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Listen != ":8888" {
		t.Errorf("Listen = %q, 期望 env 值 :8888（env 必须优先于 yaml）", cfg.Listen)
	}
	if cfg.DataDir != "/from-env" {
		t.Errorf("DataDir = %q, 期望 env 值 /from-env", cfg.DataDir)
	}
}

// TestLoadMissingFileNotError 配置文件不存在必须容忍（开发期零配置可跑），
// 而不是让服务起不来。
func TestLoadMissingFileNotError(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "不存在.yaml"))
	if err != nil {
		t.Fatalf("配置文件不存在应容忍, 实际报错: %v", err)
	}
	if cfg.Listen != ":8420" {
		t.Errorf("Listen = %q, 期望默认值 :8420", cfg.Listen)
	}
}

// TestLoadMalformedYAML 配置文件存在但格式非法必须报错——静默吞掉会让
// "改了配置没生效"变成无迹可寻的幽灵问题。
func TestLoadMalformedYAML(t *testing.T) {
	path := writeYAML(t, "listen: [未闭合的数组\n  坏缩进: :::")
	_, err := Load(path)
	if err == nil {
		t.Fatal("yaml 格式非法时应返回错误, 实际为 nil")
	}
	if !strings.Contains(err.Error(), path) {
		t.Errorf("错误信息应包含文件路径 %s 便于定位, 实际: %v", path, err)
	}
}

// TestLoadWebStaticDirDefault 锁定 SPA 托管默认目录 "../web/dist"：
// 相对 server 启动工作目录（Makefile server-run 与 启动服务端.bat 都
// cd 进 server/ 再启动），开发期零配置即命中构建产物。
func TestLoadWebStaticDirDefault(t *testing.T) {
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load(\"\") 报错: %v", err)
	}
	if cfg.Web.StaticDir != "../web/dist" {
		t.Errorf("默认 Web.StaticDir = %q, 期望 %q", cfg.Web.StaticDir, "../web/dist")
	}
}

// TestLoadWebStaticDirOverrides 验证 web.static_dir 的 yaml/env 覆盖与
// 空串禁用语义（yaml 显式空串 = 禁用 SPA 托管）。
func TestLoadWebStaticDirOverrides(t *testing.T) {
	path := writeYAML(t, "web:\n  static_dir: \"/data/web/dist\"\n")
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Web.StaticDir != "/data/web/dist" {
		t.Errorf("Web.StaticDir = %q, 期望 yaml 覆盖 /data/web/dist", cfg.Web.StaticDir)
	}
	// env 优先于 yaml。
	t.Setenv("QIMENG_WEB_STATIC_DIR", "/from-env/dist")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Web.StaticDir != "/from-env/dist" {
		t.Errorf("Web.StaticDir = %q, 期望 env 值 /from-env/dist", cfg.Web.StaticDir)
	}
	// yaml 显式空串 = 禁用 SPA 托管（env 已设时被 env 覆盖；先清 env 验证 yaml 语义）。
	t.Setenv("QIMENG_WEB_STATIC_DIR", "")
	path = writeYAML(t, "web:\n  static_dir: \"\"\n")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Web.StaticDir != "" {
		t.Errorf("显式空 static_dir 应禁用 SPA 托管（空字符串），得到 %q", cfg.Web.StaticDir)
	}
}

// TestLoadInvalidEnvWorkers 整数型环境变量给非法值必须报错，
// 不允许静默回退默认值掩盖问题。
func TestLoadInvalidEnvWorkers(t *testing.T) {
	t.Setenv("QIMENG_THUMBNAIL_WORKERS", "不是数字")
	if _, err := Load(""); err == nil {
		t.Fatal("QIMENG_THUMBNAIL_WORKERS 非法时应返回错误, 实际为 nil")
	}
}

// TestLoadFFmpegBinPathOverrides 锁定 ffmpeg/ffprobe 二进制路径配置的三段语义：
// 默认空（= PATH 自动发现，行为零变化）、yaml 覆盖、env 优先于 yaml。
// 这是 M6 单机形态（ADR-0015）的配置通道，覆盖失效会让手机上的缩略图/探测
// 全线静默回退占位图，必须在此锁定。
func TestLoadFFmpegBinPathOverrides(t *testing.T) {
	// 默认值：两个路径都是空串（空 = 回退裸命令名自动发现）。
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load(\"\") 报错: %v", err)
	}
	if cfg.Thumbnail.FFmpegPath != "" || cfg.Thumbnail.FFprobePath != "" {
		t.Errorf("默认 FFmpegPath/FFprobePath 应为空（PATH 自动发现），得到 %q/%q",
			cfg.Thumbnail.FFmpegPath, cfg.Thumbnail.FFprobePath)
	}

	// yaml 覆盖默认值。
	path := writeYAML(t, "thumbnail:\n  ffmpeg_path: \"/opt/ffmpeg/bin/ffmpeg\"\n  ffprobe_path: \"/opt/ffmpeg/bin/ffprobe\"\n")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Thumbnail.FFmpegPath != "/opt/ffmpeg/bin/ffmpeg" || cfg.Thumbnail.FFprobePath != "/opt/ffmpeg/bin/ffprobe" {
		t.Errorf("yaml 覆盖未生效，得到 %q/%q", cfg.Thumbnail.FFmpegPath, cfg.Thumbnail.FFprobePath)
	}

	// env 优先于 yaml。
	t.Setenv("QIMENG_THUMBNAIL_FFMPEG_PATH", "/from-env/ffmpeg")
	t.Setenv("QIMENG_THUMBNAIL_FFPROBE_PATH", "/from-env/ffprobe")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Thumbnail.FFmpegPath != "/from-env/ffmpeg" || cfg.Thumbnail.FFprobePath != "/from-env/ffprobe" {
		t.Errorf("env 应优先于 yaml，得到 %q/%q", cfg.Thumbnail.FFmpegPath, cfg.Thumbnail.FFprobePath)
	}
}

// TestLoadAllowedLibraryRoots 锁定 allowed_library_roots 的默认空（不限制）、
// yaml 列表覆盖、env 路径列表覆盖与空段跳过语义。
func TestLoadAllowedLibraryRoots(t *testing.T) {
	// 默认：空 = 不限制（向后兼容本地零配置）。
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load(\"\") 报错: %v", err)
	}
	if len(cfg.AllowedLibraryRoots) != 0 {
		t.Errorf("默认 AllowedLibraryRoots 应为空（不限制），得到 %v", cfg.AllowedLibraryRoots)
	}

	// yaml 列表覆盖。
	path := writeYAML(t, "allowed_library_roots:\n  - \"/media/photos\"\n  - \"/media/videos\"\n")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if len(cfg.AllowedLibraryRoots) != 2 ||
		cfg.AllowedLibraryRoots[0] != "/media/photos" ||
		cfg.AllowedLibraryRoots[1] != "/media/videos" {
		t.Errorf("yaml 白名单未生效，得到 %v", cfg.AllowedLibraryRoots)
	}

	// env 路径列表：同时接受 ';' 与本平台 PathListSeparator；空段跳过。
	t.Setenv("QIMENG_ALLOWED_LIBRARY_ROOTS", "/a;;/b;"+string(os.PathListSeparator)+"/c")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	want := []string{"/a", "/b", "/c"}
	if len(cfg.AllowedLibraryRoots) != len(want) {
		t.Fatalf("env 白名单段数期望 %d，得到 %v", len(want), cfg.AllowedLibraryRoots)
	}
	for i, w := range want {
		if cfg.AllowedLibraryRoots[i] != w {
			t.Errorf("AllowedLibraryRoots[%d] = %q, 期望 %q", i, cfg.AllowedLibraryRoots[i], w)
		}
	}

	// env 空值 = 未设置，保留 yaml 值。
	t.Setenv("QIMENG_ALLOWED_LIBRARY_ROOTS", "")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if len(cfg.AllowedLibraryRoots) != 2 {
		t.Errorf("env 空值应保留 yaml 白名单，得到 %v", cfg.AllowedLibraryRoots)
	}
}

// TestLoadBackupDefaults 锁定备份热备默认值（任务Q 批B 冻结口径）：
// enabled=true / interval=24h / retention=7。
func TestLoadBackupDefaults(t *testing.T) {
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load(\"\") 报错: %v", err)
	}
	if !cfg.Backup.Enabled {
		t.Error("默认 Backup.Enabled = false, 期望 true")
	}
	if cfg.Backup.Interval != DefaultBackupInterval {
		t.Errorf("默认 Backup.Interval = %v, 期望 %v", cfg.Backup.Interval, DefaultBackupInterval)
	}
	if cfg.Backup.Retention != DefaultBackupRetention {
		t.Errorf("默认 Backup.Retention = %d, 期望 %d", cfg.Backup.Retention, DefaultBackupRetention)
	}
}

// TestLoadBackupYAMLOverride yaml 三键覆盖 + 未写键保留默认。
func TestLoadBackupYAMLOverride(t *testing.T) {
	path := writeYAML(t, "backup:\n  enabled: false\n  interval: 12h\n")
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Backup.Enabled {
		t.Error("yaml backup.enabled=false 应生效")
	}
	if cfg.Backup.Interval != 12*time.Hour {
		t.Errorf("yaml backup.interval = %v, 期望 12h", cfg.Backup.Interval)
	}
	if cfg.Backup.Retention != DefaultBackupRetention {
		t.Errorf("未写的 backup.retention 应保留默认 7, 得到 %d", cfg.Backup.Retention)
	}
}

// TestLoadBackupEnvPriority env > yaml > 默认三键优先级 + 非法值报错。
func TestLoadBackupEnvPriority(t *testing.T) {
	path := writeYAML(t, "backup:\n  interval: 12h\n")
	t.Setenv("QIMENG_BACKUP_INTERVAL", "6h")
	t.Setenv("QIMENG_BACKUP_ENABLED", "0")
	t.Setenv("QIMENG_BACKUP_RETENTION", "3")
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Backup.Enabled {
		t.Error("env QIMENG_BACKUP_ENABLED=0 应生效")
	}
	if cfg.Backup.Interval != 6*time.Hour {
		t.Errorf("env interval = %v, 期望 6h（env 优先于 yaml 12h）", cfg.Backup.Interval)
	}
	if cfg.Backup.Retention != 3 {
		t.Errorf("env retention = %d, 期望 3", cfg.Backup.Retention)
	}

	// 非法值必须报错而非静默回落默认（排障可见性，与其他 env 键同口径）。
	t.Setenv("QIMENG_BACKUP_ENABLED", "maybe")
	if _, err := Load(path); err == nil {
		t.Error("QIMENG_BACKUP_ENABLED 非法布尔应报错")
	}
	t.Setenv("QIMENG_BACKUP_ENABLED", "true")
	t.Setenv("QIMENG_BACKUP_INTERVAL", "不是时长")
	if _, err := Load(path); err == nil {
		t.Error("QIMENG_BACKUP_INTERVAL 非法时长应报错")
	}
	t.Setenv("QIMENG_BACKUP_INTERVAL", "6h")
	t.Setenv("QIMENG_BACKUP_RETENTION", "0")
	if _, err := Load(path); err == nil {
		t.Error("QIMENG_BACKUP_RETENTION 非正整数应报错")
	}
}

// TestLoadBackupIntervalZeroFallback 非法间隔兜底（reviewer P2 清偿）：
// 能通过 Load 的零/负间隔必须回落 DefaultBackupInterval（24h）——否则
// 直通 Manager.Start 会关掉定时调度，调度回显也会把 0 展示成「每 1h」
// 误导运维。三条路径分开锁定：
//   - yaml `interval: 0`（裸整数）= yaml.v3 解析期直接报错（!!int 无法
//     解析成 time.Duration），fail-fast 起不来服务——不测回落，测报错；
//   - yaml `interval: -1h`（负时长字符串可解析）与 env `0s` = 实际可达
//     的直通路径，必须回落。
func TestLoadBackupIntervalZeroFallback(t *testing.T) {
	// yaml 裸整数 0：解析期报错（fail-fast，不静默）。
	path := writeYAML(t, "backup:\n  interval: 0\n")
	if _, err := Load(path); err == nil {
		t.Error("yaml interval: 0（裸整数）应解析报错")
	}

	// yaml 负时长字符串：可达路径，回落 24h。
	path = writeYAML(t, "backup:\n  interval: -1h\n")
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Backup.Interval != DefaultBackupInterval {
		t.Errorf("yaml interval: -1h 应回落 %v, 得到 %v", DefaultBackupInterval, cfg.Backup.Interval)
	}

	// env 0s（env 优先级下同样兜底）。
	t.Setenv("QIMENG_BACKUP_INTERVAL", "0s")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.Backup.Interval != DefaultBackupInterval {
		t.Errorf("env 0s 应回落 %v, 得到 %v", DefaultBackupInterval, cfg.Backup.Interval)
	}
}

// TestLoadAuthDevSharedSecret 锁定 dev-login 共享密钥三态：默认空
// （不校验，Web/生产零影响）、yaml 覆盖、env 优先覆盖（Android 内嵌
// 形态靠 env 把随机密钥传给拉起的子进程）。
func TestLoadAuthDevSharedSecret(t *testing.T) {
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load(\"\") 报错: %v", err)
	}
	if cfg.AuthDevSharedSecret != "" {
		t.Errorf("默认 AuthDevSharedSecret = %q, 期望空串（空 = 不校验）", cfg.AuthDevSharedSecret)
	}

	path := writeYAML(t, "auth_dev_shared_secret: \"from-yaml\"\n")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.AuthDevSharedSecret != "from-yaml" {
		t.Errorf("AuthDevSharedSecret = %q, 期望被 yaml 覆盖为 from-yaml", cfg.AuthDevSharedSecret)
	}

	t.Setenv("QIMENG_AUTH_DEV_SHARED_SECRET", "from-env")
	cfg, err = Load(path)
	if err != nil {
		t.Fatalf("Load 报错: %v", err)
	}
	if cfg.AuthDevSharedSecret != "from-env" {
		t.Errorf("AuthDevSharedSecret = %q, 期望 env 优先覆盖为 from-env", cfg.AuthDevSharedSecret)
	}
}
