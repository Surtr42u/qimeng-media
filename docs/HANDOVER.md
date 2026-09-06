# HANDOVER - AI 交接说明

> 写给下一位接手的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 最后更新：2026-09-06 08:51 **晨间收尾：夜2 双车道全部闭合（A 四批+尾批+双轮自审 R1→R2 过；B 七批+加时四批），M4-3 留 09-06 夜首发**——A 链 M4-1 88af1b9 → M4-2 4acb128（导航四化+动图缩略图动画）→ M4-5 294a576（独立审查通过）→ M4-6 944cac5 → A-S1 0ff017a → ci 21813e3 → 自审返工 a105280；M4-3 派发口径=待拍板条目 10，M4-2.1 筛选面板=条目 11，dwell 先删后发=条目 13（明晚 M4-4 前拍）；备忘录三份落 QimengNAS（A-S2 图标自持/A-S3 AGP9 三步走/M4-4 离线队列前置）；`android/启动模拟器-headless.bat` 新建（无窗口+禁音）；8:50 定时收尾自动化已建档（automation-ebea7fc2）。前次：2026-09-06 05:10 B 车道收工（B 链七批+三轮对抗审查通过+晨间拍板后加时四批，至第八十四笔）——B-1 续做收尾 36af094（两 stash 已 pop 落地）→ B-2 动效现代化 e6fac87 → B-3 小清偿 220aba3 → B-4 /assets 目录过滤协议 26c2126（跟进单测 5a472b5；reviewer 通过，曾夹带 A 车道 feature/album 两删除、内容与导航四化一致已落档披露）→ B-5 目录树文件行接线 53ba308 → B-6 M6 备忘录（仓库外 m6-next-steps.md，记账 0e1fd58）→ B-7 漂移检测 a1ca92e；自审清偿两笔 5eebfbf（P3×5）+ 40862ea（make lint 自 8344920 起首次全绿）；待拍板新增条目 1~3（条目 1 文件行数据源已由 B-4/B-5 落地）。前次：2026-09-06 00:50 两会话定稿（**计划落档，执行=用户自开两个持久会话并行自驱**——A=Android M4-1 续做→M4-2(含导航四化)→M4-5→M4-6、B=Web+协议 B-1 续做→B-2 动效→B-3 清偿→B-4 /assets 目录过滤→B-5 目录树接线→B-6/B-7 调研记账；**每会话 ≤3 并发子代理、不设账号级上限**，撞限流等 2 分钟重试兜底；计划会话只出计划书不执行，其试派的首批执行子代理已于 00:15 全部叫停、急停现场归档进蓝本「当前磁盘状态」节；用户三处规格变更落档 HANDOVER_APP——导航 5 Tab→4 Tab、M4-3 排版基准=Web 现版、验收改无截图文本证据协议；蓝本=仓库外《QimengNAS/派发任务书-20260905夜2.md》，决策台账=《待拍板-20260905夜2.md》（存疑不阻塞·建议口径先行）；S-2 likedToday=2febe21、S-3 ffmpeg 配置化=7452fd0 已就绪勿重做）。前次：2026-09-05 晨三次（W-4 打点补齐交付闭合 465f9ad——隔离实例实测通过；顺带修 CI Android job 存量红 10873ef 五 job 全绿；W-4 两个实现口径存疑落仓库外《QimengNAS/待拍板-20260905夜集群.md》）。前次：2026-09-05 晨二次（Q1~Q4 拍板全部落定：W-4 web 打点并行车道开跑、M4-3/M4-6 解锁；用户手动派发模式=贴新会话任务书，全套在仓库外《QimengNAS/派发任务书-20260905夜.md》；拍板记录=《QimengNAS/待拍板-20260905午.md》；08:50 定时暂停收尾）。历史：2026-09-04（M4 二次改道「先进优先」：Compose 重建落档 ADR-0014（废弃同日 0013）+ 新增 M6 单机形态（ADR-0015，服务端内嵌手机、旧项目退役）+ 执行路线更新；同日早前：UI 收尾三批任务书、全库质量审查清债归档。2026-08-31 用户约定三条——免密调试/不单拉前端/UI 测试库 + dev-login 端点落地、Immich 风格主题 token 与样例测试库接入）

## 接手第一步

