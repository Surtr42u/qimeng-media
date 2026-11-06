package creatorprofile

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync"
	"time"

	"qimeng-media/server/internal/store/db"
)

const maintenancePeriod = time.Hour
const refreshInterval = 24 * time.Hour
const refreshConcurrency = 2

type Service struct {
	AfterSave    func(context.Context)
	conn         *sql.DB
	q            *db.Queries
	now          func() time.Time
	logger       *slog.Logger
	slots        chan struct{}
	active       sync.Map
	readMetadata func(context.Context, string) (Candidate, error)
}

func New(conn *sql.DB, q *db.Queries, now func() time.Time, logger *slog.Logger) *Service {
	return &Service{conn: conn, q: q, now: now, logger: logger, slots: make(chan struct{}, refreshConcurrency), readMetadata: readMetadata}
}
func exists(ctx context.Context, q *db.Queries, id string) error {
	rows, err := q.ListAuthors(ctx)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if row.ID == id {
			return nil
		}
	}
	return ErrNotFound
}
func (s *Service) Get(ctx context.Context, id string) (Profile, error) {
	if err := exists(ctx, s.q, id); err != nil {
		return Profile{}, err
	}
	return Load(ctx, s.q, id)
}
func (s *Service) rollback(tx *sql.Tx) {
	if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
		s.logger.Warn("回滚作者档案事务失败", "err", err)
	}
}
func (s *Service) Save(ctx context.Context, id string, w Write) (Profile, error) {
	if err := Validate(w); err != nil {
		return Profile{}, err
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Profile{}, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	if err = exists(ctx, q, id); err != nil {
		return Profile{}, err
	}
	p, err := Load(ctx, q, id)
	if err != nil {
		return p, err
	}
	if p.Revision != w.Revision {
		return p, ErrConflict
	}
	if p.AvatarURL != w.AvatarURL {
		p.AvatarData = nil
	}
	// 新字段省略时兼容现装旧客户端，不丢失已同步的X原文。
	if w.XProfile == nil {
		w.XProfile = p.XProfile
	} else if w.XProfile.BiographyZh == nil && p.XProfile != nil &&
		XHandle(w.XProfile.URL) == XHandle(p.XProfile.URL) && w.XProfile.Biography == p.XProfile.Biography {
		w.XProfile.BiographyZh = p.XProfile.BiographyZh
	}
	p.Write = w
	if !w.TrackUpdates {
		p.Updates = []Update{}
	}
	p.Revision++
	p.Candidate = Candidate{EvidenceURLs: []string{}}
	if p.EvidenceURLs == nil {
		p.EvidenceURLs = []string{}
	}
	if err = Persist(ctx, q, p, s.now()); err != nil {
		return p, err
	}
	if err := tx.Commit(); err != nil {
		return p, err
	}
	if s.AfterSave != nil {
		s.AfterSave(ctx)
	}
	return p, nil
}
func (s *Service) Refresh(ctx context.Context, id string) (Profile, error) {
	return s.refresh(ctx, id, true)
}
func (s *Service) refresh(ctx context.Context, id string, refreshMetadata bool) (Profile, error) {
	if _, loaded := s.active.LoadOrStore(id, true); loaded {
		return Profile{}, ErrConflict
	}
	defer s.active.Delete(id)
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	default:
		return Profile{}, ErrConflict
	}
	p, err := s.Get(ctx, id)
	if err != nil {
		return p, err
	}
	original := p.Revision
	checkCtx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()
	p.LastCheckedAt = s.now().UTC().Format(time.RFC3339)
	p.LastError = ""
	if refreshMetadata && p.HomepageURL == "" {
		p.LastError = "请先填写已确认的作者主页"
	} else if refreshMetadata {
		candidate, metadataErr := s.readMetadata(checkCtx, p.HomepageURL)
		if metadataErr != nil {
			p.LastError = "公开主页暂不可读取，可能需要登录、权限或平台API"
		} else {
			p.Candidate = candidate
		}
	}
	if p.TrackUpdates {
		updates, feedErr := readFeed(checkCtx, p.FeedURL)
		if feedErr != nil {
			p.LastError = "RSS/Atom来源暂不可读取或格式不支持"
		} else {
			p.Updates = updates
		}
	}
	if ctx.Err() != nil {
		return p, ctx.Err()
	}
	if p.LastError == "" {
		p.LastSuccessAt = p.LastCheckedAt
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return p, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	if err = exists(ctx, q, id); err != nil {
		return p, err
	}
	current, err := Load(ctx, q, id)
	if err != nil {
		return p, err
	}
	if current.Revision != original {
		return current, ErrConflict
	}
	p.Revision++
	if err = Persist(ctx, q, p, s.now()); err != nil {
		return p, err
	}
	return p, tx.Commit()
}

// Run 由main以服务生命周期调用；串行遍历、最多每作者每天一次，取消后收齐。
func (s *Service) Run(ctx context.Context) {
	ticker := time.NewTicker(maintenancePeriod)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.maintain(ctx)
		}
	}
}
func (s *Service) maintain(ctx context.Context) {
	rows, err := s.q.ListAuthors(ctx)
	if err != nil {
		s.logger.Warn("读取作者维护列表失败", "err", err)
		return
	}
	for _, row := range rows {
		if ctx.Err() != nil {
			return
		}
		p, loadErr := Load(ctx, s.q, row.ID)
		if loadErr != nil {
			s.logger.Warn("读取作者维护档案失败", "err", loadErr)
			continue
		}
		if (!p.AutoRefresh && !p.TrackUpdates) || p.Status != "verified" {
			continue
		}
		if p.LastCheckedAt != "" {
			last, parseErr := time.Parse(time.RFC3339, p.LastCheckedAt)
			if parseErr != nil {
				s.logger.Warn("作者维护检查时间无效", "err", parseErr)
				continue
			}
			age := s.now().Sub(last)
			if age >= 0 && age < refreshInterval {
				continue
			}
		}
		if _, refreshErr := s.refresh(ctx, row.ID, p.AutoRefresh); refreshErr != nil && !errors.Is(refreshErr, context.Canceled) && !errors.Is(refreshErr, ErrConflict) {
			s.logger.Warn("作者公开资料维护失败", "err", refreshErr)
		}
	}
}
