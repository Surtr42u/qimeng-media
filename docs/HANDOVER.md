# HANDOVER - 交接说明

> 写给下一位接手的 AI。人类用户无编程基础，代码由 AI 生成。**最后更新：2026-10-02**：Web 全新设计语言「绮梦流光 · Aurora Glass」落地（ADR-0031：暗色优先液态玻璃 + token 体系 v2，类名契约不动样式层整体重写，见 §4 Web）；前笔 2026-10-01 备份导出/导入并入 TXT 作者片段（ADR 系列 0028/0029/0030 已落地）。历史批次见 `docs/CHANGELOG.md`。

## 1. 项目一句话

媒体全存 NAS 的多端媒体库：Go 单体服务端 + React Web/PWA + Kotlin/Compose Android + Tauri 桌面壳。协议先行（`api/openapi.yaml` 是三端唯一事实源）。

## 2. 接手第一步

1. `AI_README_FIRST.md`（协作强制规则）
2. 本文件（现状与待办）
3. `docs/PROJECT_PLAN.md`（里程碑勾选 = 进度唯一真相）；按任务查 `AGENTS.md` 路由表
4. 需要历史细节时再翻 `docs/CHANGELOG.md`（更早见 `docs/history/CHANGELOG-ARCHIVE.md`；勿把历史笔当现状）

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

**Web**：
- **设计语言「绮梦流光 · Aurora Glass」（ADR-0031，2026-10-02）**：暗色优先（无手动选择默认深色，不再跟随系统）液态玻璃——画布极光渐变 + backdrop-filter 材质 + 发丝描边顶缘高光；token 体系 v2（`web/src/tokens.css` 唯一色彩事实源，shadcn 变量反向桥接，`@immich/ui` 主题包退役）；样式层 `styles/glass.css` 按类名契约全量重写（组件 DOM/逻辑零改动）；backdrop-filter 不支持时 `@supports` 退化为不透明面板；明暗切换走 `document.startViewTransition` 交叉溶解（不支持的浏览器直切）；动效零 JS 库（CSS/原生 API）。
- 上传工作台：选完文件即传 + 超限入队前拦截 + 201 后自动挂靠；批次库/作者/来源默认；通用来源词表与作者总表镜像两卡在本页（设置页留指引）。
- 上传队列单例化（`lib/upload-queue-store.ts`）：切路由/组件卸载不取消整队，abort 仅显式「全部取消」（401 逐条失败既有语义不变）；页面刷新仍丢（IndexedDB 持久化待立项）。
- 断点续传分片（ADR-0028，`lib/upload-chunked.ts`）：≥16MB 走 8MB 分片会话流——GET 探测权威 offset 续传、409 重同步、404 重建〔≤2 次〕、网络/5xx 指数退避〔≤3 次，超限终态失败带文案〕；<16MB 直传不变。
- 分片取消 abort 在途请求+best-effort DELETE 会话；create 透传 dir 拖拽目录同享续传；complete 后与直传同一后处理。
- 上传传输层 XHR 直连不走生成 SDK：生成 client 无 abort 支持，全局 403 拦截器会误伤 UPLOAD_DISABLED 业务响应（记档见该文件头）。
- 跨端收藏/点赞秒级感知（ADR-0029）：SseBridge 消费 favorite.changed/like.changed——favorite 失效 ASSETS 根键、like 失效 ASSETS+RANKINGS+RECOMMENDATIONS 三根；SSE 重连成功按 library.changed 同一失效映射失效根查询，补偿断线窗口。
- 图片查看器、ArtPlayer、批次导航、confirm 弹窗、回收站、备份导入导出、库文件快照、库管理/作者 TXT 导入；维护页客户端日志卡默认折叠+指标网格+「本机同步」卡（ADR-0030）。

