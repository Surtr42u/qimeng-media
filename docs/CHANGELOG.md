
## feat(tools): AI 接入层本地文件引用、执行权限与工具契约规范化（2026-11-10 第七百二十三笔）

执行 AI：DeepSeek V4.1 Flash（主代理：接口协调/集成/文档/验收；子代理 A：本地权限、fileRef、Recipe；子代理 B：注册表、按需上下文、规则、MCP）

- 本地执行域：新增 `QIMENG_AI_READ_ROOTS`/`QIMENG_AI_WRITE_ROOTS` 读写根分离（默认拒绝未授权目录，媒体根计入读根，工作根/`.test-temp/`/`build/` 始终可用）；写路径拒绝根以下符号链接/junction/reparse 与 `..` 逃逸，哈希/复制前后复核，默认不覆盖、不删源。未入库文件可在授权目录直接处理；已入库资产/作者/标签/业务关联仍只走 Go `/api/v1`。
- 逻辑存储与 fileRef：新增 `QIMENG_AI_STORAGES`、`fileref.py` 与 `files.storages`/`files.search`；只返回 storageId/相对路径/不透明引用（storageId+相对路径+size+mtime），不返回物理绝对路径；文件变化返回 `FILE_REF_STALE`，伪造/越界 `FILE_REF_INVALID`，storage 移除 `FILE_REF_UNAVAILABLE`。`media.probe`/`file.hash`/`media.transfer` 源/`research.media.probe.fileRefs` 可复用 fileRef，原 `path` 参数兼容。
- 原语统一契约：`PrimitiveSpec` 声明权限/只读/副作用/确认/超时；`execution.policy_denied_error` 对有副作用原语强制 `QIMENG_AI_READONLY`、`QIMENG_AI_DISABLED_TOOLS`（支持原语 ID）与 `QIMENG_AI_GRANTED_PERMISSIONS`，新增 `local.files.write`/`local.device`。recipe 恢复校验产物 bytes/SHA-256、running 副作用步骤返回 `RECIPE_STEP_UNCERTAIN`、工具/原语版本变化拒绝续跑；transfer 仅恢复上下文对相同内容幂等，`TARGET_EXISTS` 语义保持。
- 工具发现与规则：`files.*` 归 media 域；MCP 领域过滤始终保留 `platform.*` 与 `files.storages`，`tools/list`/`tools/call` 同源；`platform.rules` 支持有界点路径；catalog/context/describe 统一 `truncated` 标记，`platform.context` 优先加载 permissions/localFiles，再按需截断通用规则；工具状态拆分 registered/implemented/contract/runtime/locallyAuthorized/serverVerified；`rulepack.consistency_problems()` 自动检查规则引用、示例 Schema、playbook/recipe 权限覆盖与版本；`schema.py` 关键字与实际实现对齐并拦截未支持关键字。
- 修复 `research_kit` 目录工件与路径混用：`_directory_artifacts` 返回 `Path` 列表而不是已序列化字典，避免 `research.media.probe`/`research.avatar.fetch` 成功返回时 `TypeError`；新增 fileRef 成功探测回归。
- 版本/文档：`platformVersion`/`toolContractVersion` 1.4.0、`rulesVersion`/`rulepackVersion` 2026-11-10.3、48 个工具；`tools/ai_maintenance/README`、`docs/AI_PLATFORM_OPERATIONS`、`docs/AI_PLATFORM_TASKBOOK` §1.4/§4.4/§11、`docs/SECURITY`、ADR-0056 补充、HANDOVER/CAPABILITY_MAP 同批更新。
- 验证：`python -X utf8 -m unittest discover -s tools -p "test_ai_*.py"` 266 项通过（A 39 项、B 24 项、主代理跨模块 6 项）；`python tools/check_docs.py` 0 错误；`consistency_problems` 0 条。隔离合成测量（60 文件/3 层/真实 1 秒 mp4）：路径式 5 次调用/1799 字符/2 次绝对路径暴露 vs fileRef 3 次/1447 字符/0 次，两次流程均定位正确且 `media.probe` 成功；重复 probe+hash 载荷 508→546 字符（fileRef 字符串比短路径长），收益在调用次数、路径隐藏与复用，不宣称固定 token 百分比。
- 限制：未安装、未接真实 AI 客户端/MCP 客户端、未对真实媒体/8420/NAS/真机执行；stateless cursor 在 20000 条目扫描上限处只能提示收窄而不能翻完；ffprobe/ffmpeg 外部进程读取仍有检查后换链窗口；fileRef 是配置内自描述引用而非签名令牌。

## fix(tools): Windows 启动前预检 winnat 保留端口（2026-11-10 第七百二十二笔）

执行 AI：DeepSeek V4.1 Flash（子代理：Windows 启动端口预检）

- 新增 `tools/ensure-listen-port.ps1`（PowerShell 7.1+，无 5.1 回退）：启动前检查已有 IPv4/IPv6 监听与 `0.0.0.0` 绑定，识别 WSAEACCES 和 `netsh` 保留段；仅在确认无 WSL/Docker/Hyper-V 负载时临时停止 `winnat`，有负载时只打印手动命令，绝不结束进程。
- `_server-common.cmd` 三种启动模式在拉起新服务前调用预检（使用 `pwsh`，缺失时明确报错并停止）；设置 `QIMENG_LISTEN` 时保持用户值，预检按其中端口检查。启动器回归夹具同步复制预检脚本；新增合成 `netsh` 解析测试并接入 Windows CI。
- 文档：`server/README.md` 新增故障排查（保留段、`winnat`、退出码、`QIMENG_LISTEN`），`deploy/README.md`/`tools/README.md` 补入口。验证：真实 8420 占用识别（exit 3）、空闲 18420（exit 0）、合成保留段解析（pwsh 7.6.9）；未在活跃 WSL/Docker 环境验证自动停服分支。

## feat(tools): AI 工具平台 1.1.0：43 工具、执行器/设备/研究/回收站能力（2026-11-08 第七百二十一笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 工具注册表 29→43：新增 `maintenance.claim/heartbeat/step.record/artifact.record/complete`、`research.image.pipeline/search`、`research.author.probe/media.probe/avatar.fetch`、`trash.list/restore`、`asset.trash`、`asset.tags.replace`；`backup.export` 与 `device.apply.prepare/submit/status` 由 planned 转 supported。`platformVersion`/`toolContractVersion` 升 1.1.0，`rulesVersion`/`rulepackVersion` 升 `2026-11-08.2`。
- 错误目录新增 `PERMISSION_DENIED`/`TOOL_DEPRECATED`/`FORBIDDEN_SCOPE`/`LEASE_LOST`；工具权限与 Go scope 对齐（asset.write/asset.upload/asset.delete/tag.write/source.write/library.write/trash.restore/maintenance.execute），执行器工具以 `leaseToken` 作为能力凭据，只在 claim 结果出现。
- 文档：`tools/ai_maintenance/README`、`rules.json`、`docs/AI_PLATFORM_OPERATIONS`、`docs/AI_PLATFORM_API_GAPS` 与 HANDOVER §10、CAPABILITY_MAP 同批。验证：Python 164 项通过；未接真实 AI 客户端、未安装、未对真实库执行写入。

