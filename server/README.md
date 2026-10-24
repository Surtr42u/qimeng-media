# server - Go 服务端开发入口

> Go 模块化单体；当前模块和依赖边界见 [ARCHITECTURE §5](../docs/ARCHITECTURE.md#5-服务端模块边界serverinternal)。只读本次影响模块，不默认通读 ADR。

## 定位任务

| 工作 | 入口 |
|---|---|
| 组合根/装配 | cmd/qimeng/；配置 → 模块装配 → HTTP |
| 协议/请求适配 | internal/httpapi/；先查 OpenAPI 与 API 专题 |
| 领域纯函数 | recommend、stats、sourcematcher、authoring；先查领域专题 |
| 持久化 | internal/store/、migrations/；ADR-0011 迁移纪律 |
| 扫描/身份合并 | internal/scanner/；ADR-0004、0039 |
| 文件操作/恢复意图 | internal/filing/；SECURITY 文件边界、领域 files |
| 移动/改名 | internal/mediawrite/move.go；先文件后库行、冲突拒绝、失败无覆盖回滚，根内改名保护在 filing/move_root.go |
| 回收站/恢复/清扫 | internal/mediawrite/trash_*；HTTP 只协议适配，filing 保留低层路径/意图机制 |
| 推荐流装载 | internal/recommendfeed/；限容量 ID 顺序快照跨页共享、取页重新校验资格；返回原始下一 offset（-1 到底），短页不截断续页，见推荐专题与 ADR-0046 |
| 排行页装载 | internal/ranking/；专用热度键 → recommend 纯函数选页 → 当前页元数据/累计计数，见推荐专题 |
| 作者 TXT 导入/保护/重建 | internal/authorimport/；跨手动/备份/同步入口复用事务，片段存取在 authorattach |
| 作者编辑用例 | internal/authoredit/；词表预填/来源区替换/作者建改删/资产作者替换六用例的单事务编排，Commit 成功后才刷新镜像（2026-10-24 Q1 事务下沉）；HTTP 只协议校验与哨兵映射 |
| 统计只读装载 | internal/statsread/；总览/趋势/榜单共用窗口；日/类型/来源聚合及精确全量数，stats 仍为纯函数 |
| 事件统计编排 | internal/eventstats/；事务内读真相/重建，stats 保持纯函数（ADR-0040） |
| 上传/本机同步 | mediawrite 负责编排直传、会话创建/完成与共用入库；uploadsess 管会话；syncchannel 编排同步，localsync 只读扫描计划，见 §5.1 |

此表是定位入口，完整包职责只维护在架构边界表。HTTP 路由使用标准库 ServeMux，废弃的 M0 初始化清单不再适用。

## 开发与验证

仓库根 `make server-run`、`make server-test`、`make lint`，具体目标以 Makefile 为准。先按 AGENTS 读取适用协作规则；广泛服务端影响跑 `go test ./...`，文件平台行为需对应 CI/目标环境。生成接口层不可手改；SDK 生成见根入口。

事务正常结束的 ErrTxDone 与真实回滚失败区别处理；响应已开始后的写失败只留日志，不重复响应或重试业务。复制/数据库打开失败中的关闭错误与主错误保留在同一错误链，不能用注释豁免吞错。

测试只用临时数据库、合成媒体、临时目录及隔离端口，禁止写入 8420 真库。扫描差集/恢复/续传失败重试边界见 [文件规则](../docs/domain/files.md) 与 [SECURITY](../docs/SECURITY.md)，便携 v1/v2 兼容见 [迁移](../docs/domain/migration.md)。

## 媒体库后台扫描

后台主管启动已有库监听，每 30 秒只查库列表，管理新增/移除、根路径变化和失败重试。监听注册后先对账一次，补停机和注册窗口内的变化。已有文件的写事件只查该路径并比较 size/mtime；新增、删除、改名和目录整体移入的事件合并后整库对账，保留移动合并身份及关联数据。事件接收与处理分离，同库只保留一个有界待处理批次；后台全量扫描跨库串行，避免同时装载大快照。API 手动扫描仍保留按库并发语义。

| YAML scanner 字段 | 环境变量 | 默认/兜底 |
|---|---|---|
| poll_interval | QIMENG_SCANNER_POLL_INTERVAL | 5m；非正值回落默认，启动生效 |
| watch_enabled | QIMENG_SCANNER_WATCH_ENABLED | true；false 时仅轮询，启动生效 |

文件监听可能在网络挂载上静默失效，所以注册成功不代表事件可靠，默认不自动延长轮询。确认本地监听可靠且能接受漏事件后更晚发现时，可显式设置如 `30m`，全量轮询次数由每小时 12 次降为 2 次；这是延迟与 I/O 的取舍，不是自动性能保证。应用上传即时入库、手动扫描不等此周期。配置值格式非法会报错；关闭监听不关闭轮询。

扫描仍逐目录/媒体文件读取元数据，并一次装载该库资产记录；路径索引仅保存快照下标，避免另复制整行资产。`BenchmarkScanSnapshotIndex` 以两万合成记录比较旧索引与新索引，只测索引构建的时间/分配，不包含 SQL、磁盘遍历或探测。云端 CI 运行该基准以及 Linux race、Windows 原生扫描回归；实库/NAS 性能须另验，不能从索引比例推算整个扫描加速比例。日志级别和耗时字段见 [监控](../docs/OBSERVABILITY.md#日志)。 首轮 f9ed404b 的[云端基准](https://github.com/Surtr42u/qimeng-media/actions/runs/40108840986/job/120916094406)三轮中位数为 3.267ms→0.754ms、额外分配 7,273,733B→873,728B（约减少 88%），分配次数 20,065→65；硬件/缓存与整库成本不包含在该比例内。

## 数据库持久性

业务库通过 `store.Open` 使用 WAL/FULL，每条池连接和重连均带同步设置。收藏/事件不能由媒体重扫恢复，默认优先持久性；代价、基准和硬件边界见 [ADR-0048](../docs/adr/0048-SQLite提交持久性优先.md)。

## 上传与存储预算

mediawrite 把完成凭据与资产行同事务提交（migration 0020），七天内重放结果；uploadsess 只管理未完成会话、预留预算和分片生命周期，不持有业务资产真相。源修改时间在库内预置文件发布前应用。协议/兼容边界见 [上传 API](../docs/api/files.md#上传任务重放源时间与容量adr-0049)。

| YAML upload 字段 | 环境变量 | 非正值/缺省回落 |
|---|---|---|
| max_sessions | QIMENG_UPLOAD_MAX_SESSIONS | 32 个活动分片会话 |
| max_temp_bytes | QIMENG_UPLOAD_MAX_TEMP_BYTES | 8 GiB；活动声明总量加孤儿分片实际大小 |
| min_free_bytes | QIMENG_UPLOAD_MIN_FREE_BYTES | 256 MiB；上传前和 readyz 的卷余量下限 |

环境变量必须为正整数；配置启动生效，不加入客户端设置页。单文件 max_bytes 与设置页限制取小者的原行为不变。预算不包含目标卷临时复制、直传临时件或其他进程占用，空间预检不等同于磁盘预留。readyz 只读检查，healthz 不受存储状态影响。

## 构建身份

日常 PC 出包走 [.github/workflows/build-pc.yml](../.github/workflows/build-pc.yml)：手动触发或推送 `codex/build-pc/**` 专用分支，在 Windows runner 生成 Windows amd64 服务端、Web 和发布清单，下载后由 AI 核对同一提交的完整门禁并整体部署。根目录三份启动 bat 共用 `_server-common.cmd`，只检查并运行已安装程序，不再启动时本地编译；产物缺失时由 AI 补交云端包。部署保留原数据、配置与旧程序，实际包身份见 HANDOVER。

“无头后端”入口保留普通黑色后端控制台及任务栏入口，不打开浏览器或 Tauri 桌面端，不新增图形状态窗口。未运行时在控制台直接运行服务端并显示输出；退出后保留停止/退出码提示。已有健康后端时复用该进程，控制台只读监测 RUNNING/NOT READY，不重复打开数据目录/抢占端口；关闭这个监测窗口不停止原有后台进程。新启动服务所在的控制台则遵循通常的 Ctrl+C/关闭语义。健康探测不提供同时首次启动的原子互斥；启动横幅仍追加到 `server/console.log`，本入口的新服务输出直接显示在控制台。

发布版本唯一源为 `internal/buildinfo/version.txt`；Android 同读该文件，安装序号独立维护在相邻 `android-version-code.txt`（必须递增）。API info.version 是协议版本，不随应用内部维护强制改动。服务端系统状态与 SSE hello 回显 `发布版本+提交短号[.dirty]`。`qimeng-server -version` 输出 JSON 后退出，不加载配置、不打开数据库、不监听端口；适合 AI 核对待部署/备份二进制。Go 原生 VCS 元数据缺失时显示 unknown，提交时间叫 sourceTime，不冒称构建时间；正式交付应从干净检出 `go build` 并核对身份及产物哈希，不能据源码版本推定设备已安装。

`internal/httpapi/gen/dependencies.go` 只声明生成代码使用的 runtime 根包依赖，不实现协议。生成文件不入库，Dependabot/干净检出中的 `go mod tidy` 也必须保留该依赖及其间接校验和；升级依赖后重新生成接口层，再执行 tidy 与服务端门禁。

列表查询策略、核心 SQL/HTTP 基准和隔离复跑命令见 [筛选专题](../docs/domain/filtering.md#查询执行策略与性能边界)。同构投影、窄查询白名单与计划锁防止加速引入漏筛选；协议/schema 不变（ADR-0043）。

统计热榜先按资产聚合再汇总关联；`store.LoadRecommendationInputs` 负责推荐输入的只读装载与规模选路，`recommend` 仍只做纯函数评分。默认宽流保留两次读取，显式媒体/COS 流的额外规模判断与窄候选收益见 [推荐专题](../docs/domain/recommendation.md#16-输入装载策略与性能边界)，热榜基准见 [统计专题](../docs/domain/statistics.md#热榜查询策略与性能边界)（ADR-0044）。

恢复/真实跨卷演练见 [RESTORE](../docs/RESTORE.md)。localsync.PlanScan 负责安全校验后的只读扫描计划，错误不返回可清理状态的半成品；同步编排在 syncchannel，TXT 事务在 authorimport。作者列表预聚合与原计数口径一致，合成基准不能替代真实大库验收（ADR-0042）。

`mediawrite` 是媒体写入的业务编排入口，生产在 main 注入数据库、配置、视频探测、扫描富化及与 HTTP 共享的 libraryrevision 服务；不依赖 HTTP 或 SDK 对象。上传/移动/改名/回收站/恢复/清扫在此编排；直传/会话完成/本机同步共用其 IngestPlacedUpload 和实时 UploadPolicy。HTTP 层仅解码、调用和协议错误/签名响应映射；syncchannel 持有同步运行态/调度/搬移/归档，复用 authorimport TXT 事务；HTTP 只读快照/触发，main 显式共享依赖。移动/改名也由该服务编排，库锁内实时复核后执行，前向/回滚都拒绝已有目标；SQL 失败不发成功通知，回滚受阻保留原件供核对。其他存量 handler 仍按实际任务迁移，不据此宣称所有写入已下沉。

云端生成：向 `codex/codegen/**` 专用分支推送协议/SQL 后，codegen workflow 固定版本执行 sqlc 和 make sdk，下载 generated-sources artifact 取回生成物与 sdk.lock，禁止手改生成文件。随后把锁和受版本控制的 sqlc 输出与实现同批交付，现有完整 CI 仍重算校验；未绕过生成锁门禁。
