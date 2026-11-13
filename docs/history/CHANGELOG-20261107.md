# CHANGELOG 历史归档：2026-11-07

> 第六百九十四～六百九十九笔，完整原文迁入；现行状态见 HANDOVER。

## feat(tools): 研究核验 CLI 命令（context/provider/lens）（2026-11-07 第六百九十四笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `tools/ai_maintenance/cli.py` 新增只读 `research` 命令组：`research context`（GET /api/v1/research/context，打印 enabled、sidecar configured/available/pending、provider 矩阵与限额）、`research provider --provider <name> --operation <author|post|work|artist|tags|url|workshop|search> --target <值> [--limit N]`（POST /api/v1/research/providers/{provider}/query；CLI 不硬编码矩阵，矩阵外/非法参数由服务端 400 透传）、`research lens --file <本地图片>`（POST /api/v1/research/lens 的 multipart 字段 file；扩展名与 20MiB 本地预检、150 秒超时、摘要只打印 status/frameSha256/topTitles/links 数量，不打印图片内容）。所有命令支持全局 `--json`；缺配置退出码 2 且不回显 token，图片字节不进入任何输出。
- 规则包与文档同步：`rules.json` 三条 research capabilities 的 `cli` 指向实际命令、研究指南补 CLI 用法并删除「本切片未提供研究 CLI」，规则版本 `2026-11-06.3`；`rulepack.py` 去掉与指南重复的研究节并把 research 命令加入只读命令清单；`tools/ai_maintenance/README.md` 增研究示例与 `RESEARCH_DISABLED`/`RESEARCH_SIDECAR_*`/`RATE_LIMITED`/`INVALID_PARAM` 语义；`docs/api/research.md` 增「命令行工具」小节，注明研究默认关闭、sidecar 需另行部署。
- 验证边界：`tools/test_ai_maintenance.py` 由 23 项增至 31 项并全部通过，仅用临时目录与 `127.0.0.1` 随机端口 stub（研究成功摘要与 --json、POST 路径/Bearer/JSON 体、multipart 字段与文件名、服务端 400/503 透传、缺配置、token 脱敏、本地文件预检）；未访问真实平台、未安装 sidecar、未触碰 8420 真库，也未改 OpenAPI/Go。`python -X utf8 tools/check_docs.py` 0 错误、`python -X utf8 tools/check_privacy.py --mode worktree` passed。第六百六十四笔完整原样移入 docs/history/CHANGELOG-20261028.md，近期日志保持 30 笔。

## feat(app): 安装 796d813f 双端并完成研究流水线真实实例试验（2026-11-07 第六百九十五笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 云端同源门禁：ci/安全/文档与 PC/APK 出包均在 796d813f 通过（CI 首轮 Windows 文件操作作业因环境清理失败，同作业重跑通过）。PC 安装 119 项清单本地 SHA-256 核对通过，version JSON revision=796d813f、schema22、health/ready 200；手机同签名保留数据覆盖安装，版本 0.2.1/code35（lastUpdateTime 2026-11-07 01:59）。
- 研究流水线真实实例试验：临时开启 `research.enabled` 并运行本机 sidecar，`/research/context` 返回 enabled=true、sidecar available、7 provider；`/research/lens` 真实返回 200/约 13s（frame SHA 与真实库图片一致、links 空、top 域噪声，符合 best-effort 预期）。`/providers/fxtwitter/query` 因本机 DNS fake-IP（198.18.1.x 保留段）被 SSRF 防护拦截返回 ADDRESS_BLOCKED；未放宽安全策略，需本机切真实 DNS 或另立显式逃生配置后再验。
- 顺带按用户 2026-11-07 授权补挂作者：POST /assets/{assetId}/authors 将「守望先锋 天使 18.jpg」关联到 vgerotica 并回读一致；写前已做服务端热备。
- 边界：未做手机 v2 同步；研究默认关闭、日常启动不带试验环境；真实 429/503/504 未触发；所有证据与安装副本只留忽略目录，未写测试脏数据。