**Android**：
- 浏览/播放/上传/离线队列/缓存/备份/数据管理/本机模式全功能；后台冻结自愈（回前台探测 /healthz 无响应自动重拉 + 残留子进程 pid 回收）。
- 上传=唯一「系统文件」SAF 入口 + 系统分享共用 submitUris 单管道，选完即传；批次默认（库/作者/来源）持久化、入队快照继承；归档文件夹一键上传（扫描归档根按「文件夹名=库名」自动匹配〔sanitize 与 ADR-0030 同规范〕、递归子目录映射、条目级库目标入队、alreadyArchived 跳过、防重门禁+超限拦截）。
- 断点续传接入（ADR-0028）：≥16MB 分片会话流，WorkManager 重试先 GET 探测权威 offset、409 重同步、404 自动重建、取消 best-effort DELETE；complete 后挂靠/归档管线零改动复用；<16MB 直传。
- 预取 revision 整轮跳过（ADR-0026）：轮首比对 GET /library/revision，未变置 Skipped；记录捆绑服务器标识防撞号，SKIP 前随机样本 50 条本地探测、缺失≥20% 降级全量，清空任一缓存池强制下轮补齐；缩略图预取磁盘探测短路+分池按当前连接 serverUrl 路由（命中免请求免解码，轮终 logcat QimengCache 记档）。
- SSE 消费（ADR-0029）：okhttp-sse 长连 /api/v1/events，登录即连/断线指数退避（3s→30s 封顶）/登出即断/后台不断开；事件汇入 DataFreshnessSignal，收藏/点赞返回刷新跳过门收敛为「指纹+SSE 信号+TTL」三层，5min TTL 为断线/离线兜底。

**服务端**：
- 扫描（含库根自动重挂 ADR-0025：库根改名/移动后按资产 rel_path+字节指纹唯一命中自动改挂并广播 library.changed）、缩略图、推荐/统计、回收站、热备快照、迁移导入导出、SSE。
- 库内容修订号（ADR-0026）：GET /library/revision 全局单计数器持久化，资产集合变更单调 +1；物删/清空/清扫/导入四条不发事件路径显式 bump。
- 签名直链 exp 窗口对齐（ADR-0027）：exp=下一窗口上界+TTL，同窗 URL 逐字节恒定（HTTP 缓存/ETag 可命中）；/media/orig 补缓存头；有效期恒 (6h,12h]；吊销=窗口级+media-secret 轮换总闸；URL 格式未变三端零适配。
- 断点续传上传（ADR-0028）：POST /uploads 五操作（创建/探测/追加分片/complete/放弃）tus 极简子集；会话服务端持有（uploadsess 包，无活动 24h 过期、重启即失效）；409 回权威 offset；单片 32MB；complete 复跑直传四道终检与同一 ingest 管线；同名自动重命名永不 409；直传保留，双端按 size 阈值分流。
- SSE 补 favorite.changed/like.changed（ADR-0029）：载荷 `{"assetId"}`，失败零发布、幂等 no-op 照发，**不 bump 修订号**；运行时事件面不入 openapi。
- 扫描变更失效缩略图（重探测 size/mtime 变化重入库即删全部档位缩略图，全量与 watch 增量两路径同联动）+ EXIF 方向显式处理（手写解析 JPEG Orientation tag〔零新依赖〕+ ffmpeg transpose 映射表缩放前应用，恒带 -noautorotate；缓存键 v2→v3）+ 跨卷移动失败当场清理半截文件（回收站三调用点兜底，疑似完整宁留勿删）。
- 三项硬化：备份快照 VACUUM INTO 写完即 quick_check 校验（坏快照删除并报错）；compose 容器日志 json-file 10MB×3 轮转；分片上传 Seal 后落位前 size 复核。
- 本机文件夹自动同步通道（ADR-0030）：QIMENG_LOCAL_SYNC_ROOT 轮询监测，子文件夹名=库名自动匹配入库（四道校验+直传同款管线），成功移走/失败保留重试；根级 TXT 恒 keep 导入后移 .synced；/local-sync/status+trigger 两端点；根重叠/隐藏/符号链接安全规则。
- 备份导出/导入含 TXT 作者片段（keep 合并，2026-10-01）：txtFragments 段导出逐字全量，导入无同名导入/相同跳过/不同并回上传条目替换，旧备份兼容；**App 端须升级 APK 才携带该段（App 备份经 SDK 模型往返，旧版静默丢段；Web 裸字节透传无碍）**。

