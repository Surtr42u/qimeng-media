// server.go：Server依赖与生命周期装配；请求管线见middleware.go。
// 包职责说明见 doc.go。
package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoredit"
	"qimeng-media/server/internal/authorimport"
	"qimeng-media/server/internal/backup"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/libraryrevision"
	"qimeng-media/server/internal/mediawrite"
	"qimeng-media/server/internal/portablebackup"
	"qimeng-media/server/internal/safego"
	"qimeng-media/server/internal/sourcematcher"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/syncchannel"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/thumbnail"
	"qimeng-media/server/internal/thumbnailwarmup"
	"qimeng-media/server/internal/uploadsess"
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
	// UpdateCustomGroups 运行期替换用户自定义出处组（PUT /sources/custom-groups
	// 在持久化之后同步调用，ADR-0033）：立即对后续匹配生效（含清匹配缓存）。
	UpdateCustomGroups(ctx context.Context, groups []sourcematcher.SourceGroup) error
	// UpdateStopWords 运行期替换停用词追加层（PUT /sources/custom-groups
	// stopWords 字段非 nil 时在持久化之后同步调用，ADR-0033）：立即对兜底
	// 提取层生效（内置基线恒生效，含清匹配缓存）。
	UpdateStopWords(ctx context.Context, words []string) error
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

func (noScanner) UpdateCustomGroups(context.Context, []sourcematcher.SourceGroup) error {
	return ErrScannerUnavailable
}

func (noScanner) UpdateStopWords(context.Context, []string) error {
	return ErrScannerUnavailable
}

func (noScanner) RecomputeEnrichment(context.Context, string) error {
	return ErrScannerUnavailable
}

// DefaultTokenTTL 是签名媒体直链的默认有效期（6h，docs/SECURITY.md 红线 5），
// 唯一来源在 config.DefaultTokenTTL——此处仅导出别名供 Deps 消费方引用。
const DefaultTokenTTL = config.DefaultTokenTTL

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

// dirPerm handler 侧新建目录的统一权限（MkdirAll 调用点原各自手抄
// 0o755；库内目录按「所有者可写、其他人可读」的 NAS 单用户口径）。
const dirPerm = 0o755

// Deps 是组装 httpapi.Server 的全部外部依赖（依赖注入只在 main 发生，
// 业务包之间禁止互相 new——ARCHITECTURE §5）。
type Deps struct {
	TXT  *authorimport.Service
	Sync *syncchannel.Service
	// Writes 是媒体写入编排服务；生产组合根显式提供，缺省仅兼容隔离测试装配。
	Writes *mediawrite.Service
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
	// Backup 备份快照管理器（调度/轮转/文件管理在 backup 包）；nil 时
	// /backups* 四端点返回 503（与 SysStatus 同语义：显式优于隐式 panic）。
	Backup *backup.Manager
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
	// Attach 上传挂靠编排服务（authorattach.Service，ADR-0019）；nil 时
	// New 内部用自身 Logger 构造缺省实例——Service 无外部依赖，缺省即
	// 生产实现，既有测试调用点零改动。
	Attach *authorattach.Service
	// Mirror 作者总表镜像写入器；nil 时同上构造缺省实例（尽力而为投影，
	// 失败只告警）。组合根（main）显式装配是规范形态（ADR-0019 DI 在 main）。
	Mirror *authorattach.MirrorWriter
	// AuthorEdit 作者编辑用例服务（authoredit.Service，Q1 事务下沉）：在
	// 单事务内编排 authorattach 原语并在提交成功后刷新镜像。nil 时 New
	// 内部用同源 Conn/Queries/Attach/Mirror/Now/Logger 构造缺省实例——
	// 服务只依赖注入的原语，缺省即生产实现，不创建第二套权威状态；
	// 生产 main 显式装配是规范形态。
	AuthorEdit *authoredit.Service
	// Revision 库内容修订号服务（GET /api/v1/library/revision 与 bump 链
	// 的唯一读写通道，libraryrevision 包）；nil 时 New 内部用 deps.Queries
	// 构造缺省实例并引导基线——Service 只依赖 store，缺省即生产实现。
	Revision *libraryrevision.Service
}

