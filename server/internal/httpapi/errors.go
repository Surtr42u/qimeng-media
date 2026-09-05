package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
)

// maxJSONBody 是 JSON 请求体上限（1MB，SECURITY 红线 6：请求体滥用防御）。
// 本包所有 JSON 解析一律先过它；大载荷端点的例外见 decodeJSONWithLimit。
const maxJSONBody = 1 << 20

// writeJSON 输出统一 JSON 响应。写失败只可能发生在客户端断开时，
// 无补救动作；调用方不需要处理该错误。
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	// 编码/写失败即客户端已断开，此处已是终端响应，无补救动作，忽略。
	_ = json.NewEncoder(w).Encode(v)
}

// writeErr 输出与 openapi components.Error 一致的错误响应。
// message 必须是人类可读文案且不含内部路径/堆栈（SECURITY 红线 7）。
func writeErr(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{"code": code, "message": message})
}

// internalErr 记录服务端内部错误并输出统一 500 响应：what 是失败阶段名
// （日志拼成"<what>失败"，让内部错误可定位到具体查询），err 原样进日志
// 但不进响应（SECURITY 红线 7：错误响应不泄露内部信息）。包内各端点的
// 查询/写库失败兜底统一走它。
func (s *Server) internalErr(w http.ResponseWriter, what string, err error) {
	s.logger.Error(what+"失败", "err", err)
	writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
}

// notImplemented 机制（M1 未接线端点统一 501 + NOT_IMPLEMENTED）已随
// openapi 全部端点接线完毕而移除；如未来新增未接线端点，从 git 历史恢复
// 此函数并在 stubs.go 挂占位方法（语义说明见 stubs.go 注释）。

// decodeJSON 读取限制大小后的 JSON body。返回 false 时响应已写完。
func decodeJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	return decodeJSONWithLimit(w, r, v, maxJSONBody)
}

// decodeJSONWithLimit per-route 上限版本：大载荷端点（旧版备份导入，
// legacyImportMaxBody）按自身限额放行，其余端点仍走 maxJSONBody 红线。
// 超限返回 413 TOO_LARGE（区别于格式错误的 400）。
func decodeJSONWithLimit(w http.ResponseWriter, r *http.Request, v any, maxBytes int64) bool {
	err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBytes)).Decode(v)
	if err == nil {
		return true
	}
	var maxErr *http.MaxBytesError
	if errors.As(err, &maxErr) {
		writeErr(w, http.StatusRequestEntityTooLarge, "TOO_LARGE",
			fmt.Sprintf("请求体超过 %d MB 上限", maxBytes>>20))
		return false
	}
	writeErr(w, http.StatusBadRequest, "INVALID_BODY", "请求体不是合法 JSON")
	return false
}
