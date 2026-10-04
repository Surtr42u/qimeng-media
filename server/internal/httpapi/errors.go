package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"

	sqlite "modernc.org/sqlite"
	sqlite3 "modernc.org/sqlite/lib"
)

// maxJSONBody 是 JSON 请求体上限（1MB，SECURITY 红线 6：请求体滥用防御）。
// 本包所有 JSON 解析一律先过它；大载荷端点的例外见 decodeJSONWithLimit。
const maxJSONBody = 1 << 20

// 协议错误码常量（writeErr 的 code 参数唯一合法来源）。
// 同步责任：这些值与 api/openapi.yaml（components.Error.code 的 description
// 与各端点 4xx 响应 description）双写联动——新增/改名错误码必须同步协议侧
// 与三端消费点，反之亦然；字符串一致性靠此单一来源保证，禁止在调用点
// 手写字面量（代码卫生约束 3）。
const (
	codeInternal           = "INTERNAL"
	codeInvalidParam       = "INVALID_PARAM"
	codeNotFound           = "NOT_FOUND"
	codeUploadTooLarge     = "UPLOAD_TOO_LARGE"
	codeUnauthorized       = "UNAUTHORIZED"
	codePathEscape         = "PATH_ESCAPE"
	codeInvalidFilename    = "INVALID_FILENAME"
	codeInvalidBody        = "INVALID_BODY"
	codeTargetExists       = "TARGET_EXISTS"
	codeSysmonUnavailable  = "SYSMON_UNAVAILABLE"
	codeSignatureInvalid   = "SIGNATURE_INVALID"
	codeScannerUnavailable = "SCANNER_UNAVAILABLE"
	codeInvalidExtension   = "INVALID_EXTENSION"
	codeInvalidCursor      = "INVALID_CURSOR"
	codeFileMissing        = "FILE_MISSING"
	codeAlreadySetup       = "ALREADY_SETUP"
	codeWeakPassword       = "WEAK_PASSWORD"
	codeUploadDisabled     = "UPLOAD_DISABLED"
	codeTooLarge           = "TOO_LARGE"
	codeThumbnailFailed    = "THUMBNAIL_FAILED"
	codeTagExists          = "TAG_EXISTS"
	codeSignatureMissing   = "SIGNATURE_MISSING"
	codeScanInProgress     = "SCAN_IN_PROGRESS"
	codePathNotFound       = "PATH_NOT_FOUND"
	codeMimeMismatch       = "MIME_MISMATCH"
	codeLibraryNotFound    = "LIBRARY_NOT_FOUND"
	codeInvalidMeta        = "INVALID_META"
	codeDevDisabled        = "DEV_DISABLED"
	codeDbUnreachable      = "DB_UNREACHABLE"
	codeDataDirConflict    = "DATA_DIR_CONFLICT"
	codeConflict           = "CONFLICT"
	codeBadRequest         = "BAD_REQUEST"
	codeRateLimited        = "RATE_LIMITED"
	codeBackupInProgress   = "BACKUP_IN_PROGRESS"
	codeBackupUnavailable  = "BACKUP_UNAVAILABLE"
)

// contentTypeJSON 全部 JSON 响应的 Content-Type 唯一取值（charset 显式
// 声明 UTF-8；探针/鉴权等手写响应与 writeJSON 统一走它，包内第二处
// 手抄即违例——代码卫生约束 2）。
const contentTypeJSON = "application/json; charset=utf-8"

// writeJSON 输出统一 JSON 响应。写失败只可能发生在客户端断开时，
// 无补救动作；调用方不需要处理该错误。
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", contentTypeJSON)
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
	writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
}

// isSQLiteConstraint 报告 err 是否为 modernc SQLite 驱动返回的指定约束类
// 错误：errors.As 到驱动官方错误类型 sqlite.Error 后按 Code()（SQLite
// 结果码）判定，约束码直接引用驱动自带 C 常量表导出的 SQLITE_CONSTRAINT_*
// （modernc.org/sqlite/lib），禁止手写魔数码。本 helper 是约束冲突判定的
// 单一来源（libraries.go 的 UNIQUE / engagement.go 的 FOREIGN KEY 两处
// 共用，2026-10 治理批把原 err.Error() 文本匹配收敛至此）——文本是驱动的
// 展示层产物、随版本可变，错误码才是稳定契约。
func isSQLiteConstraint(err error, code int) bool {
	var serr *sqlite.Error
	return errors.As(err, &serr) && serr.Code() == code
}

// 本包判定的约束码（SQLite 官方扩展结果码，值由 modernc.org/sqlite/lib
// 常量表单一来源保证）。
const (
	// sqliteCodeConstraintUnique：UNIQUE 索引冲突（如 libraries.root_path）。
	// 边界记档（2026-10-04 审查）：INTEGER PRIMARY KEY 冲突返回的是
	// SQLITE_CONSTRAINT_PRIMARYKEY(1555) 而非本码 2067（错误文本同为
	// "UNIQUE constraint failed"，旧文本匹配反而两者都兜得住）——未来对
	// 主键表判 409 时须并判 1555；本仓现有判定点均为 TEXT UNIQUE 列，不受影响。
	sqliteCodeConstraintUnique = sqlite3.SQLITE_CONSTRAINT_UNIQUE
	// sqliteCodeConstraintForeignKey：外键约束失败（如事件累加撞已删资产）。
	sqliteCodeConstraintForeignKey = sqlite3.SQLITE_CONSTRAINT_FOREIGNKEY
)

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
		writeErr(w, http.StatusRequestEntityTooLarge, codeTooLarge,
			fmt.Sprintf("请求体超过 %d MB 上限", maxBytes>>20))
		return false
	}
	writeErr(w, http.StatusBadRequest, codeInvalidBody, "请求体不是合法 JSON")
	return false
}
