# CHANGELOG - 变更历史

本文件归档项目历次功能/修复/决策变更，每条对应 git 提交（commit hash 标注）。

## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型（真实命名，品牌-版本），便于追溯每次改动由谁完成。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。

---

## 底层重构与文档体系升级（2026-08-26，c7d4839）

执行 AI：DeepSeek-V4（主代理 + 执行子代理）

三片底层重构（代码侧已落地，本条目连同文档一并归档）：

| 项 | 要点 |
|---|---|
| 探针鉴权定案 | `/readyz` 免鉴权定案：openapi.yaml:380 标 `security: []`；topRouter 注释改为"以 yaml security 元数据为准" |
| Makefile 落地 | server-run / server-test / web-dev / web-test 真实可用；`make lint` = redocly + golangci-lint + TS；docker-build 标 TODO(M5) 如实占位 |
| golangci-lint 门禁 | 新增 `server/.golangci.yml`（v2.13.1，11 linter）；depguard 两条红线：非 httpapi 包禁 import httpapi/gen；业务包与 sysmon 禁 import httpapi（cmd 组合根例外） |
| CI 对齐 | server job 插入 golangci-lint 步骤（golangci-lint-action@v9）；生成物不入库 + CI 重建链保持 |
| 缩略图档位单一来源 | `server/internal/thumbnail/cachekey.go` 常量（SizeSmall/SizeGrid/SizePreview）；config.Thumbnail.LongSide 默认 0=回落 SizeGrid；Config.Token 与 QIMENG_TOKEN 已删 |
| stubs 注释对齐 | httpapi/stubs.go 各端点里程碑注释与 PROJECT_PLAN 对齐（sysmon 接线 = M2） |

文档体系升级（本条目主体）：

- 新增 ADR-0009（SDK 生成物不入库防漂移）、ADR-0010（模块边界三层强制）、ADR-0011（数据库演进式迁移纪律）+ `docs/adr/INDEX.md` 决策索引 + adr 模板升级 Nygard 五段
- 新增 `docs/CAPABILITY_MAP.md`（能力三态表 + 对标 Jellyfin/Immich/Plex 缺口清单，AI 主动提案依据）
- 新增根 `llms.txt`（llms.txt v2 格式文档导航）
- AGENTS.md / AI_README_FIRST.md 增补模块边界、迁移纪律、生成物禁手改、commit scope 约定、Go/React 警戒线
- ARCHITECTURE §5.1 模块边界强制小节、§10 CI 四 job 实况对齐 ci.yml；OBSERVABILITY/GUIDE_API readyz 免鉴权口径统一；HANDOVER search 包现状更正；PROJECT_PLAN 补常驻任务与验收门禁

## M1 收尾：一键启动脚本（2026-08-22，`1ae33eb`）

执行 AI：GLM-5.3（主代理）

双击 `启动服务端.bat` 即可启动服务端（显示局域网 IP 列表 + 防火墙提示 + 全路径调 go 规避 PATH 截断）。三次迭代修复：中文 echo 在 cmd 代码页下破坏后续命令解析 → 全 ASCII；`go` 不在继承 PATH → 全路径调用。已实测 healthz 通。

## M1 服务端核心闭环完成（2026-08-22，`4a42acd`）

执行 AI：GLM-5.3（主代理 + 执行子代理 ×8）

九个模块全部落地、9 包测试全绿、真实二进制集成验收通过（详见 PROJECT_PLAN M1 勾选）。"9 包"口径：9 个有代码包（auth/config/events/filing/httpapi/scanner/store/sysmon/thumbnail）测试全绿；recommend/search/stats 三个 doc.go 空壳包暂无测试；sysmon 属 M2 提前件随 M1 交付。各模块与执行方式：

| 模块 | 执行 | 要点 |
|---|---|---|
| auth 鉴权 | 子代理 | argon2id PHC + token 哈希 + HMAC 签名直链 + chi 中间件（59 用例） |
| store 存储 | 子代理 | SQLite(modernc) 14 表 + golang-migrate + sqlc keyset 分页（9 测试） |
| thumbnail 缩略图 | 子代理 | ffmpeg 封装 + 黑帧检测（全部采样制）+ 工作池 + 真实 ffmpeg 集成测试（16 用例） |
| events 事件 | 子代理 | 进程内总线（慢订阅者丢弃隔离）+ SSE handler（13 用例双轮稳定） |
| filing 文件安全 | 子代理 | 路径穿越全变体/MIME 魔数/上传四道校验/回收站布局（142 子用例） |
| scanner 扫描器 | 子代理 | 全量/fsnotify 增量/轮询兜底/移动合并启发式（12 测试 -count=3 稳定） |
| httpapi 接口层 | 子代理 | 全浏览闭环端点 + 中文极简验收页 + 端到端测试 |
| sysmon 监控 | 子代理 | gopsutil 快照 + prometheus 业务指标（10 用例；M2 提前件） |
| 接线与收尾 | 主代理 | wire.go 扫描适配器、FinishScan/SetScanner、sm=256 档、自噬防御修复与验收 |

**集成验收实测发现并修复**：数据目录配置在库内时缩略图缓存（webp 白名单格式）被扫描器自噬入库——加两道防线（注册互斥校验 DATA_DIR_CONFLICT + scanner SkipDir/watch 过滤）并补测试。移动合并真机验证通过（改名后 asset_id 不变）。

## 协议变更：删除按需缩放副本（2026-08-22，`4a1f3da`）

执行 AI：GLM-5.3（主代理）

用户明确要求"查看永远发原件"：删除 `/media/preview` 端点与 `previewUrl` 字段，三端 SDK 重新生成。同步更新铁律 1（总说明）、ARCHITECTURE §7、GUIDE_API。同 commit 附带：DOMAIN_RULES 黑帧判定语义澄清（全部采样制）、ADR-0005 事件流无外键实现澄清、m4v 白名单补齐。

## CI 与仓库（2026-08-22，`848ef29`/`11a0fdb`）

执行 AI：GLM-5.3（主代理）

GitHub 私有仓库 `Surtr42u/qimeng-media` 建立；Actions 四道门禁（redocly 协议校验 / Go vet+test+build / Web tsc+build / make sdk 三端生成链）首跑全绿；actions 升级 v5/v6 + Go 缓存路径修复。

## M0 地基完成（2026-08-22，`1625db1`）

执行 AI：GLM-5.3（主代理 + 执行子代理 ×3）

| 项 | 执行 | 要点 |
|---|---|---|
| 协议定稿 v0.1.0 | 主代理 | 评审补 9 处缺口（排序/顺位/includeCos/sessionId/标签删除/时间轴删除/作者关注/回收站清空/缩略图尺寸）+ 修复草案 2 处语法错误；redocly 0 error |
| server 骨架 | 子代理 | go.mod + 12 包 doc.go + chi/slog + config（默认 :8420 测试锁定）+ healthz |
| web 骨架 | 子代理 | Vite+React+TS 严格 + Tailwind v4 + shadcn(radix/nova) + TanStack Query + --qm-* 设计 token |
| make sdk 三端链 | 子代理 | oapi-codegen v2.8.0 / @hey-api 0.99.0 / openapi-generator+JDK17（免安装 zip 方案） |
| GUIDE_API.md | 主代理 | 36 端点分组速览 + 关键机制导读 |

环境侧（不入库）：JDK17 免安装（dev-tools/jdk17）、choco make 4.4.1、winget 不可用结论，均记入 TOOLCHAIN_GUIDE。
