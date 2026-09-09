# UI-NOTES — ui/expressive 分支实验台账

本分支 = 动效实验分支（任务U）。每笔改动记录「改了什么 / 为什么 / 怎么回退」。与主分支（ui/glass）无关，回退操作只对本分支生效。

---

## exp#1 MotionScheme 全局接线 —— ⛔ 停手（未写任何代码）

- **改了什么**：无。`Theme.kt` 零改动。
- **为什么停手**：任务书前提「material3 1.4.0 stable 无需 OptIn、Companion 含 expressive/standard 可直接调用」与锁定版本的实际 API 不符。当场实证（双证据）：
  1. **官方源码**（Maven google 仓 `material3-android-1.4.0-sources.jar`，`commonMain/.../MotionScheme.kt`）：
     - `internal interface MotionScheme`（接口本身 internal）；
     - `internal fun standard(): MotionScheme`、`internal fun expressive(): MotionScheme`（Companion 工厂 internal）。
  2. **官方源码**（同 jar `MaterialTheme.kt`）：`internal fun MaterialExpressiveTheme(...)`（**函数本身 internal**）；`MaterialTheme.motionScheme` 亦 `internal val`。
  3. 本地 Gradle 缓存 AAR（material3 1.4.0）字节码交叉印证：`MaterialThemeKt$MaterialExpressiveTheme$1`、`MotionScheme$Companion.expressive$material3()`（`$material3` 后缀 = Kotlin internal 名改编）。
- **结论**：这不是「需要 OptIn 实验注解」——internal 是可见性边界，`@OptIn` 救不了；升级 material3 又被「勿升任何版本」红线禁止。按「存疑停手，禁带病交付」停手回报。
- **影响面**：exp#2 的 spring 参数暂以组件内具名常量收口（见下），MotionScheme 转正后迁入 `MaterialTheme.motionScheme.defaultEffectsSpec()` 即全局单点。
- **怎么回退**：无需回退（零改动）。

## exp#2 AssetCard 按压 spring 缩放 —— ✅ 已落地（L1 冲突提案点）

- **改了什么**：`android/core/ui/.../component/QimengMediaGrid.kt`
  - `AssetCard` modifier 链新增**一个** modifier：`.assetCardPressScale(pressSource)`（private @Composable modifier 工厂，范式照抄 `QimengSegPill.kt` pressScale：自持 interactionSource + collectIsPressedAsState + graphicsLayer 块内延迟读值不触发重组）；
  - 动效参数：scale 0.97 + `spring(DampingRatioNoBouncy, StiffnessMediumLow)`（androidx 具名常量档），常量收口在同文件头部注释块；
  - KDoc 原「无按下缩放动画…禁止再加缩放修饰」（L1 拍板口径）已改写为分支提案记档。
- **为什么**：任务书指定的**冲突提案点**——网格卡 0.97 轻缩放按压手感 vs L1「旧版无按压效果」。本分支立场=有缩放；回退即可回到 L1 立场。
- **怎么回退**：删 `AssetCard` 链上 `.assetCardPressScale(pressSource)` 一行（函数与常量节随之删），其余零改动。

## exp#3 首页网格卡→详情海报共享元素试点 —— ✅ 已落地（单页族：首页↔详情）

- **改了什么**：
  - `android/core/ui/.../motion/QimengSharedTransition.kt`（新增）：`LocalNavSharedTransitionScope`/`LocalNavAnimatedVisibilityScope` 两个 CompositionLocal + `Modifier.qimengAssetPosterSharedBounds(assetId)` 单点接线（scope 缺位原样返回，渲染零变化）；key 前缀 `asset-poster:` 单源。
  - `android/app/.../navigation/QimengNavHost.kt`：`SharedTransitionLayout` 包住 NavHost（overlay 边界=导航内容区，底栏不参与）；**只**在 HOME 与 DETAIL 两个 destination 内 provide `LocalNavAnimatedVisibilityScope`——试点范围锁死在首页族，其余页面（相册/收藏/历史/搜索等）读到 null 自动不参与。四 Tab 顶层切换的 enter/exit=None 转场参数原样未动（L2 拍板红线）。
  - `android/core/ui/.../component/QimengMediaGrid.kt`：AssetCard modifier 链加 `.qimengAssetPosterSharedBounds(asset.id)` 一行（回退=删此行）。
  - `android/feature/detail/.../DetailScreen.kt`：DetailMediaStage 的 modifier 加同 key 共享边界一段（挂 DetailScreen 单点，VideoStage/ImageStage/播放器桥接件零接触；回退=删此 modifier 段）。
  - `android/gradle/libs.versions.toml` + `android/core/ui/build.gradle.kts`：新增 `compose-animation` 别名（BOM 2026.06.01 管控的 animation 1.11.4，无版本号，非升级）；core:ui 以 `api` 暴露（CompositionLocal 签名泄漏 animation 类型）。
