package authorattach

// mirror.go：本地 txt 自动镜像（REQ §3.4）。镜像是尽力而为的单向投影
// （服务端 → 本地）：写入失败只告警不抛错，绝不阻塞或回滚核心操作——
// 服务端片段永远是唯一真相，路径恢复后的下一次变更自动补写。

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// MirrorConfig 作者总表镜像配置。path 是用户显式配置的唯一例外写点
// （REQ §3.4：默认空=关闭）；fragmentFilename 空=镜像最近导入片段。
// 字段与 openapi AuthorMirrorConfig 结构对应——协议侧改动须同步此处，
// 反之亦然（AI_README_FIRST 代码卫生约束）。
type MirrorConfig struct {
	Path             string `json:"path"`
	FragmentFilename string `json:"fragmentFilename"`
	Mode             string `json:"mode,omitempty"`
}

// mirrorTempPrefix 是镜像原子写在目标目录内的临时文件名前缀（点开头，
// 与目录内常规片段文件视觉隔离；thumbnail 的 ".qimeng-" 同款思路）。
const mirrorTempPrefix = ".qm-mirror-"

// LoadMirrorConfig 读镜像配置（无记录 → 零值 = 关闭）。
func LoadMirrorConfig(ctx context.Context, q *db.Queries) (MirrorConfig, error) {
	v, err := q.GetSetting(ctx, authoring.SettingKeyAuthorMirror)
	if errors.Is(err, sql.ErrNoRows) {
		return MirrorConfig{}, nil
	}
	if err != nil {
		return MirrorConfig{}, fmt.Errorf("authorattach: 读取镜像配置: %w", err)
	}
	var cfg MirrorConfig
	if err := json.Unmarshal([]byte(v), &cfg); err != nil {
		return MirrorConfig{}, fmt.Errorf("authorattach: 解析镜像配置 JSON: %w", err)
	}
	if cfg.Mode == "" {
		cfg.Mode = "fragment"
	}
	return cfg, nil
}

// SaveMirrorConfig 写镜像配置。
func SaveMirrorConfig(ctx context.Context, q *db.Queries, now time.Time, cfg MirrorConfig) error {
	raw, err := json.Marshal(cfg)
	if err != nil {
		return fmt.Errorf("authorattach: 序列化镜像配置: %w", err)
	}
	if err := q.UpsertSetting(ctx, db.UpsertSettingParams{
		Key:       authoring.SettingKeyAuthorMirror,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(now),
	}); err != nil {
		return fmt.Errorf("authorattach: 写回镜像配置: %w", err)
	}
	return nil
}

// MirrorWriter 把目标片段原文原子写入镜像文件。Logger 为 nil 时用
// slog.Default()。
type MirrorWriter struct {
	Logger *slog.Logger
}

var mirrorWriteMu sync.Mutex

func (w *MirrorWriter) logger() *slog.Logger {
	if w.Logger != nil {
		return w.Logger
	}
	return slog.Default()
}

// Refresh：path 空→直接返回；否则定位目标片段（FragmentFilename 命名
// 匹配；空→MostRecent；无目标→debug 级日志跳过）并把原文逐字节原子写入
// 镜像路径。任何失败 slog.Warn（含 path 与 err）后返回——绝不向上抛错。
// 任何改变片段内容的服务端操作都应在同一时机调用（REQ §3.4）。
func (w *MirrorWriter) Refresh(ctx context.Context, q *db.Queries) {
	mirrorWriteMu.Lock()
	defer mirrorWriteMu.Unlock()
	cfg, err := LoadMirrorConfig(ctx, q)
	if err != nil {
		w.logger().Warn("作者镜像：读取配置失败", "err", err)
		return
	}
	if cfg.Path == "" {
		return // 未配置 = 关闭
	}
	if cfg.Mode == "master" {
		content, err := MasterTXT(ctx, q)
		if err == nil {
			var sources []Source
			sources, err = LoadSources(ctx, q)
			if err == nil {
				err = writeMasterFile(cfg.Path, content, sources)
			}
		}
		if err != nil {
			w.logger().Warn("作者总表：写入失败", "err", err)
		}
		return
	}
	sources, err := LoadSources(ctx, q)
	if err != nil {
		w.logger().Warn("作者镜像：读取片段失败", "path", cfg.Path, "err", err)
		return
	}
	content, ok := mirrorTarget(sources, cfg.FragmentFilename)
	if !ok {
		// 配置指名的片段不存在（或库中无片段）：留 debug 痕迹即可——
		// 片段再次导入后的下一次变更会自动补写。
		w.logger().Debug("作者镜像：目标片段不存在，跳过", "path", cfg.Path, "fragment", cfg.FragmentFilename)
		return
	}
	if err := writeFileAtomically(cfg.Path, content); err != nil {
		w.logger().Warn("作者镜像：写入失败", "path", cfg.Path, "err", err)
	}
}

