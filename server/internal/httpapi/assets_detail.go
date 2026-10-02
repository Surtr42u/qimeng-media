// assets_detail.go：资产详情端点（GET /assets/{assetId}）。
//
// 详情 = 基础行 + 关联三查询（标签/作者/角色）+ 统计七查询 + 签名直链
// 组装。主 handler 只做编排，两组关联查询分别收敛在 fetchAssetRefs /
// fetchAssetStats；列表端点与浏览侧共享小助手按职责拆在
// assets_filters.go（游标/筛选）、assets_list.go（buildSummary）、
// assets_media_url.go（签名直链/标量抹平）。
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
// 溯源两字段（origin/createdAtMillis）为关联行证据的纯透传（ADR-0032
// 详情面读取，DOMAIN_RULES §10 裁决语义不涉读取面、口径零变化）。
func (s *Server) fetchAssetRefs(ctx context.Context, assetID string) (tags []gen.AssetDetailTag, authors []gen.AssetDetailAuthor, charNames []string, what string, err error) {
	// 标签 / 作者 / 角色
	tagRows, err := s.q.ListAssetTagRefs(ctx, assetID)
	if err != nil {
		return nil, nil, nil, "查询资产标签", err
	}
	tags = make([]gen.AssetDetailTag, 0, len(tagRows))
	for _, t := range tagRows {
		fc := 0 // 关联文件数：标签池级统计属 /tags 端点职责，详情处无意义
		id, name := t.ID, t.Name
		// Origin 恒透传（列 NOT NULL，含 legacy 哨兵=不可考，前端词表映射
		// 决定是否展示）；时间经 provenanceMillis 归一不可考哨兵。
		tags = append(tags, gen.AssetDetailTag{
			Id: &id, Name: &name, FileCount: &fc,
			Origin:          ptr(t.Origin),
			CreatedAtMillis: provenanceMillis(sql.NullString{String: t.CreatedAt, Valid: true}),
		})
	}
	authorRows, err := s.q.ListAssetAuthorRefs(ctx, assetID)
	if err != nil {
		return nil, nil, nil, "查询资产作者", err
	}
	authors = make([]gen.AssetDetailAuthor, 0, len(authorRows))
	for _, a := range authorRows {
		fc := 0
		id, name := a.ID, a.DisplayName
		at := gen.AssetDetailAuthorType(a.Type)
		authors = append(authors, gen.AssetDetailAuthor{
			Id: &id, DisplayName: &name, Type: &at, FileCount: &fc,
			Origin:          ptr(a.Origin),
			CreatedAtMillis: provenanceMillis(a.CreatedAt),
		})
	}
	if charNames, err = s.q.ListAssetCharacterNames(ctx, assetID); err != nil {
		return nil, nil, nil, "查询资产角色", err
	}
	return tags, authors, charNames, "", nil
}

// provenanceMillis 关联行时间戳 → Unix 毫秒指针（ADR-0032 详情面读取）。
// 不可考哨兵归一为 nil（协议 null/缺省=不可考）：asset_authors.created_at
// 的 NULL（0016 存量行）、asset_tags.created_at 的 epoch（0004 既有哨兵，
// UnixMilli=0）。口径对齐 DOMAIN_RULES §10「≤0 = 缺省/不可考」——解析失败
// 同样不伪造，绝不落 1970 纪元字面量。
func provenanceMillis(ts sql.NullString) *int64 {
	if !ts.Valid {
		return nil
	}
	t := parseStoreTime(ts.String)
	if t.IsZero() {
		return nil
	}
	if ms := t.UnixMilli(); ms > 0 {
		return &ms
	}
	return nil
}

// assetStats 承载详情页的统计聚合值（fetchAssetStats 的返回体）。
// 七项原本是主函数里的散局部变量，拆函数后由小结构体一次带回。
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
	// 统计：view/play 计数、最近浏览、累计停留秒、点赞、当日点赞态、收藏
	cntRows, err := s.q.CountAssetEvents(ctx, assetID)
	if err != nil {
		return st, "聚合资产事件", err
	}
	for _, c := range cntRows {
		switch c.Kind {
		case string(gen.Open):
			st.viewCount = int(c.Cnt)
		case string(gen.Play):
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

// fetchAssetCosWork 取单资产的 COS 作品子目录名（detail 响应 cosWork 字段
// 的数据源）。不另立查询口径：复用列表端点 fillListCosWork 同款
// ListCosWorkForAssets（IN 形式，这里只放本资产一个元素），其
// cos_work IS NOT NULL 过滤使常规库/无作品子目录资产天然返回空串
// （协议 null 语义 = 客户端回退 fileName）。展示性字段：查询失败记日志
// 返回空串降级，不阻塞详情响应——与列表端点对该字段的失败策略一致。
func (s *Server) fetchAssetCosWork(ctx context.Context, assetID string) string {
	rows, err := s.q.ListCosWorkForAssets(ctx, jsonString([]string{assetID}))
	if err != nil {
		s.logger.Error("查询资产 COS 作品名失败", "err", err)
		return ""
	}
	if len(rows) == 0 {
		return ""
	}
	return rows[0].CosWork.String
}

// GetApiV1AssetsAssetId 资产详情：组装全部关联数据与签名直链。
func (s *Server) GetApiV1AssetsAssetId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	row, err := s.q.GetAssetWithLibrary(r.Context(), assetID.String())
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询资产失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
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
	// cosWork（AssetSummary 继承字段）：detail 响应此前漏装——客户端
	// 详情页标题因此恒回退 fileName（台账 #15）。空串 = 无作品子目录，
	// 保持字段缺省即协议 null 语义。
	if w := s.fetchAssetCosWork(ctx, row.AssetID); w != "" {
		detail.CosWork = &w
	}
	orig := s.signedMediaURL(mediaPathOrig + row.AssetID)
	detail.OrigUrl = &orig
	thumb := s.thumbURL(row.AssetID, "lg")
	detail.ThumbUrl = &thumb
	// thumbUrlMd（2026-09-18 协议新增）：md 档签名直链。回填只预生成 md 档
	//（thumbnail_warmup 只投 SizeGrid），网格浏览过的资产 md 必在盘上；客户端
	// 详情海报先用 md 立即显示、lg 就绪后换上——lg 首开要现场跑 ffmpeg 全管线
	//（秒级起步 + genSlots 排队），此前整屏灰块干等就是这条链（DOMAIN_RULES
	// §11 档位分工：md=网格/详情快出档，lg=详情最终档）。
	thumbMd := s.thumbURL(row.AssetID, "md")
	detail.ThumbUrlMd = &thumbMd
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
		detail.LastPositionSeconds = ptr(row.LastPositionSeconds.Float64)
	}
	if row.VideoCodec.Valid {
		detail.VideoCodec = ptr(row.VideoCodec.String)
	}
	if row.AudioCodec.Valid {
		detail.AudioCodec = ptr(row.AudioCodec.String)
	}
	if lv, ok := st.lastViewed.(string); ok && lv != "" {
		// R8（审计清偿批）：协议统一毫秒整型（与 HistoryItem 同型）；解析失败
		// （零值）省略字段而非落 1970 前的负毫秒。
		if t := parseStoreTime(lv); !t.IsZero() {
			ms := t.UnixMilli()
			detail.LastViewedAt = &ms
		}
	}
	writeJSON(w, http.StatusOK, detail)
}
