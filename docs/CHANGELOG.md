

## docs(ai): Go 业务 API 缺口与 AI 客户端权限模型方案（2026-11-07 第七百零四笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 按用户 2026-11-07 追加长期架构要求落方案：[Go API 缺口与 AI 客户端权限模型](AI_PLATFORM_API_GAPS.md) 复核作者/媒体/文件/备份/任务既有接口（结论：基础操作完整，缺口集中在 AI 客户端身份/权限/审计），列 G1–G8 缺口（AI 客户端凭据+scope 撤销审计、业务审计归属、服务端高风险确认、外部执行器领取、批量能力、设备配对、作品/剧集模型、限流预算）与分阶段计划；新增 [ADR-0057](adr/0057-AI客户端凭据与细粒度权限模型.md) 与索引。
- 权限模型：AI 客户端凭据与用户会话分离、可轮换/撤销、scope 集合（read/research/author.write/asset.author.write/media.organize/backup.export/maintenance.read|write 与高风险 author.destructive/backup.restore/admin.*）；低风险写可在授权范围内自动执行，高风险单独 scope+动作级确认；权限由 Go 中间件与服务层强制，审计按客户端/动作/目标/结果/请求 ID 归属；拒绝任意 SQL/文件/shell；长任务复用维护账本；NAS 底座与 AI 平台独立升级。
- 本 PR 只收尾现有代码与测试，不改 Go/OpenAPI/migration；ADR-0056 补充工具目录/惰性加载/权限分离；taskbook §4.4/§5.4、CAPABILITY_MAP、HANDOVER §9.3、tools README 同步。验证：check_docs 0 错误、tools 单测 205 项本机通过（扩展性 18 项）、check_privacy passed；Go 与三端未改；云端 CI 见 HANDOVER §9.2。未安装、Go 权限实现未开始（P1+）。

## feat(tools): 工具目录按需发现、惰性模块加载与权限分离（2026-11-07 第七百零三笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 按用户 2026-11-07 扩展性要求做最小增量（仅 tools/ai_maintenance/ 与文档，不改 Go /api/v1、OpenAPI、schema、三端）：新增 platform.catalog 精简领域目录（author/media/research/backup/device/maintenance/system，28 个工具；无 Schema，默认隐藏已废弃工具）、platform.tool.describe 按需返回单个工具 Schema/示例/规则摘录/Go API 能力依赖、platform.rules 按节/领域读取有界规则（不加载完整 rules.json）；CLI 增 catalog 与 tools list --compact/--domain，tools show 输出按需描述；MCP tools/list 仍返回完整清单并新增 _meta.domain 与 catalog 指引。
- 注册表改为惰性 module:handler 引用：目录/状态计算不导入 tools_*.py / media_naming.py，首次执行/描述才导入；实现模块缺失→unavailable/TOOL_NOT_IMPLEMENTED；新增 deprecated/replacedBy（目录默认隐藏、执行 TOOL_DEPRECATED）；rules.json 增 toolCatalog 规则节（领域/发现策略/惰性加载/权限模型/管理员 Bearer 绕过风险评估）并升 rulesVersion/rulepackVersion 2026-11-07.5。
- 权限与发现分离：execution 按 QIMENG_AI_GRANTED_PERMISSIONS 或 toolConfig.grantedPermissions 在 Schema/计划/网络前强制拒绝（PERMISSION_DENIED）；未配置默认授予全部声明权限仅为兼容旧 CLI。管理员 Bearer 绕过风险已写入 README/ADR-0056/rules：工具层隐藏 token 不能阻止模型直接调用 Go API，正式开放 AI 写入前需受限凭据或执行端隔离。
- 示例与规则摘录集中在 registry（TOOL_EXAMPLES/TOOL_RULES_REFS），describe 有界返回；目录 JSON 约为完整 tools list 的 1/11（28 工具实测），120 个合成工具仍有界；新增 tools/test_ai_catalog.py 18 项与既有回归，tools 全套 205 项本机通过、check_docs 0 错误、check_privacy passed；云端 CI 见 HANDOVER §9.2。未安装、未接真实 AI 客户端。

## feat(web/app): 维护任务中心与两代布局、双端搜索结果文件名显示（2026-11-07 第七百零二笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- Web 维护任务中心（只读）：新增 `lib/maintenance-jobs.ts`(+test)、`hooks/use-maintenance-jobs.ts`、`components/maintenance/`（任务中心/列表/详情），维护页追加任务列表（状态筛选/cursor 分页/刷新）、详情（类型/状态/进度/步骤/工件元数据/错误码）与取消（二次确认后刷新）；不展示 payload/budget 与工件正文，未写「实时」承诺。
- Web 两代维护目录：`lib/maintenance-folder.ts`(+test) 与目录卡/备份卡/hook 改为 `当前/上传归档 → 上次` 两代整代轮换，每代独立 .sha256，createWritable 原子替换，摘要不符/缺摘要/不可读拒覆盖并保留现场；旧 flat3 只读兼容并可列提示、绝不删除；恢复入口改为当前/上次，旧「上上次」改文件导入说明。
- Web 搜索：搜索结果标题取完整文件名（COS 结果亦可见作品编号/组图页码）单行省略、悬停/读屏完整名，普通浏览网格保持 cosWork 优先两行；换搜索词显示「正在搜索…」加载态而不混旧词结果，同词筛选/分区保持 keepPreviousData 不闪；详情新增完整文件名行与复制（失败提示手动复制）；消费共享 Go 搜索语义，不重写匹配。
- Android 两代维护目录：`MaintenanceFolderRepository` 改为当前/上次两代 + 上传归档，整代轮换、独立摘要、原子写、拒覆盖保留现场、旧 flat3 只读兼容；新增 `MaintenanceGenerations.kt`（纯 JVM 轮换/原子写/摘要）与 `MaintenanceLayoutMigration.kt`（旧三代只读核对+复制不删除）；旧镜像/备份偏好改指新代且保留旧文件；18 项 JVM 单测由本机 kotlinc+JUnit 运行通过（AGP/Gradle 与真机未跑，云端 CI 为编译门禁）。
- Android 搜索结果：缩略图下一行简短文件名（单行省略、保留编号/页码，`QimengMediaGrid` 可选 caption，默认不影响其他网格），详情信息弹窗完整文件名与复制（Clipboard+提示），换词提交同步清空旧结果/游标（配合既有查询代际丢弃在途旧响应），保留双指缩放 2～5 列/分页/滚动/返回，内嵌与连接模式同一 API；补 JVM 单测（短名保留编号/页码与边界、换词清空旧结果）。真机与内嵌现场未验收。
- 验证边界：Web vitest 全量 41 文件/381 用例、tsc --noEmit 与本次改动 oxlint 通过（未跑 vite build/dev、浏览器走查另按 HANDOVER）；Android 仅静态类型检查与 JVM 单测，未跑 AGP/Gradle/真机；未安装到现装 PC/手机。
- 交付后 CI 修复（同批提交 6a41bc55/d1ef39f9）：维护任务中心对合成/异常响应形状做列表/详情/取消归一（避免非协议 JSON 在渲染期抛错拖垮维护页），任务中心改用独立布局类名 `.maintenance-job-center`（避免 e2e 严格模式把第二个 `.monitor-grid` 判为多元素）；Playwright 维护/管理导航场景恢复通过。
- 验证：最终 HEAD 云端 ci [42370683863](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683863) 七 job 全绿（服务端 go vet/golangci-lint/go test -race/go build、Web tsc/build/vitest/Playwright、Android assembleDebug+单测+Lint、SDK 生成链、桌面壳、Windows 原生文件操作、redocly）；文档 [42370683869](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683869) 与依赖安全 [42370683860](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683860) 通过。本轮交付仍**未安装**到现装 PC/手机，真实库、真机与外部 AI 客户端未验收。

