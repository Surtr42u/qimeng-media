# AI 接入平台 R0 定向审计

> 审计对象：外部 AI 接入平台的现有脚本、服务端能力、接口依赖与规则包漂移。
> 审计依据：[外部 AI 接入平台统一任务书 V2](AI_PLATFORM_TASKBOOK.md) §3、[ADR-0056 独立 AI 接入平台架构](adr/0056-独立AI接入平台架构.md)。
> 审计时点：2026-11-07（Asia/Shanghai）；基线分支 `codex/api-worker-20261107`，基线 HEAD `f26dcebd`。
> 只读边界：本次审计是只读工作，唯一产物是本文档。未修改代码、协议、数据库或迁移，未启动或连接任何服务实例，未访问真实库 `8420` 及其数据/媒体，未运行构建、业务测试或 Gradle。本文所有"测试存在"只表示代码库中存在对应自动化测试，不代表本轮已执行。
> 证据口径：所有结论以仓库内文件路径与行号为证据；行号以审计时点读取到的内容为准。部分并发队友正在修改 `tools/ai_maintenance/`、`server/internal/maintenance/`、`docs/api/authors.md`，行号可能随其实施漂移，引用时以文件当前内容复核。

## 0. 结论摘要

先看这一节。按任务书 §3.3 的要求，本审计的落点是一份"脚本清单 + 复用/退役映射 + 有证据的最小变更计划"，直接支撑 R1–R3（统一工具核心、规则版本与兼容、统一查询/计划/受控写入）的拆分决策。

| 编号 | 级别 | 结论 | 关键证据 | 去向 |
|---|---|---|---|---|
| A1 | P1 | 作者资料**写入算法只有一份，但不在统一工具核心内**：唯一实际写入者是 `tools/import_creator_profiles.py`，而 `rules.json` 与 CLI 都把 `author.profile.update` 标为 planned。R1/R3 必须把该算法的校验、人工字段保护、plan/apply、409 处理迁入 `tools/ai_maintenance/`，否则"唯一权威实现"名义上成立、架构上不成立。 | 写入逻辑 `tools/import_creator_profiles.py:158`；无写命令 `tools/ai_maintenance/cli.py:1`；planned 声明 `tools/ai_maintenance/rules.json:162` | R1/R3（task-2 F 项） |
| A2 | P1 | **请求客户端与 URL/令牌校验重复两份**，且环境变量名不一致：CLI 用 `QIMENG_API_BASE`，填充脚本用 `QIMENG_BASE_URL`；禁重定向、公网 URL、超时、错误分类各写一遍。应合并为一个可测试 client 模块。 | `tools/ai_maintenance/cli.py:91`、`:135`、`:184`；`tools/import_creator_profiles.py:104`、`:123`、`:196` | R1（task-2 B/C 项） |
| A3 | P1 | 规则包的**能力核对只查路径是否存在**，不核对方法与必需字段；`merge` 是唯一按方法解析的特例。任务书 §5.2 明确禁止"只根据 OpenAPI 中存在某个路径就判定可用"。 | `tools/ai_maintenance/rulepack.py:133`（只做 `path in path_set`）、`:149`（merge 特判）、`api/openapi.yaml` 路径/方法 | R2 |
| A4 | P2 | 规则包**漏声明已存在的服务端能力**：`/api/v1/maintenance/jobs*`（5 个路径、7 个方法）与 `/api/v1/device/apply-tasks*`（6 个路径、8 个方法）在 `rules.json` 中完全没有能力条目，`device.apply.*` 仍声明 `endpoint: null` 且 planned。 | `api/openapi.yaml:1553`、`:1656`；`tools/ai_maintenance/rules.json:197`；`server/internal/deviceapply/doc.go:1` | R2 + R5/R6 |
| A5 | P2 | "planned"被同时当作**实现状态**与**API 可用性**使用：作者删除/改名/改挂/合并、档案写入、v2 备份导入导出等端点都已存在，但统一以 planned 呈现，只有 `authorActions` 用 `apiStatus` 区分。R2 的注册表必须拆成"端点能力核对结果 + 工具实现状态"两个维度。 | `rules.json:315` 起 `apiStatus` 字段；`api/openapi.yaml:787`、`:828`、`:866`、`:1348`、`:1371` | R2 |
| A6 | P2 | 存在一批**只由文档/工具层约定、服务端不校验**的规则（导入前必须先导出、计划影响预览、清理阈值、设备端先备份等）。它们不应被误读为服务端已强制；R3 必须在工具层强制并如实标注"客户端强制"。 | `rules.json:404`、`:419`、`:487`、`:523`；`api/openapi.yaml:1348`（导入端点无前置导出校验） | R3 |
| A7 | P3 | **文档指针过时**：`rules.json` 的 source 与 `tools/ai_maintenance/README.md` 仍把历史任务书当作权威执行清单；`tools/MEDIA_RESEARCH_IMPORT.md:57` 的三代备份陈述将与 R7 两代布局冲突。需在对应批次同批更新并 bump 规则版本。 | `rules.json:4`、`:423`；`tools/ai_maintenance/README.md:5`、`:230`；`tools/MEDIA_RESEARCH_IMPORT.md:57` | R2/R7 同批 |
| A8 | P3 | 研究链**不是重复实现**，但含一个本地历史探针（`audit/verify/probe.local`，未入库）与 Go 研究网关 provider 矩阵功能重叠。应标记为历史/退役，禁止迁入平台。 | `tools/AUTHOR_RESEARCH.md:107`；`server/internal/research/doc.go:1`；本地探针仅确认存在、未读取内容 | R2/R3 文档边界 |
| A9 | P3 | `tools/research_sidecar/sidecar.mjs` 在仓库内**无自动化测试**（仅 README 手工实测记录）；它作为可选研究工具可以接受，但不得纳入"AI 通用工具"可用性承诺。`tools/ui-preview` 是 Web 合成夹具，**不是 AI 工具**。 | `tools/research_sidecar/README.md:393`；`tools/ui-preview/README.md:1`；CI 引用 `.github/workflows/ci.yml:152` | 分类边界 |

未确认任何 P0 级数据安全缺陷：本次审计没有写入路径，也没有发现"真实库/真实数据会被 AI 工具直接写入"的现状实现；AI 侧唯一写入路径是正式 `PUT /api/v1/authors/{authorId}/profile`（`tools/import_creator_profiles.py:183`）。

## 1. 审计范围、方法与状态口径

### 1.1 范围与方法

- 覆盖任务书 §3.1 点名的全部对象，外加 `tools/check_*.py`、`tools/ui-preview` 与相关 Go 包，用于判断归属与重复。
- 方法：逐个读源码与文档；只读提取 `api/openapi.yaml` 的 95 个 path；用一次性只读脚本把 `rules.json` 声明的 27 个端点逐条与 OpenAPI 的路径+方法比对（脚本不入库，本文记录结果）。
- 不读：`audit/` 等被忽略本地目录的内容（只确认文件存在性）、真实数据库、媒体文件、真实设备、远端 HEAD。
- 生成的规则包、产物与本地报告不写入仓库；本文不记录任何凭据、令牌、真实作者名、设备标识或含用户名的本机绝对路径。

