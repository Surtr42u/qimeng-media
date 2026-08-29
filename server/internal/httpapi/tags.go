// tags.go：标签池、资产标签绑定（替换式）、时间轴标签端点。
// 领域语义见 docs/DOMAIN_RULES §7：标签池全局唯一命名、删除级联清理
// 关联、PUT 绑定 = 整体替换。
package httpapi

import (
	"database/sql"
	"errors"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1Tags 标签池（含每标签关联文件数）。
func (s *Server) GetApiV1Tags(w http.ResponseWriter, r *http.Request) {
	rows, err := s.q.ListTags(r.Context())
	if err != nil {
		s.internalErr(w, "查询标签池", err)
		return
	}
	items := make([]gen.Tag, 0, len(rows))
	for _, t := range rows {
		id, name := t.ID, t.Name
		fc := int(t.FileCount)
		items = append(items, gen.Tag{Id: &id, Name: &name, FileCount: &fc})
	}
	writeJSON(w, http.StatusOK, items)
}

// PostApiV1Tags 新建标签。重名 409（tags.name 唯一约束的显式前置检查，
// 让客户端拿到 Conflict 语义而不是 500 约束错误）。
func (s *Server) PostApiV1Tags(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name string `json:"name"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "标签名不能为空")
		return
	}
	if _, err := s.q.GetTagByName(r.Context(), name); err == nil {
		writeErr(w, http.StatusConflict, "TAG_EXISTS", "同名标签已存在")
		return
	}
	t, err := s.q.CreateTag(r.Context(), db.CreateTagParams{
		ID:        uuid.NewString(),
		Name:      name,
		CreatedAt: store.FormatTimestamp(s.now()),
	})
	if err != nil {
		s.internalErr(w, "创建标签", err)
		return
	}
	id := t.ID
	fc := 0
	writeJSON(w, http.StatusCreated, gen.Tag{Id: &id, Name: &name, FileCount: &fc})
}

// DeleteApiV1TagsTagId 删除标签（外键级联清理全部资产绑定；媒体文件不动）。
func (s *Server) DeleteApiV1TagsTagId(w http.ResponseWriter, r *http.Request, tagID string) {
	if _, err := s.q.GetTag(r.Context(), tagID); errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "标签不存在")
		return
	} else if err != nil {
		s.internalErr(w, "查询标签", err)
		return
	}
	if err := s.q.DeleteTag(r.Context(), tagID); err != nil {
		s.internalErr(w, "删除标签", err)
		return
	}
	s.publishLibraryChanged()
	w.WriteHeader(http.StatusNoContent)
}

// PutApiV1AssetsAssetIdTags 替换资产标签集合。
//
// 事务语义：先清空后批量挂载，中途失败整体回滚——绝不允许"清空成功、
// 挂载一半失败"把标签集合删残。tagIds 引用不存在的标签 → 400（挂载
// 前逐个校验，失败信息指明具体 ID，前端能定位是哪个选择项过期了）。
func (s *Server) PutApiV1AssetsAssetIdTags(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	var req struct {
		TagIds []string `json:"tagIds"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	if _, err := s.q.GetAsset(r.Context(), assetID.String()); errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
		return
	} else if err != nil {
		s.internalErr(w, "查询资产", err)
		return
	}
	// 去重：重复 ID 挂载两次被复合主键拒绝，提前收敛幂等。
	seen := make(map[string]struct{}, len(req.TagIds))
	ids := make([]string, 0, len(req.TagIds))
	for _, id := range req.TagIds {
		if id == "" {
			continue
		}
		if _, dup := seen[id]; dup {
			continue
		}
		seen[id] = struct{}{}
		ids = append(ids, id)
	}
	for _, id := range ids {
		if _, err := s.q.GetTag(r.Context(), id); errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "标签不存在: "+id)
			return
		} else if err != nil {
			s.internalErr(w, "查询标签", err)
			return
		}
	}
	tx, err := s.conn.BeginTx(r.Context(), nil)
	if err != nil {
		s.internalErr(w, "开启事务", err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	qtx := s.q.WithTx(tx)
	if err := qtx.DeleteAssetTags(r.Context(), assetID.String()); err != nil {
		s.internalErr(w, "清空资产标签", err)
		return
	}
	for _, id := range ids {
		if err := qtx.AddAssetTag(r.Context(), db.AddAssetTagParams{
			AssetID: assetID.String(), TagID: id,
		}); err != nil {
			s.internalErr(w, "挂载资产标签", err)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "提交标签替换", err)
		return
	}
	s.publishLibraryChanged()
	w.WriteHeader(http.StatusNoContent)
}

// GetApiV1AssetsAssetIdTimelineTags 时间轴标签列表（按时间点升序）。
func (s *Server) GetApiV1AssetsAssetIdTimelineTags(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	if err := s.ensureAsset(w, r, assetID.String()); err != nil {
		return // 响应已写
	}
	rows, err := s.q.ListTimelineTags(r.Context(), assetID.String())
	if err != nil {
		s.internalErr(w, "查询时间轴标签", err)
		return
	}
	items := make([]gen.TimelineTag, 0, len(rows))
	for _, t := range rows {
		id, name := t.ID, t.Name
		ms := t.TimeMillis
		items = append(items, gen.TimelineTag{Id: &id, TimeMillis: &ms, Name: &name})
	}
	writeJSON(w, http.StatusOK, items)
}

// PostApiV1AssetsAssetIdTimelineTags 添加时间轴标签。
func (s *Server) PostApiV1AssetsAssetIdTimelineTags(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	var req struct {
		TimeMillis int64  `json:"timeMillis"`
		Name       string `json:"name"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" || req.TimeMillis < 0 {
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "timeMillis 与 name 必填（时间点非负）")
		return
	}
	if err := s.ensureAsset(w, r, assetID.String()); err != nil {
		return
	}
	t, err := s.q.InsertTimelineTag(r.Context(), db.InsertTimelineTagParams{
		ID:         uuid.NewString(),
		AssetID:    assetID.String(),
		TimeMillis: req.TimeMillis,
		Name:       name,
		CreatedAt:  store.FormatTimestamp(s.now()),
	})
	if err != nil {
		s.internalErr(w, "创建时间轴标签", err)
		return
	}
	id := t.ID
	writeJSON(w, http.StatusCreated, gen.TimelineTag{Id: &id, TimeMillis: &req.TimeMillis, Name: &name})
}

// DeleteApiV1AssetsAssetIdTimelineTagsTagId 删除时间轴标签（tagId 必须属于
// 该资产，SQL 双条件限定；影响行数 0 = 条目不存在 → 404）。
func (s *Server) DeleteApiV1AssetsAssetIdTimelineTagsTagId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId, tagID string) {
	n, err := s.q.DeleteTimelineTag(r.Context(), db.DeleteTimelineTagParams{
		ID: tagID, AssetID: assetID.String(),
	})
	if err != nil {
		s.internalErr(w, "删除时间轴标签", err)
		return
	}
	if n == 0 {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "时间轴标签不存在")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ensureAsset 资产存在性检查（时间轴标签两用例共用）：失败时响应已写、
// 返回非 nil，调用方直接 return。
func (s *Server) ensureAsset(w http.ResponseWriter, r *http.Request, assetID string) error {
	_, err := s.q.GetAsset(r.Context(), assetID)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
		return err
	}
	if err != nil {
		s.internalErr(w, "查询资产", err)
		return err
	}
	return nil
}
