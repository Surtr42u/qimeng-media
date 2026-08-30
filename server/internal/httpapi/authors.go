// authors.go：作者体系端点（M3，DOMAIN_RULES §6）。
//
// 三个端点：作者列表（常规与 COS 统一返回）、TXT 导入（三格式自动识别 +
// 统一重建语义）、关注置位/取消。
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
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
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
		followed := row.Followed == 1
		out = append(out, gen.Author{
			Id:          &row.ID,
			DisplayName: &row.DisplayName,
			Type:        &t,
			FileCount:   &fileCount,
			Followed:    &followed,
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
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "content 不能为空")
		return
	}

	res, err := s.importTxt(r.Context(), body.Filename, body.Content)
	if err != nil {
		s.internalErr(w, "导入作者 TXT", err)
		return
	}
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

// rebuildFromAllSources 统一重建（事务内调用）：
// 存片段 → 全量解析合并 → upsert 作者 → 删相关作者旧关联 → 按作品名匹配
// normal 库资产重插关联。返回「本次传入 content」的匹配文件数（计数只算
// 本次导入的作品，不含历史片段贡献——响应是"本次导入结果统计"）。
func (s *Server) rebuildFromAllSources(ctx context.Context, qtx *db.Queries, currentBlocks []authoring.AuthorBlock, filename *string, content string) (int, error) {
	// 1) 覆盖式 upsert 本次片段（同 filename 覆盖内容；filename 空串也是
	// 一个合法键——匿名导入只保留最近一份）。
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
	raw, err := json.Marshal(sources)
	if err != nil {
		return 0, err
	}
	if err := qtx.UpsertSetting(ctx, db.UpsertSettingParams{
		Key:       authoring.SettingKeyImportedTxtSources,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		return 0, err
	}

	// 2) 全量解析合并：authorId 相同的跨片段作者合并作品集（显示名首遇
	// 保留——同名作者在多个编号行中合并关联文件，不合并别名列表）。
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
	// 本次导入的作品集（计数范围锚点：filesMatched 只统计本次 TXT 的作品
	// 匹配到的文件，不含历史片段贡献——响应是"本次导入结果统计"）。
	currentWorks := make(map[string][]string, len(currentBlocks))
	for _, b := range currentBlocks {
		if len(b.AuthorNames) == 0 {
			continue
		}
		id := authoring.GenerateAuthorID(b.AuthorNames[0])
		currentWorks[id] = append(currentWorks[id], b.Works...)
	}

	// 3) upsert 作者 + 删全部相关作者旧关联（统一重建先删后插）。
	now := store.FormatTimestamp(s.now())
	ids := make([]string, 0, len(merged))
	for id, ra := range merged {
		if err := qtx.UpsertAuthor(ctx, db.UpsertAuthorParams{
			ID:          id,
			DisplayName: ra.displayName,
			Type:        authoring.AuthorTypeRegular,
			CreatedAt:   now,
		}); err != nil {
			return 0, err
		}
		ids = append(ids, id)
	}
	if err := qtx.DeleteAssetAuthorsByAuthorIds(ctx, ids); err != nil {
		return 0, err
	}

	// 4) 匹配域装载一次（normal 库全部资产），逐作者逐作品匹配重插。
	rows, err := qtx.ListNormalAssetsForAuthorMatch(ctx)
	if err != nil {
		return 0, err
	}
	domain := make([]authoring.MediaFile, 0, len(rows))
	for _, row := range rows {
		domain = append(domain, authoring.MediaFile{AssetID: row.AssetID, FileName: row.FileName})
	}

	filesMatched := 0
	for _, id := range ids {
		ra := merged[id]
		thisSet := make(map[string]bool, len(currentWorks[id]))
		for _, w := range currentWorks[id] {
			thisSet[w] = true
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
			if thisSet[w] {
				filesMatched += len(matches)
			}
		}
	}
	return filesMatched, nil
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
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "作者不存在")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
