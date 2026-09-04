# HANDOVER-APP - Android 客户端（M4）路线交接说明

> 写给执行 M4 的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 主交接文档（后端/进度/约定）以 `docs/HANDOVER.md` 为准，本文档只覆盖 M4 Android 路线。
> 最后更新：2026-09-04（路线改道：用户拍板「旧 UI 照搬 + 数据层网络化」（ADR-0013），原 Compose 重建规划作废，本文件按新路线重写；旧项目架构调研结论已并入 §1）

## 1. 路线定位与调研结论（2026-09-04）

- **路线（ADR-0013，用户拍板）**：把旧项目（`<旧项目目录>`，2.4 万行）的 UI 层资产**整体照搬**进 `android/` 新工程，数据层从 Room 单机库换成「生成 SDK（HTTP）+ Room 缓存桥接」。不做 Compose 重建。
- **照搬为基底、后续渐进改版（2026-09-04 用户补充表态，非终态）**：M4 八批次交付「能用的照搬版」为第一阶段；UI 改版为 M4 后独立阶段（方向待拍板，候选=对齐 Web 端桌面客户端视觉、主题层先行），逐项小步改、每步可回退，View/Compose 可共存逐页迁移。不做与旧项目的双向 UI 同步（知识库定位、双倍验证成本；个别页面一次性拷回自用不受限）。
- **旧项目解耦事实（照搬可行性依据，只读子代理实测）**：UI 全部经由单一 `MediaLibraryViewModel`（670 行）持有 `LocalMediaRepository` **接口**（`data/repository/LocalMediaRepository.kt:20`），实现可整体替换（`AppContainer` 一处接线）；实体是纯 Kotlin 数据类；`MediaBrowserLogic` 是零 IO object 纯函数。已知泄漏点：约 10 处 Fragment 直取 `appContainer.appPrefsManager`；`MediaDetailFragment` 4 处直接消费 `content://` URI（图片加载 :489 / 播放器 :621 / 元数据 :747 / 输入流 :1082）；`MediaLibraryViewModel` 自带 contentResolver 读 TXT（:298）与 MediaStoreObserver 注册（:240-246）。
- **旧项目技术栈（照搬基准）**：View/XML + ViewBinding + Fragment 单 Activity ×1 / Fragment ×15 / XML ×19 / 自定义控件 ×5（BiliPlayerView 837 行、ZoomImageView、FlowLayout、LineChartView、MaxHeightScrollView）；手写 AppContainer DI；Coil 3.4；Media3 1.8；Room 2.8；minSdk 31 / targetSdk 36；零网络库零 Service 零 WorkManager。
- **服务端前置件已就绪**：断点续播 `PUT /assets/{id}/progress`（commit 9773213，migration 0006）、ffprobe 编码元数据、全部领域算法（M3 完成，客户端不复算）。
- **执行环境（2026-09-04 实测就绪）**：Android Studio（jbr JDK 21）+ SDK `<AndroidSdk>`（build-tools 35，compileSdk 所需 platform 用 sdkmanager 补装）+ emulator（WHPX 加速）。**缺 AVD**——M4-0 首步创建。免安装 JDK17 备用 `..\dev-tools\jdk17`（Makefile sdk-kotlin 回退用）。
- CI 现为四 job，**无 Android job**——M4-0 补第五个。

## 2. 批次总表（一批 = 一个执行会话的量，按序执行）

| 批次 | 内容 | 前置 | 规格书节（旧仓库 GUIDE_UI.md） |
|---|---|---|---|
| M4-0 | 工程基建（AVD + Gradle + 旧壳骨架 + 登录 + make/CI） | 无 | §导航结构 |
| M4-1 | 数据层网络化（RemoteMediaRepository + Room 缓存桥接） | M4-0 | — |
| M4-2 | 列表族页面移植（首页/全部/相册/收藏/历史/搜索/作者） | M4-1 | §导航结构、§首页、§全部页、§搜索页、§芯片栏配置对比、§万能筛选组件、§药丸容器、§相册出处分区、§COS 模式、§下拉刷新、§浏览历史、§收藏页 |
| M4-3 | 详情页移植（图片 + BiliPlayerView 视频 + 断点续播 + 互动） | M4-2 | §详情页、§详情页沉浸浏览、§BiliPlayerView 视频播放器 |
| M4-4 | 行为上报离线队列 | M4-3 | —（事件口径 `docs/DOMAIN_RULES.md` §5） |
| M4-5 | 上传主通道（分享接收/文件选择/队列——全新页面） | M4-1 | —（服务端口径 `docs/GUIDE_API.md`） |
| M4-6 | 缓存策略与设置页（统计页 LineChartView 一并移植） | M4-2 | §缩略图加载策略、§数据统计页、§我的页 |
| M4-7 | M4 整体验收 | 全部 | PROJECT_PLAN M4 验收标准 |

