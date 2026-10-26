# OBSERVABILITY - 监控与仪表盘

> 对应用户需求 #9：「NAS 后端要有统计，比如负载、流量那种」。
> 方案定论：**服务端内置轻量监控**（无需外置监控服务），Prometheus/Grafana 全家桶作为后置可选增强。
> 维护日期：2026-10-25。变更经过见 CHANGELOG 与 git 历史。

## 内置监控（M2 交付）

### 系统指标（gopsutil跨平台采集；目标NAS的权限、挂载与硬件仍需验收）

| 指标 | 说明 |
|---|---|
| CPU 使用率 | 整体 + 按核心；缩略图生成期间可见 CPU 峰值 |
| 内存 | 进程 RSS + 系统已用/总量 |
| 磁盘 | 媒体库与数据目录的容量/可用空间（I/O 读写速率为规划项——采集层暂无 IO 计数器） |
| 网络 | 自启动以来的累计上下行流量（聚合 netRx/netTx bytes；按网卡明细为规划项） |
| 运行时长 | 进程 uptime、Go 版本、构建版本 |

### 业务指标（服务端自身埋点，prometheus client_golang）

实现落位 `server/internal/sysmon/metrics.go`（独立 Registry，promauto 标准用法，接线 /metrics 时直接用 `sysmon.Default.Handler()`）：

| 指标名（prometheus） | 说明 |
|---|---|
| http_requests_total{endpoint,method,code} | 请求 QPS 分维计数 |
| http_request_duration_seconds{endpoint} | 延迟直方图（buckets 5ms~60s，内网取值） |
| media_bytes_total{kind=orig\|thumb\|video} | 媒体流量累计（用户最关心的"流量"） |
| upload_total{result=ok\|fail} / upload_bytes_total | 上传成功/失败与累计字节 |
| sse_connections（Gauge） | SSE 在线连接数 |
| scan_duration_seconds（Gauge） | 上次全量扫描耗时 |
| library_files{type=image\|video}（Gauge） | 库内文件数 |
| thumb_queue_depth（Gauge） | 待生成缩略图队列深度 |
| thumb_work（Gauge，state） | admitted：持引用的不同表示；waiting：等待执行闸的单表示体；running：持执行闸体。admitted 含集合尚未释放的已完成尺寸引用，不能直接相减推导排队数 |
| thumb_work_events_total（Counter，result） | merged/rejected/failed；合并包括集合/尺寸层；拒绝为预算满，failed 为单资产准备或生成错误（服务取消不计）。标签不含资产或路径 |
| trash_items / trash_bytes（Gauge） | 回收站条目数与占用 |
| backup_last_success_timestamp（Gauge） | 最近一次成功备份快照的完成时刻（Unix 秒；0=进程内尚无成功快照，2026-09-19 任务Q 批B 起） |

系统快照 `sysmon.Collector.Snapshot()` 输出与 openapi SystemStatus 对齐；PerCore（按核 CPU 明细）已补进协议并接线 `/api/v1/system/status` 与 `/metrics`（Bearer 鉴权，httpapi/system.go，2026-08-27 M2 后端）。

### 指标埋点接线与口径注记

当前埋点落位；新增指标与已有指标分别按下表更新，不以早期接线数量概括现状：

| 指标族 | 埋点位置 | 更新时机 |
|---|---|---|
| http_requests_total / http_request_duration_seconds | httpapi/metrics_middleware.go，挂 gen `StdHTTPServerOptions.Middlewares` 一处覆盖全部 gen 路由 | 每请求（经路由匹配后） |
| media_bytes_total{kind} | httpapi/media.go 两处直链（orig/thumb）`http.ServeContent` 外套字节计数 | 每次直链输出后按实发字节 |
| sse_connections | events/sse.go `WithConnectionGauge` 回调（server.go 装配期注入） | 连接建立/断开时 Set 绝对值 |
| scan_duration_seconds | scanner/scanner.go `Scan` 成功返回前 | 每次全量扫描成功 |
| upload_total / upload_bytes_total | mediawrite 上传/入库（HTTP 只适配，2026-10-21 下沉） | 保留既有计量时机；未把全部失败分类重新定义 |
| library_files{type} | mediawrite 单 worker，HTTP/扫描/库事件只标脏 | 启动立即查询、变更有界合并（见下） |
| thumb_queue_depth | thumbnail/pool.go Submit 入队/work 取任务 | 队列每变化一次 |
| thumb_work / thumb_work_events_total | thumbnail/budget.go、generate.go、detached.go | 表示引用变化、等闸/持闸变化及合并/拒绝/活动期失败；不把正常关停计failed |
| trash_items / trash_bytes | mediawrite `RefreshTrashMetrics` | 变更点推送刷新（见下） |
| backup_last_success_timestamp | backup 包 `OnSuccess` 回调（main 装配期接 `sysmon.Default.SetBackupLastSuccess`，backup 包自身不感知 sysmon） | 每次快照成功后 |

