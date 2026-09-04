# HANDOVER-APP - Android 客户端（M4）路线交接说明

> 写给执行 M4 的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 主交接文档（后端/进度/约定）以 `docs/HANDOVER.md` 为准，本文档只覆盖 M4 Android 路线。
> 最后更新：2026-09-04（M4 二次改道定稿：用户拍板「先进方案优先」走 Compose 重建（ADR-0014，废弃同日的照搬路线 0013）；架构标准对齐 Google Now in Android 多模块范式；单机形态（ADR-0015）预留接口）

## 1. 路线定位（2026-09-04 二次改道后）

- **路线（ADR-0014，用户拍板「先进优先、额度不设限」）**：Kotlin + Jetpack Compose 全新实现，交互规格照搬旧项目（旧仓库 `<旧项目目录>\docs\GUIDE_UI.md`，482 行），实现代码全部新写；复杂自绘控件（BiliPlayerView/ZoomImageView）允许 AndroidView 互操作桥接（桥接清单进交付报告）。
- **架构标准（冻结，Now in Android 范式）**：多模块 `:app` + `:core:model/network/data/ui` + `:feature:*`；依赖单向 feature→core；UI(Compose)→ViewModel(StateFlow)→Repository 接口→生成 SDK；Hilt 注入；UI 禁直调网络、禁内嵌业务规则（ADR-0008/铁律 7）。
- **旧项目调研结论仍有效（ADR-0013 遗产，做交互规格参考）**：旧 UI 全部经由单一 ViewModel 持有 repository 接口（解耦范本）；已知旧实现要点——详情页 4 处本地 URI 直消费（图片 :489/播放器 :621/元数据 :747/输入流 :1082）对应新实现须走服务端 URL/元数据；缩略图三级解码管线不移植（服务端出图，ADR-0002 架构）。
- **服务端前置件已就绪**：断点续播 `PUT /assets/{id}/progress`（commit 9773213）、ffprobe 编码元数据、全部领域算法（M3 完成，客户端不复算）。
- **单机形态预留（ADR-0015，M6 实施）**：M4-1 的「服务器地址」配置是唯一服务端定位点——单机形态只改这一处指向 localhost，UI 零改动。禁止在 ViewModel/Repository 之外散落服务端地址假设。
- **执行环境（2026-09-04 实测就绪）**：Android Studio（jbr JDK 21）+ SDK `<AndroidSdk>`（build-tools 35，compileSdk 所需 platform 用 sdkmanager 补装）+ emulator（WHPX 加速）。**缺 AVD**——M4-0 首步创建。免安装 JDK17 备用 `..\dev-tools\jdk17`。
- CI 现为四 job，**无 Android job**——M4-0 补第五个。

## 2. 批次总表（一批 = 一个执行会话的量，按序执行）

| 批次 | 内容 | 前置 | 规格书节（旧仓库 GUIDE_UI.md） |
|---|---|---|---|
| M4-0 | 工程基建（AVD + 多模块骨架 + 壳导航 + make/CI） | 无 | §导航结构 |
| M4-1 | 登录与服务端配置（地址+token，单机形态预留点） | M4-0 | — |
| M4-2 | 列表族（首页/全部/相册/收藏/历史/搜索/作者） | M4-1 | §导航结构、§首页、§全部页、§搜索页、§芯片栏配置对比、§万能筛选组件、§药丸容器、§相册出处分区、§COS 模式、§下拉刷新、§浏览历史、§收藏页 |
| M4-3 | 详情页（图片缩放/预加载/沉浸 + Media3 视频手势 + 断点续播 + 时间轴标签 + 互动行） | M4-2 | §详情页、§详情页沉浸浏览、§BiliPlayerView 视频播放器、§缩略图加载 |
| M4-4 | 行为上报离线队列 | M4-3 | —（事件口径 `docs/DOMAIN_RULES.md` §5） |
| M4-5 | 上传主通道（分享接收/文件选择/队列） | M4-1 | —（服务端口径 `docs/GUIDE_API.md`） |
| M4-6 | 缓存策略 + 设置页 + 统计页/我的页 | M4-2 | §缩略图加载策略、§数据统计页、§我的页 |
| M4-7 | M4 整体验收 | 全部 | PROJECT_PLAN M4 验收标准 |

