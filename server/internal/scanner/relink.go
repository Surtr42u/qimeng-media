package scanner

// 库根自动重挂（auto-relink，ADR-0025）。
//
// 库身份在 DB 里以 root_path 绝对路径登记，而用户在磁盘上改名/移动该目录
// 是普通文件管理操作——路径身份因此天然不可靠。本文件在"扫描器发现库根
// 不存在"这个触发点上，用库内已知资产做指纹比对，在旧根的父目录下找回
// 新位置：命中唯一候选则把 root_path（连带 display name，用户拍板显示名
// 跟随目录名）改挂过去，本轮扫描继续；否则保持"根丢失→本次失败"的原有
// 行为，绝不猜测。
//
// 结构：匹配判定是纯函数（pickRelinkCandidate/candidateMatchesSamples，
// 领域规则，铁律 3：不做 IO、行为由单测锁定）；IO（取样/枚举/探测/写库）
// 全部在 tryRelinkRoot 一侧，探测结果以 relinkProbe 值传入纯函数。

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store/db"
)

// relinkSampleCount 重挂比对样本数上限（任务规格 3-5 条，取上限 5）：
// 样本越多误挂概率越低，取库内最小的几个文件——stat 最快、内容通常最
// 独特；行数上限与 queries/scanner.sql 的 ListLibraryRelinkSamples 联动，
// 两侧改动须同步。
const relinkSampleCount = 5

// relinkSample 重挂比对样本：库内一条已知资产的相对路径与字节数。
type relinkSample struct {
	RelPath   string
	SizeBytes int64
}

// relinkProbe 候选目录下某条样本的探测结果。零值 = 不存在/不可读；
// IO 侧产出，纯函数只读消费。
type relinkProbe struct {
	Found     bool
	SizeBytes int64
}

// relinkCandidate 一个候选目录（旧根父目录下的一层子目录）及其全部样本
// 探测结果，key = 样本 relPath。
type relinkCandidate struct {
	Name   string
	Probes map[string]relinkProbe
}

// candidateMatchesSamples 单候选命中判定（纯函数）：每个样本 relPath 在
// 候选目录下存在且字节数完全一致才算命中；全部样本命中才合格。
// 无样本（从未扫描成功的库/空库）无法证同，一律不命中——宁可不重挂，
// 不把库挂到任意同名结构的目录上。
func candidateMatchesSamples(samples []relinkSample, probes map[string]relinkProbe) bool {
	if len(samples) == 0 {
		return false
	}
	for _, sm := range samples {
		p, ok := probes[sm.RelPath]
		if !ok || !p.Found || p.SizeBytes != sm.SizeBytes {
			return false
		}
	}
	return true
}

// pickRelinkCandidate 从全部候选中挑合格者（纯函数）。
// 返回 (唯一命中者目录名, 合格候选数)。合格数 != 1 时调用方必须放弃——
// 0 个说明没有目录像这个库，≥2 个说明指纹不足以区分，两者猜测都是事故。
// best 仅在合格数==1 时有意义（多命中时其取值无意义，调用方不得使用）。
func pickRelinkCandidate(samples []relinkSample, candidates []relinkCandidate) (best string, qualified int) {
	for _, c := range candidates {
		if candidateMatchesSamples(samples, c.Probes) {
			qualified++
			best = c.Name
		}
	}
	return best, qualified
}

// ensureLibraryRoot 库根可达性保障（Scan 与 Watch 两条入口共用）。
// 根存在：无动作返回 nil。根不存在（fs.ErrNotExist）：尝试自动重挂，
// 成功则原地改写 *lib（root_path/display name 已持久化）。重挂失败或
// stat 出现其他错误：返回错误，调用方保持"根丢失→本次失败"的原有行为。
func (s *Scanner) ensureLibraryRoot(ctx context.Context, lib *db.Library) error {
	_, err := os.Stat(lib.RootPath)
	if err == nil {
		return nil
	}
	if !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("库根不可达: %w", err)
	}
	if !s.tryRelinkRoot(ctx, lib) {
		return fmt.Errorf("库根不可达且自动重挂未命中: %w", err)
	}
	return nil
}

