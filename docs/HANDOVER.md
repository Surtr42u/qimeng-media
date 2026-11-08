# HANDOVER - 当前工作交接

> 只记录当前形态、近期交付边界和下一步。能力缺口见 [能力地图](CAPABILITY_MAP.md)，架构见 [ARCHITECTURE](ARCHITECTURE.md)，规则按 [AGENTS](../AGENTS.md) 路由读取。维护日期：2026-11-08。

## 1. 当前使用形态

面向未来 NAS 的自托管多端媒体库，当前用户尚无 NAS，以手机单机与 PC 过渡使用。Android 可运行内嵌 Go 服务端；Web/PWA 和 Tauri 连接 PC/服务器。M0–M6 的历史收官记录保留在 PROJECT_PLAN，不能当作当前版本在未来具体 NAS 硬件上已验收的证据。

用户2026-10-25明确：现在实际使用的是本项目的App和Web；另一个项目的纯本地旧App已经停用，不列为现用客户端或持续同步端。旧项目只保留历史交互/领域依据；当前源码和本次已安装包均按用户要求退役v1备份导入，见ADR-0050及§2的实际装机记录；已经迁入本项目的浏览、收藏等历史记录仍是本项目数据，停用旧App不表示应删除这些记录。旧App是否卸载、遗留文件是否仍落盘本次未核验，也未授权清理。

| 使用方式 | 入口 |
|---|---|
| PC 日常服务端 | 根目录 `启动-浏览器.bat` / `启动-无头后端.bat`；8420，数据在 `qimeng-data/` |
| Android 连接 PC/未来 NAS | 登录页NAS/电脑模式自动发现、最近成功地址记忆；手填为备用 |
| Android 单机 | App 本机模式；回环 `127.0.0.1:18430`，详见 android/README |
| 桌面连接壳 | 设置窗填写服务器地址，配置见 desktop/README |
| 本机同步（可选） | `local-sync/<库名>/`；开启条件、成功移走语义见 API files 专题 |
| 外部 AI 工具平台（可选） | 本机 CLI / stdio MCP（`tools/ai_maintenance/`）；AI 客户端凭据与审计管理为 8420 管理员端点，仅源码，未安装 |

## 2. 当前源码、运行包与验证边界

### 双端实际安装

| 端 | 已安装来源 | 实际核验 |
|---|---|---|
| PC 服务端与 Web | 796d813f，0.2.1/schema22 | version JSON revision=796d813f、modified=false；本地 119 项清单（服务端+118 Web）逐文件 SHA-256 通过，Web index HTTP 字节 3152 与清单一致；health/ready 均 200，旧程序与旧 Web 已备份到忽略目录 |
| arm64 手机内嵌 | 796d813f，0.2.1/code35 | 同签名（证书 SHA-256 8f62ba55…）保留数据覆盖安装成功，versionCode 35/lastUpdateTime 2026-11-07 01:59；包来自同 SHA 云端 APK 出包 |
| 桌面连接壳 | 原壳加载PC托管Web | 本轮更新共享Web，沿用现有壳 |

