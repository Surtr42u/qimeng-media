# 外部AI维护 P0：现状、缺口与最小阶段方案

> **历史状态（2026-11-07）：本文保留为历史基线，不再作为并行执行清单。** 现行执行清单见 [外部 AI 接入平台统一任务书 V2](AI_PLATFORM_TASKBOOK.md)；文中已完成事项迁入 V2 状态表与 [HANDOVER](HANDOVER.md)，未完成方案按 V2 R0–R8 推进。

> 状态：P0 底稿（2026-11-06），P1/P2 已落地部分能力，P3 第一刀与 P4 服务端第一刀已落地（2026-11-07）。依据[外部AI维护任务书](AI_MAINTENANCE_TASKBOOK.md)与 [ADR-0052](adr/0052-外部AI维护需求修订.md) 撰写；第六百九十一/六百九十二笔已落地作者合并端点与规则包/只读 CLI，第六百九十六笔落地持久任务账本与端点（worker 待做），第六百九十七笔落地设备结果包服务端 API（Android Worker/配对/真机同步待做），其余默认方案仍待推进。
> 边界：本文为纯文档底稿，不涉及真实库/8420/设备操作；作者合并与规则包实现的验证边界见 CHANGELOG 第六百九十一/六百九十二笔。
> 事实基线：现装仍为 796d813f、0.2.1/schema22（arm64 code35）；维护目录三代同目录、标签明文；PC→手机仅有**服务端结果包 API**，Android Worker/配对/真机同步仍未实现。规则包与只读 CLI、作者合并端点已于 2026-11-06 落地（第六百九十一/六百九十二笔）；持久任务队列已落地**仅账本与端点**（第六百九十六笔，worker 待做，未安装）；设备结果包服务端已落地 7 端点与段存储（第六百九十七笔，未安装）；两代分代目录、Android Worker/配对、`author.cleanup.*`、Web 任务中心与 MCP 适配仍属**待实现**项，未取得交付证据前不得写成已实现或已安装。
>
> **2026-11-08 状态增量**：外部执行器受控通道、AI 客户端 HTTP 管理/审计/限流、设备权威回读（migration 0026）与 Android 全量翻页/本机设备令牌均已实现源码并通过本机定向测试；仍未安装、未真机、合并后云端 CI 待跑。现行状态见[统一任务书 §13](AI_PLATFORM_TASKBOOK.md)与 [HANDOVER §10](HANDOVER.md)；本文其余内容保持 2026-11-06/07 历史基线。

## 1. 核对基线

| 项 | 本次核对结果（源码只读） |
| --- | --- |
| 源码 | HEAD `426c6055`，分支 `codex/api-maintenance-20261107`（本次交付提交前状态；工作区含未提交改动） |
| 协议 | [api/openapi.yaml](../api/openapi.yaml) 本次交付前 85 个 `/api/v1` 路径；第六百九十七笔新增设备结果包 7 个路径（登记/列表/详情/领取/段下载/回传/取消）后为 **92** 个 |
| migration | [server/migrations](../server/migrations) 最新为 `0024_device_apply_tasks`，共 24 对 48 个文件；下一个可用编号 **0025** |
| 生成链 | `make sdk` = redocly 校验 → 清生成物 → oapi-codegen（Go 接口层）→ @hey-api/openapi-ts（TS）→ openapi-generator-cli（Kotlin `:sdk`）→ `api/sdk.lock` 三端指纹；生成物不入库、禁止手改（[api/sdk.lock](../api/sdk.lock)、[Makefile](../Makefile)、[ARCHITECTURE](ARCHITECTURE.md)） |
| CI | [.github/workflows](../.github/workflows) 现有 6 个 workflow：`ci.yml`、`codegen.yml`、`docs.yml`、`security.yml`、`build-pc.yml`、`build-apk.yml`；`docs.yml` 运行 tools 自测、`check_migrations.py` 与 `check_docs.py` |
| 现装 | PC 与 Web 796d813f/0.2.1/schema22，arm64 内嵌 796d813f/0.2.1/code35；三代备份、标签明文继续有效；设备结果包只有服务端 API，无 Android Worker/配对，见 [HANDOVER](HANDOVER.md) 第 2、5、8 节 |
| 数据库 | `server/migrations` 建表 24 张（assets/authors/asset_authors/tags/view_events/kv_settings/auth_sessions 等）；0023 起有 `maintenance_jobs/steps/artifacts` 三表，0024 起有 `device_apply_tasks/segments` 两表 |
| 服务端包 | `server/internal` 已有 `maintenance`（任务账本）与 `deviceapply`（设备结果包）包，全仓 Go 源码无 MCP 引用；模块边界由 `.golangci.yml` 的 depguard 两条红线加 AST 回归强制（[ARCHITECTURE](ARCHITECTURE.md) 第 5.1 节） |

核对结论：协议与领域服务已能覆盖「查询 + 单对象写」，缺的是**事务性合并**、**可执行的计划层**、**持久任务账本**、**跨端结果包**与**两代目录**五类结构性能力；这些缺口都不是现有端点的参数问题。

## 2. 可复用能力

### 2.1 作者与关联