## fix(app): 设备任务全量翻页与本机模式设备令牌自助生成（2026-11-08 第七百二十笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 领取/待办列表改为沿 `nextCursor` 读到底（100 条/页，20 页硬上限 2000），修复只读第一页导致第 51 条之后任务永远轮不到；本地/远端共用同一 runner。
- 本机单机模式新增「生成本机设备令牌」：用内嵌实例管理员会话先 `GET /ai-clients` 复用/轮换，必要时创建 `android-local-<device>`（device.apply + deviceIds 绑定），明文 token 只写设置存储，不回填 UI/日志/state；远端模式不提供该入口。
- 设置页新增只读「最近设备任务」卡（20 条，设备令牌），不参与领取/应用；README 同步边界。
- 验证：`:core:data` device/token 测试 50 项通过，`:feature:manage`/`:app` `compileDebugKotlin` + `hiltJavaCompileDebug` 成功。未安装、未真机/模拟器验证；Doze/FGS/SAF 仍未验。

## feat(server): 设备结果包服务端权威状态校验（2026-11-08 第七百一十九笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- manifest 新增可选 `expectedState`（assets/authors/assetAuthorRefs/tags/assetTagRefs/viewEvents，非负整数；未知键/负数 400）；回传结果时服务端读库内绝对计数逐键比较，并核对 `deviceLocalHashes` 与登记段摘要（少报/不符记 `hashVerified=false`，不改变回传状态码）。
- 结果新增 `authoritativeState`/`expectedStateMatches`/`expectedStateDiff`/`hashVerified`；migration `0026_device_apply_verification` 只增 nullable `verification_json`，`result_json` 仍是幂等重放比较基准，相同结果重放不重算、不产生 409，旧行保持 NULL。
- 文档：`docs/api/device.md`、OpenAPI/SDK 由同批提交维护。验证：deviceapply/httpapi 定向测试（临时 SQLite、合成包）。未安装、未真机、未向真实库回传。

## feat(server): 维护任务外部执行器受控通道与 worker 恢复修复（2026-11-08 第七百一十八笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 新增 `POST /api/v1/maintenance/executor/claim`（无任务 204）与 `jobs/{id}/heartbeat|steps|artifacts|complete` 五个端点，scope `maintenance.execute`；服务端每次领取生成 fencing `leaseToken`（30–1800 秒，默认 120），暂停/取消/终态/租约过期/重领后旧 token 一律 409 `LEASE_LOST`，步骤/工件在租约事务内校验后写。
- claim 返回**完整** `completedSteps`（步骤详情仍限 50 条）；worker 恢复按完整已完成/跳过步骤名跳过，修复 >50 步任务重跑已完成步骤的副作用。
- 文档：`docs/api/maintenance.md` 与 OpenAPI/SDK 同批。验证：maintenance/httpapi 定向 `go test`。未跑全量/race/云端 CI，未接真实外部执行器或 NAS。

## feat(server): AI 客户端按客户端令牌桶限流（2026-11-08 第七百一十七笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 每客户端默认 600 次/分、突发 120；`QIMENG_AI_RATE_LIMIT_PER_MINUTE`/`QIMENG_AI_RATE_LIMIT_BURST` 可调，0 关闭。超限 429 `RATE_LIMITED` + `Retry-After`；限流在 scope 检查之前，写请求照常记审计。
- 限流为进程内单实例实现（空闲桶 10 分钟回收）；重启清零，多实例共享配额未实现。
- 验证：aiauth/httpapi 定向测试；未安装、未做真实客户端压测。

## feat(server): AI 客户端 HTTP 管理、审计查询与写 scope 扩展（2026-11-08 第七百一十六笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 新增 scope `asset.write`/`asset.upload`/`asset.delete`/`tag.write`/`source.write`/`library.write`/`trash.restore`/`maintenance.execute`；method+path 映射扩展到上传、标签、来源、库、回收站与执行器端点，未映射写请求仍默认 deny。
- 新增 `GET/POST /api/v1/ai-clients`、`DELETE /api/v1/ai-clients/{id}`、`POST /{id}/rotate` 与 `GET /api/v1/ai-audit`：仅管理员会话可用，AI token 即使带 admin scope 也返回 403 `FORBIDDEN_SCOPE`；明文 token 只在创建/轮换响应出现一次，审计按客户端/动作/目标/结果/请求 ID 记录；撤销/轮换经 5 秒缓存生效。
- 文档：OpenAPI/SDK/`api/sdk.lock`、`docs/AI_PLATFORM_API_GAPS`、SECURITY 由同批提交维护。验证：aiauth/httpapi 定向 `go test`。未安装、未跑全量/CI、未接真实 AI 客户端；Web 管理 UI 未做。

## merge: 合并 PR #44 外部 AI 平台与 PR #45 作者 TXT 硬约束（2026-11-08 第七百一十五笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- PR #44（外部 AI 接入平台、维护执行面、Android Worker 源码）与 PR #45（作者 TXT 作品行硬约束、研究工具包）合并入 master；唯一文档冲突 `docs/domain/migration.md` 按两代备份口径 + `authorProfileConflict=replace` 解析。
- 合并后本轮增量见第七百一十六～七百二十一笔；现装仍为 796d813f，新代码未构建安装，合并后云端 CI 待跑。

## fix(app): 设备领取本地前置与 claimed 恢复收尾、worker 测试 lint 修复（2026-11-08 第七百一十四笔）

执行 AI：DeepSeek V4.1 Flash（主代理；android-client 只读复核与实现）

- Android 设备领取复核（task-19）后修复：新增 DeviceApplyPreconditions，claim 前校验登录会话→维护根→所有文件访问，缺项只记录本机提示并结束、任务保持 pending（不再消耗成 failed 终态），授权后可「立即应用」重试；Remote 增 listByStatus，Runner 同查 pending+claimed（claimed 未过期按 claim 幂等继续/对账，终态跳过），Policy 按过期时间筛选；设置页令牌只显示已配置/替换清除，不回填明文 state；docs/api/device、android/README、docs/android-background-execution 修正未登录不领取、claimed 恢复、本机单机模式无令牌创建通道等口径；补 Preconditions/Policy JVM 测试。
- 修复 CI run 42389606438 服务端 lint 失败：worker_test.go:671 `||` 两侧相同表达式 SA4000，改为先赋值再判断，语义不变（ad5cf3da）。
- 验证：go test 定向 lint 用例通过；Android 复核/编译由子代理与 CI 复验；未跑全量、未真机/安装。

