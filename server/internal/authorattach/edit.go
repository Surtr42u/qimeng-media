package authorattach

// edit.go：资产编辑的片段编排（ADR-0024：编辑端点的作者关联/来源变更同样
// 写进片段本体原地更新——TXT 片段是唯一真相，只改 asset_authors 表会被下次
// 统一重建冲掉）。落点规则复用 attach.go 的 findAuthorBlock/
// targetFragmentIndex（作者块命中→数组序第一个片段；未命中→最近导入片段；
// 无片段→自动创建承载片段），与上传挂靠共享同一份口径，禁止复制粘贴。
//
// 编辑不新增上传条目元数据（编辑是有意变更而非上传痕迹），只按
// authoring.PruneUploadEntries 剔除片段内容已不存在的旧条目——否则重导入
// 保护会对被编辑移除的行误报 409（REQ §4.2 反向缺口）。

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

// displayNameAliases 从作者行显示名重建编号行的规范别名列表：authors 表的
// displayName 是 CanonicalAuthorNames 产物按 " / " 的连接串（Apply/统一重建
// 同口径），新建块必须按空格分隔写回各别名——整串当单别名会让解析回读的
// GenerateAuthorID(首别名) 与真实 id 分裂（幻影作者、统一重建丢关联）。
// 拆分归一后全空（病态数据）回落整串 canonical 兜底。
func displayNameAliases(displayName string) []string {
	var names []string
	for _, part := range strings.Split(displayName, " / ") {
		names = append(names, authoring.CanonicalAuthorNames(part)...)
	}
	if len(names) == 0 {
		return authoring.CanonicalAuthorNames(displayName)
	}
	return names
}

// ReplaceAuthorSources 整体替换作者块的来源区（调用方事务内执行）：
//   - 块命中 → authoring.ReplaceSources 原地替换该片段；
//   - 未命中 → 最近导入片段新建作者块（AppendAuthorBlock，编号行别名由
//     作者显示名按 " / " 拆分重建——displayName 是规范别名的连接串而非单
//     别名，整串写入会让块解析回读的 id 漂移，见 displayNameAliases）；
//   - 库中无片段 → 自动创建 AutoFragmentFilename 承载片段（ImportedAt=
//     now，此后即最近导入；与 Apply 同待遇）。
//
// 片段内容变化时同步修剪该片段的上传条目元数据。返回替换后的来源区
// （即调用方规范化后的输入）。
func (s *Service) ReplaceAuthorSources(ctx context.Context, qtx *db.Queries, now time.Time, authorID, displayName string, sources []string) ([]string, error) {
	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return nil, err
	}
	idx, _, found := findAuthorBlock(fragments, authorID)
	var fragment, content string
	if found {
		fragment, content = fragments[idx].Filename, fragments[idx].Content
		newContent, _ := authoring.ReplaceSources(content, authorID, sources)
		if newContent == content {
			return sources, nil // 幂等：内容无变化不写 kv
		}
		fragments[idx].Content = newContent
		if err := PersistSources(ctx, qtx, now, fragments); err != nil {
			return nil, err
		}
		if err := s.pruneFragmentEntries(ctx, qtx, now, fragment, newContent); err != nil {
			return nil, err
		}
		return sources, nil
	}
	// 无块：最近导入片段新建作者块；库中无片段则自动创建承载片段。
	ti := mostRecentIndex(fragments)
	if ti < 0 {
		fragment = authoring.AutoFragmentFilename
	} else {
		fragment, content = fragments[ti].Filename, fragments[ti].Content
	}
	names, _ := entryIdentity(displayNameAliases(displayName), nil, displayName)
	newContent := authoring.AppendAuthorBlock(content, names, sources, nil)
	if ti < 0 {
		fragments = append(fragments, Source{
			Filename: fragment, Content: newContent, ImportedAt: store.FormatTimestamp(now),
		})
	} else {
		fragments[ti].Content = newContent
	}
	if err := PersistSources(ctx, qtx, now, fragments); err != nil {
		return nil, err
	}
	if err := s.pruneFragmentEntries(ctx, qtx, now, fragment, newContent); err != nil {
		return nil, err
	}
	return sources, nil
}

// ReplaceAssetAuthors 整体替换资产的常规作者关联（调用方事务内执行；
// ADR-0024）：
//   - 移除关联：遍历全部片段 authoring.RemoveWorks 删该资产的作品行
//     （按资产当前 file_name 匹配——与 Apply 写入的作品行同口径）；
//   - 新增关联：资产当前 file_name 作为作品行并入作者块，落点与 Apply
//     同款（块命中→数组序第一个片段；未命中→最近导入片段新建块；无片段
//     →自动创建承载片段）；
//   - 片段内容有变化时同步修剪对应片段的上传条目元数据；
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
	curSet := make(map[string]bool, len(current))
	for _, row := range current {
		curSet[row.ID] = true
	}
	newSet := make(map[string]bool, len(authors))
	for _, a := range authors {
		newSet[a.ID] = true
	}
	// changed 收集内容实际变化的片段（片段名 → 变更后内容）：PersistSources
	// 与条目修剪都只对它们执行。
	changed := map[string]string{}
	// 移除关联：遍历全部片段删作品行（片段内容未命中行时 RemoveWorks
	// 原样返回，不记变化——资产改名后的残留行按协议缺口由统一重建收敛）。
	for _, row := range current {
		if newSet[row.ID] {
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
	// 新增关联：作品行并入作者块（AppendWorks 已命中块时自带逐行去重，
	// 幂等）。
	works := []string{fileName}
	for _, a := range authors {
		if curSet[a.ID] {
			continue
		}
		idx, blockNames := targetFragmentIndex(fragments, a.ID)
		var content, fragment string
		autoCreated := idx < 0
		if autoCreated {
			fragment = authoring.AutoFragmentFilename
		} else {
			fragment, content = fragments[idx].Filename, fragments[idx].Content
		}
		newContent, blockFound := authoring.AppendWorks(content, a.ID, works)
		if !blockFound {
			names, _ := entryIdentity(displayNameAliases(a.DisplayName), blockNames, a.DisplayName)
			newContent = authoring.AppendAuthorBlock(content, names, nil, works)
		}
		if newContent == content {
			continue // 幂等：行已在块中且无块新建发生（防御，正常不可达）
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
	if len(changed) > 0 {
		if err := PersistSources(ctx, qtx, now, fragments); err != nil {
			return err
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
		if err := qtx.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: assetID, AuthorID: a.ID}); err != nil {
			return fmt.Errorf("authorattach: 建立资产-作者关联: %w", err)
		}
	}
	return nil
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
