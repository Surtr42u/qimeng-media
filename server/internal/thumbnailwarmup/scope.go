package thumbnailwarmup

import (
	"context"
	"encoding/json"
	"slices"

	"qimeng-media/server/internal/store/db"
)

// 局部待办最多一页规模的键；超限向库/全量提升，不能丢任务或无限堆积。
// 合成范围测试验证上界及最终对账，阈值不改变覆盖口径或生成并发。
const maxPendingChanges = 256

type changeScope struct {
	all       bool
	libraries map[string]struct{}
	assets    map[string]string // asset -> library，用于超限时按库提升。
}

type workScope struct {
	library string
	ids     []string
}

type ScopedCandidates interface {
	Candidates
	ListThumbnailWarmupLibraryPage(context.Context, db.ListThumbnailWarmupLibraryPageParams) ([]db.ListThumbnailWarmupLibraryPageRow, error)
	ListThumbnailWarmupAssetsPage(context.Context, db.ListThumbnailWarmupAssetsPageParams) ([]db.ListThumbnailWarmupAssetsPageRow, error)
}

// NotifyLibrary/NotifyAsset 是内部类型化范围，外部 SSE 的载荷和协议保持原样。
func (s *Service) NotifyLibrary(library string) {
	if library == "" {
		s.Notify()
		return
	}
	s.notifyScope(changeScope{libraries: map[string]struct{}{library: {}}})
}

func (s *Service) NotifyAsset(library, asset string) {
	if library == "" {
		s.Notify()
		return
	}
	if asset == "" {
		s.NotifyLibrary(library)
		return
	}
	s.notifyScope(changeScope{assets: map[string]string{asset: library}})
}

func (s *Service) notifyScope(scope changeScope) {
	s.epoch.Add(1)
	s.pendingMu.Lock()
	defer s.pendingMu.Unlock()
	s.mergePending(scope)
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *Service) mergePending(scope changeScope) {
	if s.pending.all {
		return
	}
	if scope.all {
		s.pending = changeScope{all: true}
		return
	}
	if s.pending.libraries == nil {
		s.pending.libraries = make(map[string]struct{})
	}
	if s.pending.assets == nil {
		s.pending.assets = make(map[string]string)
	}
	for library := range scope.libraries {
		s.promoteLibrary(library)
	}
	for asset, library := range scope.assets {
		if _, whole := s.pending.libraries[library]; whole {
			continue
		}
		s.pending.assets[asset] = library
		if len(s.pending.assets)+len(s.pending.libraries) > maxPendingChanges {
			s.promoteLibrary(library)
		}
	}
	if len(s.pending.assets)+len(s.pending.libraries) > maxPendingChanges {
		s.pending = changeScope{all: true}
	}
}

func (s *Service) promoteLibrary(library string) {
	s.pending.libraries[library] = struct{}{}
	for asset, owner := range s.pending.assets {
		if owner == library {
			delete(s.pending.assets, asset)
		}
	}
}

// 范围和唤醒在同一锁内取走，不能把执行中新通知的唤醒误吞掉。
func (s *Service) takePending() changeScope {
	s.pendingMu.Lock()
	defer s.pendingMu.Unlock()
	s.drainWake()
	pending := s.pending
	s.pending = changeScope{}
	return pending
}

func (s *Service) retryScope(scope changeScope) {
	s.pendingMu.Lock()
	defer s.pendingMu.Unlock()
	s.mergePending(scope)
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (scope changeScope) work() []workScope {
	if scope.all {
		return []workScope{{}}
	}
	libraries := make([]string, 0, len(scope.libraries))
	for library := range scope.libraries {
		libraries = append(libraries, library)
	}
	slices.Sort(libraries)
	out := make([]workScope, 0, len(libraries)+1)
	for _, library := range libraries {
		out = append(out, workScope{library: library})
	}
	if len(scope.assets) > 0 {
		ids := make([]string, 0, len(scope.assets))
		for id := range scope.assets {
			ids = append(ids, id)
		}
		slices.Sort(ids)
		out = append(out, workScope{ids: ids})
	}
	return out
}

func (s *Service) rowsInScope(ctx context.Context, scope workScope, after string) ([]db.ListThumbnailWarmupPageRow, error) {
	if scope.library == "" && len(scope.ids) == 0 {
		return s.q.ListThumbnailWarmupPage(ctx, db.ListThumbnailWarmupPageParams{AfterID: after, PageSize: pageSize})
	}
	q := s.q.(ScopedCandidates) // reconcileScope 已在不支持局部端口时提升为全量。
	if scope.library != "" {
		rows, err := q.ListThumbnailWarmupLibraryPage(ctx, db.ListThumbnailWarmupLibraryPageParams{LibraryID: scope.library, AfterID: after, PageSize: pageSize})
		return projectWarmupRows(rows), err
	}
	payload, err := json.Marshal(scope.ids)
	if err != nil {
		return nil, err
	}
	rows, err := q.ListThumbnailWarmupAssetsPage(ctx, db.ListThumbnailWarmupAssetsPageParams{AssetIdsJson: string(payload), AfterID: after, PageSize: pageSize})
	return projectWarmupRows(rows), err
}

func projectWarmupRows[T interface {
	db.ListThumbnailWarmupLibraryPageRow | db.ListThumbnailWarmupAssetsPageRow
}](rows []T) []db.ListThumbnailWarmupPageRow {
	out := make([]db.ListThumbnailWarmupPageRow, len(rows))
	for i, row := range rows {
		out[i] = db.ListThumbnailWarmupPageRow(row)
	}
	return out
}
