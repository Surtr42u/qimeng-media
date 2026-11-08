# Go 业务 API 缺口与 AI 客户端权限模型（方案）

> 状态：2026-11-07 用户追加长期架构要求后的**方案文档**。P0 只收尾现有代码与测试；**P1 首批（AI 客户端凭据/scope/审计/设备级绑定）已于 2026-11-07 落地**。**P2 首批已于 2026-11-08 落地**：AI 客户端 HTTP 管理端点、审计查询、按客户端限流（G1/G2/G8 的查询与限流面）与外部执行器受控领取通道（G4，ADR-0058）。剩余缺口见第 3 节标注。
> 决策记录见 [ADR-0057](adr/0057-AI客户端凭据与细粒度权限模型.md)。现行协议仍以 [api/openapi.yaml](../api/openapi.yaml) 为准；本文不替代协议、领域或安全专题。
> 原则：Go 业务 API 是稳定、完整、可组合的业务底座；独立 AI 工具平台（[`tools/ai_maintenance/`](../tools/ai_maintenance/README.md)）只做工具适配、发现、规则与编排，不复制领域逻辑；权限最终由 Go 强制。

## 1. 目标与边界

- 让**可信 AI** 在授权范围内自由调用、组合、调度现有业务能力；不把所有工作流写死为唯一路径，预制工作流只是可选模板。
- 细化 Go 侧权限：专门的 AI 客户端凭据、授权范围（scope）、可撤销与审计；不再以全局管理员 Bearer 作为长期唯一授权方式。
- 低风险操作（常规作者资料补全、媒体关联、命名整理）可在授权范围内自动执行；高风险操作（删除、不可逆覆盖、数据库恢复、批量改名/合并）需要单独授权策略与确认。
- 不提供任意 SQL、无边界文件访问、任意服务器命令执行；特殊管理能力只能通过明确的管理功能或受限执行环境。
- 本 PR **不实现**以下 Go 变更（不阻塞现有交付）；按第 6 节分阶段落地，每阶段独立协议/迁移/测试/回退。

## 2. 现有 Go API 能力复核（按域）

已实现且 AI 工具层正在复用（不改协议即可满足当前需求）：

| 域 | 现有能力（OpenAPI 证据） | 对 AI 的可用性 |
|---|---|---|
| 作者 | 列表/详情/新建/改名/删除、档案 GET/PUT（含 revision、人工字段、candidate/verified、X 原文/译文）、来源区、原子合并、镜像配置、候选/更新 | 完整；AI 工具层已封装 prepare/update/cleanup/merge/sources |
| 媒体/资产 | 分页查询（q/筛选/排序/游标）、详情、上传（直传/分片/断点）、移动、回收站、标签、作者关联、name-suggestions、缩略图/直链 | 完整（批量移动靠循环调用；当前工具层已串行+回读） |
| 库/目录 | 库列表、目录树、扫描状态、配置 | 完整 |
| 文件/备份 | 上传归档、本地同步、v2 备份导出/导入/列表/删除/校验、冷备份工具（管理员） | 备份导出 API 存在；AI 工具 `backup.export` 已实现（流式落盘 + sha256/bytes 预算，避大响应） |
| 后台任务 | 维护任务账本（创建/列表/详情/暂停/恢复/取消）、Go Worker（租约/心跳/逐步记录/恢复）、外部执行器领取通道（ADR-0058）、设备结果包 API | 账本、Worker 与受控执行器通道均可直接复用；`maintenance.execute` 只开放执行面，不授予其他业务写 |
| 研究 | provider/Lens 网关（默认关闭、SSRF 边界）、研究上下文 | 可选；默认关闭符合安全要求 |
| 鉴权/审计 | 管理员登录、多设备会话 token（ADR-0021）、AI 客户端凭据/scope（ADR-0057 P1）、AI 写操作审计与查询、client-logs、系统状态/指标 | 已有专用 AI 客户端身份、scope 强制与审计查询；管理 UI 与动作级确认凭据仍缺 |

**结论：基础业务操作不是主要缺口；真正缺口集中在“AI 客户端身份/权限/审计”与“外部执行器与高风险操作确认”。** 现有接口不重复实现。

## 3. 缺口清单（按必要性排序）

