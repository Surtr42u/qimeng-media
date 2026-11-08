# API：设备结果包（PC→手机无前台维护）

> [API 目录与全局约定](../GUIDE_API.md)。字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；本页解释结果包结构、领取/回传语义、状态机、幂等、存储与错误码。决策见 [ADR-0055](../adr/0055-手机无前台维护结果包协议.md)。

设备结果包是 PC 生成、手机领取并应用的**服务端账本 + 段文件存储**：PC 把作者主表 TXT 与 v2 便携备份登记为一个包，手机按 `targetDeviceId` 领取、下载段、应用后回传结果。服务端 API 与 SDK 已交付（P4 第一刀）；Android Worker 源码已于 task-17 交付，本轮补上全量分页领取、本机设备令牌生成与最近任务卡片，但**未安装、未真机验证**。设备级授权依赖专用 AI 客户端（`device.apply` + `device_ids`），短码/二维码配对仍未实现；服务端仍不自动清理段文件。

## 能力边界（先看这里）

- **Worker 源码已交付、未真机验证**：Android 端（WorkManager 周期 + 立即执行、设备标签/专用令牌、维护目录门禁、领取/下载/应用/回读/回传）源码在本仓库 `android/core/data/.../device/`；未安装、未在 `qimeng_api35` 或真机运行，Doze/厂商后台限制、后台拉起内嵌 FGS、SAF/共享存储授权撤回仍待实测。
- **设备级授权（P1）**：`targetDeviceId` 仍是不透明标签（服务端只校验 1–100 个 `A-Za-z0-9._:-` 字符）；专用 AI 客户端经 `device.apply` scope 与 `ai_clients.device_ids` 做设备级访问控制，管理员会话不受限。短码/二维码配对与设备证明仍未实现，不能把标签本身当身份认证。
- **端点无独立开关**：只读写本机 SQLite 与 `<dataDir>/device-apply-packages/`，不出网、不执行应用；默认即可用。
- **段字节不入库**：SQLite 只存 kind/bytes/sha256/相对路径与任务状态；正文在 DataDir 下，删除/清理任务留给后续阶段。
- **过期与取消只改账本**：到期或取消不会删除磁盘段文件；本切片没有 GC/清理任务，磁盘占用需后续维护阶段处理。
- **未安装/未真机验证**：服务端源码、协议与 SDK 已交付；Android Worker 源码已交付但未安装。现装版本见 [HANDOVER](../HANDOVER.md)。
- **权威状态校验在服务端**：回传结果时服务端读库内绝对计数与 manifest 可选 `expectedState` 比较，并核对手机上报的 `deviceLocalHashes` 与登记段摘要；校验结果单独存独立表 `device_apply_verifications`（migration 0026），不参与结果重放比较。

## 鉴权

七个端点全部走全局 Bearer（`Authorization: Bearer <token>`），无 `security: []` 豁免。结果包包含作者主表与完整 v2 备份（业务数据），按管理面数据对待。 ADR-0057 P1 起也可使用专用 AI 客户端 Bearer（`device.apply` scope + `ai_clients.device_ids` 绑定）；管理员会话不受设备级限制。

## Android Worker（源码已交付，未真机验证）

task-17 在 Android 侧新增了设备结果包 Worker（源码路径 `android/core/data/src/main/java/media/qimeng/app/core/data/device/`）与设置面（数据管理 → 资料备份与归档 → 设备领取）。本轮**未安装、未在 `qimeng_api35` 或用户真机验证**；下列为源码契约与已知边界。 后台执行限制与待真机验证项另见 [Android 后台执行分析](../android-background-execution.md)。

### 设备级授权与令牌