**口径注记（改动埋点或解读指标前必读）**：

1. **http 指标的 endpoint 是路由模板**（如 `/api/v1/assets/{assetId}`，取 `r.Pattern` 去方法前缀），不是实际 URL——低基数红线，禁改用 `r.URL.Path`（路径参数会撑爆时间序列）。
2. **排除清单**：`/metrics`、`/api/v1/healthz`、`/api/v1/readyz`、`/api/v1/events` 四条路由即使命中处理也不进 http 计数与延迟直方图。`/api/v1/events` 是 SSE 分钟级长流，qps/duration 对它无意义（长尾污染），在线数由 sse_connections gauge 单独覆盖。佐证方式：请求若干业务端点后 `/metrics` 输出的 `http_requests_total` 中不含 events 路径行。
3. **http 指标不覆盖三类请求**：参数绑定失败的 400（gen 绑定层在中间件之前短路返回）、未匹配路由的 404（ServeMux 直接兜底）与方法不匹配的 405（同为 ServeMux 兜底，不经包装器）。三者均不产生 http_requests_total / duration 序列，属既定口径而非缺陷。
4. **scan_duration_seconds 仅在扫描成功返回时 Set**（与 help「上次全量扫描耗时」一致）：失败路径保留上次成功值。API 触发与 watch 轮询两条扫描入口汇聚在 `Scanner.Scan`，单点覆盖。
5. **library_files 合并刷新，trash_items/trash_bytes 仍按变更点推送**：library_files 开机立即读取一次；上传/扫描完成/回收站/恢复及 library.changed 只标脏，由一个 worker 在首个信号后一秒查询（固定合并窗，不随连续上传延期）。查询仍限三秒；期间新变更进入下一轮，失败保留上次成功值并按同节奏再试，停机取消并收齐查询后再关库。无脏位不查询，正常收敛时间包括一秒等待与本次 SQL，故障期间可能更陈旧。此为监控近似新鲜度，不影响已提交业务成功。trash 刷新时机仍为删除入站/恢复/物理删除/清空四处及到期清扫有清除时；真实源仍为磁盘 meta，trash_items 历史表不读。
6. **缩略图指标分工**：thumb_queue_depth仅表示Submit后台队列长度；thumb_work分别记录共用表示预算、等闸与持闸体，前台不由队列长度代表。admitted包含集合未释放的已完成尺寸引用，不能据此相减推算等待数；产品页面尚未完整呈现这些内部指标。

### 展示（两种）

1. **Web 维护页仪表盘**（路由 `/app/maintenance`，登录门禁内——单用户形态无独立管理权限分层）：紧凑运行概况+接收/发送小趋势+CPU/内存/存储，完整双向曲线可展开，维护快捷入口连接原有操作。网络仍是全机网卡聚合，非影库专属流量；2s轮询，按实际成功采样时间差求速率，窗口最近两分钟且最多60点，重启/计数器回退重新取基线。监控失败保留旧读数并明示，不代表健康检查通过。存储合计沿用接口返回的监控目录容量；同卷目录可能重复计入，全部条目/路径可展开，不声称为去重物理磁盘容量。
2. **标准 `/metrics` 端点**（Prometheus 文本格式）：将来接 Grafana 时直接抓取，无需改代码。

## 日志

- `slog` 结构化 JSON 日志：时间/级别/模块/事件/关键字段（直写 stdout；容器部署的落盘轮转已实现——`deploy/docker-compose.yml` 对服务配置 json-file 驱动，max-size 10MB × max-file 3 封顶，2026-10-01 第四百一十九笔；非 Docker 形态的应用层自行落盘仍为规划项）。
- 访问日志（方法/路径/状态/耗时/token 前 4 位）为规划项未实现：当前请求级可观测性由 http_requests_total / http_request_duration_seconds 指标覆盖（计数与延迟，不含逐请求日志）。实现时须遵守 SECURITY 红线 7 脱敏口径。
- `推荐顺序冷算分段耗时`：仅顺序快照未命中时记录 seed/candidates/query/algorithm。query 包含规模观察、事件/标签查询、行解码、标签映射及 Item 转换；algorithm 包含完整评分/混合及直接 ID 装配。输入复用拆分后 Item 转换从 algorithm 移至 query，比较旧分段数据时需考虑该边界变化。命中取页仍查询元数据、装配作者/COS 字段并回写展示计数，这些及单飞等待/HTTP 编码/网络不计入该日志。完整请求延迟使用 HTTP 指标，旧限量页算法日志不可直接与完整顺序计算比较。

