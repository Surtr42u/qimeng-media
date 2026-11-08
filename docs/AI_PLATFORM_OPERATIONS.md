# 独立 AI 平台：部署、升级与运行边界

> 状态：2026-11-07 用户追加「独立部署与扩展」要求后的实现说明。平台代码在 [`tools/ai_maintenance/`](../tools/ai_maintenance/README.md)，业务底座仍是 Go `/api/v1`；本文不替代 [任务书](AI_PLATFORM_TASKBOOK.md)、[ADR-0056](adr/0056-独立AI接入平台架构.md) 与 [ADR-0057](adr/0057-AI客户端凭据与细粒度权限模型.md)。

## 1. 形态与进程边界

独立 AI 平台当前提供两种进程形态，**都不监听网络端口**：

| 形态 | 入口 | 用途 | 启动/停止 |
|---|---|---|---|
| CLI | `python -X utf8 tools/ai_maintenance/cli.py ...` | 人工/脚本/批处理调用；`catalog`/`tools`/`tool call`/`playbook` | 单次进程，结束即停 |
| MCP stdio | `python -X utf8 tools/ai_maintenance/mcp_server.py` | 外部 AI 客户端通过 stdin/stdout JSON-RPC 调用 | 由 MCP 客户端启动，EOF 即停 |

- HTTP 接入只保留扩展能力（未来可选），本轮不实现独立网络服务；Web/PC/Android 继续直连 Go API。**Go 侧**已提供 AI 客户端管理（`/api/v1/ai-clients`）、审计查询（`/api/v1/ai-audit`）与外部执行器通道（`/api/v1/maintenance/executor`），它们属于 Go 服务，不是 AI 平台监听端口。
- AI 平台不写 SQLite/TXT、不复制领域规则；所有业务读写走 Go `/api/v1`，长任务复用 Go 维护任务账本（不建第二套任务系统）。
- PC 与 Android 内嵌 Go 服务可各自独立运行；Android 不要求部署完整 AI 执行器，后台执行能力通过设备 Worker 与设备级令牌衔接（见 [Android 后台执行分析](android-background-execution.md)）。

## 2. 配置

全部通过环境变量/本地文件，不写入仓库：

| 变量 | 说明 |
|---|---|
| `QIMENG_API_BASE` | Go 服务 origin（本机/局域网/NAS）；远程必须 https 或既有隧道 |
| `QIMENG_API_TOKEN` | 受信执行端持有的凭据；优先使用专用 AI 客户端令牌（`/api/v1/ai-clients` 或 `qimeng ai-client` 生成）而非管理员 Bearer |
| `QIMENG_AI_GRANTED_PERMISSIONS` | 工具层权限收窄（未配置=全部声明权限，兼容旧 CLI） |
| `QIMENG_AI_DISABLED_TOOLS` / `QIMENG_AI_READONLY` | 工具禁用/只读开关 |
| `QIMENG_AI_OPENAPI` / `QIMENG_AI_RULES` | 排障时覆盖 OpenAPI/规则源路径 |
| `QIMENG_AI_LOCK_DIR` | media.organize 锁目录（受限沙箱可指向可写目录） |
| `QIMENG_AI_RATE_LIMIT_PER_MINUTE` | 单 AI 客户端每分钟请求上限（默认 600；0=关闭限流；服务端 config `ai.rate_limit_per_minute`） |
| `QIMENG_AI_RATE_LIMIT_BURST` | 令牌桶突发容量（默认 120；须为正整数；服务端 config `ai.rate_limit_burst`） |

- 工具发现默认走 `platform.catalog`（精简目录）→ `platform.tool.describe`（按需 Schema/示例）→ `platform.rules`/`platform.playbook`（按需规则/模板），不一次性加载完整规则包。
- MCP 客户端配置示例见 [tools/ai_maintenance/README.md](../tools/ai_maintenance/README.md) 的 MCP 节；示例只含占位符，不写入真实 token。

## 3. 版本与兼容

| 版本 | 来源 | 兼容规则 |
|---|---|---|
| `platformVersion` | AI 工具包 | 平台自身发布版本，独立升级 |
| `toolContractVersion` | AI 工具包 | 工具契约；计划绑定，变化即旧计划失效 |
| `rulesVersion` | `rules.json` | 规则版本；计划绑定，变化即旧计划失效 |
| `openapiSha256` | 目标 Go 实例 | 只做来源/构建追踪；单个工具按端点+方法+字段独立核对，不因无关协议变化全部失效 |
| Go API 版本 | `/api/v1/system/status` 等 | 只增协议优先；AI 平台按工具能力核对降级，不猜测旧参数 |

