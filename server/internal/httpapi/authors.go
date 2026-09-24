// authors.go：作者体系端点（M3，DOMAIN_RULES §6）。
//
// 端点：作者列表（常规与 COS 统一返回）、TXT 导入（POST 三格式自动识别 +
// 统一重建 + 重导入保护）、已导入 TXT 列表（GET）/导出（GET export）/
// 移除（DELETE，从剩余片段统一重建）/全量重放（POST rebuild，从已存片段
// 重建关联）、关注置位/取消。片段的存取统一走 authorattach 包
// （kv_settings.imported_txt_sources 的单一来源）。
//
// TXT 导入的存储与重建语义（GUIDE_AUTHOR「双向匹配机制」，DOMAIN_RULES
// §6「TXT 导入以全部已导入 TXT 统一重建为语义」）：
//   - 块格式（A/B/B2）：本次片段 {filename, content} 以覆盖式 upsert 进
//     kv_settings（key=imported_txt_sources，JSON 数组），随后在**同一事务**
//     内从全部已导入片段重建作者与关联——先删全部相关作者旧关联再插全量
//     新关联，跨 TXT 同名作者关联 = 所有片段匹配文件的并集，不被单 TXT 的
//     删旧插新覆盖（旧项目 rebuildAssociationsFromBlocks 语义）；
//   - 重导入保护（REQ §3.3 第 10 条）：同文件名重导会整体替换片段，替换前
//     先比对「上传写入条目」（authorattach 的出处元数据）——新内容缺这些
//     条目时，缺省返回 409 由用户二选一：keep=自动并回后替换，remove=按
//     用户明示移除（元数据一并清除）；
//   - 格式 C（纯作者名列表）：只创建作者不关联文件，不触发重建、不存片段
//     （GUIDE_AUTHOR：不触发重建语义），也不触发重导入保护；
//   - 匹配域 = normal 库资产（kind='normal'），COS 资产被 DOMAIN_RULES §6
//     隔离口径排除（查询 ListNormalAssetsForAuthorMatch）；
//   - 新文件入库后的自动重匹配由扫描路径外的本端点承担（重新导入/刷新即
//     全量重建），scanner 不感知 TXT 片段（作者规则与扫描器解耦）；
//   - 镜像挂点：改变片段内容的操作（导入 / 片段删除 / 上传挂靠）在 commit
//     成功后同步刷新本地镜像（s.mirror.Refresh，尽力而为）；rebuild 不改
//     片段内容，不挂。
//
// 删关联目标的口径（removeTxtSource）：regular 作者的关联不全是 TXT 产物
// ——旧项目迁移（import.go）也会写 regular 作者关联，故重建**只能**删
// 「已导入片段涉及作者」的关联（DeleteAssetAuthorsByAuthorIds），不得按
// type='regular' 全删。删除路径以「删除前」全量片段为删关联目标（作者从
// 剩余片段消失时旧关联一并清掉），以「删除后」剩余片段为重建来源。
package httpapi

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 重导入保护 conflictResolution 的两个取值。与 api/openapi.yaml
// POST /authors/import-txt 的 conflictResolution enum（[keep, remove]）双写
// 同步：协议侧改枚举必须同步这里，反之亦然（AI_README_FIRST 代码卫生约束 3）。
const (
	resolutionKeep   = "keep"
	resolutionRemove = "remove"
)

// txtImportConflictError 是「同文件名重导入将丢弃上传写入条目」的哨兵载荷
// 错误（REQ §3.3 第 10 条）：事务内判定、向上冒泡到 handler 用 errors.As
// 取出 missing 明细组装 409 TXT_CONFLICT 载荷，绝不静默丢弃。
type txtImportConflictError struct {
	filename string
	missing  []authoring.UploadEntry
}

func (e *txtImportConflictError) Error() string {
	return fmt.Sprintf("重导入 %q 会丢弃上传写入条目（涉及 %d 位作者）", e.filename, len(e.missing))
}

