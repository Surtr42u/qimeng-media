# Android 后台执行、文件权限与内嵌服务限制（分析 + 实现说明）

> 状态：2026-11-07 用户追加「Android 后台执行与 AI 权限适配」要求后的实现与分析说明。**本轮只交付代码与文档，未做完整测试、真机验收或安装**；实现状态见 [HANDOVER](HANDOVER.md)，协议以 [api/openapi.yaml](../api/openapi.yaml) 与 [设备结果包文档](api/device.md) 为准。

## 1. 目标与边界

- 复用现有 Go API、设备结果包协议与维护任务账本；不建立第二套业务系统，不新增手机入站端口，不要求 App 前台。
- Android 内嵌 Go 后端长期保留（当前无 NAS）；PC 与 Android 使用同一套业务规则，各自独立运行。
- 后台能力只做系统允许的事：Doze/App Standby/厂商后台限制、WorkManager 约束、SAF 授权边界内执行；不绕过 Android 系统权限。
- 不做下载器、不重构既有系统；必须由用户系统授权的操作保留 UI 入口。

## 2. 现状与相关实现

| 组件 | 现状 |
|---|---|
| 内嵌 Go 服务 | `EmbeddedServerController.ensureStartedIfLocalMode` + `LocalServerWarmup`；登录/壳层自检幂等拉起，后台使用前需显式 ensure 并等端口就绪 |
| 既有后台任务 | `EventSyncWorker`/`EventSyncScheduler`、上传相关 Worker；`QimengApplication` 自定义 `Configuration.Provider + HiltWorkerFactory`，Manifest 移除默认 initializer |
| token 存储 | `ServerConfigDataSource`（DataStore）保存服务端地址与登录 token；本轮新增设备标签/专用设备令牌键，不复用管理员会话 token 做设备级授权。设备令牌由服务端主机 CLI（`qimeng ai-client create`）写入该服务端 SQLite，**没有 HTTP/Android 创建入口**；因此纯本机内嵌单机模式暂无法配置令牌，后台领取当前仅远端模式（连接 PC/NAS 服务端）可用 |
| 维护目录 | `MaintenanceFolderRepository`/`BackupDirAccess`/`MaintenanceBackupSlots`：当前/上次两代 + 旧 flat3 只读兼容；写入依赖用户已持久化的维护根 + App 已声明的所有文件访问权限（`MANAGE_EXTERNAL_STORAGE`），不新增任意路径写入 |
| 设备结果包服务端 | `/api/v1/device/apply-tasks*`（migration 0024）；领取幂等、终态不可覆盖、段下载校验 sha256/size；详见 [设备结果包](api/device.md) |
| 设备授权 | ADR-0057 P1：AI 客户端 scope `device.apply` + `ai_clients.device_ids`；设备令牌只能访问绑定设备的结果包（创建/列表/详情/领取/段下载/回传/取消均校验），管理员会话不受限 |

## 3. Android 系统限制（决定哪些能后台做）

- **存储**：Android 10+ 分区存储；App 已声明 `MANAGE_EXTERNAL_STORAGE`（“所有文件访问”），现有维护根写入依赖该权限（`MaintenanceFolderRepository.requireAllFilesAccess`）。设备 Worker 在 **claim 之前**检查“已持久化维护根 + 所有文件访问”，任一缺失/被撤销则只记录本机提示、任务保持 pending，用户授权后「立即应用」重试（不消耗任务、不产生误导性 failed）；上传等路径仍保留既有 `READ_EXTERNAL_STORAGE`（maxSdk 32）运行时申请。
- **SAF 取舍**：两代备份布局（当前/上次）只存在于维护根，Worker 的导入前保护必须写维护根；App 的 SAF tree 持久授权备份槽不是两代布局，**不接受仅 SAF 备份槽作为应用前保护**（缺维护根时在 claim 前阻塞并提示到维护页授权）。后续若支持纯 SAF 目录，需要先定义两代目录的 SAF 授权模型。
- **后台调度**：WorkManager 周期任务系统下限 15 分钟；Doze/App Standby 下可能延迟；厂商后台限制可能更强。不能承诺精确时刻执行，也不强制前台服务。
- **进程与内嵌服务**：App 被杀后 Worker 可在系统允许时重启，但内嵌 Go 服务需要重新 ensure/等端口；若设备禁止后台启动或服务不可用，Worker 记录失败/延迟重试，不假装成功。
- **通知/前台**：非必须不申请通知或前台服务；用户可在 UI 主动触发一次立即执行（前台场景）。
- **电量/网络**：Worker 使用 WorkManager 约束（默认 CONNECTED；可选 UNMETERED/充电），不保证立即执行。

## 4. 执行矩阵（后台 Worker vs 必须 UI）

| 步骤 | 后台 Worker | 必须 UI/系统授权 |
|---|---|---|
| 列出目标设备 pending 结果包 | ✅ 有设备令牌即可 | — |
| 领取/下载段/校验 sha256+size | ✅ | — |
| 内嵌 Go 服务拉起与健康检查 | ✅ 尝试；失败则记录 | — |
| 应用作者主表 TXT（`/authors/import-txt`） | ✅ 调用正式 API | — |
| 应用 v2 备份（`/import/qimeng-backup`） | ✅ 调用正式 API | — |
| 写入库根/备份目录 | ✅ 已持久化维护根 + 所有文件访问 | ❌ 首次授权/权限被撤销 → UI（所有文件访问设置页/维护目录选择） |
| 回读与回传 result（applied/partial/failed） | ✅ | — |
| 设备标签/设备令牌配置 | ❌ | ✅ 维护页「设备领取」（用户粘贴/输入，明文只显示一次；保存后只显示已配置/替换/清除）。本机内嵌单机模式暂无法在手机侧创建令牌，需远端模式 |
| 领取前本地前置（登录会话/维护根/全文件访问） | ✅ 检查；不满足只记录提示不 claim，任务保持 pending | ❌ 用户修复：重新登录、维护页授权、系统「所有文件访问」 |
| claimed 恢复（进程被杀/回传失败） | ✅ 同时查询 pending+claimed，claimed 未过期幂等 claim 后继续 | — |
| 后台领取开关/立即执行 | ❌（可后台） | ✅ 设置页/维护页入口（用户显式开启） |
| 历史备份恢复入口/迁移核对 | 可后台校验 | ✅ 展示与人工确认在 UI |

