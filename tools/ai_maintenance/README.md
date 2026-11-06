# AI 维护规则包与只读查询 CLI（P1）

> 本目录是外部 AI 维护的 P1 切片：规则包 + 只读查询 CLI。**没有任何写入命令**；作者档案保存、删除/改名/改挂/合并、v2 导入、手机结果包与 MCP 适配器均标记为 planned。
>
> 协议以 [api/openapi.yaml](../../api/openapi.yaml) 为准；领域规则以 [任务书](../../docs/AI_MAINTENANCE_TASKBOOK.md) 第 4–6 节和 [ADR-0052](../../docs/adr/0052-外部AI维护需求修订.md) 为准；作者字段用法见 [docs/api/authors.md](../../docs/api/authors.md)。

## 文件

| 文件 | 作用 |
| --- | --- |
| `rules.json` | 唯一手写领域规则源；字段名与 OpenAPI/任务书一致。 |
| `rulepack.py` | 读取规则源与 OpenAPI，生成稳定规则包；含路径提取、指纹、端点核对与中文指南渲染。 |
| `cli.py` | `argparse` + `urllib.request` 的只读命令行；无第三方依赖。 |
| [test_ai_maintenance.py](../test_ai_maintenance.py) | unittest；仅使用临时目录与回环 HTTP stub。 |

生成的规则包默认输出到 stdout；使用 `--out` 时写入文件。生成物不提交，本目录的 `.gitignore` 已忽略 `rulepack.json`；建议写到被忽略的 `build/ai-maintenance/`。

## 安装与运行

只需要 Python 3.13 标准库，不需要 `pip install`。在仓库根执行：

```powershell
python -X utf8 tools/ai_maintenance/cli.py context --markdown
```

`context` 是纯本地命令，不联网，也不要求 token；其余命令通过正式 API 只读查询。

## 配置

只从环境变量读取，不提供命令行 token 参数：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `QIMENG_API_BASE` | 网络命令必填 | 形如 `http://127.0.0.1:8420` 的 origin；不能带子路径、查询、片段或用户信息。`http` 只允许回环/私有地址，远程用 `https` 或既有隧道。 |
| `QIMENG_API_TOKEN` | 网络命令必填 | 管理员 Bearer token；只从环境读取，CLI 不打印、不写日志、不写入规则包。 |

PowerShell 当前会话示例（占位符不是真实凭据）：

```powershell
$env:QIMENG_API_BASE = "http://127.0.0.1:8420"
$env:QIMENG_API_TOKEN = "<admin-token>"
```

bash 示例：

```bash
export QIMENG_API_BASE="http://127.0.0.1:8420"
export QIMENG_API_TOKEN="<admin-token>"
```

真实值不要写进仓库、任务记录、结果包或外部模型上下文；需要本机持久配置时放被忽略的 `*.local` 文件或启动脚本。缺少任一变量时 CLI 退出码为 2，错误只列变量名，不回显值。

## 只读命令

### context

```powershell
python -X utf8 tools/ai_maintenance/cli.py context
python -X utf8 tools/ai_maintenance/cli.py context --markdown
python -X utf8 tools/ai_maintenance/cli.py context --out build/ai-maintenance/rulepack.json
```

- 默认输出规则包 JSON（含 `guide` 中文摘要），可被机器解析。
- `--markdown` 输出简明中文指南；与 `--out` 可同时使用（文件总是 JSON）。
- `--openapi`、`--rules` 只用于排障/验证其他 checkout，普通使用不需要。

### health / libraries

```powershell
python -X utf8 tools/ai_maintenance/cli.py health
python -X utf8 tools/ai_maintenance/cli.py health --ready
python -X utf8 tools/ai_maintenance/cli.py libraries
```

`health` 默认 `GET /api/v1/healthz`，`--ready` 使用 `GET /api/v1/readyz`。即使探针免鉴权，CLI 也按统一配置发送 Bearer 头；这不改变服务端的公开探针语义。

### assets

```powershell
python -X utf8 tools/ai_maintenance/cli.py assets --limit 20 --q "合成关键词"
python -X utf8 tools/ai_maintenance/cli.py assets --page 2 --size 20 --q "合成关键词"
python -X utf8 tools/ai_maintenance/cli.py assets --cursor "<nextCursor>" --limit 20
python -X utf8 tools/ai_maintenance/cli.py assets --size 20 --library-id "<libraryId>" --media-type image
```

