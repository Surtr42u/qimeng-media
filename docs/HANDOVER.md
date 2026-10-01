# HANDOVER - 交接说明

> 写给下一位接手的 AI。人类用户无编程基础，代码由 AI 生成。
> **最后更新：2026-10-01**（**本机文件夹自动同步通道落地**（ADR-0030，第四百二十四笔）：服务端轮询监测同步根、库名文件夹自动匹配入库、TXT keep 导入、失败保留可见、Web 维护页卡、三配置键，详见 §4 服务端行与 §5 已落地段。§4 Web 行补**断点续传分片接入**（ADR-0028，第四百一十七笔同批）：≥16MB 走 8MB 分片会话流——每轮先 GET 探测权威 offset 续传、409 响应体重同步、404 重建从 0〔上限 2 次〕、网络/5xx 指数退避重试〔上限 3 次〕、complete 后与直传同一后处理；<16MB 直传不变，详见 §4 Web 行。同日早前（第四百一十七笔协议/服务端/Android 批）：§4 服务端行补**断点续传上传**——POST/GET/PATCH/DELETE /uploads 五操作 tus 极简子集服务端已落，直传通道保留、双端按 size 阈值分流，Android 已接入（同批补齐协议可选 dir：与直传逐字同语义、create 存会话、complete 落位子目录，Android 分流随之回归纯 size 判定）——Web 接入完成后**双端均已接入**）。**2026-09-30**（§4 双端上传口径改「直传化」——暂存区整体退役选完即传+收件箱入口退役+维护页观感修复，第四百零五笔；同日任务书《安全与UI升级总纲》入库）。**2026-09-28**（§4 追加第三百九十七笔：Web 上传工作台对齐 App+词表/镜像卡迁移+App 配置区固化+服务端库根自动重挂 ADR-0025；同日早前第三百九十六笔：上传归档文件夹/浏览文件入口/作者默认全显/列表加载与刷新修复，详见 ADR-0024 修订3。此前 2026-09-25 §4 上传挂靠能力段改写为「资产编辑页与作者挂靠编辑」口径——ADR-0024 上传拆分：上传挂靠参数退役、挂靠入口迁资产编辑页、来源建议改通用来源词表）。**2026-09-22**（文档严格对齐：删除过时交接/任务书，本文件重写为现状-only。历史批次一律见 `docs/CHANGELOG.md`。）

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