## docs(ai): 本轮收尾：9 项修复、AI 凭据 P1、Android 后台 Worker 与验证边界（2026-11-08 第七百一十三笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 汇总并同步本轮交付：9 个 PR #44 Codex inline finding/用户报告问题的修复（工具层 5 项、Web 两代备份、Android 历史备份、Worker 租约、MCP 取消）、工具层按需工作流 `platform.playbook` 与 MCP 异步化、Go 侧 ADR-0057 P1 首批（AI 客户端凭据/scope/审计/设备级隔离/本地 CLI）、Android 后台设备结果包 Worker 源码。
- 文档：taskbook 增 §4.5（按需模板/独立部署）与 §9.6（Android 后台执行与 AI 权限适配）、§13 状态与 §14 矩阵；CAPABILITY_MAP 增 AI 凭据/设备级隔离与 Android Worker 行；ADR-0057 状态与实现补充、ADR INDEX；HANDOVER §9.1/§9.3 交付与边界；新增 [独立 AI 平台部署升级边界](AI_PLATFORM_OPERATIONS.md) 与 [Android 后台执行分析](android-background-execution.md)；tools/ai_maintenance/README、docs/SECURITY、docs/AI_PLATFORM_API_GAPS、docs/api/device、android/README 随各提交同步。
- 验证边界：本机定向通过（AI 工具 regression 12/catalog 22/MCP 11；Web vitest 24、tsc；Android JVM 34 + compileDebugKotlin、备份 28；Go build/vet + maintenance 定向；check_docs 147 文件 0 错误、check_privacy passed）；**未跑全量测试/云端 CI/真实库/真机/安装**，未合并 PR、未部署。待集中验收清单见 HANDOVER §9.2/§9.3。

## fix(server): 维护 worker 租约丢失取消在途 handler、续租错误分类、Claim 失败释放租约（2026-11-08 第七百一十二笔）

执行 AI：DeepSeek V4.1 Flash（子代理 go-worker2）

- 依据 go-worker 对 7ce3bc79 的只读复核修复残余窗口：process 为每笔任务建 jobCtx，心跳 ErrLeaseLost/连续非租约失败/租约余量过小时 cancel，handler.Execute 改用 jobCtx；Execute 返回后按 jobCtx 判定 interrupted，不写终态、不误标 failed，已提交步骤保留、重领后重跑未完成步骤（P2-1）。
- 步骤边界心跳非租约错误不再 failJob（warn+cancel+停止本槽，等 TTL 重领）；AdvanceStatus/settleStages 临时错误同策略（P2-2）。Claim 提交后 Get 失败时新增 ReleaseMaintenanceLease 尽力释放本次租约再返回错误（P3-3）。main 等待 worker 收敛加 shutdownTimeout，超时记录后继续关库（P3-5）。
- 新增测试：Start+Concurrency=2+阻塞 handler+Pause/Resume 取消、瞬时心跳错误不 failed、领取后详情失败可立即重领；go build/vet/maintenance 定向测试通过。残余：取消依赖心跳发现（最长一个心跳间隔）；立即 resume 的极短窗口仍未完全消除，需后续协调/可重试语义评估；未改 API/协议/migration。

## feat(app): Android 后台设备结果包 Worker、设备级令牌与 SAF/权限适配（2026-11-08 第七百一十一笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；独立子代理实现与 JVM 验证）

- 复用设备结果包协议与维护账本实现后台 Worker：core/data/device 14 个文件（Runner 编排领取-校验-应用-回读-回传；Worker/Scheduler/WorkSpec 15min 周期+一次性、CONNECTED、unique work+Mutex；Remote/Policy/Authorization/SegmentVerifier/ResultPlanner/LocalBackup/Readback/SettingsRepository/Models）；feature/manage 设备领取页/VM 与维护页入口（设备标签、口令式设备令牌、后台开关、立即应用、最近摘要）。
- 设备授权：专用 AI 令牌（device.apply + device_ids）只用于设备端点，无令牌不回退会话 token；本机模式先拉起内嵌服务；维护根+所有文件访问门禁，缺失回 failed 并提示 UI 授权；先导出两代备份再 import-txt/qimeng-backup，按 expectedReadback 回读比较后回传 result，清理临时段；409 先对账终态不覆盖。
- 验证：:core:data:testDebugUnitTest device.* 34 tests 通过、feature:manage/app compileDebugKotlin 通过、check_docs 0 错误、check_privacy passed。未安装、未真机；Doze/厂商后台、后台拉起内嵌 FGS、SAF/存储授权撤回、PC→手机端到端、断网/进程被杀恢复待验证；无段级进度持久化，进程强杀可能重下整包。

## feat(server): 独立 AI 客户端凭据、scope 强制校验与写审计（2026-11-08 第七百一十笔）

执行 AI：DeepSeek V4.1 Flash（子代理 go-worker 实现与本地定向验证）

- ADR-0057 P1 首批（不改 OpenAPI/sdk.lock/三端、不加 HTTP 管理端点）：migration 0025 只增 ai_clients（token_hash 唯一、scopes_json/device_ids_json、expires/revoked/last_used）与 ai_audit（只增、无外键）；新包 server/internal/aiauth（scope 白名单/未知拒绝、token 生成+auth.TokenHash、过期/撤销 fail-closed、5s 缓存+Reload、审计、Create/List/Revoke/Rotate）。
- httpapi：管理员会话先行验证（行为不变），未命中再验 AI 凭据；AI 按 method+path 强制 scope（GET/HEAD 兜底 read，维护/备份/研究/设备各自 scope，未映射写默认 403 FORBIDDEN_SCOPE），管理员不受限；AI 写请求含被拒写审计；设备级隔离 device.apply + ai_clients.device_ids，列表/详情/领取/段/回传/取消校验 targetDevice，越权不改状态。
- 管理入口：qimeng ai-client create|list|revoke|rotate -config，直接打开同一 SQLite（先幂等 migration），明文 token 只 stdout 一次；撤销幂等、轮换旧 token 立即失效。验证：go build/vet、aiauth/httpapi/cmd 定向测试通过；未跑全量/CI/真实库。边界：协议化 scope、HTTP 管理端点、限流预算、审计查询、设备配对未实现；异地撤销约 5s 缓存生效。

## fix(server): 维护 worker 租约改为每次领取唯一 fencing token 并校验未过期租约（2026-11-08 第七百零九笔）

执行 AI：DeepSeek V4.1 Flash（子代理 go-worker2）

