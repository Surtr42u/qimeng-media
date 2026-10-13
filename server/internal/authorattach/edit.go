package authorattach

// edit.go：资产编辑的片段编排（ADR-0024：编辑端点的作者关联/来源变更同样
// 写进片段本体原地更新——TXT 片段是唯一真相，只改 asset_authors 表会被下次
// 统一重建冲掉）。落点规则复用 attach.go 的 findAuthorBlock/
// targetFragmentIndex（作者块命中→数组序第一个片段；未命中→最近导入片段；
// 无片段→自动创建承载片段），与上传挂靠共享同一份口径，禁止复制粘贴。
//
// 编辑路径的上传条目元数据（2026-10-11 修订）：实际新追加的作品行按 Apply
// 的 recordAddedLines/entryIdentity 同款口径登记——否则覆盖式重导入（同
// 文件名整体替换片段）会静默冲掉编辑写入的作品行，而保护只认上传条目；
// 来源行仍不登记（来源区编辑走 ReplaceAuthorSources，口径不变）。编辑仍是
// 有意变更：只登记实际追加的行，并按 authoring.PruneUploadEntries 剔除片段
// 内容已不存在的旧条目（REQ §4.2 反向缺口）。

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// ErrAuthorNotRegular：COS 作者不参与 TXT 编辑（COS 作者由目录结构派生，
// DOMAIN_RULES §6；handler 映射 400）。与 ErrAuthorNotFound（attach.go）
// 同为哨兵错误，供 errors.Is 判别。
var ErrAuthorNotRegular = errors.New("authorattach: author is not regular")

// ErrDisplayNameIdentityMismatch：作者行显示名无法推出自身 id（病态存量：
// 显示名被手工改过/哈希兜底 id），写块必然身份漂移——调用方转 400 提示先
// 改名（改名端点能把此类行迁回自洽形态）。2026-10-13 修订新增。
var ErrDisplayNameIdentityMismatch = errors.New("authorattach: display name does not derive the author id")

// ErrAuthorIDConflict：改名目标身份与既有作者（常规或 COS 行）冲突——
// 本端点不做作者合并，handler 映射 409。
var ErrAuthorIDConflict = errors.New("authorattach: target author id already exists")

// AuthorRef 是编辑端点传入的已校验作者（id + authors 行显示名）。
type AuthorRef struct {
	ID          string
	DisplayName string
}

// ResolveRegularAuthor 校验 authorId 存在且为常规作者，返回显示名。
// 不存在 → ErrAuthorNotFound；COS 作者 → ErrAuthorNotRegular。
// ListAuthors 全量内存找——与 resolveAuthor 同拍板（作者表量级百级，
// 不为单查开新查询）。
func (s *Service) ResolveRegularAuthor(ctx context.Context, q *db.Queries, authorID string) (string, error) {
	refs, err := resolveRegularAuthors(ctx, q, []string{authorID})
	if err != nil {
		return "", err
	}
	return refs[authorID], nil
}

// ResolveRegularAuthors 批量校验（PUT /assets/{assetId}/authors 的
// authorIds 全集）：任一 id 不存在 → ErrAuthorNotFound、非 regular →
// ErrAuthorNotRegular。返回 id → 显示名。
func (s *Service) ResolveRegularAuthors(ctx context.Context, q *db.Queries, authorIDs []string) (map[string]string, error) {
	return resolveRegularAuthors(ctx, q, authorIDs)
}

// resolveRegularAuthors 是两个 Resolve* 的共用实现：一次 ListAuthors 完成
// 存在性与类型双校验。
func resolveRegularAuthors(ctx context.Context, q *db.Queries, authorIDs []string) (map[string]string, error) {
	rows, err := q.ListAuthors(ctx)
	if err != nil {
		return nil, fmt.Errorf("authorattach: 查询作者表: %w", err)
	}
	byID := make(map[string]db.ListAuthorsRow, len(rows))
	for _, row := range rows {
		byID[row.ID] = row
	}
	names := make(map[string]string, len(authorIDs))
	for _, id := range authorIDs {
		row, ok := byID[id]
		if !ok {
			return nil, ErrAuthorNotFound
		}
		if row.Type != authoring.AuthorTypeRegular {
			return nil, ErrAuthorNotRegular
		}
		names[id] = row.DisplayName
	}
	return names, nil
}