- **Web**：上传工作台（`LibraryUploadPage`/`UploadWorkbench`——批次库/目录+批次作者/来源默认+选完文件即传（2026-09-29 直传化：暂存列表/逐项编辑/开始上传按钮退役）+超限入队前拦截+201 后自动挂靠，交互对齐 App；`UploadCard` 已退役）+ 通用来源词表与作者总表镜像两卡自设置页迁入本页（设置页留指引）、图片查看器、批次导航、ArtPlayer、confirm 弹窗、回收站（`TrashPage`）、备份导入导出（`BackupCard`）、库文件快照（`DbBackupCard`）、库管理/作者 TXT 导入（2026-09-28 第三百九十七笔）；维护页客户端日志卡默认折叠+指标网格对齐（2026-09-30 第四百零五笔）；上传队列模块级单例化（`lib/upload-queue-store.ts`，`use-upload.ts` 改 useSyncExternalStore 订阅层）——切路由/组件卸载不再取消整队、队列随页面会话存活（abort 仅用户显式「全部取消」；401 逐条失败既有语义不变；页面刷新仍丢，刷新存活/断点续传待 IndexedDB+tus 分片另立项），SSE 重连成功以 library.changed 同一失效映射失效根查询补偿断线窗口（2026-10-01 第四百一十六笔）；**断点续传分片接入**（2026-10-01 ADR-0028 同批：≥16MB 走 8MB 分片会话流 `lib/upload-chunked.ts`——每轮先 GET 探测服务端权威 offset 再续传、PATCH 409 用响应体权威 offset 立即重同步、会话 404 重建从 0 续传〔单条上限 2 次〕、网络/5xx/超时指数退避重试〔上限 3 次，超限终态失败带文案不重新入队〕、complete 后与直传完全同一入库失效+挂靠后处理、取消 abort 在途请求+best-effort DELETE 会话，create 透传入队快照 dir 拖拽目录同享续传；<16MB 直传通道一字未动，组件层零改动；传输层 XHR 直连不走生成 SDK——生成 client 无 abort 支持且全局 403 拦截器会误伤 UPLOAD_DISABLED 业务响应，选型记档见该文件头）；跨端收藏/点赞改动秒级感知接入 SSE（2026-10-01 ADR-0029 端到端：SseBridge 消费 favorite.changed/like.changed——favorite 失效 ASSETS 根键〔收藏筛选流/详情与列表收藏态〕、like 失效 ASSETS+RANKINGS+RECOMMENDATIONS 三根〔点赞字段面/点赞筛选流/热度含赞的排行/likeScore 推荐序〕，载荷不消费整面失效、不碰 library.changed 库内容根键，重连补偿同覆盖）；维护页『本机同步』卡（ADR-0030：通道状态/失败与忽略可见/立即触发）
- **Android**：浏览/播放/上传（2026-09-29 直传化：唯一「系统文件」SAF 入口+系统分享共用 submitUris 单管道，选完即传；批次默认（库/作者/来源）持久化入队快照继承；暂存区/逐项编辑/相册选择器/浏览文件/收件箱导入入口均已退役）/离线队列/缓存/备份/数据管理/本机模式（含后台冻结自愈：回前台探测 `/healthz` 无响应自动重拉 + 残留子进程 pid 回收，2026-09-25 第三百九十笔）；缩略图预取磁盘探测短路+分池路由来源化（2026-09-30 第四百一十一笔：预取单条先按稳定键直查磁盘缓存，命中免请求免解码只计进度——中断重跑/缓存齐全轮次「全库重扫」退化为「只补缺口」，轮终 logcat QimengCache 记档命中/下载汇总；剥 host 稳定键改按当前连接来源路由（CachePoolBinder 观察 serverUrl），修复本地池被「非 http 键兜底 NAS」口径架空）+ **revision 整轮跳过**（2026-09-30 ADR-0026：轮首比对 GET /library/revision 与 DataStore last_prefetch_revision，未变整轮跳过置 Skipped「缓存已是最新」，变了才全量拉列表；失败/旧服务端全降级全量；清空任一缓存池失效本地记录强制下轮补齐；刻意不做 since 增量清单/墓碑——规模到十万级或出现多端同步需求再立项；同日修订：记录捆绑服务器标识——换服务器必走全量，防两端独立修订号计数器撞号错误 SKIP + SKIP 前按记录端随机样本 50 条逐条本地磁盘探测，缺失≥20% 降级全量补拉——兜 LRU 驱逐/系统清缓存/备份恢复回滚三类「记录与实际脱节」）；**收藏/点赞返回刷新跳过门 TTL 化**（2026-10-01 第四百一十五笔：ON_RESUME 跳过条件由「进程内指纹未变」扩为「指纹未变 且 距上次成功列表拉取 < STALE_AFTER_MS 5 分钟」，TanStack Query staleTime 同款语义——SSE 无 like/favorite 事件、跨端（Web/另一设备）改动指纹永不变化的历史陈旧自此有界：至多 5 分钟 + 一次返回/回前台自愈；TTL 内仍跳过、本端刚改过立即刷新不变）；**断点续传上传接入**（2026-10-01 ADR-0028 同批：≥16MB 走 8MB 分片会话流——每轮 WorkManager 重试先 GET 探测服务端权威 offset 再续传〔会话 id 进程内记忆表，本地不存 offset〕、PATCH 409 用响应体权威 offset 立即重同步、会话 404 自动重建从 0 续传、用户取消 DELETE 会话 best-effort、complete 后挂靠/归档管线零改动复用；<16MB 维持直传不变。dir 同批补齐入分片协议〔与直传逐字同语义、create 透传〕后分流回归纯 size 判定——初版「≥16MB 且目标为库根、子目录恒直传」的临时限制随之撤销，子目录大文件同享续传）；**SSE 数据新鲜度消费接入**（2026-10-01 ADR-0029 端到端：okhttp-sse 5.4.0 长连 `GET /api/v1/events`——ServerEventConsumer 登录即连/断线指数退避重连〔3s 起 30s 封顶、连接成功重置〕/登出即断/后台不断开，鉴权经派生客户端共享 AuthInterceptor 零第二份 token 逻辑；favorite.changed/like.changed/library.changed 汇入 DataFreshnessSignal 三计数信号汇〔onOpen 重连 bump 对齐 Web 重连校验语义〕，收藏/点赞 ON_RESUME 跳过门收敛为「指纹 + SSE 信号 + TTL」三层——收藏门关联 favorite+library 两计数、首页门关联 like+library 两计数，5 分钟 TTL 降级为断线/离线窗口兜底）
- **服务端**：扫描（含**库根自动重挂** ADR-0025：磁盘上库根目录改名/移动后，按库内最小 5 条资产 rel_path+字节指纹在旧父目录唯一命中即自动改挂 root_path 与显示名并广播 library.changed，最迟下一轮 5 分钟轮询自愈）、缩略图、推荐/统计、回收站、热备快照、迁移导入导出、SSE、**库内容修订号**（2026-09-30 ADR-0026：GET /library/revision 全局单计数器 kv_settings 持久化，资产集合变更单调 +1——library.changed 订阅一处收口 + 物删/清空/清扫/导入四条不发事件路径显式 bump，客户端预取跳过依据）、**签名直链 exp 窗口对齐**（2026-10-01 ADR-0027：exp=下一窗口上界+TTL〔窗长=token_ttl 缺省 6h，一个旋钮〕，同一窗口内所有响应签出逐字节相同的 URL——浏览器 HTTP 缓存与 ETag/304 得以命中，根治逐响应轮换击穿全部 HTTP 缓存；/media/orig 补 `private, max-age=窗口长` 缓存头；有效期恒 (6h, 12h]，吊销粒度=窗口级+media-secret 密钥轮换总闸；URL 格式与参数未变、三端零适配，Android 剥签名键栈保留仍最优）、**断点续传上传**（2026-10-01 ADR-0028：POST /uploads、GET/PATCH/DELETE /uploads/{id}、POST /uploads/{id}/complete 五操作 tus 极简子集——会话服务端持有〔uploadsess 新包：内存注册表 + DataDir/uploads-tmp 分片文件，无活动 24h 过期清扫，服务端重启即失效客户端重建〕、断点以服务端权威 offset 为准〔不符 409 回权威值〕、单片上限 32MB、complete 对拼装整文件复跑直传四道终检与同一 ingest 管线〔library.changed/修订号/upload.done/富化照旧〕、同名自动重命名永不 409；直传通道保留，双端按 size 阈值分流——双端已接入（dir 同批补齐入协议，双端分流均回归纯 size 判定；Web 接入见本节 Web 行））、**扫描变更失效缩略图 + 跨卷移动失败清理半截文件**（2026-10-01 第四百一十八笔：重探测检出 size/mtime 变化重入库即删该资产全部档位缩略图缓存〔缓存键不含内容信号，外部原地换文件后海报帧按新内容重建，全量与 watch 增量两条路径同联动〕；MoveFile 跨卷复制回落失败当场清目标端半截副本、回收站入站/恢复/回滚三调用点兜底清理〔只删严格小于源的确定残缺件，疑似完整宁留勿删〕——无 meta 幽灵残缺件与"残缺件注册成坏资产"自此根治）、**SSE 补 favorite.changed / like.changed**（2026-10-01 ADR-0029：PUT /assets/{id}/favorite、PUT /assets/{id}/like 写成功后各广播一条轻事件，载荷同构 `{"assetId"}`，失败零发布、幂等 no-op 照发；**不 bump 修订号**〔ADR-0026 语义边界不变〕，运行时事件面不入 openapi——跨端收藏/点赞从"至多 5min TTL 陈旧"收敛到秒级感知，双端消费同日落地〔Android okhttp-sse 消费+门收敛、Web SseBridge 失效映射，见本节 Android/Web 行〕）、**三项硬化**（2026-10-01 第四百一十九笔：备份快照 VACUUM INTO 写完即强制 quick_check 校验〔只读打开防空库误判，非 ok 删损坏文件并报错、不留坏快照静默留存〕+ compose 容器日志 json-file 10MB×3 轮转 + 分片上传 Seal 后落位前 size 复核〔rename 间隙防篡改，不符 400 拒绝且损坏 staging 即清〕）、**缩略图 EXIF 方向显式处理**（2026-10-01 第四百二十一笔：静图缩略图显式按 EXIF Orientation 旋转——服务端手写解析 JPEG APP1 Orientation tag〔thumbnail/orientation.go 零新依赖，解析失败/非 JPEG 回落 1 不转〕、按实测锁定的映射表换 ffmpeg transpose/hflip/vflip 于缩放前应用、scaleStill 恒带 `-noautorotate` 关闭新版 ffmpeg 解码期隐式 EXIF 转向〔该隐式行为随版本漂移：9.0.1 实测默认已转、旧版不转，不关则显式滤镜叠隐式双重变换〕；缓存键策略版本段 v2→v3 全量失效重建，DOMAIN_RULES §11 同步；8 档映射经真实 ffmpeg 四象限像素级集成测试实测锁定）、**本机文件夹自动同步通道**（2026-10-01 ADR-0030：QIMENG_LOCAL_SYNC_ROOT 启用的轮询监测——子文件夹名=库名（sanitize 对齐 App 归档）自动匹配、四道校验+直传同款入库管线、成功 move 入库/失败原地保留每轮重试、根级 TXT 走 importTxt 恒 keep 后移 .synced、GET /local-sync/status+POST trigger 可见性与手动触发、根重叠/隐藏/符号链接安全规则）
- **资产编辑页与作者挂靠编辑（2026-09-25，ADR-0023/0024）**：上传不再携带作者/来源参数（ADR-0023 上传挂靠入口退役，上传回归纯上传）；资产编辑页维护作者关联与每作者来源区——写进 TXT 片段本体（唯一真相、统一重建不丢不重、同事务失败安全），通用来源词表 /authors/source-vocabulary 服务端手动维护（来源建议唯一数据源，Web 设置页维护卡；来源=获取渠道非作品出处）；同文件名重导入缺省 409 二选一（keep 并回/remove 明示）；作者联想 /authors/suggest + 片段导出下载 /authors/import-txt/export + 本地总表镜像 /authors/mirror（用户显式配置唯一例外写点、单向尽力而为）不变。Web 新增拖拽目录递归上传（相对路径映射库内子目录），Android 上传改内置相册式选择器（MediaStore 缩略图网格多选，替代系统 SAF）；Web/Android 均新增资产编辑页；桌面壳零改动自动覆盖；协议路径 63→65。**上传流程（第三百九十三/九十四/九十五笔修订）**：先选文件 → 暂存区配置目标库（必选门禁）+批次作者/来源+逐项作品名 → 上传；单文件 201 后客户端自动挂靠（PUT authors 单项 + PUT sources mode=append 不覆盖既有来源），挂靠失败不重试上传、条目落「已入库待挂靠」由编辑页补挂。**暂存区持久化**（第三百九十五笔）：暂存条目与批次配置落 DataStore 本地持久存储（跨进程/跨天保留）；**下载收件箱**——设置页 App 内目录浏览器指定真实文件夹（含点前缀隐藏目录，MANAGE_EXTERNAL_STORAGE + File API），文件夹内媒体文件自动进暂存区（缩略图卡片），上传成功源文件移入其 `uploaded/` 子目录归档；**作品名序号联想**（GET /assets/name-suggestions：少空格吸附、吸附库内既有命名风格+下一序号、扩展名锁定）；通用词表出厂自动预填（≥2 作者共用平台名，个人地址/链接形态排除）。**上传归档文件夹与浏览文件入口（2026-09-28 第三百九十六笔，ADR-0024 修订3，App 端/协议零改动）**：① 用户指定归档根后，上传成功的路径来源（收件箱+浏览文件同权）源文件移到 `<归档根>/<库名>/<原文件名>`（库名 Windows 保留字符 sanitize；同名异容加序号绝不覆盖；renameTo 优先 copy+delete 兜底且 copy 走 .part 临时名；相册来源永不移动；未设归档根仅收件箱来源维持 uploaded/ 旧口径）；② 上传页第三入口「浏览文件」——目录/文件浏览器含点前缀隐藏目录多选入暂存（浏览器组件上提 core:ui `DirectoryBrowserCard` 单源）；③ 作者选择空输入即显示全部常规作者（GET /authors 过滤 COS，/authors/suggest 协议不动）；④ 「上传收件箱与归档」设置页（收件箱+归档双卡）入口从设置页迁数据管理页。**列表加载与刷新修复（同批）**：触底哨兵 reloadTick 第三 key（加载结束重评估防钉底哑火）、推荐流 fresh==0 换 seed 续拉上限 3 轮、翻页失败 1s/3s 退避自动重试、下拉刷新立即受理+代际作废旧响应+数据落地即收圈+刷新窗口缩略图预取避让（15s 自愈）；Web 端经核无同类问题（IntersectionObserver 机制无哨兵缺陷、上传页无作者/来源控件）不同步改。**上传直传化（2026-09-29 用户拍板，2026-09-30 第四百零五笔）**：暂存区整体退役，选完文件即传——App 删 StagedUpload 模型/StagedItemEditor/UploadStagingIngestor（系统文件 SAF+系统分享共用 submitUris：describe 元数据→未选库提示→逐项判超限拦截不出网→继承批次默认快照入队），Web 删 lib/staged-upload.ts/StagedUploadList（添加文件立即上传）；批次默认（库/作者/来源）双端持久化（`StagingBatchConfig`）入队时刻快照继承，之后改默认只影响下一批；「从收件箱导入」入口随暂存区退役，「上传收件箱与归档」设置页收窄为归档文件夹单目标页；GET /assets/name-suggestions 协议保留但业务侧暂无调用方；挂靠与归档口径不变。