### 1.2 任务书 §3.3 检查项对应

| §3.3 检查项 | 本文位置 | 结论摘要 |
|---|---|---|
| 脚本清单与复用/退役映射表 | §2、§6 | 覆盖任务书 §3.1 点名的全部对象与周边工具，均给出去向 |
| 相同业务是否被多个脚本重复实现 | §5 | 3 处真实重复（请求客户端、空档案判定、公网 URL 校验），其余是职责不同 |
| CLI 与独立填充脚本能否复用请求客户端和校验代码 | §5 D1–D3 | 能且应该复用；当前两份实现已出现环境变量名不一致 |
| 研究接口与旧研究脚本是否存在不必要重叠 | §2.3、§5 D6 | Go 网关是唯一服务端出口；本地探针重叠，建议退役 |
| 是否有需要删除的过时规则 | §4.4 | 8 处需改写；未发现整条删除的错误规则 |
| 是否有 Go 端点存在、规则包仍错误标记未实现 | §4.3 F2/F3 | 有：maintenance/device 端点未声明；backup.export 端点存在但工具 planned |
| 是否有规则只存在于文档、未被正式服务端验证 | §4.3 F7 | 有 7 条，需工具层强制并标注边界（另列 1 条服务端已强制的反例） |

### 1.3 状态口径

本审计严格区分：**源码存在 / 有自动化测试 / CI 通过 / 已构建 / 已安装 / 真实功能验收**。本文只对"源码存在"与"仓库内存在对应测试文件"两类给结论，不推断 CI、构建、安装或验收状态。`rules.json` 的 planned/supported 也只描述本切片的工具实现状态，不等于服务端能力缺失。

## 2. 脚本与工具清点

表格列说明：**调用者**指仓库内实际调用/消费该条目的入口；**直连联网**指该条目自身发起外部网络请求；**正式 API**指是否通过 `/api/v1` 业务接口；**真实数据**指是否接触真实数据库/媒体文件；**副作用**指默认行为下的写入或状态改变。

### 2.1 AI 工具链：`tools/ai_maintenance/`

| 条目 | 用途与调用者 | 输入 / 输出 / 依赖 | 证据 |
|---|---|---|---|
| `rules.json` | 唯一手写领域规则源；被 `rulepack.py` 读取，生成规则包后供 CLI、外部 AI 与文档使用 | 输入：人工维护的 JSON；输出：被组装进规则包；依赖：无（运行时自包含） | `tools/ai_maintenance/rules.json:1` |
| `rulepack.py` | 读取规则源与 OpenAPI，生成稳定规则包（路径清单、OpenAPI 原始字节 SHA-256、能力/字段/工作流/权限） | 输入：`rules.json` + `api/openapi.yaml` 原始字节；输出：JSON 规则包 / 中文指南；依赖：仅标准库 | `tools/ai_maintenance/rulepack.py:1`、`:217` |
| `cli.py` | 只读查询与规则上下文 CLI（context/health/libraries/assets/authors/author-profile/author-sources/research） | 输入：子命令参数 + `QIMENG_API_BASE`/`QIMENG_API_TOKEN` 环境变量；输出：stdout JSON 或研究摘要，stderr 固定错误类别；依赖：`urllib`、`argparse` | `tools/ai_maintenance/cli.py:1`、`:249` |
| `__init__.py` | 包标记 | — | `tools/ai_maintenance/__init__.py:1` |
| `README.md` | 用法、配置、工具映射、planned 边界与测试说明 | 文档 | `tools/ai_maintenance/README.md:1` |
| `test_ai_maintenance.py`（仓库 `tools/` 下） | 31 个 unittest：规则包形状/指纹稳定/端点降级、CLI 配置与错误分类、token 脱敏、重定向拒绝、研究命令 | 临时目录 + 回环 HTTP stub | `tools/test_ai_maintenance.py:1`（测试名见 `:84`–`:695`） |

| 条目 | 直连联网 | 正式 API | 真实数据 | 写入副作用 | 自动化测试 | 重复实现 | 建议 |
|---|---|---|---|---|---|---|---|
| `rules.json` | 否 | 否 | 否 | 无（只读规则） | 经 `test_ai_maintenance.py` 间接覆盖 | 与 OpenAPI/文档存在"三处规则"风险（§5 D8） | **保留并升级**为唯一规则源（R2） |
| `rulepack.py` | 否 | 否 | 只读 `api/openapi.yaml` | 无；`--out` 时写用户指定文件 | `test_ai_maintenance.py:84`–`:205` | 另有两处 OpenAPI 行解析（`:106` 方法解析、`cli.py` 无），应合并 | **封装**：解析改为分块可测试解析（R2） |
| `cli.py` | 是（受控 API 调用） | 是，`/api/v1` 只读端点 | 否（本地文件仅 Lens 读图，且有扩展名/大小预检 `:375`） | 无写入命令；只读 | `test_ai_maintenance.py:230`–`:695` | HTTP 客户端/URL 校验与 `import_creator_profiles.py` 重复（§5 D1） | **拆分并复用**：client/registry/commands（R1–R3） |
| `README.md` | 否 | 否 | 否 | 无 | 不适用 | 权威指针指向历史任务书（§4.4） | 随 R2 更新 |
| `test_ai_maintenance.py` | 否 | 否 | 合成数据 | 无 | 自身 | — | 保留；R1 后按模块拆分 |

### 2.2 作者资料写入脚本

| 条目 | 用途与调用者 | 输入 / 输出 / 依赖 | 证据 |
|---|---|---|---|
| `tools/import_creator_profiles.py` | 受控填充作者公开档案：默认离线 dry-run，`--apply` 后经正式 API 逐条写入。调用者：主代理/本地执行器（非服务端自动调用） | 输入：忽略目录中的研究报告 JSON + 可选 reviewed IDs JSON；输出：计数/固定错误类别 JSON；依赖：标准库；令牌只从 `QIMENG_API_TOKEN` 读 | `tools/import_creator_profiles.py:1`、`:55`、`:158`、`:193` |
| `tools/test_import_creator_profiles.py` | 13 个 unittest：错库预检零写入、COS 隔离、人工字段保护、审核状态、dry-run 无网络、重定向/错误脱敏 | 合成报告 + API 替身 | 测试名 `:33`–`:137` |