**范围**：旧 UI 全部页面族的交互语义（含统计/我的/作者页）；单机生态页（数据备份/数据管理/扫描）无对应服务端能力，不做。

## 3. 各批次任务书

### M4-0 工程基建（多模块骨架）

- **AVD**：创建 API 35 x86_64 模拟器一台（WHPX 可用），启动验证。
- **Gradle 多模块（冻结结构）**：`:app`（壳/导航/Hilt 装配）+ `:core:model`（纯 Kotlin 数据类，映射 SDK DTO）+ `:core:network`（SDK 封装+OkHttp/AuthInterceptor）+ `:core:data`（Repository 接口+实现）+ `:core:ui`（主题 token/共享组件）+ `:feature:home/all/album/favorite/history/search/author/detail/stats/settings/upload`（空壳起步，逐批填充）。**include `:sdk`**（`android/sdk/` 生成物模块，禁改内部文件）。版本目录 `gradle/libs.versions.toml`；AGP/Kotlin/Compose BOM 以执行时官方稳定组合为准（铁律 8：先读官方文档，Now in Android 仓库为结构参照）。
- **冻结决策**：namespace `media.qimeng.app`（模块后缀如 `media.qimeng.app.core.data`）；minSdk 26 / compileSdk 以官方最新稳定为准；依赖白名单 = ADR-0014 技术栈清单（Compose M3/Hilt/Navigation Compose/Coil 3/Media3/Room/DataStore/WorkManager）——**白名单之外的新库停手问用户**。
- **壳层**：单 Activity + Navigation Compose + 底部导航骨架（占位页，结构对照 GUIDE_UI §导航结构）+ 主题（Material 3 动态色彩可选，色板对齐品牌主色 #4250af 系——具体色值执行时与 Web 端 prototype.css token 对照换算，写入 :core:ui 主题常量）。
- **构建接线**：`android/local.properties` 指向本机 SDK；Makefile 增 `app-build`（assembleDebug）/`app-test`（testDebugUnitTest）/`app-lint`（lintDebug）；CI 增 Android job（build+test+lint，runner 的 Android SDK 预装情况以 GitHub Actions 官方文档为准）。
- **验收**：`make app-build && make app-test && make app-lint` 全绿；模拟器安装启动进壳导航（截图）；`make lint` 全绿；CI 五 job 全绿。
- **存疑停手**：AGP/Kotlin/Compose 版本组合冲突无法按官方文档解决；`:sdk` 模块编译不过且根因在生成物（禁手改，回报走协议侧）。

### M4-1 登录与服务端配置（单机形态预留点）

- **交互（冻结最小版）**：登录页两字段——服务器地址（记忆上次，占位 `http://192.168.x.x:8420`；**支持 localhost/127.0.0.1 形式**，为 M6 单机形态留口）+ 密码 → `POST /auth/login` → token 存 DataStore；提交前 `GET /healthz` 连通性探测；错误中文文案（地址不通/密码错分开）。模拟器访问宿主机服务端用 `http://10.0.2.2:8420`（回路地址，注释说明）。
- **架构**：AuthRepository（地址/token 读写与清除，:core:data）+ OkHttp AuthInterceptor（Bearer 注入；401 清 token 发事件跳登录——拦截器不直接导航）；服务器地址经 `ServerConfigDataSource` 单点管理（**全 App 唯一服务端定位点**，ADR-0015 预留）。
- **验收**：模拟器登录本机服务端成功进壳、杀进程重启仍登录、退出登录回登录页（三截图）；单测（token 存取/401 事件流）。
- **存疑停手**：无。

### M4-2 列表族页面