## 5. 当前待办（只列真缺口）

### 2026-10-01 会话交接批（缩略图根治 → 断点续传/SSE/EXIF/硬化 → CI 首绿）

**本批已落地**（CHANGELOG 第四百一十三~四百二十三笔，ADR-0026 修订/0027/0028/0029，CI 历史首次全绿 run 36780261746）：预取 revision 记录捆绑服务器标识+SKIP 前抽样核对、签名直链 exp 窗口对齐、断点续传上传端到端（tus 子集+双端接入+dir）、收藏/点赞 SSE 事件端到端收敛（Android okhttp-sse 消费+门收敛，TTL 降级离线兜底）、Web 上传队列单例化+SSE 重连补偿、扫描变更失效缩略图+跨卷移动失败清理、备份 quick_check+Docker 日志轮转+上传 Seal 复核、缩略图 EXIF 方向显式处理（键 v2→v3）、CI 指纹锁根因修复（make sdk 清旧产物+生成器锁版本）。

**「本机文件夹自动同步通道」已落地（2026-10-01，ADR-0030，第四百二十四笔）**：用户 2026-10-01 口述立项（NAS 未就位过渡期消除手机/电脑双份手工搬运；原五条需求——指定文件夹为同步源自动入库〔图片/视频〕、成功后源文件移走、失败保留待手动处理、App 手动上传保留为备选安全通道、TXT 表同纳——已逐条兑现，验收对照见 ADR-0030 背景节）。形态=服务端侧轮询监测（`QIMENG_LOCAL_SYNC_ROOT` 启用，三配置键），能力详单见 §4 服务端行。