1. 读 `AI_README_FIRST.md`（协作强制规则，含签名纪律与冲突优先级）
2. 读 `docs/PROJECT_PLAN.md`（里程碑勾选状态 = 进度唯一真相）
3. 读 `docs/CHANGELOG.md`（历次变更 + AI 署名）
4. 按任务类型查 `AGENTS.md` 的任务→文档路由表
5. **做 Web UI 时另读 `docs/HANDOVER_UI.md`**（桌面客户端风格侧边栏/卡片流原型交接：真机抓取方法、实测参数、原型位置、对齐纪律 §4.5 与待办 §5.9 执行批次）
6. **做 Android（M4）时另读 `docs/HANDOVER_APP.md`**（M4 八批次任务书：Compose 重建路线 ADR-0014、冻结决策/验收命令/存疑停手点；单机形态预留 ADR-0015）

## 当前进度（2026-09-04）

- **M0 地基 ✅**、**M1 服务端核心闭环 ✅**、**M2 后端先行全部完成 ✅**、**M2 UI 段（Web）✅（2026-08-29，commit 82f1935 存档含并行 M3 后端件）**、**M3 后端五项 ✅（2026-08-30，推荐算法/统计/SourceMatcher/作者体系/迁移端点全量接线，见下方 M3 完成记录）**
- **M3 全部完成**：最后一项「推荐偏好设置页」已于 2026-09-03 随阶段 B 接真（设置页 9 维滑杆 + 4 预设卡，走 GET/PUT /recommendations/prefs）
- **M3 后端遗留已清零（2026-08-31）**：① 孤立 COS 作者清理（扫描收尾+增量删除后自动，旧项目 deleteOrphanCosAuthors 语义）；② custom_sources 写入端点（GET/PUT /sources/custom，整体替换+后台全库存量重算，已入库资产不依赖重扫）；③ filing 改名/移动的 cos 库作者映射修正（EnrichAsset/移动合并两条路径都按新目录首段重算）。协议债两项同清：/dirs 的 libraryId 标 required、AssetDetail 补 libraryId。细节见 CHANGELOG「M3 后端收尾」条目
- **UI 路线现状（2026-09-04 对齐口径：阶段 B 全量完成，mock 全部退役）**：web 端九页全接真数据（首页推荐流/cos/榜单 tab、相册四维筛选、详情、搜索、我的、数据、文件管理、回收站、设置、维护、作者管理、集合子页），`pages/mock.ts` 已删（2026-09-03）。原型 `media-ui-prototype/` 已整体移植进 web 正式代码（React 重建，访问 8420 即原型界面）；v1 旧壳与 v2 panel-demo 均已于 2026-09-01 删除。对齐纪律（HANDOVER_UI §4.5）在 web 端继续适用。**剩余 UI 待办三项**——ArtPlayer 播放器 UI（现用原生 video）、confirm 换原型风格弹窗、**上传 UI 入口**（2026-09-04 审查发现：后端 POST /assets/upload 四道校验齐全、SSE upload.done 已桥接、设置页上传配置已接，但页面无上传入口——通道就绪前端缺壳；手机直传以 M4 App 为主通道）。详见 HANDOVER_UI.md §5/§6
- **全库质量审查+清债完成（2026-09-04，CHANGELOG 第十一/十二笔）**：三路研究子代理并行抽查（架构符合度 / 三端代码质量 / 协议·迁移·安全纪律），结论——纪律执行整体优秀、无方向性偏离（安全三红线、协议↔实现 60 操作双向一致、迁移零改史、生成物历史零入库全过）；发现的轻微偏离已全部清零（server：assets.go 拆分 + main.go 常量 + 写错误注释；web：分层纪律收紧 + prototype.css 30 处颜色收敛 token，口径固化 HANDOVER_UI §5.8）。本行及上方 UI 路线现状的口径修正即审查后同步
- **当前执行路线（2026-09-04 用户两次拍板后定稿）**：**① Web UI 收尾三批**（任务书 HANDOVER_UI §5.9：W-1 上传入口 → W-2 弹窗 → W-3 ArtPlayer）→ **② M4 Android 八批次·Compose 重建**（ADR-0014「先进优先」，任务书 HANDOVER_APP.md）→ **③ M6 Android 单机形态**（ADR-0015：Go 服务端内嵌手机替代旧项目，日常价值优先）→ **④ M5 NAS 部署验收**（PROJECT_PLAN M5，用户自测虚拟机节奏）
- **501 stub 已全部清零**（notImplemented 机制退役，errors.go 该函数已删——新端点接线模式：实现进各自文件、无 stub 可删）

