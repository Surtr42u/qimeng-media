# API：设备结果包（PC→手机无前台维护）

> [API 目录与全局约定](../GUIDE_API.md)。字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；本页解释结果包结构、领取/回传语义、状态机、幂等、存储与错误码。决策见 [ADR-0055](../adr/0055-手机无前台维护结果包协议.md)。

设备结果包是 PC 生成、手机领取并应用的**服务端账本 + 段文件存储**：PC 把作者主表 TXT 与 v2 便携备份登记为一个包，手机（未来的 Android Worker）按 `targetDeviceId` 领取、下载段、应用后回传结果。本轮是 **P4 第一刀，只交付服务端 API**：没有 Android Worker、没有配对流程、没有设备身份校验，也没有自动清理段文件。

## 能力边界（先看这里）

- **只有服务端**：Android Worker、WorkManager 定时拉取、手机端「立即应用」、配对/短码/二维码都不在本切片；领取与回传目前只能由外部脚本或后续 Worker 调用。
- **targetDeviceId 是不透明标签**：服务端只校验 1–100 个 `A-Za-z0-9._:-` 字符，不验证设备身份、不绑定 token、不做配对。任何持有 Bearer 的调用方都能登记/领取/回传；单用户形态下靠运维约定与 token 保管，不能当作设备级授权。
- **端点无独立开关**：只读写本机 SQLite 与 `<dataDir>/device-apply-packages/`，不出网、不执行应用；默认即可用。
- **段字节不入库**：SQLite 只存 kind/bytes/sha256/相对路径与任务状态；正文在 DataDir 下，删除/清理任务留给后续阶段。
- **过期与取消只改账本**：到期或取消不会删除磁盘段文件；本切片没有 GC/清理任务，磁盘占用需后续维护阶段处理。
- **未安装**：源码、协议与 SDK 已交付，但未部署到现装 PC/手机；现装版本见 [HANDOVER](../HANDOVER.md)。

## 鉴权

七个端点全部走全局 Bearer（`Authorization: Bearer <token>`），无 `security: []` 豁免。结果包包含作者主表与完整 v2 备份（业务数据），按管理面数据对待。

## 包结构（manifest）

`POST /api/v1/device/apply-tasks` 是 `multipart/form-data`：

| 字段 | 必填 | 说明 |
|---|---|---|
| `manifest` | 是 | JSON 字符串，解码后为 `DeviceApplyManifest`（见下） |
| `masterTxt` | 条件 | 作者主表 TXT；manifest 声明 `masterTxt` 段时必填 |
| `v2Backup` | 条件 | v2 便携备份；manifest 声明 `v2Backup` 段时必填 |

manifest 示例：