| 端点/实现 | 现有语义（可直接封装） | 语义缺口 |
| --- | --- | --- |
| `GET /authors`、`GET /authors/suggest` | 列表与按名子串联想（仅常规作者，含零关联；limit 1–50） | 无「零关联/长期未维护」等清理候选口径，只能客户端过滤 |
| `POST /authors` | 按名新建或复用既有常规身份，非法名 400、与 COS 撞名 409 | 只建作者行，不建关联；不能承载合并 |
| `PUT /authors/{id}` | 改名身份迁移：TXT 块编号行原地重写、followed/created_at/origin 随迁、asset_authors 改挂、上传条目改指；同身份幂等 200 | 撞其他既有作者返回 **409 且不合并**，与「重复作者合并」语义不同 |
| `DELETE /authors/{id}` | 三清：TXT 片段块 + `asset_authors` 关联 + 作者行，媒体保留；COS 400、不存在 404；成功后刷新镜像 | 响应不含影响数量；执行前的影响清点需另行组合 |
| `GET/PUT /authors/{id}/profile` | 独立档案，revision 冲突 409，status=empty/candidate/verified | 无「合并时档案取舍」语义 |
| `GET /authors/{id}/profile/avatar` | 认证读取持久头像，缺图回源 | 不提供写入；头像正文只随 v2 备份迁移 |
| `GET/PUT /authors/{id}/sources`、`GET/PUT /authors/source-vocabulary` | 作者块来源区读写（replace/append）与通用来源词表 | append 是「并入去重」，与合并所需的「并集后回写」需要编排层保证 |
| `PUT /authors/{id}/follow` | 关注布尔 | 合并时关注取并集无端点表达 |
| `PUT /assets/{id}/authors` | 作品作者整体替换（authorIds ∪ newAuthorNames），移除关联会删除作品行 | 单作品粒度；批量改挂需逐个调用，无事务 |
| `POST /authors/import-txt`、`/rebuild`、`DELETE`、`GET …/export` | TXT 片段导入（keep/remove 与 409 冲突载荷）、幂等重建、移除、原文导出 | 导入是整片段粒度；无「按作者行手术」的预览接口 |
| `GET/PUT /authors/mirror` | 总表镜像配置（`path`/`fragmentFilename`/`mode` 取 master 或 fragment），服务端→本地单向原子写 | 只写单个文件，不管理目录代（见 4.4） |

可复用度高：单作者改名/删除/改挂/档案/来源/关注都可直接封装为工具动作。真正缺的是**影响数量前置**与**事务性合并**。

### 2.2 备份与维护目录

| 端点/实现 | 现有语义 | 语义缺口 |
| --- | --- | --- |
| `GET /export/qimeng-backup`、`POST /import/qimeng-backup` | v2 便携备份，同一只读快照导出；导入分段提交、事件身份幂等合并、TXT keep 重建、作者档案保留本端并补缺；请求上限 64MiB | 无「包指纹/段清单/期望回读」结构；导入不接受外部编排的幂等键 |
| `POST/GET /backups`、`PUT /backups/schedule`、`GET /backups/{name}/file`、`DELETE /backups/{name}` | SQLite 热备快照（VACUUM INTO）落 `DataDir/backups`，手动与定时共用防重入闸，含口令哈希（敏感） | 与资料维护目录的文本备份是两套机制，不能互相替代 |
| `server/internal/authorattach/mirror.go` | `writeMasterFile` 在**同一目录**轮换 `作者总表.txt`、`（上次）`、`（上上次）` 三代，含摘要校验与「外部编辑则拒写」 | 三代同目录，无 `当前/`、`上次/` 代目录概念 |
| `web/src/lib/maintenance-folder.ts` | File System Access API 选目录，`writeGeneration` 同名三代轮换；维护根下建 `作者资料/`、`备份/`、`上传归档/` 与 `目录说明.md` | 同上；且 [HANDOVER](HANDOVER.md) 记录内置浏览器目录选择仍返回 AbortError（现场保留） |
| `android/…/backup/MaintenanceFolderRepository.kt` | 同名三代轮换，`AtomicFile` 写入；写入前校验「所有文件访问权限」与规范化路径 | 与旧备份目录的 SAF 树 URI 是**两套权限模型**，需统一口径 |
| `android/…/backup/AutoBackupRunner.kt` | 冷启动判定 24h 自动备份；注释明确「不做 WorkManager 后台」，理由是导出依赖服务端在线 | 这正是 P4 必须解决的前置矛盾 |

### 2.3 鉴权与会话

- 单用户 Bearer session token：migration `0011_auth_sessions` 建会话表，token 只存哈希，多会话并存；登录面为 `/auth/setup|login|logout|verify|dev-login`，**无 scope/角色/设备维度**。
- dev 免密例外只在受信本机/内网成立，风险与适用范围见 [SECURITY](SECURITY.md)「开发模式」。
- 缺口：没有面向外部执行端的「配对/一次性授权」语义，也没有区分「PC 实例凭据」与「手机本机实例凭据」的协议表达——第一版只能靠运维约定（见 4.6、4.5）。

### 2.4 后台与客户端

- `server/internal/syncchannel` 是现成的常驻轮询守护样板：`Start(ctx)` 组合根启一次，ticker 与触发端点同权触发 `RunOnce`，`safego.Go` 托管 goroutine，周期/稳定门槛走 config 具名默认值。PC worker 可直接照此形态。
- Android：`EmbeddedServerService` 为 `specialUse` 前台服务，回环 `127.0.0.1:18430`，不暴露局域网；上传侧已有 `UploadWorker`（`CoroutineWorker` + `setForegroundSafely` 降级「前台失败不失败任务」）与 `EventSyncWorker`。
- Web：维护页为「监控概览 + 目录卡 + 三任务入口」，无任务列表/状态中心；数据获取走 TanStack Query hooks。
- 缺口：无通用任务队列与租约、无 PC 执行器、无手机后台拉取 Worker、无任务中心、Android 12+ 后台启动 FGS 的可行性未验证。

