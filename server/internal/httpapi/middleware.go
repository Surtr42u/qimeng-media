// middleware.go：Host/安全头、公开路由/Bearer与媒体签名的请求管线。
// 仅持Server引用，不创建第二份鉴权或配置状态；顺序与原Server装配保持一致。
package httpapi

import (
	"net"
	"net/http"
	"qimeng-media/server/internal/auth"
	"strconv"
	"strings"
)

// securityHeaders 给所有响应补安全响应头基线（docs/SECURITY.md）：
//   - X-Content-Type-Options: nosniff——MIME 嗅探防护，浏览器不再把声明的
//     Content-Type 之外的内容"猜"成可执行类型，图片/文本响应被注入
//     HTML/JS 的攻击面直接关闭；
//   - X-Frame-Options: DENY——点击劫持防护，SPA/验收页/管理页禁止被任何
//     第三方站点 iframe 嵌套（本服务无被嵌套的合法场景；媒体直链同理，
//     <img>/<video> 标签消费不受 framing 头影响）。
//
// 与 SSE/媒体流的显式 Content-Type 设置不冲突：本头只约束浏览器行为，
// 不改变服务端声明的类型。
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}

// hostCheck 校验 Host 头防 DNS rebinding（docs/SECURITY.md「Host 校验」，
// 2026-09-22）。攻击形态：恶意页面把自有域名解析到内网 IP，其页面对本
// 服务的请求在浏览器同源判定下"看起来同源"——本服务无 CORS 输出、
// Bearer 走 Authorization 头，正常跨域读取本已默认拒绝，但 rebinding
// 伪同源绕过这层；dev 免密形态下 dev-login 等于凭空送出 admin token，
// 未初始化形态下 setup 可被抢跑。默认白名单 = IP 直连（v4/v6 字面量）
// + localhost：局域网/隧道的既定访问形态全部命中，零配置零影响；域名
// 访问（如 Tailscale MagicDNS）加入 trusted_hosts 即可。
// 空 Host（HTTP/1.0 古董客户端）放行：rebinding 必须携带攻击者域名，
// 空 Host 不构成该向量，卡它只会误伤老客户端。
func (s *Server) hostCheck(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if host := r.Host; host != "" && !s.hostAllowed(host) {
			s.logger.Warn("拒绝非白名单 Host 的请求（DNS rebinding 防御）",
				"host", host, "path", r.URL.Path)
			writeErr(w, http.StatusForbidden, codeInvalidParam, "请求的 Host 不被允许")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// hostAllowed 判定 Host 是否放行：剥端口（含 "[v6]:port" 形态）→
// localhost / IP 字面量 / trusted_hosts 大小写不敏感匹配。剥失败（裸
// IPv6 如 "::1"）按整串参与判定，ParseIP 兜住。
func (s *Server) hostAllowed(host string) bool {
	h := host
	if hp, _, err := net.SplitHostPort(host); err == nil {
		h = hp
	}
	h = strings.ToLower(h)
	if h == "localhost" || net.ParseIP(h) != nil {
		return true
	}
	for _, t := range s.cfg.TrustedHosts {
		if strings.EqualFold(t, h) {
			return true
		}
	}
	return false
}

// topRouter 是鉴权/签名校验的最外层分发器。
//
// 为什么不用两层中间件挂在 mux 上：gen 生成的路由已占用根路径空间，
// /media/* 需要"签名校验但免 Bearer"、其余 API 需要 Bearer、探针与
// 验收页完全放行——三类策略按路径前缀精确分发，集中在这里一目了然，
// 避免"某个路由忘了挂中间件"这类审计盲区（SECURITY 红线 5）。
type topRouter struct {
	s   *Server
	api http.Handler
}

func (t *topRouter) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	switch {
	case p == "/healthz" || p == "/readyz" ||
		p == "/api/v1/healthz" || p == "/api/v1/readyz" ||
		p == "/" || p == "/index.html" ||
		p == "/_debug" || strings.HasPrefix(p, "/_debug/"):
		// 探针与页面：放行。免鉴权清单以 api/openapi.yaml 的 security: []
		// 元数据为准（/api/v1/healthz、/api/v1/readyz 均标 security: []），
		// 本处与协议保持同步；根路径 /healthz /readyz 是运维探针别名
		// （docker/k8s 惯例，New() 里挂 mux，与 /api/v1 版共用同一 handler），
		// 不属于协议面。页面（验收页 / 与 /index.html、固定调试挂载点
		// /_debug/、SPA 模式下的入口）是静态 HTML 不走协议（页面内数据
		// 请求照常走 Bearer）。
		t.api.ServeHTTP(w, r)
	case p == "/api/v1/auth/setup" || p == "/api/v1/auth/login" || p == "/api/v1/auth/dev-login":
		// 首次初始化与密码登录免鉴权（api/openapi.yaml security: []，与协议
		// 一致；协议侧改动须同步此处，反之亦然）。login 本身就是凭据校验
		// （argon2 比对 + 401），免鉴权不构成攻击面：错密码只换来 401，
		// setup 在已有用户时 handler 自己回 409；dev-login 恒 404 兜底
		//（handler 内校验 AuthDevMode），开启时即按设计发放 token。
		t.api.ServeHTTP(w, r)
	case strings.HasPrefix(p, mediaPathPrefix):
		// 媒体直链：Bearer 管不了 <img>/<video> 标签，走 HMAC 签名
		//（SECURITY 红线 5：禁止裸直链）。
		t.s.mediaSignature(t.api).ServeHTTP(w, r)
	case t.s.spa != nil && !strings.HasPrefix(p, "/api/") && p != "/metrics":
		// SPA 静态托管：构建产物与前端路由（无 /api/ 前缀）免 Bearer——
		// 浏览器直接加载 <script src="/assets/xxx.js"> 带不了 header；
		// API 路径不在此列（拼错的 /api/ 地址保持 401/404 语义，不被
		// SPA 回退吞掉），/metrics 保持管理鉴权（非 security: []）。
		t.api.ServeHTTP(w, r)
	default:
		// 其余全部要求 Bearer token（含 SSE：浏览器 EventSource 无法带
		// header，验收页用 fetch 流式读 SSE 而不是开 query 参数口子）。
		auth.RequireToken(t.s.authState.verify)(t.api).ServeHTTP(w, r)
	}
}

// mediaSignature 校验 /media/** 直链签名（失败一律 403）。
//
// 校验对象是 r.URL.Path（已做一次百分号解码的形态）——与签发侧
// SignMediaURL 的 path 构造保持同一形态，编码差异会导致签名不匹配，
// 这是 auth 包签名协议的既定约定。签名在路由匹配之前校验：即使路径
// 打不中任何路由，签名也必须先过关，避免"用 404 探测路由表"。
func (s *Server) mediaSignature(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// SECURITY 红线 1（路径穿越）：任何 .. 路径段（含 %2E%2E 编码
		// 被 URL 解码还原的形态）在进入路由与文件系统之前直接拒绝。
		// 不依赖 ServeMux 的 cleanPath 折叠：折叠发生在路由之后，签名
		// 校验若用折叠前路径、发文件用折叠后路径，两侧形态不一致就是
		// 绕过面——统一在最前面按"解码后的原始形态"拒绝。
		for _, seg := range strings.Split(r.URL.Path, "/") {
			if seg == ".." {
				writeErr(w, http.StatusForbidden, codePathEscape, "路径不合法")
				return
			}
		}
		expStr := r.URL.Query().Get("exp")
		sig := r.URL.Query().Get("sig")
		if expStr == "" || sig == "" {
			writeErr(w, http.StatusForbidden, codeSignatureMissing, "缺少签名参数")
			return
		}
		exp, err := strconv.ParseInt(expStr, 10, 64)
		if err != nil {
			writeErr(w, http.StatusForbidden, codeSignatureInvalid, "签名参数不合法")
			return
		}
		if err := auth.VerifyMediaSignature(r.URL.Path, exp, sig, s.secret, s.now); err != nil {
			// 篡改与过期都映射 403，但日志区分（auth 包的两种哨兵错误），
			// 便于区分攻击探测与正常的链接过期。
			s.logger.Warn("媒体直链签名校验失败", "err", err, "path", r.URL.Path)
			writeErr(w, http.StatusForbidden, codeSignatureInvalid, "签名无效或已过期")
			return
		}
		next.ServeHTTP(w, r)
	})
}
