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
| 数据库改动 | `docs/adr/0003`、`docs/adr/0004`（身份机制）+ migrations 规则 |
| 安全相关（鉴权/上传/文件操作） | `docs/SECURITY.md`（红线清单） |
| 监控/指标 | `docs/OBSERVABILITY.md` |
| UI 开发（web/android） | `docs/adr/0008`（UI 解耦策略）+ 对应端 GUIDE（建立后） |
| 部署/Docker/NAS | `docs/PROJECT_PLAN.md` M5 + 仓库外 `..\dev-tools\TOOLCHAIN_GUIDE.md` |
| 了解"为什么这么选" | `docs/adr/` 全部 |

## 铁律（违反任何一条都是事故）

1. **先改 openapi.yaml，再生成代码**——禁止手写客户端 SDK。
2. **数据库结构只能通过 migration 文件改**——禁止手改库、禁止跳过迁移直接建表。
3. **推荐算法/统计聚合是纯函数**——不碰 IO，行为由单元测试锁定，改动必须先看 `docs/DOMAIN_RULES.md` 的公式。
4. **媒体文件操作必须走回收站**——`DELETE` 语义 = 移入回收站，物理删除是独立的管理操作。
5. **上传必须白名单校验**——扩展名 + MIME + 大小上限 + 目标路径穿越检查，四道缺一不可。
6. **路径参数必须规范化并限制在 Library 根内**——任何 handler 禁止直接拼接用户输入的路径。
7. **UI 组件禁止直接调 API、禁止内嵌业务规则**——逻辑在服务端或客户端逻辑层。
8. **新技术先读官方文档再写代码**——不确定的 API 用法必须搜索确认，禁止凭记忆写。
9. **每个重大决策写 ADR**——`docs/adr/` 新增编号文件，格式见 `docs/templates/adr-template.md`。
10. **改代码必同步文档，同一 commit 提交**——commit 格式 `类型(模块): 简述 | 文档: 已更新XXX`。

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
