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
- SDK 隔离（2026-10-24 词表批）：feature 生产源码零 `media.qimeng.sdk` 引用（`:app` 单测 FeatureSdkIsolationTest 扫描强制）；编辑域模型（词表 `core:model` 的 EditableVocabulary 族）在 `:core:data` 边缘与生成 SDK 双向映射（VocabularyEditMappers），页面/VM 只见端内非 null 模型
- 当前交互约束见本仓 [GUIDE_UI](../docs/GUIDE_UI.md)，旧仓规格仅历史参考；实现全部 Compose 新写（禁搬旧 Kotlin，AndroidView 桥接例外记交付清单）

## 单机形态（ADR-0015；形态 B 已落地——任务U11 批次D，2026-09-15）

服务器地址只经 `ServerConfigDataSource`（M4-1）单点流转，支持 localhost。**内嵌形态 B**：
`:core:data` 的 `embedded/` 包（EmbeddedServerConfig 装配纯逻辑 / EmbeddedServerService
薄前台 Service（specialUse，W^X 只 exec nativeLibraryDir 成品、回环 18430 单值互指）/
EmbeddedServerController 注入点）+ 设置页本机模式切换与壳层冷启动自拉起；三件套装配
`make app-embedded-*`（供应链哈希锁见 deploy/embedded/README.md，jniLibs 不入 git）。
M6 基座历史验收已完成；近期变更未因此自动取得新版本实机验收，当前范围见 HANDOVER。