- 根因：Worker 实例 owner 跨领取复用，Claim/Heartbeat/Complete/Advance 只比较 owner 字符串；租约过期被重领后旧执行上下文仍可续租/覆盖步骤工件终态，同任务可并发执行。修复：实例身份 qimeng-worker-<pid>-<uuid> + 每次领取唯一 fencing token；Heartbeat/Finish/Advance SQL 增 lease_until 未过期条件；StepInput/ArtifactInput 增 LeaseOwner，在同一 _txlock=immediate 事务内校验 owner+未过期后写步骤/工件；ErrLeaseLost 只停止不误标 failed，暂停/取消进行中步骤保留 running。
- 验证：go test ./internal/maintenance/ -count=2、go vet、go build、gofmt、sqlc 重生成稳定；新增身份/token 唯一、同实例两次领取 token 不同、旧实例接管后不得覆盖、过期租约全链路围栏测试。未加 migration、未改 API/SDK/业务语义。

## fix(web): 两代备份写入中断恢复与轮换幂等（2026-11-08 第七百零八笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；子代理 web-client 实现与定向验证）

- 根因：先整代轮换当前→上次再写新当前且无事务标记；中断后重复轮换/半成品被当上次代/正文摘要分开写导致永久摘要不符或丢失完整旧代。修复：新增 maintenance-generation.ts 两代写入原语与恢复判定；写快照前落 .两代轮换状态.json（old/next 摘要），整代轮换（相同跳过、缺摘要补）、写新当前（正文先摘要后）、最后删状态；下次写入按断点自愈，不可判定拒绝并保留现场；相同数据重复导出只刷新当前并修复过期摘要；旧 flat3 只读兼容不变。
- 验证：maintenance-folder.test.ts 24/24、tsc --noEmit、oxlint 0；未跑全量 vitest/Playwright/vite build/真实服务端。待测：真实浏览器崩溃中断自愈、真实 FS removeEntry 失败、跨浏览器差异。

## fix(app): 修复两代布局历史备份恢复读取（上次代跨目录/旧 flat3 只读回退）（2026-11-08 第七百零七笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；子代理 android-client 实现与 JVM 验证）

- 根因：上次代在独立目录 上次/备份/影库备份.json，恢复入口仍按当前目录+影库备份（上次）.json 拼路径，历史恢复永远读不到；偏好改指当前代后旧 flat3 历史文件也不可达。修复：新增纯 JVM MaintenanceBackupSlots 按槽解析真实文件（两代优先/旧 flat3 只读回退/摘要核对）；MaintenanceFolderRepository 增 readManagedBackup+managedBackupStatus；AutoBackupRunner.readBackup 返回真实文件名+字节，BackupViewModel 以真实名进 v2 校验链并按上次/上上次分槽提示；维护/备份页三代文案改两代。
- 验证：本机 kotlinc+JUnit 28 项 JVM 通过（临时目录）；未跑 Gradle/CI/真机。待测：恢复上次成功路径、旧 flat3 回退、64MB 摘要、SAF 旧目录回退；UI 历史列表/迁移入口未接线。

## fix(tools): 修复计划实例绑定、删除前复检、合并参数、作者名回读与媒体目录筛选（2026-11-08 第七百零六笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；子代理 ai-core 实现与定向验证）

- 对应 PR #44 Codex inline finding/用户报告 1/2/4/5/6：1) 媒体目录筛选改用 GET /assets directory（精确、不递归、空串=库根），修正 AssetSummary 无 relPath 导致的子目录恒空/根目录误选；_directory 拒绝绝对路径/盘符/反斜杠/空段/./..，media 能力声明补 directory。2) plans.verify_plan 增 instance 校验，跨实例计划抛 PRECONDITION_FAILED(instance_mismatch)，profile/cleanup/asset apply 传 ctx.target，media apply 等价校验。3) cleanup apply 的 inventory 签名扩展 profile revision/status/empty/hasXProfile+sources，执行前整体复检、delete 前逐项复检，变化拒绝并保留已执行步骤。4) cleanup merge 分支不再无条件读 authorId，按动作读取必填参数修复 KeyError。5) asset.authors apply 回读按服务端别名保守归一与 PUT 前作者索引识别按名解析/复用作者，其余额外 id 仍 READBACK_MISMATCH。
- 验证：新增 test_ai_regressions 12 项 + 受影响 24 项通过；未跑全套/CI/真实库。

## feat(tools): 按需工作流模板 platform.playbook、MCP 调用异步化与独立部署文档（2026-11-08 第七百零五笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 用户 2026-11-07 补充要求：统一管理操作提示词/业务规范/工作流、减少对源码与完整文档依赖、MCP 调用期间处理取消、平台独立部署与版本。新增 platform.playbook（按领域列模板或按名取单个，规则节选有界，模板可选不强制路径）；rules.json 增 playbooks（author.profile/media.organize/author.cleanup/maintenance.jobs/backup.rotate）与 toolCatalog.discovery，版本升 2026-11-07.6；CLI 增 playbook 子命令，MCP initialize 指引更新。
- MCP serve_forever 改为 tools/call 独立线程 dispatch + 写锁 + 并发上限（QIMENG_AI_MCP_MAX_CONCURRENCY，默认 4）+ 有副作用工具全局串行；取消通知在调用期间可处理，只读工具立即 -32800、有副作用工具等真实结果（避免误以为未执行）；完成后清理取消标记防 request id 复用误判；新增/更新 MCP 取消与串行化测试。新增 docs/AI_PLATFORM_OPERATIONS.md（CLI/MCP 独立启动停止、配置、版本兼容、升级、NAS 边界）。
- 验证：test_ai_catalog 22、test_ai_mcp 11、test_ai_toolkit 47、test_ai_maintenance 31 通过；未跑全量与 CI。

## docs(ai): Go 业务 API 缺口与 AI 客户端权限模型方案（2026-11-07 第七百零四笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 按用户 2026-11-07 追加长期架构要求落方案：[Go API 缺口与 AI 客户端权限模型](AI_PLATFORM_API_GAPS.md) 复核作者/媒体/文件/备份/任务既有接口（结论：基础操作完整，缺口集中在 AI 客户端身份/权限/审计），列 G1–G8 缺口（AI 客户端凭据+scope 撤销审计、业务审计归属、服务端高风险确认、外部执行器领取、批量能力、设备配对、作品/剧集模型、限流预算）与分阶段计划；新增 [ADR-0057](adr/0057-AI客户端凭据与细粒度权限模型.md) 与索引。
- 权限模型：AI 客户端凭据与用户会话分离、可轮换/撤销、scope 集合（read/research/author.write/asset.author.write/media.organize/backup.export/maintenance.read|write 与高风险 author.destructive/backup.restore/admin.*）；低风险写可在授权范围内自动执行，高风险单独 scope+动作级确认；权限由 Go 中间件与服务层强制，审计按客户端/动作/目标/结果/请求 ID 归属；拒绝任意 SQL/文件/shell；长任务复用维护账本；NAS 底座与 AI 平台独立升级。
- 本 PR 只收尾现有代码与测试，不改 Go/OpenAPI/migration；ADR-0056 补充工具目录/惰性加载/权限分离；taskbook §4.4/§5.4、CAPABILITY_MAP、HANDOVER §9.3、tools README 同步。验证：check_docs 0 错误、tools 单测 205 项本机通过（扩展性 18 项）、check_privacy passed；Go 与三端未改；云端 CI 见 HANDOVER §9.2。未安装、Go 权限实现未开始（P1+）。

