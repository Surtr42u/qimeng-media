// authors.go：作者体系端点（M3，DOMAIN_RULES §6）。
//
// 端点：作者列表（常规与 COS 统一返回）、TXT 导入（POST 三格式自动识别 +
// 统一重建）、已导入 TXT 列表（GET）/移除（DELETE，从剩余片段统一重建）/
// 全量重放（POST rebuild，从已存片段重建关联）、关注置位/取消。
//
// TXT 导入的存储与重建语义（GUIDE_AUTHOR「双向匹配机制」，DOMAIN_RULES
// §6「TXT 导入以全部已导入 TXT 统一重建为语义」）：
//   - 块格式（A/B/B2）：本次片段 {filename, content} 以覆盖式 upsert 进
//     kv_settings（key=imported_txt_sources，JSON 数组），随后在**同一事务**
//     内从全部已导入片段重建作者与关联——先删全部相关作者旧关联再插全量
//     新关联，跨 TXT 同名作者关联 = 所有片段匹配文件的并集，不被单 TXT 的
//     删旧插新覆盖（旧项目 rebuildAssociationsFromBlocks 语义）；
//   - 格式 C（纯作者名列表）：只创建作者不关联文件，不触发重建、不存片段
//     （GUIDE_AUTHOR：不触发重建语义）；
//   - 匹配域 = normal 库资产（kind='normal'），COS 资产被 DOMAIN_RULES §6
//     隔离口径排除（查询 ListNormalAssetsForAuthorMatch）；
//   - 新文件入库后的自动重匹配由扫描路径外的本端点承担（重新导入/刷新即
//     全量重建），scanner 不感知 TXT 片段（作者规则与扫描器解耦）。
//
// 删关联目标的口径（removeTxtSource）：regular 作者的关联不全是 TXT 产物
// ——旧项目迁移（import.go）也会写 regular 作者关联，故重建**只能**删
// 「已导入片段涉及作者」的关联（DeleteAssetAuthorsByAuthorIds），不得按
// type='regular' 全删。删除路径以「删除前」全量片段为删关联目标（作者从
// 剩余片段消失时旧关联一并清掉），以「删除后」剩余片段为重建来源。
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strings"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// txtSource 是 kv_settings 中 imported_txt_sources 数组的一个片段
// （重导入同文件名覆盖其 content，旧项目 imported_txt_blocks_<fileName> 的
// 数组化等价物；无分片——HTTP 请求体无 CursorWindow 限制，见 authoring 包注释）。
type txtSource struct {
	Filename string `json:"filename"`
	Content  string `json:"content"`
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

// PostApiV1AuthorsImportTxt 导入作者 TXT：三格式自动识别 → 统一重建 →
// 返回本次导入计数。
func (s *Server) PostApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request) {
	var body gen.PostApiV1AuthorsImportTxtJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.Content) == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "content 不能为空")
		return
	}

	res, err := s.importTxt(r.Context(), body.Filename, body.Content)
	if err != nil {
		s.internalErr(w, "导入作者 TXT", err)
		return
	}
	// 作者-文件关联变了（COS 分流与 cosWork 装配输入），推荐缓存失效
	s.invalidateRecommendCache()
	writeJSON(w, http.StatusOK, res)
}

// importTxt 是导入主流程：块格式走统一重建事务；格式 C 只建作者。
// 计数口径：authorsImported = 本次 TXT 解析出的作者块数（格式 C 为行数）；
// filesMatched = 本次 TXT 的（作者, 去重作品）对匹配到的库内文件总数。
func (s *Server) importTxt(ctx context.Context, filename *string, content string) (gen.TxtImportResult, error) {
	blocks := authoring.ParseAuthorBlocks(content)
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return gen.TxtImportResult{}, err
	}
	defer func() { _ = tx.Rollback() }()
	qtx := s.q.WithTx(tx)

	if len(blocks) == 0 {
		// 格式 C：纯作者名列表——只创建作者，不关联文件、不触发重建。
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

	filesMatched, err := s.rebuildFromAllSources(ctx, qtx, blocks, filename, content)
	if err != nil {
		return gen.TxtImportResult{}, err
	}
	if err := tx.Commit(); err != nil {
		return gen.TxtImportResult{}, err
	}
	imported := len(blocks)
	return gen.TxtImportResult{AuthorsImported: &imported, FilesMatched: &filesMatched}, nil
}

// rebuiltAuthor 是跨片段合并后的重建素材（同名作者 id → 首遇显示名 +
// 作品名集合）。
type rebuiltAuthor struct {
	displayName string
	works       []string
}