| 边界项 | 结论 |
|---|---|
| 直连联网 | 是，仅目标 `--base-url`（http 强制本机/私有地址 `tools/import_creator_profiles.py:104`） |
| 正式 API | 是：`GET /api/v1/authors`、`GET/PUT /api/v1/authors/{id}/profile`（`:159`、`:174`、`:176`、`:183`） |
| 真实数据 | 读研究报告（公开研究结果，含作者名/链接，属用户数据）；不读媒体/数据库/凭据文件 |
| 写入副作用 | 有：逐条 PUT 作者档案；预检失败整轮零写入（`:167`）；已有非空档案跳过（`:179`）；409 单独计数（`:189`） |
| 自动化测试 | 有，13 项；随 `.github/workflows/docs.yml:28` 的 tools 全量 unittest 在云端运行 |
| 重复实现 | 请求客户端/URL 校验/错误分类与 `cli.py` 重复；空档案判定与 Go `creatorprofile.IsEmpty` 重复（§5 D1–D3） |
| 建议 | **封装后迁移**：算法主体迁入 `tools/ai_maintenance` 作者工具；脚本保留过渡兼容入口并转发；迁移完成后再评估退役（任务书 §6.5） |

### 2.3 研究链（可选研究工具）

| 条目 | 用途与调用者 | I/O 与依赖 | 证据 |
|---|---|---|---|
| `tools/research_sidecar/sidecar.mjs` | 本机 Google Lens / Google 文本搜索 sidecar：loopback HTTP + headless Edge/Chromium，供 Go 研究网关代理调用 | 输入：token 环境变量、multipart 图片或允许根内 JSON 路径、搜索 query；输出：Lens/搜索结果 JSON；依赖：Node 24 标准库 + 本地浏览器；无 npm 依赖 | `tools/research_sidecar/README.md:1`、`:300`、`:428` |
| `tools/research_sidecar/{start.cmd,start.sh}` | 启动包装：只检查 token 是否已注入，不读取/打印/写 token | 环境变量 | README 同上 |
| `server/internal/research/` | Go 研究网关 + Lens 代理：固定 provider 矩阵、安全 HTTP 出口、预算、证据落盘 | 输入：研究 query / 图片字节；输出：`/api/v1/research/*` 响应；依赖：标准库 + `internal/store` 等，受 depguard 约束 | `server/internal/research/doc.go:1`；端点 `api/openapi.yaml:1464`、`:1478`、`:1510` |
| `tools/AUTHOR_RESEARCH.md` | 作者身份研究方法/证据等级/交接规范文档（非可执行工具） | 引用 `import_creator_profiles.py`、创作研究目录约定 | `tools/AUTHOR_RESEARCH.md:1` |
| `tools/MEDIA_RESEARCH_IMPORT.md` | 媒体复核与双端整理流程文档（非可执行工具） | 引用 OpenAPI/领域文档 | `tools/MEDIA_RESEARCH_IMPORT.md:1` |
| 本地历史探针 `audit/verify/probe.local` | 旧研究脚本，仅存在于忽略的本地目录，未入库；功能与 Go provider 网关重叠 | —（未读取内容） | `tools/AUTHOR_RESEARCH.md:107` |

| 边界项 | research_sidecar | Go research 包 | 研究文档 |
|---|---|---|---|
| 直连联网 | 是（经本机浏览器访问 Google） | 是（受白名单/公网校验/限额约束，默认关闭） | 否 |
| 正式 API | 否（自己的 loopback 协议） | 是（`/api/v1/research/*`） | 否 |
| 真实数据 | 只读本地图片（≤20MiB） | 只读图片字节，证据落盘到 DataDir | 否 |
| 写入副作用 | 临时文件（请求结束删除）；浏览器 profile 属敏感本地状态 | 证据文件落盘（失败只告警，不影响响应） | 无 |
| 自动化测试 | **无**（仅 README §10 手工实测记录） | 有：`server/internal/research/*_test.go`（含 sidecar_test） | 不适用 |
| 重复实现 | 与 Go 网关是"依赖关系"不是重复；与本地历史探针重叠 | provider 矩阵是唯一服务端出口 | 只描述方法，不重复实现 |
| 建议 | **隔离保留**（可选研究，独立进程，不迁入 Go） | **保留**为可选研究网关（默认关闭） | 保留；标记历史探针退役 |

### 2.4 管理员专用工具与开发门禁

| 条目 | 用途与调用者 | I/O 与依赖 | 证据 |
|---|---|---|---|
| `tools/audit_library.py` | 历史数据只读对账：SQLite `quick_check`/外键、资产路径/大小、事件身份与日统计一致性、上传暂存计数 | `--db`、可选 `--backup`；`mode=ro`+deferred 读事务；输出计数/问题类别 JSON，不含路径/名称 | `tools/audit_library.py:1`、`:51`、`:178` |
| `tools/audit_backup.py` | 便携 v2 备份与当前库的只读逐段对比（被 `audit_library` 导入） | 输入：v2 envelope + 库快照；输出：分段计数；仅标准库 | `tools/audit_backup.py:1`、`:24`；调用 `tools/audit_library.py:16`、`:199` |
| `tools/cold_backup.py` | 停写目录副本 + 逐文件 SHA-256 清单核对/恢复；不操作运行实例 | create/verify/restore；必须 `--offline`；目标必须不存在 | `tools/cold_backup.py:1`、`:59`、`:97`、`:122` |
| `tools/release_manifest.py` | 核对本机服务端产物身份并写交付清单；不停止服务、不安装、不接触数据库 | 输入：可执行产物/Web/APK 目录；输出：JSON 清单（默认拒绝 dirty 正式交付） | `tools/release_manifest.py:1`、`:32` |
| `tools/check_docs.py` | 仓库文档门禁：内联链接/章节、ADR 索引、AGENTS 路由、CHANGELOG 上限、Actions 固定 SHA | 输入：仓库文件 + Git 状态；输出：ERROR 文本与计数 | `tools/check_docs.py:1`、`:140` |
| `tools/check_privacy.py` | 隐私/密钥门禁：私人文件路径、个人邮箱、设备标识、库汇总、Git 元数据；密钥复用 Gitleaks 8.30.1 | 输入：index/工作区/历史 blob；输出：类别计数（不回显命中内容） | `tools/check_privacy.py:1`、`:17`、`:184` |
| `tools/check_migrations.py` | 历史 migration 不可修改/删除/改名检查（工作区 + base） | 输入：Git 基线；输出：错误列表 | `tools/check_migrations.py:9` |
| `tools/check_glib_patch.py` | 桌面 glib 上游快照 + Cargo 本地补丁来源校验 | 输入：`desktop/vendor/glib`、Cargo 配置；输出：错误列表 | `tools/check_glib_patch.py:11` |
| `tools/discover_lan.py` | 无凭据局域网发现调试（与 Go/Android 共用发现线协议） | UDP 广播/单播；输出地址 JSON；不登录、不请求业务库 | `tools/discover_lan.py:1`、`:22` |
| `tools/ui-preview/` | Web 合成夹具 + 隔离预览服务器（144 条虚拟条目）；Web e2e 复用 `api.mjs` | 必须显式提供已构建 Web 目录；内存 API；只监听 loopback | `tools/ui-preview/README.md:1`、`:5` |
| 其余门禁（`test_windows_launcher.ps1`、`test_release_gate.py` 等） | CI 文本契约/启动器回归 | 仅云端 runner | `tools/README.md:80`、`:101` |