## 3. 缺口表

| 需求 | 现状 | 缺口 | 最小实现 | 涉及路径 | 阶段 |
| --- | --- | --- | --- | --- | --- |
| 规则包与 `project.context` | 规则散在 [AGENTS](../AGENTS.md)、任务书、[tools/AUTHOR_RESEARCH.md](../tools/AUTHOR_RESEARCH.md)、[tools/MEDIA_RESEARCH_IMPORT.md](../tools/MEDIA_RESEARCH_IMPORT.md) 与领域专题 | 曾无机器可读、可离线自包含、带指纹的固定入口 | **已落地（第六百九十二笔）**：`tools/ai_maintenance/` 规则源 `rules.json` + `rulepack.py` 生成器 + 只读 CLI；指纹 = schemaVersion + rulesVersion + OpenAPI 原始字节 SHA-256，端点缺失自动降级 planned | `tools/ai_maintenance/`；只读 [api/openapi.yaml](../api/openapi.yaml) | P1（已落地） |
| 工具适配层/CLI/MCP | 只有一次性脚本（`audit_library.py`、`import_creator_profiles.py`）与三端 SDK | 无统一动作契约、无状态化调用、无 MCP | **CLI 已落地（第六百九十二笔）**：`tools/ai_maintenance/cli.py` 提供 context/health/查询动作与 `--json` 输出；MCP 只做后续 stdio 适配 | `tools/ai_maintenance/`；MCP 待建；[tools/README.md](../tools/README.md) | P1（CLI 已落地）、P3（MCP 适配） |
| 查询与计划工具 | 端点齐全，但每次调用都要重读文档拼参数 | 无有界分页封装、无「当前 revision/受保护字段」聚合返回、无计划结构 | `library.query`/`asset.query`/`author.query`/`author.profile.prepare`/`maintenance.inventory`/`maintenance.plan` 组合现有 GET，输出固定 JSON 与错误码 | `server/internal/httpapi`（仅读）、`server/internal/store`（只读理解） | P1 |
| 作者清理 prepare/apply | 删除/改名/改挂可单点执行，无批量口径 | 无候选清点、无影响数量、无计划指纹与幂等 | `author.cleanup.prepare` 只读组合查询输出候选与影响数量；`apply` 按计划绑定指纹+幂等键逐项调用现有端点并回读 | 工具侧新增；服务端不改 | P2 |
| 重复作者合并 | 改名撞身份 409 且不合并 | 曾缺事务性合并与逐项计数 | **已实现（第六百九十一笔）**：`POST /api/v1/authors/merge`（sourceAuthorId/targetAuthorId）单事务迁移关联、合并 TXT 块、关注并集、档案保留目标/空目标迁入源、上传条目改指并删除源作者；不可撤销，无撤销端点 | [api/openapi.yaml](../api/openapi.yaml)、`server/internal/authoredit`、`server/internal/authorattach`、`server/internal/authoring` | 已完成（原 P2） |
| 持久任务队列与状态机 | 无 job 表、无租约、无状态机；只有进程内守护循环 | 重启/断网/部分成功无法恢复 | **已落地（仅队列与端点，worker 待做）**：`server/internal/maintenance` + migration `0023_maintenance_jobs` 的 jobs/steps/artifacts + `/api/v1/maintenance/jobs` 六个端点；worker 执行器、Web 任务中心与桥接仍待做（**2026-11-08 更新：worker、外部执行器受控通道与 Web 任务中心均已实现源码，未安装**） | `server/migrations`、`server/internal/maintenance`、`server/internal/httpapi`、`api/openapi.yaml` | P3（第一刀已落地） |
| 两代备份分代目录 | 三代同目录（Go/TS/Kotlin 三处同名轮换） | 无 `当前/`、`上次/` 代目录；无迁移与清理授权流程 | 目录改为 `资料维护/{当前,上次}/{作者资料,备份}`；服务端镜像按配置推导上一代路径；旧上上次按授权清理 | `server/internal/authorattach/mirror.go`、`web/src/lib/maintenance-folder.ts`、`android/…/MaintenanceFolderRepository.kt` | P3 |
| 本地权限与凭据 | 单用户 admin Bearer；数据目录可由本机进程读取；维护目录由各端自行授权 | 无「宿主数据只读」的机制化约束；凭据存放无统一约定 | 沿用 admin Bearer + 忽略的本地配置（`QIMENG_*`/`*.local`）；工具只以只读方式打开 SQLite（`mode=ro`、`query_only=ON`）；维护目录限授权根 | [SECURITY](SECURITY.md)、工具侧配置 | P1 起 |
| 无前台手机桥接（结果包/配对/worker） | 手机与 PC 是两个独立实例；无服务端→手机回写通道；手机无后台拉取 Worker | 无结果包协议、无配对流程、无后台应用链路 | PC 端 `device.apply.*` 端点登记/领取/回传/状态；Android 新 Worker 在充电+Wi-Fi 下拉取、先导出两代、再按段应用、回读、回传（**2026-11-08 更新：Worker 源码已交付；配对/设备身份证明、临时包 GC 与真机后台未做**） | `server/internal/httpapi`、`server/internal/store`、`android/core/data` | P4 |
| Web 任务中心 | 维护页只有概览与目录卡 | 无任务列表/详情/取消/逐项报告 | 只读任务列表 + 详情 + 取消，TanStack Query hooks + 组件渲染 | `web/src/pages/MaintenancePage.tsx`、`web/src/hooks/` | P3 |
| 测试夹具 | 云端有 `ui-preview` 合成夹具与各领域单测 | 无规则包/计划/队列/桥接的共享合成夹具 | 固定合成作者/TXT/备份/结果包夹具，供工具、Go、Web、Android 单测共用 | `tools/` 新增夹具目录、`web/tests` | P1 起，逐阶段扩充 |
| NAS 执行器 | 无 NAS 硬件，部署文档与容器基座已有 | 无常驻执行器形态、无目标硬件验证 | 同一 PC 执行器按配置部署到 NAS；补资源预算、断线恢复与目标硬件验收 | [deploy/README.md](../deploy/README.md) | P5 |