媒体扫描由同一 Go 服务端负责：手机本机模式更新内嵌 APK 后使用扫描优化；连接电脑/NAS 时由对端执行扫描，客户端不重复遍历或计算。调度、周期配置和资源边界统一见 [服务端扫描说明](../server/README.md#媒体库后台扫描)，当前装机来源及验证范围见 [HANDOVER](../docs/HANDOVER.md)。

## 缩略图表示版本与备份兼容

稳定键继续只剥 exp/sig，保留 size/v。预取 revision 快照携带 thumbnailVersion，DataStore 与上次完成 revision 同次写入；策略指纹变化或旧记录无指纹时重拉，避免策略升级后错误跳过。当前仅接收v2备份，BackupValidator写前拦住旧v1/缺失/未知版本及缺事件数组，确认摘要按原始事件数展示；生成SDK和域句柄透传不变。旧聚合回放已退役（ADR-0050），已有legacy/无键历史身份仍保留；精简版已同签名覆盖原手机，当前来源与现场验证见HANDOVER。

## 构建与设备验证

应用 versionName 读取 `server/internal/buildinfo/version.txt`，versionCode 读取相邻 `android-version-code.txt`；发布时 AI 同步递增安装序号，签名和 applicationId 保持。源码版本不能代替已安装 APK 的包信息/字节核对，内嵌 Go 的提交身份也须单独确认。主要持续支持路径为 arm64 手机内嵌与连接服务端；qimeng_api35 主要验证连接模式，x86_64内嵌尚未通过验收，已有SQLite初始化SIGSYS记录，不因本轮维护移除或假称已修复。

外观优化见 [Android 背景对比度记录](../docs/ANDROID_UI_TASKBOOK.md)：日间页面与卡面均为均匀 #FFFFFF，移除冷灰渐变和双向高光，SoftDaylight 只保留低强度中性灰影（Compose 原生 API，无新依赖），辅助面使用中性灰；两档次级文字提高可读性，系统栏跟随 App 主题。首页顶行三处入口、推荐/COS/排行榜三胶囊及相册维度/值胶囊仅复用日间卡片轻影，恢复原白色玻璃，撤去灰色染色与硬描边；真采样/静态分支共用修饰符，其他玻璃件不变。统计详情的摘要、洞察、分布/榜单外卡及内部排行行同样复用轻影，避免白卡融入白底。仅保留“外观”改名，布局/比例/动效改造已取消；不改原件或服务端缩略图。

现用arm64手机已用192ebaaa同源云端Release保留数据更新至0.2.1/code9，同源CI/安全/文档与Release单测/Lint、签名/内嵌身份/三件套/安装字节、首次安装时间保留、冷启动和健康均已核验；用户本次不等待新增Codex审查，不等于最终审批通过或手机业务全回归。现用版本与现场证据统一见[HANDOVER](../docs/HANDOVER.md)。真实媒体未用于删除测试；qimeng_api35 保持关闭，x86_64 SQLite SIGSYS 未修复，手机备份恢复、业务全回归、长期后台和 NAS 未验收。

内嵌装配见 [deploy/embedded](../deploy/embedded/README.md)，模拟器见 [emulator-verify](../deploy/emulator-verify/README.md)。必须显式指定 `qimeng_api35` 或用户指定真机；严禁向雷电 `emulator-5554` 安装/启动本项目或执行 wm/settings。编译、单测、实机/NAS 验收分别记录，平台测试限制见 HANDOVER。

## 持久上传的目标归属

入队保存目标 base URL，不保存 token；直传、分片和挂靠使用同址的请求工厂并关闭重定向。切服后尚未发送的请求拒绝；旧记录无目标时显示失败，用户确认目标后重新入队。业务目标拒绝不取消后续队列，已发请求可能在原端完成。URL 不能识别同址更换实例；定向单测与 Kotlin 编译已验证，设备重启/切服场景仍待验收（ADR-0042）。

## 前台恢复与分片把手

归档一键上传：本机上传保留归档，PC/NAS 确认入库后清理已上传的手机归档副本。清理前核对归档范围与入队 size/mtime，拒绝链接；文件变化、旧载荷缺时间或清理失败则保留并在完成通知/持久队列提示，不重传文件。确认弹窗说明释放行为，范围与已知竞态见 SECURITY「客户端归档与服务端文件移动」。

前台健康恢复由 ChildHealthRecovery 编排：无句柄/已退出时补启动，单次超时先等候再复核，两次失败才重启；检查期间换代不动旧快照，Service 在启动锁内再次核对身份后才摘看护。探测在 IO 线程，取消可释放单飞门；不承诺后台永久在线或长期冻结来源已定位。

分片把手与目标地址保存在既有客户端 DataStore，App 进程重建后 GET 权威 offset 续传；新建会话先提交把手再发字节。成功后的清理失败不能转上传重试。未完成会话重启/过期仍按 404 重建；新任务用持久 localId 作为 uploadId，服务端完成凭据七天内可跨重启重放同一资产。源修改时间从 SAF/MediaStore/文件系统读取，未知省略，随 WorkManager 载荷固定；归档条目同样透传。取消采用 DataStore，提交成功才发布取消状态；Worker 发送前恢复，WorkManager 确认终态后清理，写入失败提示重试。服务端提交前中断、凭据过期和客户端挂靠/归档后处理不等同于全链路恰好一次。

SSE 客户端读空闲超时为 60 秒（服务端 15 秒心跳的四周期）；注释心跳字节同样续期，静默超时沿既有退避重连，并在重连成功后失效缓存。保留五分钟 TTL 及前台恢复兜底，不承诺系统冻结时仍按墙钟准时调度。用户本轮已恢复云端验证与双端更新，实际结果见 HANDOVER。

## 维护定位

首页/搜索的 suspend 业务调用经 `core:data/util/runSuspendCatching` 区分取消与普通失败；库管理及其充电扫描调用链显式重抛取消。列表写回和 loading 清理都检查请求代际，返回搜索入口作废旧代；库列表在途时合并保留一轮后续刷新，写入成功后的复核不能被旧 GET 吞掉；注册成功先记录，再触发扫描，后续取消不伪报注册失败。此纪律不要求替换纯解析/平台探测的普通 `runCatching`。

作者 TXT 读取在可注入的 `core:data/authorimport`，UI 只交 URI。读前校验扩展名/声明大小，流至多读取 1 MiB+1，再用实际 SDK JSON 字节核对 1 MiB 请求上限（含文件名与转义）；大小未知或假报仍受流上限保护。保留既有 UTF-8 解码（BOM 不特判、非法字节替换），不增加编码支持。合成流/JVM 与浏览器验证不代替 SAF provider/设备验收。

统计主/详情页的窗口总量、互斥分类、有浏览文件/作者/标签总数均直接读服务端；多系列趋势一次读取同轴维度，不再逐维请求或用均值/榜单长度反推。并发取数由请求代际与取消保护，失败显示明确错误和同档重试。SDK 缺少新统计字段时提示同步更新服务端，不回退伪零值。

统计主页、分类型及来源趋势共用 QimengTrendLineChart：显示折线、面积、数据点与日期标签，不绘制背景水平网格线；此次仅改变 Android 图表渲染，不改变事件、窗口或统计公式。

首页默认 COS 与常规推荐共用同一取数/分批/换轮状态机，分别保留 seed 和清单，COS 请求显式 `cosOnly=true`。下拉换批不报“已是最新”；推荐公式未变，小库/评分约束下仍可能同序。非默认筛选/排序继续使用 COS 资产游标列表，重置面板恢复推荐；切模式、刷新及缓存标脏都会作废旧代响应，保留常规/COS 隔离。

- 导航：QimengNavHost 保留会话分支、路由装配与常驻层 owner/saveable 状态；Routes 管路由/编码，TabNavigationPolicy 管点击防抖/回顶/顶层跳转，ResidentTabScreen 与 ClassicBottomBar 只渲染各自部分。恢复/隐藏屏生命周期与触摸隔离顺序保持。
- 详情：DetailUiState 独立声明；DetailTagEditor 接受 VM 的同一 StateFlow、scope 和详情重拉回调，集中标签草稿/乐观解绑/失败回滚，不增第二份资产态。生命周期、批次与播放打点仍归 DetailViewModel。
- 播放：VideoStage 持有播放器/状态机/方向与 surface 交接，TimelineTagDialogs 仅接位置快照与动作，不读取播放器、不建第二实例。
- 上传：SdkUploadRepository 负责 SDK 与 WorkManager 入队，UploadQueuePresenter 只做 WorkInfo→显示状态和顺序/名称映射；业务失败、取消、挂靠失败保持原终态。新任务的纯文件名 tag 随 WorkManager 保存，重建后未执行条目也可显示原名；旧任务继续原回退，不放源 URI/路径或凭据。

这些是职责提取，不引入新框架或跨 ViewModel 状态同步。导航装配与播放器控制仍有规模，需要按实际改动继续收敛；不按行数机械切分，也不宣称技术债归零。无视觉/手势/协议规则改变；可见设备回归仍须单独执行。

视频增强能力：现有 Media3 原片播放未接入视频超分或插帧；这需要播放器帧/GPU 管线适配，不等于 Go 动态插件加载。用户已决定暂缓，当前不安排实现；状态与触发条件见 [能力地图](../docs/CAPABILITY_MAP.md)，没有引入增强 SDK 或变更播放内核。


## 规模与状态所有权审查

Q08已将统计详情的无状态展示行/文案提取到StatsDetailPresentation；VM与页面交互状态不转移。播放器、详情页、首页与导航保留单一资源/状态owner的具体理由、当前行数和测试边界见[任务书结构结论](../docs/OPTIMIZATION_TASKBOOK_20261025.md)。策略/VM单测、编译与Lint不代替手势/窗口设备验收；已登记AndroidView桥接和冻结参数保持。
