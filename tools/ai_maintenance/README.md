# 绮梦影库 AI 工具平台（R3+：查询、计划、受控写入与按需上下文）

> 本目录是外部 AI 的**独立工具核心**：规则包 + 显式工具注册表 + 统一 CLI + 计划与
> 受控写入 + stdio MCP 适配器。仅用 Python 3.13 标准库，不安装第三方依赖。
>
> 权威关系：协议以 [api/openapi.yaml](../../api/openapi.yaml) 为准；现行执行清单见
> [统一任务书 V2](../../docs/AI_PLATFORM_TASKBOOK.md)，架构决策见
> [ADR-0056](../../docs/adr/0056-独立AI接入平台架构.md)；本目录 `rules.json`
> 是 AI 工具层唯一手写规则源。旧
> [任务书](../../docs/AI_MAINTENANCE_TASKBOOK.md)只作历史需求与设计来源。

## 文件

| 文件 | 作用 |
| --- | --- |
| `rules.json` | 唯一手写领域规则源：版本、工具开关、能力声明、作者/清理/组图命名/备份规则、错误码、工作流。 |
| `versions.py` | platformVersion / toolContractVersion / planVersion 常量。 |
| `openapi.py` | OpenAPI **分块解析**（path → method → requestBody/parameters → schema required/properties）与按工具的能力核对；不引入 YAML 依赖。 |
| `registry.py` | 显式工具注册表：ID/版本/描述/输入输出 Schema/权限/只读/副作用/确认/超时/错误码/能力依赖/实现状态。 |
| `execution.py` | 统一 dispatch：禁用策略、Schema 校验、能力核对、确认门禁、错误分类。CLI 与 MCP 共用。 |
| `plans.py` | 计划结构、稳定指纹、幂等键与版本绑定校验。 |
| `client.py` | 唯一 HTTP 客户端：origin 校验、禁代理/重定向、超时、响应上限、token 脱敏。 |
| `validation.py` | 作者档案/URL/名称/跨字段约束的机器可读校验。 |
| `tools_query.py` | 只读查询与研究工具实现。 |
| `tools_author.py` | 作者档案 prepare/update、清理 prepare/apply、原子合并。 |
| `tools_asset.py` | 资产作者关联 prepare/apply、asset.trash、asset.tags.replace（标签整体替换）。 |
| `tools_backup.py` | backup.export：`GET /export/qimeng-backup` 流式落盘（临时文件 + 原子改名）、摘要与预算。 |
| `tools_device.py` | device.apply.prepare/submit/status：本地段校验、manifest 计划、multipart 登记与回读。 |
| `tools_maintenance.py` | maintenance.claim/heartbeat/step.record/artifact.record/complete：执行器控制面（leaseToken 是能力凭据）。 |
| `tools_research.py` | research_kit 适配：argv 组装、路径/超时边界、产物枚举（算法仍在 `../research_kit/`）；`research.media.probe` 接受 `fileRefs`，返回的 argv 对 fileRef 输入脱敏。 |
| `tools_files.py` | `files.storages` / `files.search`：授权存储枚举与有界文件搜索，返回不透明 `fileRef` 与相对路径，不返回物理绝对路径。 |
| `tools_trash.py` | trash.list / trash.restore（需确认 + 回读）。 |
| `pathguard.py` | 本地读写授权边界：`QIMENG_AI_READ_ROOTS`/`QIMENG_AI_WRITE_ROOTS`/媒体根/忽略工作根、链接与 reparse 检查、TOCTOU 复核与文件摘要。 |
| `safefile.py` | 本地文件安全发布：同文件/硬链接身份判断、同目录临时文件、无覆盖/原子覆盖发布；不承载业务规则。 |
| `fileref.py` | 逻辑存储与 `fileRef` 唯一实现：`QIMENG_AI_STORAGES`、自描述引用（storageId+相对路径+size+mtime）、失效校验与 path/fileRef 二选一解析。 |
| `measure_locator.py` | 隔离合成测量：路径式定位 vs `fileRef` 的调用次数、返回字符量、绝对路径暴露与定位正确率；不联网、不读真实媒体。 |
| `media_naming.py` | **组图命名纯规划/校验引擎**（作品索引、`(1)..(n)` 页码、计划指纹、`SerializedLocks`、回读报告）；ENGINE_VERSION=1.0.0。 |
| `media_scan.py` | **媒体批次内容扫描（只读）**：md5/可选 dHash64/视频时长+分辨率+抽帧、最大文件去重候选、序号延顺建议、根目录对齐与 contact sheet 计划；SCAN_ENGINE_VERSION=1.0.0。 |
| `media_primitives.py` | **媒体原语唯一实现**：ffprobe 元数据、dHash64、百分比抽帧、拼图生成与 `(path,size,mtime,参数)` 缓存；media_scan 与 recipe 共用。 |
| `device_adb.py` | **ADB 原语**：显式 `QIMENG_ADB_SERIAL`、逐文件 push/pull、字节数回读、POSIX 远程路径；无默认设备。 |
| `targets.py` | 运行目标能力档案：pc / android-embedded / nas；recipe 执行前核对 api/ffmpeg/node/adb/mediaRoots/research/lens。 |
| `recipes.py` + `recipes/` | **可执行编排配方**：JSON 步骤引用工具/原语，支持条件、人工门禁、断点续跑、产物落盘与任务卡。 |
| `tools_media.py` | media.organize.prepare 适配层：scan 模式调用内容扫描；libraryId+groups 模式调用命名引擎；apply 加锁逐步 move 并回读。 |
| `profile_import.py` | 作者公开档案受控填充的**唯一写入实现**；旧脚本 `../import_creator_profiles.py` 只转发。 |
| `rulepack.py` | 生成规则包（分离版本 + 工具状态 + 能力核对），稳定可比较。 |
| `cli.py` | 统一入口：任务卡/recipe/run + 兼容旧只读子命令 + `version`/`tools`/`tool call`（仓库根 `qai`/`qai.cmd` 为短入口）。 |
| `mcp_server.py` | stdio MCP 适配器（JSON-RPC 2.0 最小子集），与 CLI 共用注册表与 dispatch。 |
| [test_ai_maintenance.py](../test_ai_maintenance.py) | 规则包/旧 CLI 合同回归（合成数据、127.0.0.1 stub）。 |
| [test_ai_catalog.py](../test_ai_catalog.py) | 扩展性回归：精简领域目录/按需 describe+rules、惰性模块加载、模块缺失/废弃/禁用、权限与发现分离、并发、CLI/MCP 一致性（18 项）。 |
| [test_ai_toolkit.py](../test_ai_toolkit.py) | 分块解析、能力降级、注册表/禁用、计划指纹、作者/清理/合并/资产流程。 |
| [test_ai_mcp.py](../test_ai_mcp.py) | 两个独立合成 MCP 客户端的协议、超时、取消与脱敏回归。 |
| [test_media_naming.py](../test_media_naming.py) | 命名引擎纯函数回归（工作编号/页码/补图/缺页/锁/回读）。 |
| [test_ai_platform_20261108.py](../test_ai_platform_20261108.py) | 外部 AI 工具平台回归（41 项）：备份 3MiB 流式落盘、设备 prepare/submit/status、执行器 200/204/409、研究 argv/边界/超时、回收站与标签。 |
| [test_ai_platform_20261110.py](../test_ai_platform_20261110.py) | 规则漂移（endpoint+method+必需字段、apiStatus/实现状态分离）、`platform.context` 输出/权限/CLI+MCP 一致性、media.organize scan 的对齐/去重/序号/越界与 apply planned。 |
| [test_ai_recipes.py](../test_ai_recipes.py) | recipe/原语回归：任务卡边界、工具引用、门禁与续跑、条件跳过、能力缺失、author.create、name 查重、视频抽帧/dHash 缓存与拼图。 |
| [test_ai_files_refs.py](../test_ai_files_refs.py) | 本地执行域回归（39 项）：读写根授权、未授权/穿越/链接/junction 拒绝、fileRef 生成/失效/替换、原语只读/权限/门禁、transfer 覆盖与恢复幂等、产物篡改/running 步骤/版本变化、研究结果脱敏。 |
| [test_ai_contract_consistency.py](../test_ai_contract_consistency.py) | 契约一致性：领域发现与 MCP 元工具可见性、工具状态维度、规则点路径/截断、示例 Schema、playbook/recipe 引用、files 工具契约、版本同源。 |
| [test_ai_integration_local_files.py](../test_ai_integration_local_files.py) | 主代理跨模块验收：files.search → fileRef → media.probe/file.hash → recipe 复用、失效、分页与 MCP 领域过滤。 |
| [test_ai_pr53_fixes.py](../test_ai_pr53_fixes.py) | PR #53 安全回归：原语 partial/failed 停止与断点续跑、同文件/硬链接拒绝、安全覆盖与旧目标保留、probe 抽帧权限、Gate 范围、ADB 拉取覆盖保护（模拟 ADB）。 |