- 设备标签（`targetDeviceId`）与**专用设备 AI 令牌**保存在 App 的 client_prefs DataStore；令牌只用于 `device.apply` 设备端点（列表/领取/详情/段下载/回传）。导入作者 TXT / v2 备份与两代备份导出仍使用当前登录会话。
- **领取前先校验本地前置**（2026-11-08 task-21 P2-1）：登录会话、已持久化维护根、所有文件访问任一缺失时，客户端只记录本机提示并结束，**不发 claim**；任务保持 pending，用户在设置/维护页修复后点「立即应用」即可继续，不消耗任务、不产生误导性 failed。
- **本机模式可直接生成设备令牌**（2026-11-08）：本机内嵌单机模式的设置面新增「生成本机设备令牌」，经服务端 HTTP 管理端点（管理员会话）列出/复用/轮换标签为 `android-local-<deviceLabel>` 的 AI 客户端，scope 为 `device.apply` 并绑定本机设备标签，然后把明文令牌持久化到本机 prefs（**不显示明文**）。该操作要求本机管理员会话，但生成后设备端点只使用设备令牌，**不回退登录会话**。远端模式仍由远端服务端的管理员生成令牌并录入。
- 设置面只显示令牌「已配置/未配置」状态，**不回显明文**；可输入新令牌替换或清除已保存令牌。
- 最近任务卡片（只读，最新 20 条）：使用设备令牌读取该设备的任务摘要，不展示段内容与结果正文；用于本机确认后台领取是否发生。
- 未配置令牌时不提供「回退登录会话 token」路径：设置页明确提示登录会话 token 不能用于设备级授权。导入作者 TXT / v2 备份仍使用当前登录会话（本机内嵌实例或用户连接的 NAS），与设备令牌是两套凭据。
- 服务端在 ADR-0057 P1 起校验 AI 客户端 `device.apply` scope 与 `ai_clients.device_ids`；管理命令示例：`qimeng ai-client create -label phone-main -scopes device.apply -device-ids phone-main`（明文令牌只输出一次）。未绑定设备的客户端不能使用设备端点。
- 令牌明文落盘的威胁模型与登录 token 相同（单用户内网/已解锁设备不在防御范围）；不打日志、不写进结果包、不进导航参数。撤销/轮换走 `qimeng ai-client revoke/rotate`，服务端缓存窗口内生效。

### 调度与执行

- 周期链 15 分钟（WorkManager 系统下限）+ 一次性「立即应用」链；两条 unique work 各自串行，`DeviceApplyRunner` 再用进程内 Mutex 串行跨链执行。
- 远端服务器要求 `CONNECTED` 约束；本机回环模式（127.0.0.1:18430 / localhost:18430）放宽为 `NOT_REQUIRED`，因为内嵌服务不依赖系统网络状态。Worker 本身不升级前台服务。
- 本机模式先 `EmbeddedServerController.ensureStartedIfLocalMode` 并等待端口就绪；后台 FGS 启动被系统拒绝时记录失败并等待重试，不假装成功。
- Doze / App Standby / 厂商后台策略会让周期任务延迟；不能承诺精确时刻执行。设置页提供手动「立即应用」入口。

### 固定流程

1. 读取设置；设备标签/令牌不完整则不发起任何设备端点请求。
2. 本地前置门禁（登录会话 + 已持久化维护根 + 所有文件访问）——**在 claim 之前**；不满足只记录本机提示并结束，任务保持 pending，用户修复后「立即应用」重试（不消耗任务、不回传 failed）。
3. 同时查询 `GET /api/v1/device/apply-tasks?targetDevice=...&status=pending` 与 `status=claimed`：claimed 且未过期=上次领取后进程被杀/回传持续失败的可恢复任务（服务端 claim 对已 claimed 幂等 200）；客户端按 `nextCursor` 翻页（每页 100，硬上限 20 页 = 2000 条任务），超过上限显式报错而不是静默只用首页；再按目标/状态/到期二次过滤，终态跳过。
4. `POST /{id}/claim`（pending 领取或 claimed 幂等恢复）→ `GET /{id}`；校验目标、协议版本（仅 1）、规则指纹格式、段清单（1–2 段、kind 不重复、bytes/sha256 格式）。
5. 下载全部段到 App 缓存，复核声明 bytes 与 SHA-256；任一段不符即回传 `failed`，不应用任何段、不做「尽量应用」。
6. 先调用本机维护目录导出「当前/上次」两代备份；失败即中止，不写业务数据。
7. `masterTxt` → `POST /api/v1/authors/import-txt`（文件名固定为 `作者总表.txt`，同名片段覆盖并由服务端统一重建）；409 冲突不自动选择 keep/remove，回传 `partial/failed` 并提示用户到作者主表页处理。
8. `v2Backup` → `BackupValidator` 真校验链 → `POST /api/v1/import/qimeng-backup`；校验失败只让该段失败，不阻塞其他段。
9. 按 manifest `expectedReadback` 与导入响应逐段比较；应用成功的段再导出一次刷新「当前」代。`expectedState`（若声明）不参与手机侧判断，由服务端在回传时与库内绝对计数比较。
10. `POST /{id}/result` 回传终态、readback、`deviceLocalHashes` 与 message；`finally` 删除 App 缓存临时段文件（删除失败不影响终态）。

### 回读约定

