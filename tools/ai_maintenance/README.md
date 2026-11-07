# 绮梦影库 AI 工具平台（R3：查询、计划与受控写入）

> 本目录是外部 AI 的**独立工具核心**：规则包 + 显式工具注册表 + 统一 CLI + 计划与
> 受控写入 + stdio MCP 适配器。仅用 Python 3.13 标准库，不安装第三方依赖。
>
> 协议以 [api/openapi.yaml](../../api/openapi.yaml) 为准；现行执行清单见
> [统一任务书 V2](../../docs/AI_PLATFORM_TASKBOOK.md)，架构决策见
> [ADR-0056](../../docs/adr/0056-独立AI接入平台架构.md)。旧
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
| `tools_asset.py` | 资产作者关联 prepare/apply。 |
| `media_naming.py` | **组图命名纯规划/校验引擎**（作品索引、`(1)..(n)` 页码、计划指纹、`SerializedLocks`、回读报告）；ENGINE_VERSION=1.0.0。 |
| `tools_media.py` | media.organize.prepare/apply 适配层：有界读目录现状、调用引擎、加锁逐步 move 并回读；缺失引擎时工具动态 planned。 |
| `profile_import.py` | 作者公开档案受控填充的**唯一写入实现**；旧脚本 `../import_creator_profiles.py` 只转发。 |
| `rulepack.py` | 生成规则包（分离版本 + 工具状态 + 能力核对），稳定可比较。 |
| `cli.py` | 统一入口：兼容旧只读子命令 + `version`/`tools`/`tool call`。 |
| `mcp_server.py` | stdio MCP 适配器（JSON-RPC 2.0 最小子集），与 CLI 共用注册表与 dispatch。 |
| [test_ai_maintenance.py](../test_ai_maintenance.py) | 规则包/旧 CLI 合同回归（合成数据、127.0.0.1 stub）。 |
| [test_ai_catalog.py](../test_ai_catalog.py) | 扩展性回归：精简领域目录/按需 describe+rules、惰性模块加载、模块缺失/废弃/禁用、权限与发现分离、并发、CLI/MCP 一致性（18 项）。 |
| [test_ai_toolkit.py](../test_ai_toolkit.py) | 分块解析、能力降级、注册表/禁用、计划指纹、作者/清理/合并/资产流程。 |
| [test_ai_mcp.py](../test_ai_mcp.py) | 两个独立合成 MCP 客户端的协议、超时、取消与脱敏回归。 |
| [test_media_naming.py](../test_media_naming.py) | 命名引擎纯函数回归（工作编号/页码/补图/缺页/锁/回读）。 |

生成物默认输出到 stdout；`--out` 写入文件。本目录 `.gitignore` 已忽略 `rulepack.json`；
建议写到被忽略的 `build/ai-maintenance/`。

## 安装与运行

只需要 Python 3.13 标准库。在仓库根执行：

```powershell
python -X utf8 tools/ai_maintenance/cli.py version
python -X utf8 tools/ai_maintenance/cli.py context --markdown
python -X utf8 tools/ai_maintenance/cli.py tools list
```

`context`/`version`/`tools list`/`tools show` 纯本地：不联网、不需要 token。

## 配置