// GetApiV1Authors 作者列表：全部作者 + 关联文件数 + 关注标记，
// 按 display_name 升序（查询注释写明口径，与 GET /tags 同风格）。
func (s *Server) GetApiV1Authors(w http.ResponseWriter, r *http.Request) {
	rows, err := s.q.ListAuthors(r.Context())
	if err != nil {
		s.internalErr(w, "查询作者列表", err)
		return
	}
	out := make([]gen.Author, 0, len(rows))
	for _, row := range rows {
		t := gen.AuthorType(row.Type)
		fileCount := int(row.FileCount)
		viewCount := toInt(row.ViewCount) // 全部作品累计 open 事件（SQL 聚合；toInt 抹平 sqlc interface{}）
		followed := row.Followed == 1
		out = append(out, gen.Author{
			Id:          &row.ID,
			DisplayName: &row.DisplayName,
			Type:        &t,
			FileCount:   &fileCount,
			Followed:    &followed,
			ViewCount:   ptr(viewCount),
		})
	}
	writeJSON(w, http.StatusOK, out)
}

// PostApiV1AuthorsImportTxt 导入作者 TXT：三格式自动识别 → 统一重建（含
// 重导入保护）→ 返回本次导入计数。409 = 新内容会丢弃上传写入条目，须带
// conflictResolution 重发（REQ §3.3 第 10 条）。
func (s *Server) PostApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request) {
	var body gen.PostApiV1AuthorsImportTxtJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.Content) == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "content 不能为空")
		return
	}
	// conflictResolution 枚举校验（openapi enum [keep, remove]；生成物不做
	// 运行时校验，这里手查——空串视为未传，向后兼容旧客户端）。
	resolution := ""
	if body.ConflictResolution != nil {
		resolution = strings.TrimSpace(string(*body.ConflictResolution))
		if resolution != resolutionKeep && resolution != resolutionRemove {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "conflictResolution 只允许 keep 或 remove")
			return
		}
	}

	res, err := s.importTxt(r.Context(), body.Filename, body.Content, resolution)
	if err != nil {
		var conflict *txtImportConflictError
		if errors.As(err, &conflict) {
			writeJSON(w, http.StatusConflict, buildTxtImportConflict(conflict))
			return
		}
		s.internalErr(w, "导入作者 TXT", err)
		return
	}
	// 作者-文件关联变了（COS 分流与 cosWork 装配输入），推荐缓存失效
	s.invalidateRecommendCache()
	// 片段内容已变（新片段/覆盖/keep 并回）：同步刷新本地镜像（尽力而为，
	// REQ §3.4——镜像失败不影响导入结果，事务已提交）。
	s.mirror.Refresh(r.Context(), s.q)
	writeJSON(w, http.StatusOK, res)
}

// buildTxtImportConflict 把冲突哨兵错误装配为 409 响应载荷
// （gen.TxtImportConflict，协议 schema）。
func buildTxtImportConflict(e *txtImportConflictError) gen.TxtImportConflict {
	authors := make([]gen.TxtImportConflictAuthor, 0, len(e.missing))
	for _, m := range e.missing {
		id, name := m.AuthorID, m.DisplayName
		works := append([]string(nil), m.Works...)
		sources := append([]string(nil), m.Sources...)
		authors = append(authors, gen.TxtImportConflictAuthor{
			AuthorId: &id, DisplayName: &name, Works: &works, Sources: &sources,
		})
	}
	filename := e.filename
	return gen.TxtImportConflict{Filename: &filename, Authors: &authors}
}