## feat(server): 两代备份镜像、组图命名解析与搜索编号语义（2026-11-07 第七百零一笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 两代备份布局（服务端）：`server/internal/authorattach/mirror.go` 在 `path` 祖先目录恰有一段 `当前`/`current` 时，按同根兄弟 `上次`/`previous` 推导上一代并校验仍在维护根内；内容变化先把当前代（正文+独立 .sha256）提交为上次代再写新当前，相同数据只刷新当前；上次目标已存在且正文不同时必须有可复算匹配的 .sha256 才覆盖，缺失/不符/不可读拒绝并告警；相对路径、无代目录段、多段歧义、盘符/共享根等不推导时回退旧 flat3 三代语义，旧配置行为不变。新增 8 组测试覆盖推导、轮换、拒覆盖、外部编辑、失败重试与 master 模式全链路。
- 迁移核对工具：新增 `tools/migrate_backup_layout.py`，默认 `--check` 只读核对（可读性/.sha256 复算/新旧对应建议/JSON 摘要），`--apply` 才复制生成当前/上次（当前=旧最新，上次=旧「（上次）」），上上次仅列可清理清单；绝不删除/移动，目标占用拒覆盖，临时文件+os.replace 原子写；12 项合成目录单测通过。
- 组图命名：新增 `server/internal/filing/workname.go` 纯函数解析作品基名/主作品编号（位宽/分隔/标记风格）/可选组内页码，仅括号序号（含上传自动改名产物）回退旧 seriesSuffix；`SuggestSeriesNames` 对组图页建议下一作品编号而非下一页码，组图与单图混合/乱序/缺页/补图/全角括号/零填充均覆盖测试；`docs/domain/files.md` 与 `docs/api/files.md` 记录命名语法、三步优先级与「自动改名不算命名成功」。
- 搜索编号语义：新增 `server/internal/store/searchtoken`（纯函数 Match + modernc 确定性标量函数 `qimeng_search_token`，init 注册失败直接 panic 防静默退化）；browse/facets 的 10 处 q 谓词统一改为该函数：数字 token 按独立数字段匹配（前后非 ASCII 数字，裸数字不命中括号页码），`(2)` 等页码 token 定位组图页，非数字 token 保持旧 ASCII 子串多字段语义；列表/计数/分面/dateCounts 同口径。修复前隔离测试复现「朱诺 1 命中 13/13、朱诺 11 命中 110/2011、朱诺 2 命中页码」，修复后 store/search/httpapi 定向测试通过；sqlc 重新生成比对无额外漂移。
- 验证边界：上述 Go 侧本机 `go build/vet/gofmt` 与 authorttach/store/search/httpapi 定向测试通过；Python 侧 12 项迁移工具测试通过；未改 OpenAPI/sdk.lock/SDK/migration；未迁移真实维护目录、未对真实库执行搜索专项或命名整理。

## feat(server): 维护任务 Worker、幂等载荷指纹冲突与组合根装配（2026-11-07 第七百笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `server/internal/maintenance` 新增 `worker.go`：内部常驻执行器（不开放任何 HTTP 领取/续租/完成端点），仅领取本进程已注册 handler 的 kind（sqlc 查询按 kind 过滤），有界并发（默认 1）、租约 TTL+心跳、逐步骤 RecordStep（running/completed/failed/skipped + attempt/时间/detail）、状态机安全推进（queued→inventory→applying→verifying→completed/partial/failed）、暂停/取消/租约丢失后停止后续步骤并保留已提交步骤、进程退出不写终态由租约过期续跑、panic 兜底只影响单任务、已完成步骤不重复执行副作用。
- 内置 `backup_export` handler（`handler_backup.go`）：复用既有 `backup.Manager` 生成库快照并 AddArtifact 记录工件元数据（正文不入库）；无 handler 的 `asset_research`/`author_cleanup`/`custom` 保持 queued 供未来外部 AI 执行器。
- 幂等冲突保护：`Service.Create` 命中同 idempotencyKey 时比较新载荷 inputFingerprint，相同保持重放（200/created=false），不同返回 ErrStateConflict（409），httpapi 映射 STATE_CONFLICT；补并发 replay 竞争路径。
- 装配与配置：`cmd/qimeng` 以 `safego.Go` 启动 worker 并在关库前收齐；config 新增具名默认值（轮询 5s/租约 2min/并发 1，`QIMENG_MAINTENANCE_WORKER_*` 可覆盖，默认启用但只处理已注册 kind）；SQL 改动经锁定 sqlc v1.31.1 重新生成。
- 测试与文档：maintenance/config 包覆盖领取/心跳、租约过期接管、重启续跑不重复、暂停取消、无 handler kind 不被领取、同 key 同/不同载荷、backup_export 工件与失败终态；docs/api/maintenance.md 更新 worker/冲突/未开放外部领取端点的边界，ADR-0054 追加实现补充。
- 验证边界：本机 `go build ./...`、`go vet ./...`、定向 `go test ./internal/maintenance/ ./internal/config/` 通过，`gofmt -l` 干净；完整回归与 race 检测以云端 CI 为准（见 HANDOVER）。未安装到现装运行包，未对真实库创建任务。