## feat(maintenance): 持久维护任务队列服务端垂直切片（2026-11-07 第六百九十六笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 migration `0023_maintenance_jobs`：只增 `maintenance_jobs`/`maintenance_steps`/`maintenance_artifacts` 三表与状态、幂等、租约、步骤/工件索引；状态机覆盖 queued→inventory→research→planned→applying→verifying→completed、waiting_evidence/waiting_authorization/waiting_device 与 paused/partial/failed/cancelled，终态不可变更。
- 新增 `server/internal/maintenance`：Create/List/Get/Pause/Resume/Cancel、同事务状态校验、幂等键冲突返回既有任务、输入指纹、租约字段与 Claim/Heartbeat/Complete、RecordStep/AddArtifact 内部原语；只依赖 store/db，不依赖 httpapi（depguard）。
- 协议新增 `POST/GET /api/v1/maintenance/jobs`、`GET /api/v1/maintenance/jobs/{id}` 与 `POST …/{id}/pause|resume|cancel` 六端点；创建幂等 201/200，非法迁移/终态 409 STATE_CONFLICT，不存在 404，列表 status/limit/cursor 与详情步骤/工件/error。`make sdk` 三端生成通过，`api/sdk.lock` 同批更新为 331 项。
- httpapi 只做协议校验/错误映射/响应装配；测试覆盖创建/幂等重复/并发同键、列表过滤与游标、状态机合法/非法迁移、暂停/恢复/取消、终态不可变与 404/409 映射，全部使用临时 SQLite 与合成数据。文档同批：`docs/api/maintenance.md`、ADR-0054 与索引、能力地图、HANDOVER、AI_MAINTENANCE_P0 §4.2/§5、llms 导航。
- 验证边界：本地已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet` 与迁移只增检查；业务 `go test` 按仓库规定走云端 CI。**未安装**到现装 PC/手机；worker 执行器、Web 任务中心、PC→手机桥接、作者清理工具与两代布局仍未实现。第六百六十六笔完整原样移入 `docs/history/CHANGELOG-20261028.md`，近期日志保持 30 笔。

## feat(server): 设备结果包服务端 API（PC→手机无前台维护第一刀）（2026-11-07 第六百九十七笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 migration `0024_device_apply_tasks`：只增 `device_apply_tasks`/`device_apply_segments` 两表与「设备+状态+新建倒序」「过期」索引，不改历史表/索引；任务状态机 pending→claimed→applied/partial/failed、pending/claimed→cancelled/expired，终态不可变更，过期由读写路径惰性标记。
- 新增 `server/internal/deviceapply`：manifest 校验（targetDeviceId/protocolVersion=1/rulesFingerprint/ttl/段 kind/bytes/SHA-256/expectedReadback）、段落盘时流式复算摘要与字节数后再单事务写库、失败清理任务目录；Create/List/Get/Claim/OpenSegment/Result/Cancel；领取对已 claimed 未过期任务幂等、相同归一化结果回传幂等（不同结果 409）、取消对 cancelled 幂等；段相对路径逐字校验+根目录包含，下载前复核文件类型与大小；只依赖 store/db，不依赖 httpapi（depguard）。
- 协议新增 7 个 `/api/v1/device/apply-tasks` 端点（登记/列表/详情/领取/段下载/回传/取消）：登记为 multipart manifest + masterTxt≤1MiB + v2Backup≤64MiB，单个段或整体请求体超限 413；段下载 octet-stream + Content-Length + X-Content-SHA256；`make sdk` 三端生成、go build ./... 通过，`api/sdk.lock` 同批重算（355 项）。
- httpapi 只做 multipart/query 校验、错误映射与响应装配；测试覆盖创建/段落盘 SHA-256/超限/指纹不符、列表过滤与游标、领取幂等与过期、段下载头/终态/路径安全、回传终态与重复回传、取消、404/409/413，全部使用临时 SQLite、临时包目录与合成字节（含 1KB 段）。
- 文档同批：`docs/api/device.md`、ADR-0055 与索引、能力地图、HANDOVER §8、AI_MAINTENANCE_P0 §4.5/§5/§6、llms 导航。验证边界：本地已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet`、文档/隐私/迁移检查；业务 `go test` 按仓库规定走云端 CI。**未安装**到现装 PC/手机；Android Worker、配对/设备身份、真机后台同步与包清理仍未实现。第六百六十七笔完整原样移入 `docs/history/CHANGELOG-20261028.md`，近期日志保持 30 笔。

