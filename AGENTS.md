# AGENTS - qimeng-media AI 代理入口

本文件是 AI 代理的快速入口。**完整协作规则见 `AI_README_FIRST.md`**，动手前必须先读。

## 项目一句话

媒体全存 NAS 的多端媒体库：Go 单体服务端 + React Web(PWA) + Kotlin/Compose Android 客户端 + Tauri 桌面壳（ADR-0014 先进优先路线；单机形态已随 M6 收官落地=ADR-0015；桌面壳=ADR-0020），协议先行（openapi.yaml 是三端唯一事实源）。

## 读取顺序

1. `AI_README_FIRST.md`（强制，含签名规则）
2. 与任务相关的文档（见下表）
3. 对应代码

## 任务 → 文档路由表

| 任务类型 | 必读文档 |
|---|---|
| 任何 API 改动 | `api/openapi.yaml` + `docs/GUIDE_API.md` + `docs/adr/0001` |
| 推荐算法/筛选/统计 | `docs/DOMAIN_RULES.md`（唯一权威，逐字遵守公式与口径） |
| 数据库改动 | `docs/adr/0003`、`docs/adr/0004`（身份机制）、`docs/adr/0011`（迁移纪律）+ migrations 规则 |
| 安全相关（鉴权/上传/文件操作） | `docs/SECURITY.md`（红线清单） |
| 监控/指标 | `docs/OBSERVABILITY.md` |
| UI 开发（web/android/desktop） | `docs/adr/0008`（UI 解耦）+ Android 另读 `docs/adr/0014` + 桌面壳另读 `docs/adr/0020` 与 `desktop/README.md`；现状/待办见 `docs/HANDOVER.md`（HANDOVER_UI/HANDOVER_APP 已删，历史见 CHANGELOG） |
| 新增 Go 包 / 模块边界 | `docs/adr/0010` + `docs/ARCHITECTURE.md` §5.1 |
| 能力缺口评估 / 新功能提案 | `docs/CAPABILITY_MAP.md`（能力地图）+ `docs/adr/INDEX.md` |
| 部署/Docker/NAS | `docs/PROJECT_PLAN.md` M5 + 仓库外 `..\dev-tools\TOOLCHAIN_GUIDE.md` |
| 了解"为什么这么选" | `docs/adr/` 全部 |

## 铁律（违反任何一条都是事故）

1. **先改 openapi.yaml，再生成代码**——禁止手写客户端 SDK，禁止手改生成物（`*.gen.go`/`*.gen.ts`/`android/sdk/**`，ADR-0009）；生成后须同 commit 更新生成物指纹锁 `api/sdk.lock`（指纹入库≠产物入库）。
2. **数据库结构只能通过 migration 文件改**——只加不改不删（ADR-0011）；改/删既有结构走 expand-migrate-contract 两步迁移；禁止手改历史迁移文件、禁止手改运行中的库。
3. **推荐算法/统计聚合是纯函数**——不碰 IO，行为由单元测试锁定，改动必须先看 `docs/DOMAIN_RULES.md` 的公式。
4. **媒体文件操作必须走回收站**——`DELETE` 语义 = 移入回收站，物理删除是独立的管理操作。
5. **上传必须白名单校验**——扩展名 + MIME + 大小上限 + 目标路径穿越检查，四道缺一不可。
6. **路径参数必须规范化并限制在 Library 根内**——任何 handler 禁止直接拼接用户输入的路径。
7. **UI 组件禁止直接调 API、禁止内嵌业务规则**——逻辑在服务端或客户端逻辑层。
8. **新技术先读官方文档再写代码**——不确定的 API 用法必须搜索确认，禁止凭记忆写。
9. **每个重大决策写 ADR**——`docs/adr/` 新增编号文件（编号顺延，格式见 `docs/templates/adr-template.md`），并同步 `docs/adr/INDEX.md`。
10. **改代码必同步文档，同一 commit 提交**——commit 格式 `类型(scope): 简述 | 文档: 已更新XXX`，scope 区分端：api/web/app/server/desktop/docs（如 `feat(api)`、`feat(web)`、`feat(app)`、`feat(desktop)`）。
11. **模块边界不可违反**——depguard 两条红线（`httpapi/gen` 只许 httpapi 用；业务包与 sysmon 禁反向依赖 httpapi，cmd 组合根例外）+ Go internal 编译器边界（ADR-0010）。被 lint 拦截只能按依赖方向重构，禁止开豁免。
12. **新功能先对照能力地图找缺口**——主动提案的依据是 `docs/CAPABILITY_MAP.md`（对标 Jellyfin/Immich/Plex 的现状与缺口），不是等用户撞到问题。
13. **Android 构建部署必须显式指定项目 AVD（qimeng_api35）**——`adb devices` 里的 `emulator-5554` 是用户的雷电游戏模拟器（伪装 MI 9），是"第一个在线设备"的常客，严禁对它安装/拉起本项目任何包、执行 wm/settings 类调试命令；插件与 IDE 的默认设备选择不可信赖，必须显式传 `avd: qimeng_api35`（必要时先冷启动该 AVD）。（2026-09-09 事故：雷电被误当调试机，启梦 App 被装入并顶掉用户游戏）
14. **明文密钥与隐私不入库**——口令/令牌/密钥/设备序列号/内网拓扑/含用户名的本机绝对路径，一律不得进任何被跟踪文件（代码、配置、测试、文档，含 CHANGELOG/HANDOVER 等记录性文档）；配置只走 `QIMENG_*` 环境变量或 gitignore 的 `*.local`，测试口令必须是明显合成值。提交前自查敏感串；发现已入库立即脱敏，已提交的用 `git filter-repo --replace-text` 重写历史（细则见 `docs/SECURITY.md`「仓库卫生」）。（2026-09-30 开源前审计：CHANGELOG 曾明文记录真实管理口令与真机序列号，已全历史重写清除）

## 警戒线（软约束）

代码规模警戒线与代码卫生约束（硬编码/重复/调度）见 `AI_README_FIRST.md`「警戒线」「代码卫生约束」两节，此处只留索引不重复。

## 与旧项目（QimengMedia）的关系

- 旧项目 = 领域知识库（推荐算法/筛选/统计/作者规则已提炼进 `docs/DOMAIN_RULES.md`，服务端 M3 实现完毕）
- **M4 App 端走 Compose 重建（ADR-0014，2026-09-04 用户二次拍板「先进优先」）**：交互规格照搬其 `docs/GUIDE_UI.md`（唯一规格书），实现代码全部 Compose 新写，**禁止搬运旧 Kotlin 实现**；例外：复杂自绘控件（BiliPlayerView/ZoomImageView）允许 AndroidView 互操作桥接，桥接清单入交付报告
- **旧项目退役链已完成（2026-09-20 用户确认，M6 收官）**：退役拍板 → qimeng-backup 行为数据迁入 → 手机媒体原地注册三步全部完成；数据迁移走 `POST /import/qimeng-backup`（DOMAIN_RULES §10），媒体文件原地注册为库。物理归档动作由用户自行处理；本项目此后是唯一维护对象
- 同日 ADR-0013（旧 UI 照搬路线）已废弃，其架构调研结论仍有效（见该 ADR 与 `docs/adr/INDEX.md` 记档）

## 回复签名

完成代码修改后，回复末尾必须附：

```
📋 已读取文档：DOMAIN_RULES.md, SECURITY.md, adr/0004
```

只列实际读过的。纯咨询对话不需要签名。