## feat(tools): 独立 AI 工具核心、作者链受控写入与 stdio MCP 适配器（2026-11-07 第六百九十九笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `tools/ai_maintenance/` 升级为可独立维护的统一工具包（仅 Python 3.13 标准库）：新增 versions/errors/openapi/schema/registry/runtime/execution/plans/client/validation 模块与 tools_query/tools_author/tools_asset/tools_media/profile_import 实现；分离 platformVersion/toolContractVersion/rulepackVersion 与 openapiSha256（仅来源追踪），OpenAPI 改为分块解析（path→method→requestBody/schema/parameters，支持 $ref），按工具独立核对端点+方法+必需字段并输出 supported/unavailable/incompatible；25 个显式注册工具（19 supported/6 planned 如实），可列举、可禁用（QIMENG_AI_DISABLED_TOOLS/QIMENG_AI_READONLY），新增工具不改入口分支。
- 统一 CLI 保留既有只读子命令与 `--json`/退出码 0/1/2 合同，新增 `version`、`tools list/show`、`tool call <id> --input-json/--input-file`；token 仍只来自环境变量，输出/日志/报告不回显；请求客户端统一（QIMENG_BASE_URL→QIMENG_API_BASE 兼容映射并提示弃用）。
- 受控写入：prepare 计划（目标实例/库/对象、changes、impact、evidence、前置 revision/摘要、planFingerprint、idempotencyKey、confirmation、recoverableSteps、expectedReadback）→apply 校验确认+指纹+toolContract/rulepack 版本+live 前置摘要→正式 API→回读→结构化报告；作者档案 prepare/update（人工已填字段默认保护，409 不覆盖）、cleanup prepare/apply（删除/改名/合并预览与逐项回读，零关联不单条件删除）、author.merge（只用原子端点）、asset.authors prepare/apply；旧 `tools/import_creator_profiles.py` 改为转发统一实现，保留参数/退出码/输出类别与人工资料保护语义。
- 新增 `media_naming.py` 组图命名规划引擎（作品编号 vs 组内页码、先分组→编号→页码、补图/缺页/并发/回读；prepare_plan/validate_plan/plan_steps/readback_report/SerializedLocks）与 `tools_media.py` 适配：apply 前按 instance+library 串行化、锁内重取现状+双检、逐步 POST move、回读发现自动改名/缺失/多余即失败；`media.organize.prepare/apply` 由 planned 动态变为 supported。
- 新增 stdio MCP 适配器 `mcp_server.py`（JSON-RPC 2.0 最小子集：initialize/notifications/ping/tools.list/tools.call/取消与超时；与 CLI 共用同一注册表与 Schema；无 shell/任意 Python 执行；不暴露 token）；协议层以两个独立合成客户端验证，**真实 AI 客户端（Codex/Claude 等）接入未实测**，README 如实说明。
- 规则源同批：rules.json schemaVersion=2、source 指向 V2 任务书与 ADR-0056、新增 mediaNaming 规则节、补齐 maintenance/jobs* 与 device/apply-tasks* 能力声明、区分 status/apiStatus/toolStatus、过时三代陈述改两代；rulesVersion/rulepackVersion 增为 2026-11-07.3。
- 验证边界：`python -m unittest discover -s tools -p 'test_*.py'` 181 项通过（含旧 22 项 CLI 合同与旧脚本 15 项收敛回归、MCP 双合成客户端、命名 39 项）；CLI/MCP 冒烟通过；check_docs 0 错误、check_privacy passed。未改 server/api/web/android，未接真实服务端/真实 AI 客户端/8420 真库。

## docs(ai): 独立 AI 接入平台 V2 任务书、ADR-0056 与 R0 定向审计（2026-11-07 第六百九十八笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 纳入 [外部 AI 接入平台统一任务书 V2](AI_PLATFORM_TASKBOOK.md) 作为现行执行清单（R0–R8、§6.6 组图命名与作品编号、§6.7 搜索语义与双端文件名显示、§11 安全权限与 §14 验收矩阵）；原[外部 AI 维护任务书](AI_MAINTENANCE_TASKBOOK.md)与 [P0 底稿](AI_MAINTENANCE_P0.md)标记为历史需求与设计来源，保留全文供追溯，不再作为并行执行清单。
- 新增 [ADR-0056 独立 AI 接入平台架构](adr/0056-独立AI接入平台架构.md) 与 ADR 索引：两层接口独立、Python 标准库工具核心与显式注册表、版本分离与能力核对、计划/确认/执行/回读、唯一维护账本与内部 Worker、两代备份路径约定、研究默认关闭的边界与被否方案。
- 新增 [R0 定向审计](AI_PLATFORM_AUDIT.md)：脚本/工具逐项清点（AI 工具链、作者写入、研究链、管理员工具、服务端能力）、三分类收敛、重复实现 D1–D8、规则包与 OpenAPI 逐条漂移（A1–A9，无 P0，24 个有端点声明路径+方法匹配）、复用/退役映射与最小变更计划。
- 文档治理同批：AGENTS/AI_README_FIRST/llms 任务入口、ARCHITECTURE 外部 AI 平台章节、CAPABILITY_MAP 能力域（AI 工具层/维护队列/两代备份/组图命名/检索筛选）、HANDOVER §5 指针与 §9 本轮交付边界；旧三代陈述在 docs/domain/migration.md、docs/SECURITY.md、tools/MEDIA_RESEARCH_IMPORT.md 改为「当前/上次」两代并保留旧 flat3 兼容读取；tools/README 增迁移工具条目。
- 验证边界：纯文档与入口调整，未改代码/协议/迁移/SDK；`python -X utf8 tools/check_docs.py` 0 错误、`check_privacy.py --mode worktree` passed。本轮源码交付的安装、真实数据与设备验收状态见 HANDOVER §9。