| 边界项 | 结论 |
|---|---|
| 直连联网 | `audit_*`/`cold_backup`/`release_manifest` 不联网；`discover_lan` 只发局域网 UDP；`check_privacy` 只在启用 `--gitleaks` 时执行本地扫描器 |
| 正式业务 API | 全部**不调用**（管理员工具直接读只读数据库/文件或 Git） |
| 真实数据 | `audit_library`/`audit_backup`/`cold_backup` 可接触真实库与媒体目录（用户显式授权、只读/停写前提）；其余不接触 |
| 写入副作用 | `cold_backup create/restore` 有写入（目标必须不存在）；`release_manifest` 写交付清单；`audit_*` 只读；`check_*` 只读 |
| 自动化测试 | 均存在对应 `test_*.py`；tools 全量 unittest 在云端 `.github/workflows/docs.yml:28` 运行 |
| 重复实现 | `audit_backup` 是被 `audit_library` 使用的子模块（非独立重复）；`cold_backup` 的哈希/普通文件校验被 `release_manifest` 复用（`tools/release_manifest.py:7`） |
| 建议 | **保持独立管理，禁止默认对 AI 开放**（任务书 §3.2 第三类）；`audit_library` 若未来服务端提供同等只读审计接口可整件退役（`:1`–`:5` 自述） |

### 2.5 服务端相关能力与协议

| 条目 | 用途与调用者 | 现状与证据 |
|---|---|---|
| `server/internal/maintenance/` | 持久维护任务账本（migration 0023）：Create/List/Get/Pause/Resume/Cancel 经 HTTP；Claim/Heartbeat/Complete/RecordStep/AddArtifact 为内部原语 | `server/internal/maintenance/doc.go:1`；`service.go:63`（Create）、`:153`（List）、`:255`（Pause）、`:328`（Cancel）、`:366`（RecordStep）；`lease.go:17`（Claim）表明**没有外部领取 HTTP 端点** |
| `server/internal/deviceapply/` | PC→手机结果包账本（migration 0024）：Create/List/Get/Claim/Result/Cancel/OpenSegment；段字节不入 SQLite | `server/internal/deviceapply/doc.go:1`；`service.go:74`–`:415`；端点 `api/openapi.yaml:1656`–`:1792` |
| `server/internal/research/` | 研究 provider/Lens 网关（ADR-0053），默认关闭 | `server/internal/research/doc.go:1`；`service.go:178`（Query）、`:227`（Lens）；端点 `api/openapi.yaml:1464`–`:1510` |
| `api/openapi.yaml` | 业务协议唯一权威；AI 工具与三端共同消费 | 95 个 path；相关端点见 §4.2 |
| 作者领域实现（只读参考） | 档案校验/空档案判定/公开 URL 策略的**权威实现** | `server/internal/creatorprofile/model.go:132`（IsEmpty）、`:165`（Validate）；`fetch.go:42`（publicURL）；`httpapi/creator_profile.go:52`（PUT 处理）；`httpapi/authors_manage.go:128`（改名）、`:194`（删除）；`httpapi/author_edit.go:209`（改挂）；`authoredit/service.go:250`（authorAttach 库能力判定） |

这三个 Go 包都是**服务端业务能力**，不是 AI 工具脚本：AI 工具层只能经正式 API 使用它们，不得复制其写入逻辑（ADR-0056 §1、任务书 §11.3）。

## 3. 三分类收敛

按任务书 §3.2 的三分类，给出收敛结论（"统一工具层"指 `tools/ai_maintenance/` 的工具注册/校验/执行体系）：

| 分类 | 条目 | 归属处置 | 依据 |
|---|---|---|---|
| **AI 通用工具** | `rules.json`、`rulepack.py`、`cli.py` | 纳入统一工具层，作为唯一规则源、注册表与查询入口 | 任务书 §4、§6.1 |
| **AI 通用工具** | `import_creator_profiles.py` | **封装/合并**：校验、人工字段保护、plan/apply、409 处理迁入统一核心；脚本保留过渡兼容入口，不保留第二份写入算法 | 任务书 §6.5；ADR-0056 被否方案"复制旧脚本" |
| **AI 通用工具**（服务端目标能力，不迁移） | `/api/v1/authors*`、`/profile`、`/merge`、`/assets/{id}/authors`、`/maintenance/jobs*`、`/export|import/qimeng-backup` | 只作为工具声明的"所需 API 能力"；R2 补齐能力声明，R3 复用 | 任务书 §4.2、§6 |
| **可选研究工具** | `research_sidecar/`、`server/internal/research/`、CLI research 命令 | 保留可选、默认关闭、独立部署；不纳入 AI 通用工具的可用性承诺；无联网能力的 AI 只能产生待研究事项 | 任务书 §10；ADR-0056 §9 |
| **可选研究工具** | `AUTHOR_RESEARCH.md`、`MEDIA_RESEARCH_IMPORT.md` | 保留为研究方法/流程文档（不是可执行工具）；历史探针退役、不迁移 | 任务书 §10.3 |
| **管理员专用工具** | `audit_library.py`、`audit_backup.py`、`cold_backup.py`、`release_manifest.py`、`check_docs.py`、`check_privacy.py`、`check_migrations.py`、`check_glib_patch.py`、`discover_lan.py` | 保持独立管理，**不接入 AI 工具层**，不因整合而默认对 AI 开放 | 任务书 §3.2 第三类、§11.2 |
| **开发/测试工具（非 AI）** | `ui-preview/`、`test_release_gate.py`、`test_windows_launcher.ps1` | 保持现状，归 Web/CI 测试基础设施；不得误纳入 AI 平台 | `tools/ui-preview/README.md:1`、`.github/workflows/ci.yml:152` |
| **服务端能力（非工具）** | `server/internal/{maintenance,deviceapply,research}` | 作为 AI 工具的目标 API / 可选研究能力；R5/R6 复用唯一账本与协议，禁止第二套状态源 | 任务书 §8.1、§9.1；ADR-0056 §6/§7 |
| **协议权威（只读）** | `api/openapi.yaml` + `api/sdk.lock` | AI 平台只读消费；不为工具命名/研究流程改主协议 | ADR-0056 §1、§3 |

**收敛结论（对 R1-R2 的直接输入）**：作者资料写入只能有一份权威实现。现状是"唯一实现存在、但不在统一核心"，因此 R1 的第一优先级不是新增能力，而是把 `import_creator_profiles.py` 的成熟逻辑（输入上限/身份与类型预检/公网 URL 校验/人工字段与候选保护/plan/409/回读）迁入 `tools/ai_maintenance`，并让 CLI 与旧脚本共用同一实现。

## 4. 规则包与 OpenAPI 漂移检查

### 4.1 方法

以 `rules.json` 声明的 27 个端点（`capabilities.read` 12、`capabilities.write` 10、`authorActions` 4、`fields.authorProfile` 1）为对象，逐条与 `api/openapi.yaml` 的 path+method 比对；同时人工核对"服务端是否真的校验了规则文本"。比对脚本只读，结果如下。

### 4.2 已声明端点逐条核对