**范围口径（照搬路线）**：旧 UI 全部页面照搬（含统计页/我的页/作者页——代码现成，顺手搬）；**不搬**单机生态页（数据备份/数据管理/扫描设置项）与全部禁搬清单代码（ADR-0013/android README）。

## 3. 各批次任务书

### M4-0 工程基建（AVD + Gradle + 旧壳骨架 + 登录）

- **AVD**：创建 API 35 x86_64 模拟器一台（WHPX 可用），启动验证。
- **Gradle 工程**：`android/settings.gradle.kts` + `:app` 模块 + **include `:sdk`**（`android/sdk/` 生成物，**禁改内部文件**）。版本目录 `gradle/libs.versions.toml`；AGP/Kotlin/各库版本以旧项目 `libs.versions.toml` 为基线、执行时按官方兼容表取新（铁律 8：先读官方文档）。
- **冻结决策**：namespace/applicationId `com.qimeng.media`（沿用旧包名，照搬零包改动）；minSdk 31；compileSdk/targetSdk 以执行时官方稳定版为准；依赖白名单 = 照搬清单（ViewBinding/Coil 3/Media3/Room/swiperefreshlayout/documentview 等旧项目既有）+ DataStore（登录配置）+ WorkManager（M4-5 队列）——**白名单之外的新库停手问用户**；不引入 Compose/Hilt（ADR-0013）。
- **旧壳骨架**：拷入 `MainActivity` + 主题（ThemeHelper）+ `QimengApplication` + `AppContainer`（删除 localMediaRepository/scanUseCase/autoSyncUseCase 等单机绑定，登录/SDK 相关绑定本批新增）；导航壳 + 底部导航占位页（对照 GUIDE_UI §导航结构）。
- **登录（并入本批）**：登录页两字段——服务器地址（记忆上次，占位 `http://192.168.x.x:8420`）+ 密码 → `POST /auth/login` → token 存 DataStore；提交前 `GET /healthz` 连通性探测；OkHttp AuthInterceptor 注入 SDK（Bearer；401 清 token 跳登录——拦截器发事件不直接导航）。错误中文文案。模拟器访问宿主机服务端用 `http://10.0.2.2:8420`（回路地址，注释说明）。
- **构建接线**：`android/local.properties` 指向本机 SDK；Makefile 增 `app-build`（assembleDebug）/`app-test`（testDebugUnitTest）/`app-lint`（lintDebug）；CI 增 Android job（build+test+lint；runner 的 Android SDK 预装情况以 GitHub Actions 官方文档为准）。
- **验收**：`make app-build && make app-test && make app-lint` 全绿；模拟器安装→登录本机服务端→进占位壳、杀进程重启仍登录（三截图）；`make lint` 全绿；CI 五 job 全绿。
- **存疑停手**：AGP/Kotlin 版本组合与旧项目依赖冲突无法按官方文档解决；`:sdk` 模块编译不过且根因在生成物（禁手改，回报走协议侧）。

### M4-1 数据层网络化（本路线最大风险点，先行验证）

- **RemoteMediaRepository**：实现 `LocalMediaRepository` 接口——「API 拉取 → 映射 `MediaFileEntity` → 写 Room 缓存 → Flow 推 UI」桥接模式（接口 ~100 方法中 ~40 个 `observe*(): Flow` 靠 Room invalidate 驱动，请求式方法直接透传 API）。SDK DTO ↔ 实体映射层独立文件，**单测锁定**（字段映射/空值/单位）。
- **渐进实现（冻结）**：不要求一次实现全部方法——按 M4-2 起各批 UI 实际调用面补齐，未实现方法留 `TODO(M4-x)` 注释；M4-1 先做列表族所需子集（分页列表/筛选/搜索/推荐/作者/收藏/历史）。
- **旧 ViewModel 清理**：`MediaLibraryViewModel` 照搬后删除 contentResolver 读 TXT（:298）与 MediaStoreObserver 注册（:240-246）；数据刷新触发改为「下拉刷新/进页拉取」显式动作。
- **验收**：映射层单测全绿；模拟器上首页占位 Fragment 显示真库数据（列表 20 条截图）；三件套命令绿。
- **存疑停手**：接口某方法语义在服务端无对应端点（先查 GUIDE_API/openapi——真没有就停手列清单给用户拍板：补协议 or 接口瘦身）。

