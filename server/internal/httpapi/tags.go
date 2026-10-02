// tags.go：标签池、资产标签绑定（替换式）、时间轴标签端点。
// 领域语义见 docs/DOMAIN_RULES §7：标签池全局唯一命名、删除级联清理
// 关联、PUT 绑定 = 整体替换。
package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"regexp"
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
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "标签名不能为空")
		return
	}
	if _, err := s.q.GetTagByName(r.Context(), name); err == nil {
		writeErr(w, http.StatusConflict, codeTagExists, "同名标签已存在")
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
		writeErr(w, http.StatusNotFound, codeNotFound, "标签不存在")
		return
	} else if err != nil {
		s.internalErr(w, "查询标签", err)
		return
	}
	// 受影响资产名单先取（§10 级联清关联随动；删除后关联行消失无法回溯）。
	affectedAssetIDs, err := s.q.ListAssetIDsByTag(r.Context(), tagID)
	if err != nil {
		s.internalErr(w, "查询标签关联资产", err)
		return
	}
	if err := s.q.DeleteTag(r.Context(), tagID); err != nil {
		s.internalErr(w, "删除标签", err)
		return
	}
	// 级联清关联也是标签组变更（§10）：受影响资产逐一随动。名单必须在删除前取
	// （删除后 asset_tags 行已级联消失，无法回溯受影响集）。
	for _, id := range affectedAssetIDs {
		if err := s.touchAssetTagSetNow(r.Context(), id); err != nil {
			s.internalErr(w, "更新标签组改动时间", err)
			return
		}
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
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
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
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "标签不存在: "+id)
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
			// 关联时间 = 本次替换时刻（LEGACY_REQUIREMENTS §A：详情页标签
			// 按"最近添加置顶"；替换式 PUT 重插每行，重添加即置顶）。
			CreatedAt: store.FormatTimestamp(s.now()),
			// 溯源章（ADR-0032）：客户端端点写入；web/app 共用端点统一盖
			// client，服务端不做 UA 猜测。
			Origin: store.OriginClient,
		}); err != nil {
			s.internalErr(w, "挂载资产标签", err)
			return
		}
	}
	// 标签组改动时间随动（§10）：与清空/挂载同事务，时间与内容原子一致。
	if err := qtx.TouchAssetTagSet(r.Context(), db.TouchAssetTagSetParams{
		AssetID:         assetID.String(),
		TagSetUpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "更新标签组改动时间", err)
		return
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "提交标签替换", err)
		return
	}
	s.publishLibraryChanged()
	w.WriteHeader(http.StatusNoContent)
}

// touchAssetTagSetNow 标签组改动时间随动（migration 0012，DOMAIN_RULES §10
// 标签组同步语义）：任何 asset_tags 变更路径都必须调用——导入端靠它判定
// 「备份与库内谁新」。时间恒取服务器当前时刻（导入替换路径除外，写备份时刻）。
func (s *Server) touchAssetTagSetNow(ctx context.Context, assetID string) error {
	return s.q.TouchAssetTagSet(ctx, db.TouchAssetTagSetParams{
		AssetID:         assetID,
		TagSetUpdatedAt: store.FormatTimestamp(s.now()),
	})
}

// timelineTagColorRe 颜色协议形态（openapi pattern 同款）：hex 6 位。
var timelineTagColorRe = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)

// normalizeTimelineColor 校验并归一 color 入参（协议批 P2，DOMAIN_RULES §7）：
// nil/空串 → ""（不设置/清除，落库空串哨兵 = 响应省略字段）；合法 hex6 →
// 原值；非法 → 返回 false（调用方 400，防垃圾值入库）。
func normalizeTimelineColor(raw *string) (string, bool) {
	if raw == nil || *raw == "" {
		return "", true
	}
	if !timelineTagColorRe.MatchString(*raw) {
		return "", false
	}
	return *raw, true
}

// genTimelineTag db 行 → 协议对象：color 空串哨兵 → 字段省略
// （客户端自兜底，DOMAIN_RULES §7「缺省 = 服务端不给」）。
func genTimelineTag(t db.TimelineTag) gen.TimelineTag {
	id, name := t.ID, t.Name
	ms := t.TimeMillis
	out := gen.TimelineTag{Id: &id, TimeMillis: &ms, Name: &name}
	if t.Color != "" {
		c := t.Color
		out.Color = &c
	}
	return out
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
		items = append(items, genTimelineTag(t))
	}
	writeJSON(w, http.StatusOK, items)
}