| 编号 | 缺口 | 现状与影响 | 建议（最小必要） | 优先级 |
|---|---|---|---|---|
| G1 | AI 客户端凭据与 scope | 原缺口：只有管理员 Bearer/用户会话，AI 执行器必须持有全局管理员 token，无法最小授权、按工具限制或按实例撤销。**P1 已实现** `ai_clients` 凭据 + scope 服务端强制 + 设备级绑定；**P2 已实现** `/api/v1/ai-clients` HTTP 管理（仅管理员会话；创建/列表/撤销/轮换，明文 token 只返回一次） | 新增 AI 客户端概念：客户端 ID + 可轮换密钥哈希 + scope 集合 + 过期/撤销 + 最后使用时间；独立鉴权中间件与审计；token 与用户会话表分离 | **已实现**（P1+P2） |
| G2 | 业务操作审计与归属 | 原缺口：仅 client-logs/系统指标，作者/媒体写入没有按 AI 客户端归因的审计。**P2 已实现**：`ai_audit` 只增写审计（AI 写请求含被拒绝；管理员不写），并有 `GET /api/v1/ai-audit` 只读查询（clientId/action 过滤、倒序 keyset、仅元数据） | 追加只增审计表（actor/客户端/动作/目标/结果/时间/请求 ID），写路径同事务或异步可回读；管理端只读查询 | **已实现**（查询面无 UI） |
| G3 | 服务端强制高风险确认 | 当前“需确认/计划指纹”只在 AI 工具层；若持有 token 可绕过工具层直调 Go | 高风险端点支持显式确认凭据（短期确认 token/幂等键绑定动作与目标）或独立 scope（如 `author.delete` 与 `author.write` 分离）；不改变低风险自动执行 | P2（部分：已有 `author.destructive`/`backup.restore`/`admin` 独立 scope，动作级确认凭据未做） |
| G4 | 外部执行器领取协议 | 原缺口：维护任务 Claim/Heartbeat/Complete 仅包内原语，外部 AI 执行器无受控领取通道。**2026-11-08 已实现**（ADR-0058）：`/api/v1/maintenance/executor` 的 claim/heartbeat/steps/artifacts/complete，服务端生成 fencing token，暂停/取消/终态/过期/重领即失效 | 在账本之上设计受限领取/续租/完成通道（客户端凭据 + 任务绑定 + 幂等），先内部 Worker 桥接；不向普通客户端开放 | **已实现**（剩余：按执行器并发配额、推送通知） |
| G5 | 外部 AI 大批量组合操作 | 资产移动/作者关联为单对象端点；批量需循环，冲突/部分失败由调用方处理 | 优先保持单对象基础操作（AI 可组合）；仅在真实批量场景出现性能/一致性问题时加批量端点（复用领域服务，不复制逻辑） | P3 |
| G6 | 设备身份/配对 | `targetDeviceId` 仅标签；跨设备结果包可误领/冒用 | 设备配对与凭据（独立于用户会话），过期与撤销，领取/回传绑定设备 | P3（与 R6 同步） |
| G7 | 作品/组图/剧集数据模型 | 作品编号只在文件名约定；无作品/季度/剧集实体 | 当前不建第二套数据模型；未来影视/动漫需求正式进入 Go 领域设计（迁移只增），AI 层只消费 | 未来 |
| G8 | 限流与费用/资源预算 | 仅上传容量/全局预算；AI 高频调用可能压垮服务。**2026-11-08 已实现按客户端限流**：令牌桶默认 600/min、突发 120，超限 429 `RATE_LIMITED` + `Retry-After`；`QIMENG_AI_RATE_LIMIT_PER_MINUTE`/`QIMENG_AI_RATE_LIMIT_BURST`，0 关闭 | 按 AI 客户端限流/并发配额（复用现有资源预算配置），审计记录预算使用 | P2（限流已实现；并发配额与多实例共享未实现） |

## 4. AI 客户端权限模型（建议）

### 4.1 凭据

- 新增 `ai_clients`：`id`、名称、密钥哈希（只在创建时返回一次明文）、状态、created/expires/revoked、last_used_at；
- 新增 `ai_client_scopes`：客户端 → scope（可多条）；
- 鉴权：`Authorization: Bearer <ai-client-key>` 或专用 header；服务端解析为**受限身份**，与用户会话/管理员 token 走不同中间件分支，禁止互相升级；
- 撤销：删除/吊销客户端或 scope 立即生效；密钥可轮换（新旧并存窗口可控）。