// ResolveOrCreateAuthors 把编辑端点的 newAuthorNames 解析成作者引用（调用方
// 事务内执行；PUT /assets/{assetId}/authors 的按名新建通道）：
//   - 逐名按规范身份匹配已有作者——CanonicalAuthorNames 归一别名后取首别名
//     GenerateAuthorID 为身份（与块解析回读同口径，大小写/空白差异/括号备注
//     不裂分身）：命中常规作者 → 复用（沿用其 DisplayName，不重复建行）；
//   - 未命中 → 新建作者行（type=regular、origin=client、created_at=now，
//     displayName = canonical 别名以 " / " 连接——displayNameAliases 拆分
//     重建编号行的往返安全形态）。
//
// 同一次调用内两名解析到同一身份时后一名复用前一名（seen 短路）：UpsertAuthor
// 冲突分支会覆盖 display_name，不短路会让先写的显示名被后写的变体冲掉。
// COS 作者行撞名（cos_ 前缀恰是 GenerateAuthorID 的合法产物）返回错误：静默
// 复用会把 COS 作者错当常规作者、静默新建会覆盖 COS 行，两者都是事故。
func (s *Service) ResolveOrCreateAuthors(ctx context.Context, qtx *db.Queries, names []string, now time.Time) ([]AuthorRef, error) {
	rows, err := qtx.ListAuthors(ctx)
	if err != nil {
		return nil, fmt.Errorf("authorattach: 查询作者表: %w", err)
	}
	byID := make(map[string]db.ListAuthorsRow, len(rows))
	for _, row := range rows {
		byID[row.ID] = row
	}
	refs := make([]AuthorRef, 0, len(names))
	seen := make(map[string]bool, len(names))
	for _, name := range names {
		canonical := authoring.CanonicalAuthorNames(name)
		if len(canonical) == 0 {
			// handler 前置 NewAuthorNameRejectReason 已拦，防御兜底。
			return nil, ErrInvalidAuthorName
		}
		id := authoring.GenerateAuthorID(canonical[0])
		if seen[id] {
			continue // 同批重名：身份相同即同一人，复用首次解析结果
		}
		seen[id] = true
		if row, ok := byID[id]; ok {
			if row.Type != authoring.AuthorTypeRegular {
				// 哨兵包装（ErrAuthorIDConflict）：作者管理端点据此映射 409，
				// 编辑端点按既有 4xx 路径透出原因（文案不变）。
				return nil, fmt.Errorf("authorattach: 名字 %q 的规范身份 %s 与 %s 类型作者行撞名: %w",
					name, id, row.Type, ErrAuthorIDConflict)
			}
			refs = append(refs, AuthorRef{ID: id, DisplayName: row.DisplayName})
			continue
		}
		displayName := strings.Join(canonical, authoring.DisplayNameSeparator)
		if err := qtx.UpsertAuthor(ctx, db.UpsertAuthorParams{
			ID: id, DisplayName: displayName, Type: authoring.AuthorTypeRegular,
			CreatedAt: store.FormatTimestamp(now),
			// 溯源章（ADR-0032）：编辑端点按名新建作者，与同端点的关联写入
			// 同通道（web/app 共用统一盖 client）。
			Origin: store.OriginClient,
		}); err != nil {
			return nil, fmt.Errorf("authorattach: upsert 作者 %s: %w", id, err)
		}
		// 新建行登记进 byID：后续同名（同批或后续调用）按复用处理。
		byID[id] = db.ListAuthorsRow{ID: id, DisplayName: displayName, Type: authoring.AuthorTypeRegular}
		refs = append(refs, AuthorRef{ID: id, DisplayName: displayName})
	}
	return refs, nil
}

// MergeAuthorRefs 合并 authorIds 与按名解析两个来源的作者引用为全集（同 id
// 去重，先遇者胜）：身份是 id，显示名两个来源同值（authorIds 路径取作者行、
// 按名路径复用作者行），先遇者胜只影响交集元素的显示名来源，不改变身份。
func MergeAuthorRefs(base, extra []AuthorRef) []AuthorRef {
	seen := make(map[string]bool, len(base)+len(extra))
	out := make([]AuthorRef, 0, len(base)+len(extra))
	for _, list := range [][]AuthorRef{base, extra} {
		for _, ref := range list {
			if seen[ref.ID] {
				continue
			}
			seen[ref.ID] = true
			out = append(out, ref)
		}
	}
	return out
}