// PostApiV1AssetsAssetIdTimelineTags 添加时间轴标签（color 可选）。
func (s *Server) PostApiV1AssetsAssetIdTimelineTags(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	var req struct {
		TimeMillis int64   `json:"timeMillis"`
		Name       string  `json:"name"`
		Color      *string `json:"color"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" || req.TimeMillis < 0 {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "timeMillis 与 name 必填（时间点非负）")
		return
	}
	color, ok := normalizeTimelineColor(req.Color)
	if !ok {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "color 须为 hex 6 位（如 #d6336c）或空")
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
		Color:      color,
		CreatedAt:  store.FormatTimestamp(s.now()),
	})
	if err != nil {
		s.internalErr(w, "创建时间轴标签", err)
		return
	}
	writeJSON(w, http.StatusCreated, genTimelineTag(t))
}

// PutApiV1AssetsAssetIdTimelineTagsTagId 更新时间轴标签（协议批 P2 新增：
// 改名/改色/改时间点的入口）。全量替换语义：timeMillis/name 必填（校验同
// POST），color 省略或空串 = 清除颜色。tagId 不属于该资产或不存在 → 404。
func (s *Server) PutApiV1AssetsAssetIdTimelineTagsTagId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId, tagID string) {
	var req struct {
		TimeMillis int64   `json:"timeMillis"`
		Name       string  `json:"name"`
		Color      *string `json:"color"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" || req.TimeMillis < 0 {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "timeMillis 与 name 必填（时间点非负）")
		return
	}
	color, ok := normalizeTimelineColor(req.Color)
	if !ok {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "color 须为 hex 6 位（如 #d6336c）或空")
		return
	}
	if err := s.ensureAsset(w, r, assetID.String()); err != nil {
		return
	}
	t, err := s.q.UpdateTimelineTag(r.Context(), db.UpdateTimelineTagParams{
		TimeMillis: req.TimeMillis, Name: name, Color: color,
		ID: tagID, AssetID: assetID.String(),
	})
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "时间轴标签不存在")
		return
	} else if err != nil {
		s.internalErr(w, "更新时间轴标签", err)
		return
	}
	writeJSON(w, http.StatusOK, genTimelineTag(t))
}

// DeleteApiV1AssetsAssetIdTagsTag 逐条解除单标签关联（协议批 P2，
// DOMAIN_RULES §7）：只删该条，其余关联 created_at 不动（区别于整体替换
// PUT 会刷新全部关联时间）。幂等：标签存在但未挂载 → 204；资产不存在
// 或标签池无此名 → 404（拼错标签名不静默吞掉）。
func (s *Server) DeleteApiV1AssetsAssetIdTagsTag(w http.ResponseWriter, r *http.Request, assetID gen.AssetId, tag string) {
	if err := s.ensureAsset(w, r, assetID.String()); err != nil {
		return
	}
	if _, err := s.q.GetTagByName(r.Context(), tag); errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "标签不存在")
		return
	} else if err != nil {
		s.internalErr(w, "查询标签", err)
		return
	}
	n, err := s.q.RemoveAssetTagByName(r.Context(), db.RemoveAssetTagByNameParams{
		AssetID: assetID.String(), Name: tag,
	})
	if err != nil {
		s.internalErr(w, "解除标签关联", err)
		return
	}
	if n > 0 {
		// 单关联解绑也是标签组变更（§10 随动）；n=0 = 本就未挂载，集合未变不触发。
		if err := s.touchAssetTagSetNow(r.Context(), assetID.String()); err != nil {
			s.internalErr(w, "更新标签组改动时间", err)
			return
		}
		s.publishLibraryChanged()
	}
	w.WriteHeader(http.StatusNoContent)
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
		writeErr(w, http.StatusNotFound, codeNotFound, "时间轴标签不存在")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ensureAsset 资产存在性检查（时间轴标签两用例共用）：失败时响应已写、
// 返回非 nil，调用方直接 return。
func (s *Server) ensureAsset(w http.ResponseWriter, r *http.Request, assetID string) error {
	_, err := s.q.GetAsset(r.Context(), assetID)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
		return err
	}
	if err != nil {
		s.internalErr(w, "查询资产", err)
		return err
	}
	return nil
}
