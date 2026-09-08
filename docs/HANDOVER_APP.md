# HANDOVER-APP - Android 客户端（M4）路线交接说明

> 写给执行 M4 的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 主交接文档（后端/进度/约定）以 `docs/HANDOVER.md` 为准，本文档只覆盖 M4 Android 路线。
> 最后更新：2026-09-09 凌晨（**任务H/I 双卷立卷 + 过时任务书归档**：H-Android复刻前置卷（H0 复刻差距清单 docs/REPLICATION_GAPS.md →H1 core:ui 组件瘦身（M3 标准件替换，共享组件对全部页穿透豁免口径）→H2 统计折线图换 Vico（推翻 C3 拍板记档）→H3 收官，严格串行）→ I-Android页面复刻卷（I1~I8 按 feature 模块批互斥并行（每 executor 独占本批全部模块）、I9 收官；复刻基准=旧仓库 GUIDE_UI.md+《QimengNAS\旧版UI实录》，**裁决优先级=用户拍板>GUIDE_UI>Web 现版**，6 条在效拍板保护（含 G2 作者总览卡进我的页），上传/登录（无复刻基准只走查）与相册详情（全部页承接冻结区）特殊归宿，铁律新增**「主会话只派发不做事，尽量不亲自做任务除非必须」**（用户 2026-09-09 拍板））；任务书=仓库外《QimengNAS\任务H-Android复刻前置卷.md》《QimengNAS\任务I-Android页面复刻卷.md》；**任务D/E/F/G 四卷任务书收官后归档删除**（2026-09-09 用户拍板；收官记录全在 CHANGELOG 第一百二十~一百四十六笔），接手者对 AI 说「执行任务H/任务I」自驱）前次：2026-09-08 傍晚（**任务G-Android对齐卷收官——用户六项反馈全清偿**：G3 系统栏 6c0ab2e（LoginScreen 三 insets+NavHost consumeWindowInsets 清覆盖页双重留白，bytecode 实证 M3 TopAppBar 默认 insets 叠 innerPadding；作者页标题 top 300→171px 实测）→ G6 内部胶囊 7f1a9f7e（QimengCapsuleTextField/QimengSegPill 共享件+11 输入框 3 档位替换+PillChip 委托单源；含 G4 搜索胶囊）→ G5 相册对齐 Web 5536d79（排序 pill 行四档+值行 in-flow 两行收起阈值 9+卡片日期行；**基准拍板=Web 现版，推翻 B6 豁免档相册页豁免**；QimengFloatingPillPanel 收藏/历史仍在用保留）→ G2 作者总览 7e6c405（我的页作者总览卡（N 位·已关注 M+Top5+RankCard）+AuthorScreen 按 Web AuthorsPage 重排；计数口径纠偏=Web 全量数、浏览数不展示）→ G1a 详情排版 e2d5abc（68vh 舞台钳制+asset-pager 行（顶 i/N 退役）+互动钮实底 Filled bounce+meta·分隔+右栏 RankCard）→ G1b 详情交互 faaa181（整理/删除文件操作（SDK 既有端点+409 领域文案+回收站二次确认；目录树简化文本输入留后续批）+作者集合页新路由（authorId+includeCos 分页）三处接线；UpNext 副行按 Web 基准跳过）；模拟器 18461 全链验证（改名/删除回收站往返/作者双入口/关注往返）；**G7 对抗审查 1×P2（方向锁快照转场窗口：详情互 push 快照捕旧页残留 PORTRAIT 代代相传→无条件恢复 UNSPECIFIED）+7×P3 全清偿**（filterByZone 死代码/targetDir trim/Log.w 带栈/ime∪navBars/CHANGELOG 计数勘误×2）；三连绿门禁串行；零协议零 SDK 再生零 web/server 改动；CHANGELOG 第一百四十~一百四十六笔；任务书全勾选）前次：2026-09-08 夜三（**任务D 全卷收官——M4 里程碑达成**：D6 整批自查 40ec778（七块清单+GIF 停帧真 bug 修复 Animatable.start；暴露 progress 序列化 400=待拍板 #26 与 #24 同根）→ D7 M4-7 验收全过（八条+五观察项+四段门禁绿 256 tests 0 fail；110 文件 SAF 无 ANR；环境复原 cos5+normal18；证据 %TEMP%\qimeng-d7-evidence\）——PROJECT_PLAN M4 复刻/详情/行为上报三条全勾、CAPABILITY_MAP 移动端=已有(M4)；遗留记录：批次播种缺口 #21、静默黑屏 #27、harness init 坑 #28；晨间汇总=CHANGELOG 第一百三十五笔）前次：2026-09-08 夜二（**D5 M4-4 离线队列落地**：`:core:data` 新建 events 包——Room 队列表 7 列（room 2.8.4 实测兼容）+三通道触发（写入/ON_START/WorkManager 15min 兜底，无 CONNECTED 约束同上传链先例）+drain Mutex 串行+dwell 先删后发/毒丸≥3；新增 20 单测+断网 18461 对账闭环；M4-3 直连打点全部改走队列、TODO(M4-4) 清零、DwellSessionTracker 语义零改动；**暴露协议缺口：dwell seconds 被 SDK BigDecimalAdapter 序列化成字符串恒 400=M4-3 起 dwell 从未送达，待拍板 #24 高优归协议批**；搜索页点卡不进详情同族第四处已补修（一百二十七笔）；reviewer 通过 0P1/P2；毒丸 IO 口径=待拍板 #25）前次：2026-09-08 夜（**任务D D0~D3 四批落地，reviewer 一轮对抗审查通过后逐项 commit**：D0 环境预检 ✅（模拟器/18461 双库 22 件虚构数据/双 App 在位；18461 服务端二进制升级含 cosWork 修复）；D1 图片全屏覆盖层 ✅ 2018f9e（单击舞台→全屏 Dialog 覆盖层，ZoomImageView 链复用，FullscreenOverlayShell 共享外壳单源；选型实测反转两次：Box overlay 被壳层 Scaffold padding+模拟器 inset 卡死→Dialog；Dialog 取焦致系统栏回归→Dialog 自身窗口 insets 控制栏；全屏滑切兄弟落排版态=待拍板 #20）；D2 视频两级全屏+退出恢复竖屏 ✅ 本笔（VideoFullscreenStateMachine 11 单测/固定 LANDSCAPE/防抖 800ms 保留/onDispose 恢复竖屏三路径覆盖/ENDED 末帧重渲染=待拍板 #22；**reviewer P1：旋转设备升级第二级被一级方向锁物理堵死**——第二级入口先行为覆盖层内全屏钮，三候选记待拍板 #23；死 string+isLandscapeVideo 清偿）；D3 胶囊默认收起 ✅ dd1497c（用户 2026-09-07 覆盖 B8：进页默认收起/已激活维 toggle/**切维仍展开**——旧仓库四 Fragment 实录推翻任务书预判，待拍板 #19；顺手修相册/收藏/历史点卡不进详情（onAssetClick 漏传三页+NavHost 三路由）；列表族无批次上下文缺口=待拍板 #21）；证据 %TEMP%\qimeng-d1|d2|d3-evidence\；CHANGELOG 第一百二十~一百二十二笔；**剩余 D4（C8 对照+B6 收尾）→D5（M4-4 离线队列）→D6（M4-3 自查）→D7（M4-7 验收）见《任务D-Android卷.md》§5 进度表**）前次：2026-09-07 夜（**前端维护审查清偿+夜间双卷任务书重组**：Android 对抗审查通过（四笔 3c/3d/B4/B5 复核），新发现 P2 ❤/⭐ 前缀三处分叉+P3 列表族常量三处复制+P3 DwellSessionTracker 连续 pause 边界——同夜全部清偿（TimelineTagColors 单源/ListQueryDefaults 单源/pause 守卫+回归测试，215 单测全绿）；台账 #15 server detail 丢 cosWork 已修（Android 详情标题恢复 cosWork 优先）；App 已切回 18461 虚构实例（今早用户复查曾连真库）；**任务A/B/C 删除，M4 剩余任务全部并入《QimengNAS/任务D-Android卷.md》**（D1=C1 图片全屏、D2=C2 视频两级全屏、D3=C7 胶囊收起、D4=C8 对照+B6 收尾、D5=M4-4、D6=M4-3 整批自查、D7=M4-7），Web 侧=《QimengNAS/任务E-Web卷.md》，双卷文件集互斥可并行；**§4.7 证据协议更新：截图解禁但仅限虚构测试数据（用户 2026-09-07 决策），8420 真库/真机永不截图**）。前次：2026-09-07（**新增独立批次 任务C（回归修复与体验对齐批）任务书=仓库外《QimengNAS/任务C-回归修复与体验对齐.md》**：用户实测 8 项反馈——Android 侧 C1 详情页图片点击全屏缺失（DetailStage 固定宽高比舞台无全屏路径）、C2 视频全屏两级制（先竖屏全屏再可选横屏）+退出详情页方向恢复（用户截图实证退出后 App 卡横屏；VideoStage requestedOrientation 无 onDispose 兜底，旧版规格 GUIDE_UI:195 该句漏实现）、C7 维度胶囊默认收起（AlbumFilterState.expanded 默认 true+切维强制展开，覆盖 M4-2「B8 拍板」口径）、C8 新旧相册截图对照修复（吸收本文件任务A B6 剩余的场景补全/豁免终审）；另有 Web 侧 C3~C6——**该文件同夜重组时删除，内容被任务D/E 卷吸收**）。前次：2026-09-07（仓库外任务文档精简重组：两份派发任务书+开工指引合并为《QimengNAS/任务A-UI对齐.md》（M4-2A）与《QimengNAS/任务B-详情页.md》（M4-3，含 3a~3d 子批拆分），旧文件已删除、本文件引用同步——**A/B 两文件亦于 09-07 夜删除，内容被任务D 卷吸收**）。前次：2026-09-06 晚（**登录免密通道落地，CHANGELOG 第九十六笔**：登录页密码留空即走 `POST /auth/dev-login`——仅服务端 `QIMENG_AUTH_DEV_MODE=1` 时可用（`启动服务端.bat` 与对照环境 18461 已开），未开启时提示改用密码；模拟器/测试验收不再需要输密码；**同日新增独立批次 M4-2A（UI 对齐批）任务书=仓库外《QimengNAS/任务A-UI对齐.md》（2026-09-07 重组并入，原派发任务书-20260906 已删除）**，与 M4-3 并行、文件集互斥（相册/收藏/历史/搜索+core/ui 既有组件 vs feature/detail+home），M4-3 开工前先读其并行边界；对照环境《QimengNAS/ui-compare-harness/》双 App 截图对照就绪）。前次：2026-09-06 下午（昨日审查清偿·app 卷：筛选请求代际防乱序从 Album 一处补齐到同族四 VM（Favorite/History/Search/Home）+ 各配行为测试；token 明文 DataStore 补取舍注释；「QimengCache」TAG 收敛/HomeTab 中文硬编码/refreshFollowed 静默失败/上传 401 终局注释等卫生项，详见 §2 与 CHANGELOG 第九十五笔）。2026-09-06（A-S1 构建接线收敛：模块公共 Gradle 配置抽入 build-logic convention 插件，见 §1 末行）。2026-09-05 夜2（用户三处规格变更落档：①导航 5 Tab→4 Tab——「全部」更名「相册」（route all 不变只改 label）、原「相册」Tab 删除，M4-2 批落地；②M4-3 排版基准 = Web 现版 B站式双栏移动端移植（§3 M4-3「排版基准」块）；③验收证据协议改无截图——一律文本证据（§4 通用约束 7，**2026-09-07 夜更新：虚构数据下截图解禁**）。集群蓝本 = 仓库外《QimengNAS/派发任务书-20260905夜2.md》）。2026-09-04（M4 二次改道定稿：用户拍板「先进方案优先」走 Compose 重建（ADR-0014，废弃同日的照搬路线 0013）；架构标准对齐 Google Now in Android 多模块范式；单机形态（ADR-0015）预留接口）

