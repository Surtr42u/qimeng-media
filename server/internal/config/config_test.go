package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
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
