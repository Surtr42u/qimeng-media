// Package authoredit 编排作者编辑类业务用例：在同一事务内组合 authorattach
// 的低层原语完成读写，提交成功后按用例需要触发本地镜像刷新。
//
// 为什么需要这一层（ADR-0019：HTTP 不持有多步事务）：此前 httpapi 的
// author_edit.go / authors_manage.go 各自 BeginTx 并在 handler 里拼接
// 「解析作者 → 按名新建 → 片段手术 → 关联 swap」的多步流程，事务边界与
// 业务编排漂在适配层。本包把这些用例收敛为一次调用，handler 只做协议
// 校验、错误映射与响应装配。
//
// 边界约定：
//   - 本包只做事务编排与用例级错误归类；来源词表项数/字符、作者新名字、
//     写入 mode 等纯协议校验由 httpapi 层完成，本包不重复校验，也不持有
//     协议或 UI 状态。
//   - 低层 authorattach 的哨兵错误（作者不存在/COS/身份冲突等）原样透传，
//     不改错误身份；本包仅新增 ErrAssetNotFound / ErrUnsupportedLibraryKind
//     两个哨兵，供 HTTP 层映射 404/400。
//   - 推荐缓存失效与详情响应装配由调用方在用例成功返回后处理，本包不
//     触碰（不在业务包里持有 HTTP 层缓存状态）。
//   - 镜像是尽力而为的单向投影（REQ §3.4）：仅在事务 Commit 成功后刷新
//     一次，失败只由 MirrorWriter 自己告警，不影响用例结果；任何失败路径
//     都不刷新镜像。
//   - 事务不导出；低层原语复用调用方事务 qtx，不在低层另开事务。
package authoredit

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
)

// ErrAssetNotFound 目标资产不存在，HTTP 层映射为 404「资产不存在」。
// 不裹上下文，供 errors.Is 判别。
var ErrAssetNotFound = errors.New("authoredit: 资产不存在")

// ErrUnsupportedLibraryKind 该库类型不支持作者挂靠，HTTP 层映射为 400。
// 能力判定单一来源是 scanner.SupportsAuthorAttach，本哨兵只承载判定结果。
var ErrUnsupportedLibraryKind = errors.New("authoredit: 该库类型不支持作者挂靠")

// Options 由 main 显式注入；Conn/Queries 必填，其他依赖按既有模式兜底。
type Options struct {
	Conn    *sql.DB
	Queries *db.Queries
	Attach  *authorattach.Service
	Mirror  *authorattach.MirrorWriter
	Now     func() time.Time
	Logger  *slog.Logger
}

// Service 作者编辑用例服务，只持有依赖引用，自身无协议/UI 状态。
type Service struct {
	conn    *sql.DB
	queries *db.Queries
	attach  *authorattach.Service
	mirror  *authorattach.MirrorWriter
	now     func() time.Time
	logger  *slog.Logger
}

// New 装配 Service。nil 依赖兜底：Now→time.Now、Logger→slog.Default、
// Attach/Mirror→零值结构体（低层两服务自身无外部依赖，缺省即生产实现）。
// 生产 main 仍应显式注入全部依赖并与 HTTP 共享同一实例，兜底仅兼容
// httpapi.New 缺省装配的既有隔离测试惯例（ADR-0019：DI 在 main）。
func New(opts Options) *Service {
	s := &Service{
		conn:    opts.Conn,
		queries: opts.Queries,
		attach:  opts.Attach,
		mirror:  opts.Mirror,
		now:     opts.Now,
		logger:  opts.Logger,
	}
	if s.now == nil {
		s.now = time.Now
	}
	if s.logger == nil {
		s.logger = slog.Default()
	}
	if s.attach == nil {
		s.attach = &authorattach.Service{Logger: s.logger}
	}
	if s.mirror == nil {
		s.mirror = &authorattach.MirrorWriter{Logger: s.logger}
	}
	return s
}

// withTx 在单个事务内执行 fn：fn 失败或 Commit 失败均向上返回错误；
// defer 回滚，回滚失败（除 sql.ErrTxDone——事务已正常结束）记录告警，
// 不覆盖原业务错误（附录 F 口径）。仅本包用例复用，不导出事务对象，
// 也不做泛型封装。
func (s *Service) withTx(ctx context.Context, fn func(qtx *db.Queries) error) error {
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() {
		if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
			s.logger.WarnContext(ctx, "authoredit: 事务回滚失败", "error", err)
		}
	}()
	qtx := s.queries.WithTx(tx)
	if err := fn(qtx); err != nil {
		return err
	}
	return tx.Commit()
}

// EnsureSourceVocabulary 单事务内确保来源词表就绪（键缺失时一次性预填：
// 「查键 → 统计 → 写键」同事务，并发读取不会与窗口期内的用户 PUT 交叉
// 覆盖），并原样返回低层结果（nil/空切片保持低层原返回，不做归一化）。
// 词表是 kv 数据不落 TXT，本用例不刷新镜像。
func (s *Service) EnsureSourceVocabulary(ctx context.Context) ([]string, error) {
	now := s.now()
	var sources []string
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		var err error
		sources, err = authorattach.EnsureSourceVocabulary(ctx, qtx, now)
		return err
	})
	if err != nil {
		return nil, err
	}
	return sources, nil
}