## 5. 本轮实现（源码）

新增/修改（以工作树为准，统一由 Lead 提交）：

- `android/core/data/.../device/`：`DeviceApplyRunner`（编排本地前置→列表→领取/恢复→下载-应用-回读-回传）、`DeviceApplyPreconditions`（claim 前登录会话/维护根/全文件访问门禁）、`DeviceApplyWorker`/`DeviceApplyScheduler`/`DeviceApplyWorkSpec`（WorkManager 周期+立即、约束、unique work）、`DeviceApplyRemote`（SDK 设备端点，pending+claimed 分状态查询）、`DeviceApplyPolicy`（可领取/可恢复过滤/设备标签/开关）、`DeviceApplyAuthorization`（设备标签+令牌校验）、`DeviceApplySegmentVerifier`（size/sha256）、`DeviceApplyResultPlanner`（applied/partial/failed + readback）、`DeviceApplyLocalBackup`（导入前本地备份/清理）、`DeviceApplyReadback`、`DeviceApplyBootstrapper`、`DeviceApplySettingsRepository`、`DeviceApplyModels`。
- UI：`feature/manage/DeviceApplyScreen.kt`/`DeviceApplyViewModel.kt` + `MaintenanceScreen` 入口：设备标签、设备令牌（口令式，保存后只显示已配置/替换/清除，不回显明文）、后台领取开关、立即执行；无令牌时提示不能用会话 token 做设备级授权。
- 依赖接线：`QimengApplication`、`core/data/di/DataModule`、`SdkBackupRepository`、`LegacyImportModels` 的最小适配；JVM 单测（纯逻辑）随实现补充，见 §7。

## 6. 设备令牌与 Go 权限衔接

1. 管理员在 **运行目标服务端的 Go 主机**执行 `qimeng ai-client create --label phone-main --scopes read,device.apply --device-ids phone-main`（参数以 `server/cmd/qimeng/ai_client_cmd.go` 为准），只显示一次明文令牌。令牌写入该服务端本机 SQLite，因此只能用于同一服务端实例；纯手机内嵌单机模式（令牌库在手机上）目前无法用 PC 命令创建令牌，仅远端模式可用；
2. 在手机设置页填写设备标签（与 device_ids 一致）与设备令牌，开启后台领取；
3. Worker 用该令牌调用设备结果包端点；Go 侧按 scope+device_ids 校验，越权请求 403 且写审计；
4. 撤销/轮换：`qimeng ai-client revoke/rotate <id>`；服务端有短缓存，撤销在数秒内生效；
5. 管理员会话仍可人工操作，但设备后台执行不应长期使用管理员 Bearer。

## 7. 待真机验证（本轮未做）

- Doze/App Standby/厂商后台限制下周期 Worker 的实际触发与延迟；进程被杀后重启、内嵌 Go 服务重拉。
- SAF 持久授权的回收/迁移/多目录场景；写入失败与 partial 结果回传。
- 两代/旧 flat3 备份目录在 Android 上的读取与恢复（与 §两代备份修复联动）。
- 真实设备结果包端到端：PC 登记 → 手机后台领取/应用/回读/回传 → PC 查看；断网/重复领取/过期/取消/结果冲突。
- claimed 恢复：应用中途杀进程/回传持续失败后，下一周期或手动「立即应用」能否通过 pending+claimed 查询幂等 claim 并收敛到终态；任务 TTL 过期边界。
- 领取前前置门禁：未登录/维护根被清除/全文件访问被系统撤销时任务保持 pending，修复后能继续（不产生 failed）。
- 设备令牌替换/清除后 Worker 的 401/403 行为与恢复；本机内嵌单机模式令牌获取限制的 UI 提示是否足够清晰。
- Gradle assemble/test/lint 与云端 CI；真实库/真实媒体；安装与电池策略。
- UI 历史备份列表/迁移入口是否需要在后续版本补齐（当前仅仓库能力，避免本轮扩大 UI 范围）。

## 8. 风险与结论

- 后台执行受系统调度与厂商策略影响，只能尽力而为；失败必须如实回传，不能静默成功。
- 设备级令牌降低管理员 Bearer 扩散风险，但服务端仍应继续补齐 scope 协议化/HTTP 管理端点与审计查询（ADR-0057 P1+）。
- 文件写入不绕过 Android 系统权限（所有文件访问 + 维护根）；仅 SAF 备份槽不足以提供两代应用前保护，需维护根；必须用户系统授权的操作保留 UI，符合用户本轮要求。
- 设备令牌只有服务端主机 CLI 创建通道，纯本机内嵌单机模式暂时无法配置（后台领取仅远端模式可用）；这是本轮已知能力边界，后续需补凭据管理通道或本机托管令牌方案。
- 领取前本地前置与 pending+claimed 恢复已按源码实现，但尚未真机/断网演练；恢复路径依赖服务端 claim 幂等与整包重放，不持久化段级进度。