// AuthorSources 读取作者所在片段（数组序第一个命中块）的来源区；无块 = 空
// 数组（协议 200 空数组语义，非 null）。
func (s *Service) AuthorSources(ctx context.Context, q *db.Queries, authorID string) ([]string, error) {
	sources, err := LoadSources(ctx, q)
	if err != nil {
		return nil, err
	}
	if _, block, ok := findAuthorBlock(sources, authorID); ok {
		return block.Sources, nil
	}
	return []string{}, nil
}

// displayNameAliases 从作者行显示名重建编号行的规范别名列表（回写身份自洽
// 口径，实现唯一来源 = authoring.AliasesForAuthorID）：返回列表以两空格 join
// 写入编号行后，解析回读的 GenerateAuthorID(首别名) 必须等于 authorID ——
// 否则写出的块归到别的作者行（2026-10-13 事故：显示名单别名含「 / 」字面被
// 盲目拆成两别名，来源/作品写进错误 id 的块，编辑页回读永远为空、保存看着
// 「无效」）。推不出目标 id 的病态存量（显示名被手工改过/哈希兜底 id）返回
// ok=false，调用方转失败提示先改名，绝不写身份漂移的块。
func displayNameAliases(displayName, authorID string) ([]string, bool) {
	return authoring.AliasesForAuthorID(displayName, authorID)
}

// SourcesWriteMode 决定作者块来源区的写入语义（PUT /authors/{authorId}/sources
// 的 mode 参数）：replace=编辑页整体替换；append=上传流程自动挂靠的并入语义
// （ADR-0023 原上传来源口径——上传是补充不是编辑，永不冲掉作者既有来源区）。
type SourcesWriteMode string

const (
	SourcesModeReplace SourcesWriteMode = "replace"
	SourcesModeAppend  SourcesWriteMode = "append"
)

// ReplaceAuthorSources 写入作者块的来源区（调用方事务内执行）。mode：
//   - replace：块命中 → authoring.ReplaceSources 原地整体替换；
//   - append：块命中 → authoring.AppendSources 并入去重（只补缺行，永不
//     覆盖既有来源行，幂等）。
//
// 两模式共用其余路径：未命中 → 最近导入片段新建作者块（AppendAuthorBlock，
// 编号行别名由作者显示名按 " / " 拆分重建——displayName 是规范别名的连接串
// 而非单别名，整串写入会让块解析回读的 id 漂移，见 displayNameAliases；
// 无块时不存在「既有来源区可被覆盖」，append 无需独立路径）；库中无片段 →
// 自动创建 AutoFragmentFilename 承载片段（ImportedAt=now，此后即最近导入；
// 与 Apply 同待遇）。片段内容变化时同步修剪该片段的上传条目元数据（统一
// 修剪流程见 persistSourcesAndPrune）。返回写入后的来源区：replace=调用方
// 规范化输入；append=既有区 ∪ 新增（保序去重，即解析回读结果）。
func (s *Service) ReplaceAuthorSources(ctx context.Context, qtx *db.Queries, now time.Time, authorID, displayName string, sources []string, mode SourcesWriteMode) ([]string, error) {
	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return nil, err
	}
	idx, block, found := findAuthorBlock(fragments, authorID)
	if found {
		fragment, content := fragments[idx].Filename, fragments[idx].Content
		newContent, echo := writeAuthorBlockSources(content, authorID, sources, mode, block.Sources)
		if newContent == content {
			return echo, nil // 幂等：内容无变化不写 kv
		}
		fragments[idx].Content = newContent
		if err := s.persistSourcesAndPrune(ctx, qtx, now, fragment, fragments, newContent); err != nil {
			return nil, err
		}
		return echo, nil
	}
	// 无块：最近导入片段新建作者块；库中无片段则自动创建承载片段。
	// 别名列表必须往返恒等（回读 id == authorID）；推不出目标身份的病态
	// 存量直接失败，绝不写漂移块（见 displayNameAliases）。
	names, ok := displayNameAliases(displayName, authorID)
	if !ok {
		return nil, fmt.Errorf("authorattach: 作者 %s 显示名 %q 与身份不自洽（请先改名）: %w",
			authorID, displayName, ErrDisplayNameIdentityMismatch)
	}
	ti := mostRecentIndex(fragments)
	var fragment, content string
	if ti < 0 {
		fragment = authoring.AutoFragmentFilename
	} else {
		fragment, content = fragments[ti].Filename, fragments[ti].Content
	}
	// 第二返回值（显示名）只写入上传条目元数据，来源区写入路径从不产生新
	// 条目（见下方 persistSourcesAndPrune 注释）；且它恒等于 names 的
	// DisplayNameSeparator 连接（entryIdentity 三分支皆然），需要时可重建，
	// 丢弃无损失。
	newContent := authoring.AppendAuthorBlock(content, names, sources, nil)
	if ti < 0 {
		fragments = append(fragments, Source{
			Filename: fragment, Content: newContent, ImportedAt: store.FormatTimestamp(now),
		})
	} else {
		fragments[ti].Content = newContent
	}
	if err := s.persistSourcesAndPrune(ctx, qtx, now, fragment, fragments, newContent); err != nil {
		return nil, err
	}
	return sources, nil
}