**其余待办与建议（按优先级，触发条件型）**：

1. **NAS 服务端重新构建部署（用户节点）**：本批服务端新功能（断点续传端点/SSE 事件/URL 窗口对齐/EXIF/quick_check/日志轮转）需重部署才生效；连旧服务端时 App 仅一处不降级——**≥16MB 上传走分片通道会失败**（旧端无 /uploads 端点）。手机 App 与内嵌服务端已是新版（2026-10-01 真机装机，真机）。
2. Web 上传队列刷新持久化（IndexedDB）——触发：大文件上传中页面刷新造成整条丢失实际发生一次。
3. 会话远程吊销端点 + 401 专用错误码（SECURITY 规划项）——触发：设备退役或远程隧道暴露常态化。
4. 缩略图两管道分派（列表首帧快显/代表帧预生成）与孤儿全量对账（DOMAIN_RULES §11 规划项）——触发：十万级库或新批次入库首访明显变慢。
5. Android SSE 静默黑洞 idle 看门狗（无 TCP 错误的网络切换检测）——触发：实际观察到断网后长时间不自愈；当前 5min TTL 兜底。
6. **GBK/GB18030 TXT 支持**（本机同步通道现仅 UTF-8〔剥 BOM〕，解码需新依赖 x/text 再决策）——触发：用户实际遇到非 UTF-8 TXT。
7. **本机同步 fsnotify 秒级响应 / sysmon 指标化 / importTxt 编排 ADR-0019 下沉随迁（runner 随迁 + `.synced` 跨包双常量收敛）**——触发：30s 轮询延迟或指标需求实际出现。

