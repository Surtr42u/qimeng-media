# AGENTS - AI 工作入口

## 项目与当前形态

绮梦影库：面向 NAS 的自托管多端媒体库。Go 模块化单体服务端、React Web/PWA、Kotlin/Compose Android、Tauri 桌面连接壳；Android 可内嵌同一服务端离线单机运行。当前使用手机单机与 PC 过渡形态，未来 NAS 硬件须另行部署验收。协议以 `api/openapi.yaml` 为准。

## 按需读取

**本文件是唯一全量必读入口。** 不默认通读其他文档、ADR、历史或全仓源码。

1. 按下表定位任务，只读相关章节；跨域任务取并集。
2. 所有修改任务先读 `AI_README_FIRST.md`「文档维护」同步矩阵；代码任务另读「开发流程」「代码规范」「代码卫生约束」「验证与交付」，引依赖/自研另读「选型约束」。纯咨询只读所需资料。
3. 用文档、目录和 `rg` 定位实现；先读必要片段，完整理解需要时可读完整文件与调用链。生成目录仅在排查生成问题时读取。
4. 明确有效规则、影响范围、验证方式即可开始；跨域影响、信息缺口或冲突再扩读。历史按关键词/编号查；ADR 先查索引和状态，再读相关条目。

| 任务 | 读取入口与范围 |
|---|---|
| 当前形态、进行中事项、接手未完任务 | `docs/HANDOVER.md`，无需先读里程碑 |
| API/SDK | `api/openapi.yaml` 对应 path/schema + `docs/GUIDE_API.md` 对应专题 |
| 算法、筛选、统计、作者、迁移、缩略图等业务 | `docs/DOMAIN_RULES.md` 目录 → 命中专题，禁止整本默认必读 |
| Go 服务端/模块边界 | `server/README.md`；边界变化另读 `docs/ARCHITECTURE.md` §5/§5.1 |
| Web / Android / desktop UI | 对应 `web/README.md` / `android/README.md` / `desktop/README.md` + `docs/GUIDE_UI.md` 对应部分 |
| 数据库/身份 | ADR 索引定位 0003/0004/0011；相关 migration/查询规则 |
| 鉴权、上传、路径、文件操作、密钥/隐私 | `docs/SECURITY.md` 红线清单 + 对应机制及已知边界 |
| 文件并发/恢复、备份/缓存一致性 | 领域专题 + SECURITY 对应节；恢复演练读 `docs/RESTORE.md`；背景按需查 ADR-0038～0042 |
| 监控、健康检查、指标 | `docs/OBSERVABILITY.md` 对应节 |
| 部署、内嵌打包、模拟器验收 | `deploy/README.md` / `deploy/embedded/README.md` / `deploy/emulator-verify/README.md`；Termux 读其子目录说明 |
| 新功能/能力评估 | `docs/CAPABILITY_MAP.md` 对应域 + ADR 索引相关决策 |
| 架构缘由/历史/里程碑 | `docs/adr/INDEX.md` / `docs/history/INDEX.md` / `docs/PROJECT_PLAN.md`，仅命中时读 |
| 文档组织/维护 | `AI_README_FIRST.md`「文档维护」；完整导航见 `llms.txt` |

## 不可遗漏的红线

- 接口先改 OpenAPI，再 `make sdk`，同批更新 `api/sdk.lock`；禁止手写 SDK 或手改生成物（ADR-0009）。
- schema 只能新增 migration，禁止改历史迁移或运行中的库；改/删结构按 expand-migrate-contract（ADR-0011）。
- 领域公式与口径以领域专题为准；「逐字遵守」的公式常量须用户明确要求才能改变。算法纯函数，客户端不重复计算。
- UI 不直接请求 API 或嵌业务规则；Go 守住 internal/depguard 边界，禁止豁免；新增多步编排下沉业务/编排包（ADR-0019）。
- 删除媒体先移回收站，永久删除仅走独立管理操作。上传同时校验扩展名、MIME、大小、路径；路径规范化且限制在库根内。
- **8420 真库禁止测试写入**；验证用隔离实例、临时数据和合成媒体。
- **Android 必须显式指定 `qimeng_api35` AVD 或用户指定真机**。严禁向 `emulator-5554`（雷电游戏模拟器）安装、启动本项目或执行 wm/settings 调试；不可信赖默认设备选择。
- 口令、令牌、密钥、设备序列号、内网拓扑、含用户名的本机绝对路径不得入库；配置走 `QIMENG_*` 或忽略的 `*.local`。远程只走隧道；开发免密例外与边界见 SECURITY。
- 旧项目只提供历史交互/领域依据，禁止搬旧 Kotlin；复杂自绘 View 的 AndroidView 桥接例外须记交付清单（ADR-0014）。

## 权威与交付

用户当前明确要求优先；协作规则见本文件与 `AI_README_FIRST.md`。协议、领域、安全、架构由各自权威文档负责，旧历史不能覆盖现状；冲突处理见协作规范。

代码与受影响文档同批交付、同 commit；重大决策补 ADR 与索引。交付说明列出更新的专题与原因，确无语义影响须说明依据；CHANGELOG 不能代替专题同步。提交前执行 `python tools/check_docs.py`，PR 另检查改动影响。末尾列实际读取文档：`📋 已读取文档：…`；纯咨询免签。