### M4-2 列表族页面移植

- **照搬**：`ui/main`(首页)/`ui/all`/`ui/album`(相册)/`ui/favorite`/`ui/history`/`ui/search`/`ui/author` 各 Fragment + adapter（GroupedMediaAdapter/MediaThumbnailAdapter）+ FlowLayout + 对应 XML。
- **网络化改造（每页模板）**：①缩略图 `load(uriString)` → `load(签名缩略图 URL)`（Coil 不变，换 URL 来源）；②列表数据从 ViewModel Flow 取（M4-1 已桥接）；③筛选/搜索参数由服务端算（客户端只传参，不复算 applyFilter）；④下拉刷新 = 显式 API 拉取 + Room 缓存更新；⑤删除单机交互（本地扫描按钮等，若有）。
- **直取泄漏清理**：本批涉及 Fragment 的 `appContainer.appPrefsManager` 直取改经 ViewModel（约 10 处中的本批部分）。
- **验收**：各页真数据渲染 + 筛选/搜索/刷新对照 GUIDE_UI 语义逐项自检（清单进交付报告）；分页加载实测；三件套绿。
- **存疑停手**：GUIDE_UI 交互与服务端口径冲突处（停下问用户，不擅自选边）。

### M4-3 详情页移植（图片 + 视频 + 断点续播 + 互动）

- **照搬**：`MediaDetailFragment`（1345 行）+ `BiliPlayerView`（837 行，B 站式手势：长按 2x/滑动指示器/锁倍速 + 时间轴标签跳转）+ `ZoomImageView`（双指缩放/双击还原）+ 沉浸模式 + 批次导航（左右预加载）。
- **网络化改造（冻结清单）**：①图片 `load(uriString)`(:489) → 签名原件 URL（"查看永远发原件"，Coil 尺寸下采样不禁用但**不请求缩放副本**）；②`player.setVideoUri(uriString)`(:621) → 签名原件 URL；③`MediaMetadataRetriever.setDataSource`(:747) → 改用服务端 detail 元数据（宽高/时长已在协议，禁客户端再探测）；④`contentResolver.openInputStream`(:1082) → 删除（该用途改走 API）；⑤断点续播叠加：进页取 detail 播放进度为起点 + 播放中每 5s 节流 `PUT /assets/{id}/progress`（具名常量，暂停/离开立即上报）——新功能，叠加在 BiliPlayerView 上；⑥编码兼容提示（videoCodec 在不兼容清单——口径对齐 Web 端 `AssetDetailPage.tsx` 的 INCOMPATIBLE_CODECS——显示中文提示不转码）；⑦互动行（点赞/收藏/标签管理流）走 API；⑧打点 open/play/dwell 本批直连上报 + `TODO(M4-4)` 注释。
- **验收**：图片手势/视频手势/时间轴标签跳转/续播起点/标记各一截图；预加载窗口与进度节流单测；三件套绿。
- **存疑停手**：BiliPlayerView 内部对本地 URI 的隐藏依赖（比如亮度/音量手势之外的文件访问）逐个改造，复杂到影响批次规模时停下报。

### M4-4 行为上报离线队列

- **架构（冻结）**：Room 事件队列表（id/assetId/kind/startedAt/durationMs/sessionId/createdAt）+ 写入即入队 + 补传器（应用启动/回前台触发 + WorkManager 周期兜底，间隔常量）；无批量端点则逐条串行发（并发=1）。M4-3 的直连打点全部改走队列。
- **薄客户端纪律**：客户端不做去重/聚合——服务端按 sessionId+assetId+kind+当日去重（DOMAIN_RULES §5）；客户端只保证不丢不重发（状态机：待发/已发/失败重试 ≤3 后丢弃计 warning）。队列环形上限 5000 条（常量+注释）。
- **验收**：断网操作 → 联网补传 → 服务端统计数字与操作数核对（实机）；状态机单测全绿。
- **存疑停手**：逐条发性能实测不达标（先实测，数据写进交付报告）。

### M4-5 上传主通道（手机采集端核心，全新页面无旧 UI 可搬）