## docs(ai): 独立 AI 接入平台 V2 任务书、ADR-0056 与 R0 定向审计（2026-11-07 第六百九十八笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 纳入 [外部 AI 接入平台统一任务书 V2](../AI_PLATFORM_TASKBOOK.md) 作为现行执行清单（R0–R8、§6.6 组图命名与作品编号、§6.7 搜索语义与双端文件名显示、§11 安全权限与 §14 验收矩阵）；原[外部 AI 维护任务书](../AI_MAINTENANCE_TASKBOOK.md)与 [P0 底稿](../AI_MAINTENANCE_P0.md)标记为历史需求与设计来源，保留全文供追溯，不再作为并行执行清单。
- 新增 [ADR-0056 独立 AI 接入平台架构](../adr/0056-独立AI接入平台架构.md) 与 ADR 索引：两层接口独立、Python 标准库工具核心与显式注册表、版本分离与能力核对、计划/确认/执行/回读、唯一维护账本与内部 Worker、两代备份路径约定、研究默认关闭的边界与被否方案。
- 新增 [R0 定向审计](../AI_PLATFORM_AUDIT.md)：脚本/工具逐项清点（AI 工具链、作者写入、研究链、管理员工具、服务端能力）、三分类收敛、重复实现 D1–D8、规则包与 OpenAPI 逐条漂移（A1–A9，无 P0，24 个有端点声明路径+方法匹配）、复用/退役映射与最小变更计划。
- 文档治理同批：AGENTS/AI_README_FIRST/llms 任务入口、ARCHITECTURE 外部 AI 平台章节、CAPABILITY_MAP 能力域（AI 工具层/维护队列/两代备份/组图命名/检索筛选）、HANDOVER §5 指针与 §9 本轮交付边界；旧三代陈述在 docs/domain/migration.md、docs/SECURITY.md、tools/MEDIA_RESEARCH_IMPORT.md 改为「当前/上次」两代并保留旧 flat3 兼容读取；tools/README 增迁移工具条目。
- 验证边界：纯文档与入口调整，未改代码/协议/迁移/SDK；`python -X utf8 tools/check_docs.py` 0 错误、`check_privacy.py --mode worktree` passed。本轮源码交付的安装、真实数据与设备验收状态见 HANDOVER §9。

## feat(tools): 独立 AI 工具核心、作者链受控写入与 stdio MCP 适配器（2026-11-07 第六百九十九笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `tools/ai_maintenance/` 升级为可独立维护的统一工具包（仅 Python 3.13 标准库）：新增 versions/errors/openapi/schema/registry/runtime/execution/plans/client/validation 模块与 tools_query/tools_author/tools_asset/tools_media/profile_import 实现；分离 platformVersion/toolContractVersion/rulepackVersion 与 openapiSha256（仅来源追踪），OpenAPI 改为分块解析（path→method→requestBody/schema/parameters，支持 $ref），按工具独立核对端点+方法+必需字段并输出 supported/unavailable/incompatible；25 个显式注册工具（19 supported/6 planned 如实），可列举、可禁用（QIMENG_AI_DISABLED_TOOLS/QIMENG_AI_READONLY），新增工具不改入口分支。
- 统一 CLI 保留既有只读子命令与 `--json`/退出码 0/1/2 合同，新增 `version`、`tools list/show`、`tool call <id> --input-json/--input-file`；token 仍只来自环境变量，输出/日志/报告不回显；请求客户端统一（QIMENG_BASE_URL→QIMENG_API_BASE 兼容映射并提示弃用）。
- 受控写入：prepare 计划（目标实例/库/对象、changes、impact、evidence、前置 revision/摘要、planFingerprint、idempotencyKey、confirmation、recoverableSteps、expectedReadback）→apply 校验确认+指纹+toolContract/rulepack 版本+live 前置摘要→正式 API→回读→结构化报告；作者档案 prepare/update（人工已填字段默认保护，409 不覆盖）、cleanup prepare/apply（删除/改名/合并预览与逐项回读，零关联不单条件删除）、author.merge（只用原子端点）、asset.authors prepare/apply；旧 `tools/import_creator_profiles.py` 改为转发统一实现，保留参数/退出码/输出类别与人工资料保护语义。
- 新增 `media_naming.py` 组图命名规划引擎（作品编号 vs 组内页码、先分组→编号→页码、补图/缺页/并发/回读；prepare_plan/validate_plan/plan_steps/readback_report/SerializedLocks）与 `tools_media.py` 适配：apply 前按 instance+library 串行化、锁内重取现状+双检、逐步 POST move、回读发现自动改名/缺失/多余即失败；`media.organize.prepare/apply` 由 planned 动态变为 supported。
- 新增 stdio MCP 适配器 `mcp_server.py`（JSON-RPC 2.0 最小子集：initialize/notifications/ping/tools.list/tools.call/取消与超时；与 CLI 共用同一注册表与 Schema；无 shell/任意 Python 执行；不暴露 token）；协议层以两个独立合成客户端验证，**真实 AI 客户端（Codex/Claude 等）接入未实测**，README 如实说明。
- 规则源同批：rules.json schemaVersion=2、source 指向 V2 任务书与 ADR-0056、新增 mediaNaming 规则节、补齐 maintenance/jobs* 与 device/apply-tasks* 能力声明、区分 status/apiStatus/toolStatus、过时三代陈述改两代；rulesVersion/rulepackVersion 增为 2026-11-07.3。
- 验证边界：`python -m unittest discover -s tools -p 'test_*.py'` 181 项通过（含旧 22 项 CLI 合同与旧脚本 15 项收敛回归、MCP 双合成客户端、命名 39 项）；CLI/MCP 冒烟通过；check_docs 0 错误、check_privacy passed。未改 server/api/web/android，未接真实服务端/真实 AI 客户端/8420 真库。
