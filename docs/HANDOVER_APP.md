# HANDOVER-APP - Android 客户端（M4）路线交接说明

> 写给执行 M4 的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 主交接文档（后端/进度/约定）以 `docs/HANDOVER.md` 为准，本文档只覆盖 M4 Android 路线。
> 最后更新：2026-09-04（M4 执行规划定稿：八批次任务书 + 冻结决策 + 验收命令；用户拍板 Web UI 收尾完成后启动 M4）

## 1. 路线定位与现状（2026-09-04）

- `android/` 目前**零应用代码**：仅 `README.md`（技术栈定论）+ `sdk/`（`make sdk-kotlin` 生成物，git 忽略、**禁手改**，协议改动只改 `api/openapi.yaml` 后重新生成）。
- 服务端 M4 前置件已就绪（commit 9773213）：断点续播端点 `PUT /assets/{id}/progress`（migration 0006）、ffprobe 编码元数据（videoCodec/audioCodec 已入 AssetSummary/Detail）。
- **交互规格唯一来源**：旧仓库 `<旧项目目录>\docs\GUIDE_UI.md`（482 行）——只继承交互语义，实现全部用新技术（Coil/Media3/Compose）；**禁止搬运旧 Kotlin 实现代码**（AI_README_FIRST 禁止行为，旧项目是 View/Fragment 架构，本项目 Compose）。
- 执行环境（2026-09-04 实测就绪）：Android Studio（jbr JDK 21）+ SDK `<AndroidSdk>`（build-tools 35 / API 35 系统镜像可装）+ emulator（WHPX 加速可用）。**缺 AVD**——M4-0 首步创建。免安装 JDK17 备用路径 `..\dev-tools\jdk17`（Makefile sdk-kotlin 回退用）。
- CI 现为四 job（协议/服务端/Web/SDK 生成链），**无 Android job**——M4-0 补齐。

## 2. 批次总表（一批 = 一个执行会话的量，按序执行）

| 批次 | 内容 | 前置 | 规格书节（GUIDE_UI.md） |
|---|---|---|---|
| M4-0 | 环境与脚手架（AVD + Gradle 工程 + 壳 + CI + make 目标） | 无 | §导航结构 |
| M4-1 | 登录与服务端配置 | M4-0 | — |
| M4-2 | 首页 / 相册列表 / 四维筛选 / 搜索 | M4-1 | §导航结构、§首页、§全部页、§搜索页、§芯片栏配置对比、§万能筛选组件、§药丸容器、§相册出处分区、§COS 模式、§下拉刷新 |
| M4-3a | 详情页·图片（缩放/预加载/沉浸） | M4-2 | §详情页、§详情页沉浸浏览、§缩略图加载 |
| M4-3b | 详情页·视频播放与互动行 | M4-3a | §BiliPlayerView 视频播放器、§详情页 |
| M4-4 | 行为上报离线队列 | M4-3b | —（事件口径 `docs/DOMAIN_RULES.md` §5） |
| M4-5 | 上传主通道（分享接收/文件选择/队列） | M4-1 | —（服务端口径 `docs/GUIDE_API.md`） |
| M4-6 | 缓存策略与设置页 | M4-2 | §缩略图加载策略 |
| M4-7 | M4 整体验收 | 全部 | PROJECT_PLAN M4 验收标准 |

**M4 范围边界（2026-09-04 规划口径）**：范围 = PROJECT_PLAN M4 七项展开为上表批次。「我的页（浏览历史/收藏/关注）」「数据统计页」**不在 M4 范围内**（Web 端已有同功能页）——用户若要 App 端补齐，拍板后在总表追加批次，执行 AI 不得擅自扩。

## 3. 各批次任务书

### M4-0 环境与脚手架