生成物默认输出到 stdout；`--out` 写入文件。本目录 `.gitignore` 已忽略 `rulepack.json`；
建议写到被忽略的 `build/ai-maintenance/`。

## 安装与运行

只需要 Python 3.13 标准库。在仓库根执行：

```powershell
# 仓库根提供短入口（Windows: qai.cmd；PC/NAS: ./qai），等价于下面的 python 入口
qai task author.discover
qai run media.batch --input-file input.json
qai recipes list

python -X utf8 tools/ai_maintenance/cli.py version
python -X utf8 tools/ai_maintenance/cli.py context --markdown
python -X utf8 tools/ai_maintenance/cli.py tools list
```

`context`/`version`/`tools list`/`tools show` 纯本地：不联网、不需要 token。
注意：`context` 输出完整规则包（约 490KB，面向排障/CI）；模型上下文请优先用
`task`（精简任务卡，当前体积见下文）或 `task-context --task ...`，不要默认整包读取。

## 配置

2026-11-14 源码契约更新：工具包 `1.6.0`、工具契约 `2.0.0`、规则 `2026-11-14.1`。
默认权限改为仅 `read`；自动化写入须在可信配置中明确授予权限，并由服务端 AI scope
再次约束。旧计划须重新 prepare；`author.query` 返回分页对象（默认20条），旧
`authors` 子命令保留数组形状但默认只取一页。当前构建、安装与验收状态见 HANDOVER。

| 配置 | 默认与用途 |
|---|---|
| `QIMENG_AI_MCP_DISCOVERY` | `lazy`：只公布发现工具；context/describe/recipe 后发布相关业务工具并通知 list_changed。不支持通知的宿主显式用 `full` |
| `QIMENG_AI_OUTPUT_BUDGET_BYTES` | 12288：MCP/通用 tool call 与任务上下文的展示 result 上限；不包括协议外壳和宿主可能重复呈现的 text/structuredContent |
| `QIMENG_AI_STATE_DIR` | 私有执行端状态根；未设时结果续读用系统临时目录。完整计划保存在本机，不入源码或公开报告 |
| `QIMENG_AI_TASK_ID` | 与显式 STATE_DIR 一起配置后，同任务跨 CLI/MCP 客户端共享预算；不是模型参数，不建立第二套业务任务账本 |
| `QIMENG_AI_MAX_CALLS` / `QIMENG_AI_MAX_SECONDS` / `QIMENG_AI_MAX_UNCHANGED` | 100 / 900 / 3；含工具错误的调用计数、时间与相同请求无新结果停止。未绑定持久任务时只约束当前执行会话 |
| `QIMENG_AI_AUTHORIZATION_FILE` | 可选的可信任务授权文件，进一步收窄实例、工具、参数和有效期；模型不能靠 confirm=true 或自算指纹扩权 |

任务授权文件（占位示例；`expiresAt` 用实际 Unix 秒，不从模型参数接受）：

```json
{"version":1,"apiBase":"http://127.0.0.1:18568","expiresAt":1800000000,
 "allow":[{"tool":"author.create","arguments":{"displayName":"已授权的测试作者"}}]}
```

`arguments` 是输入字段点路径的精确匹配，例如 `plan.planFingerprint`；省略该对象
表示可信端授予该工具的范围内委托，不是动作级用户确认凭据。权限、Go scope、对象
版本及本地读写根继续生效。普通授权内可连续完成，不因内部计划步骤重新询问。

超过展示预算时返回 `truncated`、省略信息及 `resultRef`；用 `platform.result.read`
按 RFC6901 JSON Pointer（如 `/tools/0`）、游标与条数续读，每页最多12KiB。
有 `planRef` 时 apply/update 直接传 `{ "planRef":"<原引用>", "confirm":true }`，
执行端读取完整原计划再验证版本/指纹/前置条件。不要从预览或脱敏路径重造计划。
规则节原先已在 handler 摘录时截断，续读该摘录不能取回原文；因此 `platform.rules`
另支持单个 `sections` 配合 `offset=0,limit=1000`，按 `nextOffset` 读取原始规则 JSON
片段，供必要时补全规范；不会把缺失的后半段当成已读，也不将片段作为执行计划。
结果引用绑定实例、凭据摘要、工具/规则版本，24小时过期；单对象32MiB、总额256MiB、
最多128项，超限明确给出无法续读/不可执行原因。引用也不扩大原始权限。

业务 API token 与本机路径不会展示给模型；现有维护执行器的短期 fencing leaseToken
仍是原受控通道的能力参数，须由可信执行器保管，不能转发到其他模型或日志；本轮未
引入该通道的 opaque leaseRef。模型 token/费用仍以宿主实际遥测为准，缺失就标未测。