当前 OpenAPI 的资产列表是 **cursor + limit**，没有 `page` 参数。CLI 接受现有参数的最小子集：`--limit`（别名 `--size`，1..200）、`--cursor`、`--q`、`--library-id`、`--media-type`、`--sort`、`--order`。

`--page` 是本 CLI 的便利选项：按响应里的 `nextCursor` 顺序取第 N 页，最多 50 页；它**不会**向服务端发送 `page` 参数。`--page` 与 `--cursor` 不能同时使用；直接翻页（或超过 50 页）请使用 `--cursor`。

### authors

```powershell
python -X utf8 tools/ai_maintenance/cli.py authors
python -X utf8 tools/ai_maintenance/cli.py authors --page 1 --size 50
```

`GET /api/v1/authors` 当前返回全量数组，OpenAPI 没有分页参数；`--page`/`--size` 只在本地对结果切片（`--page` 从 1 开始，`--size` 上限 1000；只给 `--page` 时默认 50），不会改变服务端请求。

### author-profile / author-sources

```powershell
python -X utf8 tools/ai_maintenance/cli.py author-profile --author-id "<authorId>"
python -X utf8 tools/ai_maintenance/cli.py author-sources "<authorId>"
```

两者都只是 `GET`：档案返回 revision/字段/候选，来源返回来源词列表；不包含 PUT。

### 输出与退出码

- 成功：上游 JSON（或 `null`）输出到 stdout；退出码 0。
- HTTP 非 2xx：stderr 输出 `{status,error:"http_error",body,target}`；退出码 1。
- 超时/网络错误：stderr 输出 `{error:"timeout"|"network_error",target}`；退出码 1。
- 响应非 JSON/过大：stderr 输出固定错误类别；退出码 1。
- 配置缺失/非法、参数约束、规则包生成失败：退出码 2。
- `target` 只显示 host:port；任何输出都不含 token。CLI 禁用环境代理与重定向，单请求超时 20 秒、响应上限 2 MiB。

## 规则包与指纹

`rulepack.py` 生成 JSON 对象，必需键：

`schemaVersion`、`rulesVersion`、`openapiSha256`、`paths`、`capabilities`、`fields`、`authorActions`、`tags`、`backup`、`errors`、`workflows`、`permissions`；另含 `guide` 中文摘要。

- `openapiSha256`：`api/openapi.yaml` **原始字节**的 SHA-256；不用格式化后的文本。
- `paths`：用简单行解析提取的当前 `/api/v1` 路径，排序去重；不依赖 YAML 库。
- 不写 `generatedAt`：同一 OpenAPI 字节与同一 `rules.json` 生成结果稳定，指纹不会因生成时刻漂移。
- `capabilities.read` 是本切片实际支持状态；`capabilities.write` 全部为 `planned`。
- 端点若不在当前 OpenAPI 中，规则包会把 `supported/available` 降级为 `planned`，并置 `endpointPresent=false`；不编造不存在的端点。
- 当前 OpenAPI 若已包含 `POST /api/v1/authors/merge`（本批次并发加入），规则包会把 `authorActions.merge.apiStatus` 标为 `available`，但 `authorActions.merge.status` 和 `capabilities.write.author.merge.status` 仍为 `planned`：本 CLI 不执行合并。若目标 checkout 尚未包含该端点，会自动回到 `planned`。
- OpenAPI 或规则源变化后，重新运行 `context`；不要手工编辑 `api/openapi.yaml` 或生成物。

运行 `context` 会输出实际 `rulesVersion`、`openapiSha256` 与路径数；不要把某一时点的哈希写死到部署脚本或任务记录，执行前重新读取。当前本切片规则版本为 `2026-11-06.1`。

## AI 客户端接入模板

### 给模型的系统提示（占位符）

```text
你是绮梦影库的维护助手。开始前先调用 project.context 读取规则包与 openapiSha256。
只使用规则包 capabilities.read 中声明 supported 的只读工具；写入能力均为 planned，
不得声称已执行写入，也不得编造端点、字段或执行结果。
先查现状、保留人工字段；发现 409 时重读并说明冲突，不盲目覆盖。
不写标签、不直连数据库，不把 token、真实媒体或个人信息放进输出。
```

### 工具映射

