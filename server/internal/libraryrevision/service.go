// service.go：库内容修订号的读写服务（kv_settings 键 library_revision）。
package libraryrevision

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// SettingKey 是 revision 在 kv_settings 表里的键名（该表键名规则
// <功能点>_<设置名>，见 migrations/0003 文件头；本值与 openapi.yaml
// GET /api/v1/library/revision 的 description 双写，改动须两侧同步）。
const SettingKey = "library_revision"

// Service 是修订号的唯一读写通道：Get 读（带进程内缓存），Increment 原子
// 自增。并发模型：进程内单服务实例（httpapi.New 组装一份）+ mu 串行化全部
// 读写——Increment 的"自增+回读"两步在锁内完成，读到的永远是本进程视角的
// 最新值；跨进程写竞争不存在（单进程服务端），SQL 侧的单条 UPDATE 自增
// （CAST 数学在库内完成）再兜一层底，两层合起来不可能丢增量。
//
// 缓存语义：revision 是弱一致性信号，进程内缓存可能落后于库值一个写入
// 窗口（只可能"偏小"且毫秒级）——Get 用于端点响应，偏小的窗口由下一次
// Increment 回读自愈；客户端少跳过一轮的代价远小于多轮全量拉取。
type Service struct {
	q   *db.Queries
	log *slog.Logger
	now func() time.Time // updated_at 时间源（测试注入固定时钟）

	mu    sync.Mutex
	cache int64
	ready bool // cache 是否已装载（懒加载，首次 Get/Increment 回读时装）
}

// NewService 组装服务。q 必填；logger/now 为 nil 时取默认值。
func NewService(q *db.Queries, logger *slog.Logger, now func() time.Time) *Service {
	if logger == nil {
		logger = slog.Default()
	}
	if now == nil {
		now = time.Now
	}
	return &Service{q: q, log: logger, now: now}
}

// EnsureBaseline 引导基线：键缺失时以 COUNT(assets)+1 播种（单条
// INSERT..SELECT，原子，幂等——键已存在时是零行 no-op）。
//
// 为什么基线是 COUNT+1 而不是 0：0 会与客户端"本地无记录"的哨兵语义混淆
// （客户端无法区分"服务器是空的"与"我还没同步过"），恒 ≥1 保证任何真实
// 库的 revision 都与哨兵值错开；对已有存量资产的库，基线本身也恰好反映
// 了内容体量。调用时机：httpapi.New（服务启动，见 Deps.Revision 装配）。
func (s *Service) EnsureBaseline(ctx context.Context) error {
	return s.q.InitLibraryRevisionIfAbsent(ctx, db.InitLibraryRevisionIfAbsentParams{
		Key:       SettingKey,
		UpdatedAt: store.FormatTimestamp(s.now()),
		Key_2:     SettingKey,
	})
}

// Get 返回当前修订号（进程内缓存优先，miss 时回读库并装缓存）。
// 键缺失（未走过 EnsureBaseline 的裸服务实例）时先播种再读，自愈而非报错。
func (s *Service) Get(ctx context.Context) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ready {
		return s.cache, nil
	}
	v, err := s.readValue(ctx)
	if err != nil {
		return 0, err
	}
	s.cache, s.ready = v, true
	return v, nil
}

// Increment 原子自增并落库，返回自增后的新值。进程内由 mu 串行（事件
// 订阅协程与请求协程并发到达不丢增量），SQL 侧单条 UPDATE 兜底跨协程
// 竞态。键缺失时先播种再自增（正常装配顺序下 EnsureBaseline 已在启动时
// 跑过，此分支只为裸实例兜底）。
func (s *Service) Increment(ctx context.Context) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.EnsureBaseline(ctx); err != nil {
		return 0, fmt.Errorf("libraryrevision: 引导基线: %w", err)
	}
	if err := s.q.IncrementLibraryRevision(ctx, db.IncrementLibraryRevisionParams{
		UpdatedAt: store.FormatTimestamp(s.now()),
		Key:       SettingKey,
	}); err != nil {
		return 0, fmt.Errorf("libraryrevision: 自增修订号: %w", err)
	}
	v, err := s.readValue(ctx)
	if err != nil {
		return 0, err
	}
	s.cache, s.ready = v, true
	return v, nil
}

// readValue 从库读出并解析修订号（调用方必须已持 mu）。
// 键缺失 → 播种后重读；值非法（库被外部改坏）→ 报错上抛，绝不静默回 0
// （0 会把"客户端全量重拉"误判成"整轮跳过"，宁可让端点 500 暴露问题）。
func (s *Service) readValue(ctx context.Context) (int64, error) {
	raw, err := s.q.GetSetting(ctx, SettingKey)
	if errors.Is(err, sql.ErrNoRows) {
		if err := s.EnsureBaseline(ctx); err != nil {
			return 0, fmt.Errorf("libraryrevision: 引导基线: %w", err)
		}
		raw, err = s.q.GetSetting(ctx, SettingKey)
	}
	if err != nil {
		return 0, fmt.Errorf("libraryrevision: 读取修订号: %w", err)
	}
	v, err := strconv.ParseInt(strings.TrimSpace(raw), 10, 64)
	if err != nil {
		return 0, fmt.Errorf("libraryrevision: 修订号值非法 %q: %w", raw, err)
	}
	return v, nil
}