// Server 实现 gen.ServerInterface，并持有跨 handler 共享的状态。
type Server struct {
	writes    *mediawrite.Service
	conn      *sql.DB
	q         *db.Queries
	bus       *events.Bus
	cfg       *config.Config
	thumbs    *thumbnail.Generator
	scanner   Scanner
	sysStatus func(ctx context.Context) (sysmon.SystemStatus, error)
	backup    *backup.Manager
	metrics   http.HandlerFunc
	secret    []byte
	ttl       time.Duration
	now       func() time.Time
	logger    *slog.Logger

	authState  *authState
	authLimit  *authLimiter
	sse        *events.Handler
	scanStates *scanStateMap // 库扫描态（内存跟踪；库表无此列，scanner 接线后回写）
	// recommendCache 推荐流响应缓存（recommend_cache.go，2026-09-18 性能批）。
	recommendCache *recommendCache
	// dirs 目录树缓存（dirs_cache.go，审计 R3 2026-09-20 性能批）。
	dirs *dirsCache
	// startedAt 进程装配时刻（≈启动时刻）：缩略图开机回填静默窗的时间基准
	//（thumbnail_warmup.go waitForBootQuietWindow）。
	startedAt time.Time
	// spa 是 Web SPA 构建产物处理器（nil = 静态目录不可用，回退内嵌验收页）。
	// topRouter 的免鉴权判定依赖它是否存在（见 topRouter.ServeHTTP 注释）。
	spa     *spaHandler
	handler http.Handler // New() 组装完成的完整路由（Handler() 返回它）
	// attach 上传挂靠编排服务（Deps.Attach 的落位；nil 兜底见 New）。
	attach *authorattach.Service
	// mirror 作者总表镜像写入器（Deps.Mirror 的落位）。
	mirror *authorattach.MirrorWriter
	// authorEdit 作者编辑用例服务（Deps.AuthorEdit 的落位）：作者管理/
	// 来源区/资产作者替换/词表预填的事务宿主（Q1 事务下沉）。
	authorEdit *authoredit.Service
	// rev 库内容修订号服务（Deps.Revision 的落位；handler 与 bump 链共用）。
	rev *libraryrevision.Service
	// uploads 断点续传上传会话管理器（uploadsess 包，ADR-0028；New 内按
	// Cfg.DataDir 构造，时钟/日志与 Server 同源注入）。
	uploads *uploadsess.Manager
	// sync 持有本机同步业务，HTTP 只读快照和触发；txt 为跨入口共用作者导入服务。
	sync        *syncchannel.Service
	txt         *authorimport.Service
	portable    *portablebackup.Service
	thumbWarmup *thumbnailwarmup.Service
}