- 指标来源为导入响应的服务端权威计数，不臆造未返回的指标；`expectedReadback` 按段与对应指标比较，不拿整包平铺对象比较。
- 单段包 `readback` 平铺，如 `{"authors":3,"assetAuthorRefs":4}`；双段包按段嵌套，如 `{"masterTxt":{...},"v2Backup":{...}}`，避免同名键互相覆盖。
- 当前可映射键：masterTxt 的 `authors`/`authorsImported`、`assetAuthorRefs`/`filesMatched`；v2Backup 的 `viewEvents`/`eventsReplayed`、`authors`/`authorsImported`、`assetAuthorRefs`/`authorRefsImported`、`tags`/`tagsImported`、`assetsMatched`、`mediaFilesTotal`。包声明了本端不认识的键时按「回读不符」处理（partial），不静默忽略。
- **计数为增量口径**：上述指标来自本次导入响应（如重复导入的事件回放为 0），不是应用后的库内绝对值；PC 侧声明 `expectedReadback` 时应按「本次期望增量」或可容忍幂等重放的语义设计，否则幂等重放会显示为回读不符（partial）。需要绝对值断言时改用 manifest 的 `expectedState`（见下），由服务端读库比较。

### 权威状态与摘要校验（服务端）

- manifest 的 `expectedState` 是可选的**应用后绝对计数**（键：`assets/authors/assetAuthorRefs/tags/assetTagRefs/viewEvents`；值非负整数；未知键或负数 400）。它和 `expectedReadback` 语义不同：后者是本次导入的增量回读，前者是「应用完成后库里应该有多少」的绝对断言。
- 回传结果时服务端读取权威数据库计数（绝对值），与 `expectedState` 逐键比较；同时检查 `deviceLocalHashes` 是否覆盖全部已登记段并逐段等于登记摘要（少报/不符即不通过）。
- 校验结果写入独立表 `device_apply_verifications`（migration 0026），**不进入 `result_json` 的重放比较**：相同结果重放仍按原样判断字节相等/幂等，不会因为重建计数而变成 409。

### 失败、重试与幂等

- 网络/服务端 5xx/本机服务未就绪：WorkManager retry（最多 2 次退避），耗尽后等待下一周期；不把周期链标 failure 造成永久停摆。
- 设备令牌 401/403：记录本机状态并停止（不重试），等待用户在设置页轮换令牌。
- 结果回传 409：先 `GET /{id}` 对账；任务已终态说明此前某次执行已回传，按完成处理，不覆盖既有结果。
- 进程被杀/回传持续失败后的恢复：下一次运行会同时查询 pending 与 claimed，claimed 未过期则幂等 claim 后重新下载、应用与回传；已终态任务跳过。**不持久化段级进度**，恢复靠重新下载并重放整包（服务端同名 TXT 重建、v2 事件身份幂等）。
- 登录会话/维护根/所有文件访问在 claim 前校验，不满足不 claim；应用过程中授权被撤或写失败才回传 `failed`；应用成功但应用后刷新本机两代备份失败回传 `partial`，提示用户在维护页重新导出。

### 未实现与待真机验证

- 未安装到现装手机；未在 `qimeng_api35` 或真机验证后台周期触发、Doze/厂商限制、进程被杀后恢复、后台拉起内嵌 FGS、SAF/共享存储授权撤回与两代备份写入。
- 短码/二维码配对、设备证明、包清理 GC、Web 任务中心、段级进度持久化仍未实现。
- device 包 JVM 单测 40 项（2026-11-08 task-21 后新增领取前登录/维护根/全文件访问前置、pending+claimed 过期过滤与 claimed 恢复过滤；其中 37 项纯逻辑已在开发机 kotlinc+JUnit 实跑通过，设置持久化 3 项依赖 DataStore 运行时由 Gradle 单测执行）。`:feature:manage:compileDebugKotlin`/`:app:compileDebugKotlin` 此前编译通过；本次 task-21 改动后需云端 CI 重新编译验证。未跑全量 Gradle、Lint、设备验收。

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
  "expectedState": { "assets": 120, "authors": 8, "assetAuthorRefs": 130, "tags": 20, "assetTagRefs": 45, "viewEvents": 900 },
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
| `expectedState` | 否 | 应用后绝对计数，≤16KiB；仅支持 `assets/authors/assetAuthorRefs/tags/assetTagRefs/viewEvents`，值须为非负整数，未知键/负数 400 |

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