## 4. 关键设计默认方案

以下为推荐默认值。用户未另行指定时，P1 起按此推进；任一项变更都先改文档与本表，不以实现反向改写需求。

### 4.1 规则包与工具适配层

**推荐做法**：在 `tools/` 下新增 Python 自包含脚本/CLI（复用现有 Python 工具生态与云端 unittest 门禁），一个入口子命令集实现第 3 节的动作名。规则包由 `rulepack.py` 从 `rules.json`（唯一手写规则源：作者身份、清理候选口径、合并取舍、命名与关联顺序、禁止动作、回读要求）与 OpenAPI 原始字节生成 `project.context` JSON（路径、方法、必填、枚举、长度/字节预算、分页参数、错误码、能力、工作流、权限）；中文指南见 `README.md`。指纹 = schemaVersion + rulesVersion + OpenAPI 原始字节 SHA-256（端点不存在时对应能力自动降级 planned）；生成器版本随发行物记录并在升级说明中标注。

**最小改动**：新增 `tools/ai_maintenance/`（脚本、规则源、测试；第六百九十二笔已落地），不改服务端、不改 OpenAPI、不新增 migration；生成器只读协议文件，产物随发行物一起分发，运行时不读仓库文档。

**被否方案及原因**：
- 把规则包做成服务端端点：P1 会连带改 OpenAPI、生成 SDK 与三端适配，超出「无 schema 的只读阶段」。
- 运行期直接解析仓库 Markdown：违反任务书第 5 节「运行时不依赖仓库文档」的自包含要求，文档一改行为就漂移。
- 用 Go 写第一版 CLI：需要绑定发布与交叉编译流程，与现有 Python 工具链重复；P3 的常驻执行器再考虑 Go。
- 依赖未锁定的 YAML/OpenAPI 第三方库：指纹会随依赖漂移。生成器如需 YAML 解析，按「开发/检查工具」轻量通道锁定版本并记入指纹；否则先经固定版本工具转出 JSON 再解析。

### 4.2 持久任务队列

**状态（2026-11-07）：已落地（仅队列与端点，worker 待做）。** migration `0023_maintenance_jobs` 已新增 `maintenance_jobs`/`maintenance_steps`/`maintenance_artifacts` 三表；`server/internal/maintenance` 已提供状态机、幂等、租约字段与步骤/工件索引；`POST/GET /api/v1/maintenance/jobs`、`GET /api/v1/maintenance/jobs/{id}`、`POST …/{id}/pause|resume|cancel` 已进 OpenAPI 并生成三端 SDK/锁。**worker 执行器、Web 任务中心与 PC→手机桥接仍未实现**；创建的任务不会被自动消费，租约 `Claim/Heartbeat/Complete` 只是包内原语。详细语义见 [维护任务 API](api/maintenance.md) 与 [ADR-0054](adr/0054-持久维护任务队列.md)。

> **2026-11-08 更新（源码 + 本机定向测试，未安装）**：worker 与外部执行器受控通道（`/api/v1/maintenance/executor/*`，fencing `leaseToken` 30–1800 秒，旧 token 409 `LEASE_LOST`）已实现，Web 任务中心已交付；真实外部执行器、多执行器并发与真机/安装仍未验收。

**推荐做法**：新增 Go 包 `server/internal/maintenance`（职责：任务账本、状态机、租约、幂等、步骤与工件索引，不实现业务规则），只依赖 `store`/领域包，不依赖 `httpapi`（守 depguard）。migration `0023` 起新增 `maintenance_jobs`、`maintenance_steps`、`maintenance_artifacts` 三表（含实例标识、输入指纹、规则版本、授权范围、预算、幂等键、租约到期、状态、终态时间）。状态机覆盖任务书第 8 节全部状态：`queued → inventory → research → planned → applying → verifying` 与等待态 `waiting_evidence`/`waiting_authorization`/`waiting_device`，以及 `paused`/`completed`/`partial`/`failed`/`cancelled`。租约：worker 领取写 `lease_until + lease_owner`，过期由下一轮接管；同一步骤只用一条有效租约。幂等键 = 任务 + 输入版本 + 目标实例 + 动作 + 载荷指纹。新端点 `/api/v1/maintenance/jobs`（创建/列表/详情/暂停/恢复/取消），httpapi 只校验与映射，编排在 `maintenance` 包。

