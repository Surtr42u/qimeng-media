# android/ — Android 客户端（M4 · Compose 重建路线，ADR-0014）

> 本端开发入口；全局规则按 [AGENTS](../AGENTS.md) 读取，现行交互查 [GUIDE_UI](../docs/GUIDE_UI.md)。日常任务不要求通读交接或全部 ADR；架构变化再查 ADR-0008/0014/0015。

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
- 本地持久化：登录/偏好配置、可设上限的媒体缓存（LRU）、离线事件队列和传输任务元数据；权威业务数据由连接的服务端维护
- 当前交互约束见本仓 [GUIDE_UI](../docs/GUIDE_UI.md)，旧仓规格仅历史参考；实现全部 Compose 新写（禁搬旧 Kotlin，AndroidView 桥接例外记交付清单）

## 单机形态（ADR-0015；形态 B 已落地——任务U11 批次D，2026-09-15）

服务器地址只经 `ServerConfigDataSource`（M4-1）单点流转，支持 localhost。**内嵌形态 B**：
`:core:data` 的 `embedded/` 包（EmbeddedServerConfig 装配纯逻辑 / EmbeddedServerService
薄前台 Service（specialUse，W^X 只 exec nativeLibraryDir 成品、回环 18430 单值互指）/
EmbeddedServerController 注入点）+ 设置页本机模式切换与壳层冷启动自拉起；三件套装配
`make app-embedded-*`（供应链哈希锁见 deploy/embedded/README.md，jniLibs 不入 git）。
M6 基座历史验收已完成；近期变更未因此自动取得新版本实机验收，当前范围见 HANDOVER。

## 缩略图表示版本与备份兼容

稳定键继续只剥 exp/sig，保留 size/v。预取 revision 快照携带 thumbnailVersion，DataStore 与上次完成 revision 同次写入；策略指纹变化或旧记录无指纹时重拉，避免策略升级后错误跳过。v2 备份新字段经生成 SDK 透传；勿用旧版 App 转存 v2。

## 构建与设备验证

应用 versionName 读取 `server/internal/buildinfo/version.txt`，versionCode 读取相邻 `android-version-code.txt`；发布时 AI 同步递增安装序号，签名和 applicationId 保持。源码版本不能代替已安装 APK 的包信息/字节核对，内嵌 Go 的提交身份也须单独确认。主要持续支持路径为 arm64 手机内嵌与连接服务端；qimeng_api35 主要验证连接模式，x86_64 内嵌仍为未验收路径，不因本轮维护移除或假称已修复。

外观优化见 [Android 背景对比度记录](../docs/ANDROID_UI_TASKBOOK.md)：日间页面与卡面均为均匀 #FFFFFF，移除冷灰渐变和双向高光，SoftDaylight 只保留低强度中性灰影（Compose 原生 API，无新依赖），辅助面使用中性灰；两档次级文字提高可读性，系统栏跟随 App 主题。首页顶行三处入口、推荐/COS/排行榜三胶囊及相册维度/值胶囊仅复用日间卡片轻影，恢复原白色玻璃，撤去灰色染色与硬描边；真采样/静态分支共用修饰符，其他玻璃件不变。统计详情的摘要、洞察、分布/榜单外卡及内部排行行同样复用轻影，避免白卡融入白底。仅保留“外观”改名，布局/比例/动效改造已取消；不改原件或服务端缩略图。

此前（2026-10-20）arm64 内嵌 Release 已同签名保留数据覆盖装机，并核对 APK 字节、非 Debug 标志、三件套/哈希与冷启动健康/就绪；qimeng_api35 已关闭。该模拟器 x86_64 的 SQLite 初始化 SIGSYS 未修复，不等同于 arm64 手机失败；证据与验证限制见 [HANDOVER](../docs/HANDOVER.md)。

内嵌装配见 [deploy/embedded](../deploy/embedded/README.md)，模拟器见 [emulator-verify](../deploy/emulator-verify/README.md)。必须显式指定 `qimeng_api35` 或用户指定真机；严禁向雷电 `emulator-5554` 安装/启动本项目或执行 wm/settings。编译、单测、实机/NAS 验收分别记录，平台测试限制见 HANDOVER。

## 持久上传的目标归属

入队保存目标 base URL，不保存 token；直传、分片和挂靠使用同址的请求工厂并关闭重定向。切服后尚未发送的请求拒绝；旧记录无目标时显示失败，用户确认目标后重新入队。业务目标拒绝不取消后续队列，已发请求可能在原端完成。URL 不能识别同址更换实例；定向单测与 Kotlin 编译已验证，设备重启/切服场景仍待验收（ADR-0042）。

## 前台恢复与分片把手

前台健康恢复由 ChildHealthRecovery 编排：无句柄/已退出时补启动，单次超时先等候再复核，两次失败才重启；检查期间换代不动旧快照，Service 在启动锁内再次核对身份后才摘看护。探测在 IO 线程，取消可释放单飞门；不承诺后台永久在线或长期冻结来源已定位。

分片把手与目标地址保存在既有客户端 DataStore，App 进程重建后 GET 权威 offset 续传；新建会话先提交把手再发字节。成功后的清理失败不能转上传重试。服务端重启/会话过期仍按 404 重建；已提交但客户端未获成功响应/未提交 WorkManager 终态仍可能重复，未新增服务端幂等协议。取消标记仍为进程内，进程死亡窗口可能恢复已点取消的任务。这些限制与真实重启/切服/长期后台验收分开记录，不能据单测声称全链路无重复。
