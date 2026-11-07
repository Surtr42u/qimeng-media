// aiclient.go：独立 AI 客户端凭据的 Bearer 分支、scope 强制映射与写审计
// （ADR-0057 P1；方案见 docs/AI_PLATFORM_API_GAPS.md）。
//
// 不变量：
//   - 管理员会话行为不变：token 命中 auth_sessions 时完全走旧链路
//     （auth.RequireToken 注入 Principal、401 文案与错误码都不变）。
//   - AI 客户端是受限身份：认证成功后再按 method+path 映射所需 scope；
//     GET/HEAD 兜底 read，写请求未映射一律 403（默认拒绝，不做"未知即放行"）。
//     管理员会话不受 scope 限制。
//   - 审计只覆盖 AI 写请求（含被拒绝的）；管理员请求不写 ai_audit。
//     审计失败不阻断业务，只记录错误日志。
//   - 不新增 HTTP 管理端点：创建/列表/撤销/轮换走 qimeng ai-client 本地命令，
//     主 API 协议（api/openapi.yaml）本轮不变。
package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"qimeng-media/server/internal/aiauth"
	"qimeng-media/server/internal/auth"
)

const (
	// aiAuditWriteTimeout 是审计写库上限：审计是 best-effort，不能拖住已
	// 完成的业务响应，也不能因为审计失败改写响应状态。
	aiAuditWriteTimeout = 3 * time.Second
	// aiRequestIDHeader 允许调用方携带关联 ID（CLI/MCP 可回传自己的请求号），
	// 缺失/超长/含不可打印字符时由服务端生成。
	aiRequestIDHeader = "X-Request-Id"
	// aiRequestIDBytes 是服务端生成 requestId 的随机字节数（hex 后 32 字符）。
	aiRequestIDBytes = 16
)

// bearerChain 是 topRouter 默认分支的鉴权链：先管理员会话，再 AI 客户端。
//
// 为什么管理员优先：管理员会话是既有行为，必须逐字节保持（401 文案、
// Principal 注入、限速等都在 auth.RequireToken 内）；AI 凭据只在管理员
// 校验未命中时才尝试，两类身份不会互相升级。
func (s *Server) bearerChain(next http.Handler) http.Handler {
	admin := auth.RequireToken(s.authState.verify)(next)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, ok := parseBearerToken(r.Header.Get("Authorization"))
		if !ok || s.aiAuth == nil || s.authState.verify(r.Context(), auth.TokenHash(token)) {
			// 无 token / 管理员会话 / 未装配 AI 凭据：交给旧链路（行为不变）。
			admin.ServeHTTP(w, r)
			return
		}
		client, ok := s.aiAuth.Authenticate(r.Context(), token)
		if !ok {
			// 无效 token 与管理员 token 无效同文案（不区分身份类型，减少探测面）。
			admin.ServeHTTP(w, r)
			return
		}
		s.serveAIClient(w, r, client, next)
	})
}

// serveAIClient 对已认证的 AI 客户端做 scope 强制与写审计。
func (s *Server) serveAIClient(w http.ResponseWriter, r *http.Request, client aiauth.Client, next http.Handler) {
	scope, action, mapped := requiredAIScope(r.Method, r.URL.Path)
	record := &aiAuditRecord{action: action}
	requestID := aiRequestID(r)
	if !mapped {
		record.action = "admin"
		s.denyAIClient(w, r, client, record, requestID,
			"该操作未向 AI 客户端开放，需管理员会话")
		return
	}
	if !client.HasScope(scope) {
		s.denyAIClient(w, r, client, record, requestID,
			"AI 客户端缺少所需 scope："+string(scope))
		return
	}
	principal := aiauth.Principal{
		ClientID: client.ID, Label: client.Label,
		Scopes: client.Scopes, DeviceIDs: client.DeviceIDs,
	}
	ctx := aiauth.WithPrincipal(r.Context(), principal)
	ctx = context.WithValue(ctx, aiAuditKey{}, record)
	// 只包写请求：GET/HEAD 可能是 SSE/大响应，不需要审计状态码捕获，也不
	// 应该用包装 Writer 改变流式语义。
	if !isAIWriteMethod(r.Method) {
		next.ServeHTTP(w, r.WithContext(ctx))
		return
	}
	recorder := &aiStatusRecorder{ResponseWriter: w}
	next.ServeHTTP(recorder, r.WithContext(ctx))
	s.writeAIAudit(r, client.ID, record.actionString(), recorder.statusCode(), requestID)
}