只从环境变量读取，不提供命令行 token 参数：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `QIMENG_API_BASE` | 网络工具必填 | `http://127.0.0.1:8420` 形式的 origin；不能带子路径、查询、片段或用户信息。`http` 只允许回环/私有地址，远程用 `https` 或既有隧道。 |
| `QIMENG_API_TOKEN` | 网络工具必填 | Bearer token：管理员会话 token 或独立 AI 客户端 token（推荐，见下节）；只从环境读取，不打印、不写日志、不写入规则包/结果。 |
| `QIMENG_AI_DISABLED_TOOLS` | 可选 | 逗号分隔的工具或原语 ID（如 `media.transfer`），单独禁用。 |
| `QIMENG_AI_READONLY` | 可选 | 取 `1/true` 时在发起请求/执行原语前拒绝全部有副作用工具与原语。 |
| `QIMENG_AI_OPENAPI` / `QIMENG_AI_RULES` | 可选 | 排障/验证时覆盖 OpenAPI 与规则源路径。 |
| `QIMENG_AI_MCP_TIMEOUT` | 可选 | MCP 单次工具调用超时秒数（默认 120）。 |
| `QIMENG_AI_MCP_DOMAINS` | 可选 | 逗号分隔的领域白名单；只暴露这些域的业务工具与 `files.storages`，减少误调用面。全部 `platform.*` 安全发现元工具始终可见（只读、不扩大权限）。标准 MCP 客户端仍可能加载完整 `tools/list`，此过滤不是上下文节省保证。 |
| `QIMENG_AI_TARGET` | 可选 | 运行目标：`pc` / `android-embedded` / `nas`（默认 pc）；recipe 据此核对能力。 |
| `QIMENG_AI_RECIPE_DIR` | 可选 | recipe 目录覆盖（默认 `tools/ai_maintenance/recipes`）。 |
| `QIMENG_AI_MEDIA_CACHE` | 可选 | 媒体原语缓存目录（默认 `build/ai-maintenance/cache/media`）；按 path/size/mtime/参数失效；目录不在写授权根时静默禁用缓存，不影响主流程。 |
| `QIMENG_AI_FFMPEG` / `QIMENG_AI_FFPROBE` / `QIMENG_AI_FONT` | 可选 | ffmpeg/ffprobe/拼图字体覆盖；也兼容旧的 `QIMENG_FFMPEG`/`QIMENG_FFPROBE`。 |
| `QIMENG_ADB` / `QIMENG_ADB_SERIAL` | 设备推送必填 | adb 路径与**显式真机序列号**；没有默认设备，拒绝 `emulator-5554`。 |
| `QIMENG_AI_LOCK_DIR` | 可选 | media.organize.apply 的命名锁目录（默认系统临时目录）；受限沙箱下可指向可写目录。 |
| `QIMENG_AI_RESEARCH_DIR` | 可选 | research.* 与写盘工具的忽略工作根（默认 `tools/ai_maintenance/.work/research`，已加入 `.gitignore`）；必须是绝对路径。 |
| `QIMENG_AI_READ_ROOTS` | 本地读取必填 | 逗号/分号分隔的绝对路径：允许读取/扫描的目录。未配置的目录默认拒绝；`QIMENG_AI_MEDIA_ROOTS` 同样计入读根。 |
| `QIMENG_AI_WRITE_ROOTS` | 本地写入必填 | 逗号/分号分隔的绝对路径：允许复制/整理/抽帧/拼图/备份落盘等写操作。与读根分开授权；未配置的目录默认拒绝。 |
| `QIMENG_AI_MEDIA_ROOTS` | media.organize scan 必填 | 逗号/分号分隔的绝对路径：允许 `media.organize.prepare` scan 模式只读扫描的媒体/维护根，并计入读根。未配置时拒绝本地扫描；仓库内只允许忽略工作根。 |
| `QIMENG_AI_STORAGES` | 本地文件发现必填 | `storageId=绝对路径` 形式（分号/逗号分隔），声明逻辑存储；storageId 需匹配 `^[a-z][a-z0-9_-]{1,31}$`。storage 根**本身即读授权**；写入该存储还需把根加入 `QIMENG_AI_WRITE_ROOTS`。 |
| `QIMENG_AI_GRANTED_PERMISSIONS` | 可选 | 逗号分隔的已授予权限（read/research/author.profile.write/author.cleanup/asset.authors.write/asset.write/asset.delete/trash.restore/media.organize/backup.export/device.apply/maintenance.execute/admin.maintenance/local.files.write/local.device 或 all）。未配置=仅授予 read；写入须显式配置最小权限。工具可见≠有权执行，execute_tool 与原语策略在调用前强制检查。 |

```powershell
$env:QIMENG_API_BASE = "http://127.0.0.1:8420"
$env:QIMENG_API_TOKEN = "<admin-token>"
```

真实值不要写进仓库、任务记录、结果包或外部模型上下文。缺配置时退出码为 2，
错误只列变量名，不回显值。

本地路径默认拒绝：只有显式配置的读根、写根、媒体根、storage 与包内忽略工作根
（`QIMENG_AI_RESEARCH_DIR`、`.test-temp/`、`build/`）可用。模型传参不能扩大授权。
普通下载目录的典型配置：

```powershell
$env:QIMENG_AI_READ_ROOTS = "D:\Downloads"
$env:QIMENG_AI_WRITE_ROOTS = "D:\Downloads\organized"
$env:QIMENG_AI_STORAGES = "downloads=D:\Downloads"
```

### 独立 AI 客户端 token（ADR-0057 P1，推荐）

生产/受信执行端应使用独立 AI 客户端 token，而不是管理员 Bearer：

```powershell
# 在服务端本机（PC/NAS）创建；明文 token 只输出一次，不写日志/文件/数据库
cd server
go run ./cmd/qimeng ai-client create -config <config.yaml> -label ai-executor -scopes read,author.write -device-ids phone-main
# list 查看公开信息（不含 token/hash）；rotate 轮换后旧 token 立即失效；revoke 撤销
go run ./cmd/qimeng ai-client list -config <config.yaml>
go run ./cmd/qimeng ai-client rotate -config <config.yaml> -id <clientId>
go run ./cmd/qimeng ai-client revoke -config <config.yaml> -id <clientId>
```

把 create/rotate 输出的 token 放进执行端环境变量（变量名不变）：

```powershell
$env:QIMENG_API_TOKEN = "<ai-client-token>"
```

服务端先验管理员会话、未命中再验 AI 凭据，因此同一套工具无需改代码即可切换；管理员 Bearer 仍可全权使用，但不应长期下发给模型。AI 客户端按 scope 受限：
- 只读工具需要 `read`（维护/备份/研究/设备等域使用 `maintenance.read`/`backup.export`/`research`/`device.apply`）；
- 写工具按域授权：`author.write`、`asset.author.write`、`media.organize`、`maintenance.write`、`backup.export`；删除/合并需 `author.destructive`，恢复需 `backup.restore`；
- 未映射的写请求默认 403 `FORBIDDEN_SCOPE`；可用 `-scopes` 逗号列表显式收窄（未知 scope 创建时报错）；
- Android 后台执行器使用 `device.apply` + `-device-ids phone-main`，只能访问自己绑定的设备结果包；
- AI 写请求会写入 `ai_audit`（含被拒绝的），审计失败不阻断业务；管理员请求不写；
- 撤销/轮换在服务端进程缓存 TTL（约 5 秒）内生效；本地管理命令先跑幂等 migration，不需要停服。

