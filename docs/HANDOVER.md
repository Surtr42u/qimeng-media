# HANDOVER - 交接说明

> 写给下一位接手的 AI。人类用户无编程基础，代码由 AI 生成。本文件维护项目的当前状态、技术架构、各端已交付能力与待办事项。历史变更与事故复盘细节请直接查阅 `docs/CHANGELOG.md`。

## Android 外观交付（2026-10-20）

- **Android 外观最终合入（2026-10-20，用户确认）**：日间整页、卡面及窗口使用 #FFFFFF，辅助面为中性灰；共享 SoftDaylight 轻影为黑色 6%、8dp 模糊、(0,2)dp 偏移。首页搜索/筛选/列数、推荐/COS/排行榜主胶囊和相册维度/值胶囊复用轻影，不加灰色染色或硬描边；选中染色、尺寸、字号、间距和按压/切换语义保持原样。设置入口改名“外观”，提高两档次级文字与系统栏可读性；深色背景/材质保持原值。相册维度与值区块均已接线并实看展开/收起，指定 qimeng_api35 最终工作区 Debug 构建通过（21s），隔离导出的精确暂存代码另构建通过（1m12s）且同组 155 项单测零失败，UI/首页/相册/设置/统计共 155 项单测零失败；各页面实览的跨轮范围见 [交付记录](ANDROID_UI_TASKBOOK.md)。真机观感、帧率/功耗、大字体/旋转、API ≤30 未验收；8420 无测试写入，手机 Release 未替换。只整理本次 UI 与相关文档合入 master，其他已有未提交内容保留；远端同步及提交状态以 Git 为准。

## 1. 项目一句话

媒体全存 NAS 的多端媒体库：Go 单体服务端 + React Web/PWA + Kotlin/Compose Android + Tauri 桌面壳。协议先行（`api/openapi.yaml` 是三端唯一事实源）。

## 2. 接手第一步

1. `AI_README_FIRST.md`（协作强制规则）
2. 本文件（现状、技术架构与待办）
3. `docs/PROJECT_PLAN.md`（里程碑勾选 = 进度唯一真相）；按任务查 `AGENTS.md` 路由表
4. 需要历史细节与变更记录时翻 `docs/CHANGELOG.md`（更早见 `docs/history/CHANGELOG-ARCHIVE.md`；勿把历史笔当现状）

## 3. 里程碑现状（截至 2026-09-22）

**M0–M6 全部收官**：M5 于 2026-09-22 用户确认真机 NAS 测试完成（部署链路见 `deploy/README.md`，实测记录在文末）。

| 里程碑 | 状态 | 备注 |
|---|---|---|
| M0 地基 | ✅ | 协议/工具链/CI |
| M1 服务端核心闭环 | ✅ | 扫描/缩略图/直链/回收站 |
| M2 Web 完整体验 | ✅ | 九页真数据 + PWA + 上传/整理 |
| M3 算法与领域 | ✅ | 推荐/统计/SourceMatcher/作者/迁移 |
| M4 Android 客户端 | ✅ | Compose 多模块全功能 |
| M5 NAS 部署验收 | ✅ | 真机 NAS 测试完成；arm64 镜像移 M7+ 按需储备，大库压测以日常使用覆盖 |
| M6 单机形态 | ✅ | Termux 形态 A + App 内嵌形态 B + 旧项目退役 |

## 4. 已交付能力与技术架构（对照代码，勿再当缺口）

### Web 端（React 19 + TypeScript + Vite + Tailwind CSS + PWA）
- **设计语言「绮梦流光 · Aurora Glass」（ADR-0031）**：暗色优先液态玻璃风格（极光渐变画布 + backdrop-filter 材质 + 发丝描边高光）。`tokens.css` 为色彩单源，`styles/glass.css` 承接类名契约；明暗切换走 View Transitions API 原生过渡。
- **上传工作台与断点续传（ADR-0028）**：
  - 直传与分片自动分流（≥16MB 走 8MB 分片 tus 会话流，支持 offset 探测续传；<16MB 直传）。
  - 支持拖拽目录递归上传（保持库内相对路径映射），选完即传与超限入队前拦截。
  - 上传队列单例化管理（`lib/upload-queue-store.ts`），未终态条目持久化至 IndexedDB，刷新页面可恢复条目信息。
