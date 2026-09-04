// assets_detail.go：资产详情端点（GET /assets/{assetId}）。
//
// 详情 = 基础行 + 关联三查询（标签/作者/角色）+ 统计七查询 + 签名直链
// 组装。主 handler 只做编排，两组关联查询分别收敛在 fetchAssetRefs /
// fetchAssetStats；列表端点与浏览侧共享小助手（游标、筛选、buildSummary
// 等）在 assets.go。
package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// fetchAssetRefs 收敛详情页的"标签/作者/角色"三查询与 gen 模型装配。
// 失败时 what 回传失败阶段名，调用方交给 internalErr 拼"<what>失败"的
// 日志文案——保持拆分前逐查询独立文案的日志可定位性。
func (s *Server) fetchAssetRefs(ctx context.Context, assetID string) (tags []gen.Tag, authors []gen.Author, charNames []string, what string, err error) {
	// 标签 / 作者 / 角色
	tagRows, err := s.q.ListAssetTagRefs(ctx, assetID)
	if err != nil {
		return nil, nil, nil, "查询资产标签", err
	}
	tags = make([]gen.Tag, 0, len(tagRows))
	for _, t := range tagRows {
		fc := 0 // 关联文件数：标签池级统计属 /tags 端点职责，详情处无意义
		id, name := t.ID, t.Name
		tags = append(tags, gen.Tag{Id: &id, Name: &name, FileCount: &fc})
	}
	authorRows, err := s.q.ListAssetAuthorRefs(ctx, assetID)
	if err != nil {
		return nil, nil, nil, "查询资产作者", err
	}
	authors = make([]gen.Author, 0, len(authorRows))
	for _, a := range authorRows {
		fc := 0
		id, name := a.ID, a.DisplayName
		at := gen.AuthorType(a.Type)
		authors = append(authors, gen.Author{Id: &id, DisplayName: &name, Type: &at, FileCount: &fc})
	}
	if charNames, err = s.q.ListAssetCharacterNames(ctx, assetID); err != nil {
		return nil, nil, nil, "查询资产角色", err
	}
	return tags, authors, charNames, "", nil
}

// assetStats 承载详情页的统计聚合值（fetchAssetStats 的返回体）。
// 六项原本是主函数里的散局部变量，拆函数后由小结构体一次带回。
type assetStats struct {
	viewCount  int   // 浏览数（open 事件计数）
	playCount  int   // 播放数（play 事件计数）
	lastViewed any   // 最近浏览时间；MAX 的 sqlc 抹平标量（消费侧断言 string）
	seconds    any   // 累计停留秒数；SUM 的 sqlc 抹平标量（消费侧 toInt）
	likeCount  int64 // 点赞数
	likedToday int64 // 当日已赞（likes 表当日行存在性，1=已赞；与点赞端点 HasLikedOnDay 同口径）
	isFav      int64 // 收藏态（1=已收藏；消费侧转 bool）
}

// fetchAssetStats 收敛详情页的统计查询（view/play 计数、最近浏览、
// 累计停留秒、点赞、当日点赞态、收藏）。what 的语义同 fetchAssetRefs：
// 失败阶段名，供 internalErr 写日志。
func (s *Server) fetchAssetStats(ctx context.Context, assetID string) (st assetStats, what string, err error) {
	// 统计：view/play 计数、最近浏览、累计停留秒、点赞、收藏
	cntRows, err := s.q.CountAssetEvents(ctx, assetID)
	if err != nil {
		return st, "聚合资产事件", err
	}
	for _, c := range cntRows {
		switch c.Kind {
		case "open":
			st.viewCount = int(c.Cnt)
		case "play":
			st.playCount = int(c.Cnt)
		}
	}
	if st.lastViewed, err = s.q.LastViewedAt(ctx, assetID); err != nil {
		return st, "查询最近浏览时间", err
	}
	if st.seconds, err = s.q.SumBrowseSeconds(ctx, assetID); err != nil {
		return st, "累计停留秒数", err
	}
	if st.likeCount, err = s.q.CountAssetLikes(ctx, assetID); err != nil {
		return st, "统计点赞数", err
	}
	// likedToday（点赞按钮初始态）：直接复用点赞端点 PutApiV1AssetsAssetIdLike
	// 的判定查询与 day 口径（本地日历日，DOMAIN_RULES §5），不另立口径。
	if st.likedToday, err = s.q.HasLikedOnDay(ctx, db.HasLikedOnDayParams{
		AssetID: assetID, Day: store.FormatDay(s.now()),
	}); err != nil {
		return st, "查询当日点赞态", err
	}
	if st.isFav, err = s.q.IsFavorite(ctx, assetID); err != nil {
		return st, "查询收藏态", err
	}
	return st, "", nil
}

