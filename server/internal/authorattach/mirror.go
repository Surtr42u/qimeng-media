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

// 两代分代目录的命名约定（2026-11-07 R7）：配置 path 落在 …/当前/… 时，
// 上一代镜像提交到同根 …/上次/…。上一代路径仍由唯一配置 path 推导，不新增
// 第二个绝对路径字段（镜像写点不翻倍）；协议 schema 不变，语义见
// docs/api/authors.md「作者总表镜像」章节。
const (
	currentGenerationDirName       = "当前"
	previousGenerationDirName      = "上次"
	currentGenerationDirNameASCII  = "current"
	previousGenerationDirNameASCII = "previous"
)

// flat3 三代同目录布局的文件名后缀：不遵循代目录约定（或推导失败）的路径
// 保持旧语义，既有用户配置行为不变。
const (
	flat3PreviousSuffix = "（上次）"
	flat3OlderSuffix    = "（上上次）"
)

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
				err = writeMasterFile(cfg.Path, content, sources, w.logger())
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

// writeMasterFile 生成作者总表镜像（master 模式）。外部编辑保护、幂等与
// 轮换顺序沿用原语义，轮换目标由 path 的目录命名约定决定：path 落在
// …/当前/… 时提交到同根 …/上次/… 两代布局（每代独立目录与摘要）；不遵循
// 约定或推导失败时保持旧 flat3 三代同目录布局，既有用户配置行为不变。
//
// 轮换顺序固定「先提交当前代→再写新当前代」：中途失败重试时当前代仍是旧
// 内容，上次代正文已等于当前代，按幂等分支补写摘要，不会把同一份旧内容
// 重复轮换或丢失上一代。失败一律返回给 Refresh 记 Warn（镜像尽力而为）。
func writeMasterFile(path, content string, sources []Source, logger *slog.Logger) error {
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
		if previous, ok := derivePreviousGenerationPath(path); ok {
			if err := commitCurrentGeneration(previous, string(current)); err != nil {
				return err
			}
		} else {
			if logger == nil {
				logger = slog.Default()
			}
			logger.Debug("作者总表：镜像路径不遵循当前/上次目录约定，按三代同目录轮换", "path", path)
			if err := rotateFlat3Generations(path, string(current)); err != nil {
				return err
			}
		}
	}
	// 内容未变化只刷新当前代（重新原子写正文+摘要），不轮换、不挤掉上次代。
	return writeCheckedMaster(path, content)
}

// derivePreviousGenerationPath 按目录命名约定推导上一代镜像路径；ok=false
// 表示不可推导，调用方回退旧 flat3 三代同目录语义。
//
// 命名约定：配置 path 的祖先目录恰好有一段名为「当前」或「current」，把该
// 段换成同文种兄弟段「上次」或「previous」，其余路径不变。例如
// …/资料维护/当前/作者资料/作者总表.txt →
// …/资料维护/上次/作者资料/作者总表.txt。
//
// 保守拒绝（ok=false，绝不猜写点）：相对路径；没有代目录段；代目录段多于
// 一段（歧义）；代目录直接位于盘符/共享根（维护根越界，不能把同根兄弟
// 写点界定在用户配置的维护目录内）；推导结果越出维护根。
func derivePreviousGenerationPath(path string) (string, bool) {
	clean := filepath.Clean(path)
	if !filepath.IsAbs(clean) {
		return "", false
	}
	volume := filepath.VolumeName(clean)
	// 首段是根之后的空段（Windows 卷根 / Unix 根均如此），末段是文件名。
	segments := strings.Split(strings.TrimPrefix(clean, volume), string(filepath.Separator))
	generation := -1
	for i := 0; i < len(segments)-1; i++ {
		name := segments[i]
		if name != currentGenerationDirName && name != currentGenerationDirNameASCII {
			continue
		}
		if generation >= 0 {
			return "", false // 多段都像代目录：歧义，不猜
		}
		generation = i
	}
	if generation < 0 {
		return "", false
	}
	generationDir := volume + strings.Join(segments[:generation+1], string(filepath.Separator))
	root := filepath.Dir(generationDir)
	// 盘符/共享根本身不是维护根：代目录直接建在根下时（C:当前…）推导会
	// 产生根级写点，按越界拒绝并回退旧布局。
	if root == filepath.Dir(root) {
		return "", false
	}
	replacement := previousGenerationDirName
	if segments[generation] == currentGenerationDirNameASCII {
		replacement = previousGenerationDirNameASCII
	}
	previous := filepath.Join(append([]string{root, replacement}, segments[generation+1:]...)...)
	// 推导出的上一代路径必须仍在同一维护根内（filepath.Clean/Rel 比较）。
	if !withinDir(root, previous) {
		return "", false
	}
	return previous, true
}

// withinDir 判断 candidate 规范化后是否仍在 dir 内；拒绝 ..、跨卷结果与
// 绝对路径回环（代目录推导的最终写点必须守住配置维护根）。
func withinDir(dir, candidate string) bool {
	rel, err := filepath.Rel(dir, candidate)
	if err != nil || rel == "." || filepath.IsAbs(rel) {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

// commitCurrentGeneration 把当前代正文与独立摘要提交为上次代。上次目标
// 已存在且正文不同时，只有正文自带可复算核对的 .sha256 才覆盖；摘要缺失、
// 不符或正文不可读一律拒绝并向上返回（Refresh 记 Warn）——绝不删除或覆盖
// 来历不明的文件，由用户核对后手工处理。正文已等于当前代说明上一次轮换
// 在写新当前代前中断，此时补写正文+摘要即可，不再要求旧摘要。
func commitCurrentGeneration(previousPath, current string) error {
	existing, readErr := os.ReadFile(previousPath)
	if readErr != nil && !errors.Is(readErr, fs.ErrNotExist) {
		return fmt.Errorf("读取上次代镜像: %w", readErr)
	}
	if readErr == nil && string(existing) != current {
		checksum, sumErr := os.ReadFile(previousPath + ".sha256")
		if sumErr != nil {
			return fmt.Errorf("上次代镜像缺少可核对摘要，拒绝覆盖: %w", sumErr)
		}
		if strings.TrimSpace(string(checksum)) != fmt.Sprintf("%x", sha256.Sum256(existing)) {
			return errors.New("上次代镜像摘要与正文不符（可能被外部编辑），拒绝覆盖")
		}
	}
	// 上次代目录由约定推导且可预期；首次轮换时创建，避免用户手工建目录。
	if err := os.MkdirAll(filepath.Dir(previousPath), 0o755); err != nil {
		return fmt.Errorf("创建上次代镜像目录: %w", err)
	}
	return writeCheckedMaster(previousPath, current)
}

// rotateFlat3Generations 是旧布局的三代同目录轮换（兼容路径）：最新、
// 「（上次）」、「（上上次）」同目录，重试不把同一代内容重复轮换。
func rotateFlat3Generations(path, current string) error {
	ext := filepath.Ext(path)
	previous := strings.TrimSuffix(path, ext) + flat3PreviousSuffix + ext
	older := strings.TrimSuffix(path, ext) + flat3OlderSuffix + ext
	last, err := os.ReadFile(previous)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	if err == nil && string(last) != current {
		if err := writeCheckedMaster(older, string(last)); err != nil {
			return err
		}
	}
	return writeCheckedMaster(previous, current)
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