// ReplaceAuthorSources 整体替换/并入作者来源区：事务内先解析常规作者
// 显示名（404/COS 哨兵在这一步透传），再以该显示名写入来源列表；提交
// 成功后刷新本地镜像，任何失败均不刷新。来源合法性由低层哨兵错误约束，
// 本层不改错误身份。
func (s *Service) ReplaceAuthorSources(ctx context.Context, authorID string, sources []string, mode authorattach.SourcesWriteMode) ([]string, error) {
	now := s.now()
	var merged []string
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		display, err := s.attach.ResolveRegularAuthor(ctx, qtx, authorID)
		if err != nil {
			return err
		}
		merged, err = s.attach.ReplaceAuthorSources(ctx, qtx, now, authorID, display, sources, mode)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.mirror.Refresh(ctx, s.queries)
	return merged, nil
}

// CreateAuthor 创建（或复用已存在的）单个作者：仅写作者行，不写 TXT
// 片段、不关联文件，故不刷新镜像。名字先 trim（其余合法性沿用低层校验，
// 协议层已前置拦截非法名）；单名输入理论上仅返回一项，len != 1 视为
// 内部一致性异常，在提交前终止事务（不落半份数据）。
func (s *Service) CreateAuthor(ctx context.Context, displayName string) (authorattach.AuthorRef, error) {
	now := s.now()
	var created authorattach.AuthorRef
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		refs, err := s.attach.ResolveOrCreateAuthors(ctx, qtx, []string{strings.TrimSpace(displayName)}, now)
		if err != nil {
			return err
		}
		if len(refs) != 1 {
			return fmt.Errorf("authoredit: 创建作者期望返回 1 项，实际 %d 项", len(refs))
		}
		created = refs[0]
		return nil
	})
	if err != nil {
		return authorattach.AuthorRef{}, err
	}
	return created, nil
}

// RenameAuthor 重命名作者：事务内调用低层重命名（名字先 trim），提交
// 成功后刷新本地镜像；低层哨兵错误（作者不存在/COS/撞名等）原样透传。
// 推荐缓存失效由 HTTP 层在成功后处理。
func (s *Service) RenameAuthor(ctx context.Context, id, displayName string) (authorattach.AuthorRef, error) {
	now := s.now()
	var renamed authorattach.AuthorRef
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		var err error
		renamed, err = s.attach.RenameAuthor(ctx, qtx, now, id, strings.TrimSpace(displayName))
		return err
	})
	if err != nil {
		return authorattach.AuthorRef{}, err
	}
	s.mirror.Refresh(ctx, s.queries)
	return renamed, nil
}

// DeleteAuthor 删除作者（TXT 块 + 关联 + 作者行三清，媒体文件保留，
// DOMAIN_RULES §6）：事务内调用低层删除，提交成功后刷新本地镜像。
func (s *Service) DeleteAuthor(ctx context.Context, id string) error {
	now := s.now()
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		return s.attach.DeleteAuthor(ctx, qtx, now, id)
	})
	if err != nil {
		return err
	}
	s.mirror.Refresh(ctx, s.queries)
	return nil
}

// ReplaceAssetAuthors 整体替换资产作者：资产行、所在库类型与当前文件名
// 都在同一事务内读取（旧快照不跨过事务边界）；既有作者按 ids 顺序解析
// （ids 已在 httpapi 层保序、trim 并去重），新名字批量解析后合并，用
// 同事务内刚读取的 FileName 写回片段真相；提交成功后刷新本地镜像。
// 本用例不触碰推荐缓存或库事件/修订号，由调用方在成功后处理。
func (s *Service) ReplaceAssetAuthors(ctx context.Context, assetID string, ids, newNames []string) error {
	now := s.now()
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		asset, err := qtx.GetAssetWithLibrary(ctx, assetID)
		if err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrAssetNotFound
			}
			return err
		}
		lib, err := qtx.GetLibrary(ctx, asset.LibraryID)
		if err != nil {
			// 资产行存在而库元数据读取失败属于内部不一致，原样上抛归 500。
			return err
		}
		if !scanner.SupportsAuthorAttach(lib.Kind) {
			return ErrUnsupportedLibraryKind
		}
		resolved, err := s.attach.ResolveRegularAuthors(ctx, qtx, ids)
		if err != nil {
			return err
		}
		refs := make([]authorattach.AuthorRef, 0, len(ids)+len(newNames))
		for _, id := range ids {
			refs = append(refs, authorattach.AuthorRef{ID: id, DisplayName: resolved[id]})
		}
		if len(newNames) > 0 {
			created, err := s.attach.ResolveOrCreateAuthors(ctx, qtx, newNames, now)
			if err != nil {
				return err
			}
			refs = authorattach.MergeAuthorRefs(refs, created)
		}
		return s.attach.ReplaceAssetAuthors(ctx, qtx, now, assetID, asset.FileName, refs)
	})
	if err != nil {
		return err
	}
	s.mirror.Refresh(ctx, s.queries)
	return nil
}
