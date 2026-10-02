package authorattach

// attach.go：上传挂靠编排（ADR-0019：多步流放编排包，禁堆 httpapi）。
// Apply 在调用方事务内执行，保证「资产入库 + 片段挂靠 + 作者行 + 即时关联」
// 原子（REQ §3.3 第 6 条：任一环节失败不产生半更新状态——由事务回滚兜底）。

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// Service 是挂靠编排入口；Logger 为 nil 时用 slog.Default()。
type Service struct {
	Logger *slog.Logger
}

func (s *Service) logger() *slog.Logger {
	if s.Logger != nil {
		return s.Logger
	}
	return slog.Default()
}

// AttachRequest 一次挂靠请求。契约（调用方 httpapi 已校验，Apply 防御性
// 复核）：AuthorID/AuthorName 恰填一项；FinalName 是冲突重命名后的最终落盘
// 文件名（含扩展名）——禁止传用户原始名（REQ §3.3 第 3 条：挂靠记录的是
// 重命名后文件名）；Sources 已 trim 去重。
type AttachRequest struct {
	AssetID    string   // 刚入库的资产 ID
	FinalName  string   // 最终落盘文件名（含扩展名）
	AuthorID   string   // 已有作者（与 AuthorName 二选一）
	AuthorName string   // 新建作者显示名（调用方已过 ValidNewAuthorName）
	Sources    []string // 来源词（已 trim 去重）
}

// AttachResult 挂靠结果回执。
type AttachResult struct {
	AuthorID, DisplayName, Fragment string
	CreatedAuthor                   bool // 是否新建了作者块（新作者或不在任何片段的既有作者）
}

// ErrAuthorNotFound：AuthorID 传入但 authors 表查无此作者（handler 映射
// 404；不裹上下文，供 errors.Is 判别）。
var ErrAuthorNotFound = errors.New("authorattach: author not found")

// ErrInvalidAuthorName：AuthorName 经 CanonicalAuthorNames 规范化后无有效
// 别名（handler 前置 ValidNewAuthorName 已拦，此处防御兜底；不裹上下文，
// 供 errors.Is 判别）。
var ErrInvalidAuthorName = errors.New("authorattach: invalid author name")