- **入口两路**：①系统分享接收（intent-filter：`image/*`+`video/*` 的 SEND/SEND_MULTIPLE）→ 选库/选目录；②App 内 SAF `ACTION_OPEN_DOCUMENT` 多选。
- **目标目录**：`GET /dirs`（libraryId 必填）目录树 + `POST /dirs` 幂等新建。
- **队列（冻结）**：WorkManager 串行（并发=1，避免服务端扫描压力）+ okhttp 流式 `application/octet-stream`；进度前台通知；App 端不走 SSE（后台保活不可靠）。冲突自动重命名由服务端处理，UI 展示最终文件名。
- **前置提示**：大小上限读 `GET /api/v1/config` upload 项超限本地拦截（中文）；类型白名单不复制（服务端四道校验唯一口径，4xx 透传文案）。
- **验收**：模拟器相册分享 2 图 → 选目录 → 完成通知 → **Web 端 8420 立即可见**（两端对照截图）；断网中断重试；队列单测。
- **存疑停手**：SAF content:// 大文件「先拷缓存再流式上传」的空间权衡异常（注释写明策略即可）。

### M4-6 缓存策略与设置页 + 统计页

- **Coil 磁盘缓存 LRU**：上限可设（设置页档位：512MB/1GB/2GB/5GB，默认 1GB，常量）+ 清空按钮（清后容量核对）；DataStore 持久化；**视频不落盘**（直链流式）。
- **设置页照搬**：旧设置页骨架 + 删单机项（扫描/备份相关），加：服务器地址展示（改地址=退出重登）、缓存档位、版本信息。
- **统计页照搬**：`ui/stats`（含 LineChartView 自绘趋势线）接 `/stats/overview` `/stats/trends`（§5 口径已在服务端）；我的页（`ui/profile` 照搬，删备份/数据管理入口）。
- **验收**：档位持久化 + 清空归零（截图）；统计数字与 Web 端数据页一致（同库对照）；三件套绿。
- **存疑停手**：无。

### M4-7 M4 整体验收

- 逐条过 PROJECT_PLAN M4 验收标准：手机（用户真机或模拟器）完整日常使用 / 上传→Web 立即可见 / 离线行为数据不丢 / 门禁全绿 / 生成物不手改 / 新迁移只加文件（M4 不应产生服务端 migration，出现即跑偏）/ 决策先写 ADR。
- ADR 检查：M4 期间引入白名单外技术或重大结构决策，逐条核对已补 ADR。
- 全流程录屏（浏览→播放→手势→标记跳转→上传→Web 端可见）交付用户。
- 收尾：HANDOVER_APP 批次表勾选、HANDOVER 进度行、CAPABILITY_MAP 三态同步、CHANGELOG 署名。

## 4. 全批次通用约束（冻结，逐批执行前重读）

1. **照搬纪律（ADR-0013）**：`ui/` 层 + AppContainer + 主题照搬；**禁搬清单**（android/README）一律不进新工程；`MediaBrowserLogic` 只搬纯展示 helper，recommend/rank/applyFilter 不搬（服务端已算好，客户端复算=事故）。
2. **分层铁律**（ADR-0008/铁律 7）：Fragment/View → MediaLibraryViewModel → LocalMediaRepository（Remote 实现）→ 生成 SDK；UI 禁直调 SDK/网络、禁内嵌业务规则；新数据访问全部走 ViewModel。
3. **协议纪律**（铁律 1）：禁手改 `android/sdk/**`；接口改动先 `api/openapi.yaml` → `make sdk` → 三端适配。
4. **依赖白名单**：见 M4-0 冻结决策；版本全走 `libs.versions.toml`；新库先读官方文档（铁律 8），白名单外停手问用户。
5. **交互规格**：GUIDE_UI.md 对应节为规格书；照搬代码行为与规格书冲突时以代码实测行为为准（代码是打磨过的真相），仍含糊则停手问用户。
6. **代码卫生**（AI_README_FIRST）：魔法值零容忍；同一字面量第 2 次出现提常量；调度参数禁内联；方法超一屏拆；中文注释写"为什么"。
7. **验收命令**（每批必全绿）：`make app-build && make app-test && make app-lint` + 本批新增单测 + `make lint`（全仓）。
8. **文档同步**（铁律 10）：每批完成更新本文件批次表勾选 + CHANGELOG 条目（真实模型署名）；协议改动同步 GUIDE_API/DOMAIN_RULES；commit 格式 `类型(app): 简述 | 文档: 已更新XXX`，代码+文档同一 commit，提交前 `git pull`。
9. **测试纪律**：映射层/ViewModel/纯逻辑必须单测；UI 层以实机操作自检清单代验（清单进交付报告）。
10. **存疑停手总则**：环境装不动 / 版本冲突 / 接口无服务端对应 / 规格冲突 / 需要用户拍板的取舍——停手，交付报告写清现象、已试方案、候选方案，等用户；**禁止带病交付**。
