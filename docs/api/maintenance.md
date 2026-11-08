# API：持久维护任务队列

> [API 目录与全局约定](../GUIDE_API.md)。字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；本页解释状态机、幂等、租约边界与错误码。决策见 [ADR-0054](../adr/0054-持久维护任务队列.md)。

维护任务队列是外部 AI 维护的**持久账本 + 进程内 worker + 受控外部执行器通道**：任务、步骤与工件索引写入 SQLite（migration 0023），服务重启/断网后仍可查询与恢复。本轮在账本/端点之上交付 Go 内置 worker（首个真实 handler：`backup_export`，复用既有 `backup.Manager` 做在线快照），默认启用且只消费已注册 handler 的 kind；外部执行器经 `/api/v1/maintenance/executor/*` 以租约 + fencing token 领取/续租/写步骤/回传终态（ADR-0058）。Web 任务中心已实现（源码，只读列表/详情/取消；本机验证见 [HANDOVER](../HANDOVER.md)）；PC→手机桥接仍未实现。

## 能力边界（先看这里）

- **执行面分两条通道**：POST 只建 queued 行；进程内 worker 通过 `server/internal/maintenance` 内部原语推进；外部执行器只能走 `/api/v1/maintenance/executor/*` 受控通道（ADR-0058），凭 claim 返回的 `leaseToken` 续租/写步骤/工件/回传终态，**不能直接写任务表**。没有 token 或 token 已失效一律 409 `LEASE_LOST`。
- **端点无独立开关，worker 有独立开关**：与 research 不同，账本端点恒可用（只读写本地 SQLite，不出网、不执行任务）；worker 由 `maintenance_worker.enabled`（`QIMENG_MAINTENANCE_WORKER_ENABLED`）控制，默认启用，只消费已注册 handler 的 kind。
- **任务类型决定是否被本进程消费**：`backup_export` 有内置 handler，会被 worker 自动领取执行；`asset_research`/`author_cleanup`/`custom` 目前只有账本标签，保持 queued 供外部 AI 执行器，具体业务规则仍由后续实现。
- **`libraryId` 不校验存在性**：它是账本引用标签，库删除后历史任务仍保留，不能据此认为库一定存在。
- **工件正文不入库**：`maintenance_artifacts` 只存 `kind/uri/sha256/bytes/createdAt` 元数据；正文由 worker 落在授权目录，URI 只是引用。

## 鉴权

全部端点走全局 Bearer（`Authorization: Bearer <token>`），无 `security: []` 豁免。任务载荷可能包含维护计划/作者候选等业务信息，按管理面数据对待，不向未认证请求返回。AI 客户端按 scope 访问：账本读写需 `maintenance.read`/`maintenance.write`，外部执行器五个端点需 `maintenance.execute`；管理员会话不受 scope 限制。`maintenance.execute` 不等于 `admin`，不授予其他业务写能力。

## POST /api/v1/maintenance/jobs

创建任务。请求体 `CreateMaintenanceJobRequest`：