## feat(tools): 工具目录按需发现、惰性模块加载与权限分离（2026-11-07 第七百零三笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 按用户 2026-11-07 扩展性要求做最小增量（仅 tools/ai_maintenance/ 与文档，不改 Go /api/v1、OpenAPI、schema、三端）：新增 platform.catalog 精简领域目录（author/media/research/backup/device/maintenance/system，28 个工具；无 Schema，默认隐藏已废弃工具）、platform.tool.describe 按需返回单个工具 Schema/示例/规则摘录/Go API 能力依赖、platform.rules 按节/领域读取有界规则（不加载完整 rules.json）；CLI 增 catalog 与 tools list --compact/--domain，tools show 输出按需描述；MCP tools/list 仍返回完整清单并新增 _meta.domain 与 catalog 指引。
- 注册表改为惰性 module:handler 引用：目录/状态计算不导入 tools_*.py / media_naming.py，首次执行/描述才导入；实现模块缺失→unavailable/TOOL_NOT_IMPLEMENTED；新增 deprecated/replacedBy（目录默认隐藏、执行 TOOL_DEPRECATED）；rules.json 增 toolCatalog 规则节（领域/发现策略/惰性加载/权限模型/管理员 Bearer 绕过风险评估）并升 rulesVersion/rulepackVersion 2026-11-07.5。
- 权限与发现分离：execution 按 QIMENG_AI_GRANTED_PERMISSIONS 或 toolConfig.grantedPermissions 在 Schema/计划/网络前强制拒绝（PERMISSION_DENIED）；未配置默认授予全部声明权限仅为兼容旧 CLI。管理员 Bearer 绕过风险已写入 README/ADR-0056/rules：工具层隐藏 token 不能阻止模型直接调用 Go API，正式开放 AI 写入前需受限凭据或执行端隔离。
- 示例与规则摘录集中在 registry（TOOL_EXAMPLES/TOOL_RULES_REFS），describe 有界返回；目录 JSON 约为完整 tools list 的 1/11（28 工具实测），120 个合成工具仍有界；新增 tools/test_ai_catalog.py 18 项与既有回归，tools 全套 205 项本机通过、check_docs 0 错误、check_privacy passed；云端 CI 见 HANDOVER §9.2。未安装、未接真实 AI 客户端。

## feat(web/app): 维护任务中心与两代布局、双端搜索结果文件名显示（2026-11-07 第七百零二笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- Web 维护任务中心（只读）：新增 `lib/maintenance-jobs.ts`(+test)、`hooks/use-maintenance-jobs.ts`、`components/maintenance/`（任务中心/列表/详情），维护页追加任务列表（状态筛选/cursor 分页/刷新）、详情（类型/状态/进度/步骤/工件元数据/错误码）与取消（二次确认后刷新）；不展示 payload/budget 与工件正文，未写「实时」承诺。
- Web 两代维护目录：`lib/maintenance-folder.ts`(+test) 与目录卡/备份卡/hook 改为 `当前/上传归档 → 上次` 两代整代轮换，每代独立 .sha256，createWritable 原子替换，摘要不符/缺摘要/不可读拒覆盖并保留现场；旧 flat3 只读兼容并可列提示、绝不删除；恢复入口改为当前/上次，旧「上上次」改文件导入说明。
- Web 搜索：搜索结果标题取完整文件名（COS 结果亦可见作品编号/组图页码）单行省略、悬停/读屏完整名，普通浏览网格保持 cosWork 优先两行；换搜索词显示「正在搜索…」加载态而不混旧词结果，同词筛选/分区保持 keepPreviousData 不闪；详情新增完整文件名行与复制（失败提示手动复制）；消费共享 Go 搜索语义，不重写匹配。
- Android 两代维护目录：`MaintenanceFolderRepository` 改为当前/上次两代 + 上传归档，整代轮换、独立摘要、原子写、拒覆盖保留现场、旧 flat3 只读兼容；新增 `MaintenanceGenerations.kt`（纯 JVM 轮换/原子写/摘要）与 `MaintenanceLayoutMigration.kt`（旧三代只读核对+复制不删除）；旧镜像/备份偏好改指新代且保留旧文件；18 项 JVM 单测由本机 kotlinc+JUnit 运行通过（AGP/Gradle 与真机未跑，云端 CI 为编译门禁）。
- Android 搜索结果：缩略图下一行简短文件名（单行省略、保留编号/页码，`QimengMediaGrid` 可选 caption，默认不影响其他网格），详情信息弹窗完整文件名与复制（Clipboard+提示），换词提交同步清空旧结果/游标（配合既有查询代际丢弃在途旧响应），保留双指缩放 2～5 列/分页/滚动/返回，内嵌与连接模式同一 API；补 JVM 单测（短名保留编号/页码与边界、换词清空旧结果）。真机与内嵌现场未验收。
- 验证边界：Web vitest 全量 41 文件/381 用例、tsc --noEmit 与本次改动 oxlint 通过（未跑 vite build/dev、浏览器走查另按 HANDOVER）；Android 仅静态类型检查与 JVM 单测，未跑 AGP/Gradle/真机；未安装到现装 PC/手机。
- 交付后 CI 修复（同批提交 6a41bc55/d1ef39f9）：维护任务中心对合成/异常响应形状做列表/详情/取消归一（避免非协议 JSON 在渲染期抛错拖垮维护页），任务中心改用独立布局类名 `.maintenance-job-center`（避免 e2e 严格模式把第二个 `.monitor-grid` 判为多元素）；Playwright 维护/管理导航场景恢复通过。
- 验证：最终 HEAD 云端 ci [42370683863](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683863) 七 job 全绿（服务端 go vet/golangci-lint/go test -race/go build、Web tsc/build/vitest/Playwright、Android assembleDebug+单测+Lint、SDK 生成链、桌面壳、Windows 原生文件操作、redocly）；文档 [42370683869](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683869) 与依赖安全 [42370683860](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683860) 通过。本轮交付仍**未安装**到现装 PC/手机，真实库、真机与外部 AI 客户端未验收。