| 类别 | ID | rules 声明 | OpenAPI 实际 | 判定 |
|---|---|---|---|---|
| read | project.context | 无端点（纯本地） | — | 正确 |
| read | health.check | GET /api/v1/healthz（备 readyz） | GET `api/openapi.yaml:1305`（readyz `:1314`） | 一致 |
| read | library.query | GET /api/v1/libraries | GET,POST `api/openapi.yaml:105` | 一致 |
| read | asset.query | GET /api/v1/assets | GET `api/openapi.yaml:190` | 一致（limit max 200 与 `cli.py:27` 一致） |
| read | author.query | GET /api/v1/authors（全量数组） | GET `api/openapi.yaml:754`，响应数组 `:758` | 一致（无服务端分页） |
| read | author.profile.read | GET /api/v1/authors/{authorId}/profile | GET `api/openapi.yaml:828` | 一致 |
| read | author.sources.read | GET /api/v1/authors/{authorId}/sources | GET `api/openapi.yaml:956` | 一致 |
| read | research.context | GET /api/v1/research/context | GET `api/openapi.yaml:1464` | 一致 |
| read | research.provider.query | POST /api/v1/research/providers/{provider}/query | POST `api/openapi.yaml:1478` | 一致 |
| read | research.lens | POST /api/v1/research/lens | POST `api/openapi.yaml:1510` | 一致 |
| read | backup.export | GET /api/v1/export/qimeng-backup，planned | GET `api/openapi.yaml:1371` | **端点存在，工具未实现**（诚实 planned；R3 补） |
| read | author.cleanup.prepare | 无端点，planned | 无对应端点 | 正确（服务端无清理接口） |
| write | author.profile.update | PUT /api/v1/authors/{authorId}/profile，planned | GET,PUT `api/openapi.yaml:828` | **端点存在，唯一写入实现却在独立脚本**（A1） |
| write | author.delete | DELETE /api/v1/authors/{authorId}，planned | PUT,DELETE `api/openapi.yaml:866` | 端点存在，工具 planned |
| write | author.rename | PUT /api/v1/authors/{authorId}，planned | PUT,DELETE `api/openapi.yaml:866` | 端点存在，工具 planned |
| write | asset.authors.replace | PUT /api/v1/assets/{assetId}/authors，planned | PUT `api/openapi.yaml:340` | 端点存在，工具 planned |
| write | author.merge | POST /api/v1/authors/merge，planned | POST `api/openapi.yaml:787` | 端点存在，工具 planned |
| write | author.cleanup.apply | 无端点，planned | 无对应端点 | 正确（合并走专用端点，见 `:787` 描述） |
| write | backup.import | POST /api/v1/import/qimeng-backup，planned | POST `api/openapi.yaml:1348` | 端点存在，工具 planned |
| write | device.apply.prepare | endpoint null，planned | **已有** `/api/v1/device/apply-tasks` `api/openapi.yaml:1656` | **漂移**：规则未声明已有端点 |
| write | device.apply.submit | endpoint null，planned | 已有 `:1769`（result）等 | **漂移**：同上 |
| write | device.apply.status | endpoint null，planned | 已有 `:1656`、`:1717` | **漂移**：同上 |
| action | delete | DELETE /api/v1/authors/{authorId}，apiStatus available | 同 `:866` | 一致 |
| action | rename | PUT /api/v1/authors/{authorId}，apiStatus available | 同 `:866` | 一致 |
| action | reattach | PUT /api/v1/assets/{assetId}/authors，apiStatus available | 同 `:340` | 一致 |
| action | merge | POST /api/v1/authors/merge，apiStatus available | 同 `:787` | 一致（rulepack 会按方法重写 `:149`） |
| fields | authorProfile | GET/PUT /api/v1/authors/{authorId}/profile | 同 `:828` | 一致 |

**结论**：已声明的 24 个端点全部真实存在且方法匹配，**没有"规则声明的端点不存在"这类虚假声明**；漂移的方向是"已有端点/能力未被规则声明"与"实现状态语义混用"。

### 4.3 漂移与缺口发现

- **F1（P1，R2）能力核对过弱**：`rulepack.py:133`–`:146` 只判断 `path in path_set`，完全不看方法；要求必需字段的核对也不存在。唯一按方法校验的是 merge（`rulepack.py:149`–`:159`，用 `METHOD_LINE` `:22` 解析）。因此若某个方法被删除而路径仍在，工具不会被降级；若端点方法变了，规则仍标 available。任务书 §5.2 要求按"端点+方法+必需字段/业务能力"核对。
- **F2（P2，R2+R5/R6）已有服务端能力未进规则包**：`rules.json` 的 capabilities 中没有 `maintenance.inventory`/`maintenance.status`，也没有 `device.apply.*` 的真实端点。但 `/api/v1/maintenance/jobs`（列表/详情/暂停/恢复/取消，`api/openapi.yaml:1553`–`:1651`）与 `/api/v1/device/apply-tasks`（创建/列表/详情/claim/段下载/result/cancel，`:1656`–`:1792`）都已存在；`deviceapply` 的状态机与段存储已在 `server/internal/deviceapply/doc.go:1` 说明。规则包落后于协议，会误导 AI 认为这些能力未实现。
- **F3（P3，R3）backup.export 端点存在但工具 planned**：`rules.json:151`–`:155` 与 `api/openapi.yaml:1371`。这是"实现缺口"不是"错误声明"，但必须保持诚实状态，禁止在 R3 前写成 available。
- **F4（P1，R1/R3）写入状态与实现分裂**：`rules.json:162`–`:166` 把 `author.profile.update` 标 planned，而唯一实际写入者是 `import_creator_profiles.py:158`–`:190`。若不迁移，"唯一权威实现"只存在于规则包之外；若直接改状态为 supported，又会出现"支持的写工具未注册"。正确做法是先迁移实现，再由注册表宣布 supported。
- **F5（P3，R2 文档）规则来源指针过时**：`rules.json:4` 的 `source.taskbook` 指向 `docs/AI_MAINTENANCE_TASKBOOK.md`；`tools/ai_maintenance/README.md:5`、`:230`–`:231` 仍将其列为权威。ADR-0056 §10 已把该文档标记为历史来源，现行清单是 [任务书 V2](AI_PLATFORM_TASKBOOK.md)。规则包的 source 只作溯源，可保留历史指针，但 README 的"权威关系"必须改为 V2 + ADR-0056。
- **F6（P3，R7 同批）备份代际陈述将过期**：`rules.json:423`（"当前装机仍是三代同目录；两代分代目录属于 planned"）与 `tools/MEDIA_RESEARCH_IMPORT.md:57`（"最新、上次、上上次三份"）在 R7 落地后都会错误。R7 完成时必须同批更新这两处并 bump `rulesVersion`，否则规则包与文档互相矛盾。
- **F7（P2，R3）规则只存在于文档、服务端未校验**（逐条确认）：

  | 规则 | 位置 | 服务端是否校验 | 工具层要求 |
  |---|---|---|---|
  | 导入前必须先导出完整 v2 备份，导出失败即中止 | `rules.json:404`、`:419` | **否**：`POST /import/qimeng-backup`（`api/openapi.yaml:1348`）无前置导出校验；服务端无法得知调用方是否已导出 | R3 导入工具必须自己先成功导出再提交，失败即中止并报告 |
  | 写入前必须生成计划并输出影响数量/旧值与新值 | `rules.json:487`–`:489` | **否**：服务端只收到最终请求 | R3 plan 结构与指纹强制 |
  | 作者清理不得仅按零关联数量删除，需证据与人工确认 | 任务书 §6.4；`rules.json:481` 起工作流 | **否**：无清理端点，删除/合并端点只校验身份与事务 | R3 清理工具层强制 |
  | 手机应用前先导出本地完整备份，失败中止 | `rules.json:523` | **否**：deviceapply 只存/取段，不负责手机本地备份 | R6 Android Worker 强制 |
  | 合并不可撤销、必须先预览影响、改名撞身份不得偷偷合并 | `rules.json:365`、`:489`；`api/openapi.yaml:787` 描述 | **部分**：服务端保证"改名撞身份 409 不合并"（`server/internal/httpapi/authors_manage.go:124` 注释与 `authoredit` 用例）与"合并原子性"；"先预览"只在调用方 | R3 前置计划 + 影响预览 |
  | 标签只读、AI 不写标签 | `rules.json:388`–`:397` | 服务端有标签写端点，但 AI 工具不注册 | 工具注册表不注册标签写工具 |
  | 本地执行器权限边界（只读 SQLite、授权目录、仅正式 API 写入） | `rules.json:536` 起 permissions | **否**：这是执行器/工具策略，不是服务端强制 | R1/R3 在 client 层与应用层落实 |
  | 反例：`status=verified` 必须有主页+证据；autoRefresh/trackUpdates 前置；evidenceUrls ≤8；URL 必须 HTTPS 公网 | `rules.json:214` 起 fields | **是**：`server/internal/creatorprofile/model.go:165`–`:213`（含 `:201` verified 约束、`:169` 上限、`:188` URL 校验） | 工具层可复用但以服务端为准 |