scope 与强制矩阵的权威说明见 [SECURITY「独立 AI 客户端凭据」](../../docs/SECURITY.md#独立-ai-客户端凭据scope-与审计adr-0057-p1p22026-11-08) 与 [ADR-0057](../../docs/adr/0057-AI客户端凭据与细粒度权限模型.md)。2026-11-08 起同时提供管理员会话专用的 HTTP 管理端点（`/api/v1/ai-clients*`、`/api/v1/ai-audit`，AI token 一律 403）；本地 CLI 继续可用。

## 本地文件、逻辑存储与 fileRef

本地文件能力与 Go 业务 API 的边界：

| 对象 | 允许方式 | 禁止 |
| --- | --- | --- |
| 未入库文件（下载/待整理） | 在显式授权的读/写根内直接搜索、哈希、抽帧、拼图、复制、命名整理 | 绕过授权根、删除源文件、默认覆盖、写入仓库源码树 |
| 已入库资产（索引/作者/标签/业务关联/正式媒体路径） | 现有 Go `/api/v1` 正式工具（计划→确认→执行→回读） | 本地工具直接写 SQLite/TXT、直接改库内文件或建立第二份业务事实 |

`files.storages` / `files.search` 只做未入库文件的发现；它们不是全盘搜索，也不替代
`asset.*` / `author.*` / `media.organize.apply`。搜索有界：默认每页 20、上限 50，
单次扫描最多 20000 个目录条目，递归深度上限 8，默认跳过符号链接/junction/隐藏项；
`truncated`/`scanned.scanLimitReached` 必须为真时才表示结果不完整。当前 cursor 无状态，每页会重新有界扫描并排序（不建索引/缓存）；超大目录应先收窄 storage/query/extensions，不要依赖连续翻页遍历全部文件。

推荐流程（不把物理路径交给模型）：

```text
1. files.storages                          → 取 storageId / writable
2. files.search {storageId, query, ...}    → 候选 + fileRef + relativePath + nextCursor
3. media.probe / file.hash {fileRef}       → 复用已定位文件（也可在 recipe 中引用）
4. 需要写入时：media.transfer {sourceFileRef, target}（target 必须是写根内路径）
```

- `fileRef` 自描述、无落盘索引：编码 storageId、相对路径、size 与 mtime；解析时重新
  校验 storage 配置、相对路径规范化、根内约束、存在性与 size/mtime。删除/替换/修改后
  返回 `FILE_REF_STALE`，重新 `files.search` 即可；伪造/越界返回 `FILE_REF_INVALID`，
  storage 未配置返回 `FILE_REF_UNAVAILABLE`。
- `fileRef` 不能绕过写授权：读只需要 storage 配置；写仍要求目标存储根在
  `QIMENG_AI_WRITE_ROOTS` 内。原 `path` 参数完全兼容，不要求一次性迁移。
- `research.media.probe` 接受 `files`（绝对路径）与 `fileRefs` 二选一或合并；fileRef
  输入在返回摘要中以 `<fileRef:N>` 占位，不回显解析后的物理路径。
- 原语与工具共用同一策略：`QIMENG_AI_READONLY`、`QIMENG_AI_DISABLED_TOOLS`、
  `QIMENG_AI_GRANTED_PERMISSIONS` 在调用前强制检查。`media.contact_sheet` 是派生图
  （`local.files.write`，无需确认）；`media.transfer`/`device.push`/`device.pull` 需要
  本次运行已通过人工 gate。默认不覆盖、不删除源；复制后回读大小与 SHA-256。
- `media.probe` 仅在**含 `frames`** 时抽帧写盘，此时按 `local.files.write` 与写根
  强制检查；纯元数据/dHash 查询保持只读。只读模式下媒体缓存写入也关闭，避免
  “声明只读、实际写缓存”。
- `media.transfer` 先判断源/目标是否同路径、硬链接或同一底层文件，命中一律
  `SOURCE_TARGET_SAME`（`overwrite=true` 也不能绕过）；覆盖时先写同目录临时文件、
  校验后原子替换，失败保留旧目标并清理临时文件；恢复时只对“目标存在且内容一致”
  幂等跳过。
- `device.pull` 默认拒绝覆盖本地已有文件；先拉临时文件、核对大小后安全发布，
  失败/链接目标/未授权根均不破坏旧文件。
- Gate 只授权其后的具体步骤（显式 `authorizes`，否则到下一道 gate 之前）；恢复时
  核对记录范围，范围变化拒绝续跑；条件跳过的 gate 沿用此前已完成 gate 的授权，
  保持既有 recipe 流程。一次 `--yes` 仍只通过一个门禁。
- recipe 恢复：`partial`/`failed` 统一停止后续写入并保留 artifact 中的成功/失败项；
  恢复时校验已完成/部分完成产物的 bytes/SHA-256；上次停在副作用步骤（`running`）
  时返回 `RECIPE_STEP_UNCERTAIN`，要求回读/人工核对后新建运行；工具或原语版本变化
  返回 `RECIPE_STATE_VERSION`。`partial` 续跑会跳过已成功的 transfer 目标与已推送的 device.push 项，只重试
  失败项。

隔离合成测量（`python -X utf8 tools/ai_maintenance/measure_locator.py --json`，60 个文件/
3 层/同名干扰项/真实 1 秒 mp4）：路径式基线 5 次调用、返回 1799 字符、暴露 2 次物理
绝对路径；fileRef 流程 3 次调用、1447 字符、0 次绝对路径暴露；两条流程均正确定位并
完成 `media.probe`（`scanStatus=ok`）。重复 probe+hash 的载荷 508→546 字符（fileRef
字符串比短路径长），收益在调用次数、绝对路径隐藏与跨步骤复用，而不是重复载荷本身。
以上为合成环境实测，不代表所有目录规模或 token 比例。

## 版本与兼容

| 版本 | 含义 | 变化时的影响 |
| --- | --- | --- |
| `platformVersion` | 工具包发行版本 | 新增/修复工具；不单独使计划失效。 |
| `toolContractVersion` | 工具 ID/Schema/错误语义契约 | 变化后旧计划被拒绝（`PLAN_VERSION_MISMATCH`）。 |
| `rulepackVersion` | `rules.json` 规则版本 | 规则变化后旧计划需重新 prepare。 |
| `openapiSha256` | OpenAPI 原始字节指纹 | 仅来源/构建追踪；**不单独决定工具失效**。 |

每个工具按自己声明的「端点 + 方法 + 请求 schema/必需字段 + 参数」独立核对：
supported / unavailable / incompatible / planned。OpenAPI 中「路径存在」不等于
工具可用；无关 API 字段变化不会让全部工具失效。

## CLI

### 兼容的只读子命令（参数/JSON/退出码保持旧契约）

```powershell
python -X utf8 tools/ai_maintenance/cli.py health [--ready]
python -X utf8 tools/ai_maintenance/cli.py libraries
python -X utf8 tools/ai_maintenance/cli.py assets [--page N | --cursor C] [--limit N] [--q TEXT]
python -X utf8 tools/ai_maintenance/cli.py authors [--page N] [--size N]
python -X utf8 tools/ai_maintenance/cli.py author-profile --author-id <authorId>
python -X utf8 tools/ai_maintenance/cli.py author-sources --author-id <authorId>
python -X utf8 tools/ai_maintenance/cli.py research context|provider|lens ...
```

`assets` 的 `--page` 只是按 `nextCursor` 顺序取页，不向服务端发送 `page` 参数；
`authors` 的 `--page/--size` 沿服务端游标取页，默认只返回一页20条。research 默认人读摘要，全局 `--json`
（子命令前后都可）输出原始 JSON。

### 通用机器可读入口

```powershell
python -X utf8 tools/ai_maintenance/cli.py version
python -X utf8 tools/ai_maintenance/cli.py tools list --json
python -X utf8 tools/ai_maintenance/cli.py tools show author.profile.update
python -X utf8 tools/ai_maintenance/cli.py tool call author.profile.read --input-json '{"authorId": "<authorId>"}'
python -X utf8 tools/ai_maintenance/cli.py tool call author.profile.prepare --input-file plan-input.json
```

### 任务卡与可执行 recipe（优先入口）

不需要先读项目文档：先取精简任务卡，按卡上的步骤/输入/门禁执行；执行到某步
再按需查看该工具 Schema。

体积与效果边界：原 2–4KB 是目标而非硬限额；2026-11-13 五张内置卡的紧凑 JSON
为 1,975–5,909 UTF-8 字节，完整 MCP 清单为 45,295 字节。按需入口已有实现，但
这是优化前快照；2026-11-14 已实现展示预算、服务端分页与续读，实际验收状态见 HANDOVER。历史证据集中在
[平台任务书 §1.5](../../docs/AI_PLATFORM_TASKBOOK.md#15-当前-ai-api-的效果上下文与待补验收2026-11-13)。

```powershell
# 1) 列配方 / 看任务卡（默认紧凑 JSON，不加载完整规则包）
python -X utf8 tools/ai_maintenance/cli.py recipes list
python -X utf8 tools/ai_maintenance/cli.py task author.discover

# 2) 执行：只读步骤先跑，遇到人工门禁停下
python -X utf8 tools/ai_maintenance/cli.py run author.discover --input-file input.json
# 3) 人工核对产物后，同一 --state-dir 带 --yes 通过“下一个”门禁；多门禁需重复确认
python -X utf8 tools/ai_maintenance/cli.py run author.discover --state-dir <runDir> --yes
```

内置配方：
- `author.discover`：未知作者——抽帧/Lens → 搜索/查重 → `author.create` → candidate 档案 → 关联 → 回读
- `author.profile`：已有作者补档案
- `media.batch`：内容扫描 → 拼图目检 → 命名/关联 → 设备推送 → 回读
- `device.sync`：备份导出 → 设备结果包登记 → 提交 → 查询

执行器把每步产物写到 `<runDir>/artifacts/*.json`（含 sha256），模型只拿到有界摘要；
写入步骤必须先过 gate 再带 `--yes`；一次 `--yes` 只通过下一个门禁，工具自身仍校验 plan 指纹与 confirm。配方是数据：
新增编排只加 `recipes/*.json`（必要时加一个原语），不改 CLI/MCP。

`tool call` 成功输出 `{"tool","toolVersion","status":"ok","result":...}`；写入工具
的结果含 `planFingerprint`/`idempotencyKey`/`steps`/`readback`。`--input-json -`
或 `--input-file -` 从 stdin 读取。

### 精简目录与按需发现（工具数增长时优先）

工具数量增长后，不建议把完整规则包和全部工具 Schema 一次性塞进模型上下文。
平台提供三层按需发现，CLI 与 MCP 共用同一注册表/执行器：

```powershell
# 1) 精简领域目录：工具 id/版本/摘要/状态/权限，不含 Schema（默认隐藏已废弃工具）
python -X utf8 tools/ai_maintenance/cli.py catalog
python -X utf8 tools/ai_maintenance/cli.py catalog --domain author --json
python -X utf8 tools/ai_maintenance/cli.py tools list --compact --json

# 2) 按任务/领域一次取相关工具、Schema 摘要、规则节、playbook 与权限
python -X utf8 tools/ai_maintenance/cli.py task-context --task media.organize
python -X utf8 tools/ai_maintenance/cli.py task-context --task author.cleanup --no-schemas
python -X utf8 tools/ai_maintenance/cli.py task-context --task backup.sync --max-tools 8

# 3) 按需取单个工具的完整 Schema/示例/规则摘录/Go API 依赖
python -X utf8 tools/ai_maintenance/cli.py tools show author.profile.prepare

# 4) 按需读取规则节，不加载完整 rules.json
python -X utf8 tools/ai_maintenance/cli.py tool call platform.rules --input-json '{"sections":["mediaNaming","permissions"]}'

# 5) 按需读取工作流模板（可选模板，不是唯一路径）
python -X utf8 tools/ai_maintenance/cli.py playbook --name media.organize
```

- 领域：author/media/research/backup/device/maintenance/system；目录按领域分组并给
  tools 扁平列表，toolCount/domains 由注册表实时计算。
- `platform.recipe`（任务卡）/ `platform.context` / `platform.catalog` /
  `platform.tool.describe` / `platform.rules` / `platform.playbook` 与业务工具同一
  dispatch、同一权限与禁用检查；MCP tools/list 可用 `QIMENG_AI_MCP_DOMAINS` 收窄，
  客户端也可调用元工具做分类发现。
- `platform.context` 只返回相关子集（默认最多 12 个工具），含工具版本、Schema 摘要、
  规则节摘录、playbook、示例、`permitted`/副作用/确认要求；不返回全量 rules/tools。
- 任务解析顺序：playbook 名 → 领域名 → 工具 ID/前缀 → 别名（`backup.sync` 等）；
  无法解析返回 `INVALID_INPUT`，不猜测。
- 惰性加载：注册表只保有 module:handler 引用，目录/状态计算不导入实现模块；首次
  执行/描述时才导入对应 tools_*.py / media_naming.py。实现模块缺失时工具状态为
  unavailable，执行返回 TOOL_NOT_IMPLEMENTED，不会假装成功。
- 权限与发现分离：catalog/describe 只声明权限；execute_tool 按
  QIMENG_AI_GRANTED_PERMISSIONS 或 rules.json toolConfig.grantedPermissions 在
  Schema/计划前强制拒绝（PERMISSION_DENIED），CLI 与 MCP 同一条路径。

### 退出码

| 退出码 | 含义 |
| --- | --- |
| 0 | 成功；apply 结果 `status=applied/already_applied`。 |
| 1 | 远程失败/HTTP 非 2xx、冲突、前置条件变化、回读不一致、apply 返回 partial/failed。 |
| 2 | 用法/参数/Schema/配置/能力不可用/未确认/未知或禁用工具/planned 工具。 |

错误输出结构化 JSON（`error` 稳定 code、`category`、`recovery`）；HTTP 错误为
`{status,error:"http_error",body,target}`，`target` 只有 host:port，任何输出不含 token。

## 工具目录

2026-11-12 增量：platform/toolContract 1.5.0、rules 2026-11-12.1；新增 `asset.upload.prepare` / `asset.upload` / `library.scan` 和 `media.ingest` recipe。工具 permission `asset.upload` 对应服务端 `read`（库/资产回读）+ `asset.upload`；`library.write` 对应 `read` + `library.write`。服务端凭据必须另行签发，工具许可不代替 scope。

`qai task media.ingest` 返回上传任务卡，`qai run media.ingest --input-file <input.json>` 接受 `{"upload":{"libraryId":"<id>","operationId":"<持久化 UUID>","fileRef":"<files.search 返回值>","directory":"","fileName":"合成 1.png"}}`；也可用授权读根内的 `path` 代替 fileRef。prepare 绑定源摘要/时间和目标；人工 gate 后上传，回读 ID/路径/大小/时间。新操作须生成并持久化 operationId（UUID），本操作重试保持不变，另一次独立操作使用新 ID。同计划的稳定 uploadId 在七天完成凭据窗口内重放，不自动重试或换键；自动改名/源变化如实报错。直接上传已入库；扫描是已投放文件的另一条入口，不能把 accepted 当 completed。备份导入仍未封装，scope 为独立的高风险 `backup.restore`。

命名规划/校验引擎 1.1.0、plan schema 2：图片与视频独立续号，类别进入指纹；补图不跨类别读取页数，混合类别的单组拒绝，旧计划需重建。全目录占用检查不变。图片各扩展名及动图共用图片序列。

云端新增 `AI API 隔离真实服务闭环`：同 revision 的临时 Go 服务、受限 AI token 与真正 stdio MCP 子进程，验证上传重放/回读、作者确认/revision、权限/只读拒绝、租约围栏与 recipe gate/恢复。没有显式 `QIMENG_TEST_SERVER_BINARY` 时该集成测试跳过，不回退 8420；执行结果以实际 CI 为准，不能用测试存在宣称已通过。

工具数较多时先用 catalog 取精简领域目录，再 tools show <id> 按需取完整 Schema/示例/
规则摘录；tools list --json 保留完整清单（MCP tools/list 同源）。这里是用途摘要。

| 工具 | 状态 | 说明 |
| --- | --- | --- |
| `project.context` | supported | 规则包/版本/工具状态（纯本地）。 |
| `health.check` | supported | healthz/readyz。 |
| `library.query` / `asset.query` / `author.query` | supported | 有界分页查询。 |
| `author.profile.read` / `author.sources.read` | supported | 读档案/来源区。 |
| `maintenance.inventory` / `maintenance.status` | supported | 只读清点与任务账本查询（不领取/不执行）。 |
| `research.context` / `research.provider.query` / `research.lens` | supported | 研究网关（服务端默认关闭，Lens 需本机 sidecar）。 |
| `author.profile.prepare` | supported | 只读生成档案计划（revision、人工字段保护、影响预览）。 |
| `author.profile.update` | supported（需确认） | 按计划完整 PUT + revision 并回读；409 不覆盖。 |
| `author.cleanup.prepare` | supported | 只读候选/显式动作清点与计划指纹；零关联不能单条件删除。 |
| `author.cleanup.apply` | supported（需确认） | 删除/改名/原子合并，逐项回读；失败保留已执行步骤。 |
| `author.merge` | supported（需确认） | confirm=false 出计划；confirm=true 校验指纹后 POST /authors/merge。 |
| `asset.authors.prepare` / `asset.authors.apply` | supported（apply 需确认） | 资产作者关联替换；校验库 `capabilities.authorAttach`。 |
| `media.organize.prepare` / `media.organize.apply` | supported（apply 需确认） | 两种 prepare 模式：`scan` 只读内容扫描（md5/可选 dHash64/视频时长+分辨率+抽帧 → 对齐/最大文件去重/序号延顺/contact sheet 计划，扫描根需 `QIMENG_AI_MEDIA_ROOTS`）；`libraryId+groups` 生成组图命名计划。apply 只执行已绑定库的命名计划（计划指纹+前置文件名指纹+并发锁+逐步 move+回读），本地 scan 计划的受控合并仍为 planned。 |
| `backup.export` | supported | `GET /export/qimeng-backup` 流式落盘：256KiB 分块写临时文件 + 原子改名，返回 `{path,bytes,sha256}`；默认拒绝覆盖，预算 128MiB/硬上限 256MiB。 |
| `device.apply.prepare/submit/status` | supported | PC 侧结果包：prepare 只读出 manifest/指纹（masterTxt ≤1MiB、v2Backup ≤64MiB），submit 重新复核源文件后 multipart 登记并回读段清单，status 列表/详情。Android Worker/配对仍未实现。 |
| `maintenance.claim` / `heartbeat` / `step.record` / `artifact.record` / `complete` | supported | 外部 AI 执行器控制面（ADR-0058）：领取租约/fencing token、续租、幂等步骤、工件元数据、终态回传；409 统一映射 `LEASE_LOST`。 |
| `research.image.pipeline` / `research.image.search` | supported | research_kit 适配：Lens→Yandex→SauceNAO 阶梯与单工具调用，只返回退出码/产物/有界摘要。 |
| `files.storages` / `files.search` | supported | 本地文件发现：授权 storage 枚举、有界搜索、分页与不透明 `fileRef`；只读、不返回物理绝对路径。 |
| `research.author.probe` / `research.media.probe` / `research.avatar.fetch` | supported | fxtwitter/kemono 公开字段、ffprobe 抽帧、头像下载；算法在 `../research_kit/`；`research.media.probe` 支持 `fileRefs`。 |
| `trash.list` / `trash.restore` | supported | 回收站只读列表与恢复（需 confirm、前置检查与回读）。 |
| `asset.trash` / `asset.tags.replace` | supported | 删除一律进回收站（回读 404）；标签整体替换（读详情出计划、确认指纹、回读）。 |
| `platform.context` / `platform.catalog` / `platform.tool.describe` / `platform.rules` / `platform.playbook` | supported | 按任务/领域取相关工具、Schema 摘要、规则节、示例与 playbook；精简领域目录；按需工具 Schema/示例/规则摘录；可选工作流模板（纯本地、只读；不写业务数据）。 |

## 计划与受控写入

固定流程：**查询现状 → 生成计划 → 校验前置 revision/版本/授权 → 确认 →
正式 API 执行 → 回读 → 结构化报告**。

```powershell
# 1) prepare：只读生成计划（含 fingerprint/idempotencyKey/影响/前置摘要）
python -X utf8 tools/ai_maintenance/cli.py tool call author.profile.prepare \
  --input-json '{"authorId":"<id>","changes":{"homepageUrl":"https://example.com/a","status":"candidate"}}'

# 2) 人工核对 plan（protectedFields、changes、impact、expectedReadback）

# 3) update：把上一步返回的 plan 原样放进 apply 输入，并 confirm=true
python -X utf8 tools/ai_maintenance/cli.py tool call author.profile.update --input-file apply-input.json
```

- 计划不含生成时刻，相同现状与输入生成相同指纹；apply 重新计算并比对指纹与版本，
  伪造/过期计划直接拒绝（`PLAN_FINGERPRINT_MISMATCH`/`PLAN_VERSION_MISMATCH`）。
- 人工已填字段默认列入 `protectedFields` 不写入；确实要覆盖时显式
  `allowProtectedOverwrite=true`。服务端无法区分「人工清空」与「从未填写」，
  空值补填需证据判断。
- apply 先重新读取现状（profile revision、作者快照、资产作者集合、目录文件名摘要），
  变化即 `CONFLICT`/`PRECONDITION_FAILED`，不静默覆盖、不盲目重试。
- 删除/合并不可撤销；执行前导出 v2 备份，失败时保留已执行步骤与回读结果。

## 安全边界

- 业务写工具全部走正式 `/api/v1`，禁止直连 SQLite/TXT、绕过领域服务或客户端拼事务；
  本地文件原语只写 `QIMENG_AI_WRITE_ROOTS` 内的未入库文件/派生图，不写业务数据，
  也不直接改已入库资产。默认不覆盖目标、不删除源文件。
- 本地读写根默认拒绝：读根/写根分离，storage 配置本身是读授权；仓库源码树仍拒绝。
  写路径的根以下链接/junction/reparse 段、`..` 逃逸与控制字符一律拒绝；复制后回读
  目标仍在写根内且 size/SHA-256 一致。
- token 只来自 `QIMENG_API_TOKEN`；CLI/MCP 参数中不允许出现 token，输出/错误/日志
  不回显 token（上游错误体也按 token 兜底脱敏）。
- 未确认计划不得执行；`QIMENG_AI_READONLY=1` 或 `rules.json` `toolConfig.readOnlyMode`
  可在本地一刀关闭全部副作用工具与原语。
- 8420 真库禁止测试写入；测试只用临时目录、合成数据与 127.0.0.1 stub。
- **权限与发现分离**：catalog/describe 只声明权限；`execute_tool` 与原语策略在
  Schema/计划/远程请求/文件操作之前按 `QIMENG_AI_GRANTED_PERMISSIONS`（或
  `rules.json` `toolConfig.grantedPermissions`）强制拒绝未授权工具/原语
  （`PERMISSION_DENIED`）。未配置时默认仅授予 read；生产执行器
  应显式收窄并审计。看见工具≠有权执行，CLI 与 MCP 走同一检查。
- **受限凭据（ADR-0057 P1 已实现）**：服务端新增独立 AI 客户端凭据与 scope 强制（`qimeng ai-client` 本地管理、`ai_audit` 写审计）。生产执行端应使用 `-scopes` 收窄的 AI token，而不是管理员 Bearer；未映射写请求默认 403，删除/合并/恢复需各自高风险 scope。工具层隐藏 token 仍不能阻止“执行环境直接调 Go API”：管理员 Bearer 仍是全权凭据，只注入可信执行端进程环境、不下发给模型，AI 写入只用于受信本机维护；模型侧可见的只是工具声明。剩余边界与路线见 [Go API 缺口与 AI 客户端权限模型](../../docs/AI_PLATFORM_API_GAPS.md)、[ADR-0057](../../docs/adr/0057-AI客户端凭据与细粒度权限模型.md)、[SECURITY](../../docs/SECURITY.md#独立-ai-客户端凭据scope-与审计adr-0057-p1p22026-11-08)。
- 本机沙箱若限制系统临时目录/目录权限，测试临时目录放在被忽略的
  `tools/ai_maintenance/.test-temp/`。

## MCP stdio 适配器

`mcp_server.py` 以 JSON-RPC 2.0 over stdio 暴露同一注册表：

- 方法：`initialize`、`notifications/initialized`、`ping`、`tools/list`、
  `tools/call`、`notifications/cancelled`（尽力取消）；未知方法 `-32601`，
  未知工具/非法参数 `-32602`，超时 `-32001`，取消 `-32800`。
- `tools/list` 的 `name/description/inputSchema` 与 CLI/注册表完全一致；
  `tools/call` 返回 `content[0].text`（结构化 JSON 文本）+ `structuredContent` +
  `isError`，业务失败（如未确认/planned/工具错误）以 `isError=true` 返回，
  协议错误以 JSON-RPC error 返回。
- 不注册任意 shell/Python/文件执行工具；不新增写入通道；写工具仍走计划/确认/
  执行/回读。
- 远程 HTTP/网络/响应错误沿用 CLI 的结构化字段（HTTP 为 status/error/body/target），
  在 MCP 中标记 isError=true；保留服务端错误码与脱敏正文，不统一吞为 INTERNAL_ERROR。
- token 由 MCP 服务进程继承环境变量，不出现在工具参数或响应中。
- 工具分类发现：默认 lazy 先返回发现工具，按任务/工具发布后通过标准 list_changed 通知；
  `full` 兼容模式返回全部工具与 `inputSchema`。任务开始时客户端可先
  调用 `platform.context`（task/domain）取相关工具、Schema 摘要、规则节与 playbook；工具较多时
  再调用 `platform.catalog`（精简领域目录）与 `platform.tool.describe` 取单个工具 Schema/
  示例，用 `platform.rules` 按需读规则节、`platform.playbook` 读可选模板；descriptor 附带
  `_meta.domain` 便于分组展示。MCP 调用独立线程执行、有副作用工具串行，调用期间仍处理取消通知。
- `QIMENG_AI_MCP_DOMAINS` 只收窄业务工具；全部 `platform.*` 与 `files.storages` 安全发现
  元工具始终可见，`tools/list` 与 `tools/call` 使用同一可见集合，不扩大执行权限。标准 MCP
  客户端通常会在初始化时加载完整 `tools/list`，因此**服务端过滤不能承诺减少客户端上下文**；
  真正的按需加载来自客户端主动调用 `platform.context` / `catalog` / `recipe`。
- `initialize` 的 instructions 已提示“先 platform.context / catalog 后 describe”的发现顺序、
  fileRef 失效需重新搜索，以及权限边界。

### MCP 客户端配置示例（占位符，不含真实 token）

```json
{
  "mcpServers": {
    "qimeng-ai": {
      "command": "python",
      "args": ["-X", "utf8", "D:/path/to/qimeng-media/tools/ai_maintenance/mcp_server.py"],
      "env": {
        "QIMENG_API_BASE": "http://127.0.0.1:8420",
        "QIMENG_API_TOKEN": "<scope-read-ai-client-token>",
        "QIMENG_AI_READONLY": "1",
        "QIMENG_AI_GRANTED_PERMISSIONS": "read"
      }
    }
  }
}
```

### 在 ZCode 添加（先只读）

按 [ZCode 官方 MCP 文档](https://zcode.z.ai/cn/docs/mcp-services)，进入「设置 → MCP 服务器 → 新建 MCP 服务器」：选择需要使用的用户/工作区作用域，名称 `qimeng-ai`，类型 `stdio（本地命令）`；命令 `python`，参数采用上面的绝对脚本路径（替换项目目录）。也可在「完整配置」模式粘贴上述 JSON，展开环境变量填入当前绮梦地址和专用只读凭据，然后启用。若 Python 不在该应用的 PATH 中，命令改为已安装 Python 3.13 的完整路径。

绮梦凭据与 Telegram BotFather token 不同，不能互填。由管理员用现有服务端二进制的 `ai-client create -config <当前配置文件> -label zcode-readonly -scopes read` 签发凭据，再在本机界面填写一次返回的 token；配置须指向当前服务实例的数据目录，不能误建另一实例。这里不自动签发真实凭据、不修改 ZCode 配置；示例只用于只读接入，不表示已允许自动整理或删除。

启用后新开/重连会话，先让 Agent 读取 `platform.catalog`，再列出媒体库以验证实际连接。通过 Telegram 操作时，选用配置了该 MCP 的同一工作区；是否正确透传工具以实际 ZCode 会话验证为准。尚未完成 ZCode 真实接入验收，不把配置示例当作兼容认证。

**验证状态**：已在协议层用两个独立合成客户端（完整 stdio 帧序列 / 直接逐条
JSON-RPC）验证 initialize、tools/list、tools/call、ping、错误、超时、取消与脱敏；
2026-11-13 已用实际 Codex App Server 0.167.0-alpha.4.1 原生 MCP 客户端完成隔离接入核验：发现 51 个工具，31 次调用（23 正常、8 预期拒绝），覆盖只读查询、参数/未知工具、404、只读及 scope 拦截、合成作者计划确认/指纹拒绝/写后回读、同计划重放、重连及撤销后 401；8 个测试凭据均撤销，进程已清理。调用由当前实现者逐次选择、未启动额外模型 turn，不计为新 AI 无源码自然语言盲测；桌面 GUI 热挂载、ZCode/Claude、全部业务工具与 NAS 仍未验收。首次继承桌面定制 node_repl 配置的独立客户端启动失败，改用仅包含本次 MCP 的进程级配置后通过，未修改用户长期配置。证据与边界见 [HANDOVER](../../docs/HANDOVER.md) 当前记录；远程接入仍遵守隧道策略。

## 独立部署与升级

AI 平台以 CLI 与 MCP stdio 两种进程形态独立运行，不监听网络端口；配置、版本兼容、升级与运行边界见
[独立 AI 平台：部署、升级与运行边界](../../docs/AI_PLATFORM_OPERATIONS.md)。新增/废弃工具只改本包与规则，不需要三端发版。

## 测试与门禁

```powershell
python -m unittest discover -s tools -p 'test_ai_*.py'
python -m unittest discover -s tools -p 'test_import_creator_profiles.py'
python -X utf8 tools/check_docs.py
```

测试覆盖：分块解析行为（含描述块标量跳过）、合成 OpenAPI 变更只让相关工具降级、
版本/指纹稳定、工具列举/禁用/只读模式、输入 Schema 非法、计划指纹与版本校验、
人工字段保护、409 传播、清理多信号候选、原子合并指纹确认、资产作者关联、旧 CLI
合同、旧脚本转发统一实现、MCP 两客户端协议与脱敏、media.organize 端到端
（11 (1)..(4) 作品编号/页码、锁内执行、回读失败与篡改计划拒绝），以及扩展性回归
（精简目录不含 Schema 且显著小于完整清单、120 个合成工具仍有界、按需 describe/rules/playbook、
惰性模块加载与缺失模块降级、废弃/禁用工具、权限与发现分离、规则版本变化、24 路并发、
CLI/MCP 目录与完整 tools list 一致）。2026-11-08 批次另覆盖：备份导出 3MiB 流式
落盘（超过旧 2MiB JSON 上限）与摘要、覆盖/预算拒绝、设备 prepare→submit（multipart
capture）→status 与源文件漂移、执行器 200/204/409 LEASE_LOST 与 token 不落错误、
研究 argv/路径边界/超时/缺失脚本、回收站与标签计划-确认-回读。2026-11-10 批次另覆盖
本地读写根/链接与 reparse 拒绝、fileRef 生成/失效/替换、原语只读/权限/门禁、recipe
产物篡改与 running 步骤、规则一致性/MCP 元工具可见性、files.search 分页与状态维度。
全部使用合成数据与回环 stub；命名引擎自身另跑 `python -m unittest test_media_naming`。
当前 `test_ai_*.py` 全套 288 项通过。

## 备份导出、设备结果包与执行器控制面

- `backup.export`：`GET /api/v1/export/qimeng-backup` 按 256KiB 分块写同目录临时
  文件并计算 SHA-256/字节数，成功后 `os.replace` 原子改名；超过预算、HTTP 错误或
  进程失败都会删除半成品。`outPath` 必须是绝对路径：仓库外任意路径，或仓库内
  被忽略工作根（`QIMENG_AI_RESEARCH_DIR`、`.test-temp/`、`build/`）。默认
  `overwrite=false`，覆盖需显式 `overwrite=true`；预算默认 128MiB、硬上限 256MiB。
  结果只含 `{path,bytes,sha256}` 等元数据，备份正文不进入工具结果。
- `device.apply.prepare` 只读：读取 masterTxt/v2Backup，按 Go 上限（1MiB / 64MiB）
  校验并计算摘要，生成 manifest（protocolVersion=1、rulesFingerprint 默认取规则包
  摘要、ttlSeconds 300s–7d）与计划指纹；不发起写请求。`device.apply.submit` 需
  `confirm=true`，重新读取并复核源文件 bytes/sha256（变化即 `PRECONDITION_FAILED`），
  再以 multipart（`manifest` JSON 字符串 + 与 kind 同名的文件段）登记并回读段清单。
  `device.apply.status` 只读列表/详情。targetDeviceId 只是不透明标签，设备配对与真机后台同步仍未验证
  （Android Worker 源码已有但未安装/真机验收），工具不宣称已完成手机应用。
- `maintenance.claim/heartbeat/step.record/artifact.record/complete` 是外部 AI 执行器
  控制面（ADR-0058）的薄封装：claim 返回 `leaseToken`、`leaseUntil` 与
  `completedSteps`（204 → `claimed=false`）；其余调用原样回传 `leaseToken`。
  **leaseToken 是 fencing token/能力凭据**：只在 claim 的工具结果出现一次，不得写入
  日志、任务记录、证据包或转发到其他模型上下文；工具错误只带 jobId/状态码，不回显
  token。服务端 409 `LEASE_LOST` 统一映射为稳定错误码 `LEASE_LOST`（退出码 1），
  不自动重领或重试旧 token；恢复时按 `completedSteps` 跳过已完成步骤。

## 研究工具包接入（research.*）

`research.image.pipeline/search`、`research.author.probe`、`research.media.probe`、
`research.avatar.fetch` 只做 research_kit 的适配，不复制识别算法：

- argv 始终是列表并以 `shell=False` 启动；工作目录固定仓库根（脚本默认缓存落
  `build/research-kit.local/`），子进程环境剔除 `QIMENG_API_*`，研究脚本拿不到服务端 token。
- 输入/输出路径必须是绝对路径；仓库内只允许被忽略工作根（`QIMENG_AI_RESEARCH_DIR`，
  默认 `tools/ai_maintenance/.work/research`，已忽略）。相对路径、源码树内路径直接拒绝。
- 超时默认 300s、上限 900s（超时 kill 并按退出码 124 报告）；stdout/stderr 各捕获
  至多 256KiB，工具结果只返回退出码、产物 `{path,sha256,bytes}` 与有界摘要，不返回完整日志。
- 方法学与预算（Lens 优先 → Yandex → SauceNAO；Lens 8s 间隔、SauceNAO 4 次/30s 与日上限）
  以 [tools/IMAGE_RESEARCH.md](../IMAGE_RESEARCH.md)、[tools/AUTHOR_RESEARCH.md](../AUTHOR_RESEARCH.md)
  与 [research_kit/README.md](../research_kit/README.md) 为准；rules.json `research` 节只做摘要与引用。

## 回收站与标签

- `trash.list` 只读；`trash.restore` 需 `confirm=true`，先确认条目仍在回收站，恢复后
  回读条目消失（同名冲突由服务端自动改名，工具不猜测实际文件名）。
- `asset.trash` = `DELETE /assets/{assetId}`：删除一律移入回收站（非物理删除），需确认
  并回读 404；永久删除只走独立管理操作。
- `asset.tags.replace`：confirm=false 先读资产详情并返回带指纹的计划；confirm=true 需
  `planFingerprint` 一致、重新读取标签摘要后 `PUT /assets/{assetId}/tags` 并回读 tagIds。
  整体替换会刷新全部标签关联时间；不新建/删除标签池条目。

## 组图命名集成（media.organize.*）

`media_naming.py`（ENGINE_VERSION=1.0.0）负责：识别分组 → 每组一个作品编号
（同 family 现有最大编号+1，补图从现有最大页+1）→ 组内 `(1)..(n)` 页码 → 计划
指纹/幂等键/预览 → `validate_plan`（锁内双检）→ `plan_steps` → `readback_report`。

- `media.organize.prepare` 只读：有界翻页 `GET /assets` 取目标目录现状（最多
  20 页 × 200），生成计划与 preview；confidence 不足或未确认时 `needs_confirmation`。
- `media.organize.apply` 需 `confirm=true`：在 `instance+library` 级
  `SerializedLocks` 内重新取现状、核对计划指纹/前置文件名指纹、逐步
  `POST /api/v1/assets/{assetId}/move`、回读实际文件名；服务端同名自动改名
  （`基名 (2).ext`）按冲突终止，不得当作成功。锁超时/失效返回 `CONFLICT`，
  stale 锁不自动删除；锁文件只写 pid/时间/引擎版本。
- 本地受限沙箱若不能在新建目录写锁文件，请设 `QIMENG_AI_LOCK_DIR` 到可写目录；
  本地 apply 集成测试会把锁目录 patch 到工作区临时目录。

## 媒体批次内容扫描（M71：根目录对齐/最大文件去重/序号延顺/拼图核验/受控合并）

`media.organize.prepare` 的 `scan` 模式是**只读**内容扫描；命名执行仍走
`libraryId+groups` 模式与 `media_naming.py`，不复制第二套命名算法。

```powershell
$env:QIMENG_AI_MEDIA_ROOTS = "<授权媒体根1>,<授权目标根2>"
python -X utf8 tools/ai_maintenance/cli.py task-context --task media.organize
python -X utf8 tools/ai_maintenance/cli.py tool call media.organize.prepare \
  --input-json '{"scan":{"root":"<授权媒体根>","targetRoot":"<授权目标根>","perceptualLimit":100}}'
```

- 内容对齐：md5（精确）+ 图片 dHash64（ffmpeg 可选）+ 视频时长/分辨率/15·50·85%
  抽帧；文件名只作来源/批次/序列线索，不作为同一内容的证据。
- 去重：重复内容保留最大文件，其余只输出 `review_remove` 候选
  （`requiresConfirmation=true`、`safety=no-auto-delete`）；SFW/NSFW、v1/v2、
  P1/P2、4K/2K、voice/unblur 等变体不因感知相似互删；跨变体只有 md5 完全一致才
  列为候选并标 `variantMismatch`。
- 序号延顺：已有 `1 (1)..1 (3)` 时新变体建议 `1 (4)`；缺页保留、未确认不重排；
  `suggestedPages` 只作建议，真正命名仍由命名引擎执行。
- 根目录对齐：共同文件内容一致时 `suggestedCopies` 给出缺失项（本地 a b /
  文件夹 a b c → 补 c）；内容不一致时 `mergeAllowed=false`、`conflicts` 以最大文件
  为准并停止批量合并。
- 拼图目检：`contactSheets` 按组给序号/文件名/页码标签，供生成带标签拼图后逐张
  确认顺序/重复/变体。
- 边界：scan 根必须在 `QIMENG_AI_MEDIA_ROOTS`（或仓库忽略工作根）内；路径规范化
  后拒绝越界、`..`、盘符/UNC 与符号链接逃逸；扫描不写文件、不移动、不删除、不请求
  业务写入 API。本地 scan 计划的受控合并仍为 planned（`apply.status=planned`）；
  不得用脚本直接写 SQLite/TXT。

## 当前未实现（planned/未验证）

- v2 导入（`backup.import`）：分段导入与回滚仍走独立管理员流程，不向 AI 开放。
- 设备配对/设备证明与 Android 真机后台同步：Worker 源码已有并通过 JVM/编译检查，但未安装、未真机验收；工具只到 PC 登记/查询与手机侧领取，不宣称已应用。
- 标签池新建/删除、标签组、任意 SQL/文件/shell 执行、通用插件市场、远程 HTTP AI API。
- 服务端最小权限/受限凭据与独立执行端隔离已完成 P1（ADR-0057）与 P2（ADR-0058：管理员会话专属 HTTP 管理/审计、按客户端限流、执行器受控领取）；管理员 Bearer 仍可绕过工具层，生产执行端应使用 scope 收窄的 AI 客户端 token（见安全边界）。