返回任务详情：账本字段、段清单（`kind/bytes/sha256/expectedReadback`）与已回传的 `result`（含 `reportedAt = finishedAt`）。已回传的结果还暴露 `authoritativeState`（回传时读到的权威计数，旧结果/读取失败为 null）、`expectedStateMatches`（是否逐键等于 manifest `expectedState`；未声明或读取失败 null）、`expectedStateDiff`（不一致时逐键 `{expected,actual}`）与 `hashVerified`（`deviceLocalHashes` 是否逐段匹配登记摘要；未上报摘要 null）。任务不存在 404 `NOT_FOUND`；到期任务先惰性标记 `expired`。

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

成功 **200**，任务进入 `applied/partial/failed`，写 `finishedAt` 并保存结果；响应里的 `result.reportedAt` 等于 `finishedAt`。同一请求内服务端还会读取权威计数并校验段摘要，校验结果放在 `result.authoritativeState/expectedStateMatches/expectedStateDiff/hashVerified`（只读展示，不改变回传状态码）。

**重复回传语义**（文档化契约）：

- 已终态任务再次提交**完全相同**的结果（`status`、归一化 JSON `readback`、排序后的 `deviceLocalHashes`、`message` 全部一致）→ **200 幂等**，返回原任务，`finishedAt` 不变；校验结果沿用首次保存的验证快照，不重算、不因此产生冲突。
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

- **Android Worker 真机验证**：源码已有 WorkManager 周期拉取/立即执行、领取前本地前置校验、pending+claimed 全量分页（每页 100，上限 2000 条）、回读/临时包清理；但未安装、未真机验证。网络约束：远端 `CONNECTED`，本机回环模式 `NOT_REQUIRED`（内嵌服务不依赖系统网络状态）；未加充电/未计费 Wi-Fi 约束，Worker 不强制前台服务。
- **本机设备令牌流程**：单机模式已有「生成本机设备令牌」（管理员会话 + 复用/轮换 + 不显示明文），但未在 `qimeng_api35`/真机验证；远端模式仍需在远端服务端生成后录入。
- **权威状态校验**：`expectedState` 只做断言与展示，不因不匹配拒绝回传；未声明或读取失败时 `expectedStateMatches/expectedStateDiff/authoritativeState` 为 null，调用方不能把 null 当成功。
- **配对与设备身份**：短码/二维码配对、设备证明与领取者字段仍未实现；设备级授权由 ADR-0057 P1 的 `device.apply` scope + `ai_clients.device_ids` 承载，`targetDeviceId` 仍是不透明标签。
- **真机同步**：未在 `qimeng_api35` 或用户真机验证后台领取/应用/回传。
- **包清理**：取消/过期/回传后不删除段文件；无磁盘配额/GC。
- **Web/任务中心**：没有结果包列表/详情 UI。
- **分段续传**：段下载不支持 Range；v2 备份中断后整段重下。
- **创建幂等键**：重复登记会新建任务。
- **安装状态**：服务端源码、协议与 SDK 已交付；Android Worker 源码已交付但**未安装**到现装手机，未做真机验收。

## 数据与验证边界

- migration `0024_device_apply_tasks` 只新增 `device_apply_tasks`/`device_apply_segments` 两表与索引；migration `0026_device_apply_verification` 仅新增独立表 `device_apply_verifications`（task_id 主键、级联删除），不改历史迁移、不给任务表加列；旧版本回退时 `SELECT *` 扫描不受影响。`result_json` 仍是客户端结果的重放比较基准。
- 测试使用临时 SQLite、临时包目录与合成字节（含 1KB 段），覆盖创建/段 SHA/超限/指纹不符、列表过滤与游标、领取幂等/过期、段下载头与路径安全、回传终态/重复回传、取消、404/409/413，以及回传时的权威计数比较、`expectedState` 键/取值校验、摘要逐段校验与相同结果重放幂等；不访问 8420 真库、真实设备或真实维护目录。
- 本地未跑业务 `go test`（仓库规定走云端 CI）；已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet` 与文档/隐私/迁移检查。实际测试结果以云端 CI 为准。
- Android Worker：device 包 JVM 单测 40 项（2026-11-08 task-21 后：目标筛选、领取前登录/维护根/全文件访问前置、pending+claimed 过期过滤、段 size/SHA-256、授权缺失、回读比较、结果映射、两代路径、设置持久化）；其中 37 项纯逻辑用例已在开发机用 kotlinc+JUnit 实跑通过（设置持久化 3 项依赖 DataStore 运行时，由 Gradle 单测执行）。`:feature:manage:compileDebugKotlin` 与 `:app:compileDebugKotlin` 编译通过。未安装、未跑全量 Gradle/Lint、未在 `qimeng_api35` 或真机验收，也未访问真实维护目录/8420 真库。
