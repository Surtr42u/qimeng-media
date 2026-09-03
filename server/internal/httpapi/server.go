// server.go：Server 组装、顶层鉴权分发、媒体直链签名中间件。
// 包职责说明见 doc.go。
package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/thumbnail"
)

// Scanner 是 httpapi 对扫描器的最小依赖抽象。
//
// 为什么在 httpapi 内定义接口而不是直接依赖 internal/scanner：扫描器
// 由并行任务实现（M1 里程碑拆分），接口收窄到"触发一次库扫描"这一端点
// 所需的最小面，组装方（main）负责把真实 scanner 适配进来——依赖倒置
// 让两侧可以独立编译、独立测试。
//
// 契约：Scan 触发一次异步全量扫描后立即返回（进度经 events 总线以
// scan.progress 主题推送）；返回错误表示"触发失败"（如扫描器未装配）。
type Scanner interface {
	Scan(ctx context.Context, libraryID string) error
	// EnrichAsset 单资产重富化（出处/角色重算，M3 SourceMatcher）：
	// 移动/重命名端点写入成功后调用——文件名变了而 size+mtime 没变，
	// 扫描器不会重 ingest，必须由写入路径显式触发（失败调用方降级，
	// 重扫自愈）。
	EnrichAsset(ctx context.Context, libraryID, assetID string) error
	// UpdateCustomSources 运行期替换用户自定义出处（PUT /sources/custom
	// 在持久化之后同步调用）：立即对后续匹配生效（含清匹配缓存）。
	UpdateCustomSources(ctx context.Context, names []string) error
	// RecomputeEnrichment 对单库全部资产重算富化（自定义出处变更后的
	// 存量传导）：库内资产 size+mtime 未变时全量扫描只跳过，必须显式重算。
	RecomputeEnrichment(ctx context.Context, libraryID string) error
}

// ErrScannerUnavailable 表示扫描器尚未装配（M1 占位实现返回它）。
// handler 据此返回 503，让客户端能区分"功能没接"与"服务器坏了"。
var ErrScannerUnavailable = errors.New("httpapi: 扫描器未装配")

// ErrScanAlreadyRunning 表示该库已有一次扫描在进行中（handler 映射 409）。
// 由 main 的扫描适配器在触发前判定返回——httpapi 不感知扫描器内部状态。
var ErrScanAlreadyRunning = errors.New("httpapi: 该库扫描进行中")

// noScanner 是 Scanner 的占位实现：真实扫描器完成前，扫描端点返回 503。
// 组装时若 Deps.Scanner 为 nil 也自动落到它——宁可用显式错误交换隐式 nil panic。
type noScanner struct{}

func (noScanner) Scan(context.Context, string) error { return ErrScannerUnavailable }

func (noScanner) EnrichAsset(context.Context, string, string) error { return ErrScannerUnavailable }

func (noScanner) UpdateCustomSources(context.Context, []string) error {
	return ErrScannerUnavailable
}

func (noScanner) RecomputeEnrichment(context.Context, string) error {
	return ErrScannerUnavailable
}

// DefaultTokenTTL 是签名媒体直链的默认有效期（6h，docs/SECURITY.md 红线 5）。
const DefaultTokenTTL = 6 * time.Hour

// 媒体直链路径前缀：签名协议的组成部分（auth.SignMediaURL 按 path 逐字节
// 签名），改任一前缀 = 存量直链全部失效 + 三端全炸。生成侧（signedMediaURL
// 调用点）与分发侧（topRouter 免 Bearer 判定）必须引用这里的常量，禁止手抄
// 字符串——字符串一致性靠单一来源保证，不靠人肉（AI_README_FIRST 代码卫生 4）。
const (
	// mediaPathPrefix 直链家族总前缀（topRouter 免 Bearer 判定用）。
	mediaPathPrefix = "/media/"
	// mediaPathOrig 原图/原视频直链前缀。
	mediaPathOrig = "/media/orig/"
	// mediaPathThumb 缩略图直链前缀（thumbURL 生成侧）。
	mediaPathThumb = "/media/thumb/"
)

// readyzTimeout 就绪探针的数据库 Ping 上限：探针必须快速失败，
// 不能拖垮编排系统秒级周期的探活。
const readyzTimeout = 3 * time.Second

