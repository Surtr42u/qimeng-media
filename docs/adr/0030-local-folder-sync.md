# ADR-0030：本机文件夹自动同步通道（服务端侧目录监测）

## 背景（Context）

NAS 未就位过渡期，手机与电脑各自存有同一批文件，双份靠手工搬运消除不掉（App 手动上传只自动化了"入库"半程，仍要逐批选文件）。用户 2026-10-01 口述立项，需求原意五条：

1. 指定电脑上一个文件夹为同步源，服务端自动监测并把其中的图片/视频**自动上传入库**；
2. 上传**成功后源文件从同步源删除/移走**（同步源不堆积）；
3. **失败则保留**在同步源，等用户手动处理（绝不静默丢文件）；
4. App 端既有手动上传**保留为备选安全通道**（自动通道出问题时有退路）；
5. **TXT 表（作者片段 TXT）同样纳入**自动同步，不只媒体文件。

补充口径：同步源下的子文件夹名一般就是库名，应**自动读取**而不要求先手工登记映射；手机与电脑库名一致（App 归档即按 `<归档根>/<库名>/` 落盘），文件夹名与库名可以逐字对上。

备选形态两条：桌面壳（Tauri）watcher 监听后回调服务端 vs 服务端直接监测目录。

## 决策（Decision）

1. **形态 = 服务端侧纯轮询监测**：不做桌面壳 watcher——服务端就在电脑本机，少一层进程间协作；直接复用服务端内部上传管线；App 内嵌形态（ADR-0015）天然继承同一条通道。不挂 fsnotify：轮询周期默认 30s，对齐扫描器「轮询兜底」的既有现实，不引入文件系统通知的跨平台差异面。
2. **结构与库名自动匹配**：同步根直接子文件夹名 = 库名。对每个库的 name 做与 Android 归档**逐字相同**的 sanitize（trim + `\/:*?"<>|` 九字符→`_`，锚点 Android `InboxFileStore.sanitizeLibraryDirName`，纯函数单测对齐），再与文件夹名精确比对（大小写敏感）；0 命中/多命中/库停用/COS 库 = 该文件夹失败原地保留（宁可不动，绝不猜）。文件夹内媒体**递归**处理，子目录相对路径映射库内子目录（与 Web 拖拽目录上传同型）。
3. **入库语义与直传完全一致**：`filing.ValidateUpload` 四道校验同一函数、`upload.autoAccept` 同一道闸（false 时媒体暂停处理，TXT 导入不受影响）、`upload.max_bytes` 同一上限、`ResolveConflict` 同名自动改名 "名 (2).ext" 永不 409（DOMAIN_RULES §9 既有口径，不另设规则）、`WithLibraryGate` 库锁内落位 + 落位后 size 复核（对齐第四百一十九笔 Seal 落位复核硬化）、成功走 `ingestPlacedUpload`（sysmon/upload.done/library.changed/修订号/富化全同款）——自动通道不是旁路，是直传管线的第三个入口。
4. **成功移走、失败保留**：媒体成功后源文件 move 入库根（从同步源消失）；TXT 成功后移 `<同步根>/.synced/`（点前缀内部目录，扫描与同步均不处理，同名加序号绝不覆盖）；失败文件原地保留、每轮自动重试。可见性 = `GET /api/v1/local-sync/status`（enabled/root/intervalSeconds/paused/lastError/lastCycleAt/syncedTotal/counts{waitingStable,failed,ignored}/items 上限 500/recentSynced 最近 20）；另有 `POST /api/v1/local-sync/trigger` 手动触发一轮（202 异步；未启用 409 code=`LOCAL_SYNC_DISABLED`）。
5. **TXT keep 语义**：同步根直接下 `*.txt` 走既有 `POST /authors/import-txt` 统一重建语义，`conflictResolution` 恒 **keep**（无人值守通道绝不 remove——重导入保护语义下永远并回，不静默丢弃既有上传条目）；仅 UTF-8（剥 BOM），10MB 护栏；导入成功但归档移动失败不算失败，下轮幂等自愈。
6. **安全**：通道由 `QIMENG_LOCAL_SYNC_ROOT` 显式配置启用（空=关，唯一开关形态，不设独立 enabled 开关防矛盾组合）；同步根与任何库根/DataDir **双向重叠禁令**（一方包含另一方或相等一律拒绝，防两套写管线互相搬动对方文件）；隐藏条目（点前缀，含 `.synced`）与符号链接不处理；子路径经 NormalizeRelPath + PathWithinRoot 规范化并限制在同步根内；同步根不存在 = 通道级错误（status lastError 可见），**绝不代建目录**。
7. **实现落位**：库名匹配/目录扫描/重叠检查为纯逻辑进 `server/internal/localsync/`（带单测）；轮询编排与运行态进 `server/internal/httpapi/`（trash_sweeper 同型先例）；无 DB 迁移、无新依赖；App 端零改动。

配置三键（yaml `local_sync.root/interval/stable_age` / env 同名 `QIMENG_*`，间隔值非法启动即报错）：`QIMENG_LOCAL_SYNC_ROOT`（空=关）、`QIMENG_LOCAL_SYNC_INTERVAL`（默认 30s）、`QIMENG_LOCAL_SYNC_STABLE_AGE`（默认 60s——文件 mtime 年龄≥门槛且跨轮 size/mtime 不变才处理，防半截拷贝入库）。

## 状态（Status）

Accepted（2026-10-01）

## 后果（Consequences）

- **积极**：双份手工搬运消除，同步源即"已入库"的镜像；App 零改动且手动上传保留为备选安全通道；入库走同一管线，SSE `library.changed` 广播后 Web/Android 双端自动刷新；失败自愈——文件被占用（Windows 独占打开）、半截拷贝等下轮重试即可，不需人工干预。
- **负面权衡（接受并记档）**：① 运行态在内存，服务端重启后一轮内重建（attempts 等归零重来，行为幂等无实害），`syncedTotal` 归零；② 崩溃窗口——源文件 move 已完成但资产注册未落库时文件已在库外，由扫描器 ≤5min 全量轮询兜底补登记（ADR-0004 既有机制）；③ GBK/GB18030 TXT 不支持（仅 UTF-8，解码需新依赖 x/text，触发再议）；④ `upload.autoAccept=false` 期间媒体暂停处理，且此前失败条目的失败原因被 waiting 覆盖（TXT 不受影响）。
- **后置项（触发再立项）**：importTxt 编排按 ADR-0019 下沉后本通道 runner 随迁 + `.synced` 目录名/根级 TXT 判定跨包双常量收敛；GB18030 解码（需新依赖 x/text 再决策）；fsnotify 秒级响应；sysmon 指标化；同步配置编辑 UI（v1 走 yaml/env+重启，同备份调度先例）。
- **联动文件**：`api/openapi.yaml`（2 路径 + LocalSyncStatus/LocalSyncItem 两 schema）、`server/internal/localsync/`（match.go/scan.go/doc.go + 单测）、`server/internal/httpapi/`（localsync.go、localsync_runner.go、localsync_test.go）、`server/internal/config/config.go`、`server/cmd/qimeng/main.go`（接线）、`web/src/components/manage/LocalSyncCard.tsx`、`web/src/hooks/use-local-sync.ts`、`web/src/pages/MaintenancePage.tsx`、`docs/GUIDE_API.md`、`docs/SECURITY.md`、`docs/DOMAIN_RULES.md`（§9 括注）、`docs/HANDOVER.md`、`deploy/README.md`、`docs/CHANGELOG.md`（第四百二十四笔）、`docs/adr/INDEX.md`。
