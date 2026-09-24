# HANDOVER - 交接说明

> 写给下一位接手的 AI。人类用户无编程基础，代码由 AI 生成。
> **最后更新：2026-09-25**（§4 追加「上传挂靠作者与来源」能力段——ADR-0023）。**2026-09-22**（文档严格对齐：删除过时交接/任务书，本文件重写为现状-only。历史批次一律见 `docs/CHANGELOG.md`。）

## 1. 项目一句话

媒体全存 NAS 的多端媒体库：Go 单体服务端 + React Web/PWA + Kotlin/Compose Android + Tauri 桌面壳。协议先行（`api/openapi.yaml` 是三端唯一事实源）。

## 2. 接手第一步

1. `AI_README_FIRST.md`（协作强制规则）
2. 本文件（现状与待办）
3. `docs/PROJECT_PLAN.md`（里程碑勾选 = 进度唯一真相）
4. 按任务查 `AGENTS.md` 路由表
5. 需要历史细节时再翻 `docs/CHANGELOG.md`（勿把历史笔当现状）

## 3. 里程碑现状（截至 2026-09-22）

**M0–M6 全部收官**：M5 于 2026-09-22 用户确认真机 NAS 测试完成（部署链路见 `deploy/README.md`，实测记录在文末）。

| 里程碑 | 状态 | 备注 |
|---|---|---|
| M0 地基 | ✅ | 协议/工具链/CI |
| M1 服务端核心闭环 | ✅ | 扫描/缩略图/直链/回收站 |
| M2 Web 完整体验 | ✅ | 九页真数据 + PWA + 上传/整理 |
| M3 算法与领域 | ✅ | 推荐/统计/SourceMatcher/作者/迁移 |
| M4 Android 客户端 | ✅ | Compose 多模块全功能 |
| M5 NAS 部署验收 | ✅ | **2026-09-22 用户确认真机 NAS 测试完成**；arm64 镜像移 M7+ 按需储备（实测部署形态 amd64），大库压测以日常使用覆盖不设专项 |
| M6 单机形态 | ✅ | Termux 形态 A + App 内嵌形态 B + 旧项目退役 |

## 4. 已交付能力（对照代码，勿再当缺口）

- **Web**：上传（`LibraryUploadPage`/`UploadCard`）、图片查看器、批次导航、ArtPlayer、confirm 弹窗、回收站（`TrashPage`）、备份导入导出（`BackupCard`）、库文件快照（`DbBackupCard`）、库管理/作者 TXT 导入
- **Android**：浏览/播放/上传（含文件夹）/离线队列/缓存/备份/数据管理/本机模式（含后台冻结自愈：回前台探测 `/healthz` 无响应自动重拉 + 残留子进程 pid 回收，2026-09-25 第三百九十笔）
- **服务端**：扫描、缩略图、推荐/统计、回收站、热备快照、迁移导入导出、SSE
- **上传挂靠作者与来源（2026-09-25，ADR-0023）**：上传时可选指定作者（单选）与来源（多选），挂靠直接写进 TXT 片段本体（唯一真相、统一重建不丢不重、与入库同事务失败安全）；同文件名重导入缺省 409 二选一（keep 并回/remove 明示）；作者联想 /authors/suggest + 来源词表 /authors/sources + 片段导出下载 /authors/import-txt/export + 本地总表镜像 /authors/mirror（用户显式配置唯一例外写点、单向尽力而为）。Web 上传卡/TXT 导入卡/设置页与 Android 上传页已接（capabilities.authorAttach 显隐），桌面壳零改动自动覆盖；协议路径 59→63

## 5. 当前待办（只列真缺口）

### 用户节点（非 AI）

1. ~~真机 NAS 就绪后：buildx 双架构 arm64 + 大库扫描压测（M5 收尾）~~ —— **已随 2026-09-22 真机 NAS 测试收官**（arm64 移 M7+ 按需储备、压测以日常使用覆盖）
2. 本机硬件不稳（WHEA 断电，2026-09-16 记档）：构建/压测前建议排 BIOS/电源
3. 建议换管理密码（真实密码曾明文入历史文档，CHANGELOG 可溯不可改，唯一止血=轮换；详见 `REVIEW-20260922.md` §2.1）