## 1. 路线定位（2026-09-04 二次改道后）

- **路线（ADR-0014，用户拍板「先进优先、额度不设限」）**：Kotlin + Jetpack Compose 全新实现，交互规格照搬旧项目（旧仓库 `<旧项目目录>\docs\GUIDE_UI.md`，482 行），实现代码全部新写；复杂自绘控件（BiliPlayerView/ZoomImageView）允许 AndroidView 互操作桥接（桥接清单进交付报告）。
- **架构标准（冻结，Now in Android 范式）**：多模块 `:app` + `:core:model/network/data/ui` + `:feature:*`；依赖单向 feature→core；UI(Compose)→ViewModel(StateFlow)→Repository 接口→生成 SDK；Hilt 注入；UI 禁直调网络、禁内嵌业务规则（ADR-0008/铁律 7）。
- **旧项目调研结论仍有效（ADR-0013 遗产，做交互规格参考）**：旧 UI 全部经由单一 ViewModel 持有 repository 接口（解耦范本）；已知旧实现要点——详情页 4 处本地 URI 直消费（图片 :489/播放器 :621/元数据 :747/输入流 :1082）对应新实现须走服务端 URL/元数据；缩略图三级解码管线不移植（服务端出图，ADR-0002 架构）。
- **服务端前置件已就绪**：断点续播 `PUT /assets/{id}/progress`（commit 9773213）、ffprobe 编码元数据、全部领域算法（M3 完成，客户端不复算）。
- **开工前置（每批验收依赖本机服务端在线）**：服务端跑法/管理密码/测试库状态见 `docs/HANDOVER.md`「怎么跑起来」「线上实机状态」两节（双击根目录 `启动服务端.bat`，端口 8420；dev 免密模式只影响 Web 端 LoginGate，App 一律走正常密码登录）；验收涉及真数据页面前先 curl 确认 8420 在线。
- **单机形态预留（ADR-0015，M6 实施）**：M4-1 的「服务器地址」配置是唯一服务端定位点——单机形态只改这一处指向 localhost，UI 零改动。禁止在 ViewModel/Repository 之外散落服务端地址假设。
- **执行环境（2026-09-04 实测就绪）**：Android Studio（jbr JDK 21）+ SDK `<AndroidSdk>`（build-tools 35，compileSdk 所需 platform 用 sdkmanager 补装）+ emulator（WHPX 加速）。**缺 AVD**——M4-0 首步创建。免安装 JDK17 备用 `..\dev-tools\jdk17`。**模拟器启动约定（2026-09-06 用户拍板，headless）**：`emulator.exe -avd qimeng_api35 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot`（无窗口+禁音频；同 AVD 双开会被拒，启动前确认无实例；headless 下 uiautomator dump / adb input / logcat 一切照旧，验收证据协议=文本证据 §4.7）。
- CI 现为四 job，**无 Android job**——M4-0 补第五个。
- **构建接线（2026-09-06 A-S1 收敛，链条尾批·非 M4 批次）**：模块公共 Gradle 配置已抽入 `android/build-logic` included build 的 convention 插件（NIA 范式）：`qimeng.android.application` / `qimeng.android.library` / `qimeng.android.compose`（Kotlin Compose 编译器插件 + buildFeatures.compose + Compose BOM platform）/ `qimeng.android.hilt`（Hilt + KSP + hilt 依赖）/ `qimeng.jvm.library`（纯 JVM 模块，当前 = :core:model），统一收口 compileSdk 36 / minSdk 26 / Java-Kotlin 17。新模块在 plugins 块按需声明 `alias(libs.plugins.qimeng.*)` 即可，模块 build 文件只留 namespace 与依赖差异；真实插件（AGP/Kotlin/KSP/Hilt）版本仍由根 build.gradle.kts 的 apply false 收口，版本锚定唯一事实源 = libs.versions.toml（勿升）。同批 Makefile `app-test` 追加 `:core:model:test`——纯 JVM 模块的测试任务是 `test`，`testDebugUnitTest` 覆盖不到它。