- **页面（Compose 复刻，交互逐条对照规格书）**：首页推荐流（`GET /recommendations` 分批）+ 全部/相册（四维胶囊筛选，**对齐 Web 第五笔口径**：partition 恒显式传、作者行=出处分组∪COS 作者、角色行=角色∪COS 作品、`GET /assets/facets` 候选）+ 收藏/浏览历史/搜索（FTS5 `q`+类型/标签筛选）+ 作者页（关注 toggle）。下拉刷新（GUIDE_UI §下拉刷新语义）、日期分组（dateLabel 规则照规格书）。
- **图片**：Coil 加载签名缩略图直链（sm 档）；网格列数自适应（LazyVerticalGrid）；占位/失败占位统一；**动图缩略图行为实测**（服务端若静态化且规格书要求网格动画，报用户拍板服务端调整——对照旧版行为）。
- **架构**：筛选状态机放 ViewModel（StateFlow），单测锁定（分区/作者/角色/类型组合→请求参数映射，「其他」桶恒排末位，切分区清下级行选择）。
- **验收**：真数据渲染+筛选/搜索/刷新对照 GUIDE_UI 语义逐项自检（清单进交付报告）；分页实测；三件套命令绿。
- **存疑停手**：规格书交互与服务端口径冲突（停下问用户）。

### M4-3 详情页（图片 + 视频 + 互动，M4 最重批次）

- **图片（对照 §详情页/§详情页沉浸浏览）**：双指缩放、双击还原/放大、左右滑相邻预加载（窗口计算 ViewModel 单测）、沉浸模式（systemBars 隐藏+退出手势）、批次导航。原件签名直链（"查看永远发原件"）；Coil 下采样不禁用（内存保护）但**不请求缩放副本**。ZoomImageView 手势语义可用 Compose 自写（transformable/gesture）或 AndroidView 桥接旧控件，桥接则列入交付报告。
- **视频（对照 §BiliPlayerView）**：Media3/ExoPlayer + 手势复刻——单击暂停/恢复、长按 2x（松开还原）、滑动指示器（亮度/音量/进度，规格书有则复刻）、倍速菜单、静音（App 默认音量以规格书为准，未写则默认有声并在交付报告标注请用户拍板）。BiliPlayerView 837 行旧控件允许 AndroidView 整体桥接（推荐：手势层成熟），或 Compose 重写——执行时按复刻成本定。**断点续播**：进页取 detail 播放进度（字段名以生成 SDK 类型为准）为起点 + 播放中每 5s 节流 `PUT /assets/{id}/progress`（具名常量；暂停/离开立即上报）。**时间轴标签**：进度条打点+点击 seek 跳转回看（旧版核心体验，必须实现）。**编码兼容提示**：videoCodec 不兼容清单（口径对齐 Web 端 `AssetDetailPage.tsx` INCOMPATIBLE_CODECS）显示中文提示、不转码。
- **互动行**：点赞/收藏 toggle + 标签管理流（当前分组/其他分组/新建，对照规格书）。
- **打点**：open（进入）/play（起播）/dwell（停留）本批直连上报，代码注释 `TODO(M4-4): 改走离线队列`。
- **验收**：图片/视频手势/续播/标记跳转/互动各一截图；预加载窗口与进度节流单测；三件套绿。
- **存疑停手**：某手势规格书有但 Media3/Compose 无直接支撑（自定义层可解则做，复杂到影响批次规模停下报）。

### M4-4 行为上报离线队列

- **架构（冻结）**：Room 事件队列表（id/assetId/kind/startedAt/durationMs/sessionId/createdAt）+ 写入即入队 + 补传器（应用启动/回前台触发 + WorkManager 周期兜底，间隔常量）；无批量端点则逐条串行（并发=1）。M4-3 直连打点全部改走队列。
- **薄客户端纪律**：客户端不做去重/聚合（服务端按 sessionId+assetId+kind+当日去重，DOMAIN_RULES §5）；只保证不丢不重发（状态机：待发/已发/失败重试 ≤3 后丢弃计 warning）。队列环形上限 5000 条（常量+注释）。
- **验收**：断网操作→联网补传→服务端统计数字核对（实机）；状态机单测全绿。
- **存疑停手**：逐条发性能实测不达标（先实测，数据进交付报告）。

### M4-5 上传主通道（手机采集端核心）