## 线上实机状态（2026-08-30 晚，用户 PC 实机部署 ✅）

M3 完成后当天就在用户 PC 上实机跑通全流程，明天的活从这里接：

| 事项 | 现状 |
|---|---|
| 服务端 | 用户 PC 上实机运行中（`启动服务端.bat`，端口 8420）；**AI 会话结束后进程可能随会话终止——重启即双击该 bat，数据全在磁盘不丢** |
| 数据目录 | `qimeng-media/qimeng-data/`（bat 的 %~dp0 语义）；2026-08-22 的旧验收库已确认无真实数据后重置 |
| 管理密码 | `test-password-001`（用户已登录过其浏览器，token 存 localStorage；密码登录端点 2026-08-30 已补齐，换设备输同密码即可） |
| 已注册库 | 「测试收藏库」= `<本地相册目录>\1\HHH`（kind=normal），已扫描 **7347 文件**（图 6917/视频 430，约 20.7 GB） |
| 作者导入 | `4 作者\1 图集作者.txt`（35 作者/349 关联）+ `2 视频作者.txt`（100 作者/475 关联）已导入，跨 TXT 同名合并后 **122 位作者** |
| 出处匹配 | 扫描时自动富化，**47 组出处**（守望先锋 232/火焰纹章 108/英雄联盟 67/崩坏 星穹铁道 59/最终幻想 56 等） |
| 用户浏览器 | 已实测登录进主界面（推荐流正常显示真实媒体） |

### 用户约定（2026-08-31 起，持续有效）

1. **项目未完成前不要密码流程**：本地开发一律用 dev 免密通道直达 UI——服务端 `QIMENG_AUTH_DEV_MODE=1`（或 yaml `auth_dev_mode: true`），前端 LoginGate 自动调 `/auth/dev-login` 直进（404 时回退正常表单，生产零变化）。**该 env 已默认写入 `启动服务端.bat`（2026-09-03 起），双击启动即免密**。**实机/生产禁止开启**（SECURITY.md「开发模式」）。
2. **不要单独拉前端**：统一访问 `http://127.0.0.1:8420`（后端托管 `web/dist`，SPA 由服务端随附）。改了前端代码先 `npm --prefix web run build` 再访问 8420；除非排查构建问题，不要起 vite dev(5173) 单前端（没后端进不去）。
3. **UI 调试测试库**：样例相册 `<本地相册目录>\相册\2017 12 9～10  样例`（90 张 JPG，kind=normal）注册进本地开发库作为 UI 数据源；换库/删库前先问。

### 执行调度（2026-09-04 用户定：Flash 自驱模式，免 5.3 逐批规划）

任务书已自包含（冻结决策/验收命令/停手点齐备），执行 AI 按下列规则自驱；5.3 仅在停手点触发、方向变更、验收争议时介入：

1. **顺序固定**：W-1 → W-2 → W-3（Web UI 收尾）→ M4-0 ~ M4-7（严格按序，M4-5 可与 M4-3/4 并行但保守起见串行）→ M6。
2. **每批一个会话（或一个执行子代理）**：新上下文执行单批，**禁止一个会话连做多批**（上下文膨胀是错误之源）。执行前必读：`AI_README_FIRST.md` → 本文件 → 对应任务书（Web 收尾=HANDOVER_UI §5.9；M4=HANDOVER_APP.md）+ 该批引用的规格书节；Android 批次另读 `docs/adr/0014`。
3. **验收只认证据**：交付报告逐条粘贴验收命令输出原文 + 实机/模拟器截图（HANDOVER_APP 通用约束 7）；无证据=打回重做，不进入下一批。
4. **重批次加对抗审查**：M4-0 / M4-3 / M4-5 / W-3 交付前自派 reviewer 子代理全新上下文对抗审查（项目既有模式），审查通过才算交付；其余批次自查即可。
5. **每批完成即 commit**（代码+文档同一 commit）——任何时点中断，已完成批次无损；**恢复 = 新会话说「按 docs/HANDOVER.md 当前待办继续下一批」**。
6. **停手即停**：任务书存疑停手点触发时停下等用户（交付报告写清现象/已试方案/候选方案），禁止自行猜测或越权拍板。
7. **不扩围**：任务书没写的功能不做（发现缺口记入待办，停手问）；禁止顺手重构任务书范围外的代码。
8. **夜间集群模式（2026-09-04 用户定，Flash 额度不限时段）**：单个主会话作**调度器**连续派发全部批次，追加规则：
   - **写代码任务严格串行**（全部子代理共用同一工作树——前批 commit 后才派下批；同批的 reviewer 对抗审查、调研等**只读任务可并行**）；
   - 主会话**不自己写代码**，只做三件事：派发、验收（读交付报告的证据段，重批次确认 reviewer 通过）、更新 HANDOVER_APP 批次表勾选与 CHANGELOG 条目（每批照常署名）；
   - **停手批次记 SKIPPED + 原因后跳过**，继续派后续无依赖批次（M4 链上停手则该链后续全停，转派 W 批次/M6 技术验证等无依赖项）；
   - 收工产出**晨间汇总**追加进 CHANGELOG：完成批次清单（含 commit hash 与证据位置）/ SKIPPED 批次与停手原因 / 待用户处理项；
   - 主会话上下文控制：推进状态以磁盘为准（批次表勾选/git log），不靠记忆；会话被压缩前先确认工作树干净、无未提交改动。