```json
{
  "kind": "asset_research",
  "libraryId": "lib-1",
  "payload": { "query": "作者名", "assetIds": ["..."] },
  "idempotencyKey": "ai-maintenance-20261107-0001",
  "rulesVersion": "2026-11-07.1",
  "budget": { "maxSteps": 20, "maxBytes": 1048576 }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `kind` | 是 | `asset_research/author_cleanup/backup_export/custom`；未知值 400 `INVALID_PARAM` |
| `libraryId` | 否 | 库引用标签，≤100 字符；不校验库存在 |
| `payload` | 否 | 任意 JSON 对象；序列化后 ≤64KiB，超出 400 |
| `idempotencyKey` | 是 | 1–200 字符；同键重放返回既有任务 |
| `rulesVersion` | 否 | 规则包版本，≤100 字符；只入账审计 |
| `budget` | 否 | 任意 JSON 对象；序列化后 ≤16KiB，超出 400 |

响应 `MaintenanceJob`：首次 **201**，同 `idempotencyKey` 重放 **200** 且返回原任务（不新建第二行）。服务端把 kind/libraryId/payload/budget/rulesVersion 按稳定键序归一化并计算 `inputFingerprint` 入账；**重放会比较指纹**：同键同输入（含 JSON 键序不同但语义相同）返回原任务 200；同键不同 kind/库/载荷/预算/规则版本返回 409 `STATE_CONFLICT`，避免把另一笔任务误认为成功重放。调用方仍应保证一个幂等键只对应同一语义请求。

## GET /api/v1/maintenance/jobs

查询参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `status` | 全部 | 精确状态过滤（枚举见下）；未知值 400 |
| `limit` | 50 | 1–100；越界 400 `INVALID_PARAM` |
| `cursor` | 无 | 上一页 `nextCursor`；非法 400 `INVALID_CURSOR` |

响应 `MaintenanceJobList`：`items[]` 按 `createdAt` 倒序，字段 `id/kind/status/createdAt/updatedAt/finishedAt/stepCount/progress`；`nextCursor` 为 null 表示到底。`progress` 是已完成步骤数占已索引步骤总数的百分比（向下取整）；`completed` 恒 100，无步骤的非 completed 任务为 0。列表不返回 payload/steps/artifacts。

## GET /api/v1/maintenance/jobs/{id}

返回任务详情：账本字段、`payload`/`budget`、`rulesVersion`、`startedAt/finishedAt`、`steps[]`（最近最多 50 条，按 `startedAt` 倒序、空值最后、同时间按 `step` 升序）、`artifacts[]`（最近最多 100 条，按 `createdAt` 倒序）与 `error`（`errorCode/errorMessage` 非空时出现）。任务不存在 404 `NOT_FOUND`。

## POST /api/v1/maintenance/jobs/{id}/pause|resume|cancel

| 动作 | 合法来源状态 | 结果 | 非法时 |
|---|---|---|---|
| `pause` | 任意非终态且当前不是 paused | `paused`，清空租约，不写 `finishedAt` | 409 `STATE_CONFLICT` |
| `resume` | 仅 `paused` | `queued`，清空 `errorCode/errorMessage` 与租约；worker 后续从 inventory 重启 | 409 `STATE_CONFLICT` |
| `cancel` | 任意非终态（含 paused 与 waiting_*） | `cancelled`，写 `finishedAt`，清空租约 | 终态 409 `STATE_CONFLICT` |

终态 `completed/partial/failed/cancelled` **不可再变更**：暂停/恢复/取消均返回 409；取消不删除账本行，步骤与工件索引保留。

## 状态机

```text
queued -> inventory -> research -> planned -> applying -> verifying -> completed
   |          |           |          |           |          |
   +----------+-----------+----------+-----------+----------+--> failed
   |          |           |          |           |          |
   +----------+-----------+----------+-----------+----------+--> cancelled
   |          |           |          |           |          |
   +----------+-----------+----------+-----------+----------+--> paused -> queued