### 4.2 Scope 设计（与现有工具权限对齐）

| scope | 允许基础操作 | 风险 |
|---|---|---|
| `read` | 库/资产/作者/标签/统计/任务只读查询 | 低 |
| `research` | 研究 gateway 只读（服务端仍默认关闭） | 低 |
| `author.write` | 作者档案更新（revision 保护）、来源区维护 | 中（可自动） |
| `asset.author.write` | 资产作者关联替换 | 中（可自动） |
| `media.organize` | 移动/命名（不删除原件） | 中（可自动，需回读） |
| `backup.export` | 备份导出/列表（不含恢复） | 中 |
| `maintenance.read` / `maintenance.write` / `maintenance.execute` | 任务查询 / 创建与取消 / 外部执行器领取、步骤、工件与终态 | 低/中/中（执行面受租约围栏） |
| `author.destructive` | 删除作者、合并、批量改名（不可逆） | 高（单独 scope + 确认） |
| `backup.restore` / `admin` | 恢复、迁移、管理员诊断 | 高（默认不授予 AI；仅管理员显式操作） |

低风险“自动执行”= 客户端持有对应写 scope 且动作幂等/可回读/有审计；高风险“单独授权”= 独立 scope + 动作级确认凭据/管理端开关。预置工作流（如作者资料链、组图命名）只是模板，AI 可用基础工具自行组合。

### 4.3 强制点

- HTTP 中间件：认证 → 身份类型（用户/AI 客户端）→ scope 检查 → 路由 handler；未授权 403 `FORBIDDEN_SCOPE`，错误体不含敏感信息。
- 领域/服务层：高风险动作再检查独立 scope 与确认参数；不能只靠 handler。
- 审计：所有写操作记录 actor/client/action/target/result/requestId；AI 客户端额外记录 scope 与计划/确认 ID。
- 速率与预算：按客户端限流、并发与资源预算；超限 429/403 可恢复错误。

### 4.4 与现有管理员 Bearer 的关系

- 本阶段方案落地前，AI 工具层 token 仍须保存在可信执行端、绝不进入模型上下文；管理员 Bearer 不得作为长期授权方式。
- 方案落地后：日常 AI 使用受限客户端凭据；管理员 token 仅保留给人工管理与一次性迁移。
- 风险已知并记录（见 [ADR-0056](adr/0056-独立AI接入平台架构.md) §5、`rules.json` `toolCatalog.permissions.adminBearerRisk`）：仅隐藏 token 不能阻止模型直调 Go，必须由 G1–G3 服务端强制。


### 4.5 已实现（P1 首批，2026-11-07）

