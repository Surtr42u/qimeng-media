// history.go：观看历史端点（M3，DOMAIN_RULES §8 浏览面）。
//
// 历史 = 每资产最近一次 kind='open' 的 ViewEvent 时间倒序，每资产一条
// （GROUP BY asset_id 取 MAX 时间）；已删资产（事件流无 FK，ADR-0005）
// 经 FROM assets 锚定自然排除。COS 分区缺省全部 = 常规∪COS 合并
// （2026-09-05 用户拍板，DOMAIN_RULES §6），显式 includeCos=false 切常规
// 分区、cosOnly=true 切 COS 分区（三态开关与 /assets 同一套）；其余筛选
// （mediaType/work/character）与 GET /assets 同名参数同语义。
// 分页 keyset (last_viewed_at, asset_id)，游标形态复用 assets.go 的
// encodeCursor/decodeCursor（k/i 即上一页最后一行的时间串与资产 ID，
// 与前页可稳定续读）。
package httpapi

import (
	"database/sql"
	"net/http"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1History 观看历史分页列表（最近浏览在前）。
// 超函数警戒线（>100 行）理由：oapi-codegen 生成的接口签名 + 单请求
// 直线流（参数归一→SQL→装配），与 GetApiV1Assets 同型的编排壳。
func (s *Server) GetApiV1History(w http.ResponseWriter, r *http.Request, params gen.GetApiV1HistoryParams) {
	limit, ok := resolvePageLimit(w, params.Limit)
	if !ok {
		return
	}
	// COS 分区三态（2026-09-05 用户拍板）：/history 缺省 = 全部——
	// includeCos 缺省（nil）映射 1（常规∪COS 合并），显式 false 映射 0
	// （常规分区，排除 COS）；cosOnly=true 映射 COS 分区且优先（同
	// /assets 口径）。协议侧 openapi /history includeCos schema default=true
	// 须与此处同步，反之亦然（注意 /assets 侧 default=false，勿混）。
	includeCos := int64(1)
	if params.IncludeCos != nil && !*params.IncludeCos {
		includeCos = 0
	}
	cosOnly := int64(0)
	if params.CosOnly != nil && *params.CosOnly {
		cosOnly = 1
		includeCos = 0
	}
	// 与 GET /assets 同名参数同语义的多维筛选（browse.sql 同型谓词；
	// 2026-09-09 协议批：source/character/work 多值数组、新增 authorId）。
	var mediaType any
	if params.MediaType != nil {
		mediaType = nullStr(string(*params.MediaType))
	}
	var cosWorksJson any
	if params.Work != nil {
		var works []string
		for _, w := range *params.Work {
			if w != "" {
				works = append(works, w)
			}
		}
		cosWorksJson = jsonString(works)
	}
	var charactersJson any
	if params.Character != nil && len(*params.Character) > 0 {
		// 角色多值：每元素 'a+b' 组合出镜（组内 AND），数组间 OR——
		// 编成「组合的数组」，谓词见 history.sql（browse.sql 同型）。
		combos := make([][]string, 0, len(*params.Character))
		for _, c := range *params.Character {
			combos = append(combos, splitCharacters(c))
		}
		charactersJson = jsonValue(combos)
	}
	// 多值 source（同 newAssetFilters 口径）：'其他' 桶翻译成
	// source_is_other 旗，其余出处名进 JSON 数组（IN json_each）。
	var sourcesJson any
	var sourceIsOther any = int64(0)
	if params.Source != nil {
		var sources []string
		for _, src := range *params.Source {
			if src == sourceOtherLabel {
				sourceIsOther = int64(1)
			} else if src != "" {
				sources = append(sources, src)
			}
		}
		sourcesJson = jsonString(sources)
	}
	var authorID any
	if params.AuthorId != nil && *params.AuthorId != "" {
		authorID = nullStr(*params.AuthorId)
	}
	var cur pageCursor
	if params.Cursor != nil && *params.Cursor != "" {
		c, err := decodeCursor(*params.Cursor)
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidCursor, "分页游标不合法")
			return
		}
		cur = c
	}
	rows, err := s.q.ListHistory(r.Context(), db.ListHistoryParams{
		RowLimit:       int64(limit) + 1, // 多取 1 行探测下一页
		CursorKey:      nullStr(cur.K),
		CursorID:       sql.NullString{String: cur.I, Valid: cur.K != ""},
		IncludeCos:     includeCos,
		CosOnly:        cosOnly,
		MediaType:      mediaType,
		SourcesJson:    sourcesJson,
		SourceIsOther:  sourceIsOther,
		CosWorksJson:   cosWorksJson,
		CharactersJson: charactersJson,
		AuthorID:       authorID,
	})
	if err != nil {
		s.logger.Error("查询观看历史失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	hasMore := len(rows) > limit
	if hasMore {
		rows = rows[:limit]
	}
	items := make([]gen.HistoryItem, 0, len(rows))
	var lastKey, lastID string
	for i := range rows {
		row := &rows[i]
		base := buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite,
			row.LikeCount, nil, nil)
		lv := historyMillis(row.LastViewedAt)
		// 已看完徽标/时长徽标数据源（协议 HistoryItem = AssetSummary allOf 含
		// durationMs/lastPositionSeconds）：与资产列表同列，图片/未播过为 null。
		var dur *int64
		if row.DurationMs.Valid {
			dur = ptr(row.DurationMs.Int64)
		}
		var pos *float64
		if row.LastPositionSeconds.Valid {
			f := row.LastPositionSeconds.Float64
			pos = &f
		}
		items = append(items, gen.HistoryItem{
			// AssetSummary 基础字段（openapi HistoryItem = AssetSummary allOf；
			// 与 GetApiV1AssetsAssetId 的 base 展开同模式）。
			AddedAt:             base.AddedAt,
			DurationMs:          dur,
			FileName:            base.FileName,
			Id:                  base.Id,
			IsFavorite:          base.IsFavorite,
			LastPositionSeconds: pos,
			LikeCount:           base.LikeCount,
			MediaType:           base.MediaType,
			ModifiedAt:          base.ModifiedAt,
			SizeBytes:           base.SizeBytes,
			Source:              base.Source,
			ThumbUrl:            base.ThumbUrl,
			// 扩展字段：最近一次 open 的 started_at（Unix 毫秒）。
			LastViewedAt: ptr(lv),
		})
		lastKey, lastID = toString(row.LastViewedAt), row.AssetID
	}
	page := gen.HistoryPage{Items: &items}
	if hasMore {
		next := encodeCursor(lastKey, lastID)
		page.NextCursor = &next
	}
	writeJSON(w, http.StatusOK, page)
}

// historyMillis 把历史行的 last_viewed_at（store.TimestampLayout 时间串，
// 语义见 migrations/0001 文件头）转为 Unix 毫秒时间戳；脏数据（解析失败）
// 返回 0 兜底，不炸接口（与 parseStoreTime 同风格）。
func historyMillis(v any) int64 {
	if s, ok := v.(string); ok && s != "" {
		if t, err := time.Parse(store.TimestampLayout, s); err == nil {
			return t.UnixMilli()
		}
	}
	return 0
}
