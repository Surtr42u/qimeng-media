# OBSERVABILITY - 监控与仪表盘

> 对应用户需求 #9：「NAS 后端要有统计，比如负载、流量那种」。
> 方案定论：**服务端内置轻量监控**（零外部依赖），Prometheus/Grafana 全家桶作为后置可选增强。
> 最后更新：2026-08-22

## 内置监控（M2 交付）

### 系统指标（gopsutil 采集，跨平台：PC 开发与 NAS 运行行为一致）

| 指标 | 说明 |
|---|---|
| CPU 使用率 | 整体 + 按核心；缩略图生成期间可见 CPU 峰值 |
| 内存 | 进程 RSS + 系统已用/总量 |
| 磁盘 | 媒体库与数据目录的容量/可用空间、I/O 读写速率 |
| 网络 | 各网卡实时上下行流量、自启动以来的累计流量 |
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

系统快照 `sysmon.Collector.Snapshot()` 输出与 openapi SystemStatus 对齐；PerCore（按核 CPU 明细）已在快照中实现，**待接线 /api/v1/system/status 时补进协议并 make sdk**（此处记待办，2026-08-22）。

### 展示（两种）

1. **Web 管理页仪表盘**（`/admin`，管理权限）：实时刷新的卡片+趋势小图（复用 Web 端图表组件，样式与主界面统一）。默认视图 = 用户要的"网络上那种负载/流量面板"。
2. **标准 `/metrics` 端点**（Prometheus 文本格式）：将来接 Grafana 时直接抓取，无需改代码。

## 日志

- `slog` 结构化 JSON 日志：时间/级别/模块/事件/关键字段。
- 访问日志：方法/路径/状态/耗时/客户端标识（token 前 4 位，见 SECURITY.md 脱敏）。
- 日志轮转：按大小滚动保留最近 N 份，存数据目录。
- 等级：默认 info；`QM_LOG_LEVEL=debug` 排障；循环内禁止逐条日志（旧项目"50 次记 1 条采样"经验保留）。

## 健康检查

`GET /healthz`（免鉴权）：进程存活；`GET /readyz`（免鉴权，与 /healthz 同样在 `api/openapi.yaml` 标 `security: []`，见 openapi.yaml:380）：数据库可读写 + 媒体库目录可达 + 磁盘余量告警阈值。Docker healthcheck 与 fnOS 部署都用它。

## 后置可选：Grafana 增强包（不进默认部署）

想看长期历史曲线（7 天/30 天）时：docker-compose 追加 Prometheus + Grafana 两个容器，抓取 /metrics，导入预置 Dashboard JSON（仓库 `deploy/grafana/`）。默认不装——单用户内网场景内置面板覆盖 95% 需求，全家桶只服务"想折腾"的场景。

## 事件推送（SSE）与监控的关系

扫描进度、缩略图队列进度、上传进度走 SSE 实时推到客户端 UI（进度条）；系统监控面板数据由前端 3 秒轮询专用轻量接口（不塞进 SSE，职责分离）。