## 2. 批次总表（一批 = 一个执行会话的量，按序执行）

| 批次 | 内容 | 前置 | 规格书节（旧仓库 GUIDE_UI.md） |
|---|---|---|---|
| M4-0 | 工程基建（AVD + 多模块骨架 + 壳导航 + make/CI）✅ 2026-09-05 完成（CHANGELOG 第三十二笔） | 无 | §导航结构 |
| M4-1 | 登录与服务端配置（地址+token，单机形态预留点）✅ 2026-09-06 完成（CHANGELOG 第七十一笔；验收证据=文本协议存 %TEMP%\qimeng-m41-evidence\） | M4-0 | — |
| M4-2 | 列表族（首页/相册(原全部)/收藏/历史/搜索/作者；2026-09-05 夜用户拍板：**导航 5 Tab→4 Tab**——「全部」更名「相册」只改 label（route all 不变）、原「相册」Tab 删除，feature:album 空壳模块一并移除）✅ 2026-09-06 完成（CHANGELOG 第八十三笔；验收证据=文本协议存 %TEMP%\qimeng-m42-evidence\） | M4-1 | §导航结构、§首页、§全部页、§搜索页、§芯片栏配置对比、§万能筛选组件、§药丸容器、§相册出处分区、§COS 模式、§下拉刷新、§浏览历史、§收藏页 |
| M4-3 | 详情页（图片缩放/预加载/沉浸 + Media3 视频手势 + 断点续播 + 时间轴标签 + 互动行）✅ 2026-09-08（3a~3d 交付+D6 整批自查 40ec778 收口：排版七层/cosWork/❤⭐单源/队列链打点全过；暴露 progress 序列化 400=待拍板 #26；GIF 停帧修复） | M4-2 | §详情页、§详情页沉浸浏览、§BiliPlayerView 视频播放器、§缩略图加载 |
| M4-4 | 行为上报离线队列 ✅ 2026-09-08（D5=3034426：Room events 包+三通道触发+dwell 先删后发/毒丸≥3+断网对账闭环+20 单测；dwell 落库受 #24 协议缺口阻塞） | M4-3 | —（事件口径 `docs/DOMAIN_RULES.md` §5） |
| M4-5 | 上传主通道（分享接收/文件选择/队列）✅ 2026-09-06 完成（CHANGELOG 第八十五笔；验收证据=文本协议存 %TEMP%\qimeng-m45-evidence\；上传实测打隔离实例，未触碰 8420 真库） | M4-1 | —（服务端口径 `docs/GUIDE_API.md`） |
| M4-6 | 缓存策略 + 设置页 + 统计页/我的页 ✅ 2026-09-06 完成（CHANGELOG 第八十六笔；验收证据=文本协议存 %TEMP%\qimeng-m46\evidence\；实测打隔离实例 ：18460 真库快照，未触碰 8420 真库） | M4-2 | §缩略图加载策略、§数据统计页、§我的页 |
| M4-7 | M4 整体验收 ✅ 2026-09-08（任务D D7 批：八条+M4-5 五观察项全过，四段门禁全绿（256 tests 0 fail/redocly/golangci 0 issues/oxlint 0 errors）；110 文件 SAF 实测无 ANR；证据 %TEMP%\qimeng-d7-evidence\；遗留记录=批次播种入口缺口（待拍板 #21）、无效原件静默黑屏（#27）） | 全部 | PROJECT_PLAN M4 验收标准 |

