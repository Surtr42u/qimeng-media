package httpapi

import (
	"net/http"
	"qimeng-media/server/internal/discovery"
	"qimeng-media/server/internal/httpapi/gen"
)

// 与 OpenAPI /discoveries 的 limit 默认值和上限双向同步。
const defaultDiscoveryLimit = 6
const maxDiscoveryLimit = 12

func (s *Server) GetApiV1Discoveries(w http.ResponseWriter, r *http.Request, params gen.GetApiV1DiscoveriesParams) {
	limit := defaultDiscoveryLimit
	if params.Limit != nil {
		limit = *params.Limit
	}
	seed := int64(0)
	if params.Seed != nil {
		seed = *params.Seed
	}
	if limit < 1 || limit > maxDiscoveryLimit || seed < 0 {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "limit须为1–12，seed须为非负整数")
		return
	}
	rows, err := discovery.Load(r.Context(), s.q, seed, limit, s.now())
	if err != nil {
		s.internalErr(w, "查询此刻发现", err)
		return
	}
	assets := make([]gen.AssetSummary, 0, len(rows))
	for _, entry := range rows {
		row := entry.Asset
		item := buildSummary(s, row.AssetID, row.FileName, row.MediaType, row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount, nil, nil)
		if row.DurationMs.Valid {
			item.DurationMs = ptr(row.DurationMs.Int64)
		}
		assets = append(assets, item)
	}
	s.fillListAuthorNames(r.Context(), assets)
	s.fillListCosWork(r.Context(), assets)
	out := make([]gen.DiscoveryItem, 0, len(rows))
	for i, entry := range rows {
		out = append(out, gen.DiscoveryItem{Asset: assets[i], Reason: gen.DiscoveryItemReason(entry.Reason)})
	}
	writeJSON(w, http.StatusOK, out)
}