- 凭据：migration 0025 的 `ai_clients`（token_hash 唯一、scopes_json、device_ids_json、expires_at/revoked_at/last_used_at）；明文 token 只在创建/轮换响应或本地管理命令输出一次。
- 鉴权：管理员会话优先、AI 客户端凭据其次，共用一个 Bearer 通道且不能互相升级；AI 命中后按 method+path 强制 scope，未映射写请求 403 `FORBIDDEN_SCOPE`（默认拒绝）。
- 审计：`ai_audit` 记录 AI 写请求（含被拒绝）的 clientId/method/path/action/statusCode/requestId/时间，不记录正文；审计失败不阻断业务。管理员请求不写。
- 管理：`qimeng ai-client create|list|revoke|rotate -config <yaml>`（服务端本机执行，直接打开同一 SQLite，先跑幂等 migration）；2026-11-08 P2 起同一能力也有 HTTP 管理端点（仅管理员会话，AI bearer 即使持 `admin` scope 也 403 `FORBIDDEN_SCOPE`）。CLI 仍可用。
- 设备级隔离：`device.apply` + `device_ids`，设备结果包各端点校验 `targetDevice`/`targetDeviceId`；管理员不受限；真实设备配对仍是 G6。
- 细节与边界以 [SECURITY](SECURITY.md#独立-ai-客户端凭据scope-与审计adr-0057-p1p22026-11-08) 为准；实现路径见 [ADR-0057](adr/0057-AI客户端凭据与细粒度权限模型.md)。

### 4.6 已实现（P2 首批，2026-11-08）

- **AI 客户端 HTTP 管理**（仅管理员会话）：`GET/POST /api/v1/ai-clients`、`DELETE /api/v1/ai-clients/{clientId}`（幂等撤销）、`POST .../rotate`（旧 token 立即失效，已撤销 409）。创建/轮换响应含一次性明文 token，服务端只存哈希。撤销/轮换经内存缓存约 5s TTL 传播。
- **审计查询**：`GET /api/v1/ai-audit`，clientId 精确、action 前缀过滤，`limit` 1–200，倒序 keyset；只返回 client/method/path/action/status/requestId/createdAt 元数据，不含正文与凭据。
- **按客户端限流**：令牌桶，默认 600/min、burst 120；`QIMENG_AI_RATE_LIMIT_PER_MINUTE`/`QIMENG_AI_RATE_LIMIT_BURST`，0 关闭限流；超限 429 `RATE_LIMITED` + `Retry-After`。限流器进程内，多实例不共享。
- **外部执行器通道**（ADR-0058）：`maintenance.execute` scope 或管理员会话调用 claim/heartbeat/steps/artifacts/complete；fencing token 由服务端生成，暂停/取消/终态/过期/重领即失效；`completedSteps` 返回全量已完成步骤名（详情端点仍 50 步/100 工件上限）。未实现按执行器并发配额与推送通知。
- **scope 清单**（OpenAPI `AiClientScope` 为准）：`read/research/author.write/author.destructive/asset.author.write/asset.write/asset.upload/asset.delete/tag.write/source.write/library.write/media.organize/trash.restore/backup.export/backup.restore/maintenance.read/maintenance.write/maintenance.execute/device.apply/admin`。`admin` 仅管理面保留，不开放业务路由。
- **管理端点仍缺 UI**：Web/Android 没有 AI 客户端管理页；审计查询也只有 API。

## 5. 与 AI 工具平台的分工

- Go：业务规则唯一实现、事务/并发/文件/备份/任务账本、权限与审计强制；基础操作尽量完整、稳定、可组合。
- AI 工具平台：工具发现与按需规则、字段规范/脚本扩展、CLI/MCP、计划与编排、结果核验；**不复制**作者/媒体/备份/任务领域逻辑，不建第二套任务或数据存储。
- 长任务：复用 `/api/v1/maintenance/jobs` 账本与 Go Worker；AI 执行器通过 `/api/v1/maintenance/executor` 受控领取通道执行研究/编排步骤（租约 fencing），业务写入仍回 Go API。
- NAS：Go 服务为稳定底座；AI 平台独立更新/部署；PC/Android 继续用既有 API，不要求在手机部署完整 AI 执行器。

## 6. 分阶段实施（不阻塞当前交付）

| 阶段 | 内容 | 交付物/验证 |
|---|---|---|
| P0（本 PR） | 本方案文档 + ADR-0057；现有代码/测试收尾 | 文档检查、现有测试全绿；不改 Go/OpenAPI/migration |
| P1 | AI 客户端凭据 + scope + 审计（G1/G2/G8） | 只增 migration、鉴权中间件、scope 拒绝测试、审计回读；管理员可创建/撤销/轮换。**已交付 2026-11-07**（本地 CLI 管理） |
| P2 | 高风险确认（G3）与外部执行器受控领取（G4） | **部分已交付 2026-11-08**：AI 客户端 HTTP 管理/审计查询/按客户端限流（G1/G2/G8），外部执行器通道（G4，ADR-0058）；G3 的动作级确认凭据仍待做。不开放任意执行 |
| P3 | 批量能力按证据补充（G5）、设备配对（G6） | 真实规模/设备验收后实施；复用领域服务 |
| 未来 | 作品/季/剧集模型（G7）、NAS 常驻执行器 | 独立领域设计与硬件验收，不在本轮 |

每阶段独立 PR/迁移/回退说明；不得先改主协议再补实现；不得为 AI 方便复制数据库写入逻辑。

## 7. 非目标

- 任意 SQL、无边界文件系统访问、任意服务器命令/shell 执行；
- 默认授予 AI 管理员权限或恢复/迁移能力；
- 在 AI 层重建业务规则、任务账本或设备结果包协议；
- 为尚未存在的需求提前建设插件市场、分布式执行器或作品/剧集数据库。