**范围**：旧 UI 全部页面族的交互语义（含统计/我的/作者页）；单机生态页（数据备份/数据管理/扫描）无对应服务端能力，不做。

**2026-09-06 审查清偿·app 卷（CHANGELOG 第九十五笔）**：P1「筛选请求代际防乱序」从 Album 一处补齐到同族四 VM（Favorite/History/Search/Home——Home 为排行榜切周期），各配行为测试（14 条，CompletableDeferred 闸门时序构造，分页/刷新防重语义同锁）；卫生项五处——refreshFollowed 读失败不再静默清列表（入 writeError 反馈家族）、HomeTab 文案迁 feature/home strings.xml、「QimengCache」TAG 收敛文件级单源、token 明文 DataStore 取舍注释、上传 4xx/401 终局注释。

**M4-2A UI 对齐批（进行中，2026-09-06 夜起）**：外部界面完全复刻旧版（规格=《QimengNAS/任务A-UI对齐.md》，批 B0~B6）。进度：B0 对照工具+基线报告 ✅（`QimengNAS/ui-compare-harness/`，测试数据已按用户明令全虚构化；2026-09-07 修复 do_tap 空 regex 缺陷并按 P9 校准签名，见 CHANGELOG 第九十八笔）、B1 主题灰系基座 ✅（c9828b5，CHANGELOG 第九十七笔）、B2 相册四模式 ✅（cdef8f1，CHANGELOG 第九十八笔，审查档 `QimengNAS/m42a-review/B2-round1.md`：0 P1 终态）、B3 万能筛选面板 ✅（bba9d47，2026-09-07，CHANGELOG 第一百零三笔，**PROJECT_PLAN M4-2 条目随批勾选**；审查两轮=`m42a-review/B3-round1.md`（1 P1 重置语义走样+5 P2）→ 修复轮清偿 → `B3-round2.md` 复审全项真实清偿+主会话裁定 §7（S1-S4 视觉红 69 条 B6 豁免只免视觉列；入口只相册页=实录判读落档、长按删确认框恢复旧版行为）；面板 8 分区逐字实录、排序/顺位/观看/点击/大小/时间/标签族全参数映射 /assets、标签增删走 /tags 三方法、重置=回默认+应用+关面板三合一、elem_compare S5 结构/文案全绿+S1-S4 假红消除）、B4 搜索页三态对齐 ✅（2026-09-07，CHANGELOG 第一百零七笔；审查两轮=`m42a-review/B4-round1.md`（1 P1 返回族走样+1 P2 列数持久化走样，reviewer 亲读旧仓库 SearchFragment L253-270 铁证：返回/箭头同链清词回入口态）→ 修复轮清偿 → `B4-round2.md` 复审通过；三态逐字复刻+删筛选芯片/刷新（固定 includeCos=true 纯 q）+列数内存态 3 列起步+BackHandler 三态内消化未碰 NavHost；harness 增 S6/S7/S8 场景+text op，结构文本层全对齐）、B5 收藏/历史跟随+空态 ✅（2026-09-07，CHANGELOG 第一百零八笔；审查=`m42a-review/B5-round1.md`（唯一 P2=history 空态测试缺失）→ 清偿复核通过；两页悬浮化迁移+TitleRow 可选 onBack/列数（主会话四项裁定：返回钮加参/列数图标不显示/缩放不接/历史清除=协议缺口只记录）+QimengFourDimSection 退役；S1/S2 对照零新增红）→B6 总验收 未开始（**2026-09-07 夜起 B6 剩余（③场景补全/④交付报告五项/⑤自审链）并入《QimengNAS/任务D-Android卷.md》D4 批执行**，豁免双审档仍在 `m42a-review/B6-exemption-draft.md`）。接手者读《任务D-Android卷.md》§2 批次表 + `m42a-review/` 审查档。