- **F8（P3，R2）字段上限三处一致但无自动核对**：`rules.json:214`–`:217` 的 `requiredForPut` 与 `api/openapi.yaml:2701`–`:2715`（CreatorProfileWrite）及其 `:2693`–`:2700`（CreatorXProfile）一致，也与 Go `Validate` 一致；但 `rulepack.py` 完全不解析 schema，未来任何一处改动都只能靠人工发现。R2 应在分块解析中核对"端点方法 + 关键 required/字段上限"，并用合成 fixture 锁定。

### 4.4 过时 / 待改写规则

| 规则/文本 | 位置 | 问题 | 处置 |
|---|---|---|---|
| `guide.summary` "本切片只有规则上下文和只读查询……写入一律 planned" | `rules.json:12` | R3 落地后过期 | 随 R3 改写；过渡期保持 |
| `source.taskbook` 指向历史任务书 | `rules.json:4` | 溯源指针可保留，但需注明"历史来源" | R2 增加 `source.currentTaskbook` 或改注释 |
| backup.rules "当前装机仍是三代同目录" | `rules.json:423` | R7 后错误 | R7 同批更新 + `rulesVersion` bump |
| `backup.plannedLayout` | `rules.json:425`–`:428` | R7 实现后应从 planned 改为 implemented | R7 同批 |
| `device-apply-pc-only` 工作流期望"查询 device.apply.status" | `rules.json:517`–`:527` | 步骤期望的端点已存在但能力未声明 | R2 补 `device.apply.*` 端点声明 |
| `capabilities.write.device.apply.*` endpoint null | `rules.json:197`–`:210` | 与 `api/openapi.yaml:1656` 起矛盾 | R2 修正 |
| `MEDIA_RESEARCH_IMPORT.md` 三代备份陈述 | `tools/MEDIA_RESEARCH_IMPORT.md:57` | R7 后错误 | R7 同批更新（当前不在 R7 写范围内，需 Lead 协调） |
| `tools/ai_maintenance/README.md` 权威关系引用历史任务书 | `tools/ai_maintenance/README.md:5`、`:230` | 与 ADR-0056 §10 冲突 | R2 更新（task-2 写范围） |

**未发现需要整条删除的错误规则**：现有规则均有真实领域依据；问题集中在版本/状态/指针，而不是规则内容错误。标签只读、作者四类动作的前置条件、备份分段语义都与当前服务端实现一致。

## 5. 重复实现发现

| 编号 | 重复内容 | 位置 A | 位置 B | 影响 | 建议 |
|---|---|---|---|---|---|
| D1 | HTTP 请求客户端 + base URL 校验 + 禁重定向 + 令牌读取 + 错误分类 | `tools/ai_maintenance/cli.py:68`、`:91`–`:146`、`:184`–`:246` | `tools/import_creator_profiles.py:21`–`:23`、`:104`–`:120`、`:123`–`:145` | 两份实现已经漂移：环境变量名 `QIMENG_API_BASE`（`cli.py:136`）vs `QIMENG_BASE_URL`（`import_creator_profiles.py:196`）；错误类别集合不同 | R1 抽取单一 `client.py`；旧脚本保留自己的环境变量名作为兼容映射 |
| D2 | 作者档案"空/人工字段"判定 | `tools/import_creator_profiles.py:148`（`pristine`） | `server/internal/creatorprofile/model.go:132`（`IsEmpty`） | 同一业务判定在 Python/Go 各写一份；字段增减时易漂移（Python 还检查 `candidate` 全空，Go 逐字段检查） | AI 侧保留"预检"但以服务端拒绝为准；R2 在规则里声明"服务端 IsEmpty 为权威" |
| D3 | 公开 URL 校验（HTTPS、禁 userinfo、禁 fragment、端口限制、DNS/IP 公网） | `tools/import_creator_profiles.py:37`–`:52` | `server/internal/creatorprofile/fetch.go:42`–`:55` | Python 版本没有 Go 的 `blockedNetworks` 与解析后 IP 校验，可能放行 Go 会拒绝的 URL；服务端仍会兜底 | 保留轻量预检，但规则写明"最终以服务端 400 为准"；不得把 Python 预检当安全边界 |
| D4 | 作者资料写入流程（GET 列表预检 → GET profile → 人工字段保护 → PUT revision → 回读/计数） | `tools/import_creator_profiles.py:158`–`:190` | 无第二实现，但统一规则/CLI 中不存在 | 唯一实现游离在统一核心外（A1）；`rules.json` 仍 planned | R1/R3 迁入 `tools/ai_maintenance`，旧脚本转发 |
| D5 | 错误分类与输出脱敏 | `cli.py:41`–`:65`、`:171`–`:181`、`:600`–`:636` | `import_creator_profiles.py:17`–`:18`、`:140`–`:145`、`:213`–`:215` | CLI 会输出 HTTP body（已脱敏 token）；填充脚本只输出固定类别。合并后必须同时保留两种契约，不能悄悄改变旧脚本输出 | R1 统一错误模型 + 每入口的渲染契约测试 |
| D6 | 研究平台查询能力 | Go provider 网关（`server/internal/research/doc.go:1`） | 本地历史探针 `audit/verify/probe.local`（`tools/AUTHOR_RESEARCH.md:107`–`:120`） | 探针为本地未入库脚本，功能与 provider 矩阵重叠；继续维护会形成第三套研究出口 | 标记历史/退役，不迁移；文档只保留"何时查历史记录" |
| D7 | 备份相关能力 | `/api/v1/backups*`（服务端备份）、`/api/v1/export|import/qimeng-backup`（便携 v2） | `cold_backup.py`（停写目录副本）、`audit_backup.py`（v2 对账）、`audit_library.py`（库内对账） | 不是重复实现，但五个入口容易被混写为"备份工具"；R7 两代布局还需要同步"备份/导出/镜像"三者语义 | 文档与工具注册表显式区分职责；R7 只改"两代目录布局"，不合并这些工具 |
| D8 | 同一业务规则的三个来源 | `api/openapi.yaml` 字段约束 | `rules.json` fields | 方法文档/任务书 | 目前内容一致（F8），但没有自动核对；任何一处改动都可能造成三处漂移 | 规则源唯一化：OpenAPI 为协议权威、`rules.json` 为机器可读规则、文档只解释；R2 用测试锁定关键字段一致性 |