- **为什么 CompositionLocal 而非官方参数直传**：scope 走参数要穿过 HomeScreen→QimengMediaGrid→AssetCard 三层签名，全部调用方连带改动；本地局部量让两端零签名变化接入，且「只在家页/详情页 provide」天然把试点锁在首页族——铺开=壳层多包一个 destination，组件侧零改动。
- **模拟器实测结论**（qimeng_api35，swiftshader 软渲染）：
  - **返回转场（详情→首页）动画成立**：连拍捕到两帧中间态（exp-3-back-6/7.png）——详情舞台边界连续收缩回网格卡 bounds，chrome 同步淡出，首页网格/底栏已就位；四 Tab None 转场参数未动、sharedBounds 的 bounds 动画独立于内容 enter/exit，实证可叠加。
  - **前进转场（首页→详情）通常无可见动画**：详情页数据/海报经 Coil 异步加载，首帧舞台尚未出图（exp-3-fwd-4.png：chrome 已就位、舞台空白）——sharedBounds 即使配对，画的是尚未解码的空舞台（FAFAFA 底），视觉不可感知。这是数据加载竞态，属试点已知限制；若要前进可见，需舞台预载海报帧（后续批次再议）。
  - **视频回归通过**：VideoStage 海报态（播放钮可见）→ 单击起播 → BiliPlayerView 桥接件在共享边界包裹的舞台内正常渲染出控制条（exp-3-video-poster/playing-dark.png）——View 互操作与 LookaheadScope 共存无异常。
  - **日夜两态**走查正常；相册页卡片（未 provide scope）零变化，自动锁定试点范围生效。
  - ContentScale 不参与动画的官方限制：卡=16:9 crop、舞台=contain，首帧存在 crop 跳变（任务书明示可接受）。
- **怎么回退**：删 NavHost 的 `SharedTransitionLayout`+两处 provider、AssetCard 与 DetailScreen 各一个 modifier、motion/QimengSharedTransition.kt 一个文件、catalog 别名与 core:ui 的 api 行即可，其余零改动。
- **干扰记档**：走查期间模拟器被并行任务共享（本分支+玻璃分支代理）：MainActivity 反复被重建（activity token t422→t432 连变）、前台数秒后被拉回 launcher、`uiautomator dump` 因 "UiAutomationService already registered" 崩过一次（logcat 唯一 FATAL，属工具冲突，**非应用崩溃**；应用进程全程零 FATAL）。证据采集均以「am start→查 topResumedActivity→操作→复查→截图」容错重试完成。

---

## R2#0 门禁三连基线（上轮 P2 清偿，2026-09-10，不动代码）

上轮门禁日志未存证据目录被判 P2，本轮补跑并存档。worktree 无头执行，三连全绿：

- `make app-build`：`BUILD SUCCESSFUL in 7s`（562 tasks up-to-date），exit=0 → `%TEMP%\qimeng-ub-evidence\r2-gate-build.log`
- `make app-test`：`BUILD SUCCESSFUL in 3s`（428 tasks up-to-date），exit=0 → `r2-gate-test.log`
- `make app-lint`：`BUILD SUCCESSFUL in 3s`（705 tasks，68 executed），exit=0 → `r2-gate-lint.log`

## exp#4 前进转场竞态修复——预载+占位双翼 —— ✅ 已落地（实测有取舍，见「边界结论」）

- **改了什么**（三文件，均单点小改）：
  - `android/core/ui/.../component/QimengMediaGrid.kt`（预载翼）：AssetCard onClick 内导航前调用 `preloadDetailPoster(...)`——对卡上所持海报 URL 发 Coil 单例 ImageLoader 入队（发射后不管不挂组合生命周期），常量 `DETAIL_POSTER_PRELOAD_CACHE_POLICY=CachePolicy.ENABLED`（具名+为什么）；请求形状对齐详情页既有预载链（视频海报帧=默认档 / 图片动图=`Size.ORIGINAL`），同形才复用同一内存缓存键。
  - `android/feature/detail/.../VideoStage.kt`（占位翼 A）：海报态 AsyncImage 的 **placeholder** 底由 backdrop（主题底）改品牌灰 `secondaryContainer`（与网格卡 QimengThumbnail 占位/错误底同 token）；**error 底保留 backdrop**（K1「对齐旧版海报透出 qmColorBg」口径不扩权）；加载完成后的 letterbox 底仍是调用方打底的 backdrop（K1 单源不动）。
  - `android/feature/detail/.../ZoomableOriginalImage.kt`（占位翼 B）：原图解码完成前舞台以品牌灰 Box 参与（`imageReady` 态驱动，onSuccess 摘除/换资产重置）；就绪后 letterbox 底归 backdrop（K1 不动）。**分支记档**：本件「抽取零行为变化」口径自此在本分支破例一处。
