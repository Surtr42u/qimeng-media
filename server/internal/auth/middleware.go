package auth

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
)

// Principal 表示已通过鉴权的请求主体。
//
// 单用户起步阶段只有 TokenHash（唯一有效 token 的 SHA-256 即身份）；
// SECURITY 要求用户表第一天就按多用户设计，届时在接入层把
// UserID/角色填进来，中间件与下游读取方式不变。
type Principal struct {
	TokenHash string
}

// principalKey 是 ctx 存取 Principal 的键类型。
// 独立未导出类型避免与其他包的 ctx 值碰撞。
type principalKey struct{}

// RequireToken 返回 Bearer token 鉴权中间件（签名兼容 chi 的
// middleware.Middleware，即 func(http.Handler) http.Handler，可直接 m.Use）。
//
// verify 由调用方注入：收到 token 的 SHA-256 哈希，返回是否有效——
// 这样本包不依赖存储层（纯逻辑件），接入时由 store 实现查库比对，
// 比对务必走 VerifyTokenHash（恒时）。/healthz 等免鉴权路由由挂载
// 位置决定（挂在 RequireToken 之外），中间件本身不内置白名单。
//
// 通过后向 ctx 注入 *Principal，handler 用 PrincipalFromContext 读取。
func RequireToken(verify func(ctx context.Context, tokenHash string) bool) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, ok := bearerToken(r.Header.Get("Authorization"))
			if !ok {
				writeUnauthorized(w, "缺少 Bearer token")
				return
			}
			hash := TokenHash(token)
			if !verify(r.Context(), hash) {
				writeUnauthorized(w, "token 无效")
				return
			}
			ctx := context.WithValue(r.Context(), principalKey{}, &Principal{TokenHash: hash})
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// PrincipalFromContext 取出鉴权中间件注入的请求主体。
// ok 为 false 表示该请求未经 RequireToken（路由漏挂）——handler 应视为程序错误。
func PrincipalFromContext(ctx context.Context) (p *Principal, ok bool) {
	p, ok = ctx.Value(principalKey{}).(*Principal)
	return p, ok
}

// bearerToken 解析 Authorization 头，scheme 大小写容错
// （RFC 7235 规定 scheme 大小写不敏感，客户端实现不一）。
// 返回 false 的情形：无 header、scheme 不是 Bearer、token 为空。
func bearerToken(header string) (token string, ok bool) {
	parts := strings.SplitN(header, " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "bearer") {
		return "", false
	}
	token = strings.TrimSpace(parts[1])
	if token == "" {
		return "", false
	}
	return token, true
}

// unauthorizedBody 与 openapi 的 Error 模型一致（code + message），
// 供后续 oapi 生成的 SDK 直接反序列化。
type unauthorizedBody struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// writeUnauthorized 输出 401。文案固定不含任何请求细节
// （SECURITY 红线 7：错误响应不泄露内部信息）。
func writeUnauthorized(w http.ResponseWriter, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	// 响应体写失败（客户端已断开）无补救动作，net/http 会关连接；
	// 此处为终端错误响应，忽略写错误即可。
	_ = json.NewEncoder(w).Encode(unauthorizedBody{Code: "UNAUTHORIZED", Message: message})
}