## feat(server): 两代备份镜像、组图命名解析与搜索编号语义（2026-11-07 第七百零一笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 两代备份布局（服务端）：`server/internal/authorattach/mirror.go` 在 `path` 祖先目录恰有一段 `当前`/`current` 时，按同根兄弟 `上次`/`previous` 推导上一代并校验仍在维护根内；内容变化先把当前代（正文+独立 .sha256）提交为上次代再写新当前，相同数据只刷新当前；上次目标已存在且正文不同时必须有可复算匹配的 .sha256 才覆盖，缺失/不符/不可读拒绝并告警；相对路径、无代目录段、多段歧义、盘符/共享根等不推导时回退旧 flat3 三代语义，旧配置行为不变。新增 8 组测试覆盖推导、轮换、拒覆盖、外部编辑、失败重试与 master 模式全链路。
- 迁移核对工具：新增 `tools/migrate_backup_layout.py`，默认 `--check` 只读核对（可读性/.sha256 复算/新旧对应建议/JSON 摘要），`--apply` 才复制生成当前/上次（当前=旧最新，上次=旧「（上次）」），上上次仅列可清理清单；绝不删除/移动，目标占用拒覆盖，临时文件+os.replace 原子写；12 项合成目录单测通过。
- 组图命名：新增 `server/internal/filing/workname.go` 纯函数解析作品基名/主作品编号（位宽/分隔/标记风格）/可选组内页码，仅括号序号（含上传自动改名产物）回退旧 seriesSuffix；`SuggestSeriesNames` 对组图页建议下一作品编号而非下一页码，组图与单图混合/乱序/缺页/补图/全角括号/零填充均覆盖测试；`docs/domain/files.md` 与 `docs/api/files.md` 记录命名语法、三步优先级与「自动改名不算命名成功」。
- 搜索编号语义：新增 `server/internal/store/searchtoken`（纯函数 Match + modernc 确定性标量函数 `qimeng_search_token`，init 注册失败直接 panic 防静默退化）；browse/facets 的 10 处 q 谓词统一改为该函数：数字 token 按独立数字段匹配（前后非 ASCII 数字，裸数字不命中括号页码），`(2)` 等页码 token 定位组图页，非数字 token 保持旧 ASCII 子串多字段语义；列表/计数/分面/dateCounts 同口径。修复前隔离测试复现「朱诺 1 命中 13/13、朱诺 11 命中 110/2011、朱诺 2 命中页码」，修复后 store/search/httpapi 定向测试通过；sqlc 重新生成比对无额外漂移。
- 验证边界：上述 Go 侧本机 `go build/vet/gofmt` 与 authorttach/store/search/httpapi 定向测试通过；Python 侧 12 项迁移工具测试通过；未改 OpenAPI/sdk.lock/SDK/migration；未迁移真实维护目录、未对真实库执行搜索专项或命名整理。

## feat(server): 维护任务 Worker、幂等载荷指纹冲突与组合根装配（2026-11-07 第七百笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `server/internal/maintenance` 新增 `worker.go`：内部常驻执行器（不开放任何 HTTP 领取/续租/完成端点），仅领取本进程已注册 handler 的 kind（sqlc 查询按 kind 过滤），有界并发（默认 1）、租约 TTL+心跳、逐步骤 RecordStep（running/completed/failed/skipped + attempt/时间/detail）、状态机安全推进（queued→inventory→applying→verifying→completed/partial/failed）、暂停/取消/租约丢失后停止后续步骤并保留已提交步骤、进程退出不写终态由租约过期续跑、panic 兜底只影响单任务、已完成步骤不重复执行副作用。
- 内置 `backup_export` handler（`handler_backup.go`）：复用既有 `backup.Manager` 生成库快照并 AddArtifact 记录工件元数据（正文不入库）；无 handler 的 `asset_research`/`author_cleanup`/`custom` 保持 queued 供未来外部 AI 执行器。
- 幂等冲突保护：`Service.Create` 命中同 idempotencyKey 时比较新载荷 inputFingerprint，相同保持重放（200/created=false），不同返回 ErrStateConflict（409），httpapi 映射 STATE_CONFLICT；补并发 replay 竞争路径。
- 装配与配置：`cmd/qimeng` 以 `safego.Go` 启动 worker 并在关库前收齐；config 新增具名默认值（轮询 5s/租约 2min/并发 1，`QIMENG_MAINTENANCE_WORKER_*` 可覆盖，默认启用但只处理已注册 kind）；SQL 改动经锁定 sqlc v1.31.1 重新生成。
- 测试与文档：maintenance/config 包覆盖领取/心跳、租约过期接管、重启续跑不重复、暂停取消、无 handler kind 不被领取、同 key 同/不同载荷、backup_export 工件与失败终态；docs/api/maintenance.md 更新 worker/冲突/未开放外部领取端点的边界，ADR-0054 追加实现补充。
- 验证边界：本机 `go build ./...`、`go vet ./...`、定向 `go test ./internal/maintenance/ ./internal/config/` 通过，`gofmt -l` 干净；完整回归与 race 检测以云端 CI 为准（见 HANDOVER）。未安装到现装运行包，未对真实库创建任务。

## feat(tools): 独立 AI 工具核心、作者链受控写入与 stdio MCP 适配器（2026-11-07 第六百九十九笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `tools/ai_maintenance/` 升级为可独立维护的统一工具包（仅 Python 3.13 标准库）：新增 versions/errors/openapi/schema/registry/runtime/execution/plans/client/validation 模块与 tools_query/tools_author/tools_asset/tools_media/profile_import 实现；分离 platformVersion/toolContractVersion/rulepackVersion 与 openapiSha256（仅来源追踪），OpenAPI 改为分块解析（path→method→requestBody/schema/parameters，支持 $ref），按工具独立核对端点+方法+必需字段并输出 supported/unavailable/incompatible；25 个显式注册工具（19 supported/6 planned 如实），可列举、可禁用（QIMENG_AI_DISABLED_TOOLS/QIMENG_AI_READONLY），新增工具不改入口分支。
- 统一 CLI 保留既有只读子命令与 `--json`/退出码 0/1/2 合同，新增 `version`、`tools list/show`、`tool call <id> --input-json/--input-file`；token 仍只来自环境变量，输出/日志/报告不回显；请求客户端统一（QIMENG_BASE_URL→QIMENG_API_BASE 兼容映射并提示弃用）。
- 受控写入：prepare 计划（目标实例/库/对象、changes、impact、evidence、前置 revision/摘要、planFingerprint、idempotencyKey、confirmation、recoverableSteps、expectedReadback）→apply 校验确认+指纹+toolContract/rulepack 版本+live 前置摘要→正式 API→回读→结构化报告；作者档案 prepare/update（人工已填字段默认保护，409 不覆盖）、cleanup prepare/apply（删除/改名/合并预览与逐项回读，零关联不单条件删除）、author.merge（只用原子端点）、asset.authors prepare/apply；旧 `tools/import_creator_profiles.py` 改为转发统一实现，保留参数/退出码/输出类别与人工资料保护语义。
- 新增 `media_naming.py` 组图命名规划引擎（作品编号 vs 组内页码、先分组→编号→页码、补图/缺页/并发/回读；prepare_plan/validate_plan/plan_steps/readback_report/SerializedLocks）与 `tools_media.py` 适配：apply 前按 instance+library 串行化、锁内重取现状+双检、逐步 POST move、回读发现自动改名/缺失/多余即失败；`media.organize.prepare/apply` 由 planned 动态变为 supported。
- 新增 stdio MCP 适配器 `mcp_server.py`（JSON-RPC 2.0 最小子集：initialize/notifications/ping/tools.list/tools.call/取消与超时；与 CLI 共用同一注册表与 Schema；无 shell/任意 Python 执行；不暴露 token）；协议层以两个独立合成客户端验证，**真实 AI 客户端（Codex/Claude 等）接入未实测**，README 如实说明。
- 规则源同批：rules.json schemaVersion=2、source 指向 V2 任务书与 ADR-0056、新增 mediaNaming 规则节、补齐 maintenance/jobs* 与 device/apply-tasks* 能力声明、区分 status/apiStatus/toolStatus、过时三代陈述改两代；rulesVersion/rulepackVersion 增为 2026-11-07.3。
- 验证边界：`python -m unittest discover -s tools -p 'test_*.py'` 181 项通过（含旧 22 项 CLI 合同与旧脚本 15 项收敛回归、MCP 双合成客户端、命名 39 项）；CLI/MCP 冒烟通过；check_docs 0 错误、check_privacy passed。未改 server/api/web/android，未接真实服务端/真实 AI 客户端/8420 真库。

