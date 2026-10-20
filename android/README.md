# android/ — Android 客户端（M4 · Compose 重建路线，ADR-0014）

> M4 里程碑在此开发。动手前必读：`../AI_README_FIRST.md`、`../docs/HANDOVER.md`（现状）、`../docs/adr/0008`、`../docs/adr/0014`（技术路线）、`../docs/adr/0015`（单机形态预留）；历史决策见 `../docs/CHANGELOG.md`。

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

## 2026-10-19 缩略图表示版本

稳定键继续只剥 exp/sig，保留 size/v。预取 revision 快照携带 thumbnailVersion，DataStore 与上次完成 revision 同次写入；策略指纹变化或旧记录无指纹时重拉，避免策略升级后错误跳过。v2 备份新字段经生成 SDK 透传；勿用旧版 App 转存 v2。

## 外观与设备验证

外观优化见 [Android 背景对比度记录](../docs/ANDROID_UI_TASKBOOK.md)：日间页面与卡面均为均匀 #FFFFFF，移除冷灰渐变和双向高光，SoftDaylight 只保留低强度中性灰影（Compose 原生 API，无新依赖），辅助面使用中性灰；两档次级文字提高可读性，系统栏跟随 App 主题。首页顶行三处入口、推荐/COS/排行榜三胶囊及相册维度/值胶囊仅复用日间卡片轻影，恢复原白色玻璃，撤去灰色染色与硬描边；真采样/静态分支共用修饰符，其他玻璃件不变。仅保留“外观”改名，布局/比例/动效改造已取消；不改原件或服务端缩略图。

验证仅使用指定 `qimeng_api35` Debug 与隔离合成媒体，手机 Release 不替换，8420 真库无测试写入。