**资产编辑与作者体系（ADR-0023/0024，跨端+服务端）**：
- 上传回归纯上传（不携带作者/来源参数）；挂靠入口=资产编辑页（Web/Android 均有，桌面壳零改动覆盖）；编辑直接写进 TXT 片段本体（唯一真相、统一重建不丢不重、同事务失败安全）。
- 通用来源词表 /authors/source-vocabulary：来源建议唯一数据源，服务端手动维护（Web 维护卡）；出厂自动预填（≥2 作者共用平台名，排除个人地址/链接形态）；来源=获取渠道，仅记录永不参与匹配。
- 作者联想 /authors/suggest；作者空输入显示全部常规作者（GET /authors 过滤 COS）；片段导出下载 /authors/import-txt/export；本地总表镜像 /authors/mirror（显式配置唯一例外写点、单向尽力而为）；同文件名重导入缺省 409 二选一（keep 并回/remove 明示）。
- Web 拖拽目录递归上传（相对路径映射库内子目录）；作品名序号联想 GET /assets/name-suggestions（协议保留暂无调用方）；上传归档文件夹（ADR-0024 修订3）：源文件移 `<归档根>/<库名>/`（sanitize、同名异容加序号绝不覆盖；相册来源永不移动；未设归档根仅收件箱维持 uploaded/）；「上传收件箱与归档」页=归档单目标页（入口在数据管理页）。
- 列表加载与刷新：触底哨兵加载结束重评估、推荐流 fresh==0 换 seed 续拉≤3 轮、翻页失败 1s/3s 退避重试、下拉刷新代际作废旧响应+预取避让（15s 自愈）；Web 端机制不同经核无同类问题。
- 已退役（防再提案）：暂存区/收件箱导入/相册选择器/浏览文件入口已随 2026-09-29 直传化退役，上传挂靠参数已随 ADR-0024 退役；现行=选完即传+批次默认快照+成功后自动挂靠（挂靠失败由资产编辑页补挂）。

## 5. 当前待办（只列真缺口）

**待办（按优先级，触发条件型）**——已落地注记：2026-10-01 批（CHANGELOG 四百一十三~四百二十三笔，CI 历史首次全绿）与本机同步通道（ADR-0030，口述立项五条需求逐条兑现，验收对照见 ADR-0030 背景节）能力均已入 §4：

1. **NAS 服务端重新构建部署（用户节点）**：2026-10-01 服务端新功能（断点续传端点/SSE 事件/URL 窗口对齐/EXIF/quick_check/日志轮转）需重部署才生效；连旧服务端时 App 仅一处不降级——≥16MB 上传走分片通道会失败（旧端无 /uploads）。手机 App 与内嵌服务端已是新版（2026-10-01 真机装机，NX721J）。
2. ~~Web 上传队列刷新持久化（IndexedDB）~~**已落地（2026-10-03，第四百三十八笔）**：未终态条目（排队/在传）持久化 IndexedDB（原生 API 零新依赖，`qimeng_upload_queue` 库），刷新后队列页恢复为「需重新选择文件」态（File 句柄不跨页面存活=平台限制，不伪造续传假象）；重选同名同大小文件后——分片条目按持久化会话 id 走既有探测→PATCH 续传（服务端 offset 唯一真相源，会话已失效自动重建从 0），直传条目从头重传；终态/移除/清空完成同步删记录不留孤儿。已知限制（对抗审查记档）：同名同大小异内容无法识别（无内容指纹，分片续传可能拼接异源字节，属选错文件既有风险面）；多标签页口径同既有单例队列（他页在传条目会在本页恢复为待重选）；IndexedDB 未来升版本时旧标签页需关闭/刷新后升级才能进行。
3. 会话远程吊销端点 + 401 专用错误码（SECURITY 规划项）——触发：设备退役或远程隧道暴露常态化。
4. 缩略图两管道分派（列表首帧快显/代表帧预生成）与孤儿全量对账（DOMAIN_RULES §11 规划项）——触发：十万级库或新批次入库首访明显变慢。
5. Android SSE 静默黑洞 idle 看门狗（无 TCP 错误的网络切换检测）——触发：实际观察到断网后长时间不自愈；当前 5min TTL 兜底。
6. **GBK/GB18030 TXT 支持**（本机同步通道现仅 UTF-8〔剥 BOM〕，解码需新依赖 x/text 再决策）——触发：用户实际遇到非 UTF-8 TXT。
7. **本机同步 fsnotify 秒级响应 / sysmon 指标化 / importTxt 编排 ADR-0019 下沉随迁（runner 随迁 + `.synced` 跨包双常量收敛）**——触发：30s 轮询延迟或指标需求实际出现。