// persistTxtSources 把片段数组序列化写回 kv_settings（空数组也写入——
// 删到零片段时保留空壳，GET 返回空列表）。
func (s *Server) persistTxtSources(ctx context.Context, qtx *db.Queries, sources []txtSource) error {
	raw, err := json.Marshal(sources)
	if err != nil {
		return err
	}
	return qtx.UpsertSetting(ctx, db.UpsertSettingParams{
		Key:       authoring.SettingKeyImportedTxtSources,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(s.now()),
	})
}

// mergeTxtSources 全量解析合并：authorId 相同的跨片段作者合并作品集
// （显示名首遇保留——同名作者在多个编号行中合并关联文件，不合并别名列表）。
func mergeTxtSources(sources []txtSource) map[string]*rebuiltAuthor {
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
			matches := authoring.MatchWorks(w, domain)
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
// 最近一份），再对「全部已导入片段」统一重建。返回本次传入 content 的匹配
// 文件数（filesMatched 只统计本次导入的作品，不含历史片段贡献）。
func (s *Server) rebuildFromAllSources(ctx context.Context, qtx *db.Queries, currentBlocks []authoring.AuthorBlock, filename *string, content string) (int, error) {
	sources, err := loadTxtSources(ctx, qtx)
	if err != nil {
		return 0, err
	}
	thisName := ""
	if filename != nil {
		thisName = *filename
	}
	updated := false
	for i := range sources {
		if sources[i].Filename == thisName {
			sources[i].Content = content
			updated = true
			break
		}
	}
	if !updated {
		sources = append(sources, txtSource{Filename: thisName, Content: content})
	}
	if err := s.persistTxtSources(ctx, qtx, sources); err != nil {
		return 0, err
	}

	merged := mergeTxtSources(sources)
	// 本次导入的作品集（filesMatched 计数锚点）。
	countWorks := make(map[string][]string, len(currentBlocks))
	for _, b := range currentBlocks {
		if len(b.AuthorNames) == 0 {
			continue
		}
		id := authoring.GenerateAuthorID(b.AuthorNames[0])
		countWorks[id] = append(countWorks[id], b.Works...)
	}
	return s.rebuildAll(ctx, qtx, merged, merged, countWorks)
}

// errTxtSourceNotFound 是 DELETE 移除不存在的片段时的哨兵错误
// （handler 映射 404；事务内判断，确保移除+重建原子）。
var errTxtSourceNotFound = errors.New("txt source not found")

// removeTxtSource 移除一个片段并从剩余片段统一重建（DELETE 路径，事务内
// 调用）。删关联目标 = 删除前全量片段的作者集——作者只在被删片段出现时，
// 其旧关联随删除清空；重建来源 = 删除后剩余片段。作者行保留不级联删除
// （openapi：重建后零关联的常规作者保留）。
func (s *Server) removeTxtSource(ctx context.Context, qtx *db.Queries, filename string) error {
	before, err := loadTxtSources(ctx, qtx)
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
	remaining := append(append([]txtSource(nil), before[:idx]...), before[idx+1:]...)
	if err := s.persistTxtSources(ctx, qtx, remaining); err != nil {
		return err
	}
	_, err = s.rebuildAll(ctx, qtx, mergeTxtSources(before), mergeTxtSources(remaining), nil)
	return err
}

// loadTxtSources 读全部已导入片段（无记录/空数组 → nil）。
func loadTxtSources(ctx context.Context, qtx *db.Queries) ([]txtSource, error) {
	v, err := qtx.GetSetting(ctx, authoring.SettingKeyImportedTxtSources)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var sources []txtSource
	if err := json.Unmarshal([]byte(v), &sources); err != nil {
		return nil, err
	}
	return sources, nil
}

// GetApiV1AuthorsImportTxt 已导入的 TXT 文件名列表（旧版数据管理
// 「TXT导入作者」卡片；按文件名升序，匿名导入的空名排最前）。
func (s *Server) GetApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request) {
	sources, err := loadTxtSources(r.Context(), s.q)
	if err != nil {
		s.internalErr(w, "查询已导入 TXT", err)
		return
	}
	names := make([]string, 0, len(sources))
	for _, src := range sources {
		names = append(names, src.Filename)
	}
	sort.Strings(names)
	out := make([]gen.TxtImportedFile, 0, len(names))
	for _, n := range names {
		out = append(out, gen.TxtImportedFile{Filename: n})
	}
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
	w.WriteHeader(http.StatusNoContent)
}

// PostApiV1AuthorsImportTxtRebuild 重放全部已导入 TXT 片段并重建常规作者-文件
// 关联（库重建/关联意外丢失后的修复入口）。幂等：片段内容不变，不调
// persistTxtSources 写回，只按已存片段并集删旧关联再全量重插；无片段时
// 直接返回零值。计数口径：authorsImported=片段合并后的作者数；
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

	sources, err := loadTxtSources(r.Context(), qtx)
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