func writeMasterFile(path, content string, sources []Source) error {
	current, readErr := os.ReadFile(path)
	if readErr != nil && !errors.Is(readErr, fs.ErrNotExist) {
		return readErr
	}
	checksum, err := os.ReadFile(path + ".sha256")
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	if err == nil {
		accepted := string(current) == content
		for _, source := range sources {
			if source.Content == string(current) {
				accepted = true
				break
			}
		}
		if readErr == nil && !accepted && strings.TrimSpace(string(checksum)) != fmt.Sprintf("%x", sha256.Sum256(current)) {
			return errors.New("作者主表已被外部编辑，请先导入后再写回")
		}
	}
	if readErr == nil && string(current) != content {
		previous := strings.TrimSuffix(path, filepath.Ext(path)) + "（上次）" + filepath.Ext(path)
		older := strings.TrimSuffix(path, filepath.Ext(path)) + "（上上次）" + filepath.Ext(path)
		last, err := os.ReadFile(previous)
		if err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		// A retry after a partial write must not rotate the same generation twice.
		if err == nil && string(last) != string(current) {
			if err := writeCheckedMaster(older, string(last)); err != nil {
				return err
			}
		}
		if err := writeCheckedMaster(previous, string(current)); err != nil {
			return err
		}
	}
	return writeCheckedMaster(path, content)
}

func writeCheckedMaster(path, content string) error {
	if err := writeFileAtomically(path, content); err != nil {
		return err
	}
	return writeFileAtomically(path+".sha256", fmt.Sprintf("%x", sha256.Sum256([]byte(content))))
}

// mirrorTarget 定位镜像对象的内容：指名片段按 Filename 精确匹配；空名 →
// 最近导入片段。
func mirrorTarget(sources []Source, fragmentFilename string) (string, bool) {
	if fragmentFilename == "" {
		if s, ok := MostRecent(sources); ok {
			return s.Content, true
		}
		return "", false
	}
	for _, s := range sources {
		if s.Filename == fragmentFilename {
			return s.Content, true
		}
	}
	return "", false
}

// writeFileAtomically 原子写 path：目标目录内建临时文件写全文 → 关闭 →
// os.Rename 覆盖（Windows 下 Go 的 os.Rename 走 MoveFileEx+
// REPLACE_EXISTING，覆盖既有文件可用）。失败时清理残留临时文件。
func writeFileAtomically(path, content string) error {
	f, err := os.CreateTemp(filepath.Dir(path), mirrorTempPrefix+"*")
	if err != nil {
		return fmt.Errorf("authorattach: 创建镜像临时文件: %w", err)
	}
	tmp := f.Name()
	_, werr := f.WriteString(content)
	if werr != nil {
		werr = fmt.Errorf("authorattach: 写入镜像临时文件: %w", werr)
	}
	// 句柄必须先关：rename 一个被占用的文件不可移植（thumbnail
	// writeAtomically 同款口径）。关闭失败按主错误处理——数据可能未刷盘。
	if cerr := f.Close(); cerr != nil && werr == nil {
		werr = fmt.Errorf("authorattach: 关闭镜像临时文件: %w", cerr)
	}
	if werr != nil {
		if rmErr := os.Remove(tmp); rmErr != nil && !errors.Is(rmErr, fs.ErrNotExist) {
			werr = fmt.Errorf("%w;（清理临时文件 %s 也失败: %v）", werr, tmp, rmErr)
		}
		return werr
	}
	if err := os.Rename(tmp, path); err != nil {
		return fmt.Errorf("authorattach: 原子落盘镜像 %s: %w", path, err)
	}
	return nil
}