// importTxt 是导入主流程：块格式走统一重建事务；格式 C 只建作者。
// 计数口径：authorsImported = 本次 TXT 解析出的作者块数（格式 C 为行数）；
// filesMatched = 本次 TXT 的（作者, 去重作品）对匹配到的库内文件总数；
// mergedUploadEntries = keep 路径自动并回的上传条目行数（其余 0）。
func (s *Server) importTxt(ctx context.Context, filename *string, content, resolution string) (gen.TxtImportResult, error) {
	blocks := authoring.ParseAuthorBlocks(content)
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return gen.TxtImportResult{}, err
	}
	defer func() { _ = tx.Rollback() }()
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
			}); err != nil {
				return gen.TxtImportResult{}, err
			}
		}
		if err := tx.Commit(); err != nil {
			return gen.TxtImportResult{}, err
		}
		zero := 0
		imported := len(names)
		return gen.TxtImportResult{AuthorsImported: &imported, FilesMatched: &zero}, nil
	}

	filesMatched, merged, err := s.rebuildFromAllSources(ctx, qtx, blocks, filename, content, resolution)
	if err != nil {
		return gen.TxtImportResult{}, err
	}
	if err := tx.Commit(); err != nil {
		return gen.TxtImportResult{}, err
	}
	imported := len(blocks)
	return gen.TxtImportResult{
		AuthorsImported: &imported, FilesMatched: &filesMatched, MergedUploadEntries: &merged,
	}, nil
}

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
// 幂等；格式 C 与旧项目迁移也可能建过同名行，覆盖不删）。
func (s *Server) upsertMergedAuthors(ctx context.Context, qtx *db.Queries, merged map[string]*rebuiltAuthor) error {
	now := store.FormatTimestamp(s.now())
	for _, id := range orderedAuthorIDs(merged) {
		ra := merged[id]
		if err := qtx.UpsertAuthor(ctx, db.UpsertAuthorParams{
			ID:          id,
			DisplayName: ra.displayName,
			Type:        authoring.AuthorTypeRegular,
			CreatedAt:   now,
		}); err != nil {
			return err
		}
	}
	return nil
}