- **AVD**：创建一台 API 35 x86_64 模拟器（本机 SDK 就绪、WHPX 可用），启动验证能进桌面。
- **Gradle 工程**：`android/settings.gradle.kts` + `:app` 模块 + **include `:sdk`**（`android/sdk/` 生成物作为模块接入，**禁改 sdk/ 内任何文件**）。版本目录 `gradle/libs.versions.toml` 统一管理全部依赖版本；AGP/Kotlin/Compose BOM 组合以执行时官方兼容表为准（铁律 8：先读官方文档再写）。
- **冻结决策**：`applicationId = media.qimeng.app`（SDK 包名 `media.qimeng.sdk` 对齐）；单 Activity + Navigation Compose；minSdk 26；compileSdk/targetSdk 以执行时官方稳定版为准（本机 SDK 35 起步）；依赖白名单 = android/README 技术栈定论（Kotlin/Compose/ViewModel/Hilt/Coil 3/Media3）+ 本批新增（Navigation Compose、DataStore）+ 后续批次明列项（Room、WorkManager）——**白名单之外的新库一律停手问用户**。
- **壳层**：底部导航骨架（页面占位 Composable，导航结构对照 GUIDE_UI §导航结构）+ Hilt Application 壳 + Coil 全局 ImageLoader（内存/磁盘缓存参数留 M4-6 调，本批给默认值常量）。
- **构建接线**：`android/local.properties` 指向本机 SDK；Makefile 增 `app-build`（assembleDebug）/`app-test`（testDebugUnitTest）/`app-lint`（lintDebug）；CI 增 Android job（build+test+lint，runner 的 Android SDK 预装情况以 GitHub Actions 官方文档为准）。
- **验收**：`make app-build`、`make app-test`、`make app-lint` 全绿；模拟器安装启动进占位壳（截图）；`make lint` 全绿；CI 五 job 全绿。
- **存疑停手**：AGP/Kotlin/Compose 版本组合冲突无法按官方文档解决；`:sdk` 模块编译不过且根因在生成物本身（禁手改，回报用户走协议侧处理）。

### M4-1 登录与服务端配置

- **交互（冻结最小版）**：登录页两字段——服务器地址（默认记忆上次，占位 `http://192.168.x.x:8420`）+ 密码 → `POST /auth/login` → token 存 DataStore；提交前先 `GET /healthz` 连通性探测；错误中文文案（地址不通/密码错分开提示）。
- **架构**：AuthRepository（token/地址的读写与清除）+ OkHttp AuthInterceptor（Bearer 注入，token 失效 401 时清 token 跳登录——拦截器里不直接导航，发事件由 ViewModel 收）。
- **验收**：模拟器对本机服务端登录成功进壳、杀进程重启仍登录、退出登录回登录页（三截图）。模拟器访问宿主机服务端用 `http://10.0.2.2:8420`（模拟器回路地址，代码注释说明来源）。
- **存疑停手**：无（不搞自动发现/扫码，用户没要求）。

### M4-2 首页 / 相册列表 / 筛选 / 搜索

- **页面**：首页推荐流（`GET /recommendations`，分批加载）+ 相册四维胶囊筛选（**对齐 Web 第五笔口径**：分区 all/常规/COS 恒显式传 partition 参数、作者行=出处分组∪COS 作者、角色行=角色∪COS 作品、`GET /assets/facets` 取候选）+ 搜索页（FTS5 `q` + 类型/标签筛选）+ 下拉刷新（GUIDE_UI §下拉刷新语义逐条对照）。
- **图片**：Coil 加载签名缩略图直链（sm 档 256）；网格列数自适应；占位/失败占位统一。
- **架构**：AssetRepository 只包 SDK 调用；筛选状态机放 ViewModel（StateFlow），单测锁定（分区/作者/角色/类型组合 → 请求参数映射，含「其他」桶恒排末位、切分区清下级行选择）。
- **验收**：真数据渲染 + 筛选/搜索/刷新对照 GUIDE_UI 语义逐项自检（列表写进交付报告）；ViewModel 单测绿；`make app-build && make app-test && make app-lint` 全绿。
- **存疑停手**：GUIDE_UI 交互与 Web 现版语义冲突处（两处口径不同时停下问用户，不擅自选边）。

### M4-3a 详情页·图片