// Deps 是组装 httpapi.Server 的全部外部依赖（依赖注入只在 main 发生，
// 业务包之间禁止互相 new——ARCHITECTURE §5）。
type Deps struct {
	// Conn 原始数据库连接（readyz 的 PingContext、事务兜底用）。
	Conn *sql.DB
	// Queries 是 sqlc 生成的类型安全查询集。
	Queries *db.Queries
	// Bus 事件总线（SSE 的消息源；扫描进度/库变更经它推送）。
	Bus *events.Bus
	// Cfg 服务端配置（本层只读：缩略图数据目录等）。
	Cfg *config.Config
	// Thumbs 缩略图编排器（懒生成端点用）。
	Thumbs *thumbnail.Generator
	// Scanner 扫描器；nil 时落到 noScanner 占位。
	Scanner Scanner
	// SysStatus 系统快照采集函数（sysmon.Collector.Snapshot 的装配期适配，
	// 挂载点与版本号由 main 决定）；nil 时 /system/status 返回 503，
	// 与 noScanner 同语义：显式错误优于隐式 nil panic。
	SysStatus func(ctx context.Context) (sysmon.SystemStatus, error)
	// Metrics 是 Prometheus 文本输出 handler（sysmon.Default.Handler()）；
	// nil 时 /metrics 返回 503。
	Metrics http.HandlerFunc
	// MediaSecret 是媒体直链 HMAC 密钥（换密钥 = 吊销全部存量直链）。
	MediaSecret []byte
	// TokenTTL 直链有效期；<=0 用 DefaultTokenTTL。注意命名的 Token 指
	// 签名 URL 里的 exp 凭据，与 Bearer token 无关（Bearer 无过期时间，
	// 只有显式重置一条吊销路径）。
	TokenTTL time.Duration
	// Now 时钟注入（测试传固定时钟验证签名过期，不依赖真实时间 sleep）。
	Now func() time.Time
	// Logger 结构化日志；nil 用 slog.Default。
	Logger *slog.Logger
	// Version 服务版本（SSE hello 帧回显）。
	Version string
}

// Server 实现 gen.ServerInterface，并持有跨 handler 共享的状态。
type Server struct {
	conn      *sql.DB
	q         *db.Queries
	bus       *events.Bus
	cfg       *config.Config
	thumbs    *thumbnail.Generator
	scanner   Scanner
	sysStatus func(ctx context.Context) (sysmon.SystemStatus, error)
	metrics   http.HandlerFunc
	secret    []byte
	ttl       time.Duration
	now       func() time.Time
	logger    *slog.Logger

	authState  *authState
	sse        *events.Handler
	scanStates *scanStateMap // 库扫描态（内存跟踪；库表无此列，scanner 接线后回写）
	// spa 是 Web SPA 构建产物处理器（nil = 静态目录不可用，回退内嵌验收页）。
	// topRouter 的免鉴权判定依赖它是否存在（见 topRouter.ServeHTTP 注释）。
	spa     *spaHandler
	handler http.Handler // New() 组装完成的完整路由（Handler() 返回它）
}

// New 组装 HTTP 服务。返回 *Server；main 用 Handler() 拿到带完整
// 路由与鉴权链的 http.Handler 交给 http.Server，同时保留 Server 上的
// 组装期钩子（如扫描适配器接 FinishScan）。
func New(deps Deps) (*Server, error) {
	if deps.Conn == nil || deps.Queries == nil {
		return nil, errors.New("httpapi: Deps.Conn 与 Deps.Queries 必填")
	}
	if deps.Bus == nil {
		return nil, errors.New("httpapi: Deps.Bus 必填")
	}
	if deps.Cfg == nil {
		return nil, errors.New("httpapi: Deps.Cfg 必填")
	}
	if deps.Thumbs == nil {
		return nil, errors.New("httpapi: Deps.Thumbs 必填")
	}
	sc := deps.Scanner
	if sc == nil {
		sc = noScanner{}
	}
	ttl := deps.TokenTTL
	if ttl <= 0 {
		ttl = DefaultTokenTTL
	}
	now := deps.Now
	if now == nil {
		now = time.Now
	}
	logger := deps.Logger
	if logger == nil {
		logger = slog.Default()
	}
	s := &Server{
		conn:       deps.Conn,
		q:          deps.Queries,
		bus:        deps.Bus,
		cfg:        deps.Cfg,
		thumbs:     deps.Thumbs,
		scanner:    sc,
		sysStatus:  deps.SysStatus,
		metrics:    deps.Metrics,
		secret:     deps.MediaSecret,
		ttl:        ttl,
		now:        now,
		logger:     logger,
		authState:  newAuthState(),
		scanStates: newScanStateMap(),
	}
	// 服务重启后 Bearer token 仍有效：从库加载首行 token 哈希到内存
	//（SECURITY「鉴权设计」：库中只存哈希，明文 token 永不落库）。
	if err := s.authState.load(context.Background(), s.q); err != nil {
		return nil, err
	}
	s.sse = events.NewHandler(deps.Bus, events.WithVersion(deps.Version))

	// gen 生成的路由基于 Go 1.22+ 的 net/http 方法+通配符模式
	//（std-http-server 生成模式，见 gen/oapi-codegen.yaml），openapi
	// 路径已自带 /api/v1 与 /media 前缀，直接注册到根 ServeMux。
	mux := http.NewServeMux()
	api := gen.HandlerWithOptions(s, gen.StdHTTPServerOptions{
		BaseRouter: mux,
		// 绑定层错误（路径参数非法等）统一输出 Error JSON 而非裸文本，
		// 与三端 SDK 的错误反序列化约定一致。
		ErrorHandlerFunc: func(w http.ResponseWriter, r *http.Request, err error) {
			writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "请求参数不合法")
		},
	})
	// 极简验收页（免鉴权静态 HTML；页面内所有数据请求照常走 Bearer）。
	// SPA 优先（M2 起服务端随附托管 Web 构建产物）：web.static_dir 指向的
	// 目录存在且 index.html 可读时托管该目录（静态资源直发 + 前端路由
	// 回退 index.html）；目录缺失/不可用回退内嵌验收页（M1 行为不变——
	// 本机没有 dist 或未构建时服务不挂）。验收页另挂 /_debug/（SPA 可用
	// 时也可访问，serveIndex 见 page.go）。
	{
		dir := deps.Cfg.Web.StaticDir
		if spaReady(dir) {
			spa, err := newSPAHandler(dir, logger)
			if err != nil {
				// OpenRoot 失败（目录被并发删除/权限拒绝）：日志留痕并
				// 回退验收页，不因此拒绝启动。
				logger.Warn("SPA 静态目录初始化失败，回退内嵌验收页", "dir", dir, "err", err)
			} else {
				s.spa = spa
				mux.Handle("/", spa)
			}
		} else if dir != "" {
			logger.Info("未找到 Web 构建产物（web/dist），/ 使用内嵌验收页", "dir", dir)
		}
	}
	if s.spa == nil {
		// 无 SPA：M1 行为不变——/（mux 的 GET /{$} 精确匹配）与 /index.html
		// 都是验收页（topRouter 的免鉴权清单与这里双同步）。
		mux.HandleFunc("GET /{$}", serveIndex)
		mux.HandleFunc("GET /index.html", serveIndex)
	}
	// 验收页固定挂 /_debug/：调试入口不随 SPA 是否可用而变。
	mux.HandleFunc("GET /_debug/", serveIndex)
	// 根路径探针别名（运维探针，docker/k8s 惯例）：与协议面 /api/v1/healthz、
	// /api/v1/readyz 共用同一 handler，保证两边 200 + 同 body。别名不属于
	// API 协议面（openapi.yaml 只定义 /api/v1 前缀路径），docker-compose
	// healthcheck 与容器编排继续用根路径，三端 SDK 只看 /api/v1。
	mux.HandleFunc("GET /healthz", Healthz)
	mux.HandleFunc("GET /readyz", s.GetApiV1Readyz)
	s.handler = &topRouter{s: s, api: api}
	return s, nil
}