### 建议立项（按优先级）

1. **安全默认值**：日常/生产关闭 `QIMENG_AUTH_DEV_MODE`；换管理密码并勿写入文档；内嵌形态补共享密钥校验（SECURITY 规划项）
2. ~~磁盘生命周期：删除资产/库时清缩略图；回收站到期自动物理清除~~ —— **已实现（2026-09-22 复查批：trash.retention_days/sweep_interval 配置 + 到期清扫 + 缩略图四入口联动清理，见 SECURITY/DOMAIN_RULES §9/§11）**；全量对账任务（扫描器外部删除产生的孤儿）仍为规划项
3. **工程卫生**：收敛两份 `启动服务端*.bat`；根 `docker-compose.yml` 与 `deploy/` 二选一（2026-09-22 复查批处理，见 CHANGELOG 当日笔）
4. **能力窄缺口**：备份**调度参数编辑** UI（启用/间隔/保留份数现仅可看，改走 yaml/env+重启）；Web 缓存档位选择 UI（任务 S 后改档入口空缺）

### 明确不做（禁止再提案）

见 `docs/CAPABILITY_MAP.md`「明确不做」：公网裸端口、实时转码、AI 识别、iOS、跨端 UI 框架、多租户 SaaS、协议缺口 #21/#29~#34。

### 已知问题（用户拍板暂不修）

- sourcematcher 别名 `"SC"` 使 `Screenshot_*.jpg` 误归「星际争霸」——真机部署后若误伤明显再议（候选：删该别名 + 连动测试/`DOMAIN_RULES §4`）

## 6. 线上/本地使用入口

| 形态 | 入口 |
|---|---|
| PC 日常 | 双击 `启动服务端.bat`（8420，数据 `qimeng-data/`，**含 DEV 免密——生产禁用**） |
| 手机连 NAS | `http://<PC局域网IP>:8420` |
| 手机本机（M6） | `127.0.0.1:18430`（Termux 形态 A 或 App 内嵌形态 B） |
| 桌面壳 | `%APPDATA%\media.qimeng.desktop\server.json` |

## 7. 用户约定（长期有效）

1. 项目日常阶段本地可用 dev 免密；**实机/生产禁止** `auth_dev_mode`（见 `SECURITY.md`）
2. 不要单独拉前端：统一访问 8420（后端托管 `web/dist`）；改前端先 `npm --prefix web run build`
3. UI 调试测试库：样例相册目录（见历史 CHANGELOG）；换库/删库前先问
4. **Android 构建必须显式 AVD `qimeng_api35`**——禁止碰 `emulator-5554`（雷电游戏模拟器）
5. 8420 真库永不写截图；证据用隔离实例虚构数据

## 8. 文档地图（现行）

| 文档 | 职责 |
|---|---|
| `AGENTS.md` / `AI_README_FIRST.md` | 协作规则与铁律 |
| `PROJECT_PLAN.md` | 里程碑勾选 |
| `CHANGELOG.md` | 历史变更（只增不改写正文） |
| `ARCHITECTURE.md` / `DOMAIN_RULES.md` / `SECURITY.md` / `OBSERVABILITY.md` / `GUIDE_API.md` | 架构/领域/安全/监控/协议导读 |
| `CAPABILITY_MAP.md` | 能力三态与明确不做 |
| `REVIEW-20260922.md` | 最近一次全库只读审查与勘误 |
| `docs/adr/` | 架构决策 |

> 已删除勿再寻找：`HANDOVER_UI.md`、`HANDOVER_APP.md`、`REPLICATION_GAPS.md`、`AUDIT-20260920.md`、`任务书-*.md`、`P2-*`、仓库外 `_archive-20260917/` 与《任务*.md》《待拍板-*.md》。历史可溯 git 与 CHANGELOG。