**记档级（不立项，防重复怀疑）**：uploadsess 包无独立单测（httpapi 集成测试已覆盖主面）；`ListThumbnailWarmup` 每轮 O(N) 全表空扫（十万级前无感）；HEIC 内嵌 EXIF 不解析按方向 1 处理；Web 事件账本（IndexedDB）无行数上限；待扫标记可能指向已删库永不移除；`activePool` 冷启动装配竞态（后果=读 miss 重下，无害）；桌面壳无自动更新器（单用户自用，接受）；字幕域在 CAPABILITY_MAP 无条目（若用户在意先入图三态再议）；`/media/orig` 的 If-Modified-Since 用库行 mtime 而非实时 Stat（ADR-0004 已知面）。

**CI 教训（CHANGELOG 422/423 已记）**：`make sdk` 已加生成前清理+Kotlin 生成器锁版本（2.41.0→generator 7.24.0）；锁校验失败 CI 会打印 diff；执行子代理本地验证不含 gofmt（golangci-lint 不含它），新 Go 文件须 `gofmt -w`；跨模块签名变更须 grep 全部构造点（不只改过的模块）——CI 首跑抓过 `feature:detail` 测试漏适配。

### 用户节点（非 AI）

1. ~~真机 NAS 就绪后：buildx 双架构 arm64 + 大库扫描压测（M5 收尾）~~ —— **已随 2026-09-22 真机 NAS 测试收官**（arm64 移 M7+ 按需储备、压测以日常使用覆盖）
2. 本机硬件不稳（WHEA 断电，2026-09-16 记档）：构建/压测前建议排 BIOS/电源
3. 建议换管理密码（真实密码曾明文入历史文档，CHANGELOG 可溯不可改，唯一止血=轮换；详见 `REVIEW-20260922.md` §2.1）