## feat(server): 设备结果包服务端 API（PC→手机无前台维护第一刀）（2026-11-07 第六百九十七笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 migration `0024_device_apply_tasks`：只增 `device_apply_tasks`/`device_apply_segments` 两表与「设备+状态+新建倒序」「过期」索引，不改历史表/索引；任务状态机 pending→claimed→applied/partial/failed、pending/claimed→cancelled/expired，终态不可变更，过期由读写路径惰性标记。
- 新增 `server/internal/deviceapply`：manifest 校验（targetDeviceId/protocolVersion=1/rulesFingerprint/ttl/段 kind/bytes/SHA-256/expectedReadback）、段落盘时流式复算摘要与字节数后再单事务写库、失败清理任务目录；Create/List/Get/Claim/OpenSegment/Result/Cancel；领取对已 claimed 未过期任务幂等、相同归一化结果回传幂等（不同结果 409）、取消对 cancelled 幂等；段相对路径逐字校验+根目录包含，下载前复核文件类型与大小；只依赖 store/db，不依赖 httpapi（depguard）。
- 协议新增 7 个 `/api/v1/device/apply-tasks` 端点（登记/列表/详情/领取/段下载/回传/取消）：登记为 multipart manifest + masterTxt≤1MiB + v2Backup≤64MiB，单个段或整体请求体超限 413；段下载 octet-stream + Content-Length + X-Content-SHA256；`make sdk` 三端生成、go build ./... 通过，`api/sdk.lock` 同批重算（355 项）。
- httpapi 只做 multipart/query 校验、错误映射与响应装配；测试覆盖创建/段落盘 SHA-256/超限/指纹不符、列表过滤与游标、领取幂等与过期、段下载头/终态/路径安全、回传终态与重复回传、取消、404/409/413，全部使用临时 SQLite、临时包目录与合成字节（含 1KB 段）。
- 文档同批：`docs/api/device.md`、ADR-0055 与索引、能力地图、HANDOVER §8、AI_MAINTENANCE_P0 §4.5/§5/§6、llms 导航。验证边界：本地已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet`、文档/隐私/迁移检查；业务 `go test` 按仓库规定走云端 CI。**未安装**到现装 PC/手机；Android Worker、配对/设备身份、真机后台同步与包清理仍未实现。第六百六十七笔完整原样移入 `docs/history/CHANGELOG-20261028.md`，近期日志保持 30 笔。

## feat(maintenance): 持久维护任务队列服务端垂直切片（2026-11-07 第六百九十六笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 migration `0023_maintenance_jobs`：只增 `maintenance_jobs`/`maintenance_steps`/`maintenance_artifacts` 三表与状态、幂等、租约、步骤/工件索引；状态机覆盖 queued→inventory→research→planned→applying→verifying→completed、waiting_evidence/waiting_authorization/waiting_device 与 paused/partial/failed/cancelled，终态不可变更。
- 新增 `server/internal/maintenance`：Create/List/Get/Pause/Resume/Cancel、同事务状态校验、幂等键冲突返回既有任务、输入指纹、租约字段与 Claim/Heartbeat/Complete、RecordStep/AddArtifact 内部原语；只依赖 store/db，不依赖 httpapi（depguard）。
- 协议新增 `POST/GET /api/v1/maintenance/jobs`、`GET /api/v1/maintenance/jobs/{id}` 与 `POST …/{id}/pause|resume|cancel` 六端点；创建幂等 201/200，非法迁移/终态 409 STATE_CONFLICT，不存在 404，列表 status/limit/cursor 与详情步骤/工件/error。`make sdk` 三端生成通过，`api/sdk.lock` 同批更新为 331 项。
- httpapi 只做协议校验/错误映射/响应装配；测试覆盖创建/幂等重复/并发同键、列表过滤与游标、状态机合法/非法迁移、暂停/恢复/取消、终态不可变与 404/409 映射，全部使用临时 SQLite 与合成数据。文档同批：`docs/api/maintenance.md`、ADR-0054 与索引、能力地图、HANDOVER、AI_MAINTENANCE_P0 §4.2/§5、llms 导航。
- 验证边界：本地已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet` 与迁移只增检查；业务 `go test` 按仓库规定走云端 CI。**未安装**到现装 PC/手机；worker 执行器、Web 任务中心、PC→手机桥接、作者清理工具与两代布局仍未实现。第六百六十六笔完整原样移入 `docs/history/CHANGELOG-20261028.md`，近期日志保持 30 笔。

## feat(app): 安装 796d813f 双端并完成研究流水线真实实例试验（2026-11-07 第六百九十五笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 云端同源门禁：ci/安全/文档与 PC/APK 出包均在 796d813f 通过（CI 首轮 Windows 文件操作作业因环境清理失败，同作业重跑通过）。PC 安装 119 项清单本地 SHA-256 核对通过，version JSON revision=796d813f、schema22、health/ready 200；手机同签名保留数据覆盖安装，版本 0.2.1/code35（lastUpdateTime 2026-11-07 01:59）。
- 研究流水线真实实例试验：临时开启 `research.enabled` 并运行本机 sidecar，`/research/context` 返回 enabled=true、sidecar available、7 provider；`/research/lens` 真实返回 200/约 13s（frame SHA 与真实库图片一致、links 空、top 域噪声，符合 best-effort 预期）。`/providers/fxtwitter/query` 因本机 DNS fake-IP（198.18.1.x 保留段）被 SSRF 防护拦截返回 ADDRESS_BLOCKED；未放宽安全策略，需本机切真实 DNS 或另立显式逃生配置后再验。
- 顺带按用户 2026-11-07 授权补挂作者：POST /assets/{assetId}/authors 将「守望先锋 天使 18.jpg」关联到 vgerotica 并回读一致；写前已做服务端热备。
- 边界：未做手机 v2 同步；研究默认关闭、日常启动不带试验环境；真实 429/503/504 未触发；所有证据与安装副本只留忽略目录，未写测试脏数据。
# CHANGELOG - 变更历史

本文件只保留最近 30 笔；更早条目按 [历史索引](history/INDEX.md) 查询。历史条目正文及署名不改写，当前状态看 HANDOVER/能力地图与现行规范。
## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型完整名称与变体（如 GPT-6.1 Sol），以会话元数据或用户明确确认为据；Codex 仅作工具/客户端说明，不能代替模型名，具体版本也不能简写成模型系列。无法确认时注明未知，禁止猜测。
- 署名补全（2026-10-26，用户明确确认）：2026-10-20～26第五百四十八至第五百七十四笔、第五百八十九至第六百二十五笔原标未知，共64笔，补为GPT-6.1 Sol；原主代理/复核/GLM-5.3原实现分工及工具标记保留。该依据是用户本轮确认，不伪称已从运行时独立读取；本会话后续交付在此确认范围内沿用。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。
- 署名自查（2026-09-05 补）：每条变更由执行会话先确认自身实际运行模型的真实名称再署名（GLM-5.3 与 GLM-5.3-Flash 是两个不同模型名），禁止沿用上一会话或上一条目的署名行；历史条目真实署名不动。
- 署名补全（2026-10-23，用户明确确认）：2026-10-22 第五百七十五至第五百八十四笔原标未知，补为 GPT-6.1 Sol；各笔执行分工和历史验证边界不变。
- 署名更正（2026-10-20，用户明确确认）：2026-10-19～20 的第五百三十八至第五百四十五笔原误署 GPT-6 或仅署 Codex，统一更正为 GPT-6.1 Sol；保留各条主代理/执行子代理及复核分工。

## feat(tools): 研究核验 CLI 命令（context/provider/lens）（2026-11-07 第六百九十四笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `tools/ai_maintenance/cli.py` 新增只读 `research` 命令组：`research context`（GET /api/v1/research/context，打印 enabled、sidecar configured/available/pending、provider 矩阵与限额）、`research provider --provider <name> --operation <author|post|work|artist|tags|url|workshop|search> --target <值> [--limit N]`（POST /api/v1/research/providers/{provider}/query；CLI 不硬编码矩阵，矩阵外/非法参数由服务端 400 透传）、`research lens --file <本地图片>`（POST /api/v1/research/lens 的 multipart 字段 file；扩展名与 20MiB 本地预检、150 秒超时、摘要只打印 status/frameSha256/topTitles/links 数量，不打印图片内容）。所有命令支持全局 `--json`；缺配置退出码 2 且不回显 token，图片字节不进入任何输出。
- 规则包与文档同步：`rules.json` 三条 research capabilities 的 `cli` 指向实际命令、研究指南补 CLI 用法并删除「本切片未提供研究 CLI」，规则版本 `2026-11-06.3`；`rulepack.py` 去掉与指南重复的研究节并把 research 命令加入只读命令清单；`tools/ai_maintenance/README.md` 增研究示例与 `RESEARCH_DISABLED`/`RESEARCH_SIDECAR_*`/`RATE_LIMITED`/`INVALID_PARAM` 语义；`docs/api/research.md` 增「命令行工具」小节，注明研究默认关闭、sidecar 需另行部署。
- 验证边界：`tools/test_ai_maintenance.py` 由 23 项增至 31 项并全部通过，仅用临时目录与 `127.0.0.1` 随机端口 stub（研究成功摘要与 --json、POST 路径/Bearer/JSON 体、multipart 字段与文件名、服务端 400/503 透传、缺配置、token 脱敏、本地文件预检）；未访问真实平台、未安装 sidecar、未触碰 8420 真库，也未改 OpenAPI/Go。`python -X utf8 tools/check_docs.py` 0 错误、`python -X utf8 tools/check_privacy.py --mode worktree` passed。第六百六十四笔完整原样移入 docs/history/CHANGELOG-20261028.md，近期日志保持 30 笔。