```json
{
  "targetDeviceId": "phone-main",
  "protocolVersion": 1,
  "rulesFingerprint": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "ttlSeconds": 86400,
  "segments": [
    {
      "kind": "masterTxt",
      "bytes": 12345,
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "expectedReadback": { "authors": 3, "assetAuthorRefs": 4 }
    },
    {
      "kind": "v2Backup",
      "bytes": 456789,
      "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      "expectedReadback": { "viewEvents": 10 }
    }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `targetDeviceId` | 是 | 不透明目标设备标签，1–100 个 `A-Za-z0-9._:-`；本轮不校验设备身份 |
| `protocolVersion` | 是 | 仅支持 `1`；其他值 400 |
| `rulesFingerprint` | 是 | 规则包指纹，64 位小写十六进制；服务端只校验格式并记录，不重算规则包 |
| `ttlSeconds` | 否 | 包有效期，300–604800 秒，缺省 86400（24h）；超范围 400 |
| `segments` | 是 | 1–2 项，kind 不得重复；每项 `kind/bytes/sha256` 必填，`expectedReadback` 为可选 JSON 对象（≤16KiB） |

**段文件与 manifest 必须一一对应**：manifest 声明的每个 kind 必须上传同名文件段，未声明的文件段/文本字段会被拒绝（400）。`bytes` 必须等于实际字节数，`sha256` 必须等于落盘后复算的摘要；任何不一致都在**写库之前**拒绝并清理任务目录。

### 包体上限

| 限制 | 值 | 超限 |
|---|---|---|
| manifest | 256KiB | 400 `INVALID_PARAM` |
| masterTxt | 1MiB | 413 `TOO_LARGE`（段文件自身超限） |
| v2Backup | 64MiB | 413 `TOO_LARGE`（段文件自身超限） |
| 整个 multipart 请求体 | 66MiB | 413 `TOO_LARGE` |

manifest 声明值超出段类型上限、或声明字节数与实际文件不符，返回 400 `INVALID_PARAM`（服务端在写库前拒绝）。

## POST /api/v1/device/apply-tasks

登记结果包。成功 **201**，响应 `DeviceApplyTask`（`status=pending`、`segments[]` 回显声明值、`expiresAt = 创建时刻 + ttlSeconds`）。

- 本切片**没有创建幂等键**：同一内容重复登记会生成两个独立任务。调用方应保证一包一登记；网络失败后用 `GET /api/v1/device/apply-tasks?targetDevice=...` 核对是否已创建。
- 段文件先写入 `<dataDir>/device-apply-packages/<taskId>/`，逐段复算 SHA-256 与字节数，全部通过后才在单事务内插入任务与段行；任一步失败都会删除该任务目录。
- `targetDeviceId` 只入账本，不校验是否存在配对设备。

## GET /api/v1/device/apply-tasks

查询参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `targetDevice` | 必填 | 目标设备不透明标签；缺失/非法 400 |
| `status` | 全部 | 精确状态过滤（枚举见下）；未知值 400 |
| `limit` | 50 | 1–100；越界 400 `INVALID_PARAM` |
| `cursor` | 无 | 上一页 `nextCursor`；非法 400 `INVALID_CURSOR` |

响应 `DeviceApplyTaskList`：`items[]` 按 `createdAt` 倒序、id 倒序 tie-break；摘要含 `id/targetDeviceId/status/protocolVersion/rulesFingerprint/createdAt/updatedAt/claimedAt/finishedAt/expiresAt/segmentCount`，不含段清单与结果正文。`nextCursor` 为 null 表示到底。读取前会把到期任务惰性标记为 `expired`。

## GET /api/v1/device/apply-tasks/{id}

返回任务详情：账本字段、段清单（`kind/bytes/sha256/expectedReadback`）与已回传的 `result`（含 `reportedAt = finishedAt`）。任务不存在 404 `NOT_FOUND`；到期任务先惰性标记 `expired`。

## POST /api/v1/device/apply-tasks/{id}/claim

`pending -> claimed`，写 `claimedAt`。

- **重复领取幂等**：任务已 `claimed` 且未过期时再次 claim 返回 **200** 与同一任务，`claimedAt` 不变。原因是本切片没有领取者/设备身份字段，无法区分「同一手机重试」与「另一手机」；服务端选择幂等返回，避免 Worker 进程被杀后重试即失败。文档与 ADR-0055 明确此语义，后续配对/身份落地时应重新评审。
- 已 `applied/partial/failed/cancelled/expired` 或已过期任务返回 409 `STATE_CONFLICT`；不存在 404。

## GET /api/v1/device/apply-tasks/{id}/segments/{kind}

下载段原始字节：

- 仅 `pending/claimed` 且未过期的任务可下载；终态/过期任务 409 `STATE_CONFLICT`。
- `kind` 必须在该包 manifest 中声明，否则 404 `NOT_FOUND`；不在枚举内 400 `INVALID_PARAM`。
- 响应 `application/octet-stream`，`Content-Length` 为段字节数，`X-Content-SHA256` 为登记时复算并保存的小写十六进制摘要。
- 打开文件后会复核文件类型与大小与账本一致；不一致返回 500 `INTERNAL`（不泄露路径）。
- 不支持 Range/条件请求；大备份下载中断后需整段重下。

## POST /api/v1/device/apply-tasks/{id}/result

回传终态结果，仅 `claimed` 任务可回传。

请求体 `DeviceApplyResultRequest`：

| 字段 | 必填 | 说明 |
|---|---|---|
| `status` | 是 | `applied`（全部应用）/`partial`（部分应用）/`failed`；其他值 400 |
| `readback` | 否 | 任意 JSON 对象，≤64KiB，缺省 `{}`；建议按 manifest 的 `expectedReadback` 回读 |
| `deviceLocalHashes` | 否 | 0–2 条手机本地复算摘要，`kind` 不得重复、`sha256` 64 位小写十六进制；数组按 kind 排序后保存 |
| `message` | 否 | 可选说明，≤2000 字符 |

成功 **200**，任务进入 `applied/partial/failed`，写 `finishedAt` 并保存结果；响应里的 `result.reportedAt` 等于 `finishedAt`。

**重复回传语义**（文档化契约）：

- 已终态任务再次提交**完全相同**的结果（`status`、归一化 JSON `readback`、排序后的 `deviceLocalHashes`、`message` 全部一致）→ **200 幂等**，返回原任务，`finishedAt` 不变。
- 任何字段不同 → **409 `STATE_CONFLICT`**；终态结果不可覆盖。
- 未领取（`pending`）、`cancelled/expired` 或已过期 → 409；不存在 404。

## POST /api/v1/device/apply-tasks/{id}/cancel

`pending/claimed -> cancelled`，写 `finishedAt`。`cancelled` 重复取消 **200 幂等**；`applied/partial/failed/expired` 409 `STATE_CONFLICT`。取消不删除段文件与账本行，历史保留。

## 状态机

```text
pending --claim--> claimed --result(applied|partial|failed)--> 终态
   |                  |
   |                  +--cancel--> cancelled
   +--cancel---------+
   +--expire(惰性)---+--expire(惰性)--> expired