// Apply 在调用方事务内完成挂靠主流程：LoadSources → 定位目标片段（AuthorID
// 命中已有作者的所在片段取数组序第一个；无片段/作者不在任何片段→MostRecent；
// 无任何片段→自动创建 AutoFragmentFilename 片段）→ 纯函数变更 content
// （AppendWorks/AppendSources/AppendAuthorBlock，新建块编号行一律用规范
// 别名列表，保证解析回读 id == 身份 id）→ PersistSources（目标片段
// ImportedAt 不变，自动创建的置 now）→ 维护上传条目元数据（仅记本次实际
// 新追加的行，Names 记录规范别名）→ UpsertAuthor（type=regular，幂等不动
// followed）→ AddAssetAuthor 立即建立关联（作品行=FinalName 会被 MatchWorks
// 规则 1 精确命中，与统一重建的结果等价，REQ §3.3 第 1/2 条）。
func (s *Service) Apply(ctx context.Context, qtx *db.Queries, now time.Time, req AttachRequest) (AttachResult, error) {
	if strings.TrimSpace(req.FinalName) == "" {
		return AttachResult{}, errors.New("authorattach: FinalName 不能为空（须传冲突重命名后的最终落盘文件名）")
	}
	// 新建作者的身份必须取 CanonicalAuthorNames 的 names[0]：原始输入直接
	// GenerateAuthorID 会与块解析回读的 id 分裂（"Night  Cry" 表内
	// night__cry vs 块 night；REQ §3.1① 空格/符号差异不得裂分身）。
	// canonical 为空 = 非法名（handler 前置已拦，防御兜底）。
	var names []string
	if strings.TrimSpace(req.AuthorName) != "" {
		names = authoring.CanonicalAuthorNames(req.AuthorName)
		if len(names) == 0 {
			return AttachResult{}, ErrInvalidAuthorName
		}
	}
	id, displayName, err := s.resolveAuthor(ctx, qtx, req, names)
	if err != nil {
		return AttachResult{}, err
	}

	sources, err := LoadSources(ctx, qtx)
	if err != nil {
		return AttachResult{}, err
	}
	idx, blockNames := targetFragmentIndex(sources, id)
	var fragment, content string
	autoCreated := idx < 0
	if autoCreated {
		// 服务端从未导入过任何片段：自动创建承载片段，此后它即「最近导入
		// 的片段」（与手工导入片段同构同待遇，REQ §3.3 第 2 条）。
		fragment = authoring.AutoFragmentFilename
		s.logger().Debug("authorattach: 无任何片段，自动创建承载片段", "fragment", fragment)
	} else {
		fragment, content = sources[idx].Filename, sources[idx].Content
	}

	// 纯函数文本手术（块缺失走新建块路径）。AuthorID 路径无别名来源（names
	// 为空）时从 displayName 重建别名列表——整串当单别名写编号行会让解析
	// 回读的 GenerateAuthorID(首别名) 与真实 id 分裂（幻影作者，同
	// edit.go displayNameAliases 口径）。
	if len(names) == 0 {
		names = displayNameAliases(displayName)
	}
	entryNames, entryDisplay := entryIdentity(names, blockNames, displayName)
	works := []string{req.FinalName}
	newContent, blockFound := authoring.AppendWorks(content, id, works)
	if !blockFound {
		newContent = authoring.AppendAuthorBlock(content, entryNames, req.Sources, works)
	} else if withSrc, ok := authoring.AppendSources(newContent, id, req.Sources); ok {
		newContent = withSrc
	}

	// 本次实际新追加的行 = 请求的行 − 旧内容已有行（块缺失时整条带
	// DisplayName/Names）——正好是 MissingUploadEntries 的语义，直接复用。
	added := authoring.MissingUploadEntries(content, []authoring.UploadEntry{{
		AuthorID: id, DisplayName: entryDisplay, Names: entryNames, Works: works, Sources: req.Sources,
	}})

	if autoCreated {
		sources = append(sources, Source{
			Filename: fragment, Content: newContent, ImportedAt: store.FormatTimestamp(now),
		})
	} else {
		sources[idx].Content = newContent // ImportedAt 不变：挂靠不改「最近导入」序位
	}
	if err := PersistSources(ctx, qtx, now, sources); err != nil {
		return AttachResult{}, err
	}
	if len(added) > 0 {
		if err := recordAddedLines(ctx, qtx, now, fragment, added[0]); err != nil {
			return AttachResult{}, err
		}
	}

	if err := qtx.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: id, DisplayName: displayName, Type: authoring.AuthorTypeRegular, CreatedAt: store.FormatTimestamp(now),
		// 溯源章（ADR-0032）：客户端挂靠通道（上传流程/编辑页新增作者共用
		// 本编排，web/app 同端点统一盖 client）。
		Origin: store.OriginClient,
	}); err != nil {
		return AttachResult{}, fmt.Errorf("authorattach: upsert 作者 %s: %w", id, err)
	}
	if err := qtx.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: req.AssetID, AuthorID: id,
		CreatedAt: store.NullTimestamp(store.FormatTimestamp(now)),
		Origin:    store.OriginClient,
	}); err != nil {
		return AttachResult{}, fmt.Errorf("authorattach: 建立资产-作者关联: %w", err)
	}
	return AttachResult{
		AuthorID: id, DisplayName: displayName, Fragment: fragment, CreatedAuthor: !blockFound,
	}, nil
}

// resolveAuthor 解析作者身份与显示名。AuthorID 路径：查 authors 行，查无 →
// ErrAuthorNotFound；AuthorName 路径：names 须是调用方算好的
// CanonicalAuthorNames 产物，id 取 GenerateAuthorID(names[0])（原始输入直接
// 生成会与块回读 id 分裂），若表中已有同 id 行则沿用其显示名（大小写/符号
// 变体归并到同一人，REQ §3.1①「不裂成多个作者」），否则以 names 的
// " / " 连接为新作者显示名。ListAuthors 全量在内存找 id——不为单查开新
// sqlc 查询（设计拍板）。
func (s *Service) resolveAuthor(ctx context.Context, qtx *db.Queries, req AttachRequest, names []string) (id, displayName string, err error) {
	idSet := strings.TrimSpace(req.AuthorID) != ""
	nameSet := strings.TrimSpace(req.AuthorName) != ""
	if idSet == nameSet { // 同真同假都违反「二选一」契约
		return "", "", errors.New("authorattach: AttachRequest 的 AuthorID/AuthorName 必须恰填一项")
	}
	if nameSet {
		id = authoring.GenerateAuthorID(names[0])
	} else {
		id = strings.TrimSpace(req.AuthorID)
	}
	rows, err := qtx.ListAuthors(ctx)
	if err != nil {
		return "", "", fmt.Errorf("authorattach: 查询作者表: %w", err)
	}
	for _, row := range rows {
		if row.ID == id {
			return id, row.DisplayName, nil
		}
	}
	if idSet {
		return "", "", ErrAuthorNotFound
	}
	return id, strings.Join(names, " / "), nil
}

