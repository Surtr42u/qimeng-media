// Package authorimport 编排作者 TXT 导入、保护与统一重建，不依赖 HTTP 协议。
package authorimport

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// Result 的可选合并数只在块格式导入时有意义；协议映射由 HTTP 适配。
type Result struct {
	AuthorsImported     int
	FilesMatched        int
	MergedUploadEntries *int
	Rebuilt             bool
}

type Service struct {
	conn   *sql.DB
	q      *db.Queries
	now    func() time.Time
	logger *slog.Logger
}

func New(conn *sql.DB, q *db.Queries, now func() time.Time, logger *slog.Logger) *Service {
	if now == nil {
		now = time.Now
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &Service{conn: conn, q: q, now: now, logger: logger}
}

func (s *Service) rollback(tx *sql.Tx) {
	if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
		s.logger.Warn("回滚 TXT 事务失败", "err", err)
	}
}

// Import 是导入主流程：块格式走统一重建事务；格式 C 只建作者。
// origin（ADR-0032 溯源章）：本次导入触发的作者行/关联写入通道——手动
// HTTP 通道传 store.OriginTXT、备份 TXT 片段段传 OriginImport、本机同步
// 传 OriginLocalSync（调用方各自盖章，词表单源 store/provenance.go）。
// 计数口径：authorsImported = 本次 TXT 解析出的作者块数（格式 C 为行数）；
// filesMatched = 本次 TXT 的（作者, 去重作品）对匹配到的库内文件总数；
// mergedUploadEntries = keep 路径自动并回的上传条目行数（其余 0）。
func (s *Service) Import(ctx context.Context, filename *string, content, resolution, origin string) (Result, error) {
	blocks := authoring.ParseAuthorBlocks(content)
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Result{}, err
	}
	defer s.rollback(tx)
	qtx := s.q.WithTx(tx)

	if len(blocks) == 0 {
		// 格式 C：纯作者名列表——只创建作者，不关联文件、不触发重建、
		// 不存片段（重导入保护因此天然不触发：无片段可覆盖）。
		names := authoring.ParsePlainAuthorNames(content)
		now := store.FormatTimestamp(s.now())
		for _, name := range names {
			if err := qtx.UpsertAuthor(ctx, db.UpsertAuthorParams{
				ID:          authoring.GenerateAuthorID(name),
				DisplayName: name,
				Type:        authoring.AuthorTypeRegular,
				CreatedAt:   now,
				Origin:      origin,
			}); err != nil {
				return Result{}, err
			}
		}
		if err := tx.Commit(); err != nil {
			return Result{}, err
		}
		return Result{AuthorsImported: len(names)}, nil
	}

	filesMatched, merged, err := s.rebuildFromAllSources(ctx, qtx, blocks, filename, content, resolution, origin)
	if err != nil {
		return Result{}, err
	}
	if err := tx.Commit(); err != nil {
		return Result{}, err
	}
	imported := len(blocks)
	return Result{
		AuthorsImported: imported, FilesMatched: filesMatched, MergedUploadEntries: &merged,
	}, nil
}

// Remove 在单事务内移除片段/登记条目并重建剩余关联。
func (s *Service) Remove(ctx context.Context, filename string) error {
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer s.rollback(tx)
	if err := s.removeTxtSource(ctx, s.q.WithTx(tx), filename); err != nil {
		return err
	}
	return tx.Commit()
}

// Rebuild 仅重放已存片段，不重写片段或刷新镜像；零片段不写库。
func (s *Service) Rebuild(ctx context.Context) (Result, error) {
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Result{}, err
	}
	defer s.rollback(tx)
	qtx := s.q.WithTx(tx)
	sources, err := authorattach.LoadSources(ctx, qtx)
	if err != nil {
		return Result{}, err
	}
	if len(sources) == 0 {
		return Result{}, nil
	}
	merged := mergeTxtSources(sources)
	countWorks := make(map[string][]string, len(merged))
	for id, ra := range merged {
		countWorks[id] = ra.works
	}
	filesMatched, err := s.rebuildAll(ctx, qtx, merged, merged, countWorks, store.OriginTXT)
	if err != nil {
		return Result{}, err
	}
	if err := tx.Commit(); err != nil {
		return Result{}, err
	}
	return Result{AuthorsImported: len(merged), FilesMatched: filesMatched, Rebuilt: true}, nil
}
