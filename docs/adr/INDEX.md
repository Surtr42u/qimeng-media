# ADR 决策索引

> 每行 = 一个决策 + 它的全部影响点。改动任何决策前，先查本表定位全部受影响文件/文档。
> 新增 ADR 后必须同步追加本表（模板见 `docs/templates/adr-template.md`）。

| 编号 | 一句话决策 | 受影响文件/文档 | 状态 |
|---|---|---|---|
| ADR-0001 | 技术栈与 monorepo：Go 单体 + React PWA + Compose + OpenAPI 协议宪法，三端 SDK 自动生成 | `api/openapi.yaml`、根 `Makefile`、`ARCHITECTURE.md` §2/§3 | 已接受 |
| ADR-0002 | 服务端模块化单体 + 明确不做清单（AI 识别/人脸聚类/实时转码/多租户等） | `ARCHITECTURE.md` §1/§5、`DOMAIN_RULES.md` |
| ADR-0003 | SQLite（modernc 纯 Go 驱动）+ sqlc + golang-migrate，含 Postgres 演进路径 | `server/internal/store/`、`server/migrations/`、`ARCHITECTURE.md` §3 |
| ADR-0004 | 数据身份机制：asset_id（UUIDv7）主键，路径是属性，size+mtime 变更检测，移动合并 | `server/internal/scanner/`、`server/migrations/`、`ARCHITECTURE.md` §6 |
| ADR-0005 | ViewEvent 事件流只追加，统计从事件聚合重建（替代计数器累计） | `server/internal/events/`、`server/migrations/`、`ARCHITECTURE.md` §7、`DOMAIN_RULES.md` §5 |
| ADR-0006 | 远程访问只走 Tailscale/WireGuard 隧道，禁止公网端口映射 | `SECURITY.md`、`ARCHITECTURE.md` §9 |
| ADR-0007 | 媒体目录可写（受控）+ 删除=回收站 + 上传白名单四道校验 | `server/internal/filing/`、`DOMAIN_RULES.md` §9、`SECURITY.md` |
| ADR-0008 | UI 策略：逻辑协议共享、UI 各端原生，设计 token 统一视觉 | `web/src/`、`android/`、`ARCHITECTURE.md` §4 |
| ADR-0009 | SDK 生成物不入库，CI 从 openapi.yaml 重建防漂移；AI 禁止手改生成物 | `.gitignore`、`.github/workflows/ci.yml`（sdk-chain）、根 `Makefile`、`ARCHITECTURE.md` §5.1/§10、`AGENTS.md` 铁律 1 |
| ADR-0010 | 模块边界三层强制：Go internal 编译器边界 + depguard 两条红线 + golangci-lint 门禁（不引 arch-go） | `server/.golangci.yml`、`.github/workflows/ci.yml`（server job）、根 `Makefile`、`ARCHITECTURE.md` §5.1 |
| ADR-0011 | 数据库演进式迁移纪律：只加不改不删，改/删走 expand-migrate-contract，SQLite 删列默认不做 | `server/migrations/`、`ARCHITECTURE.md` §5.1、`AI_README_FIRST.md` 开发流程第 4 条 |
| ADR-0012 | 库类型（kind）可扩展体系：识别器分派 + 配套展示，新 kind 接入清单；与 enabled 开关（0007）正交 | `docs/DOMAIN_RULES.md` §6、`server/internal/scanner/`、`api/openapi.yaml` | 已接受（2026-09-03） |
| ADR-0013 | （废弃）M4 走「旧 UI 照搬 + 数据层网络化」——同日被用户二次拍板推翻，调研结论仍有效（内部批次引用已随 0014 重写过期） | 历史记录；调研结论被 `HANDOVER_APP.md` 引用 | **已废弃（2026-09-04，被 0014 取代）** |
| ADR-0014 | M4 Android 走 Compose 重建（先进优先，Now in Android 多模块范式），交互规格照搬 GUIDE_UI，复杂自绘控件允许 AndroidView 桥接；minSdk 26 | `android/`（README、HANDOVER_APP 批次任务书）、`AGENTS.md`、`AI_README_FIRST.md`、`docs/PROJECT_PLAN.md` M4 | 已接受（2026-09-04） |
| ADR-0015 | Android 单机形态：Go 服务端交叉编译进手机（modernc 纯 Go 红利），App 连 localhost，替代旧项目；Termux/App 内嵌两候选形态 | `docs/PROJECT_PLAN.md` M6、`server/`（构建目标）、`android/`（服务器地址配置）、旧项目退役安排 | 已接受（2026-09-04，实施排 M4 后先于 M5） |