- **媒体浏览与播放**：图片查看器、ArtPlayer 播放器（直链 Range 流式播放）、相册与网格布局（按日分组）。
- **实时事件感知（ADR-0029）**：`SseBridge` 长连监听 `favorite.changed` / `like.changed`，秒级失效 TanStack Query 根缓存，SSE 重连自动对齐数据状态。
- **数据管理与维护**：通用来源词表建议、作者 TXT 总表镜像与导入/导出、回收站管理（还原/彻底删除）、数据管理 Hub 词表维护（停用词与出处检索组直接编辑）。
- **交互防线与可靠性**：`ConfirmDialog` 支持 busy 状态（防重复提交、失败可重试）、SPA 路由拦截与 `beforeunload` 未保存离开保护、未知 API 错误统一人类可读兜底。

### Android 端（Kotlin + Jetpack Compose + Hilt + Media3 + Room）
- **架构与导航体系（ADR-0014）**：纯 Compose 重建，遵循单向数据流与 UI 解耦（ADR-0008）；`QimengNavHost` 采用「常驻层（懒驻留 + alpha/semantics 隔离）+ 跳板路由」双载体架构，消除 Tab 切换白屏与闪烁。
- **悬浮玻璃坞底栏（FloatingTabDock）**：独立悬浮胶囊底栏，支持单颗指示胶囊弹性滑动与透镜折射材质；容器层（`Column` / `Row` / `DockBody`）全链路挂载 `consumeTaps()` 空白隔离，杜绝胶囊四周及两端槽内 18dp 呼吸空隙误触穿透至底层媒体卡片。
- **搜索与筛选系统**：
  - 完整三态（入口/建议/结果），支持补全防抖、历史记录与深链直达。
  - 搜索结果态顶部固定呈现两级选项卡（媒体类型：`综合/视频/动图/图片`；分区：`全部/常规/COS`），遵循 `DOMAIN_RULES.md` §6 协议参数映射；选项切换即时回顶网格，换词重置为默认档位。
- **上传与后台传输**：SAF 系统文件与系统分享入口，支持批量默认属性持久化；接线 WorkManager 队列，≥16MB 自动走断点续传分片管线；支持归档文件夹一键匹配库名上传。
- **缓存与预取机制**：`GET /library/revision` 变更探测跳过（ADR-0026），双池本地缩略图磁盘缓存短路；Room 本地浏览事件离线队列（跨库兜底重映射 ADR-0037）与定时同步。
- **单机模式（M6，ADR-0015）**：支持内嵌 Go 服务端自愈拉起（`EmbeddedServerService`），本地直连 `127.0.0.1:18430`，支持本地词表直接维护与两端词表增量合并（ADR-0035）。
- **文件归档与编辑安全（ADR-0038）**：归档统一无覆盖语义（同卷优先 `renameTo` 改名快路径，跨卷受控复制至唯一 `.part` 临时文件）；禁用 AOSP 抛异常的 `getFileStore` 平台探针；编辑页来源基线未就绪 fail-closed，多步保存按成功步骤记账重试。

### 服务端（Go 单体 + Chi + SQLite + sqlc）
- **媒体核心服务**：库注册与并发扫描（全量/fsnotify 增量/轮询），库根变动自动重挂（ADR-0025）；三档缩略图懒生成（支持 EXIF Orientation 矫正与失效联动）；带签名媒体直链（Range 分片与窗口对齐缓存 ADR-0027）；回收站安全隔离与定期清扫。
- **领域与算法引擎（`DOMAIN_RULES.md` 唯一权威）**：
  - 推荐算法（十维协同打分，纯函数无 IO，单元测试锁定行为）。
  - 统计与热度聚合（事件物化表与趋势分桶）。
  - 出处匹配引擎（`SourceMatcher` 别名快速查表与角色反查）。
- **传输与同步通道**：
  - 流式直传 + tus 极简子集断点续传（POST /uploads，分片 Seal 校验，24h 无活动清扫）。
  - 本机文件夹自动同步通道（ADR-0030）：`QIMENG_LOCAL_SYNC_ROOT` 轮询监测，子文件夹名自动匹配入库，根级 TXT 片段导入归档（同名只留最新）。