只从环境变量读取，不提供命令行 token 参数：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `QIMENG_API_BASE` | 网络工具必填 | `http://127.0.0.1:8420` 形式的 origin；不能带子路径、查询、片段或用户信息。`http` 只允许回环/私有地址，远程用 `https` 或既有隧道。 |
| `QIMENG_API_TOKEN` | 网络工具必填 | 管理员 Bearer token；只从环境读取，不打印、不写日志、不写入规则包/结果。 |
| `QIMENG_AI_DISABLED_TOOLS` | 可选 | 逗号分隔的工具 ID，单独禁用工具。 |
| `QIMENG_AI_READONLY` | 可选 | 取 `1/true` 时在发起请求前拒绝全部有副作用工具。 |
| `QIMENG_AI_OPENAPI` / `QIMENG_AI_RULES` | 可选 | 排障/验证时覆盖 OpenAPI 与规则源路径。 |
| `QIMENG_AI_MCP_TIMEOUT` | 可选 | MCP 单次工具调用超时秒数（默认 120）。 |
| `QIMENG_AI_LOCK_DIR` | 可选 | media.organize.apply 的命名锁目录（默认系统临时目录）；受限沙箱下可指向可写目录。 |
| `QIMENG_AI_GRANTED_PERMISSIONS` | 可选 | 逗号分隔的已授予权限（read/research/author.profile.write/author.cleanup/asset.authors.write/media.organize/admin.maintenance 或 all）。未配置=授予全部已声明权限（兼容旧 CLI）；生产执行器应显式收窄。工具可见≠有权执行，execute_tool 在调用前强制检查。 |

```powershell
$env:QIMENG_API_BASE = "http://127.0.0.1:8420"
$env:QIMENG_API_TOKEN = "<admin-token>"
```

真实值不要写进仓库、任务记录、结果包或外部模型上下文。缺配置时退出码为 2，
错误只列变量名，不回显值。

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
`authors` 的 `--page/--size` 是客户端切片。research 默认人读摘要，全局 `--json`
（子命令前后都可）输出原始 JSON。

### 通用机器可读入口

```powershell
python -X utf8 tools/ai_maintenance/cli.py version
python -X utf8 tools/ai_maintenance/cli.py tools list --json
python -X utf8 tools/ai_maintenance/cli.py tools show author.profile.update
python -X utf8 tools/ai_maintenance/cli.py tool call author.profile.read --input-json '{"authorId": "<authorId>"}'
python -X utf8 tools/ai_maintenance/cli.py tool call author.profile.prepare --input-file plan-input.json
```

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

# 2) 按需取单个工具的完整 Schema/示例/规则摘录/Go API 依赖
python -X utf8 tools/ai_maintenance/cli.py tools show author.profile.prepare

# 3) 按需读取规则节，不加载完整 rules.json
python -X utf8 tools/ai_maintenance/cli.py tool call platform.rules --input-json '{"sections":["mediaNaming","permissions"]}'

