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
	// defaultRankingLimit 排行榜默认条数（api/openapi.yaml /rankings limit
	// default=50；协议侧改默认值必须同步这里，反之亦然）。
	defaultRankingLimit = 50
	// maxRankingLimit 排行榜防御性上限：openapi /rankings 未声明 maximum，
	// 但排行榜不分页且按热度全表排序，服务端仍需单次响应上限（与推荐流
	// maxPageLimit 同值 200）——协议侧若给 maximum 必须同步这里。
	maxRankingLimit = 200
)

// resolvePageLimit 应用 openapi 默认值并校验范围（1..maxPageLimit）；
// 越界写 400 INVALID_PARAM 并返回 ok=false，调用方必须立即 return。
func resolvePageLimit(w http.ResponseWriter, limit *int) (int, bool) {
	if limit == nil {
		return defaultPageLimit, true
	}
	if *limit < 1 || *limit > maxPageLimit {
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("limit 取值范围 1..%d", maxPageLimit))
		return 0, false
	}
	return *limit, true
}

// resolveRankingLimit 排行榜的 limit 语义（默认/校验），与 resolvePageLimit
// 同构但默认值不同（协议 default=50，见 defaultRankingLimit 注释）。
func resolveRankingLimit(w http.ResponseWriter, limit *int) (int, bool) {
	if limit == nil {
		return defaultRankingLimit, true
	}
	if *limit < 1 || *limit > maxRankingLimit {
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("limit 取值范围 1..%d", maxRankingLimit))
		return 0, false
	}
	return *limit, true
}

// resolvePageOffset 分页偏移公共语义（协议 offset default=0/minimum=0）：
// 缺省 0；负数写 400 INVALID_PARAM（错误风格与上方 limit 族一致）。
// 消费方：recommendations / rankings（推荐流与排行榜的 offset 翻页）。
func resolvePageOffset(w http.ResponseWriter, offset *int) (int, bool) {
	if offset == nil {
		return 0, true
	}
	if *offset < 0 {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "offset 取值范围 >=0")
		return 0, false
	}
	return *offset, true
}