- **交互（逐字对照 GUIDE_UI §详情页/§详情页沉浸浏览）**：双指缩放、双击还原/放大、左右滑相邻预加载（预加载窗口计算入 ViewModel 单测）、沉浸模式（systemBars 隐藏，退出手势还原）、批次导航（上一件/下一件）。
- **图片走签名原件直链**（"查看永远发原件"是用户强偏好，HANDOVER 特色纪律 4）；Coil 的尺寸下采样不禁用（内存保护），但**不请求缩放副本**。
- **验收**：手势逐项实机自检 + 预加载窗口单测 + 三件套命令绿。
- **存疑停手**：无。

### M4-3b 详情页·视频与互动行

- **播放器（Media3/ExoPlayer）**：HLS 不用（直链 mp4 等）；手势对照 GUIDE_UI §BiliPlayerView 逐条复刻——单击暂停/恢复、长按 2x 倍速（松开还原）、双击侧边快进/快退（若旧版有则复刻，以规格书为准）、倍速菜单、静音（默认静音是 Web 端 M2 约定，App 端默认音量以 GUIDE_UI 为准，规格书没写就默认有声并在交付报告标注请用户拍板）。
- **断点续播（服务端 0006 基座）**：进详情取 detail 播放进度字段（字段名以生成 SDK 类型为准）作起点；播放中每 5s 节流 `PUT /assets/{id}/progress`（间隔用具名常量），暂停/离开页面立即上报一次。
- **时间轴标签**：取该资产 timeline 标签（端点以 GUIDE_API 为准）→ 进度条打点 + 点击 seek 跳转回看（旧版核心体验，必须实现）。
- **编码兼容提示**：videoCodec 在不兼容清单（对齐 Web 端 `AssetDetailPage.tsx` 的 INCOMPATIBLE_CODECS 口径）时显示中文提示、不转码（永远发原件）。
- **互动行**：点赞/收藏 toggle + 标签管理流（复刻 GUIDE_UI 标签管理交互：当前分组/其他分组/新建）。
- **打点**：open（进入即报）/play（起播）/dwell（停留）本批**直连上报**，代码注释标注 `TODO(M4-4): 改走离线队列`。
- **验收**：播放/手势/续播/标记跳转/互动各一截图 + 进度节流与手势状态 ViewModel 单测 + 三件套命令绿。
- **存疑停手**：Media3 某手势规格书有但官方 API 无直接支撑（自定义 GestureOverlay 可解则做，复杂到影响批次规模时停下报）。

### M4-4 行为上报离线队列

- **架构（冻结）**：Room 单表事件队列（id/assetId/kind/startedAt/durationMs/sessionId/createdAt）+ 写入即入队 + 补传器（应用启动/回前台触发一次 + WorkManager 周期兜底，间隔常量）；批量 flush 端点按协议（事件上报 API 以 GUIDE_API 为准，无批量端点则逐条发、串行并发=1）。
- **薄客户端纪律**：**客户端不做去重/聚合算法**——服务端按 sessionId+assetId+kind+当日去重（DOMAIN_RULES §5），客户端只保证不丢不重发（队列状态机：待发/已发/失败重试 ≤3 次后丢弃并计 warning）。
- **队列上限**：环形 5000 条（常量+注释），超限丢最旧。
- **验收**：断网操作若干条 → 联网自动补传 → 服务端统计数字与操作数核对一致（实机）；队列状态机单测全绿。
- **存疑停手**：协议缺批量上报端点且逐条发性能实测不达标（先实测再下结论，实测数据写进交付报告）。

### M4-5 上传主通道（手机采集端核心功能）

- **入口两路**：① 系统分享接收（manifest intent-filter：`image/*`+`video/*` 的 SEND 与 SEND_MULTIPLE）→ 分享进 App 后选库/选目录；② App 内文件选择（SAF `ACTION_OPEN_DOCUMENT` 多选）。
- **目标目录浏览**：`GET /dirs`（libraryId 必填）复用目录树语义；支持新建目录（`POST /dirs` 幂等）。
- **队列（冻结）**：WorkManager 串行队列（并发=1，避免服务端扫描压力）+ okhttp 流式 `application/octet-stream` 上传（对齐服务端约定）；进度通知（前台通知类型）；冲突自动重命名由服务端处理，前端展示最终文件名（upload 响应/SSE 载荷为准）。App 端**不走 SSE**（后台保活不可靠），完成回调刷新队列 UI。
- **前置提示**：大小上限读 `GET /api/v1/config` 的 upload 项（超限本地拦截中文提示）；类型白名单**不在客户端复制**（服务端四道校验为唯一口径，前端只透传服务端 4xx 错误文案）。
- **验收**：模拟器相册分享 2 图 → 选目录 → 上传完成通知 → **Web 端 8420 立即可见**（PROJECT_PLAN M4 验收语义，截图两端对照）；断网中断重试；队列单测绿。
- **存疑停手**：SAF/分享拿到的是 content:// URI 且大文件复制到缓存占空间——按「先拷缓存再流式上传」做并在注释写明空间权衡；若单文件超过设备可用内存的路径不存在（理论），正常处理即可。

