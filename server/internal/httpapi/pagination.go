// pagination.go：列表类端点的分页参数公共语义（默认值 / 上限 / 越界响应）。
//
// 与 api/openapi.yaml 各 limit 参数（default=60、maximum=200）双写同步：
// 协议侧改默认值或上限必须同步这里，反之亦然（AI_README_FIRST「代码卫生约束」3）。
package httpapi

import (
	"fmt"
	"net/http"
)

const (
	// defaultPageLimit 列表/推荐流端点的默认分页大小（openapi limit default）。
	defaultPageLimit = 60
	// maxPageLimit 单页大小上限（openapi limit maximum）。
	maxPageLimit = 200
)

// resolvePageLimit 应用 openapi 默认值并校验范围（1..maxPageLimit）；
// 越界写 400 INVALID_PARAM 并返回 ok=false，调用方必须立即 return。
func resolvePageLimit(w http.ResponseWriter, limit *int) (int, bool) {
	if limit == nil {
		return defaultPageLimit, true
	}
	if *limit < 1 || *limit > maxPageLimit {
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM",
			fmt.Sprintf("limit 取值范围 1..%d", maxPageLimit))
		return 0, false
	}
	return *limit, true
}
