package httpapi

import (
	"encoding/json"
	"net/http"
)

// maxJSONBody 是 JSON 请求体上限（1MB，SECURITY 红线 6：请求体滥用防御）。
// 本包所有 JSON 解析一律先过它。
const maxJSONBody = 1 << 20

// writeJSON 输出统一 JSON 响应。写失败只可能发生在客户端断开时，
// 无补救动作；调用方不需要处理该错误。
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// writeErr 输出与 openapi components.Error 一致的错误响应。
// message 必须是人类可读文案且不含内部路径/堆栈（SECURITY 红线 7）。
func writeErr(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{"code": code, "message": message})
}

// notImplemented 机制（M1 未接线端点统一 501 + NOT_IMPLEMENTED）已随
// openapi 全部端点接线完毕而移除；如未来新增未接线端点，从 git 历史恢复
// 此函数并在 stubs.go 挂占位方法（语义说明见 stubs.go 注释）。

// decodeJSON 读取限制大小后的 JSON body。返回 false 时响应已写完。
func decodeJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxJSONBody)).Decode(v); err != nil {
		writeErr(w, http.StatusBadRequest, "INVALID_BODY", "请求体不是合法 JSON")
		return false
	}
	return true
}
