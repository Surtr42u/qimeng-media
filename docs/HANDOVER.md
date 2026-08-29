# HANDOVER - AI 交接说明

> 写给下一位接手的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 最后更新：2026-08-29（M2 后端先行收尾完成时点）

## 接手第一步

1. 读 `AI_README_FIRST.md`（协作强制规则，含签名纪律与冲突优先级）
2. 读 `docs/PROJECT_PLAN.md`（里程碑勾选状态 = 进度唯一真相）
3. 读 `docs/CHANGELOG.md`（历次变更 + AI 署名）
4. 按任务类型查 `AGENTS.md` 的任务→文档路由表

## 当前进度（2026-08-29）

- **M0 地基 ✅**、**M1 服务端核心闭环 ✅**、**M2 后端先行全部完成 ✅**（用户拍板：M2 拆为后端先行、UI 后置，见 PROJECT_PLAN M2 节）
- **M2 后端先行全部完成 ✅**（2026-08-29）：sysmon 接线、移动/重命名、回收站五端点、推荐流热度占位、目录树、上传（四道校验+流式+上限配置，协议补 libraryId）、标签池/资产标签/时间轴标签、**FTS5 全文搜索收尾**（迁移 0002 trigram 索引+触发器自动同步；browse 三查询 q 谓词；search 包 ParseQuery/RebuildIndex；upload.done SSE 载荷 schema 补协议）
- **分工定案（2026-08-29 用户拍板）**：M2 UI 段（Web 端）后置——用户另开 AI 路线承接；**M3 后端算法由下一会话/new AI 承接**（本会话只做了 M3 调研，见下方「M3 接手速览」）；未完成的 UI 段不阻塞 M3
- **下一步：M3（推荐算法 10 维/统计/SourceMatcher/作者体系/迁移端点）** → M4 Android → M5 部署；M2 UI 段（Web 用户界面）与 M3 并行不依赖

## M2 后端任务（2026-08-29：全部完成 ✅）

**M2 后端任务全部完成**——见上方进度；FTS5 搜索与 upload.done 载荷 schema 均已交付（commit 338c281）。

M3 及以后的任务表不变（推荐算法/统计/SourceMatcher/作者体系/迁移端点），见 PROJECT_PLAN。

## M3 接手速览（2026-08-29 调研结果，新 AI 直接可用）

| 事项 | 现状 |
|---|---|
| 权威口径 | `docs/DOMAIN_RULES.md` §1（10 维公式+自适应回收+预设表+三个后处理）、§5（统计口径/趋势分桶）、§10（旧数据映射）——逐字遵守，改前必须用户确认 |
| recommend 包 | `server/internal/recommend/` 仅 doc.go 空壳；statsby 同（`internal/stats/` 空壳）。**纯函数**（无 IO，ARCHITECTURE §5 边界） |
| 协议端点 | 已全部定义：`GET /recommendations`（seed/limit/mediaType）、`GET /rankings`（period/limit）、`GET /stats/overview`、`GET /stats/trends`、`GET+PUT /recommendations/prefs`（RecommendPrefs 9 维 schema 已在协议 components）——`make sdk` 生成物三端已含 |
| 热度占位 | `server/internal/httpapi/recommendations.go`（M2 占位：viewCount 降序，seed 忽略）——M3 只换实现，参数面不变；`recommendations_test.go` 已有占位用例 |
| 数据基础 | daily_shown 表（每日展示计数，§1.4 输入）已在 0001 建好；view_events/likes/favorites 齐备；`db` 查询层已有 CountAssetEvents/SumBrowseSeconds/LastViewedAt 等 |
| 旧项目测试（照译） | `<旧项目目录>\app\src\test\java\com\qimeng\media\`：MediaBrowserLogicRecommendTest.kt（推荐核心）、MediaBrowserLogicTest.kt、SourceMatcherTest.kt、AuthorImportUseCaseTest.kt、AppPrefsImportSanitizeTest.kt（迁移） |
| 旧项目算法文档 | `<旧项目目录>\docs\GUIDE_ALGORITHM.md`（applyFilter/recommend/rank/SourceMatcher 细节）——只搬领域规则与公式逻辑，禁止搬 Kotlin 实现 |

> 推荐算法照旧项目测试用例翻译：核心行为锁定点如「同 seed 可复现」「每日惩罚 -0.8×shownCount（随次数递增）」「视频/图片自然混合按剩余比例」「同分桶 ±0.05 打散」等，翻译时必须保留原测试断言语义。

## 怎么跑起来

- **一键启动**：双击根目录 `启动服务端.bat`（端口 8420，数据目录 `qimeng-data/`）
- **验收页**：浏览器开 `http://127.0.0.1:8420`（中文界面：设密码→注册媒体目录→扫描→浏览→播放）
- **测试**：`cd server && go test ./...`（9 包全绿是底线）；Web：`cd web && npx tsc --noEmit`
- **协议改动**：先改 `api/openapi.yaml` → `make sdk` → 按编译错误适配三端（铁律 1）
- CI 在 GitHub Actions（push 自动跑四道门禁），仓库：`Surtr42u/qimeng-media`（私有，gh 已登录）