- **入口两路**：①系统分享接收（intent-filter：`image/*`+`video/*` 的 SEND/SEND_MULTIPLE）→ 选库/选目录；②App 内 SAF `ACTION_OPEN_DOCUMENT` 多选。
- **目标目录**：`GET /dirs`（libraryId 必填）+ `POST /dirs` 幂等新建。
- **队列（冻结）**：WorkManager 串行（并发=1，避免服务端扫描压力）+ okhttp 流式 `application/octet-stream`；进度前台通知；App 不走 SSE。冲突自动重命名由服务端处理，UI 展示最终文件名。
- **前置提示**：大小上限读 `GET /api/v1/config` upload 项超限本地拦截（中文）；类型白名单不复制（服务端四道校验唯一口径，4xx 透传文案）。
- **验收**：模拟器相册分享 2 图→选目录→完成通知→**Web 端 8420 立即可见**（两端对照截图）；断网中断重试；队列单测。
- **存疑停手**：SAF content:// 大文件「先拷缓存再流式上传」空间权衡（注释写明策略）。

### M4-6 缓存策略 + 设置页 + 统计/我的页

- **Coil 磁盘缓存 LRU**：上限可设（档位 512MB/1GB/2GB/5GB，默认 1GB，常量）+ 清空按钮（清后容量核对）；DataStore 持久化；视频不落盘（直链流式）。
- **设置页**：服务器地址展示（改地址=退出重登）、缓存档位、版本信息。
- **统计页**：趋势线（规格书 §数据统计页——Compose Canvas 自绘或 AndroidView 桥接旧 LineChartView）接 `/stats/overview` `/stats/trends`；我的页（资料/关注列表）。
- **验收**：档位持久化+清空归零（截图）；统计数字与 Web 端数据页同库一致；三件套绿。
- **存疑停手**：无。

### M4-7 M4 整体验收

- 逐条过 PROJECT_PLAN M4 验收标准：完整日常使用 / 上传→Web 立即可见 / 离线行为不丢 / 门禁全绿 / 生成物不手改 / 新迁移只加文件（M4 不应产生服务端 migration，出现即跑偏）/ 决策先写 ADR。
- ADR 检查：白名单外技术或重大结构决策逐条核对 ADR。
- 全流程录屏（浏览→播放→手势→标记跳转→上传→Web 端可见）交付用户。
- 收尾：HANDOVER_APP 批次表勾选、HANDOVER 进度行、CAPABILITY_MAP 三态同步、CHANGELOG 署名。

## 4. 全批次通用约束（冻结，逐批执行前重读）

1. **架构铁律（ADR-0014）**：多模块单向依赖（feature→core，禁反向）；UI(Compose)→ViewModel(StateFlow)→Repository 接口→SDK；UI 禁直调 SDK/网络、禁内嵌业务规则；新依赖全走 Hilt 注入（AppContainer 手写单例禁止）。
2. **协议纪律（铁律 1）**：禁手改 `android/sdk/**`；接口改动先 `api/openapi.yaml` → `make sdk` → 三端适配。
3. **依赖白名单**：ADR-0014 技术栈清单；版本全走 `libs.versions.toml`；新库先读官方文档（铁律 8），白名单外停手问用户。
4. **交互规格**：GUIDE_UI.md 对应节为唯一规格书；复刻走样按 Web 端 HANDOVER_UI §4.5 教训管理——**先测后交、交付附对照清单**；规格书与 Web 现版冲突停手问用户。
5. **单机形态预留（ADR-0015）**：服务端地址只经 M4-1 的 ServerConfigDataSource 流转；任何模块禁止另行假设服务端位置。
6. **代码卫生（AI_README_FIRST）**：魔法值零容忍；同一字面量第 2 次出现提常量；调度参数禁内联；方法超一屏拆；中文注释写"为什么"。
7. **验收命令（每批必全绿）**：`make app-build && make app-test && make app-lint` + 本批新增单测 + `make lint`（全仓）。
8. **文档同步（铁律 10）**：每批完成更新本文件批次表勾选 + CHANGELOG 条目（真实模型署名）；协议改动同步 GUIDE_API/DOMAIN_RULES；commit 格式 `类型(app): 简述 | 文档: 已更新XXX`，代码+文档同一 commit，提交前 `git pull`。
9. **测试纪律**：ViewModel/状态机/映射/纯逻辑必须单测；UI 以实机操作自检清单代验（清单进交付报告）。
10. **存疑停手总则**：环境装不动/版本冲突/规格冲突/需要用户拍板的取舍——停手，交付报告写清现象、已试方案、候选方案，等用户；**禁止带病交付**。