## feat(research): 媒体研究流水线 API 集成（provider 网关 + Lens sidecar）（2026-11-06 第六百九十三笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 `GET /api/v1/research/context`、`POST /api/v1/research/providers/{provider}/query`（fxtwitter/pixiv/yandere/e621/wayback/steam/bing 固定矩阵，矩阵外 400 INVALID_PARAM）、`POST /api/v1/research/lens`（multipart file ≤20MiB，loopback sidecar）；错误映射含 400/401/413/429/502/503/504 与 `RESEARCH_DISABLED`、`RESEARCH_SIDECAR_*` 码，provider 上游失败以 200+status 表达。协议先改 OpenAPI，再 `make sdk` 生成三端并同批重算 `api/sdk.lock`；Go 枚举用 `x-enum-varnames` 规避 oapi-codegen v2.8.0 的常量去重冲突（否则既有 `Ok` 常量被改名、编译失败）。
- 新包 `server/internal/research`：provider 接口与注册表、安全 HTTP 出口（仅 https、域名白名单、禁重定向与环境代理、DNS 解析后校验公网地址、20s 超时/2MiB 响应上限）、进程内预算（provider 每分钟、Lens 每小时/每日，时钟可注入、UTC 对齐）、sidecar 客户端（loopback 校验、Bearer、/healthz、/lens multipart、超时映射）与证据落盘（剥会话参数、只记哈希/公开字段、不入库）。配置新增 `research` 段与 `QIMENG_RESEARCH_*`，默认关闭，启动期校验端点/凭据/预算；嵌入式/Android 默认关闭且不新增监听面。
- 验证边界：research 与 httpapi 测试全部使用 httptest 假上游、假 sidecar 与合成数据，不访问真实平台、不触碰 8420 真库；**未安装 Lens sidecar、未在真实实例验收真实平台查询**，现装 PC/手机仍为 264b9d83。文档同批：`docs/api/research.md`、ADR-0053 与索引、能力地图、HANDOVER 研究交接、llms 导航，规则包增研究能力/工作流/手册节。第六百六十三笔完整原样移入 docs/history/CHANGELOG-20261028.md，近期日志保持 30 笔。

## feat(tools): 外部 AI 维护规则包与只读 CLI（2026-11-06 第六百九十二笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 `tools/ai_maintenance/`：`rules.json` 为唯一手写领域规则源，`rulepack.py` 从规则源与 `api/openapi.yaml` 原始字节生成稳定规则包（`openapiSha256`、路径、字段上限、能力、作者动作、备份/错误/工作流/权限），端点缺失时自动把 `supported/available` 降级为 planned；当前 OpenAPI 的 `/authors/merge` 只标 `apiStatus=available`，写入仍 planned。`cli.py` 提供只读 `context/health/libraries/assets/authors/author-profile/author-sources`，无任何写入命令，`author.cleanup`、设备结果包、MCP 等仍为 planned；`tools/README.md` 加一行入口。
- 凭据只从 `QIMENG_API_BASE`/`QIMENG_API_TOKEN` 读取，错误不回显 token；网络命令拒绝重定向、禁用环境代理、20 秒超时、2 MiB 响应上限。`tools/test_ai_maintenance.py` 覆盖规则包必需键/指纹稳定性/未知端点降级与 CLI 配置、认证头、分页、HTTP/超时错误分类、重定向拒绝；仅临时目录与 127.0.0.1 stub，不访问真实 8420/外网。

## feat(api): 原子作者合并端点与 SDK 指纹锁（2026-11-06 第六百九十一笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 `POST /api/v1/authors/merge`（`AuthorMergeRequest` sourceAuthorId/targetAuthorId → `AuthorMergeResult`）：同一事务把源常规作者并入目标，TXT 块别名/作品行/来源词保序去重、`asset_authors` 迁移并对双端重复保留 target 既有行及其 origin/created_at、关注并集、档案 target 优先（仅 target 空档案时迁入源档案；target 与源均非空时 `sourceProfileDiscarded=true`）、上传条目改指目标块；删除源作者行，媒体文件与资产本体保留。不可撤销、无撤销端点；同 ID/空 ID/COS 400 INVALID_PARAM，任一不存在 404 NOT_FOUND，失败整体回滚。
- 按协议先行执行 `make sdk`：三端 clean 后重新生成 Go/TS/Kotlin、`go build ./...` 通过，`api/sdk.lock` 同批重算（283 项）。文档同步 `docs/api/authors.md`「作者合并」与 `docs/domain/authors.md`「作者合并」，并在领域说明中衔接改名 409 旧语义与 `author.cleanup` planned 关系。
- 验证边界：`server/internal/authoredit/merge_test.go`、`server/internal/authoring/merge_test.go`、`server/internal/httpapi/authors_merge_test.go` 覆盖全量合并、目标无块、档案保留、哨兵错误、回滚与零副作用；全部使用合成数据/测试库，未做真实实例端到端，未向 8420 真库写入。

## docs(docs): 外部AI维护P0现状与最小方案（2026-11-06 第六百九十笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 完成 P0 底稿 docs/AI_MAINTENANCE_P0.md：现状核对、可复用端点、需求/缺口表、规则包与工具适配层、持久任务队列、作者合并协议、两代备份分代目录、手机无前台桥接、本地权限与凭据、阶段切片与测试矩阵；均为设计默认方案，待用户确认。
- 任务书 P0 行、HANDOVER 与能力地图同步指到 P0 底稿。本轮纯文档，不改代码/协议/migration/SDK；现装仍 264b9d83/0.2.1/schema22、三代/明文/无桥接。
- 未运行应用构建、未操作真实库/8420/设备；文档检查与隐私扫描由主代理合并后复核。第六百六十笔完整原样移入 docs/history/CHANGELOG-20261028.md，近期日志保持 30 笔。

