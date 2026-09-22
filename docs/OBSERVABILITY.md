# OBSERVABILITY - 监控与仪表盘

> 对应用户需求 #9：「NAS 后端要有统计，比如负载、流量那种」。
> 方案定论：**服务端内置轻量监控**（零外部依赖），Prometheus/Grafana 全家桶作为后置可选增强。
> 最后更新：2026-09-19（新增 backup_last_success_timestamp gauge——备份快照成功时刻，任务Q 批B）。2026-09-12（文档准确性清偿：系统指标表两处未采集项如实标注、访问日志/轮转标规划、仪表盘路由勘误、轮询周期勘误、readyz 检查面勘误）

## 内置监控（M2 交付）

### 系统指标（gopsutil 采集，跨平台：PC 开发与 NAS 运行行为一致）

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
| trash_items / trash_bytes（Gauge） | 回收站条目数与占用 |
| backup_last_success_timestamp（Gauge） | 最近一次成功备份快照的完成时刻（Unix 秒；0=进程内尚无成功快照，2026-09-19 任务Q 批B 起） |

系统快照 `sysmon.Collector.Snapshot()` 输出与 openapi SystemStatus 对齐；PerCore（按核 CPU 明细）已补进协议并接线 `/api/v1/system/status` 与 `/metrics`（Bearer 鉴权，httpapi/system.go，2026-08-27 M2 后端）。

### 指标埋点接线与口径注记（2026-09-06，9 族全量接线）

埋点落位（除 upload 族 M2 已接外，其余于 2026-09-06 全部接线完毕）：

| 指标族 | 埋点位置 | 更新时机 |
|---|---|---|
| http_requests_total / http_request_duration_seconds | httpapi/metrics_middleware.go，挂 gen `StdHTTPServerOptions.Middlewares` 一处覆盖全部 gen 路由 | 每请求（经路由匹配后） |
| media_bytes_total{kind} | httpapi/media.go 两处直链（orig/thumb）`http.ServeContent` 外套字节计数 | 每次直链输出后按实发字节 |
| sse_connections | events/sse.go `WithConnectionGauge` 回调（server.go 装配期注入） | 连接建立/断开时 Set 绝对值 |
| scan_duration_seconds | scanner/scanner.go `Scan` 成功返回前 | 每次全量扫描成功 |
| upload_total / upload_bytes_total | httpapi/upload.go（M2 已接） | 上传成功/失败时 |
| library_files{type} | httpapi/libraries.go `refreshLibraryFileMetrics` | 变更点推送刷新（见下） |
| thumb_queue_depth | thumbnail/pool.go Submit 入队/work 取任务 | 队列每变化一次 |
| trash_items / trash_bytes | httpapi/trash.go `refreshTrashMetrics` | 变更点推送刷新（见下） |
| backup_last_success_timestamp | backup 包 `OnSuccess` 回调（main 装配期接 `sysmon.Default.SetBackupLastSuccess`，backup 包自身不感知 sysmon） | 每次快照成功后 |

**口径注记（改动埋点或解读指标前必读）**：

1. **http 指标的 endpoint 是路由模板**（如 `/api/v1/assets/{assetId}`，取 `r.Pattern` 去方法前缀），不是实际 URL——低基数红线，禁改用 `r.URL.Path`（路径参数会撑爆时间序列）。
2. **排除清单**：`/metrics`、`/api/v1/healthz`、`/api/v1/readyz`、`/api/v1/events` 四条路由即使命中处理也不进 http 计数与延迟直方图。`/api/v1/events` 是 SSE 分钟级长流，qps/duration 对它无意义（长尾污染），在线数由 sse_connections gauge 单独覆盖。佐证方式：请求若干业务端点后 `/metrics` 输出的 `http_requests_total` 中不含 events 路径行。
3. **http 指标不覆盖三类请求**：参数绑定失败的 400（gen 绑定层在中间件之前短路返回）、未匹配路由的 404（ServeMux 直接兜底）与方法不匹配的 405（同为 ServeMux 兜底，不经包装器）。三者均不产生 http_requests_total / duration 序列，属既定口径而非缺陷。
4. **scan_duration_seconds 仅在扫描成功返回时 Set**（与 help「上次全量扫描耗时」一致）：失败路径保留上次成功值。API 触发与 watch 轮询两条扫描入口汇聚在 `Scanner.Scan`，单点覆盖。
5. **library_files 与 trash_items/trash_bytes 是变更点推送刷新**，不是定时采样：library_files 刷新时机 = 扫描完成（FinishScan）/上传入库/删除进回收站/回收站恢复四处；trash 刷新时机 = 删除入站/恢复/单条物理删除/清空回收站四处。两次变更之间指标保持上次值（秒级陈旧可接受）。trash 的真实数据源是磁盘 meta 文件遍历，不是库表（trash_items 表为历史迁移遗留，只留不读）。
6. **thumb_queue_depth 已接线、当前恒 0**：工作池就绪但 M3 缩略图预热未接入，尚无生产者提交任务——属"接线完成待激活"，非故障；M3 预热接入后自动出数。

### 展示（两种）

1. **Web 维护页仪表盘**（路由 `/app/maintenance`，登录门禁内——单用户形态无独立管理权限分层）：实时刷新的卡片+趋势小图（复用 Web 端图表组件，样式与主界面统一）。默认视图 = 用户要的"网络上那种负载/流量面板"。
2. **标准 `/metrics` 端点**（Prometheus 文本格式）：将来接 Grafana 时直接抓取，无需改代码。

## 日志

- `slog` 结构化 JSON 日志：时间/级别/模块/事件/关键字段（直写 stdout，无轮转落盘——按大小滚动/存数据目录为规划项）。
- 访问日志（方法/路径/状态/耗时/token 前 4 位）为规划项未实现：当前请求级可观测性由 http_requests_total / http_request_duration_seconds 指标覆盖（计数与延迟，不含逐请求日志）。实现时须遵守 SECURITY 红线 7 脱敏口径。
- 等级：默认 info；`QM_LOG_LEVEL=debug` 排障；循环内禁止逐条日志（旧项目"50 次记 1 条采样"经验保留）。

## 健康检查

`GET /api/v1/healthz`（免鉴权，`api/openapi.yaml` 标 `security: []`）：进程存活；`GET /api/v1/readyz`（免鉴权，同样 `security: []`）：**当前实现只检查数据库可达**（PingContext，httpapi/server.go GetApiV1Readyz；媒体库目录可达/磁盘余量告警为规划扩展，接入时需同步更新本节与协议描述）。服务端同时保留根路径 `/healthz`、`/readyz` 作为运维探针别名（docker/k8s 惯例，行为一致，不属于 API 协议面）——Docker healthcheck 与 fnOS 部署用根路径即可。

## 后置可选：Grafana 增强包（不进默认部署）

想看长期历史曲线（7 天/30 天）时：docker-compose 追加 Prometheus + Grafana 两个容器，抓取 /metrics，导入预置 Dashboard JSON（接入本增强包时随包交付，当前仓库未内置）。默认不装——单用户内网场景内置面板覆盖 95% 需求，全家桶只服务"想折腾"的场景。

## 事件推送（SSE）与监控的关系

扫描进度、缩略图队列进度、上传进度走 SSE 实时推到客户端 UI（进度条）；系统监控面板数据由前端 2 秒轮询专用轻量接口（`STATUS_POLL_INTERVAL_MS=2000`，web/src/lib/constants.ts——不塞进 SSE，职责分离）。