- **为什么**：exp#3 实证前进转场首帧空舞台（海报未解码、色块与页面同色不可感知）。预载翼把「加载」提前到点击瞬间（转场前抢跑）；占位翼保证舞台未就绪期以可辨色块形态参与渲染。
- **模拟器实测结论**（qimeng_api35，swiftshader 软渲染，日夜两态走查）：
  - **预载翼生效实证**（r2both-026.png）：点击→详情加载窗（约 1~2s，加载期首页网格仍可见+「加载中…」）→详情挂载**首帧海报已在**（purple Q 卡+播放钮），无空白间歇。
  - **占位翼生效实证**（r2rec3-041.png + 像素取样）：冷缓存一次实测舞台整屏 #2D2D2D（=secondaryContainer 夜档；旧 backdrop 应为 #1A1A1A），海报就绪后被内容替换。
  - **返回转场不回归**（r2both-071/072.png）：海报块连续收缩回卡片方向、首页底栏已就位、详情 chrome 渐隐——连续两帧中间态，round-1 行为保持。
  - **视频回归**（r2-light-playing.png）：起播→BiliPlayerView 控制条/时间轴标签芯片正常（共享边界包裹的舞台内，桥接件无异常）。**图片舞台**（r2-light-image.png）：原图 fill 居中正常。
  - **日夜两态**：日光详情（r2-light-detail.png，letterbox=FAFAFA）/ 夜间详情/首页均正常。
- **边界结论（实测取舍，重要）**：**前进方向不存在边界 morph 是结构性现象，双翼不能也不应造出**——进入转场启动时 DetailScreen 仍在 `state.isLoading`（舞台未组合、shared key 未注册），数据落地（约 1~2s）晚于转场窗口（enter/exit=None 瞬时），sharedBounds 无从配对；round-1 的「动画在跑但空舞台不可见」推断据此修正为「转场窗口内舞台根本未挂载」。双翼的实际交付=①详情挂载首帧即出海报（预载赢下加载窗竞速）②未赢时舞台以品牌灰色块可见（占位翼）③返回方向 morph 保持。「前进可见连续动画」若要达成需舞台在 isLoading 期挂骨架（占位翼延展），涉加载态结构改动，超出本批「布局零改动」红线，转遗留。
- **干扰记档**（并行玻璃分支代理共享模拟器，均容错重试后完成）：run4 连拍中途前台被拉回 launcher（帧 3 起 r2-run4）；run5 详情页 chrome 被外来点击切隐（帧 5 起渐隐）；图片走查时前台被切到 Calendar 一次（拉回后重拍）。`uimode` 曾被外部翻回夜态一次。
- **怎么回退**：预载翼=删 AssetCard onClick 内 `preloadDetailPoster(...)` 调用+函数+常量节；占位翼 A=VideoStage placeholder 改回 `ColorPainter(backdrop)`；占位翼 B=删 ZoomableOriginalImage 的灰占位 Box 段与 `imageReady`。三者相互独立可单独回退。

## exp#5 共享 key 防冲突加固——网格数据按 id 去重 —— ✅ 已落地（exp#3 铺开前置，上轮 P3①）

- **改了什么**：`android/core/ui/.../component/QimengMediaGrid.kt` 把「分组段→格子」扁平化抽为纯函数 `flattenGridCells(sections)`（`GridCell` internal data class），并在其中**按资产 id 防御性去重**（首现位保留、跨段去重、组头恒渲染不参与）；调用点 `remember(sections) { flattenGridCells(sections) }` 行为等价替换。新增 JVM 单测 `core/ui/src/test/.../QimengMediaGridCellsTest.kt` 四例（重复 id→输出唯一/跨段去重+组头保留/无重复零变化/空段与全重复段组头保留）。
- **为什么选数据整理层而非 HomeViewModel**：LazyVerticalGrid 项 key 与共享元素 key 都在本组件以 asset.id 铸造——重复 id 直接撞 key 约束（崩溃级）或同屏双卡同 sharedBounds key（配对未定义）；服务端无「单响应内 id 唯一」协议承诺，在 key 铸造点收敛一处覆盖全部网格页（首页三流/相册/收藏/历史/搜索/作者集合），且不动数据层语义（批次清单/展示序仍由调用方持有）。顺序保持首现位（distinctBy 语义），正常数据输出逐格相同——零列表语义变化。
- **怎么回退**：调用点改回内联不去重 buildList + 删 `flattenGridCells`/`GridCell` + 删单测文件，其余零改动。

---

（完）