### M4-6 缓存策略与设置页

- **Coil 磁盘缓存 LRU**：上限可设（设置页滑杆，档位常量：512MB/1GB/2GB/5GB，默认 1GB）+「清空缓存」按钮（清后容量核对）；DataStore 持久化；**视频不落盘缓存**（直链流式，旧项目同语义，见 GUIDE_UI §缩略图加载策略）。
- 设置页含：服务器地址展示（改地址=退出登录重登）、缓存档位、版本信息。
- **验收**：档位切换持久化 + 清空后缓存目录实测归零（截图）；单测。
- **存疑停手**：无。

### M4-7 M4 整体验收

- 逐条过 PROJECT_PLAN M4 验收标准：手机（用户真机或模拟器）完整日常使用 / 上传→Web 立即可见 / 离线行为数据不丢 / 门禁全绿 / 生成物不手改 / 新迁移只加文件（M4 不应产生 migration，出现即跑偏）/ 决策先写 ADR。
- **ADR 检查**：M4 期间若引入白名单外技术或重大结构决策，逐条核对已补 ADR（编号顺延 + INDEX.md 同步）。
- 全流程录屏（浏览→播放→手势→标记跳转→上传→Web 端可见）交付用户。
- 收尾：HANDOVER_APP 批次表勾选、HANDOVER 进度行、CAPABILITY_MAP 三态同步、CHANGELOG 署名。

## 4. 全批次通用约束（冻结，逐批执行前重读）

1. **分层铁律**（ADR-0008/铁律 7）：Compose UI → ViewModel（StateFlow）→ Repository → 生成 SDK；UI 禁直调 SDK/网络、禁内嵌业务规则；Repository 接口+Impl、Hilt 绑定。
2. **协议纪律**（铁律 1）：禁手改 `android/sdk/**`；任何接口改动先 `api/openapi.yaml` → `make sdk` → 三端适配。
3. **依赖白名单**：见 M4-0 冻结决策；版本全部走 `libs.versions.toml`；新库先读官方文档（铁律 8），白名单外停手问用户。
4. **交互规格**：GUIDE_UI.md 对应节为唯一规格书；规格书与 Web 现版冲突/含糊 → 停手问用户，不擅自"优化"旧交互。
5. **代码卫生**（AI_README_FIRST）：魔法值零容忍（常量+注释：含义/单位/来源）；同一字面量第 2 次出现提常量；调度参数（并发/超时/重试/轮询）禁内联；方法超一屏拆分；中文注释写"为什么"。
6. **验收命令**（每批必全绿才算完成）：`make app-build && make app-test && make app-lint` + 本批新增单测 + `make lint`（全仓）。
7. **文档同步**（铁律 10）：每批完成更新本文件批次表勾选 + CHANGELOG 条目（真实模型署名）；涉及协议改动同步 GUIDE_API/DOMAIN_RULES；commit 格式 `类型(app): 简述 | 文档: 已更新XXX`，代码+文档同一 commit，提交前 `git pull`。
8. **测试纪律**：ViewModel/纯逻辑必须有单测（状态机/参数映射/节流窗口/队列状态机）；UI 层以实机/模拟器操作自检清单代验（清单写进交付报告）。
9. **存疑停手总则**：环境装不动 / 版本冲突 / 规格冲突 / 需要用户拍板的取舍——停手，在交付报告写清现象、已试方案、候选方案，等用户；**禁止带病交付**。