```
等待态 `waiting_evidence`/`waiting_authorization`/`waiting_device` 可从流水线各阶段进入，条件满足后回到 queued/inventory 或继续前进；`partial` 表示执行完成但有部分步骤失败/未完成，仍是终态。公开 HTTP 只触发 `paused/queued/cancelled` 三条迁移；其余状态由进程内 worker 通过内部方法推进（当前 `backup_export`）。无研究/计划步骤的短流水线允许 `inventory -> applying`（如 backup_export）；完成前 worker 会把状态补齐到 `verifying`，不会从更早阶段直接写 `completed`。

## 幂等

- `idempotency_key` 在 `maintenance_jobs` 上**永久唯一**：任务进入终态后仍保留，不能用同一键创建另一任务。
- 并发创建同键：一个事务成功插入，其余事务在唯一索引/事务串行化后读取既有行；指纹相同返回 200，指纹不同返回 409，不产生重复任务。
- `kind/libraryId/payload/rulesVersion/budget` 归一化后共同决定 `input_fingerprint`；同键不同指纹是调用方误用旧键，服务端拒绝（409 `STATE_CONFLICT`）而不是返回另一笔任务的 200。

## 租约与 worker 边界

`maintenance_jobs` 预留 `lease_owner`/`lease_until`；ADR-0058 的外部通道复用 `lease_owner` 承载 fencing token（不新增列），服务端每次领取重写该值。`server/internal/maintenance` 同时提供内部原语与进程内 worker；外部执行器唯一入口是下面的 `/api/v1/maintenance/executor/*` 通道，不能直接写表。

内部原语（仅包内/进程内使用）：

- `Claim(kind, owner, ttl)`：按 kind 领取最旧的可领取任务——`queued` 或租约已过期的在途状态（`inventory/research/planned/applying/verifying`），写 `lease_owner/lease_until/started_at`；`waiting_*`/`paused`/终态不可领取；
- `Heartbeat(id, owner, ttl)`：仅租约持有者续期，失主返回 `ErrLeaseLost`；
- `AdvanceStatus(id, owner, next)`：worker 专用状态推进，事务内校验租约 owner 与状态机；同状态为幂等 no-op，非法迁移 `ErrStateConflict`，失主 `ErrLeaseLost`；
- `Complete(id, owner, status, errorCode, errorMessage)`：仅 `completed/partial/failed` 终态，清空租约并写 `finishedAt`；
- `RecordStep`/`AddArtifact`：按 `(job_id, step)` 幂等 upsert 步骤、按 `(job_id, kind, uri)` 去重工件索引。

进程内 worker（`server/internal/maintenance/worker.go`，main 用 `safego.Go` 启动，默认启用）：

- **只消费已注册 handler 的 kind**：当前注册 `backup_export`；未注册 kind 保持 queued，不会被本 worker 领取。
- **租约 TTL + 心跳 goroutine**：默认 TTL 2 分钟，按 TTL/3 续租；失主（暂停/取消/被接管）在下一个步骤边界停止，不再推进状态，已提交步骤保留。
- **有界并发**：默认 1，配置上限 8；每个并发槽位串行领取/执行。
- **逐步骤记录**：开始写 `running`（attempt/startedAt），结束写 `completed/failed/skipped`（finishedAt/detail）。
- **崩溃/重启恢复**：租约过期后任务被重新领取，从已完成步骤之后续跑；账本记录为 `completed` 的步骤不会重复执行副作用。恢复用的步骤名集合直接读账本全量已完成/跳过步骤（不受详情端点最近 50 条上限影响），因此超过 50 步的任务也不会因窗口截断重跑已完成的副作用。崩溃窗口内正在执行的步骤可能重跑一次（handler 需幂等/可收敛；backup_export 极端情况下可能多出一份快照文件，由既有保留/轮转策略清理）。
- **终态可判定**：全部步骤完成 -> `completed`；有步骤 `skipped` -> `partial`；步骤失败/panic -> 记录 failed 步骤并 `Complete(failed, errorCode)`。暂停/取消由公开端点写 `paused/cancelled`，worker 保留已提交步骤后停止，不覆盖终态。
- **panic 兜底**：单个任务 panic 只把该任务记为 failed，进程继续服务。

`backup_export` handler 的步骤固定为 `inventory -> export -> verify`：`export` 调用既有 `backup.Manager.Create`（与定时/手动备份共享防重入闸）生成快照，流式计算 SHA-256 并记录工件（kind=`backup_snapshot`，uri=快照文件名，bytes=文件大小）；`verify` 重新打开快照核对大小与 SHA-256。工件正文留在 `DataDir/backups`，账本只存引用。

配置（yaml `maintenance_worker` / 环境变量）：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` / `QIMENG_MAINTENANCE_WORKER_ENABLED` | `true` | worker 总开关；false 不启动领取循环，账本端点不受影响 |
| `poll_interval` / `QIMENG_MAINTENANCE_WORKER_POLL_INTERVAL` | `5s` | 空转轮询间隔；`<=0` 回落默认 |
| `lease_ttl` / `QIMENG_MAINTENANCE_WORKER_LEASE_TTL` | `2m` | 租约有效期；`<=0` 回落默认 |
| `concurrency` / `QIMENG_MAINTENANCE_WORKER_CONCURRENCY` | `1` | 并发任务数上限（1–8；yaml 越界收敛到 8，env 越界拒绝启动） |

## 外部执行器通道（ADR-0058，`/api/v1/maintenance/executor`）

外部 AI 执行器不共享服务进程，但可以经受限通道在账本上执行：服务端为每次领取生成 fencing token，后续所有写入必须携带该 token。实现细节以 [OpenAPI](../../api/openapi.yaml) 对应 path/schema 为准。

| 端点 | 请求体要点 | 成功响应 | 说明 |
|---|---|---|---|
| `POST /claim` | `kind`（必填）、`leaseSeconds`（30–1800，默认 120） | **200** `MaintenanceLease{job,leaseToken,leaseUntil,completedSteps[]}`；无可领取任务 **204** 无正文 | 原子领取该 kind 最旧的可执行任务（queued 或租约已过期的在途状态）；`leaseToken` 只由服务端生成，客户端不能自选 |
| `POST /jobs/{id}/heartbeat` | `leaseToken`、`leaseSeconds?` | `{jobId, leaseUntil}` | 仅未过期租约的持有者可续租 |
| `POST /jobs/{id}/steps` | `leaseToken`、`step`、`status`（pending/running/completed/failed/skipped）、`attempt?`、`detail?`（≤16KiB） | `MaintenanceJob` | 按 `(job, step)` 幂等 upsert；服务端按状态补 `startedAt/finishedAt`，完成时保留原 `startedAt` |
| `POST /jobs/{id}/artifacts` | `leaseToken`、`kind`、`uri`、`sha256`、`bytes` | `MaintenanceJob` | 按 `(job, kind, uri)` 去重，重复登记 no-op；工件正文不入库 |
| `POST /jobs/{id}/complete` | `leaseToken`、`status`（completed/partial/failed）、`errorCode?`、`errorMessage?` | `MaintenanceJob` | 只有持有效租约者可回传终态；`completed` 清空错误字段；同一终态重复回传幂等 200 |

**Fencing（围栏）语义**：

- token 与本次租约绑定；任务被暂停/取消、进入终态、租约过期或被重新领取后，旧 token **立即失效**。
- 旧执行器再用旧 token 续租/写步骤/工件/回传终态一律 **409 `LEASE_LOST`**，必须放弃本次执行，不得继续产生副作用。
- 重新领取同一任务会生成新 token；旧执行器的写入无法覆盖新租约下的结果。

**步骤账本与恢复**：`completedSteps` 是该任务**全部**已完成/跳过步骤名（按 `step` 升序），不受详情端点最近 50 条步骤/100 条工件的截断影响；执行器应按名跳过已完成步骤。详情端点仍按 50/100 上限返回最近记录，查看完整进度以 claim 的 `completedSteps` 为准。

**并发与通知边界**：同一任务由租约保证单执行器；不同任务之间没有按执行器的并发配额（不限制单个 AI 客户端同时持有多少租约）。服务端**不推送通知**，执行器只能轮询 `claim`；没有可领取任务时返回 204，建议按任务规模退避。

## 错误码

| HTTP | code | 触发条件 |
|---|---|---|
| 400 | `INVALID_PARAM` | kind/status 非法、幂等键/字段超长、payload/budget 非对象或超限 |
| 400 | `INVALID_CURSOR` | 列表 cursor 非法 |
| 400 | `INVALID_BODY` | 请求体不是合法 JSON 或 Content-Type 不是 application/json |
| 401 | `UNAUTHORIZED` | 缺/错 Bearer（全局鉴权） |
| 404 | `NOT_FOUND` | 任务 ID 不存在 |
| 409 | `STATE_CONFLICT` | 非法状态迁移；终态不可变更；同幂等键不同输入（指纹冲突） |
| 409 | `LEASE_LOST` | 执行器 token 已失效（租约过期、任务暂停/取消/终态、被重新领取） |
| 500 | `INTERNAL` | 数据库/内部错误（不泄露内部路径） |

## 未实现项

- **worker 的其余 kind**：只有 `backup_export` 注册了 handler；`asset_research`/`author_cleanup`/`custom` 仍停在 queued，等外部 AI 执行器经 `/api/v1/maintenance/executor/*` 领取或后续 handler 注册。
- **执行器并发配额**：只有共享的任务租约（同一任务单执行器）；没有按 AI 客户端/执行器的同时在途任务上限。
- **推送通知**：不向执行器推送新任务；只能轮询 `claim`（空队列返回 204）。
- **PC→手机桥接**：服务端设备结果包 API 已实现（见 [设备结果包 API](device.md)），Android Worker、设备配对与真机后台同步仍未实现，端到端桥接不能写成已具备。
- **`author.cleanup.prepare/apply`**：作者清理计划工具仍未实现；不要用客户端串行删除/改名模拟。
- **MCP 适配**：规则包/CLI 已有，MCP 仍待做。
- **Web 任务中心**：已实现（源码，只读列表/详情/取消；本机验证见 [HANDOVER](../HANDOVER.md)）。
- **安装状态**：本切片源码与协议已交付，**未安装到现装 PC/手机**；现装仍为 HANDOVER 记录的版本。部署本切片后 backup_export 任务会被 worker 自动执行；未注册 kind 仍停留在 queued（或人工暂停/取消）。

## 数据与验证边界

- migration `0023_maintenance_jobs` 只新增三表（`maintenance_jobs/maintenance_steps/maintenance_artifacts`）与索引，不改历史迁移；worker 与外部执行器复用这三表，没有第二套任务表。外部执行器通道本身不新增 migration（租约用既有列承载 token 语义）。
- 测试使用临时 SQLite、合成 handler 与临时备份目录，覆盖：按 kind 领取/心跳续租、租约过期接管、重启续跑且已完成步骤不重复副作用、暂停/取消保留已提交步骤、未注册 kind 不被领取、同键同载荷 200 / 不同载荷 409、backup_export 工件（name/sha256/bytes）记录、失败与 panic 终态；外部通道另覆盖 fencing token 失效（暂停/取消/重领/过期）、步骤/工件幂等 upsert、终态重复回传，以及 >50 步任务的恢复步骤集合完整。不访问 8420 真库、真实设备或真实维护目录。
- 本机已跑 `gofmt -l`、`go build ./...`、`go vet ./...` 与定向 `go test ./internal/maintenance/`、`go test ./internal/config/ -run TestMaintenanceWorker`、`go test ./internal/httpapi/ -run TestMaintenance`（临时 SQLite/合成数据）；完整回归以云端 CI 为准。