```

- 终态：`applied/partial/failed/cancelled/expired`，不可再领取/回传/取消，段不可再下载。
- 过期是**惰性**的：列表/详情/领取/下载/回传/取消路径先执行 `UPDATE ... WHERE status IN ('pending','claimed') AND expires_at <= now`，没有后台清理循环。
- 取消/过期不删除磁盘文件；本切片没有包清理任务。

## 幂等与重试建议

| 操作 | 重复调用结果 |
|---|---|
| 创建 | 每次新建任务（无幂等键） |
| 领取 | 已 claimed 且未过期 → 200 同一任务；终态/过期 → 409 |
| 回传 | 完全相同结果 → 200；不同结果/终态 → 409 |
| 取消 | 已 cancelled → 200；其他终态 → 409 |
| 段下载 | 活动态可重复下载，字节一致；终态 → 409 |

Worker 重试建议：领取 409 时用详情确认是否已终态/过期；回传 409 时不要改结果再试，先核对是否已回传不同结果；网络超时后先 `GET /{id}` 对账再决定。

## 存储与路径安全

- 根目录：`<dataDir>/device-apply-packages/`。
- 每个任务一个子目录 `<taskId>/`（taskId 为服务端生成的 UUID），段文件名固定 `masterTxt.bin` / `v2Backup.bin`；目录 0755、文件 0600。
- 数据库只存相对路径 `<taskId>/<kind>.bin`；读取时要求相对路径与「任务 ID + 固定文件名」**逐字相等**，再做根目录包含校验，任何越界/篡改都拒绝。
- 段写入使用 `O_CREATE|O_EXCL`，写完 `Sync`，复算 SHA-256 与字节数；失败删除整个任务目录后再返回错误。
- 打开下载文件后再次核对常规文件与大小等于账本值；路径不回显给客户端。

## 错误码

| HTTP | code | 触发条件 |
|---|---|---|
| 400 | `INVALID_PARAM` | manifest 非法/未知字段、targetDeviceId/protocolVersion/rulesFingerprint 非法、段 kind/bytes/sha256/expectedReadback 非法、缺段/多段/重复字段、status/limit/kind 非法、readback 非对象、deviceLocalHashes 非法、message 超长 |
| 400 | `INVALID_CURSOR` | 列表 cursor 非法 |
| 400 | `INVALID_BODY` | 回传请求不是合法 JSON 或 Content-Type 不是 application/json |
| 401 | `UNAUTHORIZED` | 缺/错 Bearer（全局鉴权） |
| 404 | `NOT_FOUND` | 任务 ID 不存在，或段未在该包中声明 |
| 409 | `STATE_CONFLICT` | 非法状态迁移、终态不可变更、结果不同、终态/过期不可下载 |
| 413 | `TOO_LARGE` | 整个 multipart 请求体超过 66MiB |
| 500 | `INTERNAL` | 数据库/磁盘/文件与账本不一致（不泄露内部路径） |

## 未实现项

- **Android Worker**：没有 WorkManager 周期拉取、充电+Wi-Fi 约束、前台「立即应用」、回读/清理临时包。
- **配对与设备身份**：没有短码/二维码、没有设备级 token/授权、没有领取者字段；targetDeviceId 只是标签。
- **真机同步**：未在 `qimeng_api35` 或用户真机验证后台领取/应用/回传。
- **包清理**：取消/过期/回传后不删除段文件；无磁盘配额/GC。
- **Web/任务中心**：没有结果包列表/详情 UI。
- **分段续传**：段下载不支持 Range；v2 备份中断后整段重下。
- **创建幂等键**：重复登记会新建任务。
- **安装状态**：本切片源码与协议已交付，**未安装**到现装 PC/手机。

## 数据与验证边界

- migration `0024_device_apply_tasks` 只新增 `device_apply_tasks`/`device_apply_segments` 两表与索引，不改历史迁移。
- 测试使用临时 SQLite、临时包目录与合成字节（含 1KB 段），覆盖创建/段 SHA/超限/指纹不符、列表过滤与游标、领取幂等/过期、段下载头与路径安全、回传终态/重复回传、取消、404/409/413；不访问 8420 真库、真实设备或真实维护目录。
- 本地未跑业务 `go test`（仓库规定走云端 CI）；已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet` 与文档/隐私/迁移检查。实际测试结果以云端 CI 为准。