// writeAuthorBlockSources 对已定位的作者块按 mode 做纯函数手术，返回
// （新内容, 写入后应回显的来源区）：
//   - replace：ReplaceSources 整体替换，回显=调用方规范化输入（块已定位，
//     found 恒 true，与既有代码同款忽略第二返回值）；
//   - append：AppendSources 并入去重，回显=既有区 ∪ 新增（mergeLines 保序
//     去重）——AppendSources 的写入结果正是「既有行 + 补缺行」，回显不得
//     用请求输入（那不是写入后的来源区）。
func writeAuthorBlockSources(content, authorID string, sources []string, mode SourcesWriteMode, existing []string) (string, []string) {
	if mode == SourcesModeAppend {
		newContent, _ := authoring.AppendSources(content, authorID, sources)
		merged := mergeLines(existing, sources)
		if merged == nil {
			merged = []string{} // 回显恒非 nil（与 GET 空数组语义对齐，禁 JSON null）
		}
		return newContent, merged
	}
	newContent, _ := authoring.ReplaceSources(content, authorID, sources)
	return newContent, sources
}

// persistSourcesAndPrune 落库片段数组并修剪 fragment 片段的上传条目元数据
// （块命中/新建块 × replace/append 四条路径统一收口）。append 不移除行，
// PruneUploadEntries 对它恒为 no-op——仍统一走修剪而不是按模式分支，是因为
// imported_txt_upload_entries 的口径「只修剪不新增」必须只有一份实现：本
// 函数服务的来源区写入路径是纯编辑语义、从不产生新条目（作品行的新增登记
// 只属于 ReplaceAssetAuthors 与 Apply 的 recordAddedLines），分叉修剪流程
// 必然在未来某次改动中漏掉一条路径，导致重导入 409 误报。
func (s *Service) persistSourcesAndPrune(ctx context.Context, qtx *db.Queries, now time.Time, fragment string, fragments []Source, newContent string) error {
	if err := PersistSources(ctx, qtx, now, fragments); err != nil {
		return err
	}
	return s.pruneFragmentEntries(ctx, qtx, now, fragment, newContent)
}