### 建议立项（按优先级）

1. **安全默认值**：日常/生产关闭 `QIMENG_AUTH_DEV_MODE`；换管理密码并勿写入文档；~~内嵌形态补共享密钥校验（SECURITY 规划项）~~ —— **已实现（2026-09-30 批A：dev-login 共享密钥门禁三端落地，见 SECURITY「开发模式」节）**
2. ~~磁盘生命周期：删除资产/库时清缩略图；回收站到期自动物理清除~~ —— **已实现（2026-09-22 复查批：trash.retention_days/sweep_interval 配置 + 到期清扫 + 缩略图四入口联动清理，见 SECURITY/DOMAIN_RULES §9/§11）**；全量对账任务（扫描器外部删除产生的孤儿）仍为规划项
3. ~~**工程卫生**：收敛两份 `启动服务端*.bat`；compose 生产样例唯一化~~ —— **已完成（2026-09-22 复查批处理，见 CHANGELOG 当日笔）**：bat 共用逻辑收敛到根 `_server-common.cmd` 单点；根 compose 已删除，生产样例唯一权威 = `deploy/docker-compose.yml`
4. **能力窄缺口**：备份**调度参数编辑** UI（启用/间隔/保留份数现仅可看，改走 yaml/env+重启）；Web 缓存档位选择 UI（任务 S 后改档入口空缺）

### 明确不做（禁止再提案）

见 `docs/CAPABILITY_MAP.md`「明确不做」：公网裸端口、实时转码、AI 识别、iOS、跨端 UI 框架、多租户 SaaS、协议缺口 #21/#29~#34。

### 已知问题（用户拍板暂不修）

- sourcematcher 别名 `"SC"` 使 `Screenshot_*.jpg` 误归「星际争霸」——真机部署后若误伤明显再议（候选：删该别名 + 连动测试/`DOMAIN_RULES §4`）
- `:core:data` `DataStoreStagingRepositoryTest` 8 用例在 Windows 偶发失败（DataStore `.tmp` 改名竞态，环境性；2026-09-28 stash 基线比对证实为既有失败，与当批改动无关）——需修 DataStore 测试写法或统一 CI 跑 Linux

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