# 4) 按需读取工作流模板（可选模板，不是唯一路径）
python -X utf8 tools/ai_maintenance/cli.py playbook --name media.organize
```

- 领域：author/media/research/backup/device/maintenance/system；目录按领域分组并给
  tools 扁平列表，toolCount/domains 由注册表实时计算。
- 元工具 platform.catalog / platform.tool.describe / platform.rules / platform.playbook 与业务工具同一
  dispatch、同一权限与禁用检查；MCP tools/list 仍返回完整工具清单（标准协议兼容），
  客户端也可调用元工具做分类发现；descriptor 的 _meta.domain 便于客户端分组。
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
| `media.organize.prepare` / `media.organize.apply` | supported（apply 需确认） | 组图命名规划/校验/执行/回读；算法唯一实现 `media_naming.py`（随本发行物提供），prepare 只读计划、apply 校验计划指纹+前置文件名指纹+并发锁并逐步 move 后回读，服务端自动改名判失败。 |
| `backup.export` | planned | 大响应/落盘摘要/预算未实现，不假装执行。 |
| `device.apply.prepare/submit/status` | planned | 服务端结果包 API 已有，Android Worker/配对未实现。 |
| `platform.catalog` / `platform.tool.describe` / `platform.rules` / `platform.playbook` | supported | 精简领域目录、按需工具 Schema/示例/规则摘录、可选工作流模板（纯本地、只读；不写业务数据）。 |

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

- 只读工具绝不写；写工具全部走正式 `/api/v1`，禁止直连 SQLite/TXT、绕过领域服务
  或客户端拼事务。
- token 只来自 `QIMENG_API_TOKEN`；CLI/MCP 参数中不允许出现 token，输出/错误/日志
  不回显 token（上游错误体也按 token 兜底脱敏）。
- 未确认计划不得执行；`QIMENG_AI_READONLY=1` 或 `rules.json` `toolConfig.readOnlyMode`
  可在本地一刀关闭全部副作用工具。
- 8420 真库禁止测试写入；测试只用临时目录、合成数据与 127.0.0.1 stub。
- **权限与发现分离**：catalog/describe 只声明权限；`execute_tool` 在 Schema/计划/远程请求
  之前按 `QIMENG_AI_GRANTED_PERMISSIONS`（或 `rules.json` `toolConfig.grantedPermissions`）
  强制拒绝未授权工具（`PERMISSION_DENIED`）。未配置时默认授予全部已声明权限只是兼容旧
  CLI；生产执行器应显式收窄并审计。看见工具≠有权执行，CLI 与 MCP 走同一检查。
- **管理员 Bearer 绕过风险（已知边界，ADR-0056 §5、ADR-0057 方案）**：当前 `/api/v1` 仍以管理员 Bearer
  为主，AI 工具层隐藏 token 只能约束“通过工具调用”的模型；如果模型/执行环境能拿到 token，
  它可以直接调用 Go API 绕过工具层的计划、确认与回读。因此：token 只注入可信执行端进程
  环境、不下发给模型；AI 写入目前只用于受信本机维护，不向不可信模型开放；正式开放前需
  实现受限凭据（服务端 scope/最小权限）或不可绕过的执行端隔离（方案见 [Go API 缺口与 AI 客户端权限模型](../../docs/AI_PLATFORM_API_GAPS.md)、[ADR-0057](../../docs/adr/0057-AI客户端凭据与细粒度权限模型.md)；见 toolCatalog.permissions）。
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
- token 由 MCP 服务进程继承环境变量，不出现在工具参数或响应中。
- 工具分类发现：标准 `tools/list` 仍返回全部工具与 `inputSchema`；工具较多时客户端可先
  调用 `platform.catalog`（精简领域目录），再 `platform.tool.describe` 取单个工具 Schema/
  示例，用 `platform.rules` 按需读规则节、`platform.playbook` 读可选模板；descriptor 附带
  `_meta.domain` 便于分组展示。MCP 调用独立线程执行、有副作用工具串行，调用期间仍处理取消通知。
- `initialize` 的 instructions 已提示“先 catalog 后 describe”的发现顺序与权限边界。

### MCP 客户端配置示例（占位符，不含真实 token）

```json
{
  "mcpServers": {
    "qimeng-ai": {
      "command": "python",
      "args": ["-X", "utf8", "tools/ai_maintenance/mcp_server.py"],
      "env": {
        "QIMENG_API_BASE": "http://127.0.0.1:8420",
        "QIMENG_API_TOKEN": "<admin-token>"
      }
    }
  }
}
```

**验证状态**：已在协议层用两个独立合成客户端（完整 stdio 帧序列 / 直接逐条
JSON-RPC）验证 initialize、tools/list、tools/call、ping、错误、超时、取消与脱敏；
Codex/Claude 等真实 AI 客户端接入尚未实测。远程接入遵守既有隧道策略，不新增监听面。

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
CLI/MCP 目录与完整 tools list 一致）。全部使用合成数据与回环 stub；命名引擎自身另跑
`python -m unittest test_media_naming`。当前 tools 全套 205 项通过。

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

## 本切片明确未实现（planned）

- `backup.export`：大响应、落盘摘要与预算流程未实现。
- `device.apply.*`：服务端结果包 API 已存在，Android Worker/配对/真机后台同步未实现。
- 标签写入、任意 SQL/文件/shell 执行、通用插件市场、远程 HTTP AI API。
- 服务端最小权限/受限凭据与独立执行端隔离：当前管理员 Bearer 仍可绕过工具层（见安全
  边界与 rules.json toolCatalog.permissions.adminBearerRisk），需在正式开放 AI 写入前落地。