**最小改动**：三表 + 一个包 + 一族端点 + PC worker（复用 `syncchannel.Start` 的 ticker + `safego.Go` + `RunOnce` 形态，周期与并发为具名常量/配置）。Web 任务中心放 P3 末尾，先只读。

**被否方案及原因**：
- 复用 `kv_settings` 存任务：无索引、无租约、无历史，重试会覆盖上一次证据，违背「部分成功逐项可追踪」。
- 纯内存队列：重启即丢，直接违反恢复验收（重启、断网、进程被杀）。
- 引入外部消息队列/调度器：新增运维面与未授权依赖，单用户形态无收益。

### 4.3 作者合并

**现状（2026-11-06 已实现）**：`POST /api/v1/authors/merge` 已按本方案落地，请求 `{sourceAuthorId, targetAuthorId}`，OpenAPI、`make sdk` 与 [api/sdk.lock](../api/sdk.lock) 同批更新。服务端在**单事务**内由 `authoredit` 用例编排、`authorattach` 做片段手术与表写入、httpapi 只做校验与错误映射（ADR-0019）；详细语义以 [docs/api/authors.md](api/authors.md) 与 [docs/domain/authors.md](domain/authors.md) 为准。

1. `asset_authors` 源→目标改挂；同一资产双端都关联时保留目标既有行（origin/created_at 原样）、删除源行，其余迁移行保留原溯源（ADR-0032 不伪造）；
2. TXT 片段：源块从全部片段移除，别名/作品行/来源词按「目标现有顺序 + 源未出现者」保序去重并入目标块（目标块原位保留；目标无块时在首个源块所在片段新建目标块；目标多个同名块只并入首个）；
3. 关注标记取并集；
4. 附属档案保留目标非空档案、源档案随源删除并如实标记 `sourceProfileDiscarded`；目标为空档案而源非空时迁入源档案并按既有语义递增 revision；
5. 上传写入条目改指目标并汇入目标块所在片段的条目（保序去重）；删除源作者行，媒体文件与资产本体保留；提交成功后刷新作者镜像。

响应返回逐项计数（迁移关联数、去重数、合并作品行/来源词/别名数、关注/档案/条目标记）；同一 ID/空 ID/COS 作者 400 INVALID_PARAM，任一不存在 404 NOT_FOUND；**不可撤销**，没有撤销端点与源身份快照，重复调用 404。`author.cleanup.prepare/apply` 仍待实现：不新增服务端端点，而是「现有只读端点 + 合并端点」的组合——prepare 只读输出候选、影响数量、计划与指纹，apply 按指纹逐项执行并回读；不得由客户端拼装删除+改名+改挂来模拟事务性合并。

**最小改动**：已按此落地（1 个端点 + 1 段事务编排 + OpenAPI/SDK/锁）；未新增表，未改 v2 备份语义。本地未跑 `go test`（按仓库规定业务测试走云端 CI），已有合成数据测试用例待 CI 执行。

**被否方案及原因**：
- 客户端串行调用 `PUT /authors/{id}` 改名 + `PUT /assets/{id}/authors` 改挂 + 删除：中途失败会留下孤立关联与半迁移身份，验收明确要求事务、409 不静默合并。
- 复用改名端点：撞既有身份返回 409 且按设计不合并，语义相反。
- 先写合并脚本直连数据库：违反「AI 不直写数据库、业务写入只走正式 API」。
### 4.4 两代备份分代目录

**推荐做法**：目录统一为
```text
资料维护/
  目录说明.md
  当前/作者资料/  作者总表.txt(+.sha256)、作者资料.md、作者片段索引.json
  当前/备份/      影库备份.json(+.sha256)
  上次/作者资料/  上一代同名文件
  上次/备份/      上一代完整备份
  上传归档/       原件副本，与代无关，留在维护根
```
轮换语义：内容变化时先把「当前」整代提交为「上次」，再写新的「当前」；相同数据重导出只刷新当前，不挤掉上次；写入用「临时文件+rename」或等价的原子写；每代独立摘要，摘要不符或文件不可读则拒绝覆盖。**导入前保护**写入当前代，导入成功后重新导出合并结果为最新当前份。

服务端镜像：不新增第二个绝对路径写点（`/authors/mirror` 已是「向任意文本文件覆写」的高权限原语）。推荐在 `AuthorMirrorConfig` 增可选 `layout`（`flat3` 缺省 / `generations2`），当 `layout=generations2` 时由既有 `path`（当前代 `…/当前/作者资料/作者总表.txt`）推导上一代路径 `…/上次/作者资料/作者总表.txt`，推导失败或越出授权根即 400。协议描述里写明推导规则，Web/Android 同步改为写两代目录。

迁移步骤与回滚点：

1. 只读核对旧三代文件：逐个校验 `.sha256` 与可读性，记录差异，不删除任何文件。
2. 生成新布局：最新文件复制为 `当前/`，旧「（上次）」复制为 `上次/`；旧「（上上次）」不复制。
3. 切换写入：先服务端镜像（`layout` + 新 `path`），再 Web，再 Android；每端切换后观察一个导出周期。
4. 用户确认后清理旧「（上次）/（上上次）」与同目录旧文件；服务端与客户端都**不自动删除**用户目录文件，只报告可清理清单。
回滚点：第 4 步之前旧文件与旧配置都保留，回滚 = 复原 `layout`/`path` 与客户端版本，数据不丢。

**最小改动**：OpenAPI 增一个可选字段 + 服务端路径推导 + 三端轮换实现改为两代 + 迁移脚本（只读核对与复制，不删除）；不新增 migration（配置在 `kv_settings` JSON 内）。