## docs(docs): 修订外部AI维护任务书与备份布局（2026-11-06 第六百八十九笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 用户 2026-11-06 第二批需求：取消 AI 打标签与标签加密/隐私（原任务书第6章与 P5 删除）；扩大本地维护权限；新增 PC→手机无前台跨端维护通道、作者清理/去重、两代备份分代目录；规则包承接写入规范并随发行物自包含交付、运行时不依赖仓库文档，减少反复读文档。
- ADR-0052 记录修订并部分取代 ADR-0051 的标签加密部分；任务书重写，能力地图、交接、安全已确认需求节、规划、架构目标结构、llms 与工具入口同步。具体工具、队列、桥接、备份布局及旧标签加密遗留均待实施；当前装机仍 264b9d83/0.2.1/schema22、三代/明文/无桥接。
- 本轮纯文档，不运行应用构建、不操作真实库/8420/设备；文档检查通过（130 个文件，0 项错误）。第六百五十九笔完整原样移入 docs/history/CHANGELOG-20261028.md，近期日志保持 30 笔。

## docs(docs): 外部AI维护、后台执行与标签隐私任务书（2026-11-06 第六百八十八笔）

执行 AI：完整型号未知（主代理；当前会话未提供可核实的完整型号与变体元数据）。拟交接执行者DeepSeek V4.1由用户指定，后续代码执行署名单独记录。

- 保存混合媒体下载后的研究、规范命名、作者/头像/链接/双语简介/标签/关联维护、双端后台及未来NAS云端AI场景，提供可直接派发的分阶段任务书、规则发现与写入教程要求、故障/隐私/恢复验收和重构不变量。
- ADR-0051记录已确认方向，部分修订旧AI范围，能力地图、储备、架构、安全、API/工具导航及交接同步。标签加密、工具及持久队列列为待实现。
- 本轮为文档交付，按文档门禁核对，不重标运行包或宣称新功能已安装。第六百五十八笔完整归档，近期记录维持30笔。

## feat(maintenance): 统一资料目录与作者加入排序（2026-11-06 第六百八十七笔）

执行 AI：完整型号未知（主代理；当前会话未提供可核实的完整型号与变体元数据）。

- 作者TXT、备份与上传归档合并为三任务页面，一次选根目录生成对应文件夹、原始TXT、阅读版作者资料、片段索引及v2备份。Android原子保存派生路径，自动与手动备份都更新资料；Web用现有平台目录API与IndexedDB保存授权句柄，保留下载回退，上传原件副本独立归档与报错。
- 作者列表增加加入顺序正反排序，历史顺序沿TXT首次出现，入库记录时刻独立展示并注明历史日期边界。协议/SQL由云端生成；Android作品详情作者弹窗接入认证离线头像及共享渲染件。
- 更新UI、双端入口、作者/API/迁移专题、SECURITY与媒体整理规范。真实主TXT已刷新并区分bak与文件日期，最终云端门禁、出包与双端安装状态继续记HANDOVER。

- 统一主TXT由服务端同源快照合成，原片段子目录留档；补主表外部编辑保护、目录备份SHA-256/上一份保留、独立导入前快照和档案差异提示。Web在授权页面内自动写回，App本机镜像采用master。v2继续作为当前格式，安装序号33，云端生成42170017268。

- 用户追加实测TXT重新导入仍缺作者，补v2恢复/改名到TXT作品行的一致性维护和重建回归；解除相册滚动缩略图暂停。最新手机备份及新增作品已同步电脑，最后安装来源见HANDOVER。

- code33安装后按用户截图重整手机维护三页，目录摘要、任务卡与按需高级维护统一。所有共享媒体网格补滚动方向邻位有界预取，解除收藏/历史暂停，候选code34；实际云端及装机记录见HANDOVER。

- 用户追加将统一目录收敛为三代中文文件名，作者主表与完整备份各保留最新、上次、上上次；手动/自动/导入前保护纳入同一备份链，相同数据不挤占历史，外部待导入文件受摘要保护。停止额外输出原始片段，候选安装序号35，最终验证与装机记录见HANDOVER。

- 同源浏览器回归发现推荐换批返回相同数据时未再次归位，补请求完成状态作为归位时机，保留原键盘End与延迟响应回归。

- 安装前以真实完成导出重新复核，纠正暂存文件误当回读的证据；确认Android Go默认UTC造成双端日统计错位，补手机系统IANA时区传递、启动校验与跨日回归，继续同源出包及真实同步。

- 最终264b9d83的完整CI42208227103、安全42208227084、文档42208227109、PC42208361832及APK42208357598均通过并实际更新双端，手机0.2.1/code35。真实完成导出核对153位作者、7246媒体业务信息、21402事件、标签/关联/偏好及逐日统计一致；本机身份、溯源和文件时间分开记录。两端目录均保留三代，旧生成文件已清理，手机两次重复导出未挤占历史。Web内置浏览器目录绑定仍按用户要求保留待后续处理，实际目录文件和PC主表镜像已核验。

## feat(author): 简介双语与编辑交互细化（2026-11-05 第六百八十六笔）

执行 AI：完整型号未知（主代理；当前会话未提供可核实的完整型号与变体元数据）。

- 用户追加要求保留原文并增加中文译文，主卡右侧切换，编辑页按语言编辑；协议增加可选biographyZh，同原文旧客户端兼容、跨端备份仅补缺省译文并保护人工清空。SDK继续云端生成。
- 编辑页补作者上下文、分块层次、修改反馈与操作状态。用户追加反馈快速滚动头像回闪，Android补256条/16MiB会话缓存、稳定图片键、刷新及切服失效和迟到隔离回归；资料保存后推进缓存域，页面新会话独立图片键，安装序号31。浏览器回归发现字数被并入简介控件的无障碍名称，补显式名称以保留稳定语义。Web存储明细沿用数据管理的顶部返回栏与标题布局；返回对齐仅涉及Web。
- 文档影响为作者/迁移领域、SECURITY、UI/两端入口与API说明；工具入口补媒体复核、规范命名、双端资料/标签同步和总表增量交付流程。当前协议生成与实现进行中，实际来源与安装记录见HANDOVER。

## fix(author): 资料默认只读与分块编辑（2026-11-05 第六百八十五笔）

执行 AI：完整型号未知（主代理；当前会话未提供可核实的完整型号与变体元数据）。

- 按用户澄清，App/Web资料页默认只读，统一X资料、基础资料、账号来源和作品订阅四块，逐块进入编辑；保存/取消返回概览，脏草稿确认、冲突保留及完整修订保护沿用。完整核验说明与检查记录折叠，候选核对独立显示受影响字段。实际窄屏核验发现刷新悬浮按钮遮挡保存，补侧向留白、纵向操作及无重叠回归。
- Android使用全屏单块编辑页、滚动正文与固定保存区，并处理键盘/安全区；code28真机核验确认键盘上方保存区可用，随后修正独立窗口系统栏图标对比，安装序号29。新增分组隔离、取消草稿及候选保存后回只读的行为回归，验证和出包继续云端。
- 文档影响为GUIDE_UI、两端README及HANDOVER，原作者来源与作品订阅语义保持。bd9cef43/code27已安装，122份X原文已保存电脑；本次UI候选门禁与安装待完成。

## feat(author): X原文与独立资料及作品分区（2026-11-05 第六百八十四笔）