- 升级 AI 工具包：替换 `tools/ai_maintenance/` 后运行 `version`/`catalog` 自检；不需要重新构建三端。
- 当前工具包版本：`platformVersion` 1.1.0、`toolContractVersion` 1.1.0、`rulesVersion` 2026-11-08.1，共 43 个工具（含 `backup.export`、`device.apply.prepare/submit/status`、`maintenance.claim/heartbeat/step.record/artifact.record/complete`、研究探测/头像/回收站等新工具）；Python 侧 164 项测试通过。版本以 `version` 输出与 `rules.json` 为准。
- 升级 Go：先发布协议与实现，再按需更新 AI 工具包；工具会按 `openapiSha256`/能力核对报告 `supported/unavailable/incompatible`。
- 工具新增/废弃：只增 ToolSpec + 实现模块；废弃用 `deprecated/replacedBy`，精简目录默认隐藏，执行返回 `TOOL_DEPRECATED`。

## 4. 运行与安全

- token 只注入可信执行端进程环境，不进入模型上下文/工具参数/日志；AI 平台输出对 token 做脱敏兜底。
- 工具层权限检查（`QIMENG_AI_GRANTED_PERMISSIONS`）只是本地收窄；**最终权限由 Go 强制**。日常接入应使用专用 AI 客户端令牌（scope 最小化），管理员 Bearer 仅用于人工管理；风险背景见 ADR-0056 §5、ADR-0057 与 `rules.json toolCatalog.permissions.adminBearerRisk`。
- AI 客户端管理（创建/列表/撤销/轮换）只接受**管理员会话**；AI bearer 即使持有 `admin` scope 也返回 403 `FORBIDDEN_SCOPE`。明文 token 只在创建/轮换响应出现一次，服务端只存哈希；撤销/轮换经约 5s 内存缓存窗口传播到其他进程。
- 按客户端限流（令牌桶，默认 600/min、burst 120）超限返回 429 `RATE_LIMITED` + `Retry-After`；限流状态在进程内，多实例部署不共享配额（当前单实例形态）。
- 外部执行器只能经 `/api/v1/maintenance/executor` 领取任务，必须携带 claim 返回的 `leaseToken`；暂停/取消/终态/租约过期或被重领后旧 token 一律 409 `LEASE_LOST`，不得继续写入。`maintenance.execute` 不授予其他业务写权限。
- 高风险操作（删除/不可逆覆盖/恢复）仍走计划/确认/回读；低风险维护可在授权 scope 内自动执行。
- 审计：AI 工具输出结构化步骤/回读/错误码；Go 侧 `ai_audit` 记录 AI 写请求（含被拒绝）的客户端/动作/状态/请求 ID，并可用 `GET /api/v1/ai-audit` 查询；管理员请求不写。
- 禁止：任意 SQL、无边界文件访问、任意 shell/服务器命令；特殊管理能力只能通过明确管理功能或受限执行环境。

## 5. 部署形态（未来 NAS）

- NAS 上 Go 服务是稳定业务底座，AI 平台可独立安装目录、独立升级/回滚，不要求容器化或插件市场。
- NAS 场景可把 MCP/CLI 放在受信执行端（如 NAS 本机或运维机），通过 `QIMENG_API_BASE` 指向 NAS Go 服务；长期常驻/远程 AI 接入属于后续阶段，需要凭据隔离与网络边界评审。
- PC 与 Android 保持独立运行方式；手机端仅在后台上传/设备结果包执行等明确能力上参与，不要求完整 AI 执行器。

## 6. 本轮未实现 / 待验证

- HTTP 独立服务、常驻守护进程、系统服务/容器编排未实现（用户明确本轮不急于实现）。
- 真实 AI 客户端（Codex/Claude 等）接入、真实 Go 实例联调、安装到 PC/手机、NAS 硬件验收均未执行。
- AI 客户端 HTTP 管理、审计查询与按客户端限流已于 2026-11-08 落地；仍缺管理 UI、多实例共享限流与按执行器并发配额。外部执行器通道已交付，但只做过 Go 侧测试，未接真实执行器/真机/NAS 联调。
