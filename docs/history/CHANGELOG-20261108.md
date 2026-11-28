# CHANGELOG 历史归档：2026-11-08

> 第七百零五～七百一十一笔，完整原文迁入；现行状态见 HANDOVER。

## fix(web): 两代备份写入中断恢复与轮换幂等（2026-11-08 第七百零八笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；子代理 web-client 实现与定向验证）

- 根因：先整代轮换当前→上次再写新当前且无事务标记；中断后重复轮换/半成品被当上次代/正文摘要分开写导致永久摘要不符或丢失完整旧代。修复：新增 maintenance-generation.ts 两代写入原语与恢复判定；写快照前落 .两代轮换状态.json（old/next 摘要），整代轮换（相同跳过、缺摘要补）、写新当前（正文先摘要后）、最后删状态；下次写入按断点自愈，不可判定拒绝并保留现场；相同数据重复导出只刷新当前并修复过期摘要；旧 flat3 只读兼容不变。
- 验证：maintenance-folder.test.ts 24/24、tsc --noEmit、oxlint 0；未跑全量 vitest/Playwright/vite build/真实服务端。待测：真实浏览器崩溃中断自愈、真实 FS removeEntry 失败、跨浏览器差异。

## fix(server): 维护 worker 租约改为每次领取唯一 fencing token 并校验未过期租约（2026-11-08 第七百零九笔）

执行 AI：DeepSeek V4.1 Flash（子代理 go-worker2）

- 根因：Worker 实例 owner 跨领取复用，Claim/Heartbeat/Complete/Advance 只比较 owner 字符串；租约过期被重领后旧执行上下文仍可续租/覆盖步骤工件终态，同任务可并发执行。修复：实例身份 qimeng-worker-<pid>-<uuid> + 每次领取唯一 fencing token；Heartbeat/Finish/Advance SQL 增 lease_until 未过期条件；StepInput/ArtifactInput 增 LeaseOwner，在同一 _txlock=immediate 事务内校验 owner+未过期后写步骤/工件；ErrLeaseLost 只停止不误标 failed，暂停/取消进行中步骤保留 running。
- 验证：go test ./internal/maintenance/ -count=2、go vet、go build、gofmt、sqlc 重生成稳定；新增身份/token 唯一、同实例两次领取 token 不同、旧实例接管后不得覆盖、过期租约全链路围栏测试。未加 migration、未改 API/SDK/业务语义。

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

## feat(server): 独立 AI 客户端凭据、scope 强制校验与写审计（2026-11-08 第七百一十笔）

执行 AI：DeepSeek V4.1 Flash（子代理 go-worker 实现与本地定向验证）

- ADR-0057 P1 首批（不改 OpenAPI/sdk.lock/三端、不加 HTTP 管理端点）：migration 0025 只增 ai_clients（token_hash 唯一、scopes_json/device_ids_json、expires/revoked/last_used）与 ai_audit（只增、无外键）；新包 server/internal/aiauth（scope 白名单/未知拒绝、token 生成+auth.TokenHash、过期/撤销 fail-closed、5s 缓存+Reload、审计、Create/List/Revoke/Rotate）。
- httpapi：管理员会话先行验证（行为不变），未命中再验 AI 凭据；AI 按 method+path 强制 scope（GET/HEAD 兜底 read，维护/备份/研究/设备各自 scope，未映射写默认 403 FORBIDDEN_SCOPE），管理员不受限；AI 写请求含被拒写审计；设备级隔离 device.apply + ai_clients.device_ids，列表/详情/领取/段/回传/取消校验 targetDevice，越权不改状态。
- 管理入口：qimeng ai-client create|list|revoke|rotate -config，直接打开同一 SQLite（先幂等 migration），明文 token 只 stdout 一次；撤销幂等、轮换旧 token 立即失效。验证：go build/vet、aiauth/httpapi/cmd 定向测试通过；未跑全量/CI/真实库。边界：协议化 scope、HTTP 管理端点、限流预算、审计查询、设备配对未实现；异地撤销约 5s 缓存生效。

## feat(app): Android 后台设备结果包 Worker、设备级令牌与 SAF/权限适配（2026-11-08 第七百一十一笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；独立子代理实现与 JVM 验证）

- 复用设备结果包协议与维护账本实现后台 Worker：core/data/device 14 个文件（Runner 编排领取-校验-应用-回读-回传；Worker/Scheduler/WorkSpec 15min 周期+一次性、CONNECTED、unique work+Mutex；Remote/Policy/Authorization/SegmentVerifier/ResultPlanner/LocalBackup/Readback/SettingsRepository/Models）；feature/manage 设备领取页/VM 与维护页入口（设备标签、口令式设备令牌、后台开关、立即应用、最近摘要）。
- 设备授权：专用 AI 令牌（device.apply + device_ids）只用于设备端点，无令牌不回退会话 token；本机模式先拉起内嵌服务；维护根+所有文件访问门禁，缺失回 failed 并提示 UI 授权；先导出两代备份再 import-txt/qimeng-backup，按 expectedReadback 回读比较后回传 result，清理临时段；409 先对账终态不覆盖。
- 验证：:core:data:testDebugUnitTest device.* 34 tests 通过、feature:manage/app compileDebugKotlin 通过、check_docs 0 错误、check_privacy passed。未安装、未真机；Doze/厂商后台、后台拉起内嵌 FGS、SAF/存储授权撤回、PC→手机端到端、断网/进程被杀恢复待验证；无段级进度持久化，进程强杀可能重下整包。