## docs(ai): 独立 AI 接入平台 V2 任务书、ADR-0056 与 R0 定向审计（2026-11-07 第六百九十八笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 纳入 [外部 AI 接入平台统一任务书 V2](AI_PLATFORM_TASKBOOK.md) 作为现行执行清单（R0–R8、§6.6 组图命名与作品编号、§6.7 搜索语义与双端文件名显示、§11 安全权限与 §14 验收矩阵）；原[外部 AI 维护任务书](AI_MAINTENANCE_TASKBOOK.md)与 [P0 底稿](AI_MAINTENANCE_P0.md)标记为历史需求与设计来源，保留全文供追溯，不再作为并行执行清单。
- 新增 [ADR-0056 独立 AI 接入平台架构](adr/0056-独立AI接入平台架构.md) 与 ADR 索引：两层接口独立、Python 标准库工具核心与显式注册表、版本分离与能力核对、计划/确认/执行/回读、唯一维护账本与内部 Worker、两代备份路径约定、研究默认关闭的边界与被否方案。
- 新增 [R0 定向审计](AI_PLATFORM_AUDIT.md)：脚本/工具逐项清点（AI 工具链、作者写入、研究链、管理员工具、服务端能力）、三分类收敛、重复实现 D1–D8、规则包与 OpenAPI 逐条漂移（A1–A9，无 P0，24 个有端点声明路径+方法匹配）、复用/退役映射与最小变更计划。
- 文档治理同批：AGENTS/AI_README_FIRST/llms 任务入口、ARCHITECTURE 外部 AI 平台章节、CAPABILITY_MAP 能力域（AI 工具层/维护队列/两代备份/组图命名/检索筛选）、HANDOVER §5 指针与 §9 本轮交付边界；旧三代陈述在 docs/domain/migration.md、docs/SECURITY.md、tools/MEDIA_RESEARCH_IMPORT.md 改为「当前/上次」两代并保留旧 flat3 兼容读取；tools/README 增迁移工具条目。
- 验证边界：纯文档与入口调整，未改代码/协议/迁移/SDK；`python -X utf8 tools/check_docs.py` 0 错误、`check_privacy.py --mode worktree` passed。本轮源码交付的安装、真实数据与设备验收状态见 HANDOVER §9。

