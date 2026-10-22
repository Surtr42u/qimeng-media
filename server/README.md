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
| 统计只读装载 | internal/statsread/；总览/趋势/榜单共用窗口；日/类型/来源聚合及精确全量数，stats 仍为纯函数 |
| 事件统计编排 | internal/eventstats/；事务内读真相/重建，stats 保持纯函数（ADR-0040） |
| 上传/本机同步 | mediawrite 负责编排直传、会话创建/完成与共用入库；uploadsess 管会话；syncchannel 编排同步，localsync 只读扫描计划，见 §5.1 |

此表是定位入口，完整包职责只维护在架构边界表。HTTP 路由使用标准库 ServeMux，废弃的 M0 初始化清单不再适用。

## 开发与验证

仓库根 `make server-run`、`make server-test`、`make lint`，具体目标以 Makefile 为准。先按 AGENTS 读取适用协作规则；广泛服务端影响跑 `go test ./...`，文件平台行为需对应 CI/目标环境。生成接口层不可手改；SDK 生成见根入口。

测试只用临时数据库、合成媒体、临时目录及隔离端口，禁止写入 8420 真库。扫描差集/恢复/续传失败重试边界见 [文件规则](../docs/domain/files.md) 与 [SECURITY](../docs/SECURITY.md)，便携 v1/v2 兼容见 [迁移](../docs/domain/migration.md)。

## 数据库持久性

业务库通过 `store.Open` 使用 WAL/FULL，每条池连接和重连均带同步设置。收藏/事件不能由媒体重扫恢复，默认优先持久性；代价、基准和硬件边界见 [ADR-0048](../docs/adr/0048-SQLite提交持久性优先.md)。

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