执行 AI：完整型号未知（主代理；当前会话未提供可核实的完整型号与变体元数据）。

- 附属档案增加可选xProfile，X简介原文与整理说明分开保存，旧客户端省略保留。便携导入为同账号的既有核实档案补缺省原文，已有人工字段与原文保留，重复导入幂等。SDK及锁由云端生成。
- 主卡仅保留X简介和X入口，完整平台、ID、核验与维护进入独立资料子页；下方区分本地作品和作者新作，暂无可靠作品订阅时提供发布入口。Android安装序号27。
- 文档影响覆盖API、作者/迁移专题、安全、两端入口、GUIDE_UI、HANDOVER和研究方法。当前源码云端门禁、出包与实际部署待完成。

## feat(ui): 作者头像列表与资料显示层次（2026-11-05 第六百八十三笔）

执行 AI：完整型号未知（主代理；当前会话未提供可核实的完整型号与变体元数据）。

- App/Web作者管理接入认证头像、独立卡片及单独COS标记，头像读取跟随可见项。Android浅灰页底与白卡分层，深色页底同步加强对比。
- 作者档案默认显示简介首段，ID、完整核验信息与平台链接按需展开；未配置动态时隐藏空卡。Web媒体详情作者卡补头像，设置按钮底边对齐左侧界面卡片。
- 安装序号递增26；用户追加反馈后，作者卡收紧边界，关注改紧凑描边，来源/改名/删除菜单显式卡面色、圆角、图标与删除分隔。文档影响覆盖GUIDE_UI、两端README、HANDOVER，以及用户明确授权截图反搜的安全边界和离线头像交付方法；云端与实际安装结果待补齐。

## feat(server): 离线头像迁移与作者研究交接（2026-11-05 第六百八十二笔）

执行 AI：完整型号未知（主代理；当前会话未提供可核实的完整型号与变体元数据）。

- v2作者段增加可选Base64头像图片，写前校验2MiB和图片类型；同来源既有档案可幂等补图，普通资料JSON排除图片正文，来源变更清图。认证头像接口优先读持久图片；KV档案身份迁移和删除沿用原事务。Android安装序号递增至24。
- 新增通用作者资料研究指南，整理平台入口、互链判定、稳定 ID、错误候选、历史纠错、头像校验及网络排查方法，维护工具入口提供导航。
- 真实研究资料继续保留在忽略的本地目录，增加来源摘要索引和逐人待复核交接包，明确研究、审核与实际应用的边界。公开文档只保留通用方法。
- 用户追加后经现装手机 App 的 v2 作者段入口完成资料同步，留存结果与样页证据，原手动备份摘要一致且目录选择恢复。手机样页的公开头像源访问超时，网络显示验证仍有缺项。
- 文档影响覆盖 tools 指南、入口与 HANDOVER 当前状态；同步备份协议、作者/迁移领域、SECURITY与模块入口，增加离线读取、补图幂等、人工字段保留、来源失效和坏图写前拒绝回归。云端SDK生成、检查与手机新包验证待执行。本轮交接范围、文件摘要、本地链接、文档与只读隐私门禁通过。按近期条目上限完整归档既有第六百五十二笔并同步历史索引。

## feat(app): 作者档案分区与公开动态浏览（2026-11-05 第六百八十一笔）

执行 AI：完整型号未知（主代理；当前会话没有可核实的完整型号与变体元数据）。

- Android 作者作品页按身份/主页、维护摘要、公开动态、本地作品分区，复用中性设置卡面；简介与核验详情独立展开。关注、维护设置和候选审核入口明确；动态默认三条、展开全部后点 HTTPS 原帖，不推断未读、收藏或入库状态。
- 编辑按公开资料、身份维护、动态追踪分组，取消核实同时关闭自动维护和追踪，追踪启用条件对齐服务端。保留 revision、失败草稿、离开确认和原作品分页导航；过滤计数明确为筛选结果，不当作者总数。
- 文档影响：GUIDE_UI 与 Android 入口同步当前交互；无协议、数据库、平台采集器或下载能力变更。增加空状态呈现回归，区分未配源、关闭、未检查、失败和空记录。静态差异/文档检查通过，b95b03db完整云端CI/安全/文档通过；qimeng_api35以重签核验副本验证作者页/维护弹层/草稿联动与离开确认。非空动态因模拟服务及夹具写入命令被自动审批拒绝，未完成可视验证；未更新手机。详情见HANDOVER。

## chore(app): 云端包静默安装电脑与手机（2026-11-04～05 第六百八十笔）

执行 AI：完整型号未知（主代理；当前会话未提供可核实的完整型号与变体）。

- 按用户本轮要求，以3525cb6a完成云端PC编译并核对同源完整CI、安全、文档；ZIP与116份产物摘要一致。正常停服后整体更新服务端与115份Web，隐藏启动、无浏览器弹出并保持服务运行。
- 先在隔离快照验证schema20→22、12张关键表内容与数据库完整性；保留本次迁移涉及的数据库/sidecar、媒体签名密钥、配置/启动脚本与旧包。正式升级后health/ready均200，实际HTTP逐文件一致，升级前业务原行和媒体签名密钥保持。没有复制整库媒体或向真库写合成测试，副本不冒称异机灾备。
- 自动审批拒绝进程内存环境读取后改用现有启动配置，未执行或绕过；安装尾部临时句柄导致的只读核验中断已独立补齐，未重复安装或迁移。作者研究不再调度，本轮未填充真实资料；11月4日PC更新时手机暂缓；11月5日追加手机安装见下条。
- 11月5日按用户追加要求，将原真机同签名保留数据覆盖code21→23，来源c33392a1/Release41841846710，同源门禁通过；包名/版本/证书/内嵌三件套与Go身份核对，回拉APK摘要一致（`437225afd0c277582afc97ded7fc84883522cabd4ce0e17be6d3543d1cb1b940`），首次安装时间、UID及数据目录保持。未启动Activity，安装后保持停止；未启动内嵌或验证真实库迁移/SAF，不将电脑结果外推为手机验收。
- 文档影响：HANDOVER记录电脑实际安装及手机已装未启动；Android/Web入口区分两端来源，能力地图同步档案部署边界。安装证据只留忽略目录；后补文档不重标已装来源，不自动合并PR或扩展清理。

## feat(app): 作者档案双端原位集成与备份导入保护（2026-11-04 第六百七十九笔）

执行 AI：完整型号未知（主代理与工程子代理；当前会话无可核实的完整型号/变体）；GPT-6 Luna（公开作者身份研究子代理，用户明确指定）。