## 后端还剩什么（重要：不是"只剩 UI"）

M1 只完成了**浏览闭环**。剩余后端工作量不小：

| 剩余项 | 里程碑 | 规模预估 |
|---|---|---|
| ~~sysmon 接线~~ | M2 | ✅ 2026-08-27 完成 |
| ~~移动/重命名/回收站端点~~ | M2 | ✅ 2026-08-27 完成（恢复语义与已知限制见 DOMAIN_RULES §9） |
| ~~推荐占位/目录树/上传/标签体系~~ | M2 | ✅ 2026-08-29 完成（上传协议补 libraryId；upload.done SSE 载荷 schema 待补） |
| ~~FTS5 全文搜索~~ | M2 | ✅ 2026-08-29 完成（迁移 0002 触发器自动同步 + browse q 谓词 + search 包；详见 PROJET_PLAN M2） |
| **推荐算法 10 维移植（DOMAIN_RULES §1 逐字遵守 + 自适应权重回收 + 三个后处理）** | M3 | 大 |
| 统计聚合/趋势分桶（§5 口径） | M3 | 中 |
| SourceMatcher 出处匹配引擎（131 SourceGroup 表从旧仓库翻译） | M3 | 大 |
| 作者双体系（TXT 三格式解析 + COS 目录扫描） | M3 | 大 |
| 旧数据迁移端点（qimeng_backup.json 17 段映射） | M3 | 中 |

UI（M2 前半 + M4 Android）与上述后端并行推进。

## 本项目的特色纪律（换 AI 最容易踩的坑）

1. **协议先行**：任何接口改动第一步永远是 openapi.yaml，手写客户端 SDK 是事故
2. **migration 唯一**：0001 已发布（commit 1625db1），改表结构只能新增 0002+
3. **DOMAIN_RULES「逐字遵守」的常量/公式**：改动必须先经用户确认
4. **用户强偏好**：查看媒体**永远发原件**（缩放副本方案已被否决，commit 4a1f3da）；措辞用"看视频/图片"不用"看片"
5. **commit 格式**：`类型(模块): 简述 | 文档: 已更新XXX`，代码+文档同一 commit
6. **并发子代理上限 2 个**（第 3 个会撞 user concurrency limit 直接失败）
7. **Windows 环境**：无 winget；JDK 免安装在 `../dev-tools/jdk17`；bat/make 运行时输出必须纯 ASCII（cmd 代码页坑）
8. 生成物（gen/generated/android-sdk）不入库，`make sdk` 重建

## 领域知识库（旧项目）

`<旧项目目录>`（Android 单机版，2.4 万行）：**领域规则照搬、实现代码禁止搬运**。算法细节参考其 `docs/GUIDE_ALGORITHM.md`、`GUIDE_AUTHOR.md`（SourceMatcher 131 表源数据）、M4 交互规格参考 `GUIDE_UI.md`；近期审查/修复沉淀的需求级结论见本仓库 `docs/LEGACY_REQUIREMENTS.md`（标签管理/缩略图代表帧/搜索作者维度/统计口径等，M2~M4 实现前对照）。