**任务C 回归修复与体验对齐批（2026-09-07 派发；**同夜重组：任务书已删除，Android 侧 C1/C2/C7/C8=《QimengNAS/任务D-Android卷.md》D1~D4，Web 侧 C3~C6=《QimengNAS/任务E-Web卷.md》E1~E4**）**：夜间队列=任务D/E 双卷并行（D：C1 图片全屏→C2 视频两级全屏+退出恢复竖屏→C7 胶囊默认收起→C8 对照+B6 收尾→M4-4→M4-3 自查→M4-7；E：C3~C6→Web 回补→vitest）；关键拍板（视频全屏两级制/偏好只留预设/胶囊默认收起/C8 截图对照例外）已并入任务D/E §1；开工前 git status 确认无在跑会话半成品。

## 3. 各批次任务书

### M4-0 工程基建（多模块骨架）

- **AVD**：创建 API 35 x86_64 模拟器一台（WHPX 可用），启动验证。
- **Gradle 多模块（冻结结构）**：`:app`（壳/导航/Hilt 装配）+ `:core:model`（纯 Kotlin 数据类，映射 SDK DTO）+ `:core:network`（SDK 封装+OkHttp/AuthInterceptor）+ `:core:data`（Repository 接口+实现）+ `:core:ui`（主题 token/共享组件）+ `:feature:home/all/album/favorite/history/search/author/detail/stats/settings/upload`（空壳起步，逐批填充）。**include `:sdk`**——`android/sdk/` 是 `make sdk` 的生成物（**git 忽略、CI checkout 后不存在**）：工程接线第一步先跑 `make sdk` 生成它，此后任何"缺文件"都重跑 `make sdk` 解决，**禁止手改/手写其内容**（铁律 1）。版本目录 `gradle/libs.versions.toml`；AGP/Kotlin/Compose BOM 以执行时官方稳定组合为准（铁律 8：先读官方文档，Now in Android 仓库为结构参照）。
- **应用图标**：旧仓库 GUIDE_UI §应用图标 有完整资产规格，M4-0 一并接入（adaptive icon 一套，minSdk 26 起步）。
- **冻结决策**：namespace `media.qimeng.app`（模块后缀如 `media.qimeng.app.core.data`）；minSdk 26 / compileSdk 以官方最新稳定为准；依赖白名单 = ADR-0014 技术栈清单（Compose M3/Hilt/Navigation Compose/Coil 3/Media3/Room/DataStore/WorkManager）——**白名单之外的新库停手问用户**。
- **壳层**：单 Activity + Navigation Compose + 底部导航骨架（占位页，结构对照 GUIDE_UI §导航结构）+ 主题（Material 3 动态色彩可选，色板对齐品牌主色 #4250af 系——具体色值执行时与 Web 端 prototype.css token 对照换算，写入 :core:ui 主题常量）。
- **构建接线**：`android/local.properties` 指向本机 SDK；Makefile 增 `app-build`（assembleDebug）/`app-test`（testDebugUnitTest）/`app-lint`（lintDebug）；CI 增 Android job——**第一步必须 `make sdk` 生成 android/sdk**（照抄 sdk-chain job 的 setup-java 模式；runner 的 Android SDK 预装情况以 GitHub Actions 官方文档为准）。
- **文档同步项（随本批交付）**：CI 从四 job 变五 job 后，同步 `docs/PROJECT_PLAN.md` 头部「门禁 = CI 四 job」与 `docs/HANDOVER.md`「怎么跑起来」的「四道门禁」字样为五。
- **验收**：`make app-build && make app-test && make app-lint` 全绿；模拟器安装启动进壳导航（截图）；`make lint` 全绿；CI 五 job 全绿。
- **存疑停手**：AGP/Kotlin/Compose 版本组合冲突无法按官方文档解决；`:sdk` 模块编译不过且根因在生成物（禁手改，回报走协议侧）。