- App/Web 作者作品页原位整合附属资料、候选审核、人工编辑及可选公开资料检查；普通/COS 以各自内部身份定位，不新增底部 Tab，不改 TXT 来源或作者关联。Web 保存与退出放在右侧推荐偏好卡片下方，窄屏随内容排列，不吸附遮挡。
- 协议新增档案/刷新/认证头像代理，附属资料使用既有 KV；0021 清理已删除作者档案，0022 保留跨删除重建的版本代际。便携 v2 可选携带档案，恢复保护本端资料并关闭外部维护开关；来源采集有界且拒绝内网/代理/重定向，HTML 使用官方 x/net/html，Go1.27.3修复证书验证漏洞。Android新包序号23，实际安装另记。
- 浏览趋势按用户澄清定位PC导出→App导入的单向问题：修复App到期自动备份覆盖待导入同名PC文件的真实机制，自动改用独立文件，恢复来源显式选择，读盘/校验先置忙且取消可重试。未将该机制冒称为那次现场的已确认根因；事件序列化保留原日期/身份的回归与双隔离Go实例导入、趋势一致、不重复验证分别记录。
- 按后续截图将存储明细改为独立页面，客户端异常直接打开全部日志，移除设置顶部重复分类入口；保留既有资源口径、错误重试和数据获取分层。作者研究已按用户要求移交，真实档案写入0，报告与审核清单仅在忽略目录。
- 文档影响：作者领域/API、安全、架构、备份专题、各端入口、UI 约束、监控与交接；真实作者研究材料不入仓库。
- 最终代码81042304完整CI、安全、文档通过；Web355项单元/41项浏览器回归通过并核验最终截图，PC候选116文件摘要及版本核对通过。c33392a1的code23 Release候选已过同源门禁；各来源与网络/趋势隔离实测见HANDOVER。本轮未部署日常服务、未安装手机，未向真库写合成测试或启动默认Android设备；后补文档不重标产物来源。

## docs(app): 合并后归档交付记录并收尾产物清理（2026-10-31 第六百七十八笔）

执行 AI：opencode-go-chat/deepseek-v4.1-flash（主代理；工具 ZCode）。

- [PR #33](https://github.com/Surtr42u/qimeng-media/pull/33)已合入master为258185d5，PR head a652b3b6的完整CI、安全与文档门禁通过；本地只留master并与origin同步，已完成用途的任务分支清空。合并与纯文档提交不重标已装包来源，仍为576b32dd/code21。
- HANDOVER的已结项记录、旧候选与安装状态原样迁入[2026-10-26～31交付归档](history/DELIVERY-20261026-20261031.md)，当前交接只留现状、安装记录与边界；同步README、Android入口、架构、能力地图、API files与历史索引，链接和章节可达。
- 本地清理4,930个文件、4,963,049,993字节（约4.62GiB）已完成并留审计摘要；当前程序/Web、运行日志、安装核验、校时改前记录、内容摘要与数据库保留，旧包不再作为回退材料。
- 云端过时产物按计划333份收尾：本轮删除剩余179份，累计7,667,974,082字节（约7.14GiB），复核333份均不可达；保留实际安装来源PC artifact13032136003与Release artifact13032530835，两者仍可下载且未过期。
- 本次不重新构建、不安装、不操作设备，真实媒体、历史、配置与密钥保留。文档影响：交接、交付归档、变更历史、历史索引、能力地图、README、Android入口、架构与API files；无OpenAPI/schema变化。
- 提交前文档检查（tools/check_docs.py）0项错误；云端门禁随本次推送执行，结果以实际运行为准。

## fix(app): 上传与归档使用本次上传时间（2026-10-31 第六百七十七笔）

- 首次上传固定入队时间，归档副本同刻，归档再传保留，忽略下载器旧日期；旧在途任务和重试时间保持。补首次/归档再传、归档去重与回退路径回归。
- 增加仅本地显式小批日期校正，先报告、数量/版本/路径保护、归档内容比对，用户范围仅最新一批作者Liepraag七项；实际为10月31日凌晨入库，按作者关联及完整内容摘要唯一匹配后，两端物理时间与数据库已对齐手机原入库时间，另五项不变。
- 干净来源576b32dd的完整CI/安全/文档16项及PC/Release出包全部通过。原手机同签名保留数据覆盖到code21、签名/内嵌来源/已装字节一致；电脑正常停服后更新服务端与111份Web，HTTP逐文件、health/ready及七项校正核对通过。恢复手机原NAS连接、清理手机维护临时文件；完整证据见HANDOVER，不外推为NAS硬件或新增性能验收。
- 文档影响：Android入口、文件领域、服务端入口、安全边界与交接；无协议/schema变化。执行 AI：完整型号未知（主代理，当前会话未提供可核实完整型号与变体）。

## chore(app): 准备局域网连接双端保留数据安装（2026-10-31 第六百七十六笔）

执行 AI：未知（主代理；工具 Codex；当前会话无可核实的完整型号与变体元数据）。

- 用户明确安装电脑和原手机，不再测试；连接功能不变，仅递增安装序号19→20，版本0.2.1/schema20保持，为同源PC及签名arm64 Release包准备云端来源。
- 不新增业务测试，不卸载或清除日常手机数据，不主动启动App；安装只核对云端来源/既有门禁、签名、包字节及配置保留。云端打包自带检查继续执行，不修改流水线来绕过。
- 文档影响：HANDOVER区分发布准备和最终装机证据；本笔不改变UI/协议/领域/持久化规则，Android已有安装序号单源说明继续适用。实际部署结果后补，不把候选或旧包算新安装。

## feat(app): 本机与NAS双模式连接、局域网发现及五地址记忆（2026-10-31 第六百七十五笔）

执行 AI：未知（主代理；工具 Codex；当前会话无可核实的完整型号与变体元数据）。

- 登录页重做为本机/NAS双模式按钮、连接卡片、可滚动布局与折叠手填入口；服务器设置可回到NAS查找。NAS先按成功历史顺序单播寻找，均不通再广播当前网卡；单候选自动填入、多候选选择，不自动提交密码或迁移旧会话。
- DataStore保留最近5个去重成功NAS地址，本机登录/未确认预置不覆盖；兼容旧单槽，适应Wi-Fi/网线往返。显式选择/手填不被迟到候选覆盖，离开前台/切本机取消搜索。自动发现等待冷启动权限处理后再启动，主动动作走原权限门。
- 服务端独立有界IPv4 UDP发现，不缓存网卡IP或访问业务库；回环内嵌不开放。换网瞬间单包/旧IP路由失败不阻断后续查找；AI调试工具提供JSON输出。只用标准库，无新运行时依赖，选型复查/退役条件见server入口。
- 验证：代码c04a9890的完整CI/安全/文档16项通过。云端APK摘要核对后在qimeng_api35独立初始数据/只读会话核验模式、搜索/空态、手填和浅深色；现场确认旧窗口adjustPan，最终显式adjustResize后状态栏不再重叠，键盘保留可滚动到主操作。会话已关闭；未更新日常PC/手机，真实切网、API37权限弹窗及NAS硬件另验，不宣称安装完成。
- 文档影响：Android/server入口、UI、安全、架构、能力地图、部署与工具说明同批同步；HTTP OpenAPI/SDK和数据库schema未变。NAS bridge/host网络条件与来源证据见HANDOVER；后补验证文档保留c04a9890产物身份。