// ReplaceAssetAuthors 整体替换资产的常规作者关联（调用方事务内执行；
// ADR-0024）：
//   - 移除关联：遍历全部片段 authoring.RemoveWorks 删该资产的作品行
//     （按资产当前 file_name 匹配——与 Apply 写入的作品行同口径）；
//   - 新增关联（对作者全集逐位执行，含已关联者——2026-10-11 自愈修订）：
//     资产当前 file_name 作为作品行并入作者块，落点与 Apply 同款（块命中→
//     数组序第一个片段；未命中→最近导入片段新建块；无片段→自动创建承载
//     片段）。已关联但片段缺作品行是用户真实事故（关联在 DB、TXT 片段缺
//     行）：对全集幂等追加使任何一次编辑保存都补写缺失行；行已存在时
//     AppendWorks 返回原内容，靠 newContent == content 短路实现零写入；
//   - 片段内容有变化时登记本次实际新追加的作品行（recordAddedLines 同款
//     口径，防覆盖式重导入静默冲掉编辑写入的行），并同步修剪对应片段的
//     上传条目元数据；
//   - 关联表 swap（DeleteAssetAuthorsByAssetID + 逐个 AddAssetAuthor，
//     终态=新列表）与本函数的片段更新同事务，保证「片段真相 + 关联表」
//     原子。
func (s *Service) ReplaceAssetAuthors(ctx context.Context, qtx *db.Queries, now time.Time, assetID, fileName string, authors []AuthorRef) error {
	fileName = strings.TrimSpace(fileName)
	if fileName == "" {
		return errors.New("authorattach: fileName 不能为空（资产当前落盘文件名）")
	}
	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return err
	}
	current, err := qtx.ListAssetAuthorRefs(ctx, assetID)
	if err != nil {
		return fmt.Errorf("authorattach: 查询资产现有作者: %w", err)
	}
	newSet := make(map[string]bool, len(authors))
	for _, a := range authors {
		newSet[a.ID] = true
	}
	// changed 收集内容实际变化的片段（片段名 → 变更后内容）：PersistSources
	// 与条目修剪都只对它们执行；added 收集本次实际新追加的作品行（按片段
	// 分组，与 Apply 的 recordAddedLines 同一口径）。
	changed := removeAssetWorkLines(fragments, current, newSet, fileName)
	fragments, added, err := appendAssetWorkLines(fragments, authors, fileName, now, changed)
	if err != nil {
		return err
	}
	if len(changed) > 0 {
		if err := PersistSources(ctx, qtx, now, fragments); err != nil {
			return err
		}
	}
	// 登记先于修剪：新追加的行此刻已在片段中，修剪对它恒为 no-op。顺序本身
	// 不影响正确性（修剪以「追加后内容」判存在，颠倒也不会误剪刚登记的行），
	// 保持先登记后修剪只为与 Apply 的流程顺序对齐、少一处阅读负担。
	// （2026-10-11 对抗审查：修正原注释「颠倒会误剪」的不准确论证。）
	for fragment, entries := range added {
		for _, e := range entries {
			if err := recordAddedLines(ctx, qtx, now, fragment, e); err != nil {
				return err
			}
		}
	}
	for fragment, content := range changed {
		if err := s.pruneFragmentEntries(ctx, qtx, now, fragment, content); err != nil {
			return err
		}
	}
	if err := qtx.DeleteAssetAuthorsByAssetID(ctx, assetID); err != nil {
		return fmt.Errorf("authorattach: 清空资产作者关联: %w", err)
	}
	for _, a := range authors {
		if err := qtx.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
			AssetID: assetID, AuthorID: a.ID,
			// 溯源章（ADR-0032）：客户端编辑端点（PUT /assets/{id}/authors），
			// web/app 共用统一盖 client；swap 先删后插，时间=本次替换时刻。
			CreatedAt: store.NullTimestamp(store.FormatTimestamp(now)),
			Origin:    store.OriginClient,
		}); err != nil {
			return fmt.Errorf("authorattach: 建立资产-作者关联: %w", err)
		}
	}
	return nil
}

// removeAssetWorkLines 是 ReplaceAssetAuthors 的移除半场：遍历全部片段删
// 「不再在作者全集里」的作者的作品行（按资产当前 file_name 匹配——与 Apply
// 写入的作品行同口径），返回内容实际变化的片段（片段名 → 变更后内容）。
// RemoveWorks 未命中行时原样返回，不记变化——资产改名后的残留行按协议缺口
// 由统一重建收敛。
func removeAssetWorkLines(fragments []Source, current []db.ListAssetAuthorRefsRow, keep map[string]bool, fileName string) map[string]string {
	changed := map[string]string{}
	for _, row := range current {
		if keep[row.ID] {
			continue
		}
		for i := range fragments {
			newContent, removed := authoring.RemoveWorks(fragments[i].Content, row.ID, []string{fileName})
			if len(removed) == 0 {
				continue
			}
			fragments[i].Content = newContent
			changed[fragments[i].Filename] = newContent
		}
	}
	return changed
}