// Handler 返回完整路由与鉴权链的 http.Handler。
func (s *Server) Handler() http.Handler { return s.handler }

// SetScanner 在组装期替换扫描器实现（main 先 New 出 Server——因为适配器
// 需要 Server.FinishScan 钩子——再回填真扫描器，解除构造循环依赖）。
// 只应在启动阶段、开始服务前调用；并发请求期间换扫描器属未定义行为。
func (s *Server) SetScanner(sc Scanner) {
	if sc == nil {
		sc = noScanner{}
	}
	s.scanner = sc
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
				writeErr(w, http.StatusForbidden, "PATH_ESCAPE", "路径不合法")
				return
			}
		}
		expStr := r.URL.Query().Get("exp")
		sig := r.URL.Query().Get("sig")
		if expStr == "" || sig == "" {
			writeErr(w, http.StatusForbidden, "SIGNATURE_MISSING", "缺少签名参数")
			return
		}
		exp, err := strconv.ParseInt(expStr, 10, 64)
		if err != nil {
			writeErr(w, http.StatusForbidden, "SIGNATURE_INVALID", "签名参数不合法")
			return
		}
		if err := auth.VerifyMediaSignature(r.URL.Path, exp, sig, s.secret, s.now); err != nil {
			// 篡改与过期都映射 403，但日志区分（auth 包的两种哨兵错误），
			// 便于区分攻击探测与正常的链接过期。
			s.logger.Warn("媒体直链签名校验失败", "err", err, "path", r.URL.Path)
			writeErr(w, http.StatusForbidden, "SIGNATURE_INVALID", "签名无效或已过期")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// GetApiV1Readyz 就绪探针（协议面 GET /api/v1/readyz；根路径 /readyz 为
// 运维别名，共用本 handler）：检查数据库可达（SECURITY：探针不放行无鉴权
// 业务流量，只回答"依赖是否健康"）。
func (s *Server) GetApiV1Readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), readyzTimeout)
	defer cancel()
	if err := s.conn.PingContext(ctx); err != nil {
		writeErr(w, http.StatusServiceUnavailable, "DB_UNREACHABLE", "数据库不可达")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"status":"ready"}`))
}

// GetApiV1Healthz 存活探针（复用既有实现，语义见 healthz.go）。
func (s *Server) GetApiV1Healthz(w http.ResponseWriter, r *http.Request) {
	Healthz(w, r)
}

// GetApiV1Events 是 SSE 端点：直接代理 events 包的 Handler
// （http.HandlerFunc 天然兼容；连接上限/心跳/错误码语义全在那一侧）。
func (s *Server) GetApiV1Events(w http.ResponseWriter, r *http.Request) {
	s.sse.ServeHTTP(w, r)
}
