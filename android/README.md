# android/ — Android 客户端（M4 · Compose 重建路线，ADR-0014）

> M4 里程碑在此开发。动手前必读：`../AI_README_FIRST.md`、`../docs/HANDOVER.md`（现状）、`../docs/adr/0008`、`../docs/adr/0014`（技术路线）、`../docs/adr/0015`（单机形态预留）；历史决策见 `../docs/CHANGELOG.md`。

## 设计语言「流光玻璃」（2026-10-02 起，ADR-0031）

- 视觉体系：暗色第一基准三元强调色板（鸢尾/雾岚/极光青 + 氛围辉光四色）+ 大圆角（媒体卡 18dp/面板 24dp/胶囊 100dp）+ 标题族 SemiBold；token 单源 `core/ui/theme/`（Color/Theme/Type/Shape/Dimens）。
- 玻璃件单源 `core/ui/glass/`：GlassSurface/GlassCard（分层半透明+受光描边+高光纱+投影）、AuroraBackdrop（自绘径向辉光极光底，壳层全局唯一景深来源）、GlassNavBar（底栏坞）、QimengMotion（pushed 路由 spring 转场；**Tab 切换恒 snap**，常驻层防闪烁机制不可动）、pressScale/GlassIconButton（按压微交互）。feature 层禁止自绘玻璃/胶囊/按压动画，一律引用 glass 包。
- 降级策略：玻璃不依赖 backdrop 采样模糊（官方无此 API）；`Modifier.blur` 只用于自有内容且 API<31 自动 no-op，全部玻璃件天然回落「半透明纯色层+阴影」，无需分支代码。
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

## 单机形态（ADR-0015；形态 B 已落地——任务U11 批次D，2026-09-15）

服务器地址只经 `ServerConfigDataSource`（M4-1）单点流转，支持 localhost。**内嵌形态 B**：
`:core:data` 的 `embedded/` 包（EmbeddedServerConfig 装配纯逻辑 / EmbeddedServerService
薄前台 Service（specialUse，W^X 只 exec nativeLibraryDir 成品、回环 18430 单值互指）/
EmbeddedServerController 注入点）+ 设置页本机模式切换与壳层冷启动自拉起；三件套装配
`make app-embedded-*`（供应链哈希锁见 deploy/embedded/README.md，jniLibs 不入 git）。
真机 arm64 全链验收 = 任务T T7 用户节点。