// appendAssetWorkLines 是 ReplaceAssetAuthors 的追加半场：对作者全集逐位
// 幂等追加作品行（含已关联者的自愈补行，见 ReplaceAssetAuthors 注释），
// 返回更新后的片段数组与本次实际新追加的条目（按片段分组，登记口径同
// Apply）；changed 就地并入内容变化的片段。落点与 Apply 同款（块命中→数组
// 序第一个片段；未命中→最近导入片段新建块；无片段→自动创建承载片段）。
func appendAssetWorkLines(fragments []Source, authors []AuthorRef, fileName string, now time.Time, changed map[string]string) ([]Source, map[string][]authoring.UploadEntry, error) {
	added := map[string][]authoring.UploadEntry{}
	works := []string{fileName}
	for _, a := range authors {
		idx, blockNames := targetFragmentIndex(fragments, a.ID)
		var content, fragment string
		autoCreated := idx < 0
		if autoCreated {
			fragment = authoring.AutoFragmentFilename
		} else {
			fragment, content = fragments[idx].Filename, fragments[idx].Content
		}
		// entryIdentity 决定条目别名与新块编号行：块命中 → 该块解析出的
		// AuthorNames（往返恒等）；无块 → 显示名按身份自洽口径重建的规范
		// 别名（AliasesForAuthorID；推不出目标身份的病态存量直接失败，
		// 绝不写漂移块——2026-10-13 事故防线）。
		var aliases []string
		var entryDisplay string
		if len(blockNames) > 0 {
			aliases, entryDisplay = entryIdentity(nil, blockNames, a.DisplayName)
		} else {
			var ok bool
			aliases, ok = displayNameAliases(a.DisplayName, a.ID)
			if !ok {
				return nil, nil, fmt.Errorf("authorattach: 作者 %s 显示名 %q 与身份不自洽（请先改名）: %w",
					a.ID, a.DisplayName, ErrDisplayNameIdentityMismatch)
			}
			entryDisplay = strings.Join(aliases, authoring.DisplayNameSeparator)
		}
		newContent, blockFound := authoring.AppendWorks(content, a.ID, works)
		if !blockFound {
			newContent = authoring.AppendAuthorBlock(content, aliases, nil, works)
		}
		if newContent == content {
			continue // 幂等：行已在块中且无块新建发生，零写入
		}
		// 本次实际新追加的行 = 旧内容缺少的条目部分（MissingUploadEntries
		// 语义，块缺失时整条带 DisplayName/Names 供重建缺失块）。
		if e := authoring.MissingUploadEntries(content, []authoring.UploadEntry{{
			AuthorID: a.ID, DisplayName: entryDisplay, Names: aliases, Works: works,
		}}); len(e) > 0 {
			added[fragment] = append(added[fragment], e[0])
		}
		if autoCreated {
			fragments = append(fragments, Source{
				Filename: fragment, Content: newContent, ImportedAt: store.FormatTimestamp(now),
			})
		} else {
			fragments[idx].Content = newContent
		}
		changed[fragment] = newContent
	}
	return fragments, added, nil
}

// pruneFragmentEntries 剔除 fragment 片段上传条目中内容已不存在的部分
// （编辑是有意变更：不修剪会让同文件名重导入对被编辑移除的行误报 409）。
// 无条目或裁剪无变化时不写 kv。
func (s *Service) pruneFragmentEntries(ctx context.Context, qtx *db.Queries, now time.Time, fragment, newContent string) error {
	entries, err := LoadUploadEntries(ctx, qtx)
	if err != nil {
		return err
	}
	list := entries[fragment]
	if len(list) == 0 {
		return nil
	}
	pruned := authoring.PruneUploadEntries(list, newContent)
	if sameUploadEntries(list, pruned) {
		return nil
	}
	if len(pruned) == 0 {
		delete(entries, fragment)
	} else {
		entries[fragment] = pruned
	}
	return SaveUploadEntries(ctx, qtx, now, entries)
}

// sameUploadEntries 报告裁剪前后条目是否逐字段相等（避免无变化的 kv 写）。
func sameUploadEntries(a, b []authoring.UploadEntry) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i].AuthorID != b[i].AuthorID || a[i].DisplayName != b[i].DisplayName ||
			!equalStrings(a[i].Names, b[i].Names) || !equalStrings(a[i].Works, b[i].Works) ||
			!equalStrings(a[i].Sources, b[i].Sources) {
			return false
		}
	}
	return true
}

// equalStrings 逐元素相等（nil 与空切片视为相等——JSON 往返常把空数组变 nil）。
func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