// New 组装 HTTP 服务。返回 *Server；main 用 Handler() 拿到带完整
// 路由与鉴权链的 http.Handler 交给 http.Server，同时保留 Server 上的
// 组装期钩子（如扫描适配器接 FinishScan）。
// 超函数警戒线（>100 行）理由：构造器直线装配（Deps 校验→默认值
// 兜底→子系统初始化→路由挂载），每子系统一段、无嵌套分支；拆分会把
// 半组装的 Server 状态跨函数传递。
// 装配顺序涵盖密钥/镜像/业务宿主/后台订阅，保留显式依赖，不另造容器状态。
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
	attach := deps.Attach
	if attach == nil {
		attach = &authorattach.Service{Logger: logger}
	}
	mirror := deps.Mirror
	if mirror == nil {
		mirror = &authorattach.MirrorWriter{Logger: logger}
	}
	// 作者编辑用例缺省兜底：复用上面同源解析的 attach/mirror/logger/now，
	// 不另建第二套权威状态；生产 main 显式注入同一实例（ADR-0019 DI 在
	// main，缺省仅兼容既有隔离测试装配）。
	authorEdit := deps.AuthorEdit
	if authorEdit == nil {
		authorEdit = authoredit.New(authoredit.Options{
			Conn: deps.Conn, Queries: deps.Queries,
			Attach: attach, Mirror: mirror, Now: now, Logger: logger,
		})
	}
	rev := deps.Revision
	if rev == nil {
		rev = libraryrevision.NewService(deps.Queries, logger, now)
	}
	s := &Server{
		conn:           deps.Conn,
		q:              deps.Queries,
		bus:            deps.Bus,
		cfg:            deps.Cfg,
		thumbs:         deps.Thumbs,
		scanner:        sc,
		sysStatus:      deps.SysStatus,
		backup:         deps.Backup,
		metrics:        deps.Metrics,
		secret:         deps.MediaSecret,
		ttl:            ttl,
		now:            now,
		logger:         logger,
		attach:         attach,
		mirror:         mirror,
		authorEdit:     authorEdit,
		rev:            rev,
		authState:      newAuthState(),
		authLimit:      newAuthLimiter(authRateLimitMax, authRateLimitWindow),
		scanStates:     newScanStateMap(),
		recommendCache: newRecommendCache(),
		dirs:           newDirsCache(now),
		startedAt:      now(),
	}
	s.writes = deps.Writes
	s.thumbWarmup = thumbnailwarmup.New(s.q, s.thumbs, logger, s.startedAt.Add(s.cfg.Thumbnail.WarmupDelay))
	if s.writes == nil {
		s.writes = mediawrite.New(mediawrite.Options{Conn: deps.Conn, Queries: deps.Queries,
			Bus: deps.Bus, Config: deps.Cfg, Thumbs: deps.Thumbs, Logger: logger, Now: now,
			Revision: rev, MoveFile: func(src, dst string) error { return moveFile(src, dst) },
			Enrich: func(ctx context.Context, libraryID, assetID string) error {
				err := s.scanner.EnrichAsset(ctx, libraryID, assetID)
				if errors.Is(err, ErrScannerUnavailable) {
					return nil
				}
				return err
			}})
	}
	s.txt = deps.TXT
	if s.txt == nil {
		s.txt = authorimport.New(deps.Conn, deps.Queries, now, logger)
	}
	s.portable = portablebackup.New(portablebackup.Options{Conn: s.conn, Queries: s.q, TXT: s.txt, Mirror: s.mirror, Now: s.now, Logger: s.logger, Changed: s.notifyPortableImport})

	s.sync = deps.Sync
	if s.sync == nil {
		s.sync = syncchannel.New(syncchannel.Options{Config: deps.Cfg, Queries: deps.Queries, Writes: s.writes, TXT: s.txt, Mirror: mirror, Logger: logger, Now: now})
	}

	s.uploads = s.writes.UploadSessions()
	// 服务重启后 Bearer token 仍有效：从库加载首行 token 哈希到内存
	//（SECURITY「鉴权设计」：库中只存哈希，明文 token 永不落库）。
	if err := s.authState.load(context.Background(), s.q); err != nil {
		return nil, err
	}
	// 库内容修订号基线引导：键缺失时以 COUNT(assets)+1 播种（幂等）。DB
	// 失败拒绝启动——与 authState.load 同语义：数据层没准备好，服务不该
	// 带着未知状态对外提供"跳过同步"的依据。
	if err := rev.EnsureBaseline(context.Background()); err != nil {
		return nil, err
	}
	// 推荐流缓存失效主通道（2026-09-18 性能批二段）：订阅 library.changed——
	// watch 增量事件/手动扫描完成/上传入库/回收站/标签/整理/库开关全部经此
	// 事件汇出，一处订阅全覆盖（跨包触发走事件总线，AI_README 代码卫生约束
	// 7）。不发该事件的变更（点赞/收藏/导入/作者重建）保留各 handler 直接
	// 调用 invalidateRecommendCache。周期轮询扫描零变更时不发事件，缓存得以
	// 跨扫描存活。
	// 同一订阅顺带推进库内容修订号（revision.go bumpLibraryRevision）：
	// 扫描/watch/上传/回收站移入恢复等写入路径都发布 library.changed，在
	// 订阅侧自增一次即全量覆盖，scanner 等业务包零新增依赖（事件总线解耦）。
	if sub, err := s.bus.Subscribe(events.TopicLibraryChanged); err != nil {
		logger.Warn("推荐缓存失效订阅失败（仅影响缓存新鲜度，TTL 兜底）", "err", err)
	} else {
		safego.Go(logger, "库变更订阅", func() {
			for event := range sub.C {
				s.invalidateRecommendCache()
				s.bumpLibraryRevision()
				if event.LibraryChange == nil {
					s.WarmupAfterScan()
				} else {
					s.thumbWarmup.NotifyAsset(event.LibraryChange.LibraryID, event.LibraryChange.AssetID)
				}
				s.writes.RefreshLibraryFileMetrics()
			}
		})
	}
	// SSE 连接数 gauge（OBSERVABILITY sse_connections）经 Option 回调注入：
	// events 包不感知 sysmon，装配期单点接线（绝对值 Set 语义在回调侧）。
	s.sse = events.NewHandler(deps.Bus, events.WithVersion(deps.Version),
		events.WithConnectionGauge(sysmon.Default.SetSSEConnections))

	// gen 生成的路由基于 Go 1.22+ 的 net/http 方法+通配符模式
	//（std-http-server 生成模式，见 gen/oapi-codegen.yaml），openapi
	// 路径已自带 /api/v1 与 /media 前缀，直接注册到根 ServeMux。
	mux := http.NewServeMux()
	api := gen.HandlerWithOptions(s, gen.StdHTTPServerOptions{
		BaseRouter: mux,
		// HTTP 请求指标中间件：一处覆盖全部 gen 路由，行为与排除清单
		// 见 metrics_middleware.go 与 docs/OBSERVABILITY.md。
		Middlewares: []gen.MiddlewareFunc{newMetricsMiddleware(sysmon.Default)},
		// 绑定层错误（路径参数非法等）统一输出 Error JSON 而非裸文本，
		// 与三端 SDK 的错误反序列化约定一致。
		ErrorHandlerFunc: func(w http.ResponseWriter, r *http.Request, err error) {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "请求参数不合法")
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
	// 中间件链（外→内）：安全响应头 → Host 校验（DNS rebinding 防御，
	// 403 响应也带安全头）→ 鉴权分发。
	s.handler = securityHeaders(s.hostCheck(&topRouter{s: s, api: api}))
	return s, nil
}

// Handler 返回完整路由与鉴权链的 http.Handler。
func (s *Server) Handler() http.Handler { return s.handler }

// rollbackTx 事务收尾回滚的统一口（Q6 忽略错误清理批）：Commit 之后的
// Rollback 返回 sql.ErrTxDone，属事务已正常结束的预期 no-op，静默；真实
// 回滚失败记录告警留痕，不覆盖调用方已上抛/已记录的业务错误。同包多处
// 同语义收口于此；authoredit 等其他包各持同款纪律，不做跨包万能件。
func (s *Server) rollbackTx(ctx context.Context, what string, tx *sql.Tx) {
	if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
		s.logger.WarnContext(ctx, what+": 事务回滚失败", "error", err)
	}
}