**被否方案及原因**：
- 再增一个 `previousPath` 绝对路径字段：镜像写点翻倍，任一写点都可覆盖任意文本文件，安全面扩大且不再单点可审。
- 保留三代、只把「当前/上次」当别名：不满足「每代独立文件夹、当前与上次不共用路径」的验收。
- 迁移时自动删除旧文件：违反「不在未授权时删除用户目录中的历史文件」。

### 4.5 手机无前台桥接

> **落地状态（2026-11-07 第六百九十七笔）**：服务端 API 已落地——migration `0024_device_apply_tasks` 两表、`server/internal/deviceapply`（manifest 校验、段文件落盘+SHA-256 复算、状态机、惰性过期、领取/回传/取消幂等）、7 个 `/api/v1/device/apply-tasks` 端点、OpenAPI/SDK/锁与 `docs/api/device.md`/ADR-0055。Android Worker、配对/设备身份与真机后台同步**未实现**，本轮未安装；下文 Worker/配对仍为目标方案，不能写成已具备。
>
> **2026-11-08 更新（源码 + 本机定向测试，未安装）**：服务端增 manifest 可选 `expectedState` 与回传时权威计数/段摘要核对（migration 0026 `verification_json`，不参与幂等重放比较）；Android Worker 源码已交付（WorkManager、专用设备令牌、SAF/所有文件访问门禁），领取/待办改为 nextCursor 全量翻页，本机模式可自助签发设备令牌。设备配对/身份证明、临时包 GC、真机后台与应用恢复仍未验收。

**推荐做法**：PC 端新增设备结果包端点族（P4，OpenAPI 先行）：`POST /api/v1/device/apply-tasks`（登记包，绑定 `targetDeviceId`）、`GET /api/v1/device/apply-tasks?targetDevice=&status=`（手机领取）、`POST /api/v1/device/apply-tasks/{id}/result`（回传）、`GET /api/v1/device/apply-tasks/{id}`（状态）。包内固定包含：协议/规则指纹、目标实例、段清单（每段 kind 取 `masterTxt` 或 `v2Backup`、字节数、SHA-256、期望回读指标）。正文按段落 `<dataDir>/device-apply-packages/<taskId>/`，数据库只存清单、摘要与状态；主表 TXT 受现有 `httpapi.maxJSONBody`（1MiB）约束，v2 备份受 `portablebackup.ImportMaxBody`（64MiB）约束，超限在登记时拒绝。

手机 Worker：新建 `CoroutineWorker`，约束充电 + 未计费 Wi-Fi，WorkManager 周期不小于 15 分钟，另提供 App 前台「立即应用」手动触发。执行顺序固定：领取 → 校验指纹/版本/段摘要（不匹配进 `waiting_authorization`，不做「尽量应用」）→ **先**用本机服务导出两代备份与摘要（失败即中止，不写任何段）→ 主表 TXT 走本机 `/authors/import-txt`，需要完整资料时走本机 `/import/qimeng-backup` → 回读（作者数、关联数、标签数、事件数、文件摘要）→ 回传结果与本地摘要 → 清理临时包，保留两代备份与结果。幂等键绑定任务、包指纹与段标识；已应用段记录在本地，重复领取只补未完成段。

配对：第一版复用现有 `POST /auth/login` 在手机上完成一次性登录，token 只存 DataStore，仅用于主动拉取 PC；PC 地址用一次性短码/二维码传递（不含 token）。手机本机模式的 token 属于手机自己的内嵌实例，与 PC token 互不复用。不新增免口令配对端点；若将来确需免口令绑定，另立协议并先改 OpenAPI。手机不新增监听面，只主动出站。

风险与验收方式（必须实测）：

- Android 12+ 后台启动前台服务受限：`EmbeddedServerService` 是 `specialUse` 前台服务，Worker 在后台拉起它可能被系统拒绝。缓解：用户手动触发路径始终可用；失败归 `waiting_device` 并保留通知；是否可在充电时后台拉起，以 `qimeng_api35` 与用户真机的实测结论为准，不能按设计推定。
- Doze/厂商省电：验收需覆盖充电+Wi-Fi 下数小时后的定时执行、进程被杀后恢复、授权撤回。
- 内嵌服务未起或端口被占：明确失败并保留可恢复状态。
- 导出预算：两代备份 + 临时包需在手机上核对存储余量，超限中止而不是部分应用。

**最小改动**：一族端点 + 3 表（或复用 4.2 的 jobs 表加 `device_packages`）+ 1 个 Android Worker + 1 个配对入口；v2/TXT 导入全部复用现有本机端点。

**被否方案及原因**：
- PC 直连手机内嵌服务（反连）或 ADB 推送：手机回环地址对 PC 不可达，ADB 又违反「不需要 USB」，且新增监听面。
- 手机端实现完整业务编排：手机不承担工具适配层，且会与 PC 实例产生双真相。
- 把 v2 备份整份塞进 SQLite BLOB：64MiB 包会撑大业务库，连带影响热备与迁移。
- 用「增量 diff 应用」替代分段包：缺少可校验摘要与回读指标，无法满足导入前导出与可恢复验收。

### 4.6 本地权限与凭据