// insertLinks 事务内：以 merged 为重建来源，按作品名匹配 normal 库资产
// 全量重插关联（调用方先负责删旧关联）。countWorks 非 nil 时累计
// 「作品 ∈ countWorks[id]」的匹配文件数（导入响应口径=本次导入贡献）；
// 删除路径传 nil 不计。
func (s *Server) insertLinks(ctx context.Context, qtx *db.Queries, merged map[string]*rebuiltAuthor, countWorks map[string][]string) (int, error) {
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
// 片段消失时旧关联被一并清掉）。countWorks 见 insertLinks。
func (s *Server) rebuildAll(ctx context.Context, qtx *db.Queries, merged, relink map[string]*rebuiltAuthor, countWorks map[string][]string) (int, error) {
	if err := s.upsertMergedAuthors(ctx, qtx, merged); err != nil {
		return 0, err
	}
	if err := qtx.DeleteAssetAuthorsByAuthorIds(ctx, orderedAuthorIDs(merged)); err != nil {
		return 0, err
	}
	return s.insertLinks(ctx, qtx, relink, countWorks)
}

// rebuildFromAllSources 统一重建（导入路径，事务内调用）：先覆盖式 upsert
// 本次片段（同 filename 覆盖内容；filename 空串也是合法键——匿名导入只保留
// 最近一份），再对「全部已导入片段」统一重建。重导入保护在覆盖分支前执行
// （REQ §3.3 第 10/11/12 条）。返回（本次传入 content 的匹配文件数——
// filesMatched 只统计本次导入的作品不含历史片段贡献；keep 路径并回的上传
// 条目行数）。
func (s *Server) rebuildFromAllSources(ctx context.Context, qtx *db.Queries, currentBlocks []authoring.AuthorBlock, filename *string, content, resolution string) (filesMatched, mergedUploadEntries int, err error) {
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
	filesMatched, err = s.rebuildAll(ctx, qtx, mergedAuthors, mergedAuthors, countWorks)
	return filesMatched, mergedUploadEntries, err
}

// protectUploadEntries 重导入保护（事务内调用，覆盖同名片段前）：
// entries[thisName] 与新 content 比对，missing = 新内容缺少的上传条目。
//   - missing 空：照常（条目保留不动——保守口径：条目仍在上传写入名下，
//     后续同文件名重导继续受保护）；
//   - missing 非空且无 resolution：返回 txtImportConflictError（handler 409）；
//   - keep：把缺失条目并回 content（*content 就地更新，后续走既有覆盖+重建），
//     返回并回的行数（作品行+来源行）；
//   - remove：按用户明示移除——删除该片段的上传条目元数据（此后同文件名
//     重导不再受保护），content 原样替换落库。
func (s *Server) protectUploadEntries(ctx context.Context, qtx *db.Queries, thisName string, content *string, resolution string) (int, error) {
	entries, err := authorattach.LoadUploadEntries(ctx, qtx)
	if err != nil {
		return 0, err
	}
	missing := authoring.MissingUploadEntries(*content, entries[thisName])
	if len(missing) == 0 {
		return 0, nil
	}
	switch resolution {
	case "":
		return 0, &txtImportConflictError{filename: thisName, missing: missing}
	case resolutionKeep:
		*content = authoring.MergeUploadEntries(*content, missing)
		merged := 0
		for _, m := range missing {
			merged += len(m.Works) + len(m.Sources)
		}
		return merged, nil
	case resolutionRemove:
		delete(entries, thisName)
		if err := authorattach.SaveUploadEntries(ctx, qtx, s.now(), entries); err != nil {
			return 0, err
		}
		return 0, nil
	default:
		// 不可达：handler 已做枚举校验（400），此处防御兜底。
		return 0, fmt.Errorf("导入作者 TXT: 未知 conflictResolution %q", resolution)
	}
}

// errTxtSourceNotFound 是 DELETE 移除不存在的片段时的哨兵错误
// （handler 映射 404；事务内判断，确保移除+重建原子）。
var errTxtSourceNotFound = errors.New("txt source not found")

// removeTxtSource 移除一个片段并从剩余片段统一重建（DELETE 路径，事务内
// 调用）。删关联目标 = 删除前全量片段的作者集——作者只在被删片段出现时，
// 其旧关联随删除清空；重建来源 = 删除后剩余片段。作者行保留不级联删除
// （openapi：重建后零关联的常规作者保留）。片段没了，其上传条目元数据
// 一并清除（元数据按片段名分组，留着即死数据）。
func (s *Server) removeTxtSource(ctx context.Context, qtx *db.Queries, filename string) error {
	before, err := authorattach.LoadSources(ctx, qtx)
	if err != nil {
		return err
	}
	idx := -1
	for i := range before {
		if before[i].Filename == filename {
			idx = i
			break
		}
	}
	if idx < 0 {
		return errTxtSourceNotFound
	}
	remaining := append(append([]authorattach.Source(nil), before[:idx]...), before[idx+1:]...)
	if err := authorattach.PersistSources(ctx, qtx, s.now(), remaining); err != nil {
		return err
	}
	entries, err := authorattach.LoadUploadEntries(ctx, qtx)
	if err != nil {
		return err
	}
	if _, ok := entries[filename]; ok {
		delete(entries, filename)
		if err := authorattach.SaveUploadEntries(ctx, qtx, s.now(), entries); err != nil {
			return err
		}
	}
	_, err = s.rebuildAll(ctx, qtx, mergeTxtSources(before), mergeTxtSources(remaining), nil)
	return err
}

// GetApiV1AuthorsImportTxt 已导入的 TXT 片段列表（旧版数据管理「TXT导入
// 作者」卡片；按文件名升序，匿名导入的空名排最前；importedAt = 最近导入
// 时刻，旧数据无该字段则缺省）。
func (s *Server) GetApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request) {
	sources, err := authorattach.LoadSources(r.Context(), s.q)
	if err != nil {
		s.internalErr(w, "查询已导入 TXT", err)
		return
	}
	out := make([]gen.TxtImportedFile, 0, len(sources))
	for _, src := range sources {
		item := gen.TxtImportedFile{Filename: src.Filename}
		if src.ImportedAt != "" {
			item.ImportedAt = ptr(src.ImportedAt)
		}
		out = append(out, item)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Filename < out[j].Filename })
	writeJSON(w, http.StatusOK, out)
}

