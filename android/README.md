# android/ — Android 客户端（M4 · Compose 重建路线，ADR-0014）

> M4 里程碑在此开发。动手前必读：`../AI_README_FIRST.md`、`../docs/HANDOVER.md`（现状）、`../docs/adr/0008`、`../docs/adr/0014`（技术路线）、`../docs/adr/0015`（单机形态预留）；历史决策见 `../docs/CHANGELOG.md`。

## 设计语言「流光玻璃」（2026-10-02 起，ADR-0031）

- 视觉体系：暗色第一基准三元强调色板（鸢尾/雾岚/极光青 + 氛围辉光四色）+ 大圆角（媒体卡 18dp/面板 24dp/胶囊 100dp）+ 标题族 SemiBold；token 单源 `core/ui/theme/`（Color/Theme/Type/Shape/Dimens）。
- 玻璃件单源 `core/ui/glass/`：GlassSurface/GlassCard（分层半透明+受光描边+高光纱+投影）、AuroraBackdrop（自绘径向辉光极光底，壳层全局唯一景深来源）、GlassNavBar（底栏坞）、QimengMotion（pushed 路由 spring 转场；**Tab 切换恒 snap**，常驻层防闪烁机制不可动）、pressScale/GlassIconButton（按压微交互）。feature 层禁止自绘玻璃/胶囊/按压动画，一律引用 glass 包。
- 降级策略：玻璃不依赖 backdrop 采样模糊（官方无此 API），**当前实现零 blur**——质感与景深全为自绘分层（GlassColors/AuroraBackdrop）；`Modifier.blur` 是未来对自有内容（头图/氛围层）做真模糊的预留策略（API<31 no-op），引入时须按 ADR-0031 补低版本回落。
- 动效：M3 Expressive（MaterialExpressiveTheme + MotionScheme.expressive）管 M3 标准件；自绘动效规范在 `glass/Motion.kt`。

## 技术栈（2026-09-04 二次定论，ADR-0014：先进优先，Compose 全新实现）

- Kotlin + Jetpack Compose（Material 3）+ Hilt + Coroutine/Flow + Navigation Compose
- Coil 3（签名直链：缩略图/原图）、Media3/ExoPlayer（直链播放；BiliPlayerView 手势语义 Compose 复刻或 AndroidView 桥接）
- Room（客户端缓存/事件队列，非数据源）+ DataStore（登录/配置）+ WorkManager（队列）
- make sdk 生成的 Kotlin 客户端（`media.qimeng.sdk`）；namespace `media.qimeng.app`；minSdk 26
- 架构：Now in Android 范式多模块（`:app` + `:core:model/network/data/ui` + `:feature:*`），依赖单向 feature→core

## 职责边界（薄客户端纪律）

- 列表/搜索/推荐/统计：调 API，客户端不复制任何算法——服务端 M3 已实现全部领域算法
- 图片/视频：签名直链交给 Coil / ExoPlayer，客户端不做解码管线
- 行为上报：事件本地排队，联网补传（离线不丢）
- 上传（核心功能）：系统分享接收 + 系统文件（SAF）唯一入口（2026-09-29 直传化）+ 直传/分片双通道 + 归档文件夹一键上传（2026-10-01）+ 队列进度——"手机采集端"主通道
- 本地持久化三类：登录配置、可设上限的媒体缓存（LRU）、事件队列
- 交互规格唯一来源：旧项目（本地仓库外）的 `docs/GUIDE_UI.md`——只继承交互语义，实现代码全部 Compose 新写（禁搬旧 Kotlin；复杂自绘控件允许 AndroidView 桥接，清单入交付报告）

## 云端预览 APK（流光玻璃设计语言装机包，2026-10-02，ADR-0031「预览变体与端口纪律」）

设计语言走查/真机预览的装机包一律走云端 CI 产出（夜间禁止本地构建，验证唯一路径=push 后盯
CI）；每次 push 本分支，ci.yml 的 android job 在既有 debug APK 之外额外产出**预览变体**：

- 开关：`-PqmPreviewAurora=true`（实现与同步责任见 `android/app/build.gradle.kts` 文件头；
  端口注入在 `android/core/network/build.gradle.kts`）。正式构建不传该属性，零变化。
- 与正式包的差异：applicationId `media.qimeng.app.aurora`（与正式包**并存安装**，数据随包名
  隔离）、应用名「绮梦影库·流光」、版本名 `0.2.0-aurora`、内嵌服务端端口 **18431**（正式包
  18430；单源 `ServerAddress.LOCAL_MODE_PORT`，两包同装互不抢端口）。AndroidManifest 的
  `${applicationId}` 占位符不受影响。
- 签名：CI 从 secrets（`QM_AURORA_PREVIEW_KEYSTORE_B64`）还原专用预览 debug keystore
  （仓库外 keytool 生成，绝不入库——铁律14）。固定签名=重复下载可直接覆盖安装；若 secrets
  未配置则自动退回 AGP 随机 debug 签名，**该降级形态下每次下载需先卸载再装**。
- 单测锁定的是正式端口口径（18430）：跑单测/本地开发不带该属性，属性仅供 CI 预览包构建。

取件（gh CLI，产物名 `qimeng-aurora-preview-debug.apk`，artifact 名 `qimeng-aurora-preview-apk`）：

```bash
gh run list --branch ui/app-redesign --limit 5
gh run download <run-id> --repo Surtr42u/qimeng-media --name qimeng-aurora-preview-apk --dir dist/aurora
# 装机（设备纪律见 docs/adr/0031「设备纪律」：严禁触碰 emulator-5554/雷电）
adb install -r dist/aurora/qimeng-aurora-preview-debug.apk
```

## 单机形态（ADR-0015；形态 B 已落地——任务U11 批次D，2026-09-15）

服务器地址只经 `ServerConfigDataSource`（M4-1）单点流转，支持 localhost。**内嵌形态 B**：
`:core:data` 的 `embedded/` 包（EmbeddedServerConfig 装配纯逻辑 / EmbeddedServerService
薄前台 Service（specialUse，W^X 只 exec nativeLibraryDir 成品、回环 18430 单值互指）/
EmbeddedServerController 注入点）+ 设置页本机模式切换与壳层冷启动自拉起；三件套装配
`make app-embedded-*`（供应链哈希锁见 deploy/embedded/README.md，jniLibs 不入 git）。
真机 arm64 全链验收 = 任务T T7 用户节点。