### M4-1 登录与服务端配置（单机形态预留点）

- **交互（冻结最小版）**：登录页两字段——服务器地址（记忆上次，占位 `http://192.168.x.x:8420`；**支持 localhost/127.0.0.1 形式**，为 M6 单机形态留口）+ 密码 → `POST /auth/login` → token 存 DataStore；提交前 `GET /api/v1/healthz` 连通性探测（协议面路径；根路径 `/healthz` 是运维别名不入协议面）；错误中文文案（地址不通/密码错分开）。模拟器访问宿主机服务端用 `http://10.0.2.2:8420`（回路地址，注释说明）。
- **架构**：AuthRepository（地址/token 读写与清除，:core:data）+ OkHttp AuthInterceptor（Bearer 注入；401 清 token 发事件跳登录——拦截器不直接导航）；服务器地址经 `ServerConfigDataSource` 单点管理（**全 App 唯一服务端定位点**，ADR-0015 预留）。
- **验收**：模拟器登录本机服务端成功进壳、杀进程重启仍登录、退出登录回登录页（三截图）；单测（token 存取/401 事件流）。
- **存疑停手**：无。

### M4-2 列表族页面

> **2026-09-05 夜用户拍板（导航四化，本批落地）**：底部导航 5 Tab→4 Tab——「全部」Tab 更名「相册」（只改 label 与图标语义，route `all` 与页面规格不变，TopLevelDestinationTest 同步更新）；原「相册」Tab（ALBUM 目的地）删除，feature:album 空壳模块从 settings.gradle.kts includes 与目录一并移除。合并后的「相册」页内容口径 = 原「全部」页规格 + Web 相册四维胶囊（分区/作者/角色·作品/类型——原相册 Tab 的 COS 能力由分区维 全部/常规/COS 承接，首页 cos tab 亦在）。

- **页面（Compose 复刻，交互逐条对照规格书）**：首页推荐流（`GET /recommendations` 分批）+ 全部/相册（四维胶囊筛选，**对齐 Web 端相册口径**（HANDOVER_UI「第五笔」，2026-09-03）：partition 恒显式传、作者行=出处分组∪COS 作者、角色行=角色∪COS 作品、`GET /assets/facets` 候选）+ 收藏/浏览历史/搜索（FTS5 `q`+类型/标签筛选）+ 作者页（关注 toggle）。下拉刷新（GUIDE_UI §下拉刷新语义）、日期分组（dateLabel 规则照规格书）。
- **图片**：Coil 加载签名缩略图直链（sm 档）；网格列数自适应（LazyVerticalGrid）；占位/失败占位统一；**动图缩略图行为实测**（服务端若静态化且规格书要求网格动画，报用户拍板服务端调整——对照旧版行为）。
- **架构**：筛选状态机放 ViewModel（StateFlow），单测锁定（分区/作者/角色/类型组合→请求参数映射，「其他」桶恒排末位，切分区清下级行选择）。
- **验收**：真数据渲染+筛选/搜索/刷新对照 GUIDE_UI 语义逐项自检（清单进交付报告）；分页实测；三件套命令绿。
- **存疑停手**：规格书交互与服务端口径冲突（停下问用户）。

### M4-3 详情页（图片 + 视频 + 互动，M4 最重批次）