**记档级（不立项，防重复怀疑）**：uploadsess 无独立单测（httpapi 集成已覆盖）；ListThumbnailWarmup 每轮 O(N) 全表空扫（十万级前无感）；HEIC 内嵌 EXIF 按方向 1 处理；Web 事件账本（IndexedDB）无行数上限；待扫标记可能指向已删库永不移除；
activePool 冷启动装配竞态（读 miss 重下，无害）；桌面壳无自动更新器（单用户自用，接受）；字幕域无地图条目；/media/orig 的 If-Modified-Since 用库行 mtime（ADR-0004 已知面）。
**browse.sql/facets.sql 手抄同型面（2026-10-02 清点记档）**：sqlc v1.31.1 SQLite 解析器限制（browse.sql 文件头 4 条规则：宏裸形态/参数禁作 WHEN 主语/WHERE 禁引 CTE 别名/ORDER BY 禁宏）迫使同型谓词手抄——① browse.sql 的 ListAssetsFilteredDesc/Asc/CountAssetsFiltered 三联体（同 WHERE 收敛家族三份手抄，排序方向烘焙成两个变体）；② facets.sql 六聚合查询（Partition/Source/Author/Character/CosWork/MediaType）各持一份「排除自身维度+应用其余维度」谓词家族，favorite/history 子集 EXISTS 探测在文件内出现 12 处。退役条件：sqlc 升级修复解析限制或换查询形态（history.sql/recommend.sql 的 CTE 预聚合先例，见第四百三十三笔），届时按同款主流化改写收敛；改前必跑 EXPLAIN 计划锁（ADR-0011 修订第 5/6 条），子集探测已由 history_plan_test.go 锁定双等值前缀。

**CI 教训**：`make sdk` 已加生成前清理 + Kotlin 生成器锁版本，锁校验失败 CI 会打印 diff；golangci-lint 不含 gofmt，新 Go 文件须 `gofmt -w`；跨模块签名变更须 grep 全部构造点（CI 首跑抓过 `feature:detail` 测试漏适配）。

**用户节点（非 AI）**：① 真机 NAS buildx arm64+压测已随 2026-09-22 收官（arm64 移 M7+、压测不设专项）；② 本机硬件不稳（WHEA 断电，2026-09-16 记档）——构建/压测前建议排 BIOS/电源；③ 建议换管理密码（真实密码曾明文入历史文档，唯一止血=轮换；见 `docs/history/REVIEW-20260922.md` §2.1）。

**建议立项（按优先级）**：① 安全默认值——日常/生产关闭 `QIMENG_AUTH_DEV_MODE`、换管理密码并勿写入文档（内嵌共享密钥校验已实现=dev-login 门禁三端，见 SECURITY「开发模式」节）；② 磁盘生命周期已实现（trash.retention_days/sweep_interval+到期清扫+缩略图四入口联动），全量对账（扫描器外部删除产生的孤儿）仍为规划项。
③ 工程卫生已完成（bat 共用逻辑收敛根 `_server-common.cmd`；生产样例唯一权威 = `deploy/docker-compose.yml`）；④ ~~能力窄缺口——备份调度参数编辑 UI、Web 缓存档位选择 UI~~**已落地（2026-10-03，第四百四十笔）**：备份调度三键走 PUT /api/v1/backups/schedule 热生效+持久化（kv_settings 键 backup_schedule，优先级 kv > env > yaml > 默认；interval 变更重置周期、enabled=false 只停定时面），维护页快照卡直接编辑；Web 缩略图档位偏好（auto/sm/lg）=设置页选择项，localStorage 持久化，接到既有 size 查询参数单点（size 不参与签名、是缓存键组成部分，切换无混存，零协议改动）。

**明确不做（禁止再提案）**：见 `docs/CAPABILITY_MAP.md`「明确不做」：公网裸端口、实时转码、AI 识别、iOS、跨端 UI 框架、多租户 SaaS、协议缺口 #21/#29~#34。

**已知问题（用户拍板暂不修）**：

- sourcematcher 别名 `"SC"` 使 `Screenshot_*.jpg` 误归「星际争霸」——真机部署后若误伤明显再议（候选：删该别名 + 连动测试/`DOMAIN_RULES §4`）
- `:core:data` `DataStoreStagingRepositoryTest` 8 用例 Windows 偶发失败（DataStore `.tmp` 改名竞态，环境性，经基线比对证实为既有失败）——需修测试写法或统一 CI 跑 Linux

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
| `CHANGELOG.md` 与 `docs/history/` | 历史变更（只增不改写正文）+ 历史档/审查报告归档 |
| `ARCHITECTURE.md` / `DOMAIN_RULES.md` / `SECURITY.md` / `OBSERVABILITY.md` / `GUIDE_API.md` | 架构/领域/安全/监控/协议导读 |
| `CAPABILITY_MAP.md` / `LEGACY_REQUIREMENTS.md` | 能力三态与明确不做；旧版沉淀需求清单（openapi/DOMAIN_RULES 引用） |
| `docs/adr/` | 架构决策 |

> 已删除勿再寻找：`HANDOVER_UI.md`、`HANDOVER_APP.md`、`REPLICATION_GAPS.md`、`AUDIT-20260920.md`、`任务书-*.md`、`P2-*`、仓库外 `_archive-20260917/` 与《任务*.md》《待拍板-*.md》。历史可溯 git 与 CHANGELOG。