// denyAIClient 输出统一 403（FORBIDDEN_SCOPE）并补写被拒绝写请求的审计。
func (s *Server) denyAIClient(w http.ResponseWriter, r *http.Request, client aiauth.Client, record *aiAuditRecord, requestID, message string) {
	writeErr(w, http.StatusForbidden, codeForbiddenScope, message)
	if isAIWriteMethod(r.Method) {
		s.writeAIAudit(r, client.ID, record.actionString(), http.StatusForbidden, requestID)
	}
}

// writeAIAudit 追加 AI 写审计（best-effort：失败只记错误日志，不改响应）。
func (s *Server) writeAIAudit(r *http.Request, clientID, action string, statusCode int, requestID string) {
	if s.aiAuth == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.WithoutCancel(r.Context()), aiAuditWriteTimeout)
	defer cancel()
	err := s.aiAuth.RecordAudit(ctx, aiauth.AuditEntry{
		ClientID:   clientID,
		Method:     r.Method,
		Path:       r.URL.Path,
		Action:     action,
		StatusCode: statusCode,
		RequestID:  requestID,
	})
	if err != nil {
		s.logger.WarnContext(ctx, "写入 AI 写操作审计失败（不阻断业务）",
			"error", err, "clientId", clientID, "path", r.URL.Path)
	}
}

// aiAuditRecord 是中间件与 handler 共享的可变审计动作：路径映射给出默认
// action，设备等 handler 可在执行中补充目标（如 claim:<deviceId>）。
type aiAuditRecord struct {
	mu     sync.Mutex
	action string
}

// append 追加动作标签（截断到审计字段上限）。
func (a *aiAuditRecord) append(suffix string) {
	suffix = strings.TrimSpace(suffix)
	if suffix == "" {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	candidate := a.action + ":" + suffix
	runes := []rune(candidate)
	if len(runes) > aiauth.MaxAuditActionRunes {
		candidate = string(runes[:aiauth.MaxAuditActionRunes])
	}
	a.action = candidate
}

func (a *aiAuditRecord) actionString() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.action
}

// aiAuditKey 是 ctx 存取审计记录的键类型。
type aiAuditKey struct{}

// annotateAIAudit 供 handler 在中间件落审计前补充动作标签；管理员请求或
// 未挂 AI 审计时是 no-op。
func annotateAIAudit(r *http.Request, suffix string) {
	record, ok := r.Context().Value(aiAuditKey{}).(*aiAuditRecord)
	if !ok || record == nil {
		return
	}
	record.append(suffix)
}

// requireAIDeviceAccess 在 AI 客户端访问设备结果包时校验设备绑定；
// 管理员会话（ctx 无 AI principal）不受设备级限制。返回 false 时已写 403。
func (s *Server) requireAIDeviceAccess(w http.ResponseWriter, r *http.Request, targetDeviceID string) bool {
	principal, ok := aiauth.PrincipalFromContext(r.Context())
	if !ok {
		return true
	}
	if principal.AllowsDevice(targetDeviceID) {
		return true
	}
	writeErr(w, http.StatusForbidden, codeForbiddenScope,
		"AI 客户端无权访问该设备的结果包")
	return false
}

// requireAIDeviceManifestAccess 在创建结果包前从 manifest 只读取
// targetDeviceId 做设备绑定预检；manifest 结构合法性仍由 deviceapply.Create
// 判定（这里不重复实现协议校验，解析失败时放行给业务层返回标准 400）。
func (s *Server) requireAIDeviceManifestAccess(w http.ResponseWriter, r *http.Request, manifest []byte) bool {
	if _, ok := aiauth.PrincipalFromContext(r.Context()); !ok {
		return true
	}
	var probe struct {
		TargetDeviceID string `json:"targetDeviceId"`
	}
	if err := json.Unmarshal(manifest, &probe); err != nil {
		return true
	}
	return s.requireAIDeviceAccess(w, r, probe.TargetDeviceID)
}