同源796d813f的[完整CI](https://github.com/Surtr42u/qimeng-media/actions/runs/42263379160)、[安全](https://github.com/Surtr42u/qimeng-media/actions/runs/42263379165)、[文档](https://github.com/Surtr42u/qimeng-media/actions/runs/42263379174)、[PC出包](https://github.com/Surtr42u/qimeng-media/actions/runs/42263417541)、[APK出包](https://github.com/Surtr42u/qimeng-media/actions/runs/42263427121)均通过；CI 首轮 Windows 文件操作作业因环境清理失败，重跑同一作业后通过（同 SHA 门禁）。APK 版本 0.2.1/code35，证书 SHA-256 与既有安装一致；实际安装字节与核验记录在忽略目录。

### 2026-11-08 源码增量（未安装）

2026-11-08 合并 PR #44/#45 并在工作区继续交付以下源码；**现装 PC/手机仍为 796d813f**，以下能力均未构建、未安装、未接真实库或设备，合并后云端 CI 在编写本文时仍待跑：

- Go AI 权限：新增 8 个写 scope（asset.write/asset.upload/asset.delete/tag.write/source.write/library.write/trash.restore/maintenance.execute）与方法+路径映射，未映射写仍默认拒绝；AI 客户端管理与审计查询 HTTP 端点仅管理员会话可用，AI token（含 admin scope）403；撤销/轮换约 5 秒缓存生效。
- 按客户端令牌桶限流（默认 600/分、突发 120，0 关闭，429 + Retry-After），进程内单实例。
- 维护任务外部执行器受控通道（claim/heartbeat/steps/artifacts/complete + fencing leaseToken，30–1800 秒），worker 恢复按完整已完成步骤跳过（修复 >50 步重复副作用）。
- 设备结果包服务端权威校验：manifest 可选 expectedState，回传时读库绝对计数并与段摘要核对，结果新增 authoritativeState/expectedStateMatches/expectedStateDiff/hashVerified，migration 0026 只增 verification_json。
- Android：领取/待办按 nextCursor 全量翻页（100/页、上限 2000）；本机模式自助签发设备令牌；设置页只读最近任务卡。
- AI 工具 1.1.0：43 个工具、toolContract 1.1.0、rulesVersion 2026-11-08.1。

验证、未完成项与下一步测试清单见 §10。

### 数据与实际功能

- 本轮手机最新状态先导出，再经正式v2双向合并。最终以界面明确完成后的真实导出核对，7246个媒体的作品身份、类型、大小和尺寸，153位作者业务档案、7385条作者关联、20个标签及1135条标签关联、21402条浏览事件、14785条历史条目、1659条逐日统计、839条累计统计、时间轴、点赞收藏、关注和推荐权重一致。包含135份离线头像、120份中文译文。设备目录、登录与运行配置沿用各端实际位置。
- 本轮15份媒体已按现有命名规范入库，后补手机新增作品已核对原件并同步电脑。作者名称规范化为ATE_Toon，重复旧作者行已在正常界面删除，7条旧关联均已由9条规范作者关联覆盖。未确认作品继续保留待审，Dmon 3在双端均无作者关联，不能自动挂靠。
- 当前作者资料默认分组只读，按组进入编辑，保留X原文及中文切换，其他来源进入详细资料。列表、详情弹窗均使用离线头像。维护页为紧凑目录卡与三任务入口；所有共享媒体网格统一连续加载及邻近窗口有界缩略图预取。
- 手机主TXT与完整备份内authorMasterTxt一致。主TXT含已核实主页、X主页和证据链接；简介、译文、离线头像及完整业务元数据由v2承接。原始审计、设备信息、逐文件摘要和应用结果只保存在忽略目录。

### 三代保存与时区收尾（2026-11-06）

- 作者主表与完整备份各保留最新、上次、上上次三代，采用中文文件名。自动、手动和导入前保护共用同一备份链；原始TXT仍由v2内部保存，目录停止额外输出片段与历史副本。两端维护目录均核对为三份TXT、三份JSON及其摘要，加阅读版、索引与说明共15个文件，旧生成文件已清理。上传归档继续采用现有原件副本规则。
- 手机实际连续两次导出，相同数据未轮换上次/上上次，历史两份摘要保持，最新备份摘要及主TXT与authorMasterTxt一致。六次混合手动/自动写入的三代上限、外部待导入文件保护及Go主表轮换均有云端回归。新维护页面和两个历史恢复入口已在真机查看，截图和逐项证据只留忽略目录。
- 安装前复核发现一轮误把待覆盖暂存文件当成手机导出，原先的全字段一致证据已撤回。最终改为等待明确完成提示、确认文件更新并校验摘要后回读，实际手机新增事件已正式合入电脑。
- Android内嵌Go原默认UTC，已通过手机系统IANA时区注入及启动设置time.Local修正。正常v2导入从原事件重建日统计，双端北京时间日界与逐日明细实际对齐，跨日启动回归及完整门禁通过。
- 一致性按业务内容核对。跨实例资产ID、本机入库记录、关联溯源时间、维护修订、标签组已知/未知编辑时间及物理文件时间分别保留其真实来源；Windows文件时间精度及历史副本mtime不同不能据此改写事件或伪造加入日期。标签组仍执行迁移专题的时间裁决，完整备份字节无需跨设备相同。

### 保留的限制

Web内置浏览器目录选择仍返回AbortError，页面持续显示未绑定。实际维护目录内的主TXT、阅读版与完整备份已核对，PC服务端主表镜像绑定与浏览器授权是两个独立状态。用户明确要求保留现场以后自行处理，本轮停止重试；尚未对照普通浏览器，根因不能确认。浏览器自动备份目录写回未验收。

当前自动新作筛选尚未接入，作者新作区提供发布入口。x86_64内嵌SQLite SIGSYS、长期后台、真实网络切换与未来NAS硬件不在本轮验收范围。旧阶段记录与失败证据完整保留于[2026-11-04～06交付归档](history/DELIVERY-20261104-20261106.md)，更早记录见[2026-10-26～31归档](history/DELIVERY-20261026-20261031.md)。

外部 AI 平台尚无 Web 管理 UI；动作级确认 token、多实例共享限流、backup.import、Android 设备配对/设备身份证明与临时包清理仍未实现；研究 provider 仍受本机 fake-IP DNS 影响（未放宽 SSRF）。新增能力的验证边界与下一步测试见 §10。

## 3. 当前维护结果与后续边界

本轮草稿PR42保持未合并，后补文档不重标已安装包的代码来源。数据一致性是上述回读时点的结果，两套单机服务之后各自新增数据仍须通过现有同步流程合并。常规维护不自动重启旧任务书或已暂停的身份研究。外部AI维护需求已按2026-11-06第二批调整；两代备份与 Android Worker 源码已在本轮交付，但现装包仍是三代备份与标签明文。PC→手机现有服务端设备结果包 API、服务端权威回读与 Android 后台 Worker 源码（2026-11-08），设备配对、包清理与真机后台同步仍未实现/未验收，端到端桥接不能写成已具备。外部执行器受控通道与 AI 客户端 HTTP 管理同样只有源码，见 §10。

## 4. 本地操作约定

- 8420 为真库，禁止写测试脏数据；隔离验证不重用其数据目录。Android 显式选择 `qimeng_api35` 或用户指定真机，禁止操作雷电 `emulator-5554`。
- 自动构建/单测/基准走云端，本地文档静态检查与只读隐私扫描/hook按[协作规范](../AI_README_FIRST.md#验证与交付)执行。此前获授权的隔离云端产物运行验证不构成长期设备/真实数据测试许可；日常由后端托管web/dist，同源门禁、安装字节和现场结果分开记录。
- 部署核对产物同SHA的完整CI/安全/文档及签名/身份；审查遵守[Review guidelines](../AGENTS.md#review-guidelines)，结果按提交记录。某次安装不等新增审查的历史范围不作永久豁免，不主动重复触发或修改额度设置。
- 日常启动链保留 dev 免密及局域网监听是已接受例外，仅限受信家庭内网；生产/远程关闭 dev。机制、风险与适用范围统一见 SECURITY「开发模式」。
- 密钥、真实设备标识和个人环境详情不得入库。

glib GHSA-wrw7-89jp-8q8g / RUSTSEC-2024-0429 的现行处理见[SECURITY依赖与供应链](SECURITY.md#依赖与供应链)，历史证据见本节上方链接的交付归档。Cargo audit 通过或 GitHub 告警来源变化本身不证明缺陷已修复，必须同时保留源码来源、实际依赖解析及优化回归证据；不迁移 GTK/Tauri 主栈、不手工关闭告警。

## 5. 外部AI维护任务交接（2026-11-06）

> 2026-11-07 更新：现行执行清单已迁移到[外部 AI 接入平台统一任务书 V2](AI_PLATFORM_TASKBOOK.md)与 [ADR-0056](adr/0056-独立AI接入平台架构.md)；本节保留历史交接记录，最新状态见 §9。2026-11-08 更新：外部执行器通道、AI 客户端 HTTP 管理/审计/限流与设备权威回读已实现源码，仍未安装，见 §10。

用户要求混合媒体下载后由外部AI研究、校对命名、补作者/头像/链接/双语简介并执行清理与关联维护，任务后台执行、双端可用，未来延伸到NAS云端模型。第二批需求（2026-11-06）重新划定范围：[ADR-0052](adr/0052-外部AI维护需求修订.md)取代ADR-0051的标签加密部分，取消标签加密、外部代号与AI标签维护；新增本地维护权限（只读服务端数据目录、授权维护目录读写、业务写入走正式API）、无前台 PC→手机桥接、作者清理/去重、两代备份分代目录与可发现规则包。

交接入口为[详细任务书](AI_MAINTENANCE_TASKBOOK.md)，含用户场景、工具契约、本地维护权限与无前台跨端通道、规则包、后台任务、两代备份及阶段验收。规则包/只读 CLI（第六百九十二笔）、作者合并端点（第六百九十一笔）、持久任务账本端点（第六百九十六笔，[ADR-0054](adr/0054-持久维护任务队列.md)）与 PC→手机设备结果包服务端 API（第六百九十七笔，[ADR-0055](adr/0055-手机无前台维护结果包协议.md)）已实现；worker 执行器、Android Worker/配对、Web 任务中心、作者清理工具和两代备份布局仍未实现，现行三代备份与标签明文继续有效。

实际运行包仍为上文796d813f（PC 0.2.1/schema22、arm64 APK code35）；后续实现者按任务书P0核对现状与缺口再逐阶段推进，不把文档完成当作代码或装机完成。P0 底稿见[现状、缺口与最小方案](AI_MAINTENANCE_P0.md)：P1 规则包/CLI、P2 作者合并与 P3 持久账本已落地；P4 第一刀（设备结果包服务端 API）已交付源码与 SDK 但**未安装**（2026-11-08 更新：worker 执行器、Web 任务中心、Android Worker 与两代布局均已实现源码；设备配对、包清理、真机与安装仍未完成，见 §10）。后续项目重构须继承任务书记录的场景、权限、恢复及数据不变量。

## 6. 研究流水线（2026-11-06）

服务端 provider 网关与本机 Lens sidecar 代理已按 [ADR-0053](adr/0053-研究流水线API集成.md) 集成：`GET /api/v1/research/context`、`POST /api/v1/research/providers/{provider}/query`（fxtwitter/pixiv/yandere/e621/wayback/steam/bing 固定矩阵）、`POST /api/v1/research/lens`（multipart，loopback sidecar）。安全 HTTP 出口（仅 https、域名白名单、禁重定向与环境代理、DNS 后校验公网地址、超时与响应上限）、provider/minute 与 Lens/hour/day 进程内预算、证据落盘（剥会话参数、不入库）同批落地；协议先改 OpenAPI，SDK 与 `api/sdk.lock` 由 `make sdk` 生成。

**实际状态：已随 796d813f 安装，并完成一次真实实例试验（2026-11-07）。** PC 以临时 `QIMENG_RESEARCH_*` 环境启动（日常启动 bat 不带这些变量，研究仍默认关闭）：`GET /research/context` 返回 `enabled=true`、sidecar available、7 个 provider；`POST /research/lens` 真实返回 200/约 13 秒，`frameSha256` 与真实库图片一致，但 `links=[]`、top 域多为噪声（再次验证 best-effort）。`POST /providers/fxtwitter/query` 返回 `provider_unavailable / ADDRESS_BLOCKED`：本机 DNS 把域名解析到 `198.18.1.x`（保留测试段，常见于代理 fake-IP 模式），被安全出口的 SSRF 校验拦截；未为打通而放宽安全策略，需本机切真实 DNS/redir-host 或后续另立显式逃生配置再验。真实 429/503/504 映射仍未触发。已知缺陷与限制见[研究 API 专题](api/research.md)。

## 7. 持久维护任务队列（2026-11-07）

> 2026-11-07 本轮补充：Go worker（租约/心跳/逐步记录/恢复/按 kind 领取）、同幂等键不同载荷 409 冲突保护与 Web 任务中心已实现源码，见 §9；本节其余描述为上一轮交付时点的历史状态。2026-11-08：外部执行器受控通道（`/api/v1/maintenance/executor/*`，fencing leaseToken）与 worker 恢复修复已实现源码，见 §10。

维护任务账本已按 [ADR-0054](adr/0054-持久维护任务队列.md) 落地 P3 第一刀：migration `0023_maintenance_jobs` 新增 `maintenance_jobs/maintenance_steps/maintenance_artifacts` 三表；`server/internal/maintenance` 提供状态机、幂等、租约字段与步骤/工件索引；OpenAPI 新增 `POST/GET /api/v1/maintenance/jobs`、`GET /api/v1/maintenance/jobs/{id}` 与 `POST …/{id}/pause|resume|cancel` 六个端点，SDK 三端与 `api/sdk.lock` 同批生成（331 项）。创建幂等（同 key 返回既有任务，201/200），非法迁移/终态 409 `STATE_CONFLICT`，不存在 404；任务载荷/预算为有上限的 JSON 对象，工件正文不入库。

**实际状态：已实现、未安装。** 本轮未在现装 PC/手机部署，也未对真实库创建任务；现装仍为上文 796d813f。worker 执行器尚未实现——没有进程消费 queued 任务，租约 `Claim/Heartbeat/Complete` 与步骤/工件写入只是包内原语、无 HTTP 端点；Web 任务中心、PC→手机桥接、`author.cleanup.prepare/apply` 与 MCP 适配仍未做（2026-11-08 更新：worker、外部执行器通道、Web 任务中心、Android Worker 与两代布局已实现源码，见 §10；设备配对/包清理与真机仍未做）。业务 `go test` 按仓库规定走云端 CI，本地已跑 `go build ./...`、`gofmt`、`go vet` 与 `make sdk`。端点无独立开关，但因无 worker，创建后任务只会停在 queued（或人工暂停/取消）。

## 8. 设备结果包 / PC→手机无前台维护服务端（2026-11-07）

按 [ADR-0055](adr/0055-手机无前台维护结果包协议.md) 与 [AI 维护 P0](AI_MAINTENANCE_P0.md) §4.5 落地 P4 第一刀（仅服务端）：migration `0024_device_apply_tasks` 新增 `device_apply_tasks`/`device_apply_segments` 两表与索引；`server/internal/deviceapply` 提供 manifest 校验、段文件落盘与 SHA-256 复算、状态机、惰性过期、领取/回传/取消幂等；OpenAPI 新增 `POST/GET /api/v1/device/apply-tasks`、`GET …/{id}`、`POST …/{id}/claim`、`GET …/{id}/segments/{kind}`、`POST …/{id}/result`、`POST …/{id}/cancel` 七个端点，SDK 三端与 `api/sdk.lock` 同批生成（355 项）。包体为 manifest JSON + masterTxt ≤1MiB + v2Backup ≤64MiB，整体 66MiB 上限；段字节落 `<dataDir>/device-apply-packages/<taskId>/`，数据库只存元数据；声明字节数或 SHA-256 不符在任何写库前拒绝。领取对已 claimed 任务幂等（无领取者身份），回传仅对完全相同的归一化结果幂等，其余终态变更 409。

**实际状态：已实现、未安装。** 本轮未在现装 PC/手机部署，也未对真实库登记/领取/回传任何包；现装仍为上文 796d813f。Android Worker（WorkManager 周期拉取、充电+Wi-Fi、前台立即应用、回读与清理临时包）、配对/设备身份、真机后台领取/应用/回传、包字节清理与 Web 结果包界面均未实现（2026-11-08 更新：Worker 源码、服务端权威回读、全量翻页与本机设备令牌已交付，见 §10；配对/包清理与真机仍未做）；`targetDeviceId` 只是不透明标签，不能当作设备授权。业务 `go test` 按仓库规定走云端 CI，本地已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet`、文档/隐私/迁移检查；测试只用临时 SQLite、临时包目录与合成字节。

## 9. 外部 AI 接入平台与维护执行面（2026-11-07 本轮交付）

本轮按[统一任务书 V2](AI_PLATFORM_TASKBOOK.md)（R0–R7）与 [ADR-0056](adr/0056-独立AI接入平台架构.md) 交付源码、测试与文档；**未安装到现装运行包**，现装仍为 §2 记录的 796d813f（PC 0.2.1/schema22、arm64 手机 code35）。本节区分「源码交付 / 本机验证 / 云端 CI / 安装 / 真实数据与设备验收」。

### 9.1 源码交付范围

| 阶段 | 交付 |
|---|---|
| R0 | [AI 接入平台 R0 审计](AI_PLATFORM_AUDIT.md)：脚本清单、三分类、复用/退役映射、规则漂移 A1–A9、最小变更计划；[统一任务书 V2](AI_PLATFORM_TASKBOOK.md) 纳入仓库并取代旧任务书作为现行清单；旧任务书/P0 标记历史来源；ADR-0056 与索引、AGENTS/AI_README_FIRST/llms/ARCHITECTURE/CAPABILITY_MAP 入口同步 |
| R1–R3 | `tools/ai_maintenance/` 统一工具核心：分离版本（platform/toolContract/rulepack/openapiSha）、25 个显式注册工具（supported/planned 如实）、分块 OpenAPI 能力核对（端点+方法+必需字段）、统一 CLI（保留旧只读子命令与 `--json`/退出码，新增 version/tools list/tool call）、计划指纹/确认/执行/回读框架、作者链受控写入（档案 prepare/update、cleanup prepare/apply、merge、asset.authors prepare/apply）、人工字段保护与 409 处理；旧 `tools/import_creator_profiles.py` 转发统一实现（环境变量兼容映射，不回显 token） |
| R4 | `tools/ai_maintenance/mcp_server.py` stdio MCP 适配器：JSON-RPC 2.0 子集（initialize/notifications/ping/tools.list/tools.call/取消与超时），与 CLI 共用同一 dispatch 与工具 Schema；协议层用两个独立合成客户端验证；**真实 AI 客户端（Codex/Claude 等）接入未实测**，README 已如实说明。扩展性增量（2026-11-07 用户补充）：精简领域目录 `platform.catalog`、按需 `platform.tool.describe`/`platform.rules`、注册表只存惰性 `module:handler` 引用、模块缺失/废弃/禁用降级、权限与发现分离（`QIMENG_AI_GRANTED_PERMISSIONS` 在 `execute_tool` 强制）、MCP 完整 `tools/list` 保留；管理员 Bearer 绕过风险已在 README/ADR/rules 记录为正式开放 AI 写入前的隔离前置项 |
| R5 | `server/internal/maintenance` Go worker：按注册 handler 的 kind 领取、租约 TTL+心跳、有界并发、逐步骤记录、暂停/取消后停止并保留已提交步骤、租约过期/重启后从已完成步骤续跑不重复副作用、panic 兜底、`backup_export` 内置 handler（复用 `backup.Manager`）与工件记录；同幂等键不同载荷指纹返回 409；由 `cmd/qimeng` 组合根以 `safego.Go` 启动，参数为具名配置/`QIMENG_*`；无 handler 的 kind 保持 queued 供未来外部 AI 执行器，未开放 HTTP 领取端点（2026-11-08 更新：已开放受控执行器通道，见 §10） |
| R7 | 两代备份布局：`server/internal/authorattach` 作者镜像在 `path` 祖先目录为 `当前`/`current` 时按同根兄弟 `上次`/`previous` 推导上一代，两代独立摘要、先提交当前再写新当前、相同数据不轮换、上次代摘要不符/不可读拒覆盖、旧 flat3 行为不变；`tools/migrate_backup_layout.py` 默认只读核对、`--apply` 复制生成两代、绝不删除；Web `maintenance-folder.ts` 与 Android `MaintenanceFolderRepository` 同约定两代轮换并保留旧布局只读兼容；文档见 [作者镜像两代分代目录](api/authors.md#作者总表镜像的两代分代目录2026-11-07) |
| 追加需求 2（2026-11-07 第二轮） | 9 个已报告问题修复（PR #44 Codex inline finding）：媒体目录筛选改用 GET /assets directory、计划绑定/校验实例、作者删除前复检档案/来源、cleanup merge 参数、按作者名回读、MCP 取消与写串行、Android 两代历史备份读取、Worker 租约 fencing（每次领取唯一 token + 未过期校验 + 步骤/工件事务围栏）；工具层按需工作流 `platform.playbook` 与 MCP async；Go 侧 ADR-0057 P1 首批（migration 0025 ai_clients/ai_audit、aiauth scope/审计、设备级 device.apply+device_ids、本地 `qimeng ai-client` 管理）；Android 后台设备结果包 Worker（WorkManager、设备令牌、SAF/所有文件访问门禁、领取/下载/应用/回读/回传）源码与 JVM 测试 |
| 追加需求 | 组图命名与作品编号（§6.6）：Go 命名解析纯函数（`server/internal/filing/workname.go`）区分主作品编号与组内页码、`/assets/name-suggestions` 对组图页建议下一作品编号；AI 工具层 `media_naming.py` 规划/校验/回读引擎与 `media.organize.prepare/apply` 适配（计划确认后才执行，自动改名判失败）；搜索语义与文件名显示（§6.7）：共享 Go 谓词 `qimeng_search_token`（数字按独立数字段、组图页码 `(n)` 定位、列表/计数/分面同口径），Web 搜索结果完整文件名/换词加载态/详情复制与 Android 搜索缩略图下短名/详情完整名+复制（Lead 直接实现并补 JVM 单测）同批交付 |

### 9.2 本机验证与云端 CI

- 本机实际执行：`python -m unittest discover -s tools -p 'test_*.py'`（142 项）、AI toolkit/MCP/命名/迁移工具单测、`check_docs.py`（142 文件 0 错误）、`check_privacy.py --mode worktree`（passed）、server 下 `go build ./...`、`go vet ./...`、定向 `go test`（maintenance/authorattach/filing/store/search/httpapi 相关）、sqlc v1.31.1 重新生成比对；Web 侧 vitest（维护库/任务中心）与 `tsc --noEmit`；Android 未构建（云端 CI 为编译门禁）。
- **云端 CI（HEAD ed712fcd）**：[ci 42376690218](https://github.com/Surtr42u/qimeng-media/actions/runs/42376690218) 七 job 全绿（服务端 go vet/golangci-lint/go test -race/go build、Web tsc/build/vitest/Playwright、Android assembleDebug+单测+Lint、SDK 生成链、桌面壳、Windows 原生文件操作、redocly）；[文档 42376690202](https://github.com/Surtr42u/qimeng-media/actions/runs/42376690202) 与 [依赖安全 42376690209](https://github.com/Surtr42u/qimeng-media/actions/runs/42376690209) 通过。基础交付 d1ef39f9 的 ci 42370683863 亦全绿。
- 未执行：全量 `go test ./...`（按仓库约定走云端 CI）、Android Gradle 构建/单测、vite build、真实服务端联调。
- **2026-11-08 合并/增量提交的云端 CI 尚未运行**：PR #44/#45 合并后的新提交与本条记录的 2026-11-07 门禁不是同一 SHA，不能沿用为已通过证据；本轮状态见 §10。

### 9.3 未安装与未验收边界

- 本轮**未部署**到现装 PC/手机；两代维护目录未迁移真实维护目录（迁移工具只在合成目录验证）；组图命名与媒体整理未在真实媒体库执行；AI 工具未对真实实例运行写入；MCP 未接真实 AI 客户端；Android 搜索文件名/详情复制未真机走查；搜索修复未在真实大库与内嵌模式做性能/行为验收。
- **9 个已报告问题的修复均只在本机做定向验证**：AI 工具 5 项（新增 test_ai_regressions 12 项）、Web 两代备份 24 项、Android 历史备份 28 项、Worker fencing 定向 go test；未跑全量、未接真实库/真实维护目录。
- **Go AI 客户端凭据（ADR-0057 P1/P2）源码已交付但未安装**：P1 本地 `qimeng ai-client`、scope/审计/设备级隔离；2026-11-08 增 HTTP 管理端点（仅管理员会话）、审计查询、8 个写 scope 与按客户端限流（见 §10）。Web 管理 UI、动作级确认 token、多实例共享限流、真实管理员/AI 客户端验收仍未做；进程缓存使异地撤销/轮换约 5 秒生效。
- **Android 后台 Worker 源码已交付但未安装/未真机验收**：JVM 单测 device 纯逻辑 37 项本机实跑通过（设置 3 项由 Gradle）与 compileDebugKotlin 通过（task-21 领取前置/claimed 恢复后需云端 CI 重新编译；2026-11-08 增翻页/本机令牌/最近任务，见 §10）；Doze/厂商后台限制、后台拉起内嵌 FGS、SAF/所有文件访问授权撤回、真实 PC→手机端到端、断网/进程被杀恢复均未验证；无段级进度持久化（进程强杀可能重下整包）；claimed 恢复已按 nextCursor 全量翻页（2026-11-08）；readback 默认仍是导入响应增量，manifest 可声明 expectedState 由服务端做绝对计数校验（见 §10）；设备令牌依赖服务端 ADR-0057 P1（旧服务端会 401，本实现不回退会话 token）；本机内嵌单机模式已可自助生成设备令牌（2026-11-08，仅内嵌实例预设地址）。
- 外部 AI 执行器受控通道已实现源码（fencing leaseToken/租约围栏，见 §10），但真实外部执行器、多执行器并发与 NAS 未验收；Android 设备配对（短码/二维码）与临时包清理未实现；Web/Android 未安装更新，现装仍为 796d813f。

## 10. 2026-11-08 外部 AI 平台与执行面增量（未安装）

合并 PR #44（外部 AI 平台、维护执行面、Android Worker 源码）与 PR #45（作者 TXT 硬约束、研究工具包）后，本轮继续交付以下源码。**现装 PC/手机仍为 §2 的 796d813f；本轮没有构建安装、没有真机/模拟器验收、没有向 8420 或真实维护目录写入。** 编写本节时合并后提交的云端 CI 仍待运行，本文不声称绿。

### 10.1 源码交付与本机验证

| 范围 | 交付 | 本机验证 |
|---|---|---|
| Go AI 权限 | 新增 asset.write/asset.upload/asset.delete/tag.write/source.write/library.write/trash.restore/maintenance.execute；方法+路径映射扩展，未映射写默认 deny；AI 客户端管理/审计查询仅管理员会话，AI token 即使带 admin scope 也 403 | aiauth/httpapi 定向 `go test`；未跑全量/race |
| 限流 | 每客户端 600/分、突发 120，`QIMENG_AI_RATE_LIMIT_PER_MINUTE`/`_BURST` 可调，0 关闭；429 `RATE_LIMITED` + `Retry-After` | aiauth/httpapi 定向测试；未压测 |
| 外部执行器 | `/api/v1/maintenance/executor/claim`（无任务 204）与 `jobs/{id}/{heartbeat,steps,artifacts,complete}`；fencing `leaseToken` 30–1800 秒（默认 120）；旧 token 409 `LEASE_LOST`；完整 completedSteps | maintenance/httpapi 定向 `go test` |
| 设备权威回读 | manifest 可选 expectedState；服务端绝对计数 + deviceLocalHashes 核对；结果暴露 authoritativeState/expectedStateMatches/expectedStateDiff/hashVerified；migration 0026 只增 verification_json | deviceapply/httpapi 定向测试（临时 SQLite、合成包） |
| Android | 领取/待办按 nextCursor 全量翻页（100/页、上限 2000）；本机模式设备令牌自助签发；只读最近任务卡（20 条）；本地/远端共用 runner | `:core:data` device/token 50 项通过；`:feature:manage`/`:app` compileDebugKotlin + hiltJavaCompileDebug 成功 |
| AI 工具 1.1.0 | 43 工具（14 项新增，backup.export 与 device.apply.* 转 supported）；错误目录新增 4 码；rulesVersion 2026-11-08.1 | Python 164 项通过；未接真实客户端 |

### 10.2 明确未做/未验收

- 未构建、未安装 PC/手机；未更新 APK versionCode 或发布产物；合并后云端 CI 待跑，不能沿用 2026-11-07 的绿。
- 未真机/模拟器验证 Android 后台（Doze/厂商后台、FGS、SAF 授权撤回、断网/进程被杀恢复）；未验证设备配对/设备身份证明与临时包 GC。
- 未对真实库/真实维护目录执行设备包回传、权威计数比对或外部执行器领取；未验证多执行器并发与租约竞争。
- Web 无 AI 客户端管理 UI（HTTP 端点已存在）；无动作级确认 token（当前仅 scope）；无多实例共享限流；backup.import 仍为规划；研究 provider fxtwitter 仍受本机 fake-IP DNS 拦截（未放宽 SSRF）；无 NAS 硬件验收。

### 10.3 下一步测试清单

1. 合并提交云端 CI（服务端 vet/lint/test -race/build、Web、Android assemble+单测、SDK 生成链、文档）转绿后记录 SHA 与 run 链接。
2. 隔离实例验证 AI 客户端管理：创建/列表/轮换/撤销、明文 token 只返回一次、AI token 访问管理端点 403、审计 keyset 分页、限流 429 + Retry-After。
3. 隔离实例验证执行器：claim/204、心跳、步骤/工件幂等、暂停/取消/过期/重领后 409 `LEASE_LOST`、>50 步恢复不重复副作用。
4. 隔离实例验证设备包：expectedState 命中/不符/未声明、deviceLocalHashes 少报/不符、相同结果重放不产生 409。
5. 构建并安装到 PC 与 arm64 手机（同签名保留数据），核对 version JSON/schema 0026/health/ready，按 §4 记录安装字节与来源。
6. Android 真机（显式 `qimeng_api35` 或用户指定设备）：充电+Wi-Fi 后台领取→导出→应用→回读→回传；Doze、进程被杀、授权撤回、本机模式令牌生成。
7. 设备配对/设备身份证明与临时包 GC 实现后再验跨设备误领/结果冒用。
8. 研究 provider 在本机真实 DNS 下复验 fxtwitter；不为此放宽 SSRF。

