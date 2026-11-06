# API：持久维护任务队列

> [API 目录与全局约定](../GUIDE_API.md)。字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；本页解释状态机、幂等、租约边界与错误码。决策见 [ADR-0054](../adr/0054-持久维护任务队列.md)。

维护任务队列是外部 AI 维护的**持久账本**：任务、步骤与工件索引写入 SQLite（migration 0023），服务重启/断网后仍可查询与恢复。**本轮只交付账本与端点**（P3 第一刀）：没有 worker 执行器、没有 Web 任务中心、没有 PC→手机桥接；创建任务不会自动开始执行。

## 能力边界（先看这里）

- **账本不是执行器**：POST 只建 queued 行；状态推进、租约领取、步骤/工件写入由后续 worker 通过 `server/internal/maintenance` 内部原语完成。本轮没有 Claim/Heartbeat/Complete 的 HTTP 端点。
- **端点无独立开关**：与 research 不同，账本端点恒可用（只读写本地 SQLite，不出网、不执行任务）；worker 上线前不会自动消费任务。
- **任务类型是账本标签**：`asset_research`/`author_cleanup`/`backup_export`/`custom` 只用于分类；具体业务规则仍由后续 worker 与领域包实现。
- **`libraryId` 不校验存在性**：它是账本引用标签，库删除后历史任务仍保留，不能据此认为库一定存在。
- **工件正文不入库**：`maintenance_artifacts` 只存 `kind/uri/sha256/bytes/createdAt` 元数据；正文由 worker 落在授权目录，URI 只是引用。

## 鉴权

六个端点全部走全局 Bearer（`Authorization: Bearer <token>`），无 `security: []` 豁免。任务载荷可能包含维护计划/作者候选等业务信息，按管理面数据对待，不向未认证请求返回。

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

响应 `MaintenanceJob`：首次 **201**，同 `idempotencyKey` 重放 **200** 且返回原任务（不新建第二行）。服务端把 payload/budget 按稳定键序归一化并计算 `inputFingerprint` 入账；重放**不比较**载荷差异，调用方负责同一幂等键只对应同一语义请求。

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
等待态 `waiting_evidence`/`waiting_authorization`/`waiting_device` 可从流水线各阶段进入，条件满足后回到 queued/inventory 或继续前进；`partial` 表示执行完成但有部分步骤失败/未完成，仍是终态。公开 HTTP 只触发 `paused/queued/cancelled` 三条迁移；其余状态由后续 worker 推进。

## 幂等

- `idempotency_key` 在 `maintenance_jobs` 上**永久唯一**：任务进入终态后仍保留，不能用同一键创建另一任务。
- 并发创建同键：一个事务成功插入，其余事务在唯一索引/事务串行化后读取既有行并返回 200，不产生重复任务。
- 幂等键与任务类型/库/载荷共同决定调用语义；账本记录 `input_fingerprint` 供审计，但**不因指纹不同而拒绝重放**。

## 租约与 worker 边界

`maintenance_jobs` 预留 `lease_owner`/`lease_until`。`server/internal/maintenance` 已提供内部原语（本轮无 HTTP API）：

- `Claim(owner, ttl)`：领取最旧的可领取 queued 任务（租约为空或已过期），写 `lease_owner/lease_until/started_at`；
- `Heartbeat(id, owner, ttl)`：仅租约持有者续期，失主返回 `ErrLeaseLost`；
- `Complete(id, owner, status, errorCode, errorMessage)`：仅 `completed/partial/failed` 终态，清空租约并写 `finishedAt`；
- `RecordStep`/`AddArtifact`：按 `(job_id, step)` 幂等 upsert 步骤、按 `(job_id, kind, uri)` 去重工件索引。

worker 尚未实现：没有进程消费 queued 任务，没有自动重试/接管，也没有步骤执行逻辑。后续 worker 必须复用上述原语，不得直接写表绕过状态机。

## 错误码

| HTTP | code | 触发条件 |
|---|---|---|
| 400 | `INVALID_PARAM` | kind/status 非法、幂等键/字段超长、payload/budget 非对象或超限 |
| 400 | `INVALID_CURSOR` | 列表 cursor 非法 |
| 400 | `INVALID_BODY` | 请求体不是合法 JSON 或 Content-Type 不是 application/json |
| 401 | `UNAUTHORIZED` | 缺/错 Bearer（全局鉴权） |
| 404 | `NOT_FOUND` | 任务 ID 不存在 |
| 409 | `STATE_CONFLICT` | 非法状态迁移；终态不可变更 |
| 500 | `INTERNAL` | 数据库/内部错误（不泄露内部路径） |

## 未实现项

- **worker 执行器**：没有常驻循环领取/执行任务，没有租约接管与步骤执行；本轮只有账本与内部原语。
- **Web 任务中心**：没有任务列表/详情/取消 UI；Web 维护页仍是目录卡与三任务入口。
- **PC→手机桥接**：没有 `device.apply.*` 端点、结果包或 Android Worker。
- **`author.cleanup.prepare/apply`**：作者清理计划工具仍未实现；不要用客户端串行删除/改名模拟。
- **MCP 适配**：规则包/CLI 已有，MCP 仍待做。
- **安装状态**：本切片源码与协议已交付，**未安装到现装 PC/手机**；现装仍为 HANDOVER 记录的版本。worker/Web/桥接完成前，任务只会停留在 queued（或人工暂停/取消）。

## 数据与验证边界

- migration `0023_maintenance_jobs` 只新增三表（`maintenance_jobs/maintenance_steps/maintenance_artifacts`）与索引，不改历史迁移。
- 测试使用临时 SQLite 与合成数据（创建/幂等重放/并发同键、列表过滤与游标、状态机合法/非法迁移、暂停/恢复/取消、终态不可变、404/409 映射），不访问 8420 真库、真实设备或真实维护目录。
- 本地未跑业务 `go test`（仓库规定走云端 CI）；已跑 `go build ./...`、`gofmt`、`go vet` 与 `make sdk`。实际测试结果以云端 CI 为准。