- **文件操作安全（ADR-0038）**：`filing.MoveFile` 预检全面拒绝既有目标，跨卷复制以 `O_EXCL` 独占创建，回收站清理识别冲突不误删非本次目标文件。
- **安全与可靠性加固（2026-10-17 第五百三十六笔）**：JSON 端点强制 `Content-Type: application/json`；auth 限速只在实际凭据校验前计数（dev 404/坏格式不耗配额）；token 响应 `no-store`；ffmpeg/ffprobe 输入 `-protocol_whitelist file`；local-sync 409 不透传绝对路径；后台任务统一 `safego` panic 兜底；优雅关闭超时不再 `os.Exit(1)`（SSE 在线时也能完成清理）；HTTP `IdleTimeout`/`MaxHeaderBytes`；SQLite 连接池上限 + facets 并发限 4；缩略图预热单飞；回收站恢复清理补 `ErrMoveDidNotCreateTarget` 哨兵；migration 0018 补四个性能索引（作者/标签反查、likes(day)、assets(mtime)）。
- **事件面与多端联动**：SSE 广播 `favorite.changed` / `like.changed`（ADR-0029）；跨库浏览事件通过文件名与相对路径兜底重映射（ADR-0037）。
- **扫描与文件恢复一致性（2026-10-19，ADR-0039）**：扫描部分失败保留破坏性差集；扫描提交与文件整理共用短库锁、移动合并事务化；静态符号链接/junction 拒绝与可行路径的 os.Root 锚定；恢复持久化原身份/落点意图后提交，失败可重试且扫描避让；续传 complete 保留封存源和已发布落点，失败重试不生成重复文件。同卷快路径保留，外部进程并发换链、真实掉电/跨卷硬件验证的边界见 SECURITY。
- **数据库与性能**：SQLite WAL 模式 + `synchronous=NORMAL`；扫描入库段级事务化；多维度聚合并行计算；DSN 连接池与查询计划锁保护。

### 资产编辑与作者体系（跨端 + 服务端，ADR-0023 / 0024）
- **唯一真相源**：TXT 片段本体为作者数据唯一权威，编辑直接更新片段，统一重建不丢不重；保存作品行登记进上传条目，自愈补齐缺失行。
- **作者管理族（ADR-0024 修订 5）**：`POST /authors` 新建、`PUT /authors/{id}` 原地改名与关联随迁、`DELETE /authors/{id}` 级联清理（保留媒体文件）；回写身份自洽保障（`AliasesForAuthorID` 单源重建编号行），消除别名解析漂移。
- **通用来源词表**：`/authors/source-vocabulary` 服务端维护，仅作为获取渠道建议，不参与出处匹配。
- **智能联想与建议**：出处建议（`/authors/suggest`）、作品名序号联想（`/assets/name-suggestions`）。

## 5. 当前待办（只列真缺口）

1. **未来 NAS 构建部署与设备验收（用户节点）**：用户 2026-10-19 明确当前尚无 NAS，现阶段以手机单机/PC 过渡形态为准。硬件就绪后，2026-10 批次服务端能力与 migration 0018、ADR-0039 修复需构建到目标容器，并针对真实卷权限、库失联、跨卷移动及备份恢复验收；保留既有 M5 历史记录，不将其当成本轮真实设备已验收的证据。
2. **会话远程吊销端点 + 401 专用错误码**：SECURITY 规划项，待设备退役或公网隧道暴露常态化时触发。
3. **缩略图双管道分派与孤儿全量对账**：DOMAIN_RULES §11 规划项，待十万级大库性能需求触发。
4. **Android SSE 静默黑洞看门狗**：网络无感断连自愈检测，当前依赖 5min TTL 兜底。
5. **GBK/GB18030 TXT 编码支持**：本机同步通道当前仅支持 UTF-8（剥 BOM），待用户实际遇到非 UTF-8 TXT 时触发。
6. **本机同步秒级响应与指标化**：当前为 30s 轮询，待延迟敏感需求出现时引入 fsnotify。
7. **来源与词表写入 UTF-8 有效性门禁**：防非法字节入库，待实际出现乱码词条时强化。
8. **容器与 CI 供应链加固（审计 P2/P3，未落地）**：Docker 基础镜像 digest 固定 + 非 root + cap_drop/no-new-privileges（需 NAS 卷权限实测）；GitHub Actions 全部 pin SHA + 顶层 `permissions: contents: read`；出包 workflow 的 Go 补丁位与 ci 对齐（`1.27.x`→`1.27.0`）；sdk.lock 门禁的 Windows/Linux 重算差异（2026-10-11 起 master CI 已转绿，但注释与出包绕行口径待清理）。
9. **依赖漏洞门禁（审计 P1，未落地）**：Dependabot（gomod/npm/cargo/actions 四生态）+ govulncheck / npm audit / cargo audit / dependency-review；Go 直接依赖 6 项 patch 可升（prometheus client_golang/common、gopsutil、x/crypto、x/sync、modernc/sqlite）。
10. **审计遗留的 M/L 项**：httpapi 编排下沉（ADR-0019，L）；`GET /authors` 的 view_count 逐关联行 COUNT 预聚合（作者页随库增长变慢）；断点续传会话总量上限；DB/WAL/快照文件强制 0600；访问日志（含脱敏）；`/readyz` 补媒体目录/磁盘检查；大库 EXPLAIN QUERY PLAN 实测验证 0018 索引命中（尤其 mtime 排序 CASE 表达式）。scanner 静态链接跳过已随 ADR-0039 落地。
11. **2026-10-19 第二轮 R1–R5 已修（ADR-0040）**：v2 便携备份保留事件身份，0019 旁表给无键旧事件分配持久身份；多库 recordKey 全局唯一且 v2 歧义/多对一跳过。恢复原身份与单资产日统计同事务，eventstats 全量重建持写锁后读真相，避免覆盖新增量。缩略图 URL/磁盘/ETag/Android 稳定键与预取策略指纹贯通。守恒、并发及恢复失败重试在临时数据库验证；旧 v1 聚合与既有重复数据不擅自清洗。必须同步升级服务端与客户端，勿用旧客户端转存 v2。Go 全量测试/vet/lint、三端 SDK 生成及 Web 构建通过；Android App 编译与本轮 32 项定向单测通过，core:data 全套仍有 Windows DataStore 文件替换失败（包括未改动的暂存仓测试），未作实机/NAS 验收。详情见 build/audit/backend-2026-10-19/需求理解与第二轮审查.md 收口记录。
12. **迁移能力与验证缺口（非上述已确认 bug）**：直传/分片协议未传源 mtime，上传后默认文件日期可能变为落盘时间；需明确原文件时间与入库时间并保留可得的源时间。数据库快照不含原件，且默认与 DataDir 同盘，释放手机原件前需有完整恢复清单和独立副本。Android 排队上传在切换本机/PC/NAS时的目标归属需专项验收，目前只定位到任务无服务器身份快照、执行时读取当前地址，尚未作实机结论。能力状态见 CAPABILITY_MAP「可靠性复查」。


