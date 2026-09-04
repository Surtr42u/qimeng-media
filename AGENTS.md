# AGENTS - qimeng-media AI 代理入口

本文件是 AI 代理的快速入口。**完整协作规则见 `AI_README_FIRST.md`**，动手前必须先读。

## 项目一句话

媒体全存 NAS 的多端媒体库：Go 单体服务端 + React Web(PWA) + Kotlin/Compose Android 薄客户端，协议先行（openapi.yaml 是三端唯一事实源）。

## 读取顺序

1. `AI_README_FIRST.md`（强制，含签名规则）
2. 与任务相关的文档（见下表）
3. 对应代码

## 任务 → 文档路由表

| 任务类型 | 必读文档 |
|---|---|
| 任何 API 改动 | `api/openapi.yaml` + `docs/GUIDE_API.md`（建立后）+ `docs/adr/0001` |
| 推荐算法/筛选/统计 | `docs/DOMAIN_RULES.md`（唯一权威，逐字遵守公式与口径） |
| 数据库改动 | `docs/adr/0003`、`docs/adr/0004`（身份机制）、`docs/adr/0011`（迁移纪律）+ migrations 规则 |
| 安全相关（鉴权/上传/文件操作） | `docs/SECURITY.md`（红线清单） |
| 监控/指标 | `docs/OBSERVABILITY.md` |
| UI 开发（web/android） | `docs/adr/0008`（UI 解耦策略）+ 对应端 GUIDE（建立后） |
| 新增 Go 包 / 模块边界 | `docs/adr/0010` + `docs/ARCHITECTURE.md` §5.1 |
| 能力缺口评估 / 新功能提案 | `docs/CAPABILITY_MAP.md`（能力地图）+ `docs/adr/INDEX.md` |
| 部署/Docker/NAS | `docs/PROJECT_PLAN.md` M5 + 仓库外 `..\dev-tools\TOOLCHAIN_GUIDE.md` |
| 了解"为什么这么选" | `docs/adr/` 全部 |

## 铁律（违反任何一条都是事故）

1. **先改 openapi.yaml，再生成代码**——禁止手写客户端 SDK，禁止手改生成物（`*.gen.go`/`*.gen.ts`/`android/sdk/**`，ADR-0009）。
2. **数据库结构只能通过 migration 文件改**——只加不改不删（ADR-0011）；改/删既有结构走 expand-migrate-contract 两步迁移；禁止手改历史迁移文件、禁止手改运行中的库。
3. **推荐算法/统计聚合是纯函数**——不碰 IO，行为由单元测试锁定，改动必须先看 `docs/DOMAIN_RULES.md` 的公式。
4. **媒体文件操作必须走回收站**——`DELETE` 语义 = 移入回收站，物理删除是独立的管理操作。
5. **上传必须白名单校验**——扩展名 + MIME + 大小上限 + 目标路径穿越检查，四道缺一不可。
6. **路径参数必须规范化并限制在 Library 根内**——任何 handler 禁止直接拼接用户输入的路径。
7. **UI 组件禁止直接调 API、禁止内嵌业务规则**——逻辑在服务端或客户端逻辑层。
8. **新技术先读官方文档再写代码**——不确定的 API 用法必须搜索确认，禁止凭记忆写。
9. **每个重大决策写 ADR**——`docs/adr/` 新增编号文件（编号顺延，格式见 `docs/templates/adr-template.md`），并同步 `docs/adr/INDEX.md`。
10. **改代码必同步文档，同一 commit 提交**——commit 格式 `类型(scope): 简述 | 文档: 已更新XXX`，scope 区分端：api/web/app/server/docs（如 `feat(api)`、`feat(web)`、`feat(app)`）。
11. **模块边界不可违反**——depguard 两条红线（`httpapi/gen` 只许 httpapi 用；业务包与 sysmon 禁反向依赖 httpapi，cmd 组合根例外）+ Go internal 编译器边界（ADR-0010）。被 lint 拦截只能按依赖方向重构，禁止开豁免。
12. **新功能先对照能力地图找缺口**——主动提案的依据是 `docs/CAPABILITY_MAP.md`（对标 Jellyfin/Immich/Plex 的现状与缺口），不是等用户撞到问题。

## 警戒线（软约束）

代码规模警戒线与代码卫生约束（硬编码/重复/调度）见 `AI_README_FIRST.md`「警戒线」「代码卫生约束」两节，此处只留索引不重复。

## 双模型协作流程（2026-09-04 用户拍板）

白天 GLM-5.3 规划与复查、夜间 GLM-Flash 执行（套餐额度分工），交接全走既有文档体系（HANDOVER 待办 / CHANGELOG / 本文件铁律），**不另建任务包文件**：

- **白天规划（直接对话，无命令）**：5.3 把计划写进 `docs/HANDOVER.md`「明天待办」，细到 Flash 可直接执行——①冻结设计决策（字段/结构/UI 规格/依赖版本/数值口径；协议与 migration 给到 yaml/SQL 片段级）②内联验收命令（go test / tsc / build / 对齐实测公差）③指明涉及文件。新依赖先搜官方文档确认版本用法，结论写进待办。
- **白天复查（直接对话）**：读 CHANGELOG 最新笔 + `git diff`，独立复跑 `go test` 与 `tsc` 各一次（不信口头全绿）；协议/迁移/安全/新依赖改动逐行对 diff，其余抽查；结论直接对话给用户，返工项写回待办。
- **夜间执行（用户级 `/subagent` 启动）**：`/subagent 按 HANDOVER.md「明天待办」第 N 项执行，遵守 AI_README_FIRST 铁律；我在线可问，不在按存疑纪律停手`。执行走 /subagent 完整流程；计划未覆盖/决策冲突/验收不过 → 停该项，把存疑点（现象/位置/判断/建议）记到该待办条目下，继续下一项，**禁止自由发挥补设计**。
- **署名**：方案与执行分离时双署名——`方案 AI：GLM-5.3` 与 `执行 AI：GLM-Flash` 两行、方案行在前；同一 AI 完成维持单行 `执行 AI：xxx` 现状；5.3 复查未产生代码不记条目。详见 CHANGELOG「AI 署名约定」。
- **待办生命周期**：完成后条目从 HANDOVER 移除，细节沉淀在 CHANGELOG/commit——HANDOVER 不因计划膨胀。

## 与旧项目（QimengMedia）的关系

- 旧项目 = 领域知识库（推荐算法/筛选/统计/作者规则已提炼进 `docs/DOMAIN_RULES.md`）
- **禁止**把旧项目的 Android 单机架构代码搬进来；领域规则照搬，实现全部重写
- 旧项目数据迁移：一次性导入端点，见 `docs/DOMAIN_RULES.md` §10

## 回复签名

完成代码修改后，回复末尾必须附：

```
📋 已读取文档：DOMAIN_RULES.md, SECURITY.md, adr/0004
```

只列实际读过的。纯咨询对话不需要签名。