**推荐做法**：第一版沿用单用户 admin Bearer，凭据只放忽略的本地配置（`QIMENG_*` 环境变量或 `*.local`），运行时不写日志、不入任务记录、不入结果包、不下发给模型。数据目录只读靠两层：OS 权限 + 工具纪律（只以 `mode=ro`、`query_only=ON`、deferred 读事务打开，参照 [tools/audit_library.py](../tools/audit_library.py)），并明确「只读副本不是业务真相」。维护目录写入限于用户授权根，写前校验规范化路径仍在根内。业务写入一律走正式 API。8420 真库禁止测试写入。

**最小改动**：不新增服务端 scope/角色；不新增凭据类型；只在工具侧固化「只读打开 + 根内校验 + 环境变量取 token」三条约束并写进规则包。

> **2026-11-08 更新**：该「不新增 scope」的默认方案已被 ADR-0057 取代并落地源码——AI 客户端独立凭据（migration 0025）、8 个写 scope、未映射写默认 deny、HTTP 管理/审计查询（仅管理员会话）与按客户端限流（600/分、突发 120）。动作级确认 token 与多实例共享限流仍未实现，源码未安装。

**被否方案及原因**：
- 现在就在服务端加 scope/角色/设备级授权：单用户形态没有消费方，会在 P1 引入协议与 migration，而任务书把细粒度凭据列为「有缺口再设计」。
- 为工具签发长期独立 token：多一个凭据面，且违反「沿用现有鉴权」。
- 让工具直接读写 SQLite 做计划或回读：绕过领域服务与鉴权，等于第二真相。

## 5. 最小阶段方案

| 阶段 | 交付物 | 验收 | 门禁 |
| --- | --- | --- | --- |
| P1 规则包 + 查询工具 | **已落地（第六百九十二笔）**：`tools/ai_maintenance/` 规则源与生成器、只读 CLI、README 指南；AI 客户端接入模板、合成夹具与 MCP 适配待做 | 新 AI 只靠规则包与示例完成：查作者、生成合法档案计划、读懂冲突码；指纹变化时重新取上下文（CLI 22 项 unittest 已覆盖规则包/CLI 合同） | 只读、无 OpenAPI/migration 变更；`check_docs.py`、`check_privacy.py`；合成数据 |
| P2 合并端点 + 清理工具 | **合并端点已落地（第六百九十一笔）**：`POST /authors/merge`、OpenAPI 与 SDK/锁、事务编排；`author.cleanup.prepare/apply` 待做 | 合并先出影响数量、执行后回读一致、409 不静默合并、事务回滚无残留；清理计划绑定指纹与幂等键（待验收） | OpenAPI 先行 → `make sdk` → 同批 [api/sdk.lock](../api/sdk.lock)；migration 只增（本阶段应为零）；隔离实例合成数据 |
| P3 队列 + PC worker + 两代布局 + Web 任务中心 | **第一刀已落地（仅队列与端点，worker 待做）**：`server/internal/maintenance`、migration `0023_maintenance_jobs`、`/maintenance/jobs` 六端点；PC worker、两代目录（服务端/Web/Android）、Web 任务列表与详情仍待做（**2026-11-08 更新：worker、外部执行器通道、两代布局与 Web 任务中心均已实现源码，未安装**） | 账本已覆盖创建/幂等/列表/详情/暂停/恢复/取消/终态不可变与 404/409 映射；重启/断网/取消/部分成功的 worker 执行恢复、两代轮换与 Web 场景待 worker/客户端落地后验收 | 已跑 `check_migrations.py`、OpenAPI → SDK → 锁、`go build ./...`、`gofmt`、`go vet`；depguard 与 `go test ./...`（云端）、Web 合成场景待后续 |
| P4 Android 无前台桥接 | **服务端第一刀已落地（第六百九十七笔）**：`/api/v1/device/apply-tasks` 七端点、migration 0024 两表、结果包段文件存储与 SHA-256 校验；Android Worker、配对/设备身份与真机后台同步待做（**2026-11-08 更新：Android Worker 源码、设备权威回读/0026、全量翻页与本机设备令牌已交付；配对/真机未做**） | 服务端已覆盖登记/领取/段下载/回传/取消、404/409/413 与幂等；「不打开 App 前台、不接 USB 完成拉取与应用」仍需 Android Worker + `qimeng_api35`/用户真机验收，当前不成立 | 已跑 `check_migrations.py`、OpenAPI → SDK → 锁（355 项）、`go build ./...`、`gofmt`、`go vet`；Android JVM/真机验收未跑 |
| P5 NAS 执行器 | 执行器部署说明、资源预算、断线/重启/掉电恢复、目标硬件记录 | 目标 NAS 上完整链路与预算实测通过 | 部署与恢复文档同步；设备/NAS 验收记录独立于云端自动化 |

全部阶段的共同门禁：`python -X utf8 tools/check_docs.py`（文档与链接）、`check_privacy.py`（新增敏感串）、结构变更时 `check_migrations.py`（历史迁移只加不改）；任何协议改动必须 OpenAPI 先行 → `make sdk` → 同批 [api/sdk.lock](../api/sdk.lock)；所有验证只用隔离实例、临时数据与合成媒体，不向 8420 写入测试数据。

阶段推进纪律：每阶段先更新 [CAPABILITY_MAP](CAPABILITY_MAP.md) 与 [HANDOVER](HANDOVER.md) 的「实际状态」，再改代码；完成项必须有交付证据，受限或未跑的检查如实标注。

## 6. 测试矩阵