// requiredAIScope 把 method+path 映射到所需 scope（false = 未映射，AI 拒绝）。
//
// 映射顺序即优先级：具体域先于通用读兜底；写请求没有兜底 scope（默认拒绝，
// 新写入面必须显式接入，避免新增端点被 AI 自动继承）。
// 设备结果包的设备级隔离不在本函数（见 requireAIDeviceAccess）。
func requiredAIScope(method, path string) (aiauth.Scope, string, bool) {
	read := method == http.MethodGet || method == http.MethodHead
	switch {
	case strings.HasPrefix(path, "/api/v1/maintenance/jobs"):
		if read {
			return aiauth.ScopeMaintenanceRead, "maintenance.read", true
		}
		return aiauth.ScopeMaintenanceWrite, "maintenance.write", true
	case strings.HasPrefix(path, "/api/v1/device/apply-tasks"):
		return aiauth.ScopeDeviceApply, "device.apply", true
	case strings.HasPrefix(path, "/api/v1/backups"):
		return aiauth.ScopeBackupExport, "backup.export", true
	case path == "/api/v1/export/qimeng-backup":
		return aiauth.ScopeBackupExport, "backup.export", true
	case path == "/api/v1/import/qimeng-backup":
		return aiauth.ScopeBackupRestore, "backup.restore", true
	case strings.HasPrefix(path, "/api/v1/research/"):
		return aiauth.ScopeResearch, "research", true
	case path == "/api/v1/authors/merge" && method == http.MethodPost:
		return aiauth.ScopeAuthorDestructive, "author.destructive", true
	case strings.HasPrefix(path, "/api/v1/authors/") && method == http.MethodDelete:
		return aiauth.ScopeAuthorDestructive, "author.destructive", true
	case path == "/api/v1/authors" || strings.HasPrefix(path, "/api/v1/authors/"):
		if read {
			return aiauth.ScopeRead, "read", true
		}
		return aiauth.ScopeAuthorWrite, "author.write", true
	case strings.HasPrefix(path, "/api/v1/assets/") && strings.HasSuffix(path, "/authors") && method == http.MethodPut:
		return aiauth.ScopeAssetAuthorWrite, "asset.author.write", true
	case strings.HasPrefix(path, "/api/v1/assets/") && strings.HasSuffix(path, "/move"):
		return aiauth.ScopeMediaOrganize, "media.organize", true
	}
	if read {
		return aiauth.ScopeRead, "read", true
	}
	return "", "", false
}

// isAIWriteMethod 把非 GET/HEAD 请求视为写请求（需要 scope 强制与审计）。
func isAIWriteMethod(method string) bool {
	return method != http.MethodGet && method != http.MethodHead
}

// aiStatusRecorder 捕获 handler 写出的状态码（审计用）；只记录首个状态码，
// 非显式 WriteHeader 的写操作按 200 计。
type aiStatusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *aiStatusRecorder) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *aiStatusRecorder) Write(data []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	return r.ResponseWriter.Write(data)
}

func (r *aiStatusRecorder) statusCode() int {
	if r.status == 0 {
		return http.StatusOK
	}
	return r.status
}

// aiRequestID 取调用方关联 ID（有界、可打印 ASCII），否则生成随机 ID。
func aiRequestID(r *http.Request) string {
	if value := strings.TrimSpace(r.Header.Get(aiRequestIDHeader)); value != "" &&
		len([]rune(value)) <= aiauth.MaxAuditRequestIDRunes && printableASCII(value) {
		return value
	}
	buf := make([]byte, aiRequestIDBytes)
	if _, err := rand.Read(buf); err == nil {
		return hex.EncodeToString(buf)
	}
	// crypto/rand 失败极罕见且审计不能阻断请求：回退到时间戳（无安全语义）。
	return fmt.Sprintf("req-%d", time.Now().UnixNano())
}

// printableASCII 限制审计关联 ID 只含可打印 ASCII，避免日志注入/不可见字符。
func printableASCII(value string) bool {
	for i := 0; i < len(value); i++ {
		if value[i] < 0x20 || value[i] > 0x7e {
			return false
		}
	}
	return true
}

// parseBearerToken 解析 Authorization 头（scheme 大小写容错，语义与
// auth 包内部 helper 一致）。鉴权分流必须在本包先解析一次，故不能复用
// auth 包未导出函数；auth 包的 401 文案/错误码仍是唯一响应出口。
func parseBearerToken(header string) (string, bool) {
	parts := strings.SplitN(header, " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "bearer") {
		return "", false
	}
	token := strings.TrimSpace(parts[1])
	if token == "" {
		return "", false
	}
	return token, true
}