补充说明（"研究接口是否存在不必要的重叠"）：**没有第二套可执行研究网关**。CLI 的 `research` 命令只是调用 `/api/v1/research/*`（`cli.py:408`–`:428`），Go 包是唯一服务端出口，sidecar 只是它的本机依赖。真正的重叠只有本地历史探针 D6。

## 6. 复用 / 退役映射

| 条目 | 决策 | 目标归属 | 触发条件 / 时点 |
|---|---|---|---|
| `rules.json`、`rulepack.py` | 保留并升级为唯一规则源 + 分块解析 | `tools/ai_maintenance/`（R2） | R2 开始；`rulesVersion` 与 schema 同步 bump |
| `cli.py` | 拆分复用：client / registry / commands / reports | `tools/ai_maintenance/`（R1–R4） | R1 先抽 client，R2 抽 registry，R4 加 MCP 适配 |
| `import_creator_profiles.py` | 封装/合并：算法迁入统一核心，脚本保留过渡转发入口 | `tools/ai_maintenance/` + 兼容壳 | R1 完成迁移；R3 注册 `author.profile.prepare/update`；过渡期后评估退役脚本 |
| `tools/test_import_creator_profiles.py` | 测试迁入或按模块拆分，不删除覆盖 | `tools/test_ai_*.py` | 与 R1 同步 |
| `audit_library.py` + `audit_backup.py` | 保留，管理员专用、只读隔离；不接 AI | 原地独立 | 若未来服务端提供同等只读审计接口再整件退役（`audit_library.py:1`–`:5` 自述） |
| `cold_backup.py` | 保留，管理员专用；不接 AI | 原地独立 | 不因 AI 平台整合而改变停写/恢复语义 |
| `release_manifest.py` | 保留，交付门禁 | 原地独立 | 继续复用 `cold_backup` 的哈希/文件校验 |
| `check_docs.py`、`check_privacy.py`、`check_migrations.py`、`check_glib_patch.py`、`discover_lan.py` | 保留，开发/运维门禁 | 原地独立 | 不进入 AI 工具注册表 |
| `ui-preview/` | 保留，Web/CI 测试夹具 | 原地独立 | 明确不属于 AI 工具（避免被"统一工具"误收编） |
| `research_sidecar/` | 保留，可选研究工具，隔离部署 | 原地独立进程 | 不迁入 Go；保持 loopback + token + 默认不启动 |
| `server/internal/research/` | 保留，可选研究网关 | 原地 | 默认关闭；AI 工具只声明能力，不复制 provider 逻辑 |
| `server/internal/maintenance/` | 保留为唯一任务账本 | 原地 | R5 Worker 复用；R2 补 `maintenance.*` 能力声明 |
| `server/internal/deviceapply/` | 保留为唯一设备结果包协议 | 原地 | R6 手机 Worker 复用；R2 补 `device.apply.*` 端点声明 |
| `AUTHOR_RESEARCH.md`、`MEDIA_RESEARCH_IMPORT.md` | 保留为方法/流程文档 | 原地 | R7 同批更新三代陈述；历史探针段落标注退役 |
| 本地历史探针 `audit/verify/probe.local` | 退役（不迁移、不再扩展） | 仅本地历史 | `AUTHOR_RESEARCH.md` 保留"历史记录"说明即可 |
| `api/openapi.yaml` | 只读权威 | 原地 | AI 平台不得为工具命名改主协议 |

## 7. 最小变更计划（支撑 R1–R7）

只列审计能支撑的最小改动；具体实现由对应 owner 在各自 write scope 内完成。`tools/ai_maintenance/` 与 `import_creator_profiles.py` 属 task-2（ai-core）范围，本审计不写代码。

| 顺序 | 计划 | 具体动作 | 验收 | 风险 / 回退 |
|---|---|---|---|---|
| R1-P1 | 抽出统一 client | 在 `tools/ai_maintenance/` 新增 client 模块（base URL/禁重定向/代理禁用/超时/响应上限/令牌读取/错误分类/token 脱敏），`cli.py` 与 `import_creator_profiles.py` 都改用；环境变量名做一次性兼容映射（`QIMENG_BASE_URL` → `QIMENG_API_BASE`） | 现有 `test_import_creator_profiles.py` 13 项与 `test_ai_maintenance.py` CLI 用例全绿；退出码/输出类别不变 | 最坏回退为保留两入口各自包装；不改服务端 |
| R1-P2 | 抽出作者档案工具 | 迁入 `pristine`/人工字段保护/plan/apply/409/回读，注册 `author.profile.prepare|update` 与 `author.query|profile.read|sources.read`；旧脚本转发 | 合成报告对 API 替身：错库零写入、人工字段不覆盖、409 单独计数；契约测试保持旧脚本输出 | 若迁移期出现行为差异，先修差异再切换；不删除旧脚本 |
| R2-P1 | 版本三元组 | `rules.json` 增加 `platformVersion`/`toolContractVersion`/`rulepackVersion`；规则包输出版本分离；`openapiSha256` 仅作来源追踪 | 改一个无关 OpenAPI 字段只让声明该字段的工具降级；规则包稳定可比较 | 版本字段设计需与 AI 平台发行物一致；避免把主 API 字节变化等同全部失效 |
| R2-P2 | 能力核对 | 分块解析 OpenAPI：path+method+关键 required/字段；每个工具声明自身依赖；缺失/不符标记 unavailable/incompatible；补 `maintenance.*`/`device.apply.*` 与 `backup.export` 的真实端点/状态；拆 `status` 与 `apiStatus` | fixture 锁定解析行为；删除某方法/改字段时对应工具降级；规则包不再漏声明 `api/openapi.yaml:1553`、`:1656` 起能力 | 解析面扩大可能误降级；用合成 fixture + 真实 `api/openapi.yaml` 双跑 |
| R3 | 受控写入 | 按任务书 §6.2 顺序实现作者链：`author.profile.prepare/update`、`author.cleanup.prepare/apply`、`author.merge`、`asset.authors.prepare/apply`；计划含指纹/前置 revision/幂等键/影响预览；执行只走正式 API；回读并结构化报告 | 合成库上完成"读取→计划→合法修改→409→回读"；人工资料无意外覆盖；F7 规则由工具强制执行 | 误写风险靠"未确认计划不得执行"+只读合成数据测试控制 |
| R5/R6/R7 | 平台集成 | R5 复用唯一任务账本（无新增任务表）；R6 复用 deviceapply（无第二协议）；R7 两代布局落地后同批更新 `rules.json:423`、`MEDIA_RESEARCH_IMPORT.md:57` 并 bump `rulesVersion` | 各阶段按任务书 §14 验收矩阵 | 并发批次冲突：R7 与 rules 版本必须同一 PR 序列协调 |

