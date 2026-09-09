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

（完）