// DeleteApiV1AuthorsImportTxt 移除一个已导入 TXT 片段并从剩余片段统一
// 重建（204；不存在 → 404）。
func (s *Server) DeleteApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request, params gen.DeleteApiV1AuthorsImportTxtParams) {
	tx, err := s.conn.BeginTx(r.Context(), nil)
	if err != nil {
		s.internalErr(w, "移除 TXT 片段", err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	qtx := s.q.WithTx(tx)
	if err := s.removeTxtSource(r.Context(), qtx, params.Filename); err != nil {
		if errors.Is(err, errTxtSourceNotFound) {
			writeErr(w, http.StatusNotFound, codeNotFound, "TXT 片段不存在")
			return
		}
		s.internalErr(w, "移除 TXT 片段", err)
		return
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "移除 TXT 片段", err)
		return
	}
	// 片段移除触发关联重建，推荐输入变了，缓存失效
	s.invalidateRecommendCache()
	// 片段内容变了（少了一份）：同步刷新本地镜像（尽力而为，REQ §3.4）。
	s.mirror.Refresh(r.Context(), s.q)
	w.WriteHeader(http.StatusNoContent)
}

// PostApiV1AuthorsImportTxtRebuild 重放全部已导入 TXT 片段并重建常规作者-文件
// 关联（库重建/关联意外丢失后的修复入口）。幂等：片段内容不变，不写回
// 片段存储，只按已存片段并集删旧关联再全量重插；无片段时直接返回零值；
// 不刷新镜像（片段内容未变，挂点只挂「改变片段内容」的操作）。
// 计数口径：authorsImported=片段合并后的作者数；
// filesMatched=全部（作者, 去重作品）对的匹配文件数（countWorks 传每个
// 作者的全部作品名 = 全量计数，insertLinks 内按作品去重不翻倍）。
func (s *Server) PostApiV1AuthorsImportTxtRebuild(w http.ResponseWriter, r *http.Request) {
	tx, err := s.conn.BeginTx(r.Context(), nil)
	if err != nil {
		s.internalErr(w, "重放 TXT 重建关联", err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	qtx := s.q.WithTx(tx)

	sources, err := authorattach.LoadSources(r.Context(), qtx)
	if err != nil {
		s.internalErr(w, "重放 TXT 重建关联", err)
		return
	}
	if len(sources) == 0 {
		zero := 0
		writeJSON(w, http.StatusOK, gen.TxtImportResult{AuthorsImported: &zero, FilesMatched: &zero})
		return
	}
	merged := mergeTxtSources(sources)
	countWorks := make(map[string][]string, len(merged))
	for id, ra := range merged {
		countWorks[id] = ra.works
	}
	filesMatched, err := s.rebuildAll(r.Context(), qtx, merged, merged, countWorks)
	if err != nil {
		s.internalErr(w, "重放 TXT 重建关联", err)
		return
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "重放 TXT 重建关联", err)
		return
	}
	imported := len(merged)
	// 关联全量重建，推荐输入变了，缓存失效
	s.invalidateRecommendCache()
	writeJSON(w, http.StatusOK, gen.TxtImportResult{AuthorsImported: &imported, FilesMatched: &filesMatched})
}

// PutApiV1AuthorsAuthorIdFollow 设置/取消关注作者（DOMAIN_RULES §6/§7：
// 作者维度布尔标记，取消即清除、不删除作者及其文件）。
// 0 行受影响 = 作者不存在 → 404（查询注释约定）。
func (s *Server) PutApiV1AuthorsAuthorIdFollow(w http.ResponseWriter, r *http.Request, authorID string) {
	var body gen.PutApiV1AuthorsAuthorIdFollowJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	followed := int64(0)
	if body.Follow {
		followed = 1
	}
	rows, err := s.q.SetAuthorFollow(r.Context(), db.SetAuthorFollowParams{
		Followed: followed,
		ID:       authorID,
	})
	if err != nil {
		s.internalErr(w, "设置关注作者", err)
		return
	}
	if rows == 0 {
		writeErr(w, http.StatusNotFound, codeNotFound, "作者不存在")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