| 范围 | 检查 | 环境与夹具 | 通过条件 |
| --- | --- | --- | --- |
| 规则包生成与指纹 | 两次生成同输入产物逐字节一致；改协议或改规则后指纹变化；字段/枚举/预算覆盖数与 OpenAPI 抽查一致 | 本机只读 + 云端 unittest；合成 OpenAPI 片段 | 指纹稳定、缺字段即失败、离线运行不读仓库文档 |
| 作者合并 TXT 手术与事务回滚 | 作品行/来源/别名合并、源块删除、关联去重、关注并集、档案补缺；中途失败回滚 | 隔离实例 + 合成作者/TXT/资产；不用真实库 | 无孤立关联、无重复身份、计数与回读一致、409 不静默合并 |
| 队列幂等/租约/状态 | 重复提交同幂等键只执行一次；租约过期被接管；pause/resume/cancel 终态正确；重启后从 `steps` 续跑 | 合成 SQLite + 单 worker/双 worker | 无重复写入、终态可判定、partial 逐项可追踪 |
| 两代布局轮换与摘要 | 当前→上次→新当前；相同数据重复导出不轮换；摘要不符拒写；旧文件清理只出清单 | Go/TS/Kotlin 三端同语义用例 + 临时目录 | 每代独立目录与摘要；失败不留半代；不自动删用户文件 |
| 桥接包校验/重复应用/离线恢复 | 服务端已覆盖：错 targetDevice/protocolVersion/规则指纹/段摘要/字节数在写库前拒绝，重复领取与相同结果回传幂等，终态/过期不可下载；手机端「只补未完成段」「离线从最近已确认段继续」待 Android Worker | 服务端临时 SQLite + 合成包；手机端 Android JVM 单测 | 服务端拒绝先于写库、404/409 可判定；Worker/真机侧条件未验收 |
| Android 真机验收 | 充电+Wi-Fi 下后台领取→导出→应用→回读→回传；Doze、进程被杀、授权撤回 | 显式 `qimeng_api35` AVD 与用户指定真机 | 不打开 App 前台可完成；恢复后不重复应用；失败可重试 |
| Web 浏览器合成场景 | 任务列表/详情/取消；两代目录卡片与写回提示 | `ui-preview` 合成夹具 + 浏览器回归 | 无运行时 SDK 直连、无业务规则进组件；状态与错误可见 |
| 文档门禁 | `python -X utf8 tools/check_docs.py`；链接与章节可达；新增敏感串扫描 | 仓库根 | 0 项错误；新增文档不含真实用户名/绝对本机路径/密钥 |

**禁止**：不向 8420 真库写任何测试数据；不重用其数据目录；Android 不操作默认或雷电模拟器；桥接测试只用合成包与临时目录。

## 7. 风险与待确认

| 议题 | 影响 | 默认建议 | 需用户确认的一句话 |
| --- | --- | --- | --- |
| 合并语义（档案与关注取舍） | 合并后人工资料可能被覆盖或丢失 | 已实现：档案保留目标、源档案仅目标为空时迁入并如实标记丢弃；关注取并集；不合并 status/证据/X 绑定；不可撤销、无撤销端点 | 「已按此语义实现（第六百九十一笔）；若要字段级合并或 409 拒绝策略需另开协议」 |
| 目录命名与旧文件清理授权 | 迁移可能删掉用户历史备份 | **已确认（2026-11-06 用户）**：当前/上次各自独立文件夹；旧上上次先只读核对摘要与可读性再删除（用户已授权删除），不自动删未核对文件 | 「已确认：当前与上次分目录、上上次核对后可删」 |
| Android 后台可靠性 | 系统限制可能让「无需前台」不成立 | **已确认方向（2026-11-06 用户接受 WorkManager 周期拉取）**：充电+Wi-Fi、周期≥15 分钟、手机主动拉取；真机后台拉起失败时以「充电时通知一键应用」兜底 | 「方向已确认；真机后台能否拉起内嵌服务仍需 qimeng_api35 与用户真机实测，兜底按设计执行」 |
| 本地执行端打包语言 | Python 与 Go 两套工具链的维护成本 | P1–P2 用 Python CLI（规则包/工具已落地于 tools/ai_maintenance）；P3 常驻 worker 用 Go（与 syncchannel 同构）；未收到用户反对，按默认推进 | 「第一版工具用 Python、常驻 worker 用 Go，作为默认；有异议再改」 |
| 规则包治理与版本升级 | 规则漂移会让旧计划失效 | **已确认（ADR-0052 第 6 条）**：规则包随发行物自包含交付、运行时不依赖仓库文档；指纹=OpenAPI SHA-256+规则版本+生成器版本，不符即重取上下文并复校验计划 | 「已确认自包含交付；指纹细则按默认执行」 |

## 8. 下一步

1. 用户对第 4 节 4.1–4.6 与第 7 节五个确认点给出结论；有调整的项就地改写本节默认方案并标注日期。
2. 确认后进入 P1：先落 `project.context` 与只读查询工具、规则包生成器与中文指南，不碰服务端与协议。
3. P1 交付时同批更新 [CAPABILITY_MAP](CAPABILITY_MAP.md) 对应能力域与 [CHANGELOG](CHANGELOG.md)，并在 [HANDOVER](HANDOVER.md) 记录实际安装/验证边界。
4. 本文不改代码、协议、migration、SDK；后续任何协议改动仍遵守「OpenAPI 先行 → `make sdk` → 同批 `api/sdk.lock`」，任何结构改动仍只新增 migration。