## 6. 线上/本地使用入口

| 形态 | 入口 |
|---|---|
| PC 日常服务端 | 双击 `启动服务端.bat`（8420 端口，数据位于 `qimeng-data/`） |
| 手机连 NAS | `http://<PC局域网IP>:8420` |
| 手机单机模式（M6） | `127.0.0.1:18430`（App 内嵌服务端或 Termux） |
| 桌面壳 | `%APPDATA%\media.qimeng.desktop\server.json` |
| 本机同步（ADR-0030） | 把文件放进仓库根 `local-sync/<库名>/` 自动入库；根级 `.txt` 自动导入后归档至 `.synced/` |

## 7. 用户约定（长期有效）

1. 日常本地开发可用 dev 免密；**生产与真机环境禁止** `auth_dev_mode`。2026-10-17 审计风险接受：日常启动链保留 dev 免密 + 全接口监听（用户明确本人使用），代价是**同 LAN 不可信设备（访客 WiFi）可免密取得 admin token**——仅限受信家庭内网使用，接客网/远程必须关闭 dev 模式或改回环监听。
2. 前端统一由后端 8420 端口托管 `web/dist`，修改前端需先执行 `npm --prefix web run build`。
3. **Android 构建部署必须显式指定 AVD（qimeng_api35）或真机 USB 目标**——严禁碰 `emulator-5554`（雷电游戏模拟器）。
4. 8420 真库严禁写入测试脏数据；测试验证使用隔离实例或虚构数据。
5. 明文密钥与真机设备标识严禁入库。

## 8. 文档地图（现行）

| 文档 | 职责 |
|---|---|
| `AGENTS.md` / `AI_README_FIRST.md` | AI 协作准则与红线铁律 |
| `PROJECT_PLAN.md` | 里程碑与进度唯一真相 |
| `CHANGELOG.md` 与 `docs/history/` | 历史变更、提交记录与问题复盘（只增不删改） |
| `ARCHITECTURE.md` | 系统架构设计与模块分层边界 |
| `DOMAIN_RULES.md` | 领域业务规则与推荐/统计算法权威公式 |
| `SECURITY.md` | 安全基线、鉴权与文件操作红线 |
| `GUIDE_API.md` | API 协议导读与端点清单 |
| `CAPABILITY_MAP.md` | 对标成熟媒体库的能力地图与缺口全景 |
| `docs/adr/` | 架构决策记录（ADR） |