- `推荐预热共享输入装载完成`：本轮首次需要冷算时记录 candidates/elapsedMs（查询、映射及 Item 转换），另记 prepareMs 表示本轮只执行一次的 seed 无关算法准备；elapsedMs 仍只含装载转换。两个 seed 独立评分，完成日志统计整轮耗时；全命中不记录装载日志。结构性失效中止预热记 Debug，读取/计算错误记 Warn，不写展示计数。

- 等级：默认 info；`QIMENG_LOG_LEVEL=debug` 排障；循环内禁止逐条日志（旧项目"50 次记 1 条采样"经验保留）。
- 后台轮询和事件触发的整库扫描完成日志包含 `libraryId/scanned/elapsedMs/added/updated/moved/removed`；耗时不含等待跨库后台扫描名额的时间。四个变更计数全零时为 debug，有变化时为 info；错误仍为 warn/error，不能从控制台不再刷空扫推断扫描已停止。`scan_duration_seconds` 的“上次成功全量扫描”口径保持，新增日志提供分库证据，不新增带库 ID 标签的指标序列。

缩略图回填仅在有实际投递时记录 Info：`candidates` 为本轮分页检查资产数、`submitted` 为成功投递数、`full` 区分全量和局部范围、`elapsedMs` 为整轮耗时（含队列背压，不等于全部生成完成）。局部 candidates 不作为覆盖进度分母；空轮不记 Info，查询或投递失败记 Warn，保留范围待办按变更合并间隔重试；停机取消/池关闭不作为业务故障：生成侧按服务根context区分失败，即使ffmpeg仅返回signal killed也不递增failed；工作池在根取消后仅记Debug停机信息，panic仍保留Error。队列指标继续沿用既有出口。

## 健康检查

`GET /api/v1/healthz`（免鉴权，`api/openapi.yaml` 标 `security: []`）：进程存活；`GET /api/v1/readyz`（免鉴权，同样 `security: []`）：检查数据库、数据目录及全部启用媒体根的可读性与最小剩余空间；任一异常返回 503（存储错误为 STORAGE_UNAVAILABLE，响应不含路径）。存储探测只读、不写探针文件，不证明卷可写或可抵御外部占盘；底层文件系统阻塞不保证能被请求超时立即中止。服务端同时保留根路径 `/healthz`、`/readyz` 作为运维探针别名（docker/k8s 惯例，行为一致，不属于 API 协议面）——根路径别名可用；deploy 样例实际用 /api/v1/healthz。

历史资产/事件/统计一致性可由 AI 在用户授权后运行[只读对账工具](../tools/README.md#历史数据只读对账)：数据库同一只读快照核对关联和事件→日统计，文件仅 stat，可选便携 v2 备份逐条比对。结果须保留检查范围与目标增量，不能从 readyz 200 或一次对账外推全部同步异常场景、全部媒体字节或手机待传队列通过；在线文件变化与时区差异须先排除。报告与备份只放忽略目录，不展示私人媒体。

## 后置可选：Grafana 增强包（不进默认部署）

想看长期历史曲线（7 天/30 天）时：docker-compose 追加 Prometheus + Grafana 两个容器，抓取 /metrics，导入预置 Dashboard JSON（接入本增强包时随包交付，当前仓库未内置）。默认不装；内置面板覆盖基础系统/业务指标，长期历史曲线需要另行接入采集与存储。

## 事件推送（SSE）与监控的关系

当前SSE发布扫描进度（scan.progress）、库内容变更（library.changed）、上传完成（upload.done）及收藏/点赞变更信号。上传传输进度由客户端自身的XHR/Worker更新，upload.done不是逐字节进度。thumbnail.progress仅预留主题，尚无生产发布者；缩略图覆盖通过GET /api/v1/thumbnails/progress读取，不能称其已接SSE。系统监控面板仍由use-system-status按STATUS_POLL_INTERVAL_MS=2000轮询专用接口；内部指标不自动成为SSE载荷。