// entryIdentity 决定条目元数据（及块缺失时新块编号行）的别名与显示名：
// 既有块命中 → 该块解析出的 AuthorNames（MergeUploadEntries 重建块时往返
// 恒等，DisplayName 与之同步为 " / " 连接）；无块的新建作者 → canonical
// names；其余（AuthorID 路径且不在任何片段的既有作者）→ 作者行显示名作
// 单别名兜底。
func entryIdentity(names, blockNames []string, displayName string) ([]string, string) {
	if len(blockNames) > 0 {
		return blockNames, strings.Join(blockNames, " / ")
	}
	if len(names) > 0 {
		return names, strings.Join(names, " / ")
	}
	return []string{displayName}, displayName
}

// findAuthorBlock 在片段数组中定位作者块的所在片段：作者块命中的片段取
// 数组序第一个；一并返回该块解析结果（AuthorNames 是条目元数据与新建块
// 编号行的往返安全来源，Sources 供编辑端点回显——定位块时顺手取解析
// 结果，不二次解析）。ok=false = 作者不在任何片段。
func findAuthorBlock(sources []Source, authorID string) (int, authoring.AuthorBlock, bool) {
	for i := range sources {
		for _, b := range authoring.ParseAuthorBlocks(sources[i].Content) {
			if len(b.AuthorNames) > 0 && authoring.GenerateAuthorID(b.AuthorNames[0]) == authorID {
				return i, b, true
			}
		}
	}
	return -1, authoring.AuthorBlock{}, false
}

// mostRecentIndex 返回最近导入片段的下标：importedAt 字典序最大（统一时间
// 戳格式下字典序 == 时间序），平局取数组靠后（后写入者覆盖语义）；无片段 -1。
func mostRecentIndex(sources []Source) int {
	best := -1
	for i := range sources {
		// >=：平局取数组靠后，与 MostRecent 语义一致。
		if best < 0 || sources[i].ImportedAt >= sources[best].ImportedAt {
			best = i
		}
	}
	return best
}

// targetFragmentIndex 定位挂靠目标片段：作者块命中的片段取数组序第一个；
// 作者不在任何片段 → 最近导入的片段；返回 -1 = 库中无任何片段。命中时一并
// 返回该块解析出的 AuthorNames（条目元数据 Names 的往返安全来源）。
func targetFragmentIndex(sources []Source, authorID string) (int, []string) {
	idx, block, ok := findAuthorBlock(sources, authorID)
	if ok {
		return idx, block.AuthorNames
	}
	return mostRecentIndex(sources), nil
}

// recordAddedLines 把本次新追加的行并入 map[fragment] 的对应作者条目（条目
// 不存在则新建含 DisplayName/Names）；条目内的行保序去重，重复上传不使
// 元数据膨胀（REQ §3.3 第 4 条）。Names 是规范别名列表（重建缺失块的往返
// 安全来源）；合并进既有条目时旧数据缺 Names 则补写。
func recordAddedLines(ctx context.Context, qtx *db.Queries, now time.Time, fragment string, added authoring.UploadEntry) error {
	entries, err := LoadUploadEntries(ctx, qtx)
	if err != nil {
		return err
	}
	list := entries[fragment]
	merged := false
	for i := range list {
		if list[i].AuthorID == added.AuthorID {
			list[i].Works = mergeLines(list[i].Works, added.Works)
			list[i].Sources = mergeLines(list[i].Sources, added.Sources)
			if len(list[i].Names) == 0 && len(added.Names) > 0 {
				// Names 字段引入前落库的旧条目没有规范别名：借本次合并补写，
				// 老条目逐步升级到往返安全形态。
				list[i].Names = append([]string(nil), added.Names...)
			}
			merged = true
			break
		}
	}
	if !merged {
		list = append(list, authoring.UploadEntry{
			AuthorID:    added.AuthorID,
			DisplayName: added.DisplayName,
			Names:       append([]string(nil), added.Names...),
			Works:       append([]string(nil), added.Works...),
			Sources:     append([]string(nil), added.Sources...),
		})
	}
	entries[fragment] = list
	return SaveUploadEntries(ctx, qtx, now, entries)
}

// mergeLines 把 add 并入 base（保序去重，首遇保留）。
func mergeLines(base, add []string) []string {
	seen := make(map[string]bool, len(base)+len(add))
	for _, l := range base {
		seen[l] = true
	}
	out := base
	for _, l := range add {
		if seen[l] {
			continue
		}
		seen[l] = true
		out = append(out, l)
	}
	return out
}