// SetScanner 在组装期替换扫描器实现（main 先 New 出 Server——因为适配器
// 需要 Server.FinishScan 钩子——再回填真扫描器，解除构造循环依赖）。
// 只应在启动阶段、开始服务前调用；并发请求期间换扫描器属未定义行为。
func (s *Server) SetScanner(sc Scanner) {
	if sc == nil {
		sc = noScanner{}
	}
	s.scanner = sc
}

// GetApiV1Readyz 就绪探针（协议面 GET /api/v1/readyz；根路径 /readyz 为
// 运维别名，共用本 handler）：检查数据库与存储可达/余量（SECURITY：探针不放行无鉴权
// 业务流量，只回答"依赖是否健康"）。
func (s *Server) GetApiV1Readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), readyzTimeout)
	defer cancel()
	if err := s.conn.PingContext(ctx); err != nil {
		writeErr(w, http.StatusServiceUnavailable, codeDbUnreachable, "数据库不可达")
		return
	}
	if err := s.writes.CheckStorage(ctx); err != nil {
		writeErr(w, http.StatusServiceUnavailable, codeStorageUnavailable, "存储目录不可用或剩余空间不足")
		return
	}
	w.Header().Set("Content-Type", contentTypeJSON)
	if _, err := w.Write([]byte(`{"status":"ready"}`)); err != nil {
		s.logger.WarnContext(r.Context(), "写出就绪探针响应失败", "error", err)
	}
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