### 当前待办（2026-09-04 用户拍板：UI 收尾 + M4 完整任务，按序）

1. **Web UI 收尾三批**（执行任务书 = HANDOVER_UI §5.9，一批一个会话）：✅ W-1 上传 UI 入口（2026-09-04，commit 6690b12）→ ✅ W-2 confirm 换原型风格弹窗（2026-09-05，commit 2001a20）→ ✅ W-3 ArtPlayer 播放器（2026-09-05，reviewer 打回 1 轮返工后通过；选型 artplayer 5.4.0）——**三批全部完成**（W-3 追加返工 f3da79c 已闭合）。详情页发现项已拍板「M4 后回补 Web」（HANDOVER_UI §5.9 末尾，第三十笔）；web play/dwell 打点缺口（§5 第 13 条）**✅ W-4 已交付闭合（2026-09-05 晨，commit 465f9ad）**——断点定位=play/dwell 从未接线（非「代码存在未生效」，M2 记录失实）；隔离实例实测 8 条事件、trends seconds=29 与逐条精确吻合。两个实现口径存疑落仓库外《QimengNAS/待拍板-20260905夜集群.md》（<1s 停留段不上报 / play 事件覆盖面，现按建议口径 A 上线，推翻均为一行级改动）。改前端时**顺带补集成冒烟**的纪律继续有效（实机暴露的 409 误判就是缺这类测试）。
2. **M4 Android 八批次·Compose 重建**（执行任务书 = HANDOVER_APP.md，M4-0 起按序）：**2026-09-04 用户二次拍板「先进方案优先」**（ADR-0014，废弃同日照搬路线 0013）——Compose(Material 3) + Hilt + Now in Android 多模块范式，交互规格照搬旧项目 GUIDE_UI，实现全部新写（复杂自绘控件允许 AndroidView 桥接）；ServerConfigDataSource 单点留 M6 单机形态口。**进度与派发顺序（2026-09-05 晨二次更新，Q1~Q4 已全部拍板）：M4-0 ✅（cdc422e）——今晚 Android 串行链 M4-1 → M4-2 → M4-5 → M4-6（07:00 后不新贴批，给验收留窗口），M4-3 最重批次留明晚首发**；M4-2 含拍板 1A/2B/3B/4A 落地（A3/A4 依 DOMAIN_RULES §6/§3 已拍板口径执行）；M4-3 五条/M4-6 六条拍板详情已并入仓库外《QimengNAS/派发任务书-20260905夜2.md》「A 车道批次拍板与执行口径」节（原《待拍板-20260905午.md》等历史文件已于 2026-09-06 01:15 清理）。M4-4 前置 M4-3，M4-7 收尾全后置。**执行模式（2026-09-05 04:15 回调）：主会话后台子代理派发（executor=Flash 通道，08:50 定时自动化可 TaskStop 强收——手动会话停不了才改此模式），单批任务书=仓库外《QimengNAS/派发任务书-20260905夜2.md》**（**唯一任务书**；旧《派发任务书-20260905夜.md》已于 2026-09-06 01:05 按用户要求删除，其 M4 拍板执行口径全部并入夜2「A 车道批次拍板与执行口径」节）；恢复惯例继续有效（新会话说「按 docs/HANDOVER.md 当前待办继续下一批」）。**2026-09-06 00:50 用户定稿（两会话版，C 车道裁撤）**：执行=用户自开**两个**持久会话按蓝本《QimengNAS/派发任务书-20260905夜2.md》并行自驱（A=android/**、B=web/**+api+server/**——原 C 车道的协议扩展/M6 备忘录/文档漂移并入 B 链 B-4~B-7，夜终审查由各会话按「续做与自审协议」多轮自审承担）；每会话持续做完一批接一批；**每会话同时可跑 ≤3 个子代理，账号级子代理上限经用户实测撤除**（撞 1302 限流等 2 分钟重试兜底）；B 恢复协议改动权，A↔B 的 make sdk 生成物竞态自愈协议在蓝本；计划会话只出计划书，其试派的首批（M4-1/B-1）已叫停、半成品留工作树未提交——**B-1 含两个 stash（b1-temp-orig/b1-temp-dirbrowser）待依序 pop、代码基本完成且隔离实例证据已取得；M4-1 core 层写完从未编译、settings.gradle 已 include 未建的 :feature:login（直接构建必失败）**，现场清单与恢复步骤=蓝本「当前磁盘状态」节；同日用户三处规格变更：Android 导航四化（「全部」更名「相册」、原相册 Tab 删，M4-2 落地）、M4-3 排版基准=Web 现版（HANDOVER_APP §3）、验收全程禁截图改文本证据（HANDOVER_APP §4.7）。**B 车道收工（2026-09-06 03:40）：B 链七批全部完成**（commit 链 36af094→e6fac87→220aba3→26c2126→5a472b5→53ba308→5eebfbf→40862ea→a1ca92e，详见 CHANGELOG 第七十二~七十九笔与各笔 %TEMP%\qimeng-b* 证据目录；B-1 两 stash 已 pop 落地、M4-1 半成品由 A 车道独立收尾 88af1b9——蓝本「当前磁盘状态」节急停现场全部清偿）——遗留移交：CAPABILITY_MAP 三处漂移与 openapi /import 413 声明缺口、指标埋点缺口（第七十八笔记账）；26c2126 夹带 feature/album 删除的引用清理待 A 车道提交收尾。**晨间用户拍板（待拍板条目 1/2/3/5 同意、条目 2 修复、4/6/7 否）后加时四批全部完成**：目录树嵌套子树对齐修复 8ed4b97（条目 2 落地+DirFileList 闪烁清偿）→ 9 族指标埋点接线 1f01f29（reviewer 对抗审查通过）→ 协议补全 openapi 413+directory 描述+能力地图三处 b0ee832 → 埋点 P3 清偿 ReaderFrom 委托+trash 刷新限时 874df6a——第七十九笔移交项全部清零，B 车道就此收工。**A 车道收工（2026-09-06 07:42 push）：M4-1 88af1b9 → M4-2 4acb128 → M4-5 294a576（独立对抗审查 14 项全过）→ M4-6 944cac5 → A-S1 build-logic 0ff017a → ci 21813e3 → 自审返工 a105280——自审 R1 P2×3（筛选请求乱序/设置页静默失败/CI 覆盖缺口）返工后 R2 通过，Android 72+:core:model 59 用例全绿；M4-3 时间门未赶上留 09-06 夜首发（派发口径=待拍板条目 10）；备忘录三份落 QimengNAS（A-S2/A-S3/M4-4 前置），待拍板新增条目 13/14；8:50 定时收尾自动化已建档。**（后记 08:55 B 会话 CI 核查：26c2126 夹带的 feature/album 两删除曾使 master CI 的 Android 作业在 26c2126→b0ee832 区间红约 2.5 小时——:app 对 :feature:album 的引用未随删（引用清理当时仍在 A 车道工作树未提交），4acb128 落地后自愈；当前 HEAD a105280 CI 全绿，教训=跨车道共树提交前必须 `git diff --cached` 逐文件核对，26c2126 批已录入该流程。）**
3. **M6 Android 单机形态**（M4 后启动，优先于 M5；ADR-0015 + PROJECT_PLAN M6）：Go 服务端交叉编译进手机（modernc 纯 Go 红利，最大难点=ffmpeg 移动端方案），App 连 localhost，媒体存手机——**旧项目绮梦影库由此退役，只维护一个项目**（数据迁 POST /import/qimeng-backup + 媒体原地注册）。
4. **M5 NAS 部署验收**（M4 后，PROJECT_PLAN M5；验收环境 = 用户 PC 上的 fnOS 虚拟机，见 PROJECT_PLAN M5 第一项）。
5. **搁置（2026-09-04 用户拍板，不催不问，用户主动提起再做）**：① `<本地相册目录>\2 收藏`（内含 `收藏\` 一层）与 `<本地相册目录>\相册`（个人照片 2013~2025）是否注册为库（相册若按 COS 目录结构需选库类型）；② 旧备份 `<旧项目目录>\qimeng_backup.json` 的行为数据（浏览历史/点赞/收藏）是否走 POST /import/qimeng-backup 补进新库。

> 环境已清理（2026-08-31）：`server/data/` 测试残留已删除（config 默认 `./data` 的裸启动空库产物；正式数据目录是仓库根的 `qimeng-data/`）。实机库经查只注册了 1 个正式库（测试收藏库）——若再看到第二个数据目录，先确认不是某个裸启动又没带 `QIMENG_DATA_DIR` 产生的，再删。

## M2 后端任务（2026-08-29：全部完成 ✅）

**M2 后端任务全部完成**——见上方进度；FTS5 搜索与 upload.done 载荷 schema 均已交付（commit 338c281）。

M3 及以后的任务表不变（推荐算法/统计/SourceMatcher/作者体系/迁移端点），见 PROJECT_PLAN。

## M3 接手速览（2026-08-29 调研结果）

> **⚡ 本节调研已于 2026-08-30 全部落地完成**（M3 后端五项，详见 CHANGELOG「M3 后端算法移植全量完成」条目与 PROJECT_PLAN M3 勾选）——下表仅作历史调研存档，"现状"列全部过时。

| 事项 | 现状 |
|---|---|
| 权威口径 | `docs/DOMAIN_RULES.md` §1（10 维公式+自适应回收+预设表+三个后处理）、§5（统计口径/趋势分桶）、§10（旧数据映射）——逐字遵守，改前必须用户确认 |
| recommend 包 | `server/internal/recommend/` 仅 doc.go 空壳；statsby 同（`internal/stats/` 空壳）。**纯函数**（无 IO，ARCHITECTURE §5 边界） |
| 协议端点 | 已全部定义：`GET /recommendations`（seed/limit/mediaType）、`GET /rankings`（period/limit）、`GET /stats/overview`、`GET /stats/trends`、`GET+PUT /recommendations/prefs`（RecommendPrefs 9 维 schema 已在协议 components）——`make sdk` 生成物三端已含 |
| 热度占位 | `server/internal/httpapi/recommendations.go`（M2 占位：viewCount 降序，seed 忽略）——M3 只换实现，参数面不变；`recommendations_test.go` 已有占位用例 |
| 数据基础 | daily_shown 表（每日展示计数，§1.4 输入）已在 0001 建好；view_events/likes/favorites 齐备；`db` 查询层已有 CountAssetEvents/SumBrowseSeconds/LastViewedAt 等 |
| 旧项目测试（照译） | `<旧项目目录>\app\src\test\java\com\qimeng\media\`：MediaBrowserLogicRecommendTest.kt（推荐核心）、MediaBrowserLogicTest.kt、SourceMatcherTest.kt、AuthorImportUseCaseTest.kt、AppPrefsImportSanitizeTest.kt（迁移） |
| 旧项目算法文档 | `<旧项目目录>\docs\GUIDE_ALGORITHM.md`（applyFilter/recommend/rank/SourceMatcher 细节）——只搬领域规则与公式逻辑，禁止搬 Kotlin 实现 |

> 推荐算法照旧项目测试用例翻译：核心行为锁定点如「同 seed 可复现」「每日惩罚 -0.8×shownCount（随次数递增）」「视频/图片自然混合按剩余比例」「同分桶 ±0.05 打散」等，翻译时必须保留原测试断言语义。

## M2 UI 段（Web）完成记录（2026-08-29，未提交）

> 前会话「开做 m2 剩下的 web ui」因额度暂停于 executor-C/D 中间态（31 个构建报错 + 4 页占位），本次会话接手完成。reviewer 全新上下文对抗审查**通过**（build/lint/go test 独立重跑核验）。

### 本次完成

1. **31 个构建报错清零**（6 文件）：Dashboard `const { data } = useSystemStatus()`、RecommendPage `hotItems/favoriteItems` undefined 保护 + `(_, index)`、labels.ts 从 `Library['scanState']` 派生 `LibraryScanState`（generated 无独立导出）、3 处 unused 删除。
2. **4 个占位页组装**：DetailPage（主 AI 亲写——媒体区双分支 + chrome 互动行 + 批次导航 + open/dwell 打点 + 四弹层；key=assetId 复位模式过 React Compiler lint）+ Organize/Trash/Stats（executor——目录树/上传队列、回收站全套确认弹窗、501 空态设计）。
3. **集成检查五项全过**：Toaster 已挂 main.tsx、SSE 单连接引用计数多实例安全、error-text 统一口径、相册跳转 source 参数经 parseFilters 闭环、MoveDialog 接线（修复 `/dirs` libraryId 业务必填的接线 bug——MoveDialog 增 `libraryId?` prop，DetailPage 传第一库 id）。
4. **browser 冒烟通过**（隔离实例 8421 + ffmpeg 造数）：设密→注册库→扫描→列表分组/缩略图→视频播放（TimelineBar/倍速/默认静音）→点赞/收藏/标签增删→目录新建→移动→删除→回收站恢复→统计 501 空态→仪表盘真数据→相册兜底分组。上传 UI 受 IAB 无 file chooser 限制，XHR 通道页面内直调验证 201。
5. reviewer 观察项处置：注释口径修正（服务端打点当前不去重，勿假设）；`find` 冗余简化。其余记账：StatsPage isError 不分流错误码（M3 分流）、openapi `/dirs` libraryId 未标 required 的协议债（铁律 1 视角应修协议或改实现，M3 处理）。

### 提交待办（需用户确认策略）

工作树同时含**并行 M3 后端改动**（server/internal/recommend/*、httpapi/rankings.go、prefs.go、daily_shown/settings/recommend.sql 等）。建议拆两笔：`feat(web): M2 UI 段`（web/** + 本次文档）与 M3 段另行走 M3 会话流程；store/db/*.sql.go 与 generated 为生成物、含双方变更，归属需用户拍板。**当前未做任何 commit。**

### 关键事实速查（M2 UI 实现依据）

- 交互规格 = 旧项目 GUIDE_UI 验证语义的 Web 对齐版（@ 本仓库根 AGENTS.md/README，实现要点已由方案基线固化到各页面 prompt——现不在文件里，fallback 依据：LEGACY_REQUIREMENTS.md + DOMAIN_RULES §3/§8/§11 + 旧项目 docs/GUIDE_UI.md）。
- 协议事实清单（字段级，含实现差异）：以 `api/openapi.yaml` + `server/internal/httpapi/gen/` + DOMAIN_RULES 为准。
- 关键约定：日期分组客户端按 modifiedAt 折叠；列表排序 default=addedDate desc（M1 占位）；tagIds 多选+tagMode；sources 端点 name=null 兜底"其他"；上传 XHR（octet-stream）；SSE 必须 fetch 流式；localStorage token key `qimeng_token`、sessionId 用 sessionStorage UUID。

## 怎么跑起来

- **一键启动（2026-09-04 起 UI 自动打开；2026-09-05 起改固定路径构建根治防火墙弹窗）**：双击根目录 `启动服务端.bat`（端口 8420，数据目录 `qimeng-data/`）——脚本先 `go build -o server/qimeng-server.exe` 再运行（**不再 `go run`**：go run 每次生成随机临时路径二进制，Windows 防火墙视为陌生程序反复弹允许对话框）；已建端口级防火墙规则「Qimeng Media Server 8420」（TCP 8420 入站，**仅专用网络**——SECURITY.md 红线 8 公网永不开，远程访问走 Tailscale 不变），同日清理了 40 条 go run 时代死路径的 qimeng.exe 旧规则。原描述：脚本在启动服务端的同时派生一个隐藏探测线程，等 8420 端口就绪后**自动用默认浏览器打开 Web UI**（500ms 轮询、上限约 60s；起不来则超时静默不开，窗口里有日志）。**UI 不需要单独启动——它就是 8420 的网页**；浏览器没弹时手动访问 `http://127.0.0.1:8420`。手机同 WiFi 访问局域网 IP:8420（横幅里印了本机 IP）
- **Web 页面**：服务端存在 Web 构建产物（默认 `../web/dist`，`make web-build` 生成）时 `/` 提供 Web UI；未构建时 `/` 与 `/_debug/` 回退内嵌验收页（功能受限于 M1 范围，服务本身不挂）
- **验收页**：浏览器开 `http://127.0.0.1:8420/_debug/`（中文界面：设密码→注册媒体目录→扫描→浏览→播放）
- **测试**：`cd server && go test ./...`（9 包全绿是底线）；Web：`cd web && npx tsc --noEmit`
- **协议改动**：先改 `api/openapi.yaml` → `make sdk` → 按编译错误适配三端（铁律 1）
- CI 在 GitHub Actions（push 自动跑五道门禁；M4-0 起含 Android 客户端 job），仓库：`Surtr42u/qimeng-media`（私有，gh 已登录）

## 后端还剩什么（重要：不是"只剩 UI"）

M1 只完成了**浏览闭环**。剩余后端工作量不小：

| 剩余项 | 里程碑 | 规模预估 |
|---|---|---|
| ~~sysmon 接线~~ | M2 | ✅ 2026-08-27 完成 |
| ~~移动/重命名/回收站端点~~ | M2 | ✅ 2026-08-27 完成（恢复语义与已知限制见 DOMAIN_RULES §9） |
| ~~推荐占位/目录树/上传/标签体系~~ | M2 | ✅ 2026-08-29 完成（上传协议补 libraryId；upload.done SSE 载荷 schema 待补） |
| ~~FTS5 全文搜索~~ | M2 | ✅ 2026-08-29 完成（迁移 0002 触发器自动同步 + browse q 谓词 + search 包；详见 PROJET_PLAN M2） |
| **推荐算法 10 维移植（DOMAIN_RULES §1 逐字遵守 + 自适应权重回收 + 三个后处理）** | M3 | 大 |
| 统计聚合/趋势分桶（§5 口径） | M3 | 中 |
| SourceMatcher 出处匹配引擎（131 SourceGroup 表从旧仓库翻译） | M3 | 大 |
| 作者双体系（TXT 三格式解析 + COS 目录扫描） | M3 | 大 |
| 旧数据迁移端点（qimeng_backup.json 17 段映射） | M3 | 中 |

UI（M2 前半 + M4 Android）与上述后端并行推进。

## 本项目的特色纪律（换 AI 最容易踩的坑）

1. **协议先行**：任何接口改动第一步永远是 openapi.yaml，手写客户端 SDK 是事故
2. **migration 唯一**：0001 已发布（commit 1625db1），改表结构只能新增 0002+
3. **DOMAIN_RULES「逐字遵守」的常量/公式**：改动必须先经用户确认
4. **用户强偏好**：查看媒体**永远发原件**（缩放副本方案已被否决，commit 4a1f3da）；措辞用"看视频/图片"不用"看片"
5. **commit 格式**：`类型(模块): 简述 | 文档: 已更新XXX`，代码+文档同一 commit
6. **Windows 环境**：无 winget；JDK 免安装在 `../dev-tools/jdk17`；bat/make 运行时输出必须纯 ASCII（cmd 代码页坑）
7. 生成物（gen/generated/android-sdk）不入库，`make sdk` 重建

## 领域知识库（旧项目）

`<旧项目目录>`（Android 单机版，2.4 万行）：**M4 走 Compose 重建（ADR-0014，2026-09-04 用户二次拍板「先进优先」）**——交互规格照搬其 `docs/GUIDE_UI.md`（唯一规格书），实现代码全部 Compose 新写、禁搬旧 Kotlin（复杂自绘控件允许 AndroidView 桥接，清单入交付报告）；算法已在服务端 M3 落地，客户端不复算。ADR-0013（同日照搬路线）已废弃，其架构调研结论（解耦事实/URI 消费点清单）仍被 HANDOVER_APP 引用作规格参考。**旧项目待退役**：M6 单机形态（ADR-0015）验收通过后归档——数据迁移走 `POST /import/qimeng-backup`，媒体文件原地注册为库。算法细节参考其 `docs/GUIDE_ALGORITHM.md`、`GUIDE_AUTHOR.md`；近期审查/修复沉淀的需求级结论见本仓库 `docs/LEGACY_REQUIREMENTS.md`（标签管理/缩略图代表帧/搜索作者维度/统计口径等，M2~M4 实现前对照）。
