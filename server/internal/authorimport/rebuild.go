package authorimport

import (
	"context"
	"sort"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// rebuiltAuthor 是跨片段合并后的重建素材（同名作者 id → 首遇显示名 +
// 作品名集合）。
type rebuiltAuthor struct {
	displayName string
	works       []string
}

// mergeTxtSources 全量解析合并：authorId 相同的跨片段作者合并作品集
// （显示名首遇保留——同名作者在多个编号行中合并关联文件，不合并别名列表）。
// 片段类型是 authorattach.Source（kv_settings 单一来源；ImportedAt 不参与
// 合并——重建只关心内容）。
func mergeTxtSources(sources []authorattach.Source) map[string]*rebuiltAuthor {
	merged := make(map[string]*rebuiltAuthor)
	for _, src := range sources {
		for _, b := range authoring.ParseAuthorBlocks(src.Content) {
			if len(b.AuthorNames) == 0 {
				continue
			}
			id := authoring.GenerateAuthorID(b.AuthorNames[0])
			if ra, ok := merged[id]; ok {
				ra.works = append(ra.works, b.Works...)
				continue
			}
			merged[id] = &rebuiltAuthor{displayName: b.DisplayName, works: append([]string(nil), b.Works...)}
		}
	}
	return merged
}

// orderedAuthorIDs 把合并集 id 排成确定性顺序（map 迭代无序，删除/重插的
// 顺序稳定便于测试与回放）。
func orderedAuthorIDs(merged map[string]*rebuiltAuthor) []string {
	ids := make([]string, 0, len(merged))
	for id := range merged {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

// upsertMergedAuthors 事务内：把合并集里的作者写成 regular 作者行（upsert
// 幂等；格式 C 与旧项目迁移也可能建过同名行，覆盖不删）。origin 为本次
// 重建的溯源章（ADR-0032）：新行盖章；既有行仅 legacy（不可考）被补证，
// 可考行不被覆盖（DOMAIN_RULES §10 补证/首写优先规则在 UpsertAuthor 内）。
func (s *Service) upsertMergedAuthors(ctx context.Context, qtx *db.Queries, merged map[string]*rebuiltAuthor, origin string) error {
	now := store.FormatTimestamp(s.now())
	for _, id := range orderedAuthorIDs(merged) {
		ra := merged[id]
		if err := qtx.UpsertAuthor(ctx, db.UpsertAuthorParams{
			ID:          id,
			DisplayName: ra.displayName,
			Type:        authoring.AuthorTypeRegular,
			CreatedAt:   now,
			Origin:      origin,
		}); err != nil {
			return err
		}
	}
	return nil
}

// insertLinks 事务内：以 merged 为重建来源，按作品名匹配 normal 库资产
// 全量重插关联（调用方先负责删旧关联）。origin/created_at 为本次重建的
// 溯源章（ADR-0032，migration 0016）：重建=先删后插，关联时间与通道章随
// 每次重建刷新（与替换式标签 PUT 同一口径）。countWorks 非 nil 时累计
// 「作品 ∈ countWorks[id]」的匹配文件数（导入响应口径=本次导入贡献）；
// 删除路径传 nil 不计。
func (s *Service) insertLinks(ctx context.Context, qtx *db.Queries, merged map[string]*rebuiltAuthor, countWorks map[string][]string, origin string) (int, error) {
	rows, err := qtx.ListNormalAssetsForAuthorMatch(ctx)
	if err != nil {
		return 0, err
	}
	domain := make([]authoring.MediaFile, 0, len(rows))
	for _, row := range rows {
		domain = append(domain, authoring.MediaFile{AssetID: row.AssetID, FileName: row.FileName})
	}
	// R2（审计 2026-09-20）：域级预计算索引，把每个 (作者, 作品) 的匹配从
	// 全域线性扫降为桶内候选过滤；语义与 authoring.MatchWorks 等价
	// （fileindex 对照测试锁定）。
	ix := authoring.BuildFileIndex(domain)

	filesMatched := 0
	for _, id := range orderedAuthorIDs(merged) {
		ra := merged[id]
		thisWorks := countWorks[id]
		countSet := make(map[string]bool, len(thisWorks))
		for _, w := range thisWorks {
			countSet[w] = true
		}
		seenWork := make(map[string]bool, len(ra.works))
		for _, w := range ra.works {
			if seenWork[w] {
				continue
			}
			seenWork[w] = true
			matches := ix.MatchWorks(w)
			for _, f := range matches {
				if err := qtx.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
					AssetID: f.AssetID, AuthorID: id,
					CreatedAt: store.NullTimestamp(store.FormatTimestamp(s.now())),
					Origin:    origin,
				}); err != nil {
					return 0, err
				}
			}
			if countSet[w] {
				filesMatched += len(matches)
			}
		}
	}
	return filesMatched, nil
}

// rebuildAll 统一重建事务内主流程：保证 merged（删关联目标）的作者行存在
// → 删其全部旧关联 → 从 relink（重建来源）全量重插。常规路径 merged 与
// relink 同集；删除路径 merged=删除前全量、relink=删除后剩余（作者从剩余
// 片段消失时旧关联被一并清掉）。origin 见 insertLinks/upsertMergedAuthors；
// 删除路径（removeTxtSource）同为 TXT 通道（OriginTXT）。
func (s *Service) rebuildAll(ctx context.Context, qtx *db.Queries, merged, relink map[string]*rebuiltAuthor, countWorks map[string][]string, origin string) (int, error) {
	if err := s.upsertMergedAuthors(ctx, qtx, merged, origin); err != nil {
		return 0, err
	}
	if err := qtx.DeleteAssetAuthorsByAuthorIds(ctx, orderedAuthorIDs(merged)); err != nil {
		return 0, err
	}
	return s.insertLinks(ctx, qtx, relink, countWorks, origin)
}

// rebuildFromAllSources 统一重建（导入路径，事务内调用）：先覆盖式 upsert
// 本次片段（同 filename 覆盖内容；filename 空串也是合法键——匿名导入只保留
// 最近一份），再对「全部已导入片段」统一重建。重导入保护在覆盖分支前执行
// （REQ §3.3 第 10/11/12 条）。返回（本次传入 content 的匹配文件数——
// filesMatched 只统计本次导入的作品不含历史片段贡献；keep 路径并回的上传
// 条目行数）。
func (s *Service) rebuildFromAllSources(ctx context.Context, qtx *db.Queries, currentBlocks []authoring.AuthorBlock, filename *string, content, resolution, origin string) (filesMatched, mergedUploadEntries int, err error) {
	sources, err := authorattach.LoadSources(ctx, qtx)
	if err != nil {
		return 0, 0, err
	}
	thisName := ""
	if filename != nil {
		thisName = *filename
	}
	updated := false
	for i := range sources {
		if sources[i].Filename == thisName {
			// 覆盖既有片段前先走重导入保护：不得静默冲掉上传写入的条目。
			merged, perr := s.protectUploadEntries(ctx, qtx, thisName, &content, resolution)
			if perr != nil {
				return 0, 0, perr
			}
			mergedUploadEntries = merged
			sources[i].Content = content
			sources[i].ImportedAt = store.FormatTimestamp(s.now()) // 重导入=新的导入时刻
			updated = true
			break
		}
	}
	if !updated {
		sources = append(sources, authorattach.Source{
			Filename:   thisName,
			Content:    content,
			ImportedAt: store.FormatTimestamp(s.now()),
		})
	}
	if err := authorattach.PersistSources(ctx, qtx, s.now(), sources); err != nil {
		return 0, 0, err
	}

	mergedAuthors := mergeTxtSources(sources)
	// 本次导入的作品集（filesMatched 计数锚点）。
	countWorks := make(map[string][]string, len(currentBlocks))
	for _, b := range currentBlocks {
		if len(b.AuthorNames) == 0 {
			continue
		}
		id := authoring.GenerateAuthorID(b.AuthorNames[0])
		countWorks[id] = append(countWorks[id], b.Works...)
	}
	filesMatched, err = s.rebuildAll(ctx, qtx, mergedAuthors, mergedAuthors, countWorks, origin)
	return filesMatched, mergedUploadEntries, err
}