## feat(server): 设备结果包服务端 API（PC→手机无前台维护第一刀）（2026-11-07 第六百九十七笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 migration `0024_device_apply_tasks`：只增 `device_apply_tasks`/`device_apply_segments` 两表与「设备+状态+新建倒序」「过期」索引，不改历史表/索引；任务状态机 pending→claimed→applied/partial/failed、pending/claimed→cancelled/expired，终态不可变更，过期由读写路径惰性标记。
- 新增 `server/internal/deviceapply`：manifest 校验（targetDeviceId/protocolVersion=1/rulesFingerprint/ttl/段 kind/bytes/SHA-256/expectedReadback）、段落盘时流式复算摘要与字节数后再单事务写库、失败清理任务目录；Create/List/Get/Claim/OpenSegment/Result/Cancel；领取对已 claimed 未过期任务幂等、相同归一化结果回传幂等（不同结果 409）、取消对 cancelled 幂等；段相对路径逐字校验+根目录包含，下载前复核文件类型与大小；只依赖 store/db，不依赖 httpapi（depguard）。
- 协议新增 7 个 `/api/v1/device/apply-tasks` 端点（登记/列表/详情/领取/段下载/回传/取消）：登记为 multipart manifest + masterTxt≤1MiB + v2Backup≤64MiB，单个段或整体请求体超限 413；段下载 octet-stream + Content-Length + X-Content-SHA256；`make sdk` 三端生成、go build ./... 通过，`api/sdk.lock` 同批重算（355 项）。
- httpapi 只做 multipart/query 校验、错误映射与响应装配；测试覆盖创建/段落盘 SHA-256/超限/指纹不符、列表过滤与游标、领取幂等与过期、段下载头/终态/路径安全、回传终态与重复回传、取消、404/409/413，全部使用临时 SQLite、临时包目录与合成字节（含 1KB 段）。
- 文档同批：`docs/api/device.md`、ADR-0055 与索引、能力地图、HANDOVER §8、AI_MAINTENANCE_P0 §4.5/§5/§6、llms 导航。验证边界：本地已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet`、文档/隐私/迁移检查；业务 `go test` 按仓库规定走云端 CI。**未安装**到现装 PC/手机；Android Worker、配对/设备身份、真机后台同步与包清理仍未实现。第六百六十七笔完整原样移入 `docs/history/CHANGELOG-20261028.md`，近期日志保持 30 笔。

## feat(maintenance): 持久维护任务队列服务端垂直切片（2026-11-07 第六百九十六笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 migration `0023_maintenance_jobs`：只增 `maintenance_jobs`/`maintenance_steps`/`maintenance_artifacts` 三表与状态、幂等、租约、步骤/工件索引；状态机覆盖 queued→inventory→research→planned→applying→verifying→completed、waiting_evidence/waiting_authorization/waiting_device 与 paused/partial/failed/cancelled，终态不可变更。
- 新增 `server/internal/maintenance`：Create/List/Get/Pause/Resume/Cancel、同事务状态校验、幂等键冲突返回既有任务、输入指纹、租约字段与 Claim/Heartbeat/Complete、RecordStep/AddArtifact 内部原语；只依赖 store/db，不依赖 httpapi（depguard）。
- 协议新增 `POST/GET /api/v1/maintenance/jobs`、`GET /api/v1/maintenance/jobs/{id}` 与 `POST …/{id}/pause|resume|cancel` 六端点；创建幂等 201/200，非法迁移/终态 409 STATE_CONFLICT，不存在 404，列表 status/limit/cursor 与详情步骤/工件/error。`make sdk` 三端生成通过，`api/sdk.lock` 同批更新为 331 项。
- httpapi 只做协议校验/错误映射/响应装配；测试覆盖创建/幂等重复/并发同键、列表过滤与游标、状态机合法/非法迁移、暂停/恢复/取消、终态不可变与 404/409 映射，全部使用临时 SQLite 与合成数据。文档同批：`docs/api/maintenance.md`、ADR-0054 与索引、能力地图、HANDOVER、AI_MAINTENANCE_P0 §4.2/§5、llms 导航。
- 验证边界：本地已跑 `make sdk`（含 `go build ./...`）、`gofmt`、`go vet` 与迁移只增检查；业务 `go test` 按仓库规定走云端 CI。**未安装**到现装 PC/手机；worker 执行器、Web 任务中心、PC→手机桥接、作者清理工具与两代布局仍未实现。第六百六十六笔完整原样移入 `docs/history/CHANGELOG-20261028.md`，近期日志保持 30 笔。

## feat(app): 安装 796d813f 双端并完成研究流水线真实实例试验（2026-11-07 第六百九十五笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 云端同源门禁：ci/安全/文档与 PC/APK 出包均在 796d813f 通过（CI 首轮 Windows 文件操作作业因环境清理失败，同作业重跑通过）。PC 安装 119 项清单本地 SHA-256 核对通过，version JSON revision=796d813f、schema22、health/ready 200；手机同签名保留数据覆盖安装，版本 0.2.1/code35（lastUpdateTime 2026-11-07 01:59）。
- 研究流水线真实实例试验：临时开启 `research.enabled` 并运行本机 sidecar，`/research/context` 返回 enabled=true、sidecar available、7 provider；`/research/lens` 真实返回 200/约 13s（frame SHA 与真实库图片一致、links 空、top 域噪声，符合 best-effort 预期）。`/providers/fxtwitter/query` 因本机 DNS fake-IP（198.18.1.x 保留段）被 SSRF 防护拦截返回 ADDRESS_BLOCKED；未放宽安全策略，需本机切真实 DNS 或另立显式逃生配置后再验。
- 顺带按用户 2026-11-07 授权补挂作者：POST /assets/{assetId}/authors 将「守望先锋 天使 18.jpg」关联到 vgerotica 并回读一致；写前已做服务端热备。
- 边界：未做手机 v2 同步；研究默认关闭、日常启动不带试验环境；真实 429/503/504 未触发；所有证据与安装副本只留忽略目录，未写测试脏数据。
# CHANGELOG - 变更历史

本文件只保留最近 30 笔；更早条目按 [历史索引](history/INDEX.md) 查询。历史条目正文及署名不改写，当前状态看 HANDOVER/能力地图与现行规范。
## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型完整名称与变体（如 GPT-6.1 Sol），以会话元数据或用户明确确认为据；Codex 仅作工具/客户端说明，不能代替模型名，具体版本也不能简写成模型系列。无法确认时注明未知，禁止猜测。
- 署名补全（2026-10-26，用户明确确认）：2026-10-20～26第五百四十八至第五百七十四笔、第五百八十九至第六百二十五笔原标未知，共64笔，补为GPT-6.1 Sol；原主代理/复核/GLM-5.3原实现分工及工具标记保留。该依据是用户本轮确认，不伪称已从运行时独立读取；本会话后续交付在此确认范围内沿用。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。
- 署名自查（2026-09-05 补）：每条变更由执行会话先确认自身实际运行模型的真实名称再署名（GLM-5.3 与 GLM-5.3-Flash 是两个不同模型名），禁止沿用上一会话或上一条目的署名行；历史条目真实署名不动。
- 署名补全（2026-10-23，用户明确确认）：2026-10-22 第五百七十五至第五百八十四笔原标未知，补为 GPT-6.1 Sol；各笔执行分工和历史验证边界不变。
- 署名更正（2026-10-20，用户明确确认）：2026-10-19～20 的第五百三十八至第五百四十五笔原误署 GPT-6 或仅署 Codex，统一更正为 GPT-6.1 Sol；保留各条主代理/执行子代理及复核分工。

## feat(tools): 研究核验 CLI 命令（context/provider/lens）（2026-11-07 第六百九十四笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `tools/ai_maintenance/cli.py` 新增只读 `research` 命令组：`research context`（GET /api/v1/research/context，打印 enabled、sidecar configured/available/pending、provider 矩阵与限额）、`research provider --provider <name> --operation <author|post|work|artist|tags|url|workshop|search> --target <值> [--limit N]`（POST /api/v1/research/providers/{provider}/query；CLI 不硬编码矩阵，矩阵外/非法参数由服务端 400 透传）、`research lens --file <本地图片>`（POST /api/v1/research/lens 的 multipart 字段 file；扩展名与 20MiB 本地预检、150 秒超时、摘要只打印 status/frameSha256/topTitles/links 数量，不打印图片内容）。所有命令支持全局 `--json`；缺配置退出码 2 且不回显 token，图片字节不进入任何输出。
- 规则包与文档同步：`rules.json` 三条 research capabilities 的 `cli` 指向实际命令、研究指南补 CLI 用法并删除「本切片未提供研究 CLI」，规则版本 `2026-11-06.3`；`rulepack.py` 去掉与指南重复的研究节并把 research 命令加入只读命令清单；`tools/ai_maintenance/README.md` 增研究示例与 `RESEARCH_DISABLED`/`RESEARCH_SIDECAR_*`/`RATE_LIMITED`/`INVALID_PARAM` 语义；`docs/api/research.md` 增「命令行工具」小节，注明研究默认关闭、sidecar 需另行部署。
- 验证边界：`tools/test_ai_maintenance.py` 由 23 项增至 31 项并全部通过，仅用临时目录与 `127.0.0.1` 随机端口 stub（研究成功摘要与 --json、POST 路径/Bearer/JSON 体、multipart 字段与文件名、服务端 400/503 透传、缺配置、token 脱敏、本地文件预检）；未访问真实平台、未安装 sidecar、未触碰 8420 真库，也未改 OpenAPI/Go。`python -X utf8 tools/check_docs.py` 0 错误、`python -X utf8 tools/check_privacy.py --mode worktree` passed。第六百六十四笔完整原样移入 docs/history/CHANGELOG-20261028.md，近期日志保持 30 笔。