// GetApiV1AssetsAssetId 资产详情：组装全部关联数据与签名直链。
func (s *Server) GetApiV1AssetsAssetId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	row, err := s.q.GetAssetWithLibrary(r.Context(), assetID.String())
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询资产失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	ctx := r.Context()

	tags, authors, charNames, what, err := s.fetchAssetRefs(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, what, err)
		return
	}
	st, what, err := s.fetchAssetStats(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, what, err)
		return
	}

	// 签名直链（exp 默认 6h；orig 永不发转码副本，thumb 用大图档）。
	// gen.AssetDetail 是 allOf 展平后的单层结构，先把 Summary 基础字段
	// 复制过来再补扩展字段。
	base := buildSummary(s, row.AssetID, row.FileName, row.MediaType, row.SizeBytes,
		row.Mtime, row.CreatedAt, row.Source, st.isFav > 0, st.likeCount, nil, nil)
	detail := gen.AssetDetail{
		// AssetSummary 基础字段（allOf 展开）
		AddedAt:    base.AddedAt,
		FileName:   base.FileName,
		Id:         base.Id,
		IsFavorite: base.IsFavorite,
		LikeCount:  base.LikeCount,
		LikedToday: ptr(st.likedToday > 0),
		MediaType:  base.MediaType,
		ModifiedAt: base.ModifiedAt,
		SizeBytes:  base.SizeBytes,
		Source:     base.Source,
		// AssetDetail 扩展字段
		LibraryId:          ptr(row.LibraryID),
		Authors:            &authors,
		Characters:         &charNames,
		Tags:               &tags,
		Directory:          ptr(dirOf(row.RelPath)),
		RelPath:            ptr(row.RelPath),
		ViewCount:          ptr(st.viewCount),
		PlayCount:          ptr(st.playCount),
		TotalBrowseSeconds: ptr(toInt(st.seconds)),
	}
	orig := s.signedMediaURL(mediaPathOrig + row.AssetID)
	detail.OrigUrl = &orig
	thumb := s.thumbURL(row.AssetID, "lg")
	detail.ThumbUrl = &thumb
	if row.DurationMs.Valid {
		detail.DurationMs = ptr(row.DurationMs.Int64)
	}
	if row.Width.Valid {
		detail.Width = ptr(int(row.Width.Int64))
	}
	if row.Height.Valid {
		detail.Height = ptr(int(row.Height.Int64))
	}
	if row.LastPositionSeconds.Valid {
		detail.LastPositionSeconds = ptr(float32(row.LastPositionSeconds.Float64))
	}
	if row.VideoCodec.Valid {
		detail.VideoCodec = ptr(row.VideoCodec.String)
	}
	if row.AudioCodec.Valid {
		detail.AudioCodec = ptr(row.AudioCodec.String)
	}
	if lv, ok := st.lastViewed.(string); ok && lv != "" {
		t := parseStoreTime(lv)
		detail.LastViewedAt = &t
	}
	writeJSON(w, http.StatusOK, detail)
}