// tryRelinkRoot 自动重挂主流程（IO 侧）：取样 → 枚举旧父目录一层子目录
// → 逐候选探测样本文件 → 纯函数判唯一命中 → 更新库行 + 广播。
// 返回 false 表示放弃（原因已记日志），调用方保持既有失败行为。
//
// 并发语义：Scan 侧调用点在 beginScan CAS 闸之后，同一库不会有并发重挂；
// Watch 启动期调用点不持闸，理论上可与轮询 Scan 同时发现根丢失而双写——
// 两侧对同一文件系统独立探测、写同一组值，单行 UPDATE 幂等，后果良性
// （Watch 尚未接线，接线时按 ADR-0025 已知限制段处理）。
func (s *Scanner) tryRelinkRoot(ctx context.Context, lib *db.Library) bool {
	oldRoot := lib.RootPath
	parent := filepath.Dir(oldRoot)

	samples, err := s.loadRelinkSamples(ctx, lib.ID)
	if err != nil {
		s.logger.Warn("scanner: 库根自动重挂放弃：载入资产样本失败",
			"libraryId", lib.ID, "err", err)
		return false
	}
	entries, err := os.ReadDir(parent)
	if err != nil {
		// 旧父目录也没了（整棵树被移走/盘未挂载）：无处可找，直接放弃。
		s.logger.Warn("scanner: 库根自动重挂放弃：旧根父目录不可枚举",
			"libraryId", lib.ID, "parent", parent, "err", err)
		return false
	}

	candidates := s.probeRelinkCandidates(parent, entries, samples)
	name, qualified := pickRelinkCandidate(samples, candidates)
	if qualified != 1 {
		s.logger.Warn("scanner: 库根自动重挂失败：合格候选不唯一，保持原失败行为",
			"libraryId", lib.ID, "oldRoot", oldRoot, "parent", parent,
			"candidates", len(candidates), "qualified", qualified,
			"samples", len(samples))
		return false
	}

	newRoot := filepath.Join(parent, name)
	if samePath(newRoot, oldRoot) {
		// 理论不可达（旧根不存在才会走到这里）；防御：避免把根"重挂"回自身。
		s.logger.Warn("scanner: 库根自动重挂放弃：候选即旧根",
			"libraryId", lib.ID, "root", oldRoot)
		return false
	}

	// UpdateLibrary（name+root_path 整体更新，目前唯一调用方就是本重挂流程）：
	// 显示名跟随新目录 basename 是用户拍板的行为（ADR-0025），非副作用。
	updated, err := s.q.UpdateLibrary(ctx, db.UpdateLibraryParams{
		Name:     name,
		RootPath: newRoot,
		ID:       lib.ID,
	})
	if err != nil {
		s.logger.Error("scanner: 库根自动重挂写库失败",
			"libraryId", lib.ID, "newRoot", newRoot, "err", err)
		return false
	}
	*lib = updated
	s.logger.Info("库根自动重挂",
		"libraryId", lib.ID, "oldRoot", oldRoot, "newRoot", newRoot,
		"displayName", name, "samples", len(samples))
	// 广播 library.changed（零计数载荷，协议允许的广播型触发帧）：SseBridge
	// 收到即失效 LIBRARIES_QUERY_KEY 等根键，三端下次拉取就看到新根与新显示名。
	s.publish(events.TopicLibraryChanged, ScanResult{LibraryID: lib.ID})
	return true
}

// loadRelinkSamples 取比对样本：库内最小的 relinkSampleCount 条资产
// （rel_path + size_bytes），恰好覆盖"优先小文件"的任务规格。
func (s *Scanner) loadRelinkSamples(ctx context.Context, libraryID string) ([]relinkSample, error) {
	rows, err := s.q.ListLibraryRelinkSamples(ctx, db.ListLibraryRelinkSamplesParams{
		LibraryID: libraryID,
		Limit:     relinkSampleCount,
	})
	if err != nil {
		return nil, err
	}
	samples := make([]relinkSample, 0, len(rows))
	for _, r := range rows {
		samples = append(samples, relinkSample{RelPath: r.RelPath, SizeBytes: r.SizeBytes})
	}
	return samples, nil
}

// probeRelinkCandidates 枚举并探测候选目录：旧父目录下一层子目录（跳过
// 隐藏/系统目录与数据目录），每个候选对全部样本做一次 stat。
func (s *Scanner) probeRelinkCandidates(parent string, entries []os.DirEntry, samples []relinkSample) []relinkCandidate {
	candidates := make([]relinkCandidate, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() || SkipDir(e.Name()) || IsHiddenName(e.Name()) {
			continue
		}
		dir := filepath.Join(parent, e.Name())
		if s.dataDir != "" && samePath(dir, s.dataDir) {
			continue // 自噬防御（同 Scanner.dataDir）：绝不把库根重挂进数据目录
		}
		probes := make(map[string]relinkProbe, len(samples))
		for _, sm := range samples {
			info, err := os.Stat(filepath.Join(dir, filepath.FromSlash(sm.RelPath)))
			if err != nil {
				probes[sm.RelPath] = relinkProbe{} // 不存在/不可读：该样本不命中
				continue
			}
			probes[sm.RelPath] = relinkProbe{Found: true, SizeBytes: info.Size()}
		}
		candidates = append(candidates, relinkCandidate{Name: e.Name(), Probes: probes})
	}
	return candidates
}