| 目标工具名 | 本切片实现 | 客户端命令 |
| --- | --- | --- |
| `project.context` | 支持（本地） | `python -X utf8 tools/ai_maintenance/cli.py context` |
| `health.check` | 支持 | `... health` |
| `library.query` | 支持 | `... libraries` |
| `asset.query` | 支持（有界 cursor/limit） | `... assets --limit 20 [--q ...]` |
| `author.query` | 支持 | `... authors [--page ... --size ...]` |
| `author.profile.read` | 支持 | `... author-profile --author-id <authorId>` |
| `author.sources.read` | 支持 | `... author-sources <authorId>` |
| `author.cleanup.prepare` | planned | 暂无；不要用本地脚本猜测影响数量 |
| `author.profile.update` | planned | 暂无写入命令 |
| `author.cleanup.apply` | planned | 暂无写入命令 |
| `device.apply.prepare/submit/status` | planned | 暂无；手机侧协议未实现 |
| `mcp.adapter` | planned | 见下方占位说明 |

工具适配层只做参数校验、调用和结果透传，不复制 `rules.json` 中的领域规则；模型输出先经计划/校验器，再交给正式 API。

### 云端模型代理运行器

- 模型 API key 只由代理运行器持有；工具进程只拿到 `QIMENG_API_BASE` 与 `QIMENG_API_TOKEN`，不把模型 key 传给工具。
- 运行器负责持久化任务 ID、计划指纹与逐项结果；这些记录放被忽略的本地目录，不写仓库。
- 工具输出中的真实作者名、媒体名、路径与证据链接属于用户数据；导出给外部模型前按任务范围脱敏，至少去掉 token、内网地址和本机绝对路径。
- 客户端暂时不能连接工具时，可先运行 `context --markdown` 并导出所选只读命令的 JSON，交给可信执行器复核；不要手工编造执行结果。

### 未来 MCP 适配占位（未实现）

计划中的 MCP 适配器只把上述工具映射到本 CLI 的 stdio 调用，不重新实现领域规则，也不新增写入通道。建议：

1. MCP 服务进程继承 `QIMENG_API_BASE`/`QIMENG_API_TOKEN` 环境变量，不把 token 放进工具参数或日志。
2. `project.context` 返回 `context` 的 JSON；查询工具返回对应子命令的 JSON；错误保留退出码/错误类别。
3. 写入工具在 P2 前不注册；即使未来注册，也必须先有计划、指纹、确认和回读。
4. 远程接入遵守现有隧道策略，不新增监听面。

当前仓库**没有** `mcp_server.py`；不要按不存在的文件配置客户端。本节只是接口边界说明。

## 本切片明确未实现（planned）

- 任何写入：作者档案 PUT、删除、改名、改挂、合并、TXT 导入/重建、标签写入。
- 作者清理 `prepare/apply`、备份导出/导入、手机结果包与后台 worker、两代分代目录。
- 标签查询命令（标签只读且本轮不提供 CLI）、MCP server、Web 任务中心、持久任务队列。
- 不写入标签；标签保持现行明文存储、FTS5、唯一约束与关联语义。

## 测试与门禁

```powershell
python -m unittest discover -s tools -p 'test_ai_maintenance.py'
python -X utf8 tools/check_docs.py
python -X utf8 tools/check_privacy.py --mode worktree
```

测试只使用临时目录和 `127.0.0.1` 随机端口的 `http.server` stub，合成作者/URL/token；不请求真实 8420、不访问外网、不读写真实库。规则包测试覆盖必需键、指纹正确性、同字节稳定性、OpenAPI 变化导致指纹变化、未知端点降级；CLI 测试覆盖 context、缺配置退出码 2、health/libraries 的 URL 与 Authorization、assets 参数与 --page 跟随 cursor（不发送 page）、authors 本地分页、HTTP 错误 body、超时/网络分类、重定向拒绝与未知参数拒绝。

## 与权威文档的关系

- 协议字段/路径：以 [OpenAPI](../../api/openapi.yaml) 为准；规则包只读取，不修改。
- 字段上限/核验规则：以 [任务书](../../docs/AI_MAINTENANCE_TASKBOOK.md) 第 5.1 节和 [作者 API 说明](../../docs/api/authors.md) 为准；`rules.json` 是其机器可读摘要。
- 权限、凭据与跨端边界：以 [任务书](../../docs/AI_MAINTENANCE_TASKBOOK.md) 第 6 节、[ADR-0052](../../docs/adr/0052-外部AI维护需求修订.md) 和[安全专题](../../docs/SECURITY.md)为准。
- 规则包随发行物自包含交付；本页只解释用法与排障，不替代协议正文。
