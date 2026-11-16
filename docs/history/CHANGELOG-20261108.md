# CHANGELOG 历史归档：2026-11-08

> 第七百零五～七百零六笔，完整原文迁入；现行状态见 HANDOVER。

## fix(tools): 修复计划实例绑定、删除前复检、合并参数、作者名回读与媒体目录筛选（2026-11-08 第七百零六笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；子代理 ai-core 实现与定向验证）

- 对应 PR #44 Codex inline finding/用户报告 1/2/4/5/6：1) 媒体目录筛选改用 GET /assets directory（精确、不递归、空串=库根），修正 AssetSummary 无 relPath 导致的子目录恒空/根目录误选；_directory 拒绝绝对路径/盘符/反斜杠/空段/./..，media 能力声明补 directory。2) plans.verify_plan 增 instance 校验，跨实例计划抛 PRECONDITION_FAILED(instance_mismatch)，profile/cleanup/asset apply 传 ctx.target，media apply 等价校验。3) cleanup apply 的 inventory 签名扩展 profile revision/status/empty/hasXProfile+sources，执行前整体复检、delete 前逐项复检，变化拒绝并保留已执行步骤。4) cleanup merge 分支不再无条件读 authorId，按动作读取必填参数修复 KeyError。5) asset.authors apply 回读按服务端别名保守归一与 PUT 前作者索引识别按名解析/复用作者，其余额外 id 仍 READBACK_MISMATCH。
- 验证：新增 test_ai_regressions 12 项 + 受影响 24 项通过；未跑全套/CI/真实库。

## feat(tools): 按需工作流模板 platform.playbook、MCP 调用异步化与独立部署文档（2026-11-08 第七百零五笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 用户 2026-11-07 补充要求：统一管理操作提示词/业务规范/工作流、减少对源码与完整文档依赖、MCP 调用期间处理取消、平台独立部署与版本。新增 platform.playbook（按领域列模板或按名取单个，规则节选有界，模板可选不强制路径）；rules.json 增 playbooks（author.profile/media.organize/author.cleanup/maintenance.jobs/backup.rotate）与 toolCatalog.discovery，版本升 2026-11-07.6；CLI 增 playbook 子命令，MCP initialize 指引更新。
- MCP serve_forever 改为 tools/call 独立线程 dispatch + 写锁 + 并发上限（QIMENG_AI_MCP_MAX_CONCURRENCY，默认 4）+ 有副作用工具全局串行；取消通知在调用期间可处理，只读工具立即 -32800、有副作用工具等真实结果（避免误以为未执行）；完成后清理取消标记防 request id 复用误判；新增/更新 MCP 取消与串行化测试。新增 docs/AI_PLATFORM_OPERATIONS.md（CLI/MCP 独立启动停止、配置、版本兼容、升级、NAS 边界）。
- 验证：test_ai_catalog 22、test_ai_mcp 11、test_ai_toolkit 47、test_ai_maintenance 31 通过；未跑全量与 CI。