### 7.1 明确不做（本轮）

- 不重写 Go 业务逻辑、不新增第二套任务账本或设备结果包协议。
- 不把旧脚本原样复制进新平台；不为整合而删除任何旧脚本或历史备份。
- 不把管理员工具（审计/备份/迁移/门禁）接入 AI 工具层或默认对 AI 开放。
- 不为 AI 工具命名或研究流程修改 `api/openapi.yaml`/SDK；确需新能力先按正式流程评审。
- 不引入第三方 YAML/校验库；不扩大字符串行解析作为长期方案（R2 用分块解析替代）。
- 不迁移 research_sidecar 进 Go，不默认启用研究网关，不新增自动外网抓取。
- 不把 `ui-preview` 收编为 AI 工具。
- 不在本审计批次删除 `import_creator_profiles.py`；过渡期保留兼容入口。
- 不触碰真实库 `8420`、真实媒体、真实设备或用户历史备份。

### 7.2 遗留风险

1. **规则包与 OpenAPI 双源**：R2 能力核对上线后，若解析或声明有误，可能出现大量工具被误降级；需 fixture 与真实协议双跑，并保留逐工具禁用开关。
2. **旧脚本行为契约**：转发实现后若错误类别/退出码变化，会破坏本地 AI 运维流程；必须保留契约测试。
3. **跨批次文档漂移**：R7 会同时影响 `rules.json`、`MEDIA_RESEARCH_IMPORT.md`、`docs/api/authors.md` 与 Android 端说明；需 Lead 在整合批次中统一检查。
4. **研究链证据质量**：sidecar 无仓库自动化测试，Lens 结果质量有限（`tools/research_sidecar/README.md:320`–`:333`）；R3 不得把研究结论直接写 verified，必须保留 candidate 与人工审核。
5. **并发工作树**：本审计期间多个队友正在修改 `tools/ai_maintenance/`、`server/internal/maintenance/`、`docs/api/authors.md`；本文行号可能漂移，实施前应重新读取。

## 8. 证据边界与已读文件

### 8.1 实际执行的检查

- 只读读取源码/文档；只读提取 OpenAPI 路径与方法；只读比对 `rules.json` 声明端点；只读执行 `python -X utf8 tools/check_docs.py` 记录审计前基线。
- 审计前基线：文档检查 141 个文件、4 项错误，其中 3 项由本文档缺失引起（`docs/AI_PLATFORM_TASKBOOK.md`、`llms.txt`、`AGENTS.md` 指向 `docs/AI_PLATFORM_AUDIT.md`），第 4 项为 ADR-0056 内的 `../api/openapi.yaml` 相对路径错误（应为 `../../api/openapi.yaml`，属 Lead 编辑范围，已单独上报）。
- 本文写完后再次运行 `check_docs`，预期只剩 ADR-0056 一项；结果以交付消息为准。
- **未执行**：`python -m unittest`（本次无代码改动，且任务授权本机验证不含全量测试）、`go build/vet/test`（无 Go 改动）、任何 Web/Android 构建、任何服务启动、任何真实库/真实设备连接。

### 8.2 已读文件（按用途）

- 入口与任务书：`AGENTS.md`（工作尺度/红线/评审口径）、`docs/AI_PLATFORM_TASKBOOK.md`（§2–§16）、`docs/adr/0056-独立AI接入平台架构.md`（全文）。
- AI 工具链：`tools/ai_maintenance/{README.md, rules.json, rulepack.py, cli.py}`（全文/结构与关键段）、`tools/test_ai_maintenance.py`（测试清单）、`tools/ai_maintenance/__init__.py`。
- 作者写入：`tools/import_creator_profiles.py`（全文）、`tools/test_import_creator_profiles.py`（测试清单）。
- 管理员工具：`tools/audit_library.py`、`tools/audit_backup.py`、`tools/cold_backup.py`、`tools/release_manifest.py`、`tools/check_docs.py`、`tools/check_privacy.py`、`tools/check_migrations.py`、`tools/check_glib_patch.py`、`tools/discover_lan.py`（全文）、`tools/README.md`（全文）、`tools/ui-preview/README.md`（全文）。
- 研究链：`tools/research_sidecar/README.md`（§1–§2、§6–§12）、`tools/AUTHOR_RESEARCH.md`（边界/历史探针/X 简介/媒体复核）、`tools/MEDIA_RESEARCH_IMPORT.md`（入口/日常刷新）、`server/internal/research/{doc.go,service.go 签名}`。
- 服务端与协议：`api/openapi.yaml`（paths 全量 + 相关 schema：CreatorProfileWrite/CreatorXProfile/AuthorMergeRequest/Author）、`server/internal/{maintenance,deviceapply}/doc.go`、`server/internal/maintenance/{service.go,lease.go} 签名`、`server/internal/deviceapply/service.go 签名`、`server/internal/creatorprofile/{model.go,fetch.go}` 相关段、`server/internal/httpapi/{creator_profile.go,authors_manage.go,author_edit.go}` 相关段、`server/internal/authoredit/service.go`（authorAttach 判定）。
- CI 事实：`.github/workflows/{docs,security,ci}.yml` 中与 `tools/` 测试相关的行。
- 确认存在但未通读正文：`docs/api/{authors,backup,device,maintenance,research}.md`；`audit/` 下被忽略的本地目录（仅确认存在，未读取内容）。

### 8.3 证据边界

- 本文不证明任何服务端行为"已验证"：所有"服务端校验"均指源码中存在校验路径，未在隔离实例中实测。
- 未核对远端 GitHub HEAD 与 CI 结果；基线仅为本地 `f26dcebd`。
- 未审计 Web、Android、desktop 客户端对 AI 能力的使用（本轮范围外）。
- 行号证据随并发改动可能漂移；实施前请以文件当前内容复核。
- 本文不包含凭据、令牌、真实作者/作品名、设备标识、真实库统计或含用户名的本机绝对路径。
