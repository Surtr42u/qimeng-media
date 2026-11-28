# CHANGELOG 历史归档：2026-11-08

> 第七百零五～七百零九笔，完整原文迁入；现行状态见 HANDOVER。

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
