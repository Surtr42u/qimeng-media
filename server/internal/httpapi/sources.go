// sources.go：出处列表端点（相册按出处分组用）。
// 语义唯一权威：docs/DOMAIN_RULES §3（筛选体系）/§6（COS 隔离）——
// 出处计数口径与资产列表一致（默认排除 COS 作者关联文件），
// name=null 表示无出处文件（显示层兜底"其他"）。
package httpapi

import (
	"database/sql"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
)

// GetApiV1Sources 按出处规范名分组的文件计数，fileCount 降序。
// includeCos 默认 false（与 GET /assets 的 COS 隔离口径一致——
// COS 作者关联文件有独立入口，不进常规浏览/相册流）。
func (s *Server) GetApiV1Sources(w http.ResponseWriter, r *http.Request, params gen.GetApiV1SourcesParams) {
	includeCos := int64(0)
	if params.IncludeCos != nil && *params.IncludeCos {
		includeCos = 1
	}
	// ListSources 只有一个参数，sqlc 以裸参数生成（非 Params 结构体）。
	rows, err := s.q.ListSources(r.Context(), includeCos)
	if err != nil {
		s.internalErr(w, "查询出处分组", err)
		return
	}
	items := make([]gen.SourceCount, 0, len(rows))
	for _, row := range rows {
		fc := int(row.FileCount)
		items = append(items, gen.SourceCount{Name: nullableName(row.Name), FileCount: &fc})
	}
	writeJSON(w, http.StatusOK, items)
}

// nullableName 把 SQL 的 NULL 来源转成响应 null：无出处文件归入"其他"桶，
// 桶名留给显示层兜底（协议语义见 openapi.yaml 的 SourceCount.name 描述）。
func nullableName(ns sql.NullString) *string {
	if ns.Valid {
		v := ns.String
		return &v
	}
	return nil
}