> **进度（2026-09-07 凌晨，B 会话 GLM-5.3-Flash）**：**M4-3 四子批代码全部完成**——3a 骨架与排版 ✅（412e81a，续前会话半成品+审查两轮；文本证据 `%TEMP%\qimeng-m43-3a-evidence\`）、3b 图片态 ✅（4a7c67f，ZoomImageView/GpuInfo 桥接+Size.ORIGINAL+预加载窗口+沉浸）、3c 视频态 ✅（19f278d，media3 1.8.0 双来源锁版+BiliPlayerView 桥接+G1~G9）、3d 续播+标签+打点 ✅（本笔，策略层 21 单测+VM 接线+dwell 分段累加口径+configChanges）。待办：整批自查+隔离实例写操作数字核对+M4-7 前置观察项（见任务B文档 §6）。上报项：服务端 cosWork 缺陷、openapi:951 dwell 注释勘误——《待拍板-20260905夜2》#15/#16。

> **排版基准（2026-09-05 夜用户拍板：详情页使用 Web 现版显示排版移植到 Android）**：布局结构以 web 端 `pages/AssetDetailPage.tsx` 现版（B站式双栏，commit db6ac73）为唯一基准做移动端移植——竖屏单列堆叠顺序：媒体舞台 → 标题（cosWork ?? fileName）→ meta 行（浏览·播放·大小·尺寸·日期·出处）→ 点赞/收藏互动行 → 标签行 → 作者卡（displayName + ·COS + 关注）→ 「接下来播放」同类型推荐栏（缩略图+时长角标+两行标题，排除当前资产）。本节其余手势/播放器/续播/打点规格照旧（备忘录 G1~G9 为准）。

- **图片（对照 §详情页/§详情页沉浸浏览）**：双指缩放、双击还原/放大、左右滑相邻预加载（窗口计算 ViewModel 单测）、沉浸模式（systemBars 隐藏+退出手势）、批次导航。原件签名直链（"查看永远发原件"）；Coil 下采样不禁用（内存保护）但**不请求缩放副本**。ZoomImageView 手势语义可用 Compose 自写（transformable/gesture）或 AndroidView 桥接旧控件，桥接则列入交付报告。
- **视频（对照 §BiliPlayerView）**：Media3/ExoPlayer + 手势复刻——单击暂停/恢复、长按 2x（松开还原）、滑动指示器（亮度/音量/进度，规格书有则复刻）、倍速菜单、静音（App 默认音量以规格书为准，未写则默认有声并在交付报告标注请用户拍板）。BiliPlayerView 837 行旧控件允许 AndroidView 整体桥接（推荐：手势层成熟），或 Compose 重写——执行时按复刻成本定。**断点续播**：进页取 detail 的 `lastPositionSeconds`（协议字段，openapi AssetDetail）为起点——**已看完判定冻结**：`lastPositionSeconds >= durationMs/1000` 时视为已看完，从 0 重播并显示已看完徽标（徽标由客户端推导，协议明文）；播放中每 5s 节流 `PUT /assets/{id}/progress`（具名常量，严于协议建议值 10s、协议允许；暂停/离开立即上报）。**时间轴标签**：进度条打点+点击 seek 跳转回看（旧版核心体验，必须实现）。**编码兼容提示**：videoCodec 不兼容清单（口径对齐 Web 端 `AssetDetailPage.tsx` INCOMPATIBLE_CODECS）显示中文提示、不转码。
- **互动行**：点赞/收藏 toggle + 标签管理流（当前分组/其他分组/新建，对照规格书）。
- **打点**：open（进入）/play（起播）/dwell（停留）本批直连上报，代码注释 `TODO(M4-4): 改走离线队列`。
- **验收**：图片/视频手势/续播/标记跳转/互动各一截图；预加载窗口与进度节流单测；三件套绿。
- **存疑停手**：某手势规格书有但 Media3/Compose 无直接支撑（自定义层可解则做，复杂到影响批次规模停下报）。

### M4-4 行为上报离线队列

- **架构（冻结）**：Room 事件队列表（id/assetId/kind/startedAt/durationMs/sessionId/createdAt）+ 写入即入队 + 补传器（应用启动/回前台触发 + WorkManager 周期兜底——**兜底周期 ≥15min**，系统对周期任务有 15 分钟最小值钳制，短值会被静默放大，间隔常量注明）；无批量端点则逐条串行（并发=1）。M4-3 直连打点全部改走队列。
- **去重与累加语义（照 DOMAIN_RULES §5 实口径，防时长虚增）**：服务端仅对 open/play 按「同一会话内只计一次」去重；**dwell 是累加语义、服务端不去重**——客户端必须保证一次停留恰好产生一条 dwell 事件（进入计时、离开/暂停 flush 兜底，禁止重复 flush）；客户端不做任何聚合算法。队列环形上限 5000 条（常量+注释）。
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

**验收自查清单（2026-09-06 夜2 返工批预置，验收时逐条勾并贴文本证据——证据协议见 §4.7，禁截图/录屏）**

对照 PROJECT_PLAN M4 验收标准：

- [ ] 完整日常使用：登录→首页推荐/相册四维筛选/收藏/历史/搜索→详情（图片缩放/视频播放+倍速+时间轴标记）→收藏/点赞，模拟器全流程走通（交互自检清单+uiautomator 文本树）
- [ ] 上传→Web 立即可见：相册分享 2 图→选库选目录→完成通知→Web 端 8420 立即可见（curl JSON 两端对照）
- [ ] 离线行为不丢：断网浏览/点赞/收藏→恢复网络→行为补传核对（logcat + 服务端数据）
- [ ] 门禁全绿：`make app-build && make app-test && make app-lint` + `make lint`（全仓）+ CI 五 job 绿（交付报告逐条贴结尾输出原文）
- [ ] 生成物不手改：`android/sdk/**`、`*.gen.go`、web generated 均由生成链产出，`git status` 无手改痕迹
- [ ] 新迁移只加文件：M4 不应产生服务端 migration，出现即跑偏（核查原因并报告）
- [ ] 决策先写 ADR：M4 期间新增重大决策逐条核对 `docs/adr/INDEX.md` 有对应编号文件
- [ ] 交互规格对照：GUIDE_UI 复刻走样抽查（相册筛选联动/视频手势/图片缩放三项），复用各批交付报告对照清单

纳入 M4-5 审查观察项（待拍板 2026-09-05 夜2 §12，审查结论=通过，验收时逐条补证）：

- [ ] content:// 授权持久化：进程回收后残余重试终局失败场景，需要时补 takePersistableUriPermission
- [ ] dataSync FGS 6h 限时：Android 15+ 前台服务系统限时对长上传队列的影响评估
- [ ] 分享路径通知权限：用户未授通知权限时上传进度的降级反馈（App 内队列页兜底）
- [ ] 取消上传路径：队列无用户取消单任务入口的现状确认（缺口入 CAPABILITY_MAP 候选）
- [ ] 100+ 文件多选：SAF 多选大清单实测（分批/内存/上传耗时）

## 4. 全批次通用约束（冻结，逐批执行前重读）

1. **架构铁律（ADR-0014）**：多模块单向依赖（feature→core，禁反向）；UI(Compose)→ViewModel(StateFlow)→Repository 接口→SDK；UI 禁直调 SDK/网络、禁内嵌业务规则；新依赖全走 Hilt 注入（AppContainer 手写单例禁止）。
2. **协议纪律（铁律 1）**：禁手改 `android/sdk/**`；接口改动先 `api/openapi.yaml` → `make sdk` → 三端适配。
3. **依赖白名单**：ADR-0014 技术栈清单；版本全走 `libs.versions.toml`；新库与版本号**必须当场查官方来源并锁版本**（禁止凭训练记忆写版本号；交付报告附官方来源链接），锁后本批次内不得升级；白名单外停手问用户。
4. **交互规格**：GUIDE_UI.md 对应节为唯一规格书；**需求级结论另读 `docs/LEGACY_REQUIREMENTS.md` 中标注 M4 的条目**（标签管理/搜索维度/浏览播放交互/空态健壮性/性能体验——与 GUIDE_UI 冲突时停手问用户）；复刻走样按 Web 端 HANDOVER_UI §4.5 教训管理——**先测后交、交付附对照清单**；规格书与 Web 现版冲突停手问用户。
5. **单机形态预留（ADR-0015）**：服务端地址只经 M4-1 的 ServerConfigDataSource 流转；任何模块禁止另行假设服务端位置。
6. **代码卫生（AI_README_FIRST）**：魔法值零容忍；同一字面量第 2 次出现提常量；调度参数禁内联；方法超一屏拆；中文注释写"为什么"。
7. **验收命令（每批必全绿，且必须贴证）**：`make app-build && make app-test && make app-lint` + 本批新增单测 + `make lint`（全仓）——**交付报告逐条粘贴各命令的结尾输出原文**（成功/失败都要贴），未贴输出不算交付。**证据协议（2026-09-07 夜用户更新：截图解禁但仅限虚构测试数据——全部验证只用 18461 对照实例/自建隔离实例的虚构数据；8420 真库与真机永不截图；此前 2026-09-05「一律禁截图」口径作废）**：实机/模拟器/UI 验收证据=截图（虚构数据）+ `adb shell uiautomator dump` 文本层级树、logcat 关键行、curl JSON、单测输出、浏览器 DOM 数值实测（可复核优先）；证据目录 `%TEMP%\qimeng-<批>-evidence\`；M4-7 全流程录屏=交互自检清单+文本证据+虚构数据截图。
8. **文档同步（铁律 10）**：每批完成更新本文件批次表勾选 + CHANGELOG 条目（真实模型署名）；协议改动同步 GUIDE_API/DOMAIN_RULES；commit 格式 `类型(app): 简述 | 文档: 已更新XXX`，代码+文档同一 commit，提交前 `git pull`。
9. **测试纪律**：ViewModel/状态机/映射/纯逻辑必须单测；UI 以实机操作自检清单代验（清单进交付报告）。**全程不碰音量、默认静音**（2026-09-06 用户拍板：模拟器 -no-audio 启动，任何播放类测试也不调音量）。
10. **存疑停手总则**：环境装不动/版本冲突/规格冲突/需要用户拍板的取舍——停手，交付报告写清现象、已试方案、候选方案，等用户；**禁止带病交付**。
