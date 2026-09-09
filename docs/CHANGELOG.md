# CHANGELOG - 变更历史

本文件归档项目历次功能/修复/决策变更，每条对应 git 提交（commit hash 标注）。
## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型（真实命名，品牌-版本），便于追溯每次改动由谁完成。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。
- 署名自查（2026-09-05 补）：每条变更由执行会话先确认自身实际运行模型的真实名称再署名（GLM-5.3 与 GLM-5.3-Flash 是两个不同模型名），禁止沿用上一会话或上一条目的署名行；历史条目真实署名不动。

---
## fix(app): 任务K K1 详情黑底污染清偿——舞台底色单源对齐旧版（chrome显=主题底/沉浸=纯黑/播放中=播放器黑）（2026-09-09 第一百七十四笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务K K1 批）

- **用户原话**：「详情页也严格按照旧版实现　现在的会出现黑色背景的详情页污染视觉」——I7 时代舞台盒无条件 `Color.Black`，日间主题下整页黑底；旧版舞台底透出主题背景色（日 #FAFAFA / 夜 #1A1A1A），仅沉浸态与视频播放中才黑
- **底色单源**：新增 `stageBackdropColor(chromeVisible, playerActive, themeBackground)` 纯函数（`DetailStage.kt`，全卷唯一裁决点在 `DetailScreen.kt`，经 `DetailMediaStage.backdrop` 参数逐层下发，子层禁止自带底色防口径分叉）——chrome 有效显示=主题背景透传；沉浸（chrome 隐藏）或播放器活动期（视频 PLAYING/ENDED/PAUSED，chrome 让位播放器）=纯黑；全屏覆盖层 Dialog 维持黑（拍板口径不动）
- **改动文件**：`DetailStage.kt`（删两处无条件 `background(Color.Black)` 改 backdrop 单源 + KDoc 重写新口径）、`DetailScreen.kt`（裁决点接线，negate-inset 舞台平移后状态栏后区域底色随单源同步，无黑条/主题色条错位）、`VideoStage.kt`（海报态 AsyncImage 占位/错误底 ColorPainter 黑→backdrop）、`ImageStage.kt`（KDoc 同步）、`ZoomableOriginalImage.kt`（解码失败覆盖层改自带纯黑底——舞台底不再恒黑后 RES #27 黑底白面高对比由覆盖层自身保证，视觉不变、拍板不回退）
- **DetailChromeBars**：免改——顶/底 chrome 渐变本就取 `colorScheme.background.copy(alpha)`（主题色），沉浸态 chrome 整体隐藏无底色可同步
- **单测**：新增 `StageBackdropTest`（JVM 纯函数锁定，5 用例：chrome 显透传日/夜主题底、播放中黑、沉浸黑、沉浸+播放黑、日间浏览态绝不返回纯黑的回归锁）
- **门禁四绿**：`make app-test`（StageBackdropTest tests=5 failures=0，BUILD SUCCESSFUL）/ `make app-lint` / `make app-build` / `make lint` 全 EXIT=0（后台无头，日志在证据目录）
- **UI 实测停手记档**：本机同时存在用户雷电模拟器（dnplayer.exe 在跑）与官方 qimeng_api35 无头实例（占 5554/5555），adb `emulator-5554` 归属无法在不违禁（5554 全命令禁碰）的前提下验证；自有第二实例因 AVD 锁（既有实例非 -read-only 独占）无法在 5556 启动，杀既有实例=触碰 5554 背后进程，同样违禁——按「尽力而为」条款停手，UI 对照（日/夜、竖/横/超宽、chrome 显隐）留给 K3 收官走查
- 证据目录：`%TEMP%\qimeng-k1-evidence\`（四门禁日志 + 模拟器启动失败日志）

---
## refactor/feat/fix/docs(server+web): 系统审查 P2 六条清偿——assets 拆分+ADR-0019+库白名单+回收站路径闸门+Web 打点测试+工作区清理（2026-09-09 第一百七十三笔）

执行 AI：MiMo（主代理，汇总/验收/修 config 编译缺陷）；实现子代理 general-1~6 分条落地（P2-1~P2-6）

- **P2-1**：`httpapi/assets.go` 612 行拆为 assets / assets_filters / assets_list / assets_media_url（主文件 93 行，handler ~61 行）；符号名保留，行为零变化
- **P2-2**：ADR-0019「编排层从 httpapi 下沉」Accepted；INDEX / ARCHITECTURE §5.1 / AI_README_FIRST 自审清单同步
- **P2-3**：`allowed_library_roots`（yaml + `QIMENG_ALLOWED_LIBRARY_ROOTS`）；空=不限制；注册库不在白名单 → 400；SECURITY.md 新节；config/httpapi 测试
- **P2-4**：删除与回收站恢复路径补 `filing.PathWithinRoot` 纵深防御（污染 root/rel 拒 400）；3 个 API 测试
- **P2-5**：抽 `web/src/lib/progress-report.ts` 纯函数；`use-progress` 接线不变；+24 vitest；全量 118 绿
- **P2-6**：原型归档 README + 工作区清理说明；`qimeng-server.exe~` 因进程占用暂未删（Access denied）
- 门禁：`go test ./...` 全绿；`npm test` 118/118；`tsc --noEmit` 过
- 详单：`docs/P2-修复执行报告.md` + `docs/.p2-progress-P2-1..6.md`

---
## feat(app): RES 残账清偿——总览行直达集合页+搜索/作者集合页批次上下文+NavHost去重与编码器单测（2026-09-09 第一百七十一笔）

执行 AI：GLM-5.3-Flash（执行子代理，RES批；主会话质量排查认定的可直接做项）

- **R1 QimengNavHost 去重（卫生 J1）**：StatsScreen/StatsDetailScreen 两处三回调接线（onOpenAsset/onOpenAuthor/onOpenTagSearch）逐字重复，收敛为私有参数对象 StatsNavLinks + statsNavLinks(navController) 单源构造；encodeQueryValue 补 6 断言单测（EncodeQueryValueTest：unreserved 保留/中文 UTF-8/&/# 劈裂防护/空格/emoji 四字节/searchRoute 拼接，对齐 AuthorCollectionRoutesTest 风格，private→internal 供同模块锁定）
- **R2 我的页作者总览 Top5 行直达作者集合页（I4 挂账清偿）**：此前借道作者管理页两跳。SettingsScreen 签名加 onOpenAuthorCollection(authorId, displayName)，OverviewAuthorRow 点击带 AuthorSummary.id+原始名（不带 ·COS 展示后缀，对齐详情页作者卡口径）上抛，QimengNavHost 接线 authorCollectionRoute——模拟器实证一跳直达（dump：标题「I6走查作者」+「作者 · 4 个文件」）
- **R3 搜索页进详情批次上下文（N1 范式补齐，清偿 SearchScreen D3 注释挂账）**：SearchViewModel 注入 MediaBatchIndex 加 enterDetail（结果态 items 含翻页追加件=批次，快照整体替换），Screen 点卡先写批次再导航；单测 2 例（未落地空批次/追加件序号滑切 + 换词重查整体替换）——模拟器实证「2 / 18」序号
- **R4 作者集合页进详情批次上下文（台账 #21 余量清偿）**：AuthorCollectionViewModel 同范式加 enterDetail + Screen 接线；单测 1 例（翻页追加+邻位滑切+筛选重拉整体替换）——模拟器实证「4 / 4」「3 / 4」序号随点卡位置变化
- 新增单测 9 例全绿（EncodeQueryValueTest 6 + SearchViewModelTest 2 + AuthorCollectionViewModelTest 1）；门禁四连绿（app-test/app-lint/app-build/make lint 0 errors）；证据 %TEMP%\qimeng-res-evidence\

---
## feat(app): RES #27 详情损坏原件解码失败中文提示（2026-09-09 第一百七十二笔）

执行 AI：GLM-5.3-Flash（执行子代理，RES批）

- **损坏件不黑屏哑失败（D7 发现，台账 #27）**：损坏 JPEG 进详情此前黑屏无任何提示。ZoomableOriginalImage 的 Coil Target 补 onError（coil3 3.4.0 源码实证恒回调语义）→ 舞台中央中文提示「该文件无法解码，可能已损坏或格式不受支持」+ 重试/返回按钮（重试=递增 retryAttempt 重建请求真重发，错误结果不入缓存；返回=onExitDetail 参数链上抛与顶行返回同链 popBackStack）。黑底白字（VideoStage 播放钮同款前例）；失败态只反映最近一次完成的结果（请求发起清零，切资产/重试不残留旧态）
- **视频态不碰（记档）**：Web 端编码兼容提示条已按 2026-09-05 用户拍板移除（ArtPlayer 自身错误态兜底），INCOMPATIBLE_CODECS 无可对照现版口径；Android Media3 播放器错误面自成体系——按任务书「无既有口径则只做图片态并记档」执行
- 模拟器实证（18461 虚构库）：构造损坏 JPEG（SOI+JFIF 头+随机熵数据无 EOI，320 字节）经上传 API 入 compare-normal 库 → 搜索 corrupt 点卡进详情 → 提示+重试/返回三文案 dump 实证；重试点按后再现提示不崩（logcat 无 FATAL）；返回回搜索页；证据 %TEMP%\qimeng-res-evidence\
- 门禁四连绿（app-test/app-lint/app-build/make lint 0 errors）

---
## fix(app): BVIS 行距微调——搜索词丸流间隙对齐旧实录8dp+悬浮药丸面板间隙4dp（2026-09-09 第一百七十笔）

执行 AI：GLM-5.3-Flash（执行子代理，BVIS批；走查B类清偿）

- **走查 B 类两处清偿（%TEMP%\qimeng-jvis-evidence\ 判差距，零协议批：只碰 core/ui 两文件）**：
- **①搜索词丸流行距过紧**：走查实测 QimengWordPillFlow 行节距 33.9dp（[QimengDimens.WordPillRowSpacing]=2dp 档）vs 旧实录 search_entry.txt 推荐词丸行位 404/528/652px@density3 → 节距 124px=41.3dp（32dp 芯片 → 纵向间隙 ≈8dp）；横向同帧枚缘 329→353px → 间隙 24px=8dp。修：WordPillRowSpacing 2dp→8dp、WordPillSpacing 6dp→8dp（XML 声明值 marginEnd=6dp 让位于实录渲染值）。**F 批 KDoc「34dp 节距=对齐实录」勘正**：34dp 实为胶囊输入框场高（CapsuleFieldHeight），与词丸行节距两个数混淆，注释已改写
- **②悬浮药丸面板行距过松**：走查实测 QimengFloatingPillPanel 药丸节距 40dp（FlowRow spacedBy SpaceM=8dp）vs 旧实录 all_partition_pills.txt 行位 466/568px@density3 → 节距 102px=34dp（30dp 芯片 → 间隙 4dp）。修：新增具名 token FloatingPillPanelSpacing=4dp，面板内 FlowRow 纵横两处换用。**只动 QimengFloatingPillPanel**——QimengValuePillFlow（相册页值流）保持 8dp 不随动（G5 Web 基准拍板保护，走查未判差距），QimengChipRow 同禁扩散
- token 全进 QimengDimens 具名常量附 px→dp 换算注释，无内联魔法值
- 实测（emulator-5554 @density2.625，像素带扫描+语义 bounds 双口径）：搜索页词丸流行节距 33.9dp→**40.0dp**（105px=84px 芯片+21px 间隙，纵横间隙实测 8.0dp 精确命中旧实录；节距 vs 旧实录 41.3dp 差 -1.3dp=间隙拍板 8dp 对实录 9.3dp 的取整档）；历史页悬浮面板（作品维度展开三行）节距 40dp→**36.2dp**（95px=84px 芯片+10.5px 间隙，纵横间隙实测 4dp 命中拍板；节距 vs 旧实录 34dp 余 2.2dp 差全部源自芯片高统一 32dp（QimengSegPill F 批口径）vs 旧实录/走查 30dp 口径——芯片高度不在本批间隙 token 范围，记档不扩）；证据 %TEMP%\qimeng-bvis-evidence\（截图 3+dump 4+扫描脚本 3+输出 3）
- 门禁四连绿（app-test/app-lint/app-build/make lint 0 errors）

---
## fix(app): 任务J J3 首页切换两案——tab 切换哨兵抑制窗口（#35）+ 排行榜→COS 卡半屏复现记档停手（#37）（2026-09-09 第一百六十九笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务J J3批）

- **J3a 哨兵抑制窗口（#35 清偿）**：HomeViewModel.onNearBottom 开头判定——距上次 switchTab 不足 SENTINEL_SUPPRESS_AFTER_TAB_SWITCH_MS(500ms，具名常量：覆盖 pager settle 动画 ~300ms + 周期行移除 1~2 帧重布局回流；代价=切换后 500ms 内真实触底被吞一次、滚停自愈) 一律丢弃：切换瞬间 RANK 周期行移除使 pager 视口变高，布局回流把 lastVisible 抬过距底阈值（≥total-1-6）是布局噪声非用户滚动意图，放行即误换 seed 追加，违背「切 tab 不重拉」（小库一次全揭示必现，任务I I1 发现）。时间戳初值 Long.MIN_VALUE=冷启动首布局不抑制（init 揭示哨兵是既有分批行为）；时钟回拨判负不抑制；selectPeriod 不动周期行高度不参与。实现位置选 VM 层而非 QimengMediaGrid：业务语义归 VM+单测友好+不动五页共用组件公共 API（J3 文件集纪律）
- **时钟源**：internal var clockMs（默认 System::currentTimeMillis）而非构造注入——Hilt @Inject constructor 无法提供函数类型绑定（ProgressThrottlePolicy 先例是普通类）；internal 可变=单测推进时间入口，生产恒默认
- **单测 +3**：切 tab 后短窗内哨兵被抑制不追加换 seed（RANK→推荐 fake-clock 窗口内 onNearBottom 无第二次推荐请求）/ 窗口过后恢复追加（恰出窗口 seed=2 正常）/ 冷启动首布局不在窗口（无 switchTab 时哨兵照常）。10 用例全绿
- **J3b 排行榜→COS 卡半屏（#37）——复现 1 次+无法重放，带证据停手（任务书明文口径，禁止盲改）**：模拟器虚构库 100+ 次 RANK↔COS 切换（chip 点击/横滑/半途反向/连点/滚动中切/周期切换后切/刷新在途切/后台往返/fling 后长等待，A~H+R1~R3 共 13 组），**成功复现 1 次**：RECOMMEND→RANK→RECOMMEND chip 连切（间隔 1s/0.8s）后 pager 卡死在 page≈0.94 中间态——推荐页内容被挤压至左缘 61px 窄条、COS 页占其余（j3b-stuck-primary.xml/png：左列窄卡 x[0,61] 与右两列正常卡同屏并列），稳定不自愈（2s/4s 后 dump 不变）；同序列定向重放 R1×5+R2×12（抖动间隔）+R3×8（含 dump 时序回放）均不复现——**低概率 (<1%) 时序竞争**。疑似机制（无日志佐证不下手）：chip 点击驱动的 animateScrollToPage 途中 currentPage 翻转经 snapshotFlow 回环 switchTab 劫持 currentTab，叠加 RANK 周期行移除引发 pager 高度重测打断动画，LaunchedEffect(currentTab) 单次触发不重试→卡中间态。候选方案（待拍板另批）：①settledPage 持续对齐（snapshotFlow 观察纠正，非单次 animate）②动画期间禁用 snapshotFlow 回环（isScrollInProgress 门控）③升级 Compose BOM 看是否上游已修。台账 #37 维持开放记档
- J3a 实测（emulator-5554）：RANK→RECOMMEND 切换首屏内容稳定无重排（before/after 对照 dump）；门禁四连绿（app-test/app-lint/app-build/make lint 0 errors）；证据 %TEMP%\qimeng-j3-evidence\（复现卡死 dump+截图 1 组+13 组不复现探针）

---
## feat(app): 任务J J2 网格组头跨整行——GridItemSpan(maxLineSpan) 对齐旧版（#36）（2026-09-09 第一百六十八笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务J J2批）

- **修法（一行级）**：core/ui QimengMediaGrid 组头 item 声明 `span = GridItemSpan(maxLineSpan)`（卡片保持单 lane）——日期组头独占整行，不再占单列与首卡同行；KDoc「组头跨全列」自此与实现相符（I6/I9 实证不符项清偿，台账 #36 销账）
- **穿透豁免口径（H1 同款，用户 2026-09-09 晨拍板「对齐旧版做」即授权）**：影响全部网格页含冻结的全部页（相册页组头渲染随组件变化）
- 单测说明：QimengMediaGrid 无既有组件测试（core:ui 仅纯函数 FormatTest），span 为 LazyGrid DSL 纯渲染参数无 VM 逻辑分支，Compose UI 测试框架不在 ADR-0014 依赖白名单（为一行 DSL 参数引入整框架不值）——按任务书口径单测不强求，以五页模拟器实测代验
- 实测（emulator-5554 虚构库）：五页组头 bounds 全部整屏宽 [21,x][1059,y] 且首卡行 y 均在组头 bottom 之下（独占行判定）——相册「周一 2 项」/收藏「周四 3 项」/历史「今天 7 项」/搜索结果「周日 2 项」「周六 1 项」/作者集合「周日 2 项」；日期分组降序不乱；首页推荐/COS 无组头段不渲染（无回归）
- 门禁四连绿（app-test/app-lint/app-build/make lint 0 errors）；证据 %TEMP%\qimeng-j2-evidence\（dump+截图 6 件）

---
## feat(app): 任务J J1 统计榜单跳转接线——常看文件批次上下文+作者集合页+标签携词搜索（2026-09-09 第一百六十七笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务J J1批）

- **GUIDE_UI L218-224 详情页跳转链清偿（REPLICATION_GAPS §3.3 裁定 7「记遗留」转已接线）**：统计页/统计详情页榜单条目全部可点击，四链路——①常看文件条目（主页面 Top3 卡）→详情页，榜单作批次上下文（StatsViewModel.enterDetail 写 MediaBatchIndex 快照=HomeViewModel/FavoriteViewModel 同款 N1 机制，DetailViewModel 消费零改动）；②常看作者条目→作者集合页（TopAuthorEntry.authorId 真实 id 来自 /stats/top-authors 响应，无硬造）；③常看标签条目→搜索页携词；④详情页 seconds 榜 Top20 条目→详情页（StatsDetailViewModel.enterDetail 写整榜快照）
- **混合卡可点击模型**：StatsUiState.topAuthorsTagsMixed 由 Pair<String,Int> 升级为 TopAuthorTagEntry(kind/id/name/views)——kind 分发 AUTHOR→集合页 / TAG→携词搜索，id 字段承载跳转键（作者=authorId、标签=词名）
- **携词转义单源**：壳层 Routes.searchRoute(query)（query 百分号编码 RFC 3986，自持编码器与 feature:author encodeRouteSegment 同款理由：行为确定性+JVM 单测 stub 不可用平台 API；两处注释互指）；QimengNavHost StatsScreen/StatsDetailScreen 两处回调接线（导航接线报备：任务书允许的例外）
- 单测：StatsViewModelTest +2（enterDetail 批次快照=当前榜单清单 / 混合条目 kind+id 意图构造——作者带真实 id 标签带词）、StatsDetailViewModelTest +1（seconds 榜 enterDetail 快照=整榜）；既有混合卡断言随模型升级改 name 取值。14+10 全绿
- 实测（emulator-5554 虚构库 18461）：常看文件条目→详情「1 / 3」与「2 / 3」位序正确；作者条目→集合页（测试作者一·3 个文件 / I6走查作者·4 个文件）；标签条目→搜索携词（虚构补数：POST /tags J1走查标签+PUT tagIds+open×3，top-tags 7d=16 次；搜索框携词+结果命中挂标资产）；seconds 榜条目→详情「2 / 6」；返回统计页滚动位置/档位（30 天）/数据保持无重拉
- 门禁四连绿（app-test 428 任务/app-lint/app-build/make lint 0 errors）；证据 %TEMP%\qimeng-j1-evidence\（dump+截图 14 件）

---
## fix(app): F批 用户反馈三修——搜索胶囊族尺寸对齐旧版+我的页删作者管理入口行+底导航指示器色接线（2026-09-09 第一百六十六笔）

执行 AI：GLM-5.3-Flash（执行子代理，F批；用户2026-09-09三反馈）

- **80dp 之谜根因（反馈①「搜索的胶囊 ui 都过大」）**：material3 1.4.0（BOM 2026.06.01）无 label 输入框内容高=内部文本区 minimumInteractiveComponentSize 48dp + 无 label 默认 contentPadding 上下 16dp（TextFieldImplKt TextFieldPadding 字节码实证）=80dp，直接顶穿 TextFieldDefaults.MinHeight=56dp 下限（模拟器实测搜索输入胶囊 210px=80dp、placeholder 节点 126px=48dp 互证）。contentPadding 参数只在 TextFieldState 新态重载开放（String 经典重载无，34dp 固定高下内部留白会把文字压到 2dp），新态重载又缺 visualTransformation/KeyboardActions 面（登录密码/搜索 IME 提交依赖）——**QimengCapsuleTextField 换 foundation BasicTextField+自绘 decorationBox 复刻旧版 EditText+bg_capsule_soft 同构**：34dp 高（fragment_search.xml L29）+14sp（L36）+横向 14dp（L40）+焦点底色加深一档（保 G6 语义），公开签名零变化 9 处消费方零改动
- **词丸/胶囊芯片紧凑化（同反馈①）**：QimengSegPill 全仓胶囊单源加 CompositionLocal(LMinimumInteractiveComponentSize=0.dp)（旧版 chipMinTouchTargetSize=0dp 的 Compose 等价；m3 1.4.0 可点 Surface 内部施该 modifier——FilterChip 布局节点被撑到 48dp 的元凶，字节码 SurfaceKt$Surface$2 实证）+字号 labelMedium 12sp（styles.xml L13 全仓胶囊统一）；QimengWordPillFlow 行距 2dp/横距 6dp（styles.xml L8 marginEnd）。实测：词丸芯片 84px=32dp+行节距 89px（修前 56dp=147px）
- **首页搜索框 40dp**（fragment_home.xml L37）：实测 105px=40.0dp（修前 48dp），文字垂直居中
- **我的页删「作者管理」入口行（反馈②）**：SettingsScreen ROW_AUTHORS/SUBTITLE_AUTHORS 常量+行项整块删除（用户拍板「已经有了一个作者管理」=作者总览卡「管理」入口为唯一入口，onOpenAuthors 仍供该卡使用）；实测行序 收藏→浏览历史→上传文件
- **底导航指示器接线（反馈③「圆润边角不可见」）**：NavigationBarItem indicatorColor=PrimarySoftLight(#123A3A3A)/Dark(#1AC8C8C8)（旧版 styles.xml 底栏 ActiveIndicator 同值，Color.kt 具名 token 备好从未接线）+选中 icon/label=onSurface（旧版选中图标=主色深灰）。修前默认 indicator=secondaryContainer 与底栏背景色差 2/255 不可见；修后四 Tab 轮点实测指示器 #E5E5E7 叠底栏 #F2F2F4 **色差 13/255**（≥10 达标），pill 84px=32dp=M3 默认圆润胶囊
- 测量口径记档：m3 触摸目标扩展使 uiautomator 可点节点 bounds 虚胖（如 34dp 胶囊报 48dp）——视觉尺寸以截图像素扫描为准；门禁四连绿（app-test 7+0/:core:ui 绿/app-lint/app-build/make lint 0 errors）；证据 %TEMP%\qimeng-f-evidence\（截图+dump 15 件）
- 遗留记档：搜索页顶栏核对无需改码（顶栏 48dp/返回钮 24dp 字形/文本钮 14sp 均已在旧版规格上，SearchScreen.kt 不在本批文件集）；QimengCapsuleTextField 聚焦反馈沿用 G6「surfaceContainerHigh 加深一档」（浅色下 delta 2/255 极淡，未在本批反馈范围，如需强化待用户拍板）

---
## feat(app): N4 解冻消费批——多选 UI+历史作品维+统计常看族接线+标签即时解绑+服务端色+dwell/progress 端到端复测（2026-09-09 第一百六十五笔）

执行 AI：GLM-5.3-Flash（监督会话 executor；夜间授权链收官批）

- **I5b**：AlbumFilterState author/character 单选改多选集（同维 OR/跨维 AND），favorite/history 走 N2 数组参数；历史页补「作品」维行（分区/作品/角色/类型对齐 GUIDE_UI L386，facets history=1 source 桶作候选）；作者集合页 #34 SOURCE 兜底解除
- **I3b**：统计页常看文件卡（views Top3）+常看作者/标签混排卡+均值真值联动；详情页 seconds 榜 Top20/作者 Top15/标签 Top10/来源构成/来源双系列趋势——I3 冻结占位全部换真数据
- **I7b**：DetailTagSheet chip 关闭=立即 DELETE 单条解绑（乐观移除失败回滚+横幅）；时间轴标签服务端 color 优先（BiliPlayerView tintList 透传，非法 hex 静默回退前缀档保底）
- **端到端铁证（18461 升级后）**：应用内播放→dwell seconds=29 补传落库（**M4-3 以来首次**）→curl seconds 榜即时反映；progress 0.476 落库；curl 数字 202/字符串 400 对照；多选并集 4 件实证；标签解绑重进不回来；服务端色像素数学实证。迁移 0009 自动应用，cos5+normal18 基线 23 件未变
- 测试：AlbumFilterState 多选/数组投影、History 作品行映射、Stats 装配/空态/降级、Detail 解绑成功/回滚/防重、color mapper；门禁四连绿（app-test/app-lint/app-build/lint 全仓 0 errors）；证据 %TEMP%\qimeng-n4-evidence\（81 件）
- 遗留记档：统计榜单条目点击跳转（GUIDE L218-224）未做；facets 多选跨维收窄放宽（协议单值位）；/assets authorId 多 COS 作者同选降级不传（协议缺口已注释+单测锁）

---
## feat(api): N3 协议批 P2——统计聚合三端点+来源维度（#31）+标签逐条删/颜色协议化（#32）（2026-09-09 第一百六十四笔）

执行 AI：GLM-5.3-Flash（监督会话 executor；夜间授权协议批第二段）

- **统计聚合（#31）**：新增 `GET /stats/most-viewed`（metric=views|seconds，常看文件双榜）、`GET /stats/top-authors`、`GET /stats/top-tags`（range+limit 缺省 20/钳 50）；`/stats/trends` 加可选 `source=normal|cos`；`/stats/overview` 加可选 `range`（缺省 all 兼容）+响应 `sourceNormalCount/sourceCosCount/avgViewsPerFile`（分母 0→null）。DOMAIN_RULES §5 新增「统计聚合榜单口径/来源桶口径/平均浏览次数」三条（公式风格对齐既有节，既有公式零改动）
- **标签协议化（#32）**：新增 `DELETE /assets/{assetId}/tags/{tag}`（只删单条关联不动其余 created_at、幂等 204）；时间轴标签 `color`（hex6）创建/更新/响应三处透传+迁移 0009_timeline_tag_color（只加不改，up/down 对）；新增 PUT timeline-tags/{tagId} 更新端点（color 省略=清除）
- server：stats_top.go 新 handler（修 trendWindowStart 未落日界的存量窗口 bug→与 trends day 口径对齐）+9 用例（倒序/窗口边界/limit 钳制/孤儿事件/COS 作者计法/avg null）；tags +2 用例；store 层 view_events+5 查询/tags+2/migration 0009
- make sdk 三端再生（纯新增可选字段与新端点，web/android 零适配编译绿）；门禁 redocly 0 errors/go test 全 ok/make lint 0 issues/app-test 绿/web-build 绿；端到端消费属 N4

---
## feat(api): N2 协议批 P1——seconds 序列化根修（#24/#26）+筛选多值化（#29）+/history 补参（#30）+facets 作者行收窄（#34）（2026-09-09 第一百六十三笔）

执行 AI：GLM-5.3-Flash（监督会话 executor；用户授权协议批自决，待拍板 2026-09-09 夜条目②）

- **seconds 根修（#24/#26，数据正确性）**：openapi 请求侧小数字段（dwell seconds/progress positionSeconds）+prefs 9 字段+响应侧 lastPositionSeconds/system 百分比加 `format: double`——Kotlin 生成器改走原生 Double 直序列化 JSON 数字，M4-3 起 dwell/progress 被引号字符串卡 400 从未落库的缺陷根除；新增 ViewEventReportWireTest 锁 JSON 数字契约；Go gen float32→float64 六文件机械适配（行为无关）；**台账 #7 顺带闭环**：prefs okhttp 手拼绕行撤除改走生成 SDK
- **筛选多值化（#29）**：/assets、/history 的 source/character/work 改数组（同名重复参数，单值向后兼容）——**同维内 OR、跨维 AND**、character 组合出镜组内 AND、「其他」桶可混选；sqlc 谓词双层 json_each；8 项新单测
- **/history 补 source（数组）/authorId（#30）**：谓词+单测
- **facets 作者行收窄（#34）**：facets.sql 两查询补 source/author_id 谓词（实证消费方全为客户端排自身，零破坏）；DOMAIN_RULES §3 旧「一起忽略」表述废止同步
- 三端再生 make sdk（Kotlin Double/List、TS Array、Go float64）；android 调用点 listOf 最小适配+mapper Double 化；web 增 toSdkAssetListQuery 边界归一（用户面单值不变）
- 门禁：redocly 0 errors / go test 14 包全 ok / make lint 0 issues / app-test 绿 / web build 绿；端到端 dwell 复测属 N4

---
## feat(app): N1 决策落地批——相册默认 3 列（台账#3）+收藏/历史进详情批次上下文（台账#21）（2026-09-09 第一百六十二笔）

执行 AI：GLM-5.3-Flash（监督会话 executor；用户 2026-09-09 夜拍板「剩下的对齐旧版」授权）

- **相册默认列数 2→3**（台账 #3 销账）：`DEFAULT_ALBUM_COLUMNS=3`（core/data ClientPrefsRepositories），注释落档拍板；三处 Fake 常量符号自动跟随，clamp 单测不受影响。
- **收藏/历史进详情补批次上下文**（台账 #21 部分销账）：复用首页 MediaBatchIndex 单例机制——两页 VM 新增 `enterDetail(assetId)` 快照式写入当前显示清单，Screen 点卡先写批次再导航；DetailViewModel 既有批次消费零改动、导航/app 层零改动。i/N 序号+滑切对两页生效（模拟器实证：历史 2/16→3/16 横滑切件、收藏 2/2 尾件不环绕与 Web 同语义）。
- 测试：两页 VM 各新增批次上下文用例（空批次语义/追加件入批/indexOf/size/邻位+尾越界）；首轮自测两处失败（翻页闸门缺 nextCursor、缺 advanceUntilIdle）已修复并如实记档。
- 门禁四连绿（app-test/app-lint/app-build/lint 全仓 0 errors）。
- 遗留：全部页入口（冻结令豁免）与作者集合页仍无批次上下文，留在台账 #21 备注。

---
## docs(app): 任务I I9 收官走查——旧版UI实录对比总走查全过（口径A 七组结构对照+口径B 五页走查，0 P1/0 新增 P2）（2026-09-09 第一百六十笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I9 总装走查；commit 由主会话收官笔一并提交）

- 构建 728c7bb 装 qimeng_api35 实测；工作树开工/收工双检干净；18461 虚构库走查，8420 未触碰；证据 %TEMP%\qimeng-i9-evidence\（12 目录 120+ 件，uiautomator 视图树对比判静止——像素哈希会被 GIF 动图骗，I8 教训沿用）。
- **口径A（实录 dump 逐页结构对照）**：home 标题/搜索/三芯片/双列/时长角标全过（筛选图标=拍板①豁免）；搜索三态全过（占位逐字+两丸流+清除历史+建议行类型标签+结果 3 列日期分组）；全部页族冻结区只记档——四芯片带候选计数/作品·角色模式分组语义/类型保日期分组全对齐实录，无「收起▲」尾丸=D3 拍板②toggle、排序页头四档=G5 形态；favorite 空态双分支逐字（悬浮丸板收起▲=拍板⑨）；history 计数+日期分组（无清除钮=拍板③、缺作品维=#30 在案）；mine 数量卡+五行入口副文案逐字（数据管理/备份=拍板⑩豁免）；filter_sheet 观看/点击/大小/时间/标签模式/重置应用全对齐实录（排序 7 档+顺位=G5 移除记档）。
- **口径B（无 dump 五页）**：统计三档+6 指标+分类型趋势/分布详情可达（#31a-d 冻结项不渲染实证，无死入口）；详情沉浸 4 层+单击显隐（隐藏态 dump 全空树）+chrome+信息/快速转跳 Sheet+横滑翻件 i/N 更新+播放返回拦截+返回先回上一媒体全过；作者管理排序▾下拉+常规三芯片/COS 两芯片差异实证；上传/登录健康态+免密往返。
- 差异全部记档不判打回（冻结区/协议缺口/拍板保护逐条对号）；P3 记档 3 条（首页卡片文本行=信息增强向、搜索组头星期格式、收起▲两页形态并存）；存疑记档：统计三档联动因虚构库数据集中近 7 天而 UI 数值同值，联动机制由 I3 单测+走查锁定。

---

## docs(app): 任务I-Android页面复刻卷收官——I9 全卷对抗审查通过+实录总走查全过+文档同步（2026-09-09 第一百六十一笔）

执行 AI：GLM-5.3-Flash（主会话主代理，任务I I9 收官批；批次级自审+I9 独立 reviewer 全卷审查+走查 executor 总装走查，三层在案）

- **I9 全卷对抗审查通过**（独立 reviewer，范围 ab916ca..HEAD 十笔）：commit 边界与文件集逐笔核对无夹带；全卷红线零触碰（api/server/web/sdk/feature:all 空 diff）；差距清偿抽查通过+冻结项全部维持（多选/历史作品维/标签逐条/平均浏览次数等）；批间缝隙四点通过（LikeMutationTracker I1→I7 闭环、StatsRepository 双参重载共存、列数三页共档、negate-inset 数学成立）；门禁三连亲跑全绿（全仓单测 --rerun 428 tasks 全执行/assemble+lint 0 errors/make lint）。孤儿会话三笔非常规 commit（ac8ecfe 补交/1b30953 收尾/728c7bb 纯走查）复核与声明一致；I8「未等仲裁即接管收口」流程偏离记档成立不构成打回。
- **CHANGELOG 笔序校正**（reviewer P3-1）：154 笔（I5）移至 153 与 155 之间、151 笔（I2）移至 150 之前——并行批并发提交所致乱序，校正后 150→159 自顶向下有序。
- **文档同步**：REPLICATION_GAPS.md §3 八节状态收口（已清偿条目挂 commit hash、冻结/豁免维持注记、使命完成归档注记）；《待拍板-20260907.md》增 #34（facets 作者行「排自身」收窄限制，I6 消费侧裁剪兜底）/#35（首页排行榜→推荐切换哨兵误触发换 seed，存量边界）/#36（QimengMediaGrid 组头未声明 GridItemSpan，KDoc 与实现不符，存量）；HANDOVER_APP 补任务I 节。
- **收官清理（用户 2026-09-09 拍板）**：删除仓库外已复用调研笔记 m4-2/m4-3/m4-6-spec-notes.md 三份（m4-6-runtime-notes.md 为协议口径事实长期保留）；ui-compare-harness 与 m42a-review 历史档案不动。
- **任务I 全卷收官**：I1~I9 九批交付完毕；复刻基准=GUIDE_UI 的八页差距全部清偿或按拍板/协议缺口冻结在案；遗留挂账：I4 总览行直达作者集合页（待壳层共享窗口）、I5 双指真机走查（adb 无多点注入）、I7 三项证据已由总走查兜底。

---

## docs(app): 任务I I8 上传/登录健康走查——insets/可用性全过（无复刻基准，只核查）（2026-09-09 第一百五十九笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I8 批；REPLICATION_GAPS §2.1/§2.2/§3.8：GUIDE_UI 与实录均无上传/登录条目，本批不做 GUIDE_UI 对照、不进差距清单，仅 insets/可用性健康走查+对照 Web 现版大致相似。**零代码改动**——两模块走查未发现可修缺陷，feature:upload/feature:login 零触碰）

- **登录页 4 项全过**：①三 insets 健康（状态栏/手势条不遮内容；IME 弹出时表单整体上浮、两输入框与提交钮完整可见——G3 的 ime∪navigationBars 取大方案回归通过，LoginScreen.kt:62）；②错误态可用（「密码错误，请重新输入」中文完整、表单可重试；5 类 LoginError 文案资源齐全）；③dev 免密路径可用（密码留空→dev-login 进主壳，本批亲手实证+AuthRepositoryImpl password.isEmpty() 分支有单测锁定）；④键盘弹出后提交钮可达（URL/密码两字段分别实证）。
- **上传页 5 项全过**：①insets 健康（TopBar/状态栏/手势条无遮挡）；②两入口可达——SAF 多选：选择文件→DocumentsUI 长按多选 2 项→确认→待传清单+大小正确；分享接收：SEND intent→singleTask 复用跳上传页→待传+1（describe 成功），manifest SEND/SEND_MULTIPLE image/*|video/* 声明齐全；③队列态显示不遮挡（「上传成功」两行完整可见，错误/拦截卡为可点关横幅）；④实测上传（独立测试库避免污染 cos5+normal18 虚构基线：SAF 选 1 jpg+1 mp4→库根→队列两行「上传成功」；curl 对账服务端 files=2/img=1/vid=1、/assets 两条字节数与源文件一致、磁盘落库正确；测后删库+清设备/宿主文件现场还原）；⑤对照 Web 现版（LoginGate/UploadCard）布局结构大致相似：同为居中登录表单/选库→目录树→选文件→队列进度四段式；Android 以胶囊选库+SAF+纵向队列行替代 Web 下拉+拖拽+表格，属端形态适配非差距。观察记档（无复刻基准，不判差距）：Web 队列有「全部取消/清除已完成」控件，Android 串行 WorkManager 队列无对应钮。
- **门禁三连绿**：:feature:upload:testDebugUnitTest+:feature:login:testDebugUnitTest 0 failures（BUILD SUCCESSFUL）；:app:assembleDebug BUILD SUCCESSFUL；lintDebug 0 errors。
- **执行披露（并行会话，两次上报主会话仲裁未获回复，按 I7 第155笔先例披露后收口）**：①另一 I8 会话先行完成登录/上传页走查（%TEMP%\qimeng-i8-evidence\ 05:42-05:52 证据 39 件），其登录 insets/错误态/dev 免密/SAF 入口证据经复核采信，本批补其未竟的实际上传+curl 对账与分享接收链；②I6 会话持续占用模拟器至 ~06:16 后收步（其 worktree author 改动与本批无涉零触碰）；③屏幕静止判定曾被首页 GIF 动图缩略图骗过（截图哈希假变化），改用 uiautomator 视图树对比确认静止后才操作+重装自证（构建=当时 HEAD 1b30953，I6 author 改动当时未提交，feature:upload/login 两模块两版本同源）；④18461 单 token 模型下各会话 dev-login 互相重铸属预期（app 401 后回退登录页行为顺带实证，无崩溃）。
- **证据**：%TEMP%\qimeng-i8-evidence\（burst 系列 19 步=登录 dev 免密/SAF 多选/上传全链/分享接收/队列态截图+dump；复用先行会话 i8-login-*/i8-upload-* 证据）。

---
## feat(app): 任务I I6 作者页复刻——作者集合页四维芯片体系（常规作品/角色/类型·COS角色/类型）+pinch/列数共档+作者管理排序回改单钮下拉（2026-09-09 第一百五十八笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I6 作者批；独占 feature:author，依据 REPLICATION_GAPS §3.6 差距条目逐条清偿；与并行 I7 收尾会话共享模拟器实例，重装自证+紧凑窗口取证）

- **作者集合页四维芯片体系（差距1，GUIDE_UI §芯片栏配置对比 L68-69，新增整套非回改）**：AuthorCollectionScreen 从裸网格升级为维度子集芯片栏——常规作者「作品/角色/类型（无分区）」、COS 作者「角色/类型」（COS 判定=路由 authorId `cos_` 前缀，openapi Author.id 口径；`isCosAuthorId`/`authorCollectionDims`/`collectionAssetQuery`/`collectionFacetsQuery` 纯函数层新增于 feature:author 内部 `AuthorCollectionFilter.kt`）。复用 core:model FourDimPills 维度子集机制（`dimChips(model,dims)`/`pillsFor(model,dim)` 只读重载参数位承接，core:model 零改动）+ :core:ui 瘦身后组件 QimengChipRow（「角色|类型」竖分隔线对齐全部/收藏页）/QimengFloatingPillPanel（拍板⑨列表族悬浮形态）。药丸语义对齐全部页：**进页默认收起、切维度行强制展开（拍板②）**、点已激活维折叠、「全部 (N)」清行（payload=null）；多选现状单选模型保留（协议缺口 #29 冻结不扩）。ViewModel 扩展：筛选代际防乱序（镜像 FavoriteViewModel 同族方案）+筛选变化回第一页重拉 items+facets；「全部 (N)」药丸计数=服务端 /assets totalMatched（当前其他维选择下的总数，与相册页分区栏 all 桶同口径，零额外请求）。
- **协议限制记档（建议主会话进台账 §4）**：服务端 facets 作者行「排自身=source 与 authorId 一起忽略」（server/internal/httpapi/facets.go 作者行注释），**作品维（authors 桶）候选无法按 authorId 收窄**——返回全库 source∪COS 作者桶；本卷零协议，消费侧裁剪为 SOURCE 子集（kind=author 的 COS 作者候选在本页固定集合作者语义下不可作筛选参数，裁剪同时消除死药丸），故作品候选计数为全库口径（选中筛选行为仍正确：/assets authorId+source 联收）；角色/类型两桶 authorId 收窄实测正常（COS 页「测试作品M (3)」实证）。协议扩展（facets 增非排自身收窄参数或作者行细分）留待拍板。
- **双指缩放 2-5 列+列数共用全部页档（差距2，GUIDE_UI §导航结构 L36+§全部页 L149）**：`qimengPinchToColumns` 接线（照抄 AllScreen:254/I5 FavoriteViewModel 模式：adjustColumnsLive 内存步进 clamp 2..5、commitPinchColumns 手势结束持久化一次）；纯复用 `GridPrefsRepository.albumColumns/setAlbumColumns`（键 grid_columns_all），core:data 零改动；COLLECTION_COLUMNS=3 固定列退役。
- **作者管理排序形态回改（差距3，GUIDE_UI §芯片栏配置对比 L73「排序 ▾」单钮，R6 无拍板保护）**：G2 三枚排序 SegPill 直排（Web AuthorsPage 形态）回改单钮（QimengSegPill 胶囊语言）+M3 DropdownMenu 下拉标准件（当前选中项 ✓ 标识），与体系胶囊同行右置（左 全部/常规/COS、右 排序 ▾）；排序功能语义不变（AuthorViewModel.selectSort/applyAuthorRows 零改动，对所有分类+关键词后排序生效）。
- **超规格记档保留（差距4，不回改不扩做）**：作者管理计数行「全部作者 · N 位」/RankCard 行/行分隔线（G2）；作者集合页计数行「作者 · N 个文件」/空态「该作者下暂无内容。」（G1b）。
- **门禁三连绿**：:feature:author:testDebugUnitTest 25 用例 0 failures（新增：维度子集纯函数/COS 前缀判定/VM 维度子集与激活维默认/COS 不拉作品维 facets/筛选→协议参数映射/筛选重拉回第一页/facets 收窄联动×2/行内全部清行/作品维 SOURCE 裁剪/药丸进页收起切维展开/列数读共用档/pinch clamp+落档）；:app:assembleDebug BUILD SUCCESSFUL；根 lintDebug 0 errors。模拟器 18461 虚构库走查：常规作者（POST /import/qimeng-backup 协议内造数 i6walkauthor+4 文件关联，走查后复原）集合页=作品(1)/角色(0)/类型(3) 三芯片+悬浮面板展开/「其他」选中筛选/类型→视频筛后计数行联动「作者 · 1 个文件」；COS 作者集合页=角色/类型两芯片、角色面板 authorId 收窄「测试作品M (3)」；作者管理排序下拉选「浏览数」实测重排（viewCount 14/6/1）；关注 toggle 落库对账（followed=true 服务端复核后复原 false）。证据 %TEMP%\qimeng-i6-evidence\（14 张）。**已知观察（共享件 core:ui，非本批改动）**：QimengMediaGrid 组头未加 GridItemSpan（KDoc「组头跨全列」与实现不符，日期组头现占单列与首卡同行）——core:ui 冻结不属本批文件集，记待办归主会话串行窗口/I9 收官裁定。

---
## fix(app): 任务I I7 收尾——舞台negate-inset真edge-to-edge（chrome两态不位移）（2026-09-09 第一百五十七笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I I7 批收尾；仲裁 B 案执行——主会话 2026-09-09 裁定推翻 fit-viewport 收口稿，理由：真 edge-to-edge 系 GUIDE_UI L158 语义要求、两态不位移合 L273、纯 feature:detail 零共享文件改动）

- **舞台 negate-inset（DetailScreen）**：壳层 Scaffold 对 NavHost 统一 `padding(innerPadding)+consumeWindowInsets`，fit-viewport 收口稿舞台贴视口致顶部露壳底色条、切 chrome 时 Scaffold 重算 padding 致图片重居中（L273 违规）。改法：`WindowInsets.statusBars.getTop(density)` 读窗口真实 inset（consumeWindowInsets 只作用于 padding 修饰符链，raw 值不受影响），舞台盒高度加回 inset、`Modifier.layout` 绘制时向上平移并等量扣回占位高度——舞台视觉恒 [0,整屏]，chrome 切换（inset 128↔0）两态舞台屏幕框不动（图片不重居中），内容区起点恒屏底两态等高。
- **chrome 避让改原生 inset（DetailChromeBars）**：B 案首版模拟器实测暴露底部 chrome 因壳层 consumeWindowInsets 读到近似 0 的 nav inset 而下坠入导航栏区——顶/底两 chrome 的 `statusBarsPadding()/navigationBarsPadding()` 改 `padding(WindowInsets.statusBars/navigationBars.asPaddingValues())`（asPaddingValues 不受消费影响，L275 真值避让恒成立），KDoc 同步。
- **门禁三连绿**：:feature:detail:testDebugUnitTest（含点赞指纹/exitToPoster 用例）0 failures；:app:assembleDebug BUILD SUCCESSFUL；:feature:detail:lintDebug 0 errors。模拟器 18461 走查：B 案顶栏自屏顶渐变（无壳底色条）、双击缩放 1.8x 详见 %TEMP%\qimeng-i7-evidence\（35-37 号证据）；底部 chrome 避让修复后复验与海报横滑正例/快速转跳 Sheet 两项补证因并行会话持续占用模拟器未完成独立取证，代码链路已核如实记档（I9 收官总走查兜底）。

---
## fix(app): 任务I I5 补齐——滑动暂停缩略图加载 core:ui 门控参数（2026-09-09 第一百五十六笔）

执行 AI：GLM-5.3-Flash（监督会话续作 executor 实现；I5 主体=e516f12 原会话 executor，本笔闭合其缺失依赖修复 broken master）

- **修编译断裂**：e516f12 已入库两页 `pauseThumbnailsWhileScrolling = true` 接线，但承载该参数的 `QimengThumbnail`（`paused` 门控）与 `QimengMediaGrid`（开关参数，默认 false 零波及）两文件未随批提交——stash 对照实测缺此二文件 `:feature:favorite:compileDebugKotlin` 失败。本笔补齐闭合。
- 依据：GUIDE_UI L394/L411「滑动暂停缩略图加载」（H0 清单漏收项，续作按 GUIDE_UI 补做）；两页开关随 I5 批开启。
- 门禁：模块编译对照（stash 红→恢复绿）；全量四连门禁于 I7 收尾态已绿（app-test/app-lint/app-build/lint 全仓 0 errors）。

---
## feat(app): 任务I I7 详情页沉浸复刻——4层沉浸结构+chrome显隐/系统栏+信息/快速转跳BottomSheet+播放返回拦截+海报态横滑+点赞指纹接线（全链重排闭环）（2026-09-09 第一百五十五笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I7 详情页批；独占 feature:detail，依据 REPLICATION_GAPS §3.7 差距条目逐条清偿；接手工作树 I7 遗留半成品原地收尾，未回滚未重做。**执行披露**：本批 feature:detail 在盘点半成品期间检测到另一并行续作会话同面修错与走查（04:49 编译错收敛、05:10 DetailScreen 舞台高度改 BoxWithConstraints.maxHeight），两次上报主会话仲裁未获回复；本笔按现状树收口提交，含该会话同批改动，特此记档）

- **沉浸 4 层结构回归（差距1，GUIDE_UI §详情页 L158-160）**：G1a/G1b Web 排版页（68vh 舞台）回归旧版沉浸组织——第一屏媒体舞台黑底盒整屏（图片态 ZoomImageView 链 / 视频态 BiliPlayerView 链，gesture/分层/续播全链冻结不动），媒体层下方保留信息内容区（标题/meta/互动/标签/作者卡/UpNext，拍板⑧超规格件保留融入，下滑查看；chrome 挂舞台盒内只覆盖第一屏）。基准依据=台账 #33 用户拍板「1 a」按 GUIDE_UI 沉浸复刻（推翻 09-05 Web 排版基准）。
- **上下渐变 chrome（差距2，L171/172/313）**：DetailChromeBars.kt 新增顶部渐变操作层（返回/n/N/信息）与底部渐变操作层（点赞/收藏/标签/快速转跳四钮 SpaceEvenly），Brush.verticalGradient 模拟 bg_detail_top/bottom_gradient（背景色 90%↔透明）；浅底/黑底随明暗切换（L161，色取 MaterialTheme background/onBackground）；六钮按下缩放反馈 0.92→1.0/100ms（L173）；statusBarsPadding/navigationBarsPadding 避让（L275）；chrome 图标 info/sell/people 三枚自持（QimengDetailIcons 同款构造，I 卷共享文件冻结不进 core:ui，I9 后可收拢）；无批次上下文（batchIndex<0 深链单卡，#21 待拍板）计数不渲染，未补批次基建。
- **单击显隐 chrome+系统栏（差距3，L271-276）**：图片态单击舞台切 chromeVisible（AnimatedVisibility fade），chromeEffective 驱动 SystemBarsImmersiveEffect 显隐系统栏（BEHAVIOR_DEFAULT 不触发布局重排，离场 DisposableEffect 恢复）；视频态 chrome 让位播放器（VideoStage 上报 onPlayerActiveChanged：海报态=false/播放·暂停·ENDED=true）；海报态单击=起播（L163 旧版语义优先，与 L271 冲突取旧版并记档：海报态不接 chrome 切换）；▶ 恒显随 chrome（L276 口径的冲突态记档同上）。**D1 图片全屏覆盖层退役（裁决记档）**：沉浸结构下舞台本就全出血，D1 的存在理由（68vh 排版态的「半成品全屏」）消失，保留入口反与「单击切 chrome」手势冲突——ImageFullScreenOverlay.kt 整件删除，ZoomableOriginalImage 保留为舞台唯一内容件；D2 视频两级全屏覆盖层保留（拍板⑦），FullscreenOverlayShell 骨架注释同步。
- **信息 BottomSheet（差距4，L169/171）**：DetailSheets.kt DetailInfoSheet——文件名/出处/尺寸/时长四行（协议 AssetDetail width/height/durationMs 直读零解码；尺寸行齐备且>0 才渲染；时长仅视频资产有值），无「完成」按钮（下滑/点外部关闭）。
- **快速转跳 BottomSheet（差距5，L172）**：DetailJumpSheet——当前文件关联作者列表（COS 作者带·COS 后缀、空态「该文件暂无关联作者」），点击经既有 onOpenAuthor 链进作者集合页（与作者卡同链，壳层路由零改动）。
- **播放中按返回先退 chrome 浏览模式（差距6，L168/L279）**：VideoStage 挂 BackHandler（播放器活动期 enabled、全屏覆盖层打开时禁用）→ 暂停+VideoStageStateMachine.exitToPoster（新增，纯函数）+chrome 恢复显示；播放器不销毁——再点播放走同源续播不归零（L165 同款语义）。**海报态横滑切兄弟（差距7，L163）**：单指横滑（>60dp 且横速度>800，对齐 ZoomImageView 冻结阈值）→onSiblingNavigate(±1)；播放态不接（对齐旧版「预览态可横滑」边界）。
- **LikeMutationTracker 接线（差距8，I1 消费端闭环）**：DetailViewModel 注入 tracker，toggleLike 成功处 onLikeMutated()（失败不上报）；验收全链实测=详情点赞（实心拇指）→返回首页→推荐流重拉且顺序全变（对照基线截图 08/28），「浏览退出保持原样」半边由无变更指纹不变天然满足。**冻结不动**：标签逐条即时移除（#32a 草稿式现状保留）、冷启动重试（R5 豁免）、「其他标签」名字序（已拍板）。
- **残留与存疑（上报仲裁中，非阻塞）**：①壳层 Scaffold 对全部路由统一 innerPadding.top+consumeWindowInsets，详情舞台照单全收则顶部留一条壳底色条、且 chrome 切系统栏时 Scaffold 重算 padding 致图片重居中（L273 违规）——05:10 收口改法=舞台高度取 BoxWithConstraints.maxHeight 贴视口（底 chrome 可见、功能全通）；另一方案（读窗口真实 inset 平移舞台，真 edge-to-edge+两态不位移，纯 feature:detail 可实现）已上报主会话待裁，若裁另案仅动 DetailScreen 舞台盒一段，低成本回改。②图片/动图原图首次解码 3-4s 期间舞台黑屏（测试库大图放大现象，占位黑底属沉浸色系，加载完成即出图）——记档不阻塞。
- **门禁三连绿**：:feature:detail:testDebugUnitTest（DetailViewModelTest 含点赞指纹成功上报/失败不动两断言 + VideoStageStateMachineTest 含 exitToPoster 三态迁移/幂等/再起播不重装源四用例）0 failures；:app:assembleDebug BUILD SUCCESSFUL；:feature:detail:lintDebug+:app:lintDebug 0 errors。模拟器 18461 虚构库走查：沉浸舞台全出血黑底/图片 fit-center 出图、chrome 初始可见↔单击显隐两向、信息 Sheet 四行+无完成钮（视频案例 960×540/0:02）、播放中 chrome 让位+BACK 拦截退海报态 chrome 恢复、BiliPlayerView G 链控制器（倍速/静音/书签/进度/全屏）回归无损、点赞→首页重排闭环，证据 %TEMP%\qimeng-i7-evidence\（20+ 截图；海报横滑正例/快速转跳/双击缩放三项因并行会话共用模拟器互踩未取到独立截图证据，代码链路+既有单测已核，如实记档）。

---
## feat(app): 任务I I5 收藏/历史复刻——双指缩放2-5列接线+列数共用全部页档持久化+详情返回resume重拉两页（2026-09-09 第一百五十四笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I5 收藏+浏览历史批；独占 feature:favorite+feature:history，依据 REPLICATION_GAPS §3.5 差距条目逐条清偿；接手工作树 I5 遗留半成品原地收尾，未回滚未重做）

- **双指缩放 2-5 列接线（差距1，GUIDE_UI §公共UI工具 L300+§全部页 L149，R2 裁决图标豁免不覆盖手势）**：FavoriteScreen/HistoryScreen 网格容器接线既有 QimengGridPinchGesture（qimengPinchToColumns onStep/onGestureEnd，与 AllScreen.kt:254 既有接线逐行同构——feature:all 冻结区只读参考）；VM 侧 pinchColumns 瞬时值逐帧内存反馈、手势结束 commitPinchColumns 统一持久化一次，clamp 2..5 由 MIN/MAX_ALBUM_COLUMNS 常量锁定。
- **列数持久化共用全部页档（差距2，GUIDE_UI L149 v1.15 口径「收藏/浏览历史/作者文件/全部→updateGridColumnsAll」）**：两页 VM 纯复用既有 GridPrefsRepository.albumColumns/setAlbumColumns（键 grid_columns_all），初始值 Eagerly stateIn 读档、手势结束回写同档——**core:data 零改动**（半成品已按此口径实现，本批核实并保留）；单测锁定初始读档（预置 4 非 2）/clamp 边界/落盘/幂等 no-op 四点。
- **收藏页 resume 重拉（差距3，GUIDE_UI §收藏页 L410 详情返回自动刷新）**：FavoriteScreen 挂 ON_RESUME LifecycleEventObserver（镜像 HomeScreen I1 模式，覆盖详情 pop 返回与 App 回前台两路径）触发 VM 重拉第一页+候选；防叠加风暴=首个 ON_RESUME 与 init 首载天然重叠跳过 + 后续复用 refresh 的 isRefresh+isLoading 在途防重（不另造指纹）。
- **历史页 resume 重拉（差距4，GUIDE_UI §浏览历史 L391 Flow 自动性语义）**：同款接线——详情浏览上报后 lastViewedAt 已变，服务端化后旧版 Room Flow 自动重排丢失，ON_RESUME 重拉补偿；同款防叠加。
- **冻结项不渲染（差距5，协议缺口 §4-#29/#30 记档）**：作品/角色多选维持 AlbumFilterState 单值模型（/assets、/history 参数单值）；历史页「作品」维维持三维子集（/history 无 source/authorId）；无清除按钮（拍板③）——均不进本批，UI 不留半成品。
- **门禁（口径披露）**：:feature:favorite/:feature:history testDebugUnitTest --rerun 实跑各 10 tests 0 failures（各含 I5 新增 4 用例：初始读档/pinch clamp+持久化/resume 首载跳过+后续重拉/resume 在途防重）；:app:assembleDebug 与 lintDebug **在 git worktree（HEAD+本批 diff）验证 BUILD SUCCESSFUL**——主工作树 :feature:detail 被 I7 并行批沉浸复刻半成品（未完成、不归本批）编译挡住，两 feature 模块互不依赖不受影响。
- **模拟器 18461 虚构库走查（与 I4/I7 并行共享）**：收藏 resume 链=详情取消收藏→返回收藏页即时回「0 文件」空态（旧缓存不残留）；历史 resume 链=历史页点 W-01 进详情浏览→返回即重排至首位（旧序散图-02 退居第 2）；空态双分支文案/统计行/四芯片行/悬浮面板/日期分组 dump 结构在位。**pinch 双指注入受限披露**：adb 无多点注入 API，sendevent（reader 层确认两指 down/move/up 流完整）与 emulator console event send 两条通道均无法触发 Compose calculateZoom 步进（冻结参照系全部页同组件接线对照实验同样无效，判定为合成事件流与真实触摸的合批差异，非本批接线缺陷信号）；组件为 M4-2A-B2 已交付生产件+VM 语义 10 用例单测锁定，真机双指走查留待实机。列数写侧（pinch→持久化）重启保持与共档交叉验证依赖同一注入通道，同记受限；读侧共用档实证=两页+全部页网格同为 grid_columns_all 缺省 2 列（run-as 读 client_prefs.preferences_pb 该键未写档，读缺省一致），证据 %TEMP%\qimeng-i5-evidence\（resume 链前后 dump+截图+getevent 校准日志）。

---

## feat(app): 任务I I4 我的页复刻——图片/视频数量卡+主题色彩行+入口行副文案两行化+作者总览行接线（2026-09-09 第一百五十三笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I4 我的页批；独占 feature:settings，依据 REPLICATION_GAPS §3.4 差距条目逐条清偿）

- **图片/视频数量卡（差距1，GUIDE_UI L252）**：页首（标题之后、ServerUrlCard 之前——旧版页首即数量卡的实录语义）新增两卡并排「图片 N」「视频 N」（标题在上数字在下，实录 mine.txt 结构）；数据源复用既有 StatsRepository.overview()（GET /stats/overview 的 imageCount/videoCount 纯计数，零协议改动，不触拍板⑤容量豁免）；读失败/未就绪降级数字位显「—」不崩不弹横幅（装饰性计数不构成操作反馈，writeError 族口径不变）。
- **主题色彩行（差距2，GUIDE_UI L253+L268）**：新增不可点击纯展示行（EntryRow onClick=null），副文案「跟随手机白天/深色模式自动切换」实录逐字；行位按 GUIDE 行序=作者管理（+上传）之后、推荐偏好之前。
- **入口行两行化（差距3，实录 mine.txt 逐字）**：EntryRow 改「标题+副文案」两行结构（subtitle 参数；detail 右灰字参数保留供版本行等旧形态行），各行副文案逐字——收藏「查看收藏的图片和视频」/浏览历史「查看最近打开过的图片和视频」/作者管理「管理作者与关联文件」/推荐偏好「调整首页推荐算法的权重偏好」；推荐偏好行当前预设名从行 detail 挪走不再展示（实录无此展示，当前项高亮已在 BottomSheet 内，GUIDE L255），BottomSheet 四预设+整行应用+高亮回归走查无损。行序重排为 GUIDE L253 口径：收藏→浏览历史→作者管理→上传（M4-5 新增无旧版锚点，保持作者管理后）→主题色彩→推荐偏好。
- **AuthorOverviewCard 接线核实（差距4，存疑记档条）**：核实结论=原注释「行点击进作者集合页待 G1b 批接线」过时——G1b 实际接的是作者管理页 AuthorScreen 行点击（QimengNavHost 实证 AUTHOR_COLLECTION 路由在作者管理页承接），我的页总览卡 Top5 行此前无 onClick。本批补接线：OverviewAuthorRow 可点，onOpenAuthor 当前传 onOpenAuthors（进作者管理页，经其行点击继续进作者集合页，模拟器全链实测闭环）；**总览行直达作者集合页需壳层为 SettingsScreen 增配 authorId 回调（QimengNavHost.kt 属共享文件，本批只读红线未动）——记「需共享窗口」项**。
- **拍板豁免不动**：数据管理/数据备份/兼容性检查三入口（拍板⑩）、作者总览卡形态（拍板⑥保护）均未回改。
- **门禁三连绿**：:feature:settings:testDebugUnitTest 11 tests 0 failures（新增数量卡 2 用例：init 拉取 5721/414 实录锚点落地、读失败降级置空不崩不弹横幅且不牵连总览/版本初始化链）；:app:assembleDebug BUILD SUCCESSFUL；lintDebug BUILD SUCCESSFUL 0 errors。模拟器 18461 虚构库（dev 免密）走查：数量卡「图片 17」「视频 6」与同库 Web /api/v1/stats/overview（imageCount=17/videoCount=6）一致、主题色彩行纯展示、五行副文案逐字、作者总览行→作者管理→作者集合页两跳点击链闭环、推荐偏好 Sheet 四预设+「当前」高亮无损，证据 %TEMP%\qimeng-i4-evidence\（4 截图）。

---
## feat(app): 任务I I3 数据统计复刻——三档回改+数字卡6指标联动+趋势marker交互+分类型趋势/分布详情页（协议缺口#31冻结项不渲染）（2026-09-09 第一百五十二笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I3 数据统计批；独占 feature:stats，依据 REPLICATION_GAPS §3.3 八项裁定逐条清偿）

- **时间范围三档回改（裁定1，R10）**：core:model StatsRangeOption 删 90 天档回 GUIDE_UI 三档 7天/30天/全部（apiRange 映射 7d/day/all 保留「day=近30天逐日」陷阱注释；新增 detailTitleSuffix 后缀「近7天/近30天/全部」供详情页标题），全仓 grep 无其他 90 天档引用；StatsRangeTest 五用例锁定（含「90 天档废止」防回加锁）。
- **数字卡 6 指标改造+全局联动（裁定2）**：StatsScreen OverviewCards 换 GUIDE_UI L209-211 指标集——第一行总浏览次数/总播放次数/总浏览时长=**/stats/trends 桶求和随档位联动**（TrendPoint 含 playCount/seconds 字段，窗口值与趋势同请求同源同防重，无第二覆盖窗口）；第二行总文件数/总占用空间=overview 静态；「平均浏览次数」=协议缺口 #31a 冻结显示「—」占位保 6 格形态；旧「图片/视频/今日浏览」Web 残留格删除。时长格式化 formatDurationSeconds 四档（秒/分/小时/天，纯函数单测）。
- **趋势交互（裁定3）**：QimengTrendLineChart 增加 markerController 参数位，统计页趋势卡实装 Vico DefaultCartesianMarker（rememberTrendValueMarker：MarkerCorneredShape 圆角描边气泡+系列色圆点 indicator+自定义 ValueFormatter）+ rememberToggleOnTap 点击显隐，点击数据点高亮出数值气泡（官方 2.5.1 API 当场核验，非自绘）。
- **趋势卡进详情（裁定4）**：趋势卡整体可点+右上「分类型趋势 ›」入口，携当前档位进 StatsDetail。
- **分布统计小入口卡（裁定5）**：纯文字卡「类型与来源的库存构成/查看详情 ›」进分布详情（来源维度 #31b 冻结，详情只做类型库存）。
- **统计详情页 StatsDetail（裁定6，新路由 stats_detail/{mode}/{range}，路由契约单源 feature:stats）**：「类型浏览趋势」卡=图片/视频/动图多系列（复用 QimengTrendLineChart series+seriesColors，mediaType 单值三次并发调用拼系列）+调用点自组图例行（H3 记档轻方案，未扩封装）+气泡含系列名（Point.color 反查）；「类型分布对比」卡=overview 类型库存 QimengRankCard 形态+相对第一名进度条+前三名排名主题色高亮；空态「暂无数据」。常看文件/常看作者标签两模式（#31c/d 冻结）不进 StatsDetailMode 枚举不渲染入口（交付报告记档）。
- **跳转链（裁定7，协议内最小实现）**：Search 路由加 q 可选参数（defaultValue 空串=无词入口态行为不变）+SearchScreen 加 initialQuery 默认参透传（进页即 submit，GUIDE v1.15 携词语义；feature:search 内部逻辑未动）——唯一调用方常看标签卡属 #31d 冻结，管道先就位记档；类型分布行=聚合值无单文件落点、趋势桶条目同为聚合，协议内无可达成跳转目标，条目不设点击（记档简化）。
- **【边界披露需共享窗口认账】core:data 加参**：裁定6 要求的 mediaType 参数客户端唯一出口=StatsRepository.trends(range)，原签名无 mediaType 而 core:data 不在 I3 授权清单；已两次上报（RespondToCoordinator+AskUserQuestion）未获回复，按裁定6 明示要求以最小面落地——接口加**默认方法双参重载**（单参保留为抽象，既有实现/调用零感知，I1 并行批 LikeMutationTracker 为不同文件零冲突），SdkStatsRepository 覆写实传 MediaType 枚举；接口 KDoc 与交付报告双记档，如裁决另议回退仅动 StatsRepositories.kt/SdkStatsRepositories.kt 两处。
- **门禁三连绿**：:feature:stats:testDebugUnitTest 21 tests（StatsViewModel 8 含三档映射/窗口求和/联动/防重扩展、StatsDetailViewModel 7 含路由解析/多系列拼装/分布派生/空态、StatsFormatters 6）0 failures；:feature:search:testDebugUnitTest 12 tests（initialQuery 签名改动回归）0 failures；:core:data/:core:model（StatsRangeTest 5）全绿；:app:assembleDebug BUILD SUCCESSFUL；lintDebug BUILD SUCCESSFUL 0 errors。模拟器 18461 虚构库走查：三档切换联动（logcat range=7d→day→all）/数字卡 6 格/趋势点气泡「0次」/详情页多系列+图例+「图片 11次」系列名气泡/分布对比卡进度条/冻结项无死入口，证据 %TEMP%\qimeng-i3-evidence\（WALKTHROUGH-RECORD.md+9 截图+3 dump+请求日志）。

---
## feat(app): 任务I I2 搜索页复刻走查——IME 遮挡实测判定通过（无需修复）+H1 换件回归确认，零代码改动纯走查批（2026-09-09 第一百五十一笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I2 搜索批；独占 feature:search，REPLICATION_GAPS §3.2 两条目全走查）

- **键盘适配走查通过（GAPS §3.2 存疑条=R12 裁决 I2 走查项，实测判定无需修复）**：18461 虚构库（QIMENG-TEST）+ qimeng_api35 模拟器三态实测——搜索页聚焦弹 IME（`mInputShown=true`）后，①输入框（顶栏底边 y=307 ≪ IME 上沿≈1510）不被遮挡、输入与 IME Search 动作正常；②uiautomator dump 逐节点 bounds 对比（输入框占位/推荐搜索区头/搜索历史区头 IME 前后逐像素相同）证明布局零调整=Compose edge-to-edge 无 ime insets 消费天然等价旧版 `SOFT_INPUT_ADJUST_NOTHING`（GUIDE_UI L130）语义；③列表 IME 开启时下沿被覆盖、收起后滚到底全部可达（与旧版基准行为一致，非缺陷）；④旧版动机「避免底栏顶到键盘上方」结构性满足——搜索路由为覆盖页壳层 bottomBar 不组合。无黑边/跳动/压瘪异常。
- **H1 换件回归确认（知悉项：QimengSegPill 内部换 M3 FilterChip）**：搜索页词丸流（推荐搜索/搜索历史两区 QimengWordPillFlow）软底胶囊无描边、FlowRow 换行正常；建议行「icon+候选名+右侧类型徽标（COS作品）」、顶栏三件、结果态日期分组头与实录 search_entry/suggest/results.txt 结构逐项对齐，无视觉回退（大致相似口径）。
- **交互链走查**：输入防抖拉建议（suggest 只匹配名字索引五维——「01」空建议为正确行为）/IME Search 与按钮提交同链/结果态词保留/点搜索栏回建议态词保留/返回族 建议→空态清词→退页回首页，全链符合 GUIDE_UI §搜索页。
- **门禁三连全绿**：`:feature:search:testDebugUnitTest` BUILD SUCCESSFUL（12 tests, 0 failures, 0 errors, 0 skipped）；`:app:assembleDebug` BUILD SUCCESSFUL；`lintDebug` BUILD SUCCESSFUL（0 errors, 25 warnings 存量）。证据 %TEMP%\qimeng-i2-evidence\（WALKTHROUGH-RECORD.md+9 截图+7 dump）。零代码改动（纯走查批），commit 仅含本笔 CHANGELOG。

---
## feat(app): 任务I I1 首页复刻——刷新清空三tab缓存+点赞返回重排指纹（LikeMutationTracker，detail侧接线归I7）+胶囊按下缩放（2026-09-09 第一百五十笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务I-Android页面复刻卷 I1 批；依据=docs/REPLICATION_GAPS.md §3.1 三条差距逐条清偿，清单外不做）

- **下拉刷新清空所有 tab 排序缓存**（GUIDE_UI §下拉刷新 L86，差距①）：HomeViewModel.refresh() 重写——当前 tab 立即重拉（推荐=B 站式换 seed 全量重排；COS/排行=重拉当前页），另两 tab 数据缓存清空+loaded 标脏，切入时经既有 switchTab 懒重拉，不再残留刷新前旧数据；当前 tab 不预清数据（在途防重拦截后不白屏，响应落地即整体替换）。行为单测×2 锁定（当前 tab 立即重拉且换 seed/另两 tab 标脏缓存清空/切入懒重拉发新请求）。
- **点赞后返回自动重排**（GUIDE_UI L89 likeVersion 指纹维度，缺失②=I1 核心项）：:core:data 新增 LikeMutationTracker（Hilt 单例，纯内存指纹=likeVersion 计数器+变更时间戳，不持久化；KDoc 写明 SSE 无 like 事件（openapi.yaml /events 清单），本地感知是协议内唯一路径；**detail 侧上报点=I7 批在详情页点赞成功处调用，全链实测留 I7**）；HomeViewModel.onHomeResumed() 指纹对比（首次回调只采纳基线，进页不误刷），变化→重拉当前 tab（推荐走刷新路径换 seed——同 seed 服务端返回同一打散序、重排不可见；重排效果由服务端打分决定，客户端不做语义假设），无变更不重拉=「浏览退出保持原样」半边天然满足；HomeScreen 以 LifecycleEventObserver ON_RESUME 接线（覆盖详情 pop 返回与 App 回前台两路径）。本批验收=tracker 单测×3+首页响应单测×2+模拟器「浏览返回不重拉」走查。
- **三胶囊按下缩放反馈 0.92→1.0**（GUIDE_UI §UI约束 L312 微交互，缺失③）：QimengSegPill（全仓胶囊渲染单源，一处补齐全局生效）补 pressed scale——自持 interactionSource+collectIsPressedAsState+animateFloatAsState（tween 100ms FastOutSlowInEasing 对应旧版 PressAnimation，常量具名 SEG_PILL_PRESSED_SCALE=0.92f），graphicsLayer 块内延迟读取、缩放动画不触发重组；模拟器像素实测：按下时选中胶囊宽比 0.911/面积比 0.826（≈0.92²），松手回弹 1.0。
- 门禁：:feature:home testDebugUnitTest（7 用例含 4 新）+:core:data testDebugUnitTest（LikeMutationTrackerTest 3 用例）+模块 lintDebug 全绿；模拟器 18461 虚构库（QIMENG-TEST）走查：下拉刷新后切 COS/排行榜各自发出全新请求（旧实现命中缓存不重拉）+当前 tab 换 seed 立即重拉；详情返回无列表重拉（浏览保持原样）；证据 %TEMP%\qimeng-i1-evidence\（请求序列 logcat+前后截图+按下缩放双帧）。
- 记档：①emulator-5554 为 I1/I2/I3 并行共享，走查中观测到并行批操作交错（幽灵 tab 切换/他批下拉刷新的 seed++ 请求/他批重装触发的重登录），关键断言以紧凑单窗口请求序列与单测为准；②既有边界行为（非本批引入、按范围红线不动，待拍板）：小库（18 文件一次全揭示）下 RANK→RECOMMEND 胶囊切换时周期行消失使 pager 视口变高，距底哨兵（core:ui QimengMediaGrid lastVisible≥total-1-6）在阈值上触发 appendNextSeedRound 换 seed 请求一次，与「切 tab 不重拉」相悖，大库不可见，如需清偿另开小批。

---
## docs(app): 任务H-Android复刻前置卷收官——H3 全卷对抗审查通过+P3 清偿+文档同步（2026-09-09 第一百四十九笔）

执行 AI：GLM-5.3-Flash（主会话主代理，任务H H3 收官批；批次级审查 H1/H2 各有独立 reviewer 报告在案，本笔=全卷收官）

- **H3 全卷对抗审查通过**（独立 reviewer，范围 bc2efb9..HEAD 四笔）：全卷零协议/零 sdk/零 feature:all 实证；6ac9849 stringResource 上提与 H1 换件叠加无回归（占位符全 %s 型、无 remember 跨重组记忆）；H0→H2 交付一致性成立（QimengTrendLineChart 多系列+marker 预留满足 REPLICATION_GAPS §3.3 承诺，**图例能力缺口记档供 I3**）；三套门禁 reviewer 亲跑全绿（assembleDebug/全仓 lint 0 errors/单测 --rerun-tasks 无缓存重跑/make lint）；模拟器终态抽查（Vico 趋势两档重渲+相册页 G5 三要素）证据 %TEMP%\qimeng-h3-evidence\。
- **收官清偿 P3×3**：①StatsViewModel StatsUiState KDoc 的 C1/C2/C3 死引用改标「已被 2026-09-08 完全复刻拍板覆盖，I3 回改，趋势渲染见 ADR-0018」；②ADR-0018 与 libs.versions.toml 注释的 stdlib 版本精度拆分（3.3.1→2.4.10、2.5.2→2.4.0，均超 Hilt kotlin-metadata-jvm 2.3.0 上限，否决结论不变；勘误 148 笔「均 2.4.0」表述，历史条目按惯例不改正文）；③REPLICATION_GAPS.md §3.3 两处定位更新到 HEAD 现状（TrendChart 已删→TrendCard:117/QimengTrendLineChart:128）+ 图例缺口记档 + §4 #33 状态明确为「建议口径执行、非已拍板」。
- **文档同步**：HANDOVER_APP 文头收官记录+新增任务H 节（四批交付与证据目录）；M4-3 排版基准块加注「09-05 拍板已被 09-08 完全复刻覆盖，台账 #33 知情条，建议口径=I7 沉浸复刻先行」；通用约束 4「规格书与 Web 现版冲突停手问用户」对齐裁决优先级口径（用户拍板 > GUIDE_UI > Web 现版）。
- **任务I 前置就绪**：REPLICATION_GAPS.md 定稿移交，I1~I8 按 feature 互斥可并行、I9 收官。

---
## feat(app): 任务H H2 统计页折线图换 Vico——2.x compose-m3 锁版+可复用封装+Canvas 退役（2026-09-09 第一百四十八笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务H-Android复刻前置卷 H2 批；拍板=用户 2026-09-08 原话「不要自绘」「统计页折线图换 Vico」，**推翻 C3 批次 Canvas 自绘拍板并记档**，StatsScreen 注释已同步改写不留死引用）

- **锁版 Vico 2.5.1**（`com.patrykandpatrick.vico:compose-m3`，ADR-0018）：官方源当场实测逐版核查（compose-m3 构件 .module + AAR aar-metadata）——全局最新稳定线 3.3.1 与 2.x 末位 2.5.2 均 kotlin-stdlib 2.4.0（@Metadata 超出 Hilt 2.58 kotlin-metadata-jvm 上限 2.3.0，同 Coil 3.5.0 否决因），**2.5.1 = 2.x 线在冻结工具链（AGP 8.13.2/compileSdk 36/Kotlin 2.3.21）上的末位可落稳定版**：stdlib 2.3.21 对齐、三构件 minCompileSdk=36 实测、bom requires 2026.05.00 被工程 2026.06.01 覆盖。注意：官方 Maven group 为 `com.patrykandpatrick.vico`（patryk**andpatrick**，非任务书草稿里的 patrykmichalik）。
- **可复用封装 QimengTrendLineChart**（feature/stats 新文件）：系列数可配（Vico 事务一次 add=一条系列，天然多系列）+ 系列颜色可配（数量不足 require 抛错防静默串色）+ **marker/persistentMarkers 参数位预留**（I3「点击数据点高亮+数值气泡」直接传 DefaultCartesianMarker/persistentMarkers，本批零交互）；视觉近似旧趋势卡=渐变面积（AreaFill.single+ShaderProvider.verticalGradient 顶部 0.25 透明度对齐旧 AREA_ALPHA）+折线+6dp 数据点，X 轴日期标签防重叠抽稀由 Vico ItemPlacer 内置（替代旧 labelStep 手工截断），无 Y 轴（旧右上角最大值参考标签属读数辅助，不复刻）；StatsScreen 数据装配零改动（TrendPoint 流不动，只换渲染层）。
- **Canvas 实现退役**：StatsScreen TrendChart 手绘（路径/渐变/数据点/文本测量）整体删除，连带 AREA_ALPHA/LINE_WIDTH_DP/DOT_RADIUS_DP/MAX_X_LABELS 四常量；数字卡/时间范围四档胶囊逻辑不动（三档回改归 I3）。
- **顺手清偿（H1 审查 P3 遗留）**：QimengSegPill KDoc 删「补 SpaceS 凑 14dp」矛盾 bullet（终态=不额外补白）；core/ui theme/Dimens.kt 文件头第 3 来源列表与 ScreenPaddingHorizontal 注释两处 QimengPlaceholderPage 死引用清零（出处改挂 component/ComponentDimens.kt）。
- **新增 ADR-0018**（Android 图表库选型 Vico）+ INDEX.md 索引行同步；ADR-0014 白名单扩一项（用户原话拍板）。
- 门禁：:feature:stats testDebugUnitTest + make app-build/app-test/app-lint + :feature:stats lintDebug 全绿；模拟器 qimeng_api35（18461 虚构库）目检：趋势卡默认档与切换档各一截（重拉重渲正常），证据 %TEMP%\qimeng-h2-evidence\。

---
## refactor(app): 任务H H1 :core:ui 组件瘦身——M3标准件替换+死代码清理（2026-09-09 第一百四十七笔）

执行 AI：GLM-5.3-Flash（执行子代理，任务H-Android复刻前置卷 H1 批）

- **胶囊单源标准件化**：QimengSegPill 内部实现从自绘 Text+clip+background+clickable 换 M3 FilterChip（对外签名零变化，全仓胶囊渲染仍单源，feature 调用点零改动；目检微调=label 不额外补白，m3 1.4 内建留白已≈旧版 14dp）；分段切换不选 SegmentedButton 的理由=连体分段布局与消费方 spacedBy 独立排布冲突、改动面大，按任务书「改动面小者为准」选 FilterChip。G6 胶囊 token 逐项保留（PillCornerRadius=100dp/选中主色实底+onPrimary+SemiBold/未选 surfaceVariant，border=null 去 FilterChip 默认描边）。
- **筛选面板内部标准件化**（基座已是 ModalBottomSheet 不动）：SheetButton 手绘 Box→M3 Button（压平投影保旧版平面观感，软底档覆盖 surfaceVariant）；AddTagRow→TextButton（Text 撑满回左对齐保实录形态）；年份下拉锚点手绘药丸→M3 可点击 Surface；单选行 clickable→selectable(role=RadioButton) 无障碍语义升级（视觉零变化）。**例外记档**：TagChip 保留手绘——两轮 FilterChip 化模拟器实测均破坏长按删除（①常态 FilterChip+外层 combinedClickable：内层手势吞长按且长按抬起误转点击；②enabled=false 纯视觉化：连单击都到不了外层），按「以不破坏功能为前提」回退，待 M3 出带长按芯片或手势可穿透再收编。
- **零签名改动核对**：feature 调用点全部零改动（QimengSegPill 签名不变、删除件零调用）。
- **死代码清理**：QimengPlaceholderPage（M4-0 零调用遗留）删除，其宿主文件仅存 `Dimens.ScreenPadding`（login/settings/stats 三处引用）改名 ComponentDimens.kt；QimengPillFlowRow（疑零调用 grep 全仓核实仅自引用）删除，「收起 ▲」折叠语义由 FloatingPillPanel/ValuePillFlow 分承。
- **保留记档**：QimengGridPinchGesture（捏合切列数无标准件）、QimengMediaGrid/QimengThumbnail/QimengScaffold（布局薄封装）、QimengCapsuleTextField（已是 M3 OutlinedTextField 薄封装，搜索框不换 SearchBar=改动面小者）、QimengRankCard/QimengTitleRow/TabScrollController/QimengFormat；icon/ 两文件自持矢量=体积决策在案不动。
- **卫生清偿**：QimengScaffold 空态/加载态裸 48dp/96dp 换已登记 QimengDimens token；QimengPills 竖分隔线自绘 Box→M3 VerticalDivider。
- 门禁：单测（:core:ui+全仓 testDebugUnitTest+:core:model:test）/assembleDebug/:core:ui lintDebug 全绿；全仓 lintDebug 的 6 errors 系 master 既有（:feature:detail DetailScreen.kt LocalContextGetResourceValueCall，git stash 对照实验证实与本批无关）。模拟器 qimeng_api35 同机位前后截图对比大致相似，全部页 G5 三要素（排序 pill 行/in-flow 值行/卡片日期行）无回滚；筛选面板长按删除标签全链路（长按→确认框→删除→重添加复原）实测通过。

---
## docs: 任务G-Android对齐卷收官——G7 全卷对抗审查+P2/P3 清偿+文档同步（2026-09-08 第一百四十六笔）

执行 AI：GLM-5.3（主代理调度；reviewer 全卷对抗审查/主代理修复，任务G-Android对齐卷 G7）

- **全卷对抗审查**（7970592..faaa181 十笔=维护清偿三笔+任务G 七笔）：铁律全数达标（UI 零直调 API/零 SDK 手改/删除=回收站端点/零硬编码色/零旧代码搬运）；G5 排序四档、G2 计数口径、G1b includeCos 与 Web 三处逐字核对一致；全部单测 --rerun-tasks 强制重跑逐套件核对（101/12/57/9/91 全绿+web 94/94）。
- **P2 清偿（方向锁转场窗口）**：快照恢复方案在详情页互 push 转场期新页先组合、旧页后 dispose——新页快照捕到旧页 exitToNone 残留的 PORTRAIT 并代代相传，会话级永久锁竖屏（本会话上午修复的 bug 类窄路径复发）。终修=离场无条件恢复 UNSPECIFIED（全仓唯一方向写入点在 VideoStage，grep 证；manifest 不锁方向=App 自然基线恒 UNSPECIFIED，语义正确且更简）。
- **P3×7 清偿**：filterByZone 死代码+尸注释+对应用例删除（G2 计数口径改全量后残留）；整理弹窗 targetDir 提交与判定统一 trim（尾空格目录原样发出会 400）；ViewEventSender catch(Exception) 补 throwable 全栈；LoginScreen 底部改 ime∪navigationBars union（顺序叠加在键盘弹出时多让一个手势条高度）；CHANGELOG 两处计数勘误（137笔标题 P3×5、G1b 新增 24 测）。
- **门禁**：testDebugUnitTest+lintDebug+assembleDebug 三连绿（串行；lint 0 错误 24 版本漂移警告均存量）；lint 曾在后台与前台构建并发时假红一次，串行复跑绿=并发冲突非真问题。**环境**：18461 虚构库复原（改名/删除均 API 恢复 23 件基线）；dev token TTL 过期链实证（dwell 401 终局丢弃+清 token 回登录+空密码重登）=正常行为记档。
- **文档同步**：HANDOVER_APP 文头任务G 收官条目、HANDOVER.md 任务书入口段 D/E/F/G 四卷收官、任务书（仓库外）全部勾选。

---
## feat(app): 任务G G1b 详情页交互补齐——文件操作+作者集合页（2026-09-08 第一百四十五笔）

执行 AI：GLM-5.3（主代理调度；executor 执行/主代理模拟器全链验证，任务G-Android对齐卷 G1b）

- **文件操作（对齐 Web FileOpsButton）**：互动行右端「整理/删除」两钮（DriveFileMove/Delete 自持图标）；整理弹窗=新名输入预填 fileName+目标目录预填当前目录（**简化取舍：Web 目录树选择器→文本输入「库内相对路径，留空=库根」，目录树组件留后续批**）+409 同名领域异常中文文案；删除=danger 二次确认（文案逐字对齐 Web，明示回收站可恢复=铁律4）→成功 Toast+onBack()。Repository 端口扩 moveAsset/deleteAsset（封 SDK 既有端点，零协议）；AssetDetail 补 directory 字段透传。
- **作者集合页**（新路由 author_collection/{authorId}/{authorName}，路由契约单源在 feature:author 沿 DetailRoutes 范式）：QimengTopBar(作者名)+计数行「作者 · N 个文件」+日期分组网格+onNearBottom cursor 增量分页+空态「该作者下暂无内容。」；数据复用 MediaRepository.assets(AssetQuery(authorId, includeCos=true)) 零扩端点（includeCos=true 对齐 Web CollectionPage 口径，单测锁定——不补则 COS 作者集合恒空）。**三处接线**：AuthorScreen 行点击（G2 占位替换）/详情页作者卡名字可点（主色+clickable）/UpNext 副行**按 Web 基准跳过**（Web 副行作者名非独立链接+AssetSummary 无作者 id，现状=与 Web 一致非缺口）。
- **新增 24 测**（feature:detail 101=90+11/feature:author 12 含路由编码 6+VM 分页 6/core:data 57 含 directory 映射）：整理表单可提交判定/路由编码中文与斜杠劈裂/取数参数/409 领域文案/删除回调。
- **模拟器全链验证**（18461 虚构库）：改名「…gif→…gif%2dG1B」生效（详情标题刷新+API 对账）→API 恢复原名；删除→danger 确认→回列表→trash API 实证在位→API restore 恢复 23 件基线；作者管理行点击与详情作者卡点击均进集合页（测试作者一·3 文件·周日/周六/周四分组）。**中途实证 dev token TTL 过期链**：dwell 401 终局丢弃（队列口径正确）+AuthInterceptor 清 token 回登录页+空密码重登恢复——正常过期行为记录在案。截图 %TEMP%\qimeng-g1b-evidence\。

---
## feat(app): 任务G G1a 详情页排版对齐 Web（2026-09-08 第一百四十四笔）

执行 AI：GLM-5.3（主代理调度；executor 执行/主代理模拟器验证，任务G-Android对齐卷 G1a）

- **舞台高度钳制**：图片舞台固定 aspectRatio 改 heightIn(max=屏高×0.68f)+黑底 contain（常量 DETAIL_STAGE_MAX_HEIGHT_FRACTION 注明来源 Web .asset-stage max-height:68vh）——竖图不再撑超一屏；视频舞台不动（播放器自适应已有 letterbox）。
- **asset-pager 行**（对齐 Web 交互形态）：舞台下方「上一件 ‹ | n / N | › 下一件」行（边界 disabled、无批次上下文整行不渲染=已知 #21 单卡语义保持）；复用 onSiblingNavigate 单源；**顶部行 i/N 文本退役**（旧版形态，Web 无——「完全一致」口径）。
- **互动钮 active 视觉对齐 Web**：primaryContainer 软底退役→primary 实底+onPrimary+图标 Filled/Outlined 变体切换+点击 bounce（Animatable+spring(DampingRatioMediumBouncy) 回弹，参数常量注明 Web :active scale 语义）。
- **meta 行分隔**：相邻可见项间「·」分隔（对齐 Web .detail-meta span::before）。
- **右栏卡片化**：作者卡+推荐栏套 QimengRankCard（G2 批共享件复用，内部结构零改动）。
- **图标**：core:ui QimengDetailIcons 补四枚自持 ImageVector（ThumbUpOutlined/StarOutlined/ChevronLeft/ChevronRight，path data 逐字取自 google/material-design-icons 官方 24px.svg，图标自持纪律）。
- **验收**：compile+feature:detail 90 单测全绿；模拟器实测：首页流进详情 pager「1 / 18」在位、点下一件「2 / 18」换件成功、meta「浏览 0 · 播放 0 · 42 KB · 9-3 · …」分隔在位、相册入口（无批次）pager 正确回退不渲染；截图 %TEMP%\qimeng-g1a-evidence\。

---
## feat(app): 任务G G2 作者总览进「我的」+ AuthorScreen Web 形态重排（2026-09-08 第一百四十三笔）

执行 AI：GLM-5.3（主代理调度；executor 执行+两处 Web 口径纠偏/主代理模拟器验证，任务G-Android对齐卷 G2；用户拍板=「只把作者总览放到我的界面，web的作者管理替换掉现在安卓端的作者管理」）

- **「我的」页作者总览卡**（Web DataPage 形态，替换「关注的作者」区）：区头「作者总览|管理」+双计数副行「N 位作者 · 已关注 M」+按文件数 Top5 行（displayLabel 单源 ·COS 标记+「N 个文件」）+rank-card 卡片底；SettingsViewModel.refreshFollowed/unfollow 退役改 refreshAuthorOverview（authors() 全量+toAuthorOverview 纯函数聚合）；0 作者空态「暂无作者」。
- **AuthorScreen Web 形态重排**（Web AuthorsPage 形态）：顶栏下计数行「全部作者 · N 位」；排序行 QimengSegPill 直排；列表套 QimengRankCard 卡片底+行间分隔；行副行「N 个文件」单计数（**executor 纠偏两处执行基线**：①计数 N=全量作者数非体系过滤数——Web AuthorsPage L71 运行时口径；②不加「浏览 M 次」——Web 2026-09-05 反馈⑤拍板不展示、F7 批统一「N 个文件」）；onAuthorClick(id,name) 行点击接口+NavHost 占位接线（集合页归 G1b）。
- **共享件**：core:ui 新增 QimengRankCard（12dp 圆角+1dp 边框+16dp 内边距，对齐 Web .rank-card，Dimens 四常量单源）；core:model 新增 toAuthorOverview/filterByZone/AuthorOverview+AUTHOR_OVERVIEW_TOP_COUNT=5（+3 单测）。
- **验收**：compile+单测全绿（SettingsViewModelTest 9/AuthorRowsTest 7）；模拟器实测：我的页总览卡「2 位作者 · 已关注 0」+Top2 行在位、作者管理页计数行/胶囊搜索/排序行/卡片行全对齐、关注 toggle 往返（关注→已关注→关注）链路通；截图 %TEMP%\qimeng-g2-evidence\。

---
## feat(app): 任务G G5 相册排版对齐 Web（2026-09-08 第一百四十二笔）

执行 AI：GLM-5.3（主代理调度；executor 执行/主代理模拟器验证，任务G-Android对齐卷 G5；**基准拍板=Web 现版，推翻 B6 豁免档相册页相关豁免**——用户 2026-09-08 反馈「web的倒是和旧版手机的ui一致，为什么新版的手机端反而没做到」按冲突优先级第 1 条裁决）

- **排序 pill 行提到页头**：维度芯片行下常驻排序行（精选/最新/最旧/按名称，与 Web AlbumsPage SORTS 逐字一致；映射 DEFAULT+DESC/FILE_DATE+DESC/FILE_DATE+ASC/NAME+ASC，走 AlbumViewModel.selectSort 既有代际链，同档不重发）；QimengFilterSheet 移除排序方式/顺位两段（单一编辑入口；面板草稿仍原样携带 sort/order 模型零改动；10 条死字符串资源清除）。面板七档排序（添加日期/观看次数等）随段移除失去入口=对齐 Web 四档基线的必然结果。
- **值行改 in-flow**：相册页候选值药丸从 QimengFloatingPillPanel 悬浮面板改为内联值区块（QimengValuePillFlow 新组件：FlowRow+maxLines 钳制），默认收起两行（阈值 9 对齐 Web VALUE_COLLAPSE_THRESHOLD，候选>9 显示「展开 ⌄/收起 ⌃」）；切维归位收起+旋转存活（rememberSaveable）；展开推挤网格不再遮挡。**维度芯片行 D3 拍板语义（默认收起/toggle/切维仍展开）保持不动**；QimengFloatingPillPanel 本体保留（收藏/历史页仍在用，grep 确认）。网格 180dp 底衬留白保留（原始理由消失，留作呼吸区注释更新）。
- **卡片补日期行**：QimengMediaGrid 卡片四层=图/标题/作者/日期（对齐 Web MediaCard；formatShortDate(modifiedAtMs) 复用既有函数同字段同格式「M-D」；up/date 皆空不渲染）——列表族（首页/相册/收藏/历史/搜索）共用组件一并对齐。
- **验收**：compile+单测全绿（AlbumViewModelTest 7/AlbumFilterPanelTest 11/core:model 91 全过）；模拟器实测：排序行四档在位、分区展开值区内联推挤排序行下移（428→534px）、卡片「其他 9-7」日期行在位；截图 %TEMP%\qimeng-g5-evidence\。

---
## feat(app): 任务G G6 内部 UI 胶囊语言统一（含 G4 搜索胶囊）（2026-09-08 第一百四十笔）

执行 AI：GLM-5.3（主代理调度；executor 执行/主代理模拟器验证，任务G-Android对齐卷 G6）

- **共享组件两件**（core:ui，Web 胶囊语言的安卓落地）：`QimengCapsuleTextField`（胶囊软底输入框——PillCornerRadius 全圆角+surfaceVariant 软底+三态描边透明，聚焦反馈走 focusedContainerColor=surfaceContainerHigh；label 一律 placeholder 语义对齐 Web；不设高度档=保留 56dp 触摸目标，取舍记 KDoc）；`QimengSegPill`（分段选择胶囊——自绘 Text+clip+background，选中 primary 实底反色字 SemiBold/未选软底，无勾选框对齐 Web .seg/.pill=999px；不用 FilterChip 因自带勾选图标冲突）。**查重产出**：QimengPills 私有 PillChip 与 QimengSegPill 渲染同谱 → PillChip 改委托单源，8 处既有胶囊消费方零变化。
- **替换 11+3 处散点**：搜索页搜索框（=G4 本体，补 leading 放大镜图标，外层手势拦截不动）/作者页搜索框（同款）/设置页缓存档位/统计页时段档/上传页目标库三处 FilterChip→QimengSegPill；上传新建目录/标签新建/时间轴标签命名/筛选面板标签输入→QimengCapsuleTextField；作者页关注钮+首页假搜索框硬编码 100.dp→QimengDimens.PillCornerRadius 单源。图标纪律=自持矢量（SearchIcon 非 Icons.Outlined）。替换后 feature/core 下零裸 OutlinedTextField 默认形状消费。
- **验收**：assembleDebug+testDebugUnitTest 全绿；模拟器实测搜索页——胶囊形+放大镜+软底+顶部无异常间距（视觉模型判读），截图 %TEMP%\qimeng-g6-evidence\。

---
## fix(app): 任务G G3 外部页面系统栏遮挡+覆盖页双重留白（2026-09-08 第一百四十一笔）

执行 AI：GLM-5.3（主代理调度；executor 执行/主代理补 consumeWindowInsets+模拟器验证）

- **登录页结构性缺口**（渲染在主壳 Scaffold 之外，edge-to-edge 下内容画进系统栏+键盘盖密码框）：LoginScreen 根容器补 statusBarsPadding+navigationBarsPadding+imePadding；两输入框随批换 QimengCapsuleTextField。
- **覆盖页双重状态栏留白（executor 反编译 material3 1.4.0 bytecode 实证）**：主壳 Scaffold 无 topBar→innerPadding.top=状态栏高，NavHost 只 padding 不消费 insets；search/favorite/history/authors/upload/detail 覆盖页内嵌 QimengTopBar（M3 TopAppBar 默认 windowInsets=statusBars）再自留一段→标题上方两倍空白。修复=NavHost `padding(innerPadding).consumeWindowInsets(innerPadding)`（Scaffold 官方范式，嵌套组件 insets 归零）。
- **验收**：assembleDebug 全绿；模拟器实测作者管理页标题 top=171px（状态栏 128 下方正常位，修复前≈300px 双倍）。

---
## fix(app): 维护审查清偿——任务D卷审查 P2×5/P3×5（2026-09-08 第一百三十七笔）

执行 AI：GLM-5.3（主代理；三路并发审查子代理 Android 卷/Web 卷/流程合规 + 主代理修复）

- **审查**：对今日 D/E/F 三卷 25 笔提交三路并发对抗审查（Android 卷七提交/Web 卷十三提交/流程合规全量），Android 卷无 P0/P1，P2×5/P3×6 清偿如下（P3 dwell 假精度与 EventSyncWorkSpecTest 弱断言记档不清偿，见 HANDOVER_APP）。
- **P2 清偿**：①ViewEventSender 补非 IO 异常兜底（catch Exception 折进 IoError，防 UUID.fromString 脏 assetId 击穿 drain 契约致整轮已删行全丢；毒丸阈值最终收敛）；②VideoStage onDispose 方向恢复改**进入时快照还原**（原无条件写 PORTRAIT 把从未进全屏的整个 App 永久锁竖屏，横屏平板致命）；③BiliPlayerView.rebindPlayer 补 ENDED 态同位 seek 重渲末帧（进出全屏两方向对称，防 ENDED 退场黑屏）；④「先删后发」测试改全局序号硬断言（FakeDao.deleteByIds/FakeSender.send 共享 OpSequence，改「先发后删」必红）；⑤新增多批循环测试（120 行=3 批+RETRY 回队交错，锁终止性）。
- **P3 清偿**：QimengMediaGrid.onAssetClick 删默认空实现（D3 同族四页 bug 根因根除，漏接变编译错误）；enqueueWithinLimit 先 count 判满再淘汰（未达上限免整表 NOT IN 排序扫描）；ImageFullScreenOverlay KDoc BEAVIOR→BEHAVIOR；AllScreen 生产调试 Log.d 清除；ZoomImageView log() 加 isLoggable 门控（默认静默，setprop 可开）。
- **验收**：`:core:data:testDebugUnitTest`（22 测含 2 强化）+ :core:ui/:feature:all/:feature:detail compileDebugKotlin 全绿。#24/#26（BigDecimalAdapter 序列化致 dwell/progress 恒 400）审查复核根因定位准确，修复归协议批待用户拍板。

---
## fix(web): 维护审查清偿——任务E/F卷审查 P2×3/P3×5（2026-09-08 第一百三十八笔）

执行 AI：GLM-5.3（主代理）

- **P2 清偿**：①use-multi-select.selectOnly 去 locked 守卫（唯一调用方=useBatchRunner.onFinished 终局回调，捕获 locked 闭包恒 no-op 的死机制根除，F6 P2-1 闭环）；②useRestoreTrash 失效面补 ASSETS+DIRS+RECOMMENDATIONS（原仅 TRASH+SSE 兜底，断连退避窗跨页不一致；F6 批量恢复放大暴露面）；③SseBridge library.changed 补 RECOMMENDATIONS_QUERY_KEY（跨标签页删除后推荐流 stale 窗口内显示已删资产）。
- **P3 清偿**：image-viewer 键盘方向键换件（左=上一件/右=下一件，与横滑同映射）+ effect deps 补 onPrev/onNext；router 加 catch-all `*` 路由（未注册路径不再白屏，含裸 /app/ranks，回首页）；Sidebar '/app/albums' 字面量收敛 ALBUMS_PATH；useDeleteAsset 补 STATS 失效；DirFileList 重命名/移动两同行为钮合并为「移动/重命名」（Pencil 死导入清除）。
- **记档不清偿**：MediaCard key={a.id} 无 id 行 duplicate-key 警告风险（8 处存量惯用法，无功能影响）；image-viewer keydown effect 因调用方内联回调每渲染重挂 listener（功能等价，代价可忽略）。
- **验收**：tsc 0 错/oxlint 15 warnings 0 errors（持平基线）/vitest 94/94 全绿。

---
## docs: 任务G-Android对齐卷立卷 + 流程合规审查三处清偿（2026-09-08 第一百三十九笔）

执行 AI：GLM-5.3（主代理；流程合规审查子代理 + 研究子代理六项根因定位）

- **流程合规审查结论**：今日 25 笔提交无铁律/安全/生成物/迁移/协议红线违规；3 处文档失实本笔清偿——①adr/INDEX.md ADR-0017 行「CI 接入记待办」滞后 → 闭环（F4 批 run 34173261179 验证）；②HANDOVER.md 任务书入口段「D 卷仍开放」与文头矛盾 → 三卷收官口径；③E1~E6 hash 回填：8dac898/263d255/89e688a/5097402/e0f9f57/c28a9e1（对齐 D/F 卷回填惯例，此前仅在 HANDOVER 文头）。另记：commit「文档：」栏今后不列仓库外文件（7970592 教训）。
- **任务G 立卷**（用户六项反馈：详情页对齐 Web/作者总览进我的/状态栏遮挡/搜索胶囊/相册排版/内部 UI 理念）：任务书=仓库外《QimengNAS/任务G-Android对齐卷.md》（G0~G7 八批，根因定位到文件行级）；关键拍板=G5 相册基准以 Web 现版为准（调研实证旧版与 Web 形态互斥，用户断言 Web=旧版手机 UI，按冲突优先级第 1 条用户最新要求裁决，**推翻 B6 豁免档相册页相关豁免**）；G2=我的页作者总览卡（Web DataPage 式）+ AuthorScreen 按 Web AuthorsPage 重排；流程约束重申=双会话隔离/三子代理并发/异步子代理/后台执行/无真实文件（8420 真库永不连）/多种测试（JVM 单测+三连绿+模拟器实测）。零协议零 SDK 再生（所需端点已核验在生成物内）。

---
## docs: 任务F-Web卷 晨间收尾汇总（2026-09-08 第一百三十六笔）

执行 AI：GLM-5.3-Flash（主会话调度收尾；08:50 定时收尾自动化触发，用户指令「今天早上8.50暂停会话中的所有任务」）

- **完成批次（F0~F7 全部完成，无 SKIPPED、无中断断点）**：F0 环境预检（18463 隔离实例+虚构库 23 文件，无 commit）；F1 集合页深链白屏修复+全局 ErrorBoundary=**66162a5**；F7 用户实测反馈四项=**bf2a026**；F2 E 系列审查遗留收口六项=**ea1892f**；F3 测试覆盖面补齐=**54a9fe2**；F4 CI web job 接入 vitest=**ebcd6a6**；F5 保态推广（调研推荐方案 a+相册页实施，未弃批；memo=仓库外 web-overlay-rollout-memo.md）=**323950a**；F6 文件管理/回收站批量多选=**8c015b4**。每批 executor 执行+全新上下文 reviewer 对抗审查（F2 首轮 P1 tags 占位错账打回→返工清偿复审通过）+主会话浏览器走查；证据 %TEMP%\qimeng-f1~f7-evidence\（保留勿删）。
- **push 与 CI 闭环**：执行期间 GitHub 持续 502（06:14~08:05 四次 push 失败），六笔 commit 滞留本地；网络恢复后随 D 卷收官 push 一并上远程（origin/master=b3556c1 同步，push 确认 Everything up-to-date）。**F4 CI 实跑验收补验通过**：run 34173261179（含 ci.yml 的 npm test 新步骤+全部 F 卷 web 代码）五 job 全绿 12m22s，web job 日志确认「Run npm test」步骤真实执行。至此 F 卷无任何未闭环项。
- **基线推进**：npm test 39→**94**（F1 +2 / F3 +37 / F5 +7−1 / F6 +10）；oxlint 16→**15**（F5 删一处 lazy 声明告警）；ADR-0017 待办清零。
- **记档遗留（下批清偿，不阻塞）**：F6 P2-1（onFinished 的 selectOnly 死机制、注释失实，行为由 prune 兜底正确）；F7 P3×3（spinning 卸载重放/搜索收藏空态嵌格/尾注间距）+存量裸 /app/ranks 404；F1 P3（AppErrorBoundary inline 字号）；F5 留后续批（其余 6 入口入组、Sidebar '/app/albums' 字面量、相册查询串保态）。详见 HANDOVER_UI §5 第 25~29 条。
- **环境**：18463 隔离实例在线（虚构库 23 文件，仅供 Web 验证复用）；8420 真库全程未碰；与并行 D 卷会话文件集零冲突（CHANGELOG 编号交错兼容）。

---
## docs: 任务D-Android卷 夜间晨间汇总（2026-09-08 第一百三十五笔）

执行 AI：GLM-5.3-Flash（主会话调度收尾）

- **完成批次（D0~D7 全部完成，无 SKIPPED）**：D0 环境预检（含 18461 服务端二进制升级带 cosWork 修复）；D1 图片全屏覆盖层=2018f9e；D2 视频两级全屏+退出恢复竖屏=f5a295c；D3 胶囊默认收起+相册/收藏/历史点卡修复=dd1497c；D4 C8 对照+B6 收尾=d659833；搜索页点卡补修=2ad4d62；D5 M4-4 离线队列=3034426；D6 M4-3 整批自查+GIF 停帧修复=40ec778；D7 M4-7 验收（本笔同 commit 落文档）。每批独立 reviewer 对抗审查通过；证据 %TEMP%\qimeng-d1~d7-evidence\。
- **里程碑**：PROJECT_PLAN M4 三条未勾项（复刻/详情/行为上报）全部勾选；CAPABILITY_MAP 移动端「规划中(M4)」→「已有(M4)」+两条候选记账。
- **中断/断点**：无——全部批次完成并落库；D7 交付时 HEAD 曾被并行 F-web 会话前移（8c015b4），android 侧验收不受影响。
- **待用户项（晨间翻案随时，均为低成本）**：《待拍板-20260907.md》#19 D3 切维口径、#20 全屏滑切落排版态、#21 批次播种入口缺口、#22 ENDED 末帧重渲染、#23 旋转入口三候选、**#24【高优】dwell 恒 400 协议缺口（M4-3 起 dwell 从未送达）**、#25 毒丸 IO 口径、**#26 progress 恒 400（断点续播位置未落库，与 #24 同根）**、#27 无法解码原件静默黑屏、#28 harness init Git Bash 坑。#24/#26 建议同一协议批修（openapi+make sdk 三端再生，或 core:network 临时 interceptor 治标）。
- **环境交还**：模拟器与 18461 在线（cos 5+normal 18 基线）、App 已 pm clear；8420 真库全程未碰；与并行 F-web 会话文件集零冲突（CHANGELOG 编号交错兼容）。

---
## feat(app): D7 M4-7 整体验收通过——M4 里程碑达成（2026-09-08 第一百三十四笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 验收（零代码改动红线遵守），任务D-Android卷 D7）

- **八条验收全过**（证据 %TEMP%\qimeng-d7-evidence\，80 项）：①完整日常使用（列表/筛选/详情/播放/收藏全链+服务端 DB 实证 favorites/likes/timeline_tags 落库、view_events 66 条）②上传→立即可见（SAF 2 图→curl totalMatched≥2）③离线行为不丢（断网→恢复→logcat `enqueue kind=DWELL`→EventSyncWorker POST→Worker SUCCESS；点赞/收藏离线出中文错误=冻结口径内非队列事件；dwell 落库受 #24 阻塞已知不算 FAIL）④门禁四段全绿（256 tests 0 fail/redocly valid/golangci 0 issues/oxlint 0 errors）⑤`make sdk` 幂等（前后 porcelain diff 空）⑥cdc422e..HEAD 迁移零改动 ⑦Room/lifecycle-process 均在 ADR-0014 白名单、无需新 ADR ⑧三项规格抽查（COS 联动/长按 2x+暂停/双击缩放像素级）。
- **M4-5 五观察项补证**：①takePersistableUriPermission 0 命中=缺口实锤（→CAPABILITY_MAP 候选）②dataSync FGS 已声明+串行短任务 6h 风险定性低 ③拒通知权限上传仍成功+队列页可见（已恢复权限）④取消上传入口缺口实锤→CAPABILITY_MAP 候选 ⑤**110/110 SAF 多选上传全落库、无 ANR**。
- **环境复原**：验收测试物清理+两库重建重扫（cos 5 + normal 18，与 P0 基线一致）；App 已 pm clear 干净交还；init 脚本 Git Bash cygpath 坑记待拍板 #28。
- **遗留记录**：批次播种入口缺口（=待拍板 #21 相册/搜索进详情单卡语义）、无法解码原件静默黑屏（新发现=待拍板 #27）。

---
## feat(web): F6 文件管理/回收站批量多选（2026-09-08 第一百三十三笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 全项对抗审查/主会话浏览器主链走查，任务F-Web卷 F6=#15 用户拍板两处都做）

- **结构**（零协议改动=前端逐条循环既有单条端点，两页同构共享件防复制粘贴）：`lib/batch.ts` 纯函数四件（进度换算/失败原因/汇总文案/失败清单折叠，+10 单测）+ `use-multi-select`（选集/执行期 locked 冻结/Esc 退出带弹窗让位/prune）+ `use-batch-runner`（严格串行逐条 mutateAsync、失败收集不中断整批、runningRef 防重入、失效不另立=每条成功各自触发既有 mutation onSuccess+SSE 双保险）+ `multi-select-bar`/`batch-ops-panel`（复用上传队列 .progress 进度条+页内失败明细列表）。
- **两页接线**：文件管理「多选」→checkbox→全选本目录→批量删除（文案明示「N 个文件移入回收站…可恢复」，铁律 4）/批量移动（MoveDialog 加 batch 模式，既有单文件调用方零影响）；回收站「多选」→批量恢复/批量彻底删除（danger 二次确认「物理删除不可恢复」）。
- **审查**：reviewer 11 项全过——真串行/防重入/失效根键子键命中/MoveDialog 路径隔离/Esc 让位/零新端点零直调 fetch（铁律 7）/样式零新颜色/范围干净。
- **验收**：tsc 0 错/oxlint 15≤17/build 42 entries/npm test **94 过**（84+10）+ executor curl 级全链自验（删 3→回收站 3→恢复 3→库对账 18；批量移动 1 条故意 409+2 成功不中断）+ 主会话浏览器实测主链（多选→全选 3/3→批量删除确认文案→面板「移入回收站完成 3/3」+toast→回收站在位→批量恢复「完成 3/3」+toast）。
- **P2-1 留下批**（reviewer 揪出）：onFinished 的 selectOnly 在三条经弹窗路径捕获 locked 实例成 no-op，「终局只留失败项」实际由 prune 收敛达成（行为正确、机制注释失实）——批次结束到重取落地间 stale 勾选短暂残留；修法=稳定回调收尾或 ref 判锁，至少改三处注释。P3×3（批次不可取消/两页多选态不同构/空 id 行 checkbox 无危害）记 HANDOVER_UI §5 第 29 条。证据 %TEMP%\qimeng-f6-evidence\（保留勿删）。

---
## fix(app): D6 M4-3 整批自查 + GIF 动图停帧修复（2026-09-08 第一百三十二笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行自查，任务D-Android卷 D6）

- **自查结论**：7 块清单基本全绿（证据 %TEMP%\qimeng-d6-evidence\自查清单.md）——排版基准七层序（含 COS 作者卡）、原件直链（Content-Length==磁盘字节）、G1/G7/G8/G9/G6 抽查、已看完徽标+续播起点、❤️/❤ 变体单源（UI 快捷钮写 0x2764 0xfe0f，取色逐通道差 0）、cosWork 双证、队列链打点（TODO(M4-4)=0，open/play 会话去重，API viewCount==asset_daily_stats）、3d 隔离证据复引（after_contract.json 四项 PASS，废片 after.json 未引）。
- **真 bug 修复（GIF 停第 0 帧）**：ZoomableOriginalImage Coil Target.onSuccess 补 `(drawable as? Animatable)?.start()`——旧版 load() 的 ImageViewTarget 自带动图 start，M4 重建换自定义 Target 后丢失；排版态舞台与全屏覆盖层共用此件一处修复两处生效；实测帧差 0→87.83（N-02.gif 渲染至第 2 帧）。
- **重大发现（待拍板 #26，与 #24 同根并案）**：progress 上报 positionSeconds 同被 BigDecimalAdapter 序列化为字符串→服务端 400→**断点续播位置自 M4-3 起从未落库**（App 侧离开补报机制正确有 logcat，curl 字符串 400/数字 204 复现）。修复候选：a) 修生成模板+make sdk 治本 b) core:network wire 修补 interceptor 治标（~20 行）。
- **口径注记**：dwell 缺席=已知 #24；ZoomImageView 无既有单测（清单原假设不实，双击/双指按代码语义+人工清单口径）；双击宿主态归属受 adb 注入时序限制如实注记；红系芯片取色改同底差分判定（半透明混色下 B>G，❤️==❤ 差 0+相对压制方向正确）。
- **验收**：三连绿；修复后重装实机复验 GIF 帧渲染。

---
## feat(web): F5 列表页入叠加组推广——相册页进详情保态+批次导航（2026-09-08 第一百三十一笔）

执行 AI：GLM-5.3-Flash（主会话调度；F5 调研=researcher 子代理（memo 仓库外 web-overlay-rollout-memo.md）/executor 实施/reviewer 全项对抗审查含 E1 零回归专项/主会话浏览器走查，任务F-Web卷 F5=E1 遗留①）

- **方案 a「单组+动态底衬槽」落地**（调研三方案对比后选定；三方案评估全文见 memo）：albums 移入 pathless 叠加组，HomeBackdropLayout 泛化改名 AssetOverlayGroupLayout——activeKey=列表路径 pathname 派生（listKeyFromPath，登记集外显形不静默）/详情 readBackdropKey(state)??HOME_PATH；backdrop 为 state 顶层独立字段+独立校验（不扩 readAssetNavState，理由：nav 缺失无缺省 vs backdrop 缺失安全回落 home，职责与容错纪律不同）；同位置同类型组件=详情往返实例存活（筛选态保）。
- **相册入口批次导航顺带解锁**：AlbumsPage 组装 items 快照（flatMap 平铺序）+ state {ids,index,backdrop}，详情 pager「n/23」可用（E5 留档缺口清偿）；goNeighbor/UpNextList 显式透传 backdrop（相册进的详情右栏换件后底衬仍=相册）；home 链条件不写=state 与 E1 逐字节一致零回归。
- **滚动门改写**（AppShell）：next 详情→跳过；prev 详情且目标===backdrop（缺省 home）→跳过；其余复位——任务书字面版「prev 或 next 是详情就跳过」被正确裁断否决（字面版相册详情切首页不复位），实现版与 E1 旧行为七类用例逐条等价；inHomeDetailGroup 退役。
- **审查**：reviewer 全项过+E1 零回归专项（HomePage 零 diff/state 逐字节/深链底衬/FAB 门/chunk 独立性 dist 实证无首载膨胀）。
- **验收**：tsc 0 错/oxlint **15**≤17（降 1）/build 41 entries/npm test **84 过**（78−1+7：listKeyFromPath 3+readBackdropKey 4）+ 主会话浏览器实测：相册滚动 500.26→进详情→返回 **scrollTop 逐位一致**、pager 1/23、相册详情切首页复位+卸载、深链无 state 底衬=home 降级、home cos 链 ?tab=cos 保态。留档：侧栏相册高亮不联动（同现状）、Sidebar '/app/albums' 字面量与相册查询串保态留后续批、其余 6 入口入组留后续批。证据 %TEMP%\qimeng-f5-evidence\（保留勿删）。

---
## build(web): F4 CI web job 接入 vitest（2026-09-08 第一百三十笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 对抗审查通过，任务F-Web卷 F4=ADR-0017 待办闭环）

- **改动**：`.github/workflows/ci.yml` web job 步骤尾部（build 之后）加 `- run: npm test`（+1 行，与 job 既有裸 run 步骤同风格，working-directory 走 job 级 defaults=web）；server/android/sdk-chain/openapi 四 job 零改动。
- **审查**：reviewer 五项全过——diff 单 hunk 落 web job 内；`vitest run` 无 watch 挂起风险、vitest 在 devDependencies 且 npm ci 默认装、执行序 npm ci→openapi-ts→tsc→build→npm test；vitest.config node 环境无浏览器依赖；js-yaml 解析 5 job 结构完整；本地 npm test 78/78 独立复跑。
- **验收**：本地四命令绿（npm test 78 全绿）；**CI 实跑验收因 GitHub 502 push 受阻，待 push 恢复后以 gh run watch web job 绿补验（五 job 整体不红）**。ADR-0017 状态行已更新闭环、HANDOVER_UI §5 第 24 条待办句清账。证据 %TEMP%\qimeng-f4-evidence\（保留勿删）。

---
## test(web): F3 E6 测试覆盖面补齐——导航状态校验/SSE 重连停连/日期区间等（2026-09-08 第一百二十九笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 对抗审查含破坏性推演，任务F-Web卷 F3=E6 reviewer P3 清偿）

- **+37 用例（41→78）全绿，产品源码与 package.json 零 diff**（仅 4 个 *.test.ts，+345 行）：route-keys +12（readAssetNavState 逐字段非法整包 null 九态+assetDetailWithSearch 查询串三态）、sse +5（retry 夹取下限/上限/区间/缺省四边界+401/403 停连：onAuthFailed 广播+推进 60s fetch 仍 1 次）、search-mapping +6（dateRangeFor 今天/本周周一起始/周日跨周回退/含当日/未知档 undefined+partitionKey 三态）、format +14（七函数含非法输入边界）。
- **行为锁定产出（怪癖 4 条成文，测试注释标注「锁怪癖非背书」）**：react-router 8 空 id 产出无尾斜杠 `/app/asset`；formatDuration null→`0:00`/undefined→`NaN:NaN`/负数→`-1:-1`；localDateKey 月份 0 基不补零（分组键口径）；readAssetNavState 合法返回恒带 origUrls 键（显式 undefined）。
- **审查**：reviewer 十项全过——断言经源码推演+react-router 8.3.1 独立实测双验证；三类高风险用例（夹取/停连/怪癖）破坏性推演「改错必红」；假测试专项排查零命中（无弱匹配器/无断言复刻实现/401 走真实事件总线）；既有 19 条用例未弱化；测试卫生（localStorage 桩先于动态 import、文件级隔离不泄漏、固定时钟自清理）核过。
- **验收**：tsc 0 错/oxlint 16≤17（测试文件 0 告警）/build ✅/npm test **78 passed (78)**。证据 %TEMP%\qimeng-f3-evidence\（保留勿删）。

---
## feat(app): D5 M4-4 行为上报离线队列（2026-09-08 第一百二十八笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 独立对抗审查（防虚增审计+logcat 逐行互证），任务D-Android卷 D5）

- **架构**（:core:data 新建 events 包 12 文件）：Room 队列表 `pending_view_events` 7 列（room 2.8.4 × Kotlin 2.3.21/KSP 2.3.11 兼容实测通过，schema 1.json 导出入库）；写入即入队+5000 FIFO 淘汰同一事务；触发三通道=写入即触发/启动与 ON_START（ProcessLifecycleOwner）/WorkManager 周期兜底（`MIN_PERIODIC_INTERVAL_MILLIS` 15min 钳制注释，KEEP，unique 名 `qm-event-sync-*` 与上传链隔离）；drain Mutex 串行并发=1（Worker 与前台触发唯一入口）。
- **防虚增语义（冻结口径全落地）**：dwell 先删后发（DAO 事务内 select+delete 再出网，at-most-once，宁少计不虚增）；毒丸连败≥3 丢弃（连败计数随回队迁新 id，AUTOINCREMENT 防撞号）；失败分类表驱动纯函数（202=删行/400,401,403,404=终局/429,408,5xx,IO,未知=重试）；单位换算 `movePointLeft(3)` 无损、open/play 不带 seconds；**事件链不加 CONNECTED 约束**（同仓上传链弱网悬置先例，与备忘录 §3.2 相反的有意决策）。
- **语义保持铁律**：DwellSessionTracker/DirectAnalyticsReporter/DetailViewModel 仅注释清偿零逻辑变化（reviewer diff 逐行核实）；sessionId 保持每 VM 实例 UUID；TODO(M4-4) 六处清偿 grep=0。
- **测试**：新增 20 测（失败分类表 15 行含 200/204/409 边界、5001 实值 FIFO、真并发双 drain maxInFlight=1、毒丸恰好 3 发后丢、先删后发时序、回队内容保真）；既有全族保绿；三连绿。**room 2.8.4 编译确认点归 D6/D7 复跑收口**（审查禁构建令所限）。
- **断网补传对账（18461 实测）**：svc wifi+data disable→ping unreachable→离线浏览→恢复回前台→`drain sent/dropped/kept/pending` 日志闭环；totalViews 15→17 可解释（含 1 笔毒丸丢弃实证：rowId 1→2→4 连败 1→2→3）。
- **重大发现（待拍板 #24 高优）**：dwell 上报恒 400——SDK 生成物 BigDecimalAdapter.toJson 把 seconds 序列化为带引号字符串，服务端 gen `Seconds *float32` 拒收（curl 数字得 202 佐证）；**M4-3 直连时代 dwell 从未送达**（静默吞错掩盖，本批终局丢弃日志首次暴露）；协议冻结夜不改，客户端按冻结表处置正确，修复归协议批+三端 SDK 再生。
- **审查**：独立 reviewer 通过（0 P0/P1/P2）——冻结口径逐条可复核、防虚增审计未发现实现层缺陷（进程死亡只丢不重/并发 Mutex 真覆盖/id 不撞号）、logcat 与代码行为逐行互证；P3×4 记录备查（非 IO 运行时异常击穿=少计方向/RETRY 回队暂超 5000 下次收敛/KEEP 运行中触发延迟非丢失/一处本批前陈旧注释）。

---
## fix(app): 搜索结果页点卡进详情补修（2026-09-08 第一百二十七笔）

执行 AI：GLM-5.3-Flash（主会话调度；原 D3 执行者续聊补修，任务D-Android卷 D3 同族收尾）

- **根因**：D3 修相册/收藏/历史三页 onAssetClick 漏传时，搜索页同族第四处漏网——SearchScreen 的 QimengMediaGrid onAssetClick 默认 `{}`，点结果卡无响应。D5 批离线对账 UI 实测中发现并移交。
- **修法**（镜像 D3 已审口径，2 文件）：SearchScreen 签名加 onOpenAsset（onBack 后、默认参前，与三页风格一致）+ SearchPhase.RESULT 调用点与私有 ResultPhase 透传；QimengNavHost Routes.SEARCH 调用点补接线。EmptyPhase/SuggestPhase 无网格不涉及。
- **验收**：三连绿；实机——搜索「W-01」（中文词 adb 不可键入，ASCII 词走同一 ResultPhase 链路）出结果卡→点 jpg 卡进详情 dump 实证；相册/收藏/历史三页点卡回归通过（收藏借临时造数、已撤销还原）。证据 %TEMP%\qimeng-d3-evidence\（d3_search_fix_*）。

---
## fix(web): F2 E系列审查遗留收口——错误态/换件占位/viewer细节/字面量收敛（2026-09-08 第一百二十六笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行+返工/reviewer 两轮对抗审查（首轮 P1 打回）/主会话浏览器走查，任务F-Web卷 F2）

- **StreamCards 错误态**（E2 备案）：isError 分支「加载失败」+重试钮，错误态优先空态/endHint、哨兵错误期卸载；三流同口径。注释如实化：换键失败=占位丢弃网格清空+错误行，同键失败=卡片保留+错误行（v5 占位仅 pending 态生效）。走查：停隔离实例模拟断网，「加载失败+重试」出现且无误导空态，恢复重试即复原。
- **useTimelineTags 占位+播放器闸门（首轮 P1 打回返工，本批核心）**：仅加 keepPreviousData 会让旧 `tagsLoading` 门确定性失效（v5 占位期 status 乐观翻 'success'，isLoading 同拍 false）→ 换件窗口新 VideoPlayer 以旧资产 highlights 首挂**永久定格**（ArtPlayer highlight 仅构造时消费）→ 进度条挂错打点、seek 错位。修复=挂载门改 `tagsPending || tagsPlaceholder`（占位期「加载中…」不挂播放器），七时序推演（冷启动/换件/detail 先到/tags 先到/换键失败/同键 refetch）全过；消费方契约注释落 use-progress.ts 与 video-player.tsx。走查：换件窗口精确「加载中…」+无播放器，新件到货即挂。
- **viewer P3 三处**：pointercancel 清 lastTap；手写焦点管理（焦点入关闭钮/卸载归还/Tab trap 过滤 visibility:hidden，未迁 radix Dialog——portal+手势+key=src 耦合深迁移不可逆，45 行可回退）；沉浸态空焦点集补 preventDefault 吞 Tab；「进视频项 viewer 卸载、回图片项 viewerOpen 不复位自动重现」设计语义注释（E5 备案「不自动重现」与代码事实相反，按实际行为落笔，改行为待拍板）。
- **杂项**：`'/app/home'` 三处字面量收敛 HOME_PATH（URL 不变）；use-prefs.ts 过时注释修正（零行为）；作者页搜索图标 r=8 核识自阶段 A 即在位（拍板=确认 8 否决原型 7），补溯源注释。
- **审查**：reviewer 首轮揪出 P1（tags 占位错账）与 P2（注释失实）打回；返工后二轮七时序推演+范围+四命令全过。P3 备忘：timelineTags keepPreviousData 对唯一消费方（被闸播放器）已无可见收益，冗余但无害。
- **验收**：tsc 0 错/oxlint 16≤17/build 41 entries/npm test 41 过 + 主会话浏览器实测（错误态与重试恢复/换件闸门/焦点入钮与 trap/全站导航回归）。走查方法备案：SDK client 固化 globalThis.fetch 引用致合成拦截无效，错误态以停服务端真实模拟；合成 Pointer 事件无法触发沉浸切换（E5 已知限制），该分支代码级核验覆盖。证据 %TEMP%\qimeng-f2-evidence\（保留勿删）。

---
## feat(app): D4 C8 新旧截图对照修复 + B6 收尾（2026-09-08 第一百二十五笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 独立对抗审查（取证级：设备时钟校准/APK 反汇编/像素几何互证），任务D-Android卷 D4）

- **代码修复 1 处**：QimengEmptyState Box fillMaxWidth→fillMaxSize——空态文案改剩余区域垂直居中，对齐旧仓库口径（fragment_favorite/fragment_browse_history 均 weight=1+gravity=center）；修复前钉顶与旧版可见位置差是 S9 空态文案 26.8% 差的根因。8 处调用方逐一核安全（7 处 PTR content + search ResultPhase 独占 Box）；pair_C8_S9_favorite_empty.png 双证（新 y≈1359 居中形态 vs 旧 y≈1856 下半区居中；残差=旧构建空态下药丸容器占位，复刻缺陷不跟，报告已记）。
- **harness 场景补全与修正**（ui-compare-harness，仓库外）：新增 S9 收藏页（8 签名空态对称基线）/S10 历史页（5 签名，作品芯片=/history 协议缺口不入签、清除按钮不入签）；S1/S4 分组标题签名改双侧同规日期头宽匹配+CAUSE_DATE_DRIFT（原钉「今天」随日历翻转腐化产生假结构红）；S2 补 CAUSE_COUNT（23 件数据集演化计数差）。
- **豁免重审**（79 项按用户视角全部重审，以复跑为唯一依据）：三轮全量对照收敛，round3 终版 10 场景 104 签名 100 红**全部有解释**（文案 19/结构 4/视觉 96）=修复 1+假红修复 2+豁免 97（V14 拆分：S2/S3≈91% 真实交互口径差=待拍板 #2 维持现状；S1/S4≈50% 裁片配准伪差亲证排除）。判据纪律：known_cause 只豁免文本列、结构缺失/视觉差不洗白。
- **B6 五项交付**（QimengNAS/m42a-review/C8-visual-report.md）：①任务书预估勘误成文 ②统计行容量降级汇编 ③旧构建三条口径差异（含日期标签行为级断言：旧构建同日历档渲染绝对日期、新版按 DOMAIN_RULES §8 渲染周X）④P8 虚构数据对称降级说明 ⑤隔离环境截图标注口径成文；附 #17/#20/#3 差异记录。
- **审查**：独立 reviewer 通过——100 红逐项可溯抽查全命中、场景幂等性（round2→round3 逐值一致）、时间线取证（报告头 GMT 时区疑点经设备时钟+APK 哈希+dex 反汇编排除）；P2 注释失实（search 调用方不在 PTR 内）与 P3 行号漂移已随本笔顺带修正（改按符号定位防再漂）。
- **验收**：三连绿（app-test 当时 186 tests/23 XML 0 失败——**注**：该 XML 证据已被并行批次后续测试运行覆盖，终态以本笔工作树三连与后续 D5/D7 复跑为准）；修复面仅 core/ui 两文件。证据 %TEMP%\qimeng-d4-evidence\ + shots/pair_C8_*.png。

---
## fix(web): F7 用户实测反馈批——到底了独立行/详情期隐藏刷新FAB/作者榜计数/全量榜单页单框（2026-09-08 第一百二十四笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 全新上下文对抗审查六质询点全过/主会话浏览器 4 剧本走查，任务F-Web卷 F7=2026-09-08 晨用户反馈）

- **首页「到底了」独立成行**：StreamCards 网格化重构——HomePage 不再包 .grid，StreamCards 加 gridClassName prop（HotRankTab 传 grid grid--hot 保留热榜 margin），.grid 内只渲染卡片，加载中/空态/换一批页脚/到底了/触底哨兵全移网格下全宽独立行（复用 .grid-empty token 样式零新 CSS）；修前「到底了」占单格宽 271px 与缩略图并列（用户实测反馈），修后 1151px 全宽。五页尾注 sweep 确认 E3 已页级直挂零改动。
- **刷新 FAB 详情/查看器期隐藏**（用户推翻 E5 有意设计注释）：AppShell 刷新 FAB 条件卸载 `{!overlayOpen && …}`（与回顶部 FAB 同一 isAssetDetailPath 门；不用 hidden 属性防 display:flex 压过 [hidden] 既有坑）；image-viewer/AppShell/prototype.css 三处「FAB 浮于查看器系有意」注释改如实语义。
- **作者榜计数文案统一**：rank-rows.ts 单源改排——排序口径不动（viewCount 降序、0 浏览不入榜、卡头注「按浏览」保留），裸 `<b>3</b>` → `.rank-sub2`「N 个文件」（fileCount 生成 SDK 真实字段，文案与作者总览逐字一致）；DataPage 榜与 /app/ranks/authors 全量页同源生效。
- **全量榜单页单框单标题**：RanksPage 三全量榜（content/tags/authors）删内层 .rank-card>.rank-head 重复 h3，保留框体+口径注+列表，单标题=page-head h2，节奏对齐集合子页单框流。
- **审查**：reviewer 六质询点全过——StreamCards 条件分支与改前逐字一致、`#page-home .grid(--hot)` 后代选择器作用机制不变（E1 网格顶沿 83.1 基线不受影响）、查看器全库唯一消费点在详情组件内（FAB 卸载链条成立）、fileCount 对照生成 SDK 非杜撰、LOCALE_ZH 无孤儿消费、三处注释 grep 零残留；四命令独立重跑一致。
- **验收**：tsc 0 错/oxlint 16≤17/build ✅/npm test 41 过 + 主会话浏览器 18463 实测：到底了 parent=.page 宽 1151=容器全宽、进详情 fab=false（回顶部 FAB 仍在）、img-viewer 开启期 fab=false、作者榜行 b=0 且 rank-sub2=「N 个文件」、三全量榜页 h3=0 单框单标题。P3 记账×3（spinning 卸载动画重放/搜索收藏空态嵌格遗留/尾注间距松 8px）+ 存量记账（裸 /app/ranks 404 系路由只注册 ranks/:rank 的既有现状）——详见 HANDOVER_UI §5 第 26 条。证据 %TEMP%\qimeng-f7-evidence\（保留勿删）。

---
## fix(web): F1 集合页深链白屏修复 + 全局 ErrorBoundary 兜底（2026-09-08 第一百二十三笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 全新上下文对抗审查 12 项全过/主会话浏览器 4 剧本走查，任务F-Web卷 F1=待拍板 #17）

- **病根**：`router.tsx` 路由表只注册 `collection/:kind/:name` 路径形态，查询串深链 `/app/collection?author=x` 的裸 pathname 在 React Router v8 数据路由下 matches 为空 → **整树渲染 null 白屏**（matchRoutes 探针实证；E2 基线已复现=存量缺陷非回归）。任务书两疑点排除：CollectionPage 渲染期 `prevAuthorId` setState 非病根（白屏 URL 下组件不挂载，且系 React 官方 adjust-state-in-render 模式，未改动）；displayName→id 解析链无失败路径。
- **修法**：`route-keys.ts` 新增 COLLECTION_PATTERN/collectionPath（generatePath 编码单源，禁调用方预编码防双跳，+2 单测）；新 `CollectionDeepLink.tsx` 归一组件（?author= 优先于 ?tag=、`<Navigate replace>` 不加历史、双缺省回首页）；router 注册裸 collection 路由；CollectionPage 零改动。
- **全局 ErrorBoundary**：新 `AppErrorBoundary.tsx`（class 边界）挂 `main.tsx` 根（QueryClientProvider 内、RouterProvider 外）——兜底页全 `--qm-*` token 零硬编码色、中文文案、「重试」清错误重挂 +「返回首页」整页刷新不依赖 router；componentDidCatch 补 console 记录。P3 记账：inline 字号字面量后续收敛（tokens.css 无字号档位）。
- **审查**：reviewer 逐项对抗审查 12 项全过——病根 matchRoutes 独立复现一致、Navigate 无循环重定向、无编码双跳、LoginGate/dev-login 交互不破坏、ErrorBoundary 挂载位置能接住路由树内异常、样式逐 token 核对、零越界（仅 6 文件全在 web/src）、四命令独立重跑输出与声称一致。
- **验收**：tsc 0 错/oxlint 16≤17（新文件 0 告警）/build ✅ + npm test **41 通过**（39 存量+2 新增）+ 主会话浏览器 18463 实测：`?author=测试作者二` → 归一路径正常渲染 2 文件+筛选胶囊、`?tag=深链测试`（API 造数）→ 1 文件、`?author=不存在` → 空态「该作者下暂无内容。」不白屏、数据页→作者行→集合站内导航回归、裸 `/app/collection` 回首页。存疑记账：同传优先级/未登录深链丢查询串（AuthGate 既有）见 HANDOVER_UI §5 第 25 条。证据 %TEMP%\qimeng-f1-evidence\（保留勿删）。

---
## feat(app): D2 视频两级全屏 + 退出恢复竖屏（2026-09-08 第一百二十二笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 全新上下文对抗审查（含独立强制重跑测试），任务D-Android卷 D2）

- **两级全屏**：点全屏钮 → 第一级=竖屏全屏覆盖层（视频经 `adoptCurrentState` 挂同一 ExoPlayer，surface 随挂载迁移播放零中断，实测跨级同帧 1.958s；横屏视频 letterbox 属正常）；覆盖层内再点全屏钮 → 第二级=横屏全屏（**固定 SCREEN_ORIENTATION_LANDSCAPE** 防双横屏乱闪，800ms 防抖保留两钮共用）；退出逐级回退（横屏级→竖屏级→排版态）。BiliPlayerView isLandscapeVideo 门控删除，全类型视频可全屏（GUIDE_UI:195 旧句被用户 2026-09-07 口径覆盖）。
- **方向恢复兜底**：VideoStage onDispose 强制 PORTRAIT——系统返回/兄弟 push/退出详情三条路径走组合离场天然覆盖；用户场景（横屏全屏退详情卡横屏）实机复现已修（回首页 dumpsys `mCurrentAppOrientation=PORTRAIT` 实证）。
- **结构**：VideoFullscreenStateMachine 纯 JVM 状态机（NONE/PORTRAIT/LANDSCAPE + 方向指令 + awaitPortraitSettle 瞬态忽略窗口），11 例单测；FullscreenOverlayShell 共享外壳（D1 图片/D2 视频共挂，Dialog+insets+退出骨架全仓单源，禁第 3 次复制粘贴落地）；ENDED 态进覆盖层同位 seek 强制重渲染末帧防黑屏（记待拍板 #22）。
- **reviewer P1 处置（旋转入口如实化）**：「旋转设备→横屏」在常规全屏下物理不可达（第一级方向锁后系统不响应旋转，仅分屏/自由窗口等忽略 requestedOrientation 环境可达）——第二级入口先行为「覆盖层内全屏钮」（已实机验证），旋转分支代码保留+KDoc/测试注释如实标注可达性，三候选（传感器检测/一级不锁向/维持现状）记待拍板 #23。P3 清偿：死 string detail_video_fullscreen_portrait_unsupported 与 isLandscapeVideo() 死方法删除（grep 零残留）、ImageStage 过时 KDoc 修正。
- **审查**：D2 主体 PASS（状态机/防抖逐行同构/退出覆盖推演/外壳单源/setPlayer 默认参逐行兼容）；独立 `--rerun-tasks` 强制重跑全绿。
- **验收**：三连绿（VideoFullscreenStateMachineTest 11/0、VideoStageStateMachineTest 6/0、PlayerMathTest 7/0 保绿）；实机两片型（竖屏 540x960 专项 ffmpeg 造片上传虚构库+横屏 960x540）各走两级、方向证据 dumpsys+窗口 bounds、防抖连点、ENDED 末帧复验。证据 %TEMP%\qimeng-d2-evidence\（47 项）。

---
## feat(app): D1 详情页图片全屏覆盖层（2026-09-08 第一百二十一笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 全新上下文对抗审查，任务D-Android卷 D1）

- **行为**：排版态单击图片舞台 → 打开全屏查看覆盖层（ZoomImageView 手势/原图加载链复用不换控件、铺满整屏 `[0,0][1080,2400]`、隐藏系统栏、盖住顶行/底节）；再次单击或系统返回退出回排版态（返回键由覆盖层消费不 pop 路由）；左右滑兄弟切换全屏态可用（onSiblingNavigate 与排版态同一回调链）。排版态布局/视频舞台/缩放手势语义零改动。
- **选型（实测驱动反转）**：宿主 Box overlay 方案实测被壳层 Scaffold innerPadding + 模拟器 override-inset 特性卡死（顶部恒留白条）；改 Compose 全屏 Dialog（usePlatformDefaultWidth=false + decorFitsSystemWindows=false）；又实测推翻「系统栏显隐单源在 Activity 窗」假设——Dialog 取焦后系统栏回归，修正为覆盖层打开期间由 Dialog 自身窗口 insetsController 隐藏（同既有 BEHAVIOR_DEFAULT 语义），关闭恢复。API 用法经 developer.android.com 官方文档核实。
- **结构**：ZoomableOriginalImage 自 ImageStage 逐字抽出（排版态舞台与覆盖层两处复用，杜绝分叉）；ImageStage 单击语义 onToggleChrome→onOpenFullScreen；覆盖层挂载条件=资产就绪；rememberSaveable 与 chrome 显隐合并单源（`chromeVisible && !imageOverlayVisible`）。
- **审查**：D1 部分 PASS（抽取与原实现逐行一致/挂载条件/铺满+系统栏/兄弟链同源/零越界零硬编码零真实数据）。
- **验收**：三连绿；实机文本树（全屏态顶行/底节节点不可见、图片节点铺满、退出恢复）+ 手势清单（双击缩放还原/左右滑换件/两种退出）+ 虚构数据截图。证据 %TEMP%\qimeng-d1-evidence\。
- **已知口径差异**：全屏态左右滑后兄弟资产落在排版态（旧版媒体层恒全屏），记待拍板 #20；双指捏合 adb 不可注入（组件冻结语义，非本批耦合）。

---
## fix(app): D3 维度胶囊默认收起 + 列表族点卡进详情修复（2026-09-08 第一百二十笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 全新上下文对抗审查，任务D-Android卷 D3）

- **C7 默认收起**（用户 2026-09-07 拍板覆盖 M4-2「B8：切维度默认展开」）：AlbumFilterState.expanded 默认 false；落地口径=进页默认收起 + 点已激活维 toggle + **切维仍展开**（selectDim 零改动）——任务书预判「切维不展开」被其规定的旧仓库复核推翻（四 Fragment setViewMode 齐证切维强制展开，AlbumDetail v1.15 更是显式拍板从折叠改回展开），差异记《待拍板-20260907.md》#19，翻案=三 VM 各删一行+测试回翻。
- **注释纠偏**：三 VM onDimChipClicked KDoc 所引「旧版 QimengFourDimSection」在旧仓库不存在（grep 零命中），修正为实证出处（AllFilesFragment/FavoriteFragment/BrowseHistoryFragment 的 setViewMode）。
- **测试反转**：三个同构用例改「进页默认收起→首点展开→再点收起→切维展开」，新增进页默认收起前置断言；AlbumPanelFilterTest/FourDimPillsTest 方向中性零改动。
- **顺手修复（同族 bug）**：相册/收藏/历史三页 QimengMediaGrid 的 onAssetClick 漏传（默认 `{}`）致点卡不进详情——三 Screen 补 onOpenAsset 参数 + NavHost 三路由接线；dump 哈希前后对照实证（修复前点卡页面零反应，修复后进详情）。
- **审查**：D1~D3 一轮 reviewer 对抗审查，D3 部分 PASS（测试真锁含前置断言/三页接线镜像一致/core:ui 默认 {} 未破坏/零越界零硬编码）。
- **验收**：make app-build/app-test/app-lint 全绿（AlbumViewModelTest 7/0、Favorite 6/0、History 6/0，Video 族既有测试无回归）；实机 uiautomator 逐态 dump（默认收起/toggle/切维展开/收起▲/收藏历史两页点卡进详情）。证据 %TEMP%\qimeng-d3-evidence\（37 文件）。

---
## build(web): E6 vitest 测试基建——ADR-0017 实施，lib 纯函数 39 测试锁定（2026-09-08 第一百一十九笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 独立对抗审查含破坏性抽验，任务E-Web卷 E6）

- **基建**：devDependency vitest ^5.0.0（npm latest，peer 官方覆盖 vite ^8.2.0）+ scripts.test="vitest run"（package.json 仅两处 diff）+ 独立 vitest.config.ts（node 环境，不碰 vite.config.ts 构建链）；CI web job 接入仍记待办（ADR-0017）。
- **测试**：7 个 lib 纯函数模块 39 条全绿——sse 帧解析（跨 chunk/多帧/容错 8 条）、pagination 游标、format 档位与时钟（vi.setSystemTime 相对天数，时区无关）、album/history 分组（跨零点/过滤/剔无效）、search-mapping 两表 toStrictEqual 全表、route-keys 路径三边界；显式 import 不用 globals；sse 测试 vi.stubGlobal+动态 import 解决 api-client 顶层 localStorage 前提（isolate 隔离无泄漏）。行为锁定产出：formatBytes 1024²-1→KB 档怪癖、pagination 页长>limit 判到底等既有行为首次成文。
- **审查**：reviewer 逐行比对断言与源码（反推 3 嫌疑点均系锁定真实行为）+ 破坏性抽验（改断言精确变红→sha256 恢复核验）+ lock peer/picomatch hoist 连带闭环。P3 覆盖面建议记档（readAssetNavState/subscribeSSE retry/401 停连/dateRangeFor 等后续补）。
- **验收**：npm test 全绿 + tsc/oxlint（16≤17 新文件 0 告警）/build 41 entries。文档：HANDOVER_UI.md §5 第 24 条。证据 %TEMP%/qimeng-e6-evidence/（保留勿删）。

---
## feat(web): E5 自研图片查看器+列表上下文批次导航+zoom 备忘（2026-09-08 第一百一十八笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 两轮对抗审查/主会话浏览器验收 11 项，任务E-Web卷 E5=任务B §6 回补批）

- **图片查看器**（components/media/image-viewer.tsx 新，**零新依赖**）：详情页图片/动图点击进入全视口覆盖层（z-index 35，暗底，zoom 0.9090909 反补偿与 popper 同公式注释互指）；Pointer Events 双指捏合 0.5~5x/双击 1.8x/拖拽平移/横滑 60px 换件/单击 300ms 沉浸 chrome/Esc 退出；**保焦点缩放一般式 t1=t0+(s0−s1)·(f−c−t0)/s0**（首版特设式在平移/缩放态起手漂移达数百 px，reviewer 数学反例打回后返工）；纯视觉坐标（W-3 纪律）；查看永远发详情接口 origUrl 签名原件直链，GIF 原生动，视频不接查看器。
- **批次导航**：location state 快照+运行时校验（readAssetNavState），叠加组两入口（首页三 tab 流/UpNextList）传上下文；.asset-pager 上一件/下一件+n/总数、边界停止不循环（规格未定义处先行口径，待拍板 #18）；换件统一 assetDetailWithSearch 保查询串；直达/刷新无 state 降级隐藏；其余 7 处入口按设计不传。
- **zoom 备忘**：仓库外 web-zoom-memo.md（约定盘点+四方案影响面+迁移风险，只写不改）。
- **审查**：reviewer 首轮需返工——P2-1 保焦点公式（数学反例：t0=(-500,0) 双击漂移 400px）、P2-2 z25<搜索 z30 层级注释失实、P3×3 竞态（双动作同发/定时器叠加/lastTap 残留）→ 返工后复核通过（一般式反例精确落回/z 三处同步/31~34 无占用；package.json vitest diff 经裁决归 E6 批边界，非 E5 缺陷）。
- **验收**：主会话浏览器 18462 实测 11 项全过（查看器结构四值、双击缩放、沉浸切换、Esc、图→图横滑 viewer 保持+查询串保留、视频项卸载、pager 边界、无上下文降级、零依赖）；z35>30 看图终验过。文档：HANDOVER_UI.md §5 第 23 条。证据 %TEMP%/qimeng-e5-evidence/（保留勿删）。

---
## fix(web): E4 推荐偏好只留 4 预设——滑杆/草稿态退役+slider 组件清理（2026-09-08 第一百一十七笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 对抗审查/主会话浏览器验收，任务E-Web卷 E4=C6，用户 2026-09-07 口径）

- **改动**：设置页推荐偏好卡只留 4 预设 Pill（均衡推荐/高记忆流行/深度探索/新鲜优先，文案与 use-prefs.ts 逐字一致）；删 9 维滑杆/百分比/保存按钮/「有未保存的调整」及 draft/setPref/doSave/dirty 态、PREFS_LABELS；applyPreset 点击即 PUT（toast 保留），isActivePreset 对服务端保存值逐维比较（DEFAULT_PREFS 兜底）。use-prefs.ts 与协议零改动（9 维整体存取 UI 不暴露）。连带退役全站零引用的 components/ui/slider.tsx 与 prototype.css .slider-* 6 条死规则（radix-ui 包仍被 Switch/Select 使用不移除）。
- **审查**：reviewer 深挖服务端链反证高亮判定成立（clampPrefs 只钳 [0,1] 无归一化/浮点往返无损/DefaultWeights=PRESET_BALANCED 逐字一致）；被删符号消费方逐一 grep 零悬空。P2 备案：GET 失败兜底时「均衡推荐」误导性高亮（口径内双路兜底一致）；P3×5 记录（滞后一拍/satisfies 子集/注释过时/导出冗余/aria-pressed 既有）。
- **验收**：三命令绿（16≤17）+ 主会话浏览器实测：仅 4 pill 无滑杆/百分比/保存钮、点深度探索→「推荐偏好已保存」toast+高亮迁移+GET 回读 9 维与 PRESET_EXPLORE 逐字一致、其余设置卡不受影响。文档：HANDOVER_UI.md §2 现状行+§5 第 22 条。证据 %TEMP%/qimeng-e4-evidence/（保留勿删）。

---
## fix(web): E3 五页无感加载——LoadMorePill 退役换 useAutoMore 哨兵（2026-09-08 第一百一十六笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 对抗审查/主会话浏览器验收，任务E-Web卷 E3=C5）

- **改动**：相册/收藏/历史/搜索/集合五处 LoadMorePill 全换 useAutoMore 哨兵（触底提前 6 项自动拉下一页、hasNextPage=false 卸载哨兵），onHit 双守卫（isFetchingNextPage+isPlaceholderData 前瞻）；isFetchingNextPage 底部「加载中…」占位与首页模板同构；尾注「共 N 项 · 到底了」（!hasNextPage 且 items.length>0，保留原 pill 计数信息；MinePage 双 pane 各自挂）。components/ui/load-more-pill.tsx 删除（grep 零命中）；.pill/.pill-count/.grid-empty 共享样式核实有其他消费方后保留；Search/Collection 移除仅 pill 消费的 isFetching 解构（空态链不受影响，reviewer 逐处核实）。分组纯函数零改动。
- **审查**：reviewer 五处接线/enabled 口径与原 when 等价性/删除完整性四重复核/尾注 N 口径/换键无混拼窗口逐项过；三命令亲跑复现（tsc 0 错/oxlint 16w0e/build 42 entries=删共享 chunk 减 1）。
- **验收**：主会话浏览器 18462 实测——相册尾注「共 22 项 · 到底了」、历史 5 项、搜索 19 项、收藏 0 项空态无尾注、全站无「加载更多」；自动拉页数据集单页（22<60）不可触发备查。存量缺陷记账：集合页深链 /app/collection?author=… 冷启动整页空白（stash 法在 E2 基线构建复现，非本批回归）→ 待拍板-20260907。文档：HANDOVER_UI.md §5 第 21 条。证据 %TEMP%/qimeng-e3-evidence/（含 accept/acceptance-main-session.md，保留勿删）。

---
## fix(web): E2 首页加载/刷新过渡——榜单流 keepPreviousData（2026-09-08 第一百一十五笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 源码级对抗审查/主会话浏览器验收，任务E-Web卷 E2=C4）

- **改动**：`useRankingsInfinite` 加 `placeholderData: keepPreviousData`（use-stats.ts）——hot tab 的 qm:refresh/reloadKey 换键与 period 换档期间旧榜保留、无闪空；HomePage HotRankTab 过时注释修正（isPlaceholderData 自本批起真实变 true，E1 预埋透传字段随之激活，到底提示守卫与推荐流同口径）。
- **C4 其余项去向**：useRecommendations/useUpNextList 占位与 UpNextList 换一批旧卡保留=E1 已落地；MediaCard 入场渐入=B-2 批 qm-card-in stagger 本就存在；翻页流底部占位行现状已不跳版——本批无重复改动。
- **审查**：reviewer 源码级核验（queryObserver.js:265-283 占位仅换键+pending 生效、isLoading 过渡期 false）；消费方全核（useRankingsInfinite 全仓仅 HotRankTab，RanksPage/DataPage 单页 useRankings 不受影响、queryKey 'paged' 段无碰撞）；SSE 相互作用排除（无 RANKINGS 根键失效路径，同键失效时占位本就不生效）；占位窗口哨兵竞态源码级排除（infiniteQueryBehavior 退化为重拉首页，无害）。
- **验收**：三命令绿（oxlint 16≤基线 17，被改文件 0 告警）+ 主会话浏览器 18462 实测四场景（推荐刷新/hot 换档/hot 刷新/UpNextList 换一批）占位窗口旧内容保留零闪现；翻页场景数据集单页（17<60）不可触发，executor 代码级核实。P3 备忘：StreamCards 换键重取失败无 isError 分支（E2 前既有，另立批次收口）。文档：HANDOVER_UI.md §5 第 20 条。证据 %TEMP%\qimeng-e2-evidence\（保留勿删）。

---
## fix(web): E1 首页详情返回保态——叠加路由+查询串保态+占位防闪+打点闸门（2026-09-08 第一百一十四笔）

执行 AI：GLM-5.3-Flash（主会话调度；executor 执行/reviewer 两轮对抗审查/主会话浏览器 DOM 验收，任务E-Web卷 E1=C3）

- **叠加路由**：首页与详情收进 React Router v8 pathless layout route（新 `components/layout/HomeBackdropLayout.tsx`：HomePage 恒挂载底衬+Outlet+详情打开时底衬 inert；`home` 子路由 element:null）；URL 仍 `/app/asset/:assetId` 可直达（直达时底衬=首页）。AppShell pathname→scrollTo(0,0) 加叠加组门（route-keys.ts 新增 HOME_PATH/ASSET_DETAIL_PATTERN/isAssetDetailPath/inHomeDetailGroup 单源）：组内互切不复位、进出组真导航保持回顶；回顶部 FAB 叠加期隐藏。`.asset-overlay` 叠加层全 token 锚点（--header-h/--sidebar-w）+独立滚动+overscroll:contain+z-index 10 有序。
- **查询串保态（reviewer P1 返工项）**：叠加组导航统一 `assetDetailWithSearch(id, location.search)`（首页 openDetail 与 UpNextList 行共用）——`?tab=cos`/`?tab=hot&period=` 全程随 URL，底衬 tab 子组件不换挂、换一批 seed/reloadKey 不丢；`assetDetail()` 改 generatePath(ASSET_DETAIL_PATTERN) 单源派生（P3.3 消模板串双写）。
- **占位防闪**：useAssetDetail/useRecommendations/useUpNextList 加 placeholderData: keepPreviousData——刷新/换批/详情→详情换件保留旧内容至新数据就绪，无整页「加载中…」/「暂无推荐」闪现。
- **占位窗口打点闸门（reviewer P2 返工项，防跨资产错账）**：useProgress 增可选 enabled（占位窗口 tick/flush no-op；切资产补报 effect 不受门限，旧资产最后位置报旧资产）；useDwellReport 占位传 undefined id（空 id 不开段既有行为，调用点注释声明）；reportPlay 加 isPlaceholderData 守卫；open 打点不设门（导航事实+服务端 assetId+当日去重）。
- **到底提示守卫（P3.1）**：StreamCards endHint 与 cos 页脚「· 到底了」加 !isPlaceholderData——换 seed 占位窗口不闪「到底了」。
- **审查与验收**：reviewer 首轮需返工（P1 UpNextList 丢查询串/P2 打点错账/P3.1 到底闪现/P3.3 模板双写）→ 续聊原 executor 返工 → 二轮全项通过（含 React Query v5 hasNextPage 源码级推演：占位窗口哨兵自动卸载无混拼入口）；主会话浏览器 DOM 验收 18462 隔离虚构实例 14 步全过——底衬 .content scrollTop 进出详情精确保留（538.70±0）、叠加层独立滚动（Z=485.2）不传染、cos/hot 两链查询串逐级保留+换批流按历史条目还原、占位窗口「到底了」抑制实测（0~518ms 无闪现）、真导航复位不受门误伤、首页网格顶沿 83.1 与 §4.5 基线零漂移；三命令绿（oxlint 16≤基线 17，stash 对比法核实）。文档：HANDOVER_UI.md §5 第 19 条+文头。证据 %TEMP%\qimeng-e1-evidence\（保留勿删）。留档：非 home 页进详情仍整页重建（范围外）；非 home 入口底衬隐发请求=叠加设计固有成本；P2 dwell 段占位窗口延后起算（<0.5s 且 <1s 段不上报，口径影响可忽略）。

---
## docs: 前端维护审查+夜间双卷任务书重组（2026-09-07 夜 第一百一十三笔）

执行 AI：GLM-5.3（主会话调度；审查/修复由执行子代理并发完成，四路审查+两路修复）

- **任务书重组（用户指令：删多余任务书、并为两份可并行的新书）**：删除仓库外《任务A-UI对齐.md》《任务B-详情页.md》《任务C-回归修复与体验对齐.md》《待拍板-20260905夜2.md》《待拍板-20260905夜集群.md》，全部存活内容吸收为两份新任务书——《QimengNAS/任务D-Android卷.md》（D0~D7：C1 图片全屏/C2 视频两级全屏+方向恢复/C7 胶囊收起/C8 截图对照+B6 收尾/M4-4 离线队列/M4-3 整批自查/M4-7 验收）与《QimengNAS/任务E-Web卷.md》（E0~E6：C3 叠加路由保态/C4 加载过渡/C5 五页无感加载/C6 偏好只留预设/Web 回补批自研图片查看器+批次导航/vitest 基建）。双卷文件集互斥（android/** vs web/**）、共享文件（docs/协议/Makefile）冻结由主会话串行写——并行零代码冲突；夜间执行=单调度会话+双写车道+常驻读车道（持续 ≥3 子代理、异步可中途微调、免授权全程后台）；拍板存量合并为《QimengNAS/待拍板-20260907.md》。
- **证据协议更新（用户 2026-09-07 决策）**：截图解禁但仅限虚构测试数据（HANDOVER_APP §4.7 改写）；**8420 真库/真机永不连写截图**——今早用户复查曾连真库，已切回：App pm clear 重登 18461 虚构实例（dump 证据全是「测试出处A/B/C」标记，存 `QimengNAS/run-screens-20260907/`），真实截图移出仓库，`.gitignore` 增 `.run-screens/` 防再犯。
- **ADR-0017 预落**：web 测试基建 vitest（仅 devDependency；E6 批实施，npm registry 例外授权）。
- **文档同步**：HANDOVER.md（头部+当前待办改双卷入口）、HANDOVER_APP.md（头部+批次表 B6/任务C 段+§4.7）、`docs/adr/INDEX.md`、仓库外 00-总说明.md 任务入口句。

---
## refactor(web): 维护审查清偿——路由键收口+轮询常量单源+页面规则抽离 lib+死常量清理（2026-09-07 夜 第一百一十二笔）

执行 AI：GLM-5.3（主会话调度；执行子代理）

- **审查发现（对抗审查四维全过，总评通过）**：4 P2——①`use-system-status.ts` refetchInterval=2000 魔法值与零消费死常量 STATUS_POLL_INTERVAL_MS=3000 注释失真三合一；②`/app/asset/${id}` 模板串 9 处/8 文件散写而 route-keys.ts 自称唯一来源；③相册/我的/搜索三页分组与映射规则内嵌组件（ADR-0008 违例）；④web 零测试基建（→E6 批+ADR-0017）。
- **清偿**：STATUS_POLL_INTERVAL_MS 统一 2000（行为不变）+MaintenancePage 差分改常量派生 RATE_DIVISOR_S；route-keys.ts 增 `assetDetail(id)`，9 处全改引用（URL 逐字节不变）；抽离 `lib/album-grouping.ts`/`lib/history-grouping.ts`/`lib/search-mapping.ts` 纯函数，三页改 import；删 SOURCES_PATH/METRICS_PATH 死常量；UpNextList filter 补类型谓词（assetDetail 参数严格化的最小必要）。P3 悬置：Suspense fallback 白屏、theme.ts hex 双写（有同步注释）记档。
- **验收**：web 目录内 tsc --noEmit exit 0 / oxlint 17 warnings（存量持平）0 errors / npm run build ✅（executor 亲跑贴原文）；全仓 `make lint` ✅。C3~C6 根因定位（AppShell.tsx:31-33 scrollTo/use-assets 无 placeholderData/四页 LoadMorePill/SettingsPage.tsx:196-239）已写进任务E 卷供执行免复研。

---
## fix(app): 维护审查清偿——❤/⭐ 前缀单源+列表族常量收敛 core:model+DwellSessionTracker 连续 pause 边界（2026-09-07 夜 第一百一十一笔）

执行 AI：GLM-5.3（主会话调度；执行子代理）

- **审查结论**：M4-3c/3d、B4、B5 四笔对抗审查全过（总评通过）——架构红线/安全/测试质量/真实数据残留（无）全部在位；P1 横屏卡死已由任务D-D2 立项。
- **P2 清偿（行为修正）**：❤/⭐ 快捷标签前缀三处独立定义口径分叉（BiliPlayerView startsWith("❤️") 带变体符 vs TimelineTagColors.HEART_PREFIX 不带 vs VideoStage 第三处）——统一单源 TimelineTagColors（判定用裸前缀兼容两种写法、写入用完整字面量 HEART_TAG/STAR_TAG），修正"同源"不实注释；手输无变体符标签取色与芯片底色不再分叉。
- **P3 清偿**：PARTITION_KEY_ALL/LIST_PAGE_SIZE/LIST_LOAD_FAILED_MESSAGE 三 VM 复制收敛 `core/model/ListQueryDefaults.kt` 单源（协议联动注释）；DwellSessionTracker 连续两次 pause 清掉挂起位致 resume 丢段——pause 加 session 非空守卫+回归测试。
- **验收**：7 模块 202 任务全量重跑（--rerun-tasks）215 单测 0 失败；grep 单源验证（❤/⭐ 字面量、PARTITION_KEY_ALL 各只剩一处定义）；app-build/app-test/app-lint 三件套+全仓 make lint ✅。

---
## fix(server): GET /assets/{id} 补 cosWork 映射（台账#15）+ openapi sessionId 口径注释勘误（#16）（2026-09-07 夜 第一百一十笔）

执行 AI：GLM-5.3（主会话调度；执行子代理）

- **#15（用户可见缺陷）**：详情组装漏 cosWork——`assets_detail.go` 增 fetchAssetCosWork（复用列表端点 ListCosWorkForAssets 同款查询，失败降级空串同列表策略），非空时填 detail.CosWork；Android 详情标题恢复「cosWork 优先、null 回退 fileName」语义。新增 TestAssetDetailCosWork（COS 资产返回 cosWork 级断言+常规资产缺省）。
- **#16（协议注释勘误）**：openapi ViewEventReport.sessionId description 改为真实口径「open/play 按当日会话级去重、dwell 不去重逐条累加」（对照 engagement.go 与 DOMAIN_RULES §5 核实）；顺带 AssetSummary.cosWork 描述补「详情端点也返回」；`make sdk` 三端生成物同步（均 gitignored 本地重建）。
- **验收**：go test ./... 15 包全绿（httpapi 实跑 10.3s）；gofmt 清偿；web tsc（用新生成物）exit 0。附记：make sdk 首跑遇 redocly Windows libuv 偶发崩溃（spec 校验已过后崩溃，重跑即过）——CI 间歇假失败风险记档待观察。

---
## docs: 任务C 回归修复与体验对齐批任务书落档——8 项用户反馈根因定位+今晚队列合并（2026-09-07 第一百零九笔）

执行 AI：GLM-5.3-Flash（主会话，规划会话）

- **范围（用户 2026-09-07 实测反馈 8 项）**：新建仓库外《QimengNAS/任务C-回归修复与体验对齐.md》（自足任务书，同任务A/B 体例）：C1 Android 详情页图片点击全屏缺失、C2 视频全屏两级制+退出详情页恢复竖屏、C3 web 首页详情返回保态、C4 首页加载/刷新过渡、C5 相册等四页无感加载、C6 推荐偏好只留预设去参数、C7 Android 维度胶囊默认收起、C8 新旧相册截图对照修复；并与今晚既定队列（任务A B6 收尾④⑤→M4-4→M4-7→Web 回补批→自审）合并为一条夜间队列（任务书 §4）。
- **根因定位（主会话实读代码，写入任务书供执行者免复研）**：C1=`DetailStage.kt:54-57` 固定宽高比舞台+`DetailScreen.kt:133` 单击只切 chrome；C2=`VideoStage.kt:273-277` requestedOrientation 无 onDispose 兜底（用户截图实证退出详情后 App 卡横屏）+全屏直接锁横屏无竖屏全屏中间态；C3=`router.tsx:44,54` 路由切换卸载 HomePage（seed useState 复位/滚动位置丢）；C4=卡片无入场动画+qm:refresh 整流重置；C5=`AlbumsPage.tsx:296-301` LoadMorePill 手动按钮（首页已有 use-auto-more 哨兵）；C6=`SettingsPage.tsx:211-227` 9 维滑杆+百分比（旧版 GUIDE_UI:255=仅 4 预设）；C7=`AlbumFilterState.kt:35` expanded 默认 true+三 VM 切维强制展开（覆盖 M4-2「B8 拍板」口径）；C8=吸收任务A B6 的 79 项视觉豁免重审+场景补全 S9/S10。
- **关键拍板落档（用户 2026-09-07 口径）**：视频全屏两级制（先竖屏全屏再可选横屏，覆盖旧版 GUIDE_UI:195 竖屏视频不可全屏句）；推荐偏好只显示选项；胶囊默认不弹开；C8 截图对照例外（仅限对照环境虚构数据，8420 真库/真机仍禁）。
- **文档同步**：`docs/HANDOVER.md`（头部最后更新+当前待办新增最优先条目）、`docs/HANDOVER_APP.md`（头部最后更新+批次表区任务C 段）。本笔=纯文档，无代码改动；协议零改动。

---
## feat(app): M4-2A-B5 收藏/历史跟随+空态——两页悬浮化迁移+TitleRow 可选返回/列数+QimengFourDimSection 退役（2026-09-07 第一百零八笔）

执行 AI：GLM-5.3-Flash（任务A 会话主代理调度；执行子代理实现+reviewer 对抗审查+P2 清偿复核通过）

- **范围（任务A-UI对齐 §2 B5）**：收藏/历史两页随共用状态机获得 B2/B3 改动——头部重排镜像 AllScreen（QimengTitleRow+QimengChipRow+QimengFloatingPillPanel 悬浮药丸+网格 bottomContentPadding）；两 VM 补 onDimChipClicked（点已激活维=切展开/折叠，镜像相册页）；收藏空态双行逐字「还没有收藏\n在详情页点击收藏按钮添加」（旧仓库 FavoriteFragment L376）+COS 分支，文案迁各页 strings.xml（emptyText:String→emptyTextRes）。
- **主会话四项裁定落地**：①QimengTitleRow 加可选 onBack（实录两页有返回钮）；②列数图标改可选（实录两页无列数图标，相册页不受影响）；③双指缩放两页不接（不在 B5 清单，B6 裁决项）；④历史清除按钮=协议缺口只记录（/api/v1/history 仅 GET；旧版 historyClearBtn 无端点支撑，2026-09-05 拍板 2B 砍交互，补齐须先改 openapi.yaml）。
- **组件退役**：QimengFourDimSection 迁移后全仓零代码调用，删除（Dimens 相关注释清理）；QimengTitleRow 唯一旧调用方 AllScreen 具名传参零改动兼容。
- **质量与审查**：reviewer round1（唯一 P2=history 空态测试缺失而 KDoc 虚称已锁）→ 补镜像用例清偿 → 复核确认（越界清单空、S1/S2 对照零新增红、证据链闭环含 dumpsys GMT 时区考据）。
- **测试与证据**：FavoriteViewModelTest 6+HistoryViewModelTest 6（芯片四态语义/空态分支/既有并发族）；四命令全绿（app-lint 全量本轮恢复可用）；实机文本证据 B5-*（收藏空态双行逐字/芯片四击/历史页分隔线对齐「角色|类型」/相册页回归）；elem_compare S1/S2 红项与 B3-round2 §5 登记集合零新增。
- **记录（B6 收口项）**：统计行 TitleRow 右端 vs 实录独立整行=B2 遗留版式差（豁免候选）；两页统计行数据源 totalForAllPill（HistoryPageResult 无 totalMatched，数值等价）；历史页 3 维 vs 实录 4 维（缺「作品」chip，M4-2 协议缺口现状）；收藏/历史页对照场景 harness 尚未建（S1-S8 外）；历史带数据态未实测（列表点击进详情导航属 M4-2 既有缺口）。

---
## feat(app): M4-2A-B4 搜索页三态对齐——入口/建议/结果逐字复刻+删筛选芯片+返回族清词+列数内存态，harness 增 S6~S8（2026-09-07 第一百零七笔）

执行 AI：GLM-5.3-Flash（任务A 会话主代理调度；执行子代理实现+reviewer 对抗审查两轮+修复轮+复审通过）

- **范围（任务A-UI对齐 §2 B4）**：搜索页三态复刻——入口态（顶栏三件=返回 desc+placeholder 逐字「搜索文件、出处、角色、COS作者…」+文本按钮「搜索」；推荐搜索词丸流 q=""/recommend=true/limit10；搜索历史区+清除历史 desc 逐字）；建议态（行=icon desc「候选」+候选名+五维类型标签，/search/suggestions 从短到长）；结果态（仅日期分组网格，组头两空格格式；空态逐字「未找到相关内容」旧 XML 口径）。**删**：结果态两条筛选芯片（分区/类型——SearchViewModel 删 partition/mediaType 链路，固定 includeCos=true 纯 q 检索，标签/排序族已由 B3 承接）、顶栏清除 icon、QimengPullToRefresh（实录/旧 XML 均无）。
- **返回族语义（round1 P1 纠偏，reviewer 亲读旧仓库 SearchFragment L253-270 铁证）**：系统返回与左上箭头同走 handleBack——结果/建议态一律**清词回入口态+重拉推荐词**，入口态退页；「点搜索栏」是独立点击路径（词保留回建议态，onFocusChange 语义）。初版误实现为词保留回建议态，已反转+测试锁死。**列数（round1 P2 纠偏）**：页内存态 3 列起步（旧版 ColumnsRef(3) 从不落盘）、双指缩放 clamp 2..5 只本页生效，删持久化注入。
- **质量与审查**：round1（1 P1+1 P2+8 P3）→ 修复轮全清偿（P3 带走 4 项：未用 strings 删除/词保留用例正名/改词切建议直测/@OptIn）→ round2 复审通过（越界清单空、12 单测亲跑全绿、实机证据 18 件抽查属实）。返工期环境两坑自愈进 harness：elem_compare login_if_needed 加固（地址空盲触回填）+新增 stop op；compare_scenes search_reset 改 force-stop 冷复位。
- **harness 扩展（B4 验收依赖）**：elem_compare run_steps 新增 ("text",…) 文本输入 op（adb input text 仅 ASCII→查询词用 M/A 命中虚构数据）；compare_scenes 新增 S6 入口/S7 建议/S8 结果三场景+search_reset 幂等复位（新侧定位全 text/desc；词丸随机/候选池环境差不签）。
- **测试与证据**：SearchViewModelTest 12 用例（三态状态机/返回链三分支/防抖取消/空词 no-op/代际防乱序纯 submit 链/列数 clamp+内存态）；实机文本证据 B4-24 件+B4fix-18 件（`%TEMP%\qimeng-m42a-evidence\`，禁截图）；elem_compare S6/S7/S8 结构文本层全对齐（余红=视觉裁片差 B6 豁免域+S8 组头计数 CAUSE_GROUP/缩略图配对 CAUSE_THUMB 环境性已知豁免）。
- **记录（不阻塞）**：结果态点栏后不自动拉焦点键盘（旧版有 showKeyboard，B6 实机体验项）；服务端 q 五维匹配范围核实（能力缺口候选，零协议改动红线内不修）。

---
## feat(app): M4-3d 断点续播+时间轴标签+行为打点集成——策略层+VM 接线+壳层 manifest（2026-09-07 第一百零六笔）

执行 AI：GLM-5.3-Flash（B 会话主代理调度；3d 纯逻辑+集成两轮执行子代理+对抗审查两轮，清偿复审 15/15 PASS）

- **策略层（feature/detail/playback，21 单测）**：`WatchState`（已看完冻结判定 lastPositionSeconds≥durationMs/1000→起点归 0+徽标，客户端推导）；`ProgressThrottlePolicy`（5s 具名常量严于协议建议 10s+force 立即放行+reset 防旧值外泄）；`DwellSessionTracker`（**分段累加口径**：pause=flush 当前段并结束会话、resume 仅紧跟 pause 开新段（awaitingResume 门闩）、段内幂等——对齐服务端 engagement.go「dwell 不去重逐条累加」，推翻初版误读 openapi 注释的「恰一条」）；`DirectAnalyticsReporter`（open/play/dwell 直连，TODO(M4-4) 三处）。
- **时间轴标签**：`TimelineTagColors`（❤→红/⭐→金 name 前缀约定，协议零改动）；VM 加载/新建/删除+重拉；VideoStage 映射桥接实体→updateTimelineTags 芯片（点击 seek 回看）+添加对话框（❤️/⭐ 快捷键+打开暂停 wasPlaying 快照恢复）+长按菜单（跳转/删除）。
- **接线**：续播起点→VideoStage startPositionMs；进度 1s 轮询→节流→PUT progress+暂停/离开 force；打点 open（VM init）/play（起播回调）/dwell（Screen 生命周期）；sessionId=每详情实例 UUID（对齐 DOMAIN_RULES §5 会话去重）；DetailStage 解冻删穿墙（3b 冻结债）。
- **壳层**：MainActivity configChanges（orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden）——修 3c 遗留「全屏旋转重建 Activity 杀播放器」。
- **审查清偿**：P1 dwell 分段语义反转（含用例反转+两段累加用例）、DetailRepository 接口收拢抽象、标签增删失败路径 3 用例、P2 全屏方向常量、findActivity 单源等 15 项全 PASS。
## feat(app): M4-3c 视频态基座——media3 1.8.0 锁版+BiliPlayerView/TimelineTagEntity 桥接+G1~G9（2026-09-07 第一百零五笔）

执行 AI：GLM-5.3-Flash（B 会话主代理调度；3c 执行子代理实施+对抗审查「可提交」+清偿复审）

- **依赖锁定（禁联网双来源）**：`media3=1.8.0`——旧项目 `gradle/libs.versions.toml:20`（BiliPlayerView 原生宿主，桥接兼容已验证）+本地 Gradle 缓存已解析构件；toml 注释标注「待联网复核」。
- **桥接（ADR-0014 例外清单）**：旧仓 `ui/detail/BiliPlayerView.kt`（837 行）整文件搬运至 `feature/detail/video/`（包名/R/私有 dp 三处适配；TimelineTagEntity 由 Room 实体剥离为纯数据类，6 字段不变；搬运时补回 speedPopup 字段）；G4/G7 三档上限与倍速表抽 `PlayerMath` 纯函数（7 单测锁边界）；POSTER/PLAYING/ENDED 状态机（6 单测：幂等起播/ENDED 回 0）；`VideoPlayerState` ExoPlayer 生命周期（创建即静音 volume=0、handleAudioFocus、ON_PAUSE/RESUME 记忆恢复、onDispose 唯一释放点）+logcat TAG=QimengVideo 四态；12 个 drawable 逐字节一致。
- **手势逐项**：G1 单击播停/G2 双击横屏/G3 长按 2x+锁速区/G4 水平拖进度（24dp 阈值+三档上限+非 READY 忽略）/G5 亮度音量=不做（旧版无）/G6 全屏（固定 LANDSCAPE 对齐旧版乱闪规避注释+800ms 防抖）/G7 倍速 0.5~2x/G8 默认静音/G9 控制器 5s 自动隐藏。
- **lint 清偿**：UnstableApi opt-in 链（DetailStage/VideoPlayerState `@androidx.annotation.OptIn`）+update 块改 `LocalConfiguration.current`。
## feat(app): M4-3b 图片态——ZoomImageView/GpuInfo 桥接+原图直链+预加载窗口+沉浸 chrome（2026-09-07 第一百零四笔）

执行 AI：GLM-5.3-Flash（B 会话主代理调度；3b 执行子代理实施+对抗审查两轮+清偿复审 15/15 PASS）

- **桥接（ADR-0014 例外清单）**：旧仓 `ui/detail/ZoomImageView.kt`（420 行）+`core/GpuInfo.kt` 整文件搬运至 `feature/detail/image/`（AppCompatImageView→ImageView、AppLog→Log 两处文档化适配；手势常量 0.5x~5x/双击 1.8x/60dp 滑动阈值逐值保留）；minSdk26 下 AnimatedImageDrawable(API28) 加版本短路（lint NewApi）。
- **口径②原图直链**：图片舞台 Coil 显式 `Size.ORIGINAL`（不写 size 会被 Coil 按 View 尺寸自动降采样，违背「查看永远发原件」）；GPU 防护=搬运件长边>4096 SOFTWARE 分层。
- **预加载窗口（拍板③）**：`DetailPreloadPolicy` 纯函数（前1后2/不含当前/超大图窗口限量1距最近/heap≥0.6 跳超大/未知按普通）+VM 接线（邻位详情拉取+Coil 预取 Disposable 取消链）+6 单测；heap provider 双通道（Java+native）。
- **沉浸模式**：DetailScreen chromeVisible 单源+SystemBarsImmersiveEffect（只控显隐不触发重排，onDispose 恢复）。
- **审查清偿**：P1 预载链 UI 消费缺失（DisposableEffect 补 Coil enqueue+dispose map）、P2 heap 丢 native 通道、P3 EGL 参数/重复 id 用例/超时防零。
## feat(app): M4-2A-B3 万能筛选面板——底部筛选面板全参数族+标签增删+实录逐字对齐，PROJECT_PLAN M4-2 条目补勾（2026-09-07 第一百零三笔）

执行 AI：GLM-5.3-Flash（任务A 会话主代理调度；执行子代理实现+reviewer 对抗审查两轮+修复轮+复审）

- **范围（任务A-UI对齐 §2 B3）**：万能筛选面板（ModalBottomSheet 全开 0.62 占屏）落地相册页——8 分区逐字实录（排序 7 单选/顺位/观看 5 档/点击 5 档/大小 4 档/时间 7 档/标签模式/标签流）+「重置/应用筛选」底部双钮；编辑态=草稿（打开拷贝已应用值/下滑关闭丢弃/应用写入+applyFilter 代际刷新链）。协议映射零协议改动：sort/order/viewRange/playRange/sizeRange/dateFrom/dateTo/yearFrom/yearTo/tagIds/tagMode 全参数族（AssetQuery+AlbumFilterState 扩展，SdkMappers 枚举镜像，年份交叉归一 start=min）；时间档=滚动窗口口径（今天 00:00 / 本周 7 天 / 本月 30 天 / 近三月 90 天 / 本年 365 天，reviewer 亲核旧仓库 MediaBrowserLogic L488-491 证实，非自然周月年）；标签流多选+长按删（确认框+级联警示，恢复旧版 L239-250 行为）+「+ 添加标签」（预查重拦一道+服务端 409 兜底一道，反馈「标签「N」已存在」逐字旧版文案），走 /tags GET/POST/DELETE 三方法（MediaRepository 标签族，接口默认实现为避开任务B 在改的 DataModule，round1 P3 记录任务B 合并后收编）。
- **入口裁定（落档 B3-round2 §7）**：筛选入口只在相册页标题行（QimengTitleRow 可选 onFilterClick）——旧版实录仅全部页有 allFilterButton（favorite/history 实录无），/history 协议亦无筛选参数族；任务书「相册/收藏/历史」三处入口与实录冲突处按任务书 §3 三重优先级（实录最高）裁实录。
- **交互语义对抗审查揪清（round1 P1）**：「重置」从「草稿回默认不关面板」纠正为旧版三合一（回默认+立即应用+关面板，旧仓库 MediaFilterSheet L266-269 口径）+用例反转；年份初值 2020..当前年（旧版 MediaFilterState 缺省）；「按年份」双下拉（Compose 官方 API，零新依赖）。
- **审查链**：round1（1 P1+5 P2+7 P3）→ 修复轮全清偿（含 compare_scenes.py S1-S4「筛选图标」定位 text→desc 假红修正、N1 测试假锁修正：conflictNames 注册先于 addTag+前置 TagExists 断言）→ round2 复审全项真实清偿、越界清单空；主会话裁定 S1-S4 首次启用的截图像素比对 69 条视觉红=旧 View vs 新 Compose 环境级渲染差，B6 豁免清单只免视觉列不免结构与文案列（B3-round2 §5/§7）。
- **测试与证据**：新增 AlbumPanelFilterTest 19 条（映射/滚动窗口日期算术/年份往返）+AlbumFilterPanelTest 11 条（草稿拷贝/应用刷新链+参数/重置三合一/关闭丢弃/预查重/409 兜底/删已选标签移除/反馈清除真锁）；模块级全绿（全量 make app-test 时点受任务B detail 中间态影响无法复现全仓数，round2 §2 已注明归属）；实机文本证据（headless+18461，禁截图）：面板 8 分区逐字、应用「未观看+本周」统计行 22→17、重置三合一、重名拦截零请求、删除确认框逐字、年份 2020/2026，存 `%TEMP%\qimeng-m42a-evidence\` B3fix- 前缀；elem_compare 全量 S5 面板结构/文案双端全绿、S1-S4 假红消除，余红=视觉裁片差（B6 豁免清单）+Tab-全部（已豁免）。
- **文档同步**：PROJECT_PLAN M4-2「登录/首页/列表/筛选/搜索」条目随本笔勾选（万能筛选面板=M4-2.1 缺口补齐）；HANDOVER_APP M4-2A 段 B3 勾选；任务A-UI对齐 §0 进度表同步（仓库外）。

---
## feat(app): M4-3a 详情页骨架与排版——路由+竖屏堆叠排版全链+批次导航逻辑层+ImageStage/VideoStage 文件缝（2026-09-07 第一百零二笔）

执行 AI：GLM-5.3-Flash（B 会话主代理调度；前会话半成品续作+三路只读调研子代理并行消化规格+执行/审查子代理多轮）

- **范围（任务B-详情页 §1 子批 3a）**：detail 路由接入（路由契约单源 DetailRoutes 下沉 feature:detail，故意不设 launchSingleTop 支持批次导航叠栈）；媒体舞台（图片/动图/视频海报占位，宽高比自适应兜底 16:9）；标题 cosWork??fileName；meta 行六字段（浏览·播放·大小·尺寸·日期·出处）；点赞收藏互动行（PUT 无 body 服务端 toggle + LikeState 权威回填 / 收藏显式布尔）；标签行+管理弹窗（读-改-写 PUT 整体替换）；作者卡（displayName+·COS+关注 toggle）；「接下来播放」推荐栏（limit=12/seed 换一批/cosOnly/同类型收窄/排除当前）；MediaBatchIndex 内存批次单点+moveBy 邻位解析（3 单测，UI 接线留 3b）。
- **并行纪律**：只碰 feature/detail|home 与 core data/model/ui 的 detail 专属文件；任务A B3 在途文件（AlbumFilterState/QimengFilterSheet/SdkMediaRepositories 等）零接触零编译依赖（detail 走独立 DetailRepository 端口，QimengFormat.kt 追加函数经 diff 核验纯 detail 性质）。
- **质量与审查**：对抗审查两轮（首轮判「不可提交」揪出 P1 大小格式化三处走样→新增 formatBytesForDetail 对齐 Web format.ts 口径+QimengFormatTest 11 用例锁死；复审 `--rerun-tasks` 全量重跑终判「可提交」）；DetailSections 728 行拆 6 文件（**ImageStage/VideoStage 占位桩为 3b/3c 并行批铺好文件缝**）；测试矩阵 DetailViewModel 11+SdkDetailMappers 6+MediaBatchIndex 3+QimengFormat 11 全绿（core/ui 建首个测试目录）。
- **模拟器文本证据（18461 虚构数据+headless uiautomator，禁截图协议）**：图片/COS/视频三类详情页 dump 对照 Web 逐项通过——批次序号 i/N、meta 六字段（服务端 null 时正确省略/有值 960×540 正确显示）、作者卡 ·COS+关注双态、推荐栏同类型收窄+时长角标；证据驱动修复 AAPT 裁剪 `detail_author_cos_suffix` 前导空格（\u0020 转义）。证据存 `%TEMP%\qimeng-m43-3a-evidence\`。
- **环境发现上报**：server AssetDetail.cosWork=null 而 AssetSummary 正常（detail 组装丢字段，Web 同受影响）→《待拍板-20260905夜2》#15；开发 token 短时效登出属 M4-1 设计行为非缺陷。
## fix(server): 分割审查清偿·server 卷（Android 工作日不触 app/web/api）——上传原子落盘+指标字节修正+签名构造单源+错误码常量化+import 拆分+DOMAIN_RULES 七处勘误（2026-09-07 第一百零一笔）

执行 AI：GLM-5.3（主代理；三路只读审查子代理并行调研：安全红线/代码卫生与架构边界/领域一致性）

- **审查背景（用户指示）**：今日工作区在跑 Android M4-2A-B3（前端日），按「前端日只审后端」分割审查以避免影响在跑任务——本笔只动 server/** 与文档，未触碰 android/web/api，零协议改动零 SDK 重生成（工作区 android 未提交改动原样保留）。
- **安全清偿（SECURITY 红线 4 落盘原子性，唯一行为变化）**：上传从「os.Create 直写最终路径」改为「同目录 .qm-upload-*.tmp 临时文件 + 原子 rename」（upload.go 拆出 receiveAndStore 子程）——进程崩溃不再留下占用最终名的半成品（残留 .tmp 在扫描白名单外不会入库），并发同名上传不再互相截断写坏；新增锁定测试 TestUploadAtomicNoTempResidue（成功路径无临时残留 + 断连路径最终名不出现/临时清理，失败路径同步直调子程避免异步竞态）。**顺带修正 upload_bytes_total 严重少计**：原只计魔数头字节（≤512B），改按落盘事实 fi.Size() 计（OBSERVABILITY「累计字节」口径）。
- **签名构造单源（代码卫生约束 4「发现手抄即修」）**：auth/mediaurl.go 签发/校验两侧手抄的三行 MAC 消息构造抽共享 signMAC——单侧漂移会静默炸掉全部存量直链，现在物理上不可能只改一侧。
- **错误码常量化（卫生约束 3）**：writeErr 的 32 种协议错误码从 ~120 处裸字面量收敛为 errors.go 常量族（code* 前缀），附「与 openapi Error.code 双写联动、改动须同步协议侧」注释；测试文件按豁免条款不动。
- **import.go 拆分回警戒线内**：事件回放段（replayEvents/replayDailyBrowse/replayStatsGap/replayHistory 等 ~210 行）拆出 import_replay.go（639→435 行）；open/play 回放双循环逐字重复抽 replayKind 共享；事件 kind 字面量 9 处（import 两函数 + assets_detail switch）改用 gen.Open/Play/Dwell 协议常量。
- **超线补注 7 处**：>100 行函数补「超函数警戒线理由」注释（upload/facets/recommendations/history 四 handler=oapi 生成签名+单请求直线流；main/Server.New=组合根直线装配；Scanner.Scan=阶段编排、子步骤已拆），assets.go:309 既有范式对齐；import.go 超线以拆分消除而非注释。
- **P3 卫生批七项**：①直链 TTL 双源（config/httpapi 各写 6h）收敛 config.DefaultTokenTTL 单源+httpapi 别名；②immutable 年缓存双常量收敛 contentAddressedCacheControl、no-cache 三处收敛 noCacheControl（httpapi）+sseNoCache（events，跨包各自具名）；③config/clientlogs 五条校验文案改 Sprintf 引用常量（边界数值不再双写）；④httpapi/doc.go 与 main.go 头注释的「501 占位/M1」过时表述更新为全接线现状；⑤scanner progressMinEvery 内联默认提具名常量 defaultProgressMinEvery；⑥stats/doc.go 职责措辞收敛（事件流→物化表重建编排在 httpapi，不在本包）；⑦dev 免密模式+非回环监听组合启动打 Warn（SECURITY 开发模式边界，只提醒不阻止，loopbackListen 判定）。
- **DOMAIN_RULES 七处勘误补记**（全部文档侧对齐既有锁定行为，零代码行为变化）：§2 同分排序键「最近活动时间」勘误为文件 mtime（TestRank_tieScore_newerModifiedAtFirst 锁定口径）；§1.1 tagRelevance 空默认 0.2 补记；§4「其他沉底」澄清为展示层约定（/sources 按 fileCount 降序）+数字保护 RE2 实现注+多出处同名角色合并例外；§6 媒体扩展名判定集合（§9 白名单∪wmv/bmp/svg/tiff/tif）；§10 表行拆分（likes/favorites 非事件回放）+回放去重三口径补记；§11 缓存键公式勘误为 SHA-256("v2:assetId:size") 带定界符形态。SECURITY.md 开发模式节同步补启动 Warn 条目。
- **审查零发现项（三路子代理交叉确认）**：SECURITY 红线 1/2/3/5/6/7/8 全守住（路径三层防御 NormalizeRelPath/PathWithinRoot/os.Root、HMAC exp 入签+恒时比较、token 哈希存储+恒时比对、回收站语义与物理删除边界、body/分页/SSE 上限全覆盖、错误响应零内部信息）；depguard 两红线人工复核零违规；recommend/stats/sourcematcher/authoring 四纯函数包零 IO 零内部依赖；OBSERVABILITY 九族埋点口径零偏差；130 组内置检索表实测核实；十维权重/回收表/-0.8/分档值等「逐字遵守」常量全部一致。
- **备查挂账不修项**：trash 指标刷新超时后的残余遍历 goroutine 与扫描 goroutine 的 ctx 编排（进程退出瞬间语义，涉及 shutdown 编排重设计，留专项）；upload/trash 两处 exists 闭包近似重复（4 行级，抽取收益低于扰动）。
- **验证**：go build ./... + go vet ./... 全过；go test ./... 15 包全绿（httpapi 13s 实跑含新增原子性用例）；golangci-lint run 0 issues；本笔未跑 make sdk（无协议改动）。

---
## docs: M4-2A B2 残留清理——任务A文档续作指引归档 + 并发纪律勘误「在跑子代理 ≤3」（2026-09-07 第九十九笔）

执行 AI：GLM-5.3-Flash（主代理）

- **动机（用户指示）**：B2 已提交（cdef8f1）后，仓库外《QimengNAS/任务A-UI对齐.md》仍留有已消费的「§1 B2 续作」接手指引与 B2 限流期的「一次一个执行子代理」临时条款——前者易误导新会话重读已完成流程，后者与任务A 实际并发纪律不符（正确约束=**在跑子代理 ≤3**）。
- **改动**：任务A 文档 §0 执行顺序更新为「B2 已完成、可开任务B 通知已发过一次，当前 B3→B6」；§1 续作指引归档化（指向 cdef8f1 与 B2-round1.md）；§0 持续执行条款与 §3 P11 勘误为「在跑 ≤3」（保留被杀快照/冷却协议）。仓库侧 HANDOVER_APP M4-2A 进度块同步该清理与下一批指引（B3 万能筛选面板，B2 已铺 QimengTitleRow 单源与悬浮药丸底座）。
- **工作区核验**：被取消的 B3 执行子代理经查未在工作区留下任何改动（git status 干净 @ cdef8f1）；删除 B2 中断会话遗留的未跟踪临时目录 `.tmp/`（token 候选/截图等 15 个调试草稿文件，非验收材料——验收文本证据在 `%TEMP%\qimeng-m42a-evidence\` 与 `QimengNAS/m42a-review/`）。
- **注**：纯文档与工作区清理卷，无代码改动；B3~B6 由新会话按《任务A-UI对齐.md》§2 续作。

---
## feat(app): M4-2A-B2 相册四模式对齐——统计行/四维芯片/分组四模式/列数缩放/悬浮药丸（2026-09-07 第九十八笔）

执行 AI：GLM-5.3-Flash（执行子代理×4 轮，主会话 GLM-5.3-Flash 调度/验收/审查裁决）

- **动机（任务A-UI对齐.md 批 B2）**：相册页外部行为完全对齐旧版——统计行「N 文件」（P9-1，totalMatched）、四维芯片字样「分区/作品/角色/类型」（P9-2/3）、分组四模式（分区与类型=日期分组 DOMAIN_RULES §8；作品=authorNames→source→其他；角色=characters∪cosWork，P9-5；「N 项」两空格组头、其他恒沉底 P10）、TitleRow 换 ic_grid_2~5 图标+双指缩放（≥2 指 calculateZoom、结束持久化一次、clamp 2..5）、药丸区改悬浮 overlay（4dp elevation/屏高限高/内部滚动，不推挤网格）。
- **改动面**：`feature/all`（AllScreen 接线重写、AlbumViewModel +onDimChipClicked/pinch 列数、strings.xml 新建 all_title）、`core/model`（DateGrouping 分组四模式、FourDimPills 芯片/药丸纯函数、AlbumFilterState 维度字样、MediaAsset+cosWork）、`core/data`（SdkMappers 补 cosWork 映射，零协议改动）、`core/ui`（QimengMediaGrid 组头行、QimengPills 限高滚动变体、QimengGridPinchGesture 新建通用 Modifier、QimengTitleRow 新建并从 feature/all 收编单源、QimengIcons ic_grid_2~5、Dimens 补 IconDefaultSize、core/ui strings.xml 新建）。
- **审查与修复**：对抗审查（reviewer 子代理，七维）初审 0 P1/4 P2 全清偿——P2-1 characterGroupKey 改 characters→cosWork→其他 +source 空边界测试；P2-2 删除零计数药丸隐藏（引据不存在的「拍板条目 4」，实录「角色 (0)」为据，药丸可见性只由数据行决定）；P2-3 TitleRow 迁 :core:ui 单源。elem_compare 全量重跑再揪出两深层缺陷并修复：**P1 authorGroupKey 与真实载荷不符**（服务端对未匹配出处与 COS 资产的 source 都填字面「其他」非 null，实测 `/assets?includeCos=true` 证实；组键改 authorNames 首个→source→其他，测试改镜像真实载荷+混排回归）；**切维滚动位置保留**（实录切维后首组恒在视口顶，AllScreen 补 `LaunchedEffect(activeDim){scrollToItem(0)}`）。
- **验收工具校准**（`QimengNAS/ui-compare-harness/`，仓库外）：修复 elem_compare `do_tap` 空 regex 缺陷（B0 起 `("tap", 正则, "re:")` 写法下正则取自 mode 尾部为空串，点击落在首节点，**基线报告 S2/S3/S4 新版侧实为分区模式态**）；compare_scenes 签名按 P9 口径校准（统计行/芯片字样/组头两空格/列数 desc/S2S3 首组钉死单值+P10 豁免可见化），余红=筛选面板（B3）+已裁决口径差异（带可见 known_cause）+视觉裁片残差（B6 豁免清单收口）。
- **测试**：新增 15 条（DateGrouping 7：四模式/两空格格式/真实载荷混排+沉底/空边界/source 空角色边界；FourDimPills 5：维度字样/类型折叠文案/零计数照常显示/类型行降序；AlbumViewModel 3：clamp 越界+持久化一次/无步进不落盘/芯片切换）；既有代际防乱序 14 条全绿；四命令（app-build/app-test/app-lint/lint）全绿；grep `Color(0x` 触达面零命中。
- **记录**：旧 App 冷启重扫使对照数据 21→22 件（计数口径差异已消失，豁免文案留作历史注记）；点已激活维芯片=切换展开/折叠（GUIDE_UI.md:344/347 逐字核对为正确语义）；审查与校准全文存 `QimengNAS/m42a-review/B2-round1.md`。

---
## feat(app): M4-2A-B1 主题基座·旧版灰系换肤——深浅两套 24 token + Dimens.kt 建立（2026-09-06 第九十七笔）

执行 AI：GLM-5.3-Flash（执行子代理，主会话 GLM-5.3-Flash 派发与审查裁决）

- **动机（任务书拍板 P4）**：M4-2A UI 对齐批（派发任务书-20260906-UI对齐.md）首批——新版主题从 Web 品牌蓝系（#4250af）整体换为旧版中性极简灰系，深浅两套都换，唯一来源=旧仓库 `res/values/colors.xml` + `values-night/colors.xml`（P2「外部视觉完全复刻旧版」的组成基座）。
- **改动面**（仅 `core/ui/theme/` 三文件）：`Color.kt` 24 个 token（浅/夜 12 组）全量换值，逐 token 注释旧版 colors.xml 来源行号，另立 4 个带 alpha soft 常量（旧版底栏选中指示器/浸润底依赖）；`Theme.kt` M3 全槽位灰系重排（补 tertiary/secondaryContainer/inverse 族/surfaceContainer 族防 Material 默认紫粉漏出；error/scrim 留 M3 基线——旧版同样未定制，已注释声明）；新建 `Dimens.kt` 22 个尺寸常量（来源=旧 fragment_all_files.xml/styles.xml/drawable/组件现状，逐条注释文件+行号；旧仓库无 dimens.xml 已核实）。
- **验证**：grep `Color(0x` 全仓仅 Color.kt 自身命中（feature/core/app 零散落——任务书预估「约 20 处散落」经查为 token 文件自身字面量数，M4-0 起组件层从未有硬编码）；make app-build/app-test/app-lint + make lint 四条全绿；对抗审查 B1-round1（色值逐位转录核对 22 token、alpha 换算手算复核、Dimens 22 常量来源行号 51 处全命中、越界零触碰）0 P1/P2，报告存 `QimengNAS/m42a-review/B1-round1.md`。
- **注**：M4-2A 各批（B0~B6）的 HANDOVER_APP 批次表勾选按任务书 §6 统一在 B6 收口；本批表内进度以 CHANGELOG 为准。

---
## feat(app): 登录空密码走 dev-login 免密通道——测试环境免输密码（2026-09-06 第九十六笔）

执行 AI：GLM-5.3（主代理）

- **动机（用户拍板）**：密码输入在模拟器验证中反复阻塞任务测试（IME 逐字符输入+焦点漂移曾耗掉整段调试时间），拉长每批验收成本；用户指示项目密码暂时全关——服务端侧 `启动服务端.bat` 本就带 `QIMENG_AUTH_DEV_MODE=1`（Web 端已免密），本笔补齐 App 端通道。
- **行为**：登录页**密码留空提交 → 走 `POST /auth/dev-login`**（协议端点 2026-09-03 即有，零协议改动）；密码非空路径完全不变。服务端未开启 dev 模式时恒 404 → 新增 `LoginError.DevLoginUnavailable`，App 提示「该服务器未开启免密模式，请输入密码登录」——生产/远程部署（dev 模式关）零影响，SECURITY.md 开发模式节已补 App 侧条目。
- **改动面**：`:core:network` AuthApi 增 `devLogin()`（SDK `apiV1AuthDevLoginPost`）；`:core:data` AuthRepositoryImpl 登录编排空密码分支 + 404 映射（`HTTP_NOT_FOUND` 常量，协议侧改动须同步注释）；`:feature:login` 密码标签改「密码（开发模式服务器可留空）」+ 新错误文案资源。
- **测试**：AuthRepositoryImplTest 新增 2 条——`空密码_走dev-login免密登录成功且不触密码端点`（含路由命中断言：dev-login=1 且 /auth/login=0）、`空密码_dev模式未开启_404返回DevLoginUnavailable不落盘`；fake 传输层路由表补 `/api/v1/auth/dev-login` 通道（开关变量模拟 auth_dev_mode）。
- **验证**：模块单测全绿 → make app-test（BUILD SUCCESSFUL）→ make app-lint（BUILD SUCCESSFUL）→ 模拟器实测：pm clear 后仅输地址、密码留空、一键登录成功直进首页（隔离实例 18461 开 dev 模式，2026-09-06 晚实测）。
- **配套（同日非本 commit）**：对照环境 `QimengNAS/ui-compare-harness/` 隔离服务端同步开 dev 模式（server.sh），M4-2A/M4-3 两批任务书已记录免密口径。

---
## fix(app): 昨日(09-06)全量审查清偿·app 卷——筛选代际防乱序补齐同族四 VM+卫生项五处（2026-09-06 第九十五笔）

执行 AI：GLM-5.3-Flash（主代理）

- **审查背景**：接第九十四笔（web+文档卷），本笔清偿 app 卷——P1 一项 + 卫生项五处，均为 2026-09-06 全量四路审查的发现。
- **P1 筛选代际防乱序补齐（Album 范式四连）**：仿 AlbumViewModel 既有范式（代际号：筛选变化即递增，请求发起时快照、响应落地前校验；筛选重载绕过 isLoading 拦截，分页/下拉刷新防重保持原状）补齐同族四 VM——FavoriteViewModel（applyFilter 族）、HistoryViewModel（applyFilter）、SearchViewModel（submit/selectPartition/selectMediaType 三入口）、HomeViewModel（selectPeriod 排行榜周期切换：修复前在途日榜未归时切周期，重载会被 isLoading 拦截导致周榜请求根本不发出，迟到旧响应也无校验直接落地）。弱网下在途旧代响应（含失败）一律丢弃，不再覆盖新筛选态。
- **行为测试 14 条锁定**：Favorite 4 + History 4 + Search 3 + Home 3，时序构造与 AlbumViewModelTest 同款——仓库请求挂 CompletableDeferred 闸门由测试决定放行顺序；每页锁三条语义：旧代迟到响应不落地（loading 不被旧代收走）、旧代失败不污染新筛选态、分页/刷新防重不回归。修正：favorite 测试初稿引用了不存在的 FavoriteUiState.totalMatched（收藏页无总数展示面），改为 items/loading 状态面断言。
- **refreshFollowed 静默失败修整（P2）**：原实现 `runCatching.getOrDefault(emptyList())` 把读失败伪装成「没有关注」（清空既有列表）；改为失败保持原列表 + writeError 横幅（并入 P2-3 反馈家族，MineUiState KDoc 同步）；SettingsViewModelTest 补读失败路径锁定用例（失败保持原列表 → 恢复后正常落地）。
- **HomeTab 文案迁字符串资源**：HomeTab 枚举去 label（UI 文案不进状态层），feature/home 新增 strings.xml（home_ 前缀，login/settings 同款约定），HomeScreen 以 tabLabelRes 映射 + stringResource 取文案。注：core:model 枚举族（RankingPeriod/RecommendPreset/StatsRangeOption 等）的 label 中文常量是全库既定口径，本笔不动（存量口径记录备查，如需统一资源化另立批）。
- **「QimengCache」TAG 收敛**：TAG 常量从 CoilModule object 内上提文件级（改名 CACHE_LOG_TAG，CoilModule 与 RealCoilCacheManager 同文件共用），RealCoilCacheManager.clear() 两处裸字面量归零——验收证据协议 grep 'QimengCache' 不受影响。
- **注释型卫生两处**：①token 明文 DataStore 取舍注释（DataStoreServerConfigDataSource KDoc：SECURITY.md 威胁模型=纯内网单用户不覆盖物理取证场景 + androidx security-crypto 全量弃用（官方发布页口径，当场核实）+ 服务端登录即重铸/重置 token 可吊销兜底——风险接受决策落档，远期多用户/公网面时此处为改造点）；②上传 4xx/401 终局注释（AssetUploader 分类点：登录即重铸 token 下旧 token 重试必再 401、Worker 拿不到新凭据无法自愈，401 已由 AuthInterceptor 广播跳登录，重登后重新入队）。
- **验证（双门禁全绿）**：make app-test 全量 BUILD SUCCESSFUL（416 tasks，含 :core:model:test）；make app-lint BUILD SUCCESSFUL（1m06s）；受影响模块单测先行单跑逐个全绿。
- **顺手清理**：Windows 下 exe 被占用重建遗留的 `server/qimeng-server.exe~` 备份产物未入 .gitignore（`*.exe` 规则不覆盖 `~` 后缀）——补 `*.exe~` 规则并删除陈旧备份（09-05 23:25 旧构建，现役 exe 为 09-06 11:37）。

---
## fix(web): 昨日(09-06)全量审查清偿——web 页大小单源补净+Pill 模板全收拢 + 文档卷勘误补勾补账（2026-09-06 第九十四笔）

执行 AI：deepseek-v4-flash-vision-exp（主代理）

- **审查背景**：用户要求审查 2026-09-06 全部 33 笔提交（代码规范/功能一致/文档准确），四路对抗审查子代理并行（server+api / web / app / 文档）——发现 3×P1、4×P2 与若干 P3；本笔清偿 web+文档卷，app 卷见第九十五笔。
- **页大小单源补净（第九十三笔宣称「页大小单源」，grep 证伪）**：`use-assets.ts` `useRecommendations(limit = 60, …)` 默认参数与 `AlbumsPage.tsx` `limit: 60` 两处裸字面量残留（第九十三笔只删了 HomePage 的 HOME_PAGE_SIZE）——均改引用 `DEFAULT_PAGE_SIZE`（constants.ts 既有常量，与 pagination.go 互指注释在），hook 签名注释同步改写；第九十三笔 CHANGELOG 内文本就诚实写了「消除三重定义之一」，标题为夸大，本笔补齐后成立。
- **Pill 模板全部收拢**：`ui/pill` 组件增 `className` 变体参数（与 base 类拼接为 `pill <variant>[ active]`，注释明示禁止页面再手写模板）；SearchFilters 最后两处模板拼接（标签池 `pill pill-tag`、`+ 添加` `pill pill-add`）改用 Pill——第九十三笔「三处收拢」漏掉的第 4/5 处清零。
- **M4-2 笔数错引勘误（P1）**：HANDOVER_APP 批次总表 M4-2 行「CHANGELOG 第八十笔」→「第八十三笔」——第八十笔实为 web 目录树缩进修复 8ed4b97，M4-2=4acb128=第八十三笔（CHANGELOG 自书一致，HANDOVER_APP 出生即错，昨日文档审查未抓到）。
- **PROJECT_PLAN M4 补勾（P1）**：M4-5 上传（294a576）、M4-6 缓存策略（944cac5）两行补勾并注 commit；「登录+首页/列表/筛选/搜索」行补进度注（M4-1 88af1b9 + M4-2 4acb128 已交付）但**保持未勾**——「万能筛选面板」=M4-2.1（待拍板条目 11，M4-3 后独立批）未交付，整行勾选即失实，进度注替代之。
- **d26999e 漏记补账（P2）**：晨间收尾后记（26c2126 夹带删除致 CI 红窗归因）此前在 CHANGELOG 零记载——按第九十一笔对 09-05 三笔漏记的同款判罚口径补记（其内容已存 HANDOVER.md 后记，本笔只补账）。
- **三处笔误/失实勘误**：CHANGELOG 第八十七笔「10 个 feature」→「11 个 feature」（实测 feature 目录 11 个）；HANDOVER「CI 红约 2.5 小时」→「约 2 小时 15 分」（实测 26c2126 失败 01:36 → 874df6a 转绿 03:48）。
- **文头「最后更新」刷新**：HANDOVER/HANDOVER_APP/CAPABILITY_MAP 三份文头补记（CAPABILITY_MAP 系 b0ee832 漏跟，按「表头未动」自披露补记）。
- **验证**：`tsc --noEmit` 零错误 + `oxlint` 0 errors；grep 全库 `pill${` 模板拼接零残留、60 字面量仅剩具名常量。
- 审查其余低优先发现（记录备查，不构成本批返工）：web 圆角/字号/尺寸 token 化系统性落差（ADR-0008 与原型惯例的历史欠账）；prototype.css 1718 行超 500 警戒线（存量持续增肥）；动效 stagger delay 13 处硬编码与段头自述矛盾；app 冷启动 token 预热竞态窗口；server scanner 注入时钟与 time.Since 混用、trash 刷新 goroutine 不可取消。

---
## fix(web): 昨日审查清偿·web 卷——备份导入 stats 死键修复 + 查询键族收敛 + Pill 三处收拢/页大小单源（2026-09-06 第九十三笔）

执行 AI：GLM-5.3（主代理）

- **stats 死键修复（高优先，昨日 8344920 引入）**：use-backup 导入成功后失效 `['api/v1/stats']`，而 use-stats 实际键首段是完整路径 `'api/v1/stats/overview'`/`'api/v1/stats/trends'`——TanStack 逐元素匹配下前缀失效一条不命中，与 0a2f5d1 修掉的 SSE 键错配同类（"修完 A 留下 B"）。实际影响有界（全局 staleTime 20s 后自然重取）但意图失效静默落空。修法：query-keys 增 `STATS_QUERY_KEY`/`RANKINGS_QUERY_KEY`/`HISTORY_QUERY_KEY` 三根键，use-stats/use-history 子键一律 `[...根键, 子段]` 构造，use-backup 引用常量——stats 族键形态随之统一，根键命中全部子键。
- **query-keys 头注豁免声明修正**：原头注声称 stats/rankings/history"字面量仅出现一次"留在各自 hooks——审查证伪（'api/v1/rankings' 已 3 处、'api/v1/history' 2 处，违反"第 2 次出现必须提常量"），本批收敛后声明同步改写。
- **Pill 三处内联收拢**（b57076b 组件库化遗留）：SearchFilters 本地 FilterPill 组件删除、5 个调用点改用 `ui/pill`；SettingsPage 推荐预设按钮、AssetTagRow 标签选择按钮同收拢（含 AssetTagRow 顺带获得 type="button"）。
- **页大小单源**：HomePage 本地 `HOME_PAGE_SIZE = 60` 删除改引用 `DEFAULT_PAGE_SIZE`（constants.ts 既有常量，消除 60 三重定义之一）；useQmRefresh 注释"事件名一字不改"措辞更新为 QM_REFRESH_EVENT 常量单一来源口径（提常量后残留）。
- **验证**：`tsc -b` 零错误、`oxlint` 0 errors（17 warnings 为存量 router 等，与昨日交付时一致）；`make lint` TS 道全过。
- 审查未采纳返工项说明：LibraryManagePage.tsx 昨日末态 535 行超线无注释，今日 36af094 目录树操作化重构后已 496 行回线内，无需处理。
## fix(server): 昨日审查清偿·server 卷——assets.go 拆 fillList* 族回文件警戒线内 + 主 handler 超线补理由注释（2026-09-06 第九十二笔）

执行 AI：GLM-5.3（主代理）

- **昨日审查发现**：assets.go 被 09-05 三笔 API 功能（likedToday/cosWork/收藏历史筛选）连日推过 600 行文件警戒线（558→642→673）且无超线理由注释；GetApiV1Assets 101→118 行同样越过 100 行函数线无注释。
- **拆分**（纯函数搬移零行为变化）：fillListAuthorNames/fillListCosWork/fillListLikedToday 三函数族（列表条目批量字段装配，统一"页大小一次 IN 查询+二次装配"模式）整体搬到新文件 `internal/httpapi/assets_list_fill.go`（108 行，文件头注明职责与失败策略分级）；assets.go 673→582 行回线内，头注释补拆分去向。
- **超线注释**：GetApiV1Assets 补理由——oapi-codegen 生成接口签名 + 单请求直线流（参数归一→游标→SQL→装配→响应），无嵌套分支复杂度，拆段只会把一串局部状态提升为结构体在函数间传递。
- **验证**：`go build ./...` + `go test ./internal/httpapi/` 全绿 + `make lint` 四道（redocly/gofmt/golangci/oxlint）全过（昨日 8344920 漏跑 lint 致 CI 红的教训，本批补跑）。
## docs(docs): 昨日(09-05)全量审查清偿·文档卷——第五十六笔标题补回 + 三笔漏记勘误 + 两处笔误（2026-09-06 第九十一笔）

执行 AI：GLM-5.3（主代理；四路对抗审查 reviewer 子代理并行：api+server / web / app / 文档一致性）

- **审查背景**：用户要求审查 2026-09-05 全部 43 笔提交（代码规范/功能一致/文档准确）。四路结论：三端代码实质可接受（协议同步、推荐纯函数、§10 映射、模块单向依赖、声明-实现核验全部通过，tsc/lint/go test 独立复跑全绿）；两个高优先遗留已由今日提交清偿（openapi 备份导入 413 缺声明→b0ee832；legacyHistoryLimit 未接线致 CI lint 红→40862ea）。本笔清偿文档侧发现，代码侧清偿见第九十二/九十三笔。
- **第五十六笔标题补回**：29777c8 并发编辑把 85be719（详情页 B站式双栏排版）的条目标题行当替换锚误删，正文成无标题孤块且编号链表面断裂（五十七→…→五十五）——按 85be719 原 diff 补回标题并在孤块处加注。
- **三笔零记录勘误补记**（同日同类纯文档提交均有编号条目，此三笔口径不一漏记）：
  - f295e92（docs）：派发模式回调落档 HANDOVER.md——主会话后台子代理派发模式（08:50 定时自动化可 TaskStop 强制收停，手动会话定时停不了才改此模式）。
  - cf956e5（docs/server）：S-2 审查 P2 清偿——assets_detail.go assetStats 注释补齐当日点赞态第七项（纯注释改动）。
  - 1266af2（docs）：HANDOVER_UI 六任务批 E4 条目补记 TXT 导入卡去重（f039b98 交付时漏记，锚文本失配）。
- **记账既成事实说明**：f384b69（作者 TXT 匹配扩展名检查）的第五十八笔条目实由同秒并发提交 29777c8 夹带入库（f384b69 自身 diff 未动 CHANGELOG，与其 message 声明不符——并发竞态既成事实，无法追改历史 message，特此注明）。
- **两处笔误修正**：HANDOVER_UI §5.9 详情页 B站式节"第四十六笔"→"第五十六笔"（85be719 引入，第四十六笔实为 TXT 导入卡去重）；PROJECT_PLAN 门禁行"M4-0 起"日期 2026-09-04→2026-09-05（M4-0 实际 09-05 交付）。
- 审查其余低优先发现（不构成本批返工，记录备查）：9 笔裸 `docs:` 无 scope 前缀（历史 message 无法追改，后续新提交注意）；f384b69 大小写不敏感口径未落 DOMAIN_RULES 表行；413 分支(>64MB)无直接单测；export recordKey 第三级消歧跨库同 rel_path 场景无区分度且零测试（涉及行为语义，留待专项）；HANDOVER_UI 文头「最后更新」自第六十笔起未随正文刷新（欠账累积，待下次 UI 会话统稿一并更新）。
## docs(docs): 晨间收尾——夜2 双车道闭合落档（A 四批+尾批+双轮自审；M4-3 留今夜）（2026-09-06 第九十笔）

执行 AI：GLM-5.3-Flash（A 会话主代理·8:50 定时收尾）

- **A 车道夜2战果**：M4-1 登录 88af1b9 → M4-2 列表族+导航四化 4acb128 → M4-5 上传主通道 294a576（独立对抗审查 14 项全过）→ M4-6 缓存/设置/统计/我的 944cac5 → A-S1 build-logic 收敛 0ff017a → ci 补 :core:model:test 21813e3 → 自审返工 a105280。自审 R1 判 P2×3（相册筛选请求乱序覆盖/设置页写操作静默失败/CI 覆盖缺口）→ 返工批 → R2 全过；Android 模块 72 + :core:model 59 用例全绿，四件套全绿，294a576..a105280 已 push。
- **M4-3 详情页留 09-06 夜首发**（04:30 时间门未赶上四批齐）；派发口径=待拍板条目 10（ZoomImageView/BiliPlayerView 桥接、详情原图不降采样、规格书式标签管理、视频海报态+默认静音条目 8）。今夜链建议：M4-3 → M4-2.1 筛选面板（条目 11）→ M4-4（备忘录已备；dwell 先删后发口径=条目 13，待用户拍）。
- **备忘录三份落 QimengNAS**：A-S2 图标自持策略（官方停更 material-icons 库族，维持手绘 vector 按需补）/ A-S3 AGP9+compileSdk37 三步走升级路线（M4-7 后独立基建批）/ M4-4 离线队列前置验证（DDL/调度/失败语义/测试矩阵）。
- **新建**：`android/启动模拟器-headless.bat`（无窗口+禁音频一键启动）；8:50 晨间收尾定时自动化建档（automation-ebea7fc2）。
- **遗留**：加做项 a（core 单测补强）未做；M4-5 审查观察项 5 条已入 HANDOVER_APP §3 M4-7 验收清单；B 车道移交项已全部清零（其收工档 ec9c1a5）。
## fix(app): 自审返工——筛选请求代际防乱序/设置页写失败反馈/卫生清偿（含 M4-7 验收清单与 headless 启动脚本）（2026-09-06 第八十九笔）

执行 AI：GLM-5.3-Flash（A 车道·夜2 返工批执行子代理）

- **P2-1 相册页筛选请求乱序（AlbumViewModel）**：筛选变化递增代际号 `filterGeneration`，列表与四维候选请求发起时快照、响应落地前校验——旧代响应（含失败）一律丢弃，弱网下旧筛选响应不再覆盖新筛选态；筛选重载不再被 `isLoading` 拦截丢弃（在途的是旧代请求，其响应由代际校验兜底），分页/下拉刷新的防重语义保持原状。新增 `AlbumViewModelTest`（feature/all 首个 ViewModel 单测）锁行为：在途 A 未归时应用筛选 B → A 迟到响应被丢弃、最终为 B 结果；旧代失败不污染新筛选态；四维候选旧代整批不落地；分页在途防重+追加不丢页。配套删去 `onNearBottom` 里的重复 `Log.d("QimengApi", …)`（出网请求日志唯一源在 SdkMediaRepositories.logRequest；该行既重复又裸写魔法串，且 android.util.Log 未 mock 会阻断纯 JVM 单测）。
- **P2-3 设置页写操作静默失败（SettingsViewModel/SettingsScreen）**：`MineUiState` 增 `writeError` 反馈位 + `dismissWriteError()`，UI 顶部横幅展示（errorContainer 底，「知道了」点按消除）；applyPreset/unfollow/setCacheQuota 失败时给出中文提示（「保存失败，请重试」/「取关失败，请重试」），成功路径永不产生。失败回滚语义：均不乐观更新——取关失败列表保持原状、档位失败跟随 DataStore 原值、预设失败高亮保持原项且退出 applying。SettingsViewModelTest 补三条失败路径用例 + 成功不弹断言（测试替身加可编程错误注入）。
- **卫生 4 处**：QimengMediaGrid 重复 `import LaunchedEffect` 删一处；AllScreen/AlbumFilterStateTest 旧注释（声称含排序组——与 2026-09-06「相册=旧版全部页完全一致」拍板相反）修正；HomeScreen 顶行注释去「筛选图标」字样。
- **[v3] 调试日志前缀清偿**：SdkMediaRepositories `logRequest` 去掉 `[v3]` 前缀标记（logcat 证据标签 `QimengApi` 不变，grep 口径不受影响）。
- **M4-7 验收自查清单（HANDOVER_APP §3 M4-7 追加）**：对照 PROJECT_PLAN M4 验收标准逐条列检查项（完整日常使用/上传→Web 立即可见/离线不丢/门禁全绿/生成物不手改/新迁移只加文件/决策先写 ADR/GUIDE_UI 复刻抽查），并纳入 M4-5 审查观察项 5 条（content:// 授权持久化/dataSync FGS 6h 限时/分享路径通知权限/取消上传路径/100+ 文件多选）。
- **headless 启动脚本**：新增 `android/启动模拟器-headless.bat`（内容注释纯 ASCII——Windows cmd 代码页坑）：先 `adb devices` 提示检查（不强制阻断），再启动 `emulator -avd qimeng_api35 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot`（SDK 路径写死 `%LOCALAPPDATA%\Android\Sdk`）。
- **A-S2/A-S3 备忘录落账（仓库外文件，本笔只记账）**：`<本地工作区>\a-s2-icon-strategy-memo.md`（应用图标策略备忘）与 `<本地工作区>\a-s3-agp9-upgrade-memo.md`（AGP9 升级前置约束备忘）已落档。
## ci(app): android job 补 :core:model:test——纯 JVM 模块 testDebugUnitTest 覆盖不到（2026-09-06 第八十八笔）

执行 AI：GLM-5.3-Flash（A 车道·夜2 返工批执行子代理）

- CI android job 构建命令补 `:core:model:test`：:core:model 是纯 JVM 模块（`kotlin("jvm")`，qimeng.jvm.library convention），只有 `test` 任务，`testDebugUnitTest` 聚合覆盖不到——该模块的领域纯逻辑单测（筛选状态机/分组/分批）此前在 CI 从未执行。与本机 Makefile `app-test` 的同款补账对齐（0ff017a），只动 android job 一处。
## refactor(app): build-logic convention 插件收敛 17 模块重复配置 + Makefile app-test 补 :core:model:test（2026-09-06 第八十七笔）

执行 AI：GLM-5.3-Flash（A 车道·A-S1 批执行子代理）

- **build-logic included build（NIA 范式，范式与 API 用法当场核对 android/nowinandroid main 源码后落笔）**：新增 `android/build-logic`（settings 复用主工程 `gradle/libs.versions.toml` 为 libs 目录；`convention` 子工程 kotlin-dsl，AGP/KGP API 走 compileOnly，运行期版本仍由根 build.gradle.kts 的 apply false 收口——convention 不携带运行时版本）。抽 5 个 convention 插件：`qimeng.android.application`（application+Kotlin Android+公共 Android 面）、`qimeng.android.library`（library+Kotlin Android+公共 Android 面）、`qimeng.android.compose`（Kotlin Compose 编译器插件+buildFeatures.compose+Compose BOM platform）、`qimeng.android.hilt`（Hilt+KSP+hilt-android/hilt-compiler 依赖）、`qimeng.jvm.library`（kotlin("jvm")+Java/Kotlin 17）。公共面收口：compileSdk 36 / minSdk 26 / compileOptions 17 / jvmTarget 17（build-logic 内具名常量 ANDROID_COMPILE_SDK/ANDROID_MIN_SDK）；AGP 8.13.2/Kotlin 2.3.21/Compose BOM 2026.06.01 等版本零变更。
- **17 个模块 build.gradle.kts 迁移（零行为变更）**：:app（application+compose+hilt）、:core:model（jvm.library）、:core:network|data（library+hilt）、:core:ui（library+compose）、:core:testing（library）、11 个 feature（library+compose+hilt）。模块文件只留 namespace、targetSdk/versionName（:app）与依赖差异；重复的 android{} 公共块/kotlin{} jvmTarget/buildFeatures.compose/`platform(compose-bom)`/hilt 依赖对全部上收 convention。libs.versions.toml 增补：build-logic 编译期依赖 2 条（android-gradlePlugin/kotlin-gradlePlugin，版本锚定同 agp/kotlin）+ [plugins] 5 条 qimeng.* 别名（version "unspecified"，由 included build 提供）。无损失收敛注记：各模块既无 lint 配置也无 packaging 块（任务描述提及，实际不存在，无从保持）；依赖解析面不变——迁移后测试任务输入与迁移前命中同一构建缓存键（实测 UP-TO-DATE 直通），构成零行为变更的旁证。
- **实现注记（与 NIA main 的差异，均为 AGP 8.13.2 环境适配）**：①NIA main 对 `CommonExtension` 用裸类型（其 compileOnly 实为 AGP 9 线），本项目 AGP 8.13.2 的 CommonExtension 仍带 6 个类型参数，按 `CommonExtension<*, *, *, *, *, *>` 星投影书写；②compose convention 内按泛型基类查扩展运行期匹配不到 AGP 注册的具体扩展，改为 ApplicationExtension→LibraryExtension 逐级 Class 回退（源自我实测报错，非凭记忆）。
- **Makefile app-test 补账（一行）**：`:core:model` 是纯 JVM kotlin("jvm") 模块，测试任务为 `test`（无 testDebugUnitTest 变体），原 `app-test` 只跑 testDebugUnitTest 覆盖不到它——追加 `:core:model:test` 并注释原因。
- **自测（全绿）**：`make app-build` BUILD SUCCESSFUL（561 tasks）；`make app-test` BUILD SUCCESSFUL，删缓存强制真实重跑核数：Android 模块 72 条 + :core:model 59 条，0 失败 0 错误（:core:model 用例数 59 为现场实测，此前口头口径 32 已过时——M4-5/M4-6 批次各追加过测试类）；`make app-lint` BUILD SUCCESSFUL（1m11s）；`make lint` exit 0（redocly/gofmt/golangci-lint/web build+lint 四段全过）。
- 改动文件：android/build-logic/**（新增：settings.gradle.kts、convention/build.gradle.kts、convention/src/main/kotlin/media/qimeng/buildlogic/ 下 5 插件+KotlinAndroid.kt+AndroidCompose.kt+ProjectExtensions.kt）；android/settings.gradle.kts（pluginManagement.includeBuild）；android/gradle/libs.versions.toml（+7 条目）；17 个模块 build.gradle.kts；Makefile（仅 app-test 一处）；文档：HANDOVER_APP.md（§1 构建接线行）、CHANGELOG.md（本条）。

---
## feat(app): M4-6 缓存策略与设置/统计/我的页——Coil 磁盘缓存 LRU 档位/趋势线/关注列表/推荐偏好（2026-09-06 第八十六笔）

执行 AI：GLM-5.3-Flash（A 车道·M4-6 批执行子代理）

- **Coil 磁盘缓存 LRU（C5）**：档位 512MB/1GB/2GB/5GB（默认 1GB，`DiskCacheQuota` 具名枚举带字节值），DataStore 持久化（client_prefs 文件 `disk_cache_quota_mb` int 键）+ 清空按钮（清后容量归零核对）。**档位变更重启生效**：Coil 官方源码明言「同一目录多个 DiskCache 实例并发会损坏缓存」（ImageLoader.Builder.diskCache 注释）且 SingletonImageLoader.setSafe 语义为不可覆盖已创建实例，运行中重建不做，UI 同步注明「重启应用后生效」。ImageLoader 全局单例装配由 :app 迁至 :core:data `CoilModule`（Hilt @Provides；位置偏离任务书初拟 :core:network——档位仓库在 :core:data 而 data→network 单向依赖冻结，network 无法反向依赖，模块边界红线优先，已入交付报告）；GIF 解码分档（28+ AnimatedImageDecoder/26-27 GifDecoder）、内存缓存 25% 堆、动图直链进磁盘缓存（网络缓存不损动画语义）、视频不落盘（播放走 Media3 流式、上传走 WorkManager——全 App 无「Coil 加载视频」路径，由架构保证）。清空/容量经 `CoilCacheManager` 端口（clear 前后 size 打 logcat `QimengCache` 供核对）。
- **统计页（C1/C2/C3）**：数字卡六指标静态（/stats/overview 无 range 参数，不随档联动）；时段四档 7天/30天/90天/全部（`StatsRangeOption`→range 映射单点收口 ：core:model，**day 陷阱档**=近 30 天逐日非单日，DOMAIN_RULES §5，专门单测防改回）；趋势卡 Compose Canvas 自绘（渐变面积+折线+数据点+稀疏 X 轴标签+空态「暂无趋势数据」；点击数据点气泡与分类型详情页维持 C3 砍除）；快速切换防覆盖=序号防重（GUIDE_UI §数据统计页：响应带发起档快照，回写前校验选中档未变）。
- **我的页（C4/C6）**：我的 Tab 单页列表——服务器地址展示卡（改地址=退出重登语义）+ 关注列表（`GET /authors` 无 follow 参数→客户端 `filterFollowed` 纯函数过滤，取关 `PUT /authors/{id}/follow` toggle 后行消失）+ 推荐偏好 BottomSheet 四预设（均衡推荐/高记忆流行/深度探索/新鲜优先，9 维值**逐字**取 DOMAIN_RULES §1.3 预设表，`matchPreset` 当前项高亮、自定义值不高亮任何行）+ 收藏/浏览历史/作者管理既有入口 + **上传入口**（M4-5 遗留：上传页此前只能经分享流进入）+ 缓存区（档位+清空）+ 版本信息（`GET /system/status` version 字段，C6）+ 退出登录。
- **PUT /recommendations/prefs 绕行说明（生成物缺陷）**：make sdk 生成物的 BigDecimalAdapter 将 number 序列化为 JSON 字符串（`"0.1"` 带引号），服务端 Go `*float32` 拒收 400——GET 走生成 SDK 正常；PUT 改 Repo 层注入 OkHttpClient 直发数字字面量（org.json 平台内置零新依赖；Bearer 仍走全局 AuthInterceptor 单点；路径常量注释「协议侧改动须同步此处」+绕行原因）。根治需 openapi schema 9 字段增 `format: double` 后 make sdk（动 api/ 协议面，超本批边界，已报告待拍板）。另修 putPrefs 静默吞错→失败打 logcat（证据协议）。
- **测试（新增 25 条单测全绿）**：core:model StatsRangeTest 3（四档映射/day 陷阱/UI 文案）、RecommendPresetsTest 6（四预设逐维对表+命中/自定义不命中+文案顺序+均衡档权重合计 0.95）、FollowedAuthorsTest 3（过滤保序/空集/全未关注）、DiskCacheQuotaTest 4（字节换算/默认档/MB 往返/非法回落）；feature:stats StatsViewModelTest 5（init 双加载/C1 映射集成 7d-day-90d-all/同档不重拉/**晚完成旧档响应不覆盖**/总览失败置空）；feature:settings SettingsViewModelTest 7（logout 会话闭环/关注过滤/取关行消失/预设应用 9 维载荷+高亮/档位持久化/清空归零/版本与空态）。SettingsViewModel 注入 @IoDispatcher（可测性，Dispatchers.IO 收口 DI）。
- **自测（全绿）**：`make app-build`、`make app-test`（全模块 testDebugUnitTest）、`make app-lint`、`make lint` 全部 BUILD SUCCESSFUL/exit 0。模拟器（headless qimeng_api35 文本证据，存 %TEMP%\qimeng-m46\evidence\；隔离实例 ：18460=真库快照 db+thumbs+media-secret，8420 真库零写入）：a) 统计页数字与 curl 对照——App dump `6,325/5,911/414/189.57GB/1/880` vs `GET /stats/overview` JSON `totalFiles:6325,imageCount:5911,videoCount:414,totalSizeBytes:203552629218(=189.57GB),todayViews:1,totalViews:880` 全等；b) 四档依次点击 logcat `GET /stats/trends range=7d→day→90d→all`（13_logcat_range_switch.log）；c) 关注列表显示 curl 预置的 AcidRaiN/AlenAbyss 两行→取关→logcat `PUT /authors/acidrain/follow followed=false`→仅剩 AlenAbyss；d) BottomSheet 四预设（服务端值命中时「当前」高亮）→点高记忆流行→logcat `PUT /recommendations/prefs tagRel=0.15 tagColl=0.1 engage=0.2 recency=0.25 like=0.1 discov=0.05 fresh=0.05 depth=0.05 random=0.1`（DOMAIN_RULES §1.3 该行逐字）→curl 回读 9 值一致→重启后行内仍显示「高记忆流行」；e) 档位点 2GB→DataStore 文件 `disk_cache_quota_mb` varint=2048（run-as 提取 od 佐证）→杀进程重启→`QimengCache: DiskCache 装配 maxSizeBytes=2147483648`（默认 1GB=1073741824 的日志在先，对照成立）→清空按钮→logcat `clear() 之前 size=746968 → 之后 size=0`+UI「已用 0B」；f) 「版本信息·服务端 0.1.0」与 curl `/system/status` version=0.1.0 一致；「上传文件」行进入上传页（目标库/目录树渲染在案）。期间 App token 两次被验证用 curl login 吊销（服务端单 token 策略），App 正确 401→回登录页（M4-1 会话闭环顺带复验）。
- 改动文件：android/core/model/{StatsRange.kt、RecommendPresets.kt、FollowedAuthors.kt、DiskCacheQuota.kt、StatsModels.kt(均新增)+4 个对应测试(新增)}；android/core/data/{build.gradle.kts(+coil-core/coil-gif)、repository/StatsRepositories.kt(新增 4 接口)、repository/SdkStatsRepositories.kt(新增)、di/CoilModule.kt(新增 ImageLoader 装配+RealCoilCacheManager)、di/DataModule.kt(+5 绑定+IoDispatcher)}；android/core/ui/component/QimengFormat.kt(新增 formatBytesHumanReadable 共享)；android/app/{QimengApplication.kt(改走注入单例)、navigation/QimengNavHost.kt(+onOpenUpload)}；android/feature/stats/{build.gradle.kts(+core:data/lifecycle)、StatsViewModel.kt(新增)、StatsScreen.kt(重写)、test/StatsViewModelTest.kt(新增)}；android/feature/settings/{SettingsViewModel.kt(重写)、SettingsScreen.kt(重写)、test/SettingsViewModelTest.kt(重写+6 新用例)}；文档：HANDOVER_APP.md（批次表勾选）、CHANGELOG.md（本条）。

---
## feat(app): M4-5 上传主通道——系统分享接收/SAF 多选/选库选目录/WorkManager 串行队列+前台通知（2026-09-06 第八十五笔）

执行 AI：GLM-5.3-Flash（A 车道·M4-5 批执行子代理）

- **入口两路（HANDOVER_APP §3 M4-5）**：①系统分享接收——MainActivity 增 SEND/SEND_MULTIPLE intent-filter（image/*+video/*，launchMode=singleTask 保冷/热两条路汇入 onNewIntent），流 URI 经 MainViewModel.pendingShareUris 暂存、壳层导航观察后进上传流（选库/选目录页，MIME 白名单不在 App 侧复制——服务端四道校验唯一口径）；②App 内 SAF `ACTION_OPEN_DOCUMENT` 多选（OpenMultipleDocuments，image/*+video/*）。
- **目标目录**：GET /dirs（libraryId 必填）目录树递归渲染（展开/收起/点选，根=空串协议口径）+ POST /dirs 幂等新建（对话框输名 → UploadRules.joinDirPath 纯函数拼路径，客户端名单段卫生校验、服务端 NormalizeRelPath 仍唯一权威）。
- **队列（冻结架构落地）**：WorkManager 2.11.2 串行 unique 链（`beginUniqueWork`+APPEND_OR_REPLACE，官方语义并发=1，避免服务端扫描压力）+ @HiltWorker CoroutineWorker（androidx.hilt:hilt-work 1.3.0 同族接线，Application 实现 Configuration.Provider 注入 HiltWorkerFactory，manifest 移除默认初始化器）+ OkHttp 流式 `application/octet-stream` 直传 content://（不拷缓存；SDK 上传方法只收 java.io.File 故按冻结口径走注入的 OkHttpClient，路径/参数注释与 openapi.yaml 双同步）；前台 dataSync 通知进度（API 34+ 类型声明+FOREGROUND_SERVICE_DATA_SYNC，setForeground 失败降级后台续跑）+ 完成/失败通知（POST_NOTIFICATIONS 33+ 运行时申请，拒绝只影响可见性）。断网/中断恢复走官方 retry 语义：IOException → Result.retry() 指数退避（MIN_BACKOFF_MILLIS 起，上限 MAX_RETRIES=3 次后终局失败）；不加 CONNECTED 约束——依赖系统 VALIDATED 判定会让任务在隔离网段悬置（2026-09-06 模拟器实测），retry 路径覆盖同一恢复语义。
- **口径（冻结）**：大小上限读 GET /api/v1/config 的 upload.maxBytesMb（入队时现取现判，实时生效口径），超限本地拦截中文文案（「以下文件超过服务端上限 64 MB，已停止上传：m45_big.jpg」）不出网；类型白名单不复制，服务端 4xx Error{code,message} 文案透传展示；服务端冲突自动重命名——UI/完成通知展示最终文件名（m45_t1 (2).jpg 实测）。
- **测试（新增 34 条单测全绿）**：core:model UploadModelsTest 9 条（超限判定边界/目录名合法性/路径拼装）；core:data UploadWorkSpecTest 9 条（入队载荷往返/缺失键兜底/tag 反查/失败重试状态机——成功携带最终名、4xx 终局透传、未耗尽 retry、耗尽转 failure/进度钳制）+ UploadApiBodiesTest 5 条（4xx 文案透传与非 JSON 兜底/最终名解析）；feature:upload UploadViewModelTest 11 条（拦截文案生成且不入网/部分超限放行其余/批量入队保序/去重/新建目录非法名不发起/切库重载/队列透传）。WorkManager 官方 work-testing 组件经评估未引入：TestListenableWorkerBuilder 需真机 Context，纯 JVM（禁 Robolectric，白名单外）不可用，串行/进度/重试的运行时行为由模拟器文本证据覆盖。
- **自测（全绿）**：`make app-build`、`make app-test`（全模块 testDebugUnitTest，含 34 条新用例）、`make app-lint`、`make lint` 全部 BUILD SUCCESSFUL/exit 0。模拟器（headless qimeng_api35，adb 全程文本证据，证据存 %TEMP%\qimeng-m45-evidence\）：上传打隔离实例（:18420 临时端口+临时数据目录注册 M45-TestLib 测试库，HEAD 归档构建，8420 真库零接触）——a) 分享 t1 + SAF 多选 t2/big 三文件入队→串行完成→curl 隔离实例 GET /assets?directory=m45-test 立即见 2 新资产、GET /dirs 见 m45-test 节点（「Web 端立即可见」的 App 端替代口径：App 不走 SSE，SSE 失效键历史问题服务端 0a2f5d1 已修）；b) `am start -a android.intent.action.SEND --eu android.intent.extra.STREAM content://media/...` 分享单图→上传页预填→上传成功；c) config 调 64MB 后传 65MB 文件→拦截文案 dump 在案且 logcat 零 enqueue/零 worker start（未出网）；d) config autoAccept=false→上传 403→UI 队列与完成通知均展示透传文案「UPLOAD_DISABLED 上传已被关闭（设置页自动接收上传开关）」（logcat `rejected` 行）；e) `svc wifi disable; svc data disable` 后入队→logcat `start attempt=1`+`retry scheduled reason=网络异常`→恢复网络→10s 退避后 `start attempt=2` 成功，最终文件名 m45_t1 (2).jpg（冲突自动重命名顺带实证）；f) 串行时间线 logcat：08.758 start t1→08.818 success t1→08.862 start t2（t1 成功后才起 t2）。通知权限运行时弹窗实测 Allow；dumpsys notification 见 qm_upload 渠道「上传完成：m45_t2.jpg」等完成通知。
- 依赖（白名单内 ADR-0014 WorkManager，版本当场锁官方来源）：androidx.work work-runtime-ktx/work-testing 2.11.2（release 页 2026-08-11 更新，stable 最新；2.11.2 含 2.11.0/2.11.1 网络约束误判修复，https://developer.android.com/jetpack/androidx/releases/work）；work-testing 本批评估后未引入（见测试节），toml 已登记供 M4-4 复用。androidx.hilt:hilt-work/hilt-compiler 1.3.0（既有锁版）。
- 改动文件：android/gradle/libs.versions.toml；android/core/model/UploadModels.kt 与其单测；android/core/data/{build.gradle.kts、src/main/AndroidManifest.xml(新增)、src/main/res/drawable/qm_ic_stat_upload.xml(新增)、repository/UploadRepository.kt+SdkUploadRepository.kt(新增)、upload/UploadOutcome.kt+UploadWorkSpec.kt+AssetUploader.kt+UploadWorker.kt+UploadNotifications.kt(新增)、di/DataModule.kt(补绑定)、upload 两条单测(新增)}；android/core/testing/FakeUploadRepository.kt(新增)；android/feature/upload/{build.gradle.kts、UploadViewModel.kt(新增)、UploadScreen.kt(重写)、UploadViewModelTest.kt(新增)}；android/app/{build.gradle.kts、AndroidManifest.xml、MainActivity.kt、session/MainViewModel.kt、navigation/QimengNavHost.kt、QimengApplication.kt}；文档：HANDOVER_APP.md（批次表勾选）、CHANGELOG.md（本条）。

---
## fix(server): 指标包装器补 ReaderFrom 委托与回收站刷新超时（埋点批P3清偿）（2026-09-06 第八十四笔）

执行 AI：GLM-5.3-Flash（B 会话·埋点 P3 清偿批）

- **sendfile 优化恢复（埋点批 reviewer P3-2）**：statusRecorder（metrics_middleware.go）与 countingResponseWriter（media.go）各自实现 `io.ReaderFrom.ReadFrom`——底层 ResponseWriter 实现 io.ReaderFrom（生产环境 net/http 的 response，sendfile 零拷贝）则委托其 ReadFrom，否则 io.Copy 兜底；此前两个 wrapper 只有 Write，`http.ServeContent` 内部 io.CopyN 检测不到 ReaderFrom，大文件直链退化成用户态缓冲拷贝。countingResponseWriter 的 ReadFrom 两条路径都同步累计实发字节（media_bytes_total 口径不变），statusRecorder 在 ReadFrom 路径补隐式 200（状态码捕获不丢）。
- **trash 指标刷新限时（reviewer P3-4）**：refreshTrashMetrics 刷新体（WalkDir 遍历+逐条 Stat）挪进 goroutine，外层最多同步等待 `trashMetricsRefreshTimeout = 3 * time.Second`，超时放弃等待（后台算完自行 Set，gauge 推送语义晚到无害）。选型说明：listTrash 不接受 context，改签名会波及其全部调用点，取侵入最小的自我约束；删除入站/恢复/单条物理删除/清空四处挂点与同步刷新语义（正常耗时内响应返回时 gauge 已刷新）不变。
- **测试**：新增 media_test.go（countingResponseWriter 的 ReadFrom 委托路径计数精确 + io.Copy 兜底路径计数口径，自建 rfRecorder fake——httptest.ResponseRecorder 无 ReadFrom 方法，go doc 实测无法用于验证委托）；metrics_middleware_test.go 新增 TestMetricsMiddleware_readFromDelegated（手动设 req.Pattern 复现 ServeMux 匹配后时序，断言委托发生+状态码捕获），SSE 用例补反向断言（/api/v1/events 开流后 registry 无任何 http_requests_total/duration 序列，reviewer P3-3）。
- **文档**：OBSERVABILITY.md 口径注记 3「http 指标不覆盖」清单由两类扩为三类，补「方法不匹配的 405（同为 ServeMux 兜底，不经包装器）」（reviewer P3-5）；metrics_middleware.go 尾注同步（该文件头注明与 OBSERVABILITY 注记两侧同步修改）。
- **自测（全绿）**：`cd server && go build ./...` 过；`go test ./... -count=1` 全部 ok（httpapi 含新用例 TestCountingResponseWriter_readFromDelegated/readFromFallback、TestMetricsMiddleware_readFromDelegated 全 PASS）；`make lint` exit 0（golangci 0 issues；redocly/web 存量 warning 不属本批）。隔离实例复验（:18428，全新临时数据目录+dev 模式，测完收进程）：注册库→扫描入库 5242880 字节 jpg→拉一次原件 200 下载 5242880 字节，/metrics `media_bytes_total{kind="orig"} 5.24288e+06` 与文件大小精确相等（ReadFrom 委托路径计数未丢）；删除资产后 trash_items=1/trash_bytes=5.24288e+06 立即刷新（超时 goroutine 化未破坏推送语义）；发一次 405 后 http_requests_total 序列中无 code="405"（注记 3 口径与行为一致）。
- 改动文件：server/internal/httpapi/{metrics_middleware.go,media.go,trash.go,metrics_middleware_test.go,media_test.go}；文档：OBSERVABILITY.md、CHANGELOG.md（本条）。

---
## feat(app): M4-2 列表族与导航四化——推荐流/相册四维胶囊/收藏/历史/搜索/作者+四 Tab（2026-09-06 第八十三笔）

执行 AI：GLM-5.3-Flash（A 会话·M4-2 批执行子代理）

- **导航四化（2026-09-05 夜拍板落地）**：底部 5 Tab→4 Tab——TopLevelDestination 删 ALBUM 枚举项、「全部」Tab 更名「相册」（route `all` 不变，图标语义换 AlbumIcon）；`feature:album` 模块从 settings.gradle.kts includes、:app 依赖与目录三处删净；TopLevelDestinationTest 改四 Tab 断言（含「相册沿用 route all」锁定）。
- **六页实现（UI→ViewModel→Repository 接口→SDK，ADR-0014 分层）**：①首页=推荐（`GET /recommendations?limit=200` 一次拉满、本地 20 条分批揭示、批次尽换 seed 追加去重，`RecommendPaging` 纯函数单测锁定）+ COS（拍板 A3：`GET /assets?cosOnly=1` 独立入口）+ 排行榜（缺省显式传 `period=day`，日/周/月/年四 chip）三 tab HorizontalPager 横滑切换、tab 缓存独立、下拉刷新=推荐换 seed 重排；②相册页（route all）=四维胶囊筛选（分区 全部=显式 includeCos=1/常规=不传/COS=cosOnly=1；作者行=出处分组∪COS 作者、角色行=character∪work 按 kind 分派 source/authorId/character/work；切分区清作者/角色两行；「其他」桶前端恒置底）+ `GET /assets/facets` 四请求各缺自身参数（partition 恒显式传）+ 日期分组（dateLabel 照 DOMAIN_RULES §8：今天/昨天/周X/yyyy-MM-dd/未知日期）+ cursor 分页 + 下拉刷新；③收藏=`favorite=true&sort=favoriteAt&order=desc` + 四维同相册口径（facets 加 favorite=1）+ 空态文案区分；④历史=`GET /history` cursor 分页 + 分区/角色·作品/类型三维（作者行无协议参数支撑——/history 无 source/authorId，见遗留）+ 按浏览时间分组 + 无清空按钮（拍板 2B）；⑤搜索=三层状态（空态推荐搜索+搜索历史 DataStore ≤20 条去重最新在前含清除/补全从短到长+五维右侧类型徽标防抖 300ms/结果分区胶囊全部=显式 includeCos=1+类型档+日期分组分页）；⑥作者管理=`GET /authors` 全量 + 体系胶囊/名字搜索/排序三项（默认=API 原序、浏览数降序、文件数降序）全客户端 + 关注 toggle `PUT /authors/{authorId}/follow`（乐观翻转失败回滚）。覆盖页（搜索/收藏/历史/作者）入栈隐藏底栏返回恢复；双击 Tab 400ms 窗口回顶（壳层判定 + TabScrollController 广播，列表页精确 scrollToItem(0)）。
- **规格订正落档（2026-09-06 用户三处拍板）**：①相册页=旧版「全部」页只改名，**不引入 Web 相册页排序组（精选/最新/最旧/按名称）**，排序固定协议缺省 default 降序（AlbumSort 已删、单测改锁缺省排序）；②首页**不做筛选入口**（待拍板条目 5 关闭）——TopBar 筛选图标与 mediaType 弹层已删（mediaType 协议参数面保留，各列表页「类型」维照常用）；③动图缩略图**必须动画**（条目 9）：引入 Coil 3（白名单 ADR-0014），animated_image 网格项经 `AssetOrigUrlResolver`（详情接口取签名原件直链 + LRU 内存缓存 500 条）用**原件直链**由 coil-gif 动画解码渲染，image 类型照旧静态缩略图；QimengApplication 按官方 SingletonImageLoader.Factory 装配（API 28+ 用 AnimatedImageDecoder、26/27 回退 GifDecoder，内存缓存 25% 堆；磁盘缓存不做留 M4-6 C5）。**Coil 版本锁定 3.4.0**（Maven Central coil3 组核查：3.6.2 minCompileSdk=37、3.5.0 Kotlin 2.4.0 @Metadata 超 Hilt 2.58 上限 2.3.0，均不可用；3.4.0=minCompileSdk 35 + Kotlin 2.3.10 双兼容）——依赖 `io.coil-kt.coil3:coil-core/coil-compose/coil-gif/coil-network-okhttp:3.4.0`，来源 https://repo1.maven.org/maven2/io/coil-kt/coil3/ + https://coil-kt.github.io/coil/，版本收口 libs.versions.toml。
- **架构**：:core:model 落纯领域模型与纯逻辑（AlbumFilterState 四维状态机/FourDimPills 药丸装配/DateGrouping/RecommendPaging/AuthorRows/Suggestion 排序，JVM 单测 32 例）；:core:data 落 MediaRepository/HistoryRepository/AuthorRepository/AssetOrigUrlResolver/SearchHistoryRepository/GridPrefsRepository（SDK 映射、IO 调度、请求日志=logcat 证据协议）；:core:ui 落共享组件（药丸容器/胶囊行/四维筛选区/媒体网格/QimengThumbnail/顶栏/空态/PullToRefresh/回顶总线）；:app 壳接覆盖页路由；ServerConfigDataSource 增 currentServerUrl()（业务 API 工厂用，ADR-0015 单点不变）。
- **验收（全绿）**：`make app-build` BUILD SUCCESSFUL、`make app-test` BUILD SUCCESSFUL（全仓 68 例 0 失败，含 :core:model 新增 32 例：筛选状态机参数映射/切分区清下级/其他桶置底/四维装配/推荐分批/dateLabel/作者行与建议排序）、`make app-lint` BUILD SUCCESSFUL、`make lint` exit 0。模拟器（emulator-5554，headless）真库文本证据（%TEMP%\qimeng-m42-evidence\，uiautomator dump+logcat，禁截图）：①底栏四 Tab 首页/相册/数据/我的；②相册页四维胶囊（分区(2)/作者(61)/角色·作品(282)/类型(3)，全部(6325)/常规(767)/COS(5558)）点 COS 胶囊后 `共 5558 项` 收敛 + logcat `GET /assets cosOnly=true`；③收藏页请求 `favorite=true sort=FAVORITE_AT order=DESC`（真库无收藏→「还没有收藏」空态）；④历史页每资产一条+「今天」分组+无清空按钮（logcat `GET /history cursor=false`）；⑤搜索补全输入 ko 出 8+ 行（COS作者/作者/COS作品徽标、从短到长）→点建议出结果网格（`q=Koelet3D includeCos=true`）；⑥首页下拉刷新 logcat `seed=1→seed=2`；⑦相册页滚动分页 logcat 70 条 `GET /assets cursor=eyJ...` 游标逐页演进（VM 侧 album loadMore 同步留痕）；⑧双击相册 Tab logcat `QimengM42: scroll-to-top route=all`；⑨作者页（体系胶囊/搜索/排序三项/关注按钮）+ `PUT /authors/alenabyss/follow followed=true` 按钮态翻转；⑩动图链路：库内 animated_image 189 项、详情接口返回 origUrl、App logcat 出现 `GET /assets/{id} resolve origUrl`（动画本身为视觉形态，文本证据到解析与解码器装配为止）。隔离实例说明：并行 B 会话在 8420 上以秒级频率重铸单用户 token（App 恒被 401 踢回登录），M4-2 证据采集改用**真库数据快照隔离实例**（:18420，qimeng.db 经 sqlite backup API 一致性快照+thumbs 拷贝，只读真库不触运行库），登录/数据形态与 8420 同源。
- **遗留**：①相册页「筛选图标+万能标签筛选面板」未做（规格书 §全部页 有筛选图标；万能筛选面板涉及 tagIds/tagMode，属增量功能，建议随 M4-3 详情页标签管理一并做）；②历史页作者维筛选无协议参数（/history 无 source/authorId——需协议扩展再补，页面已留三维）；③资产卡片点击详情为 M4-3 交界（onClick 已预留未接线）；④首页/相册列数切换用「n列」文字按钮（旧版列数图标矢量的零依赖等价表达）；⑤动图「滚动停止动画迭代」依赖 Coil 生命周期与组合回收（LazyGrid 出屏即停），未做额外逐帧节流（真库 189 项动图滚动实测无肉眼可感卡顿；如后续掉帧数据出现再议优化）。

---
## docs(api): /import/qimeng-backup 补 413 声明+directory 描述补全；能力地图三处过期缺口清偿（2026-09-06 第八十二笔）

执行 AI：GLM-5.3-Flash（B 会话·协议补全批）

- **openapi 补 413（第七十八笔记账的协议缺口）**：POST /import/qimeng-backup responses 增 "413" 条目（内联 content $ref schemas/Error，仿 /assets/upload 403 既有内联写法），description「请求体超 64MB 上限（备份导入 per-route 放宽值）」——行为早已存在（0977190 引入，import.go legacyImportMaxBody=64<<20 + errors.go decodeJSONWithLimit 超限 413 TOO_LARGE），本笔只补声明零行为变化；400 校验失败已声明核对无需补，500 全仓无声明惯例不补。GUIDE_API.md 40 行已有「64MB/超限 413 TOO_LARGE」说明核对一致不动。
- **directory 参数描述补全（B-4 reviewer P3）**：GET /assets 的 directory 参数 description 末尾补「`a/..` 这类规范化后收敛为库根的相对路径同样拒绝（400）」——实读 filing/path.go 证实「./、a/.. 等 Clean 后收敛为库根本身」确在拒绝清单，非发明。
- **CAPABILITY_MAP 三处过期缺口清偿（第七十八笔记账「待权限方修」，经用户授权本笔修）**：①媒体库行「相册视图」缺口→现状列（AlbumsPage 四维筛选，分区维承接 COS 能力，0d4c9df）；②索引与检索行「检索高亮/联想建议未做」→联想建议移现状列（GET /search/suggestions + web 顶栏补全 eda96cf），缺口余「检索高亮未做」；③推荐行「推荐偏好设置页 UI（M3 剩余 1 项）」→现状列（9 维滑杆+4 预设，2026-09-03 接真），缺口置「—」。只动三行，表头「最后更新」行未动（任务约束逐字最小修改）。
- **自测**：`make sdk` 全链通过（redocly validate "spec OK" → go → ts → kotlin，生成物 git 忽略不入库）；`make lint` exit 0 全绿（golangci 0 issues；web 存量 17 warning 与 redocly 存量 112 warning 均不属本批，无 warning 指向本笔两处改动）；`cd server && go build ./...` 过。openapi diff 仅 /import/qimeng-backup responses 与 /assets directory description 两处。
- 改动文件：api/openapi.yaml；文档：CAPABILITY_MAP.md（三行）、CHANGELOG.md（本条）。
## feat(server): 9 族指标埋点接线——http 中间件/媒体字节/SSE 连接/扫描/库文件/缩略图队列/回收站（2026-09-06 第八十一笔）

执行 AI：GLM-5.3-Flash（B 会话·指标埋点清偿批，拍板来源=主会话接线口径定稿）

- **问题与定性**：docs/OBSERVABILITY.md 定义的 9 族业务指标在 server/internal/sysmon/metrics.go 已注册，但除 upload 族（M2）外全仓零埋点调用，/metrics 恒零值（第七十九笔遗留移交之"指标埋点缺口"）。本笔全量清偿：9 族全部接线，/metrics 出实数。
- **接线落位**：①http_requests_total/http_request_duration_seconds 新增 httpapi/metrics_middleware.go（statusRecorder+中间件），挂 gen `StdHTTPServerOptions.Middlewares` 一处覆盖全部 gen 路由，endpoint 取 `r.Pattern` 去方法前缀（低基数路由模板，单测锁定）；②media_bytes_total：media.go 两处 `http.ServeContent` 外套 countingResponseWriter，304/Range 只计实发字节（隔离实测 orig 拉取 1602752B=文件大小、thumb 34248B=缩略图实发）；③sse_connections：events/sse.go 新增 `WithConnectionGauge` Option 回调（events 包不感知 sysmon，server.go 装配期注入 `sysmon.Default.SetSSEConnections`），占坑成功/释放后 Set 绝对值防漂移，503 拒绝不计，Flusher 透传保 SSE 不断流；④scan_duration_seconds：scanner.go `Scan` 成功返回前 Set（失败留旧值），API 触发与 watch 轮询单点覆盖；⑤library_files{type}：refreshLibraryFileMetrics（ListLibraries+逐库 CountLibraryMedia，animated_image 归 image 与库列表同口径），挂 FinishScan/上传入库/删除进回收站/恢复四个变更点；⑥thumb_queue_depth：pool.go Submit 入队与 work 取任务两处 Set（M3 预热接入前无生产者恒 0，属"接线完成待激活"）；⑦trash_items/trash_bytes：refreshTrashMetrics（listTrash 遍历磁盘 meta + 逐条 os.Stat 求和，不走死表），挂删除入站/恢复/单条物理删除/清空四个变更点。
- **口径注记（OBSERVABILITY.md 新增权威小节）**：排除清单 /metrics、/api/v1/healthz、/api/v1/readyz、/api/v1/events 四条路由整条跳过 http 计数（events 长流由 sse_connections 单独覆盖）；http 指标不含参数绑定失败的 400（gen 绑定层在中间件之前短路）与未匹配路由 404；library_files 与 trash 两 gauge 为变更点推送刷新非定时采样。
- **自测**：go build 过；`go test ./... -count=1` 全绿（新增 metrics_middleware_test.go 四用例：endpoint=路由模板/状态码捕获/排除清单不计数/SSE Flusher 保留）；`make lint` 全绿（0 issues，web 存量 17 warning 不属本批）。隔离实例（18427+新临时数据目录 %TEMP%\qimeng-b2+dev 模式，未碰 8420，测完收进程）实测：http_requests_total 全部为路由模板形态且不含 events//metrics 行、media_bytes_total 两 kind 有字节、sse_connections 开流 1/断开 0、trash_items 删除后 1、library_files 扫描后 1/删除后归 0、scan_duration_seconds 非零。
- 改动文件：server/internal/httpapi/{metrics_middleware.go,metrics_middleware_test.go(新),server.go,media.go,libraries.go,upload.go,trash.go} + server/internal/events/sse.go + server/internal/scanner/scanner.go + server/internal/thumbnail/pool.go；文档：OBSERVABILITY.md（口径注记节）、CHANGELOG.md（本条）。
## fix(web): 目录树嵌套子树层级缩进对齐修复 + DirFileList 重取闪烁清偿（2026-09-06 第八十笔）

执行 AI：GLM-5.3-Flash（B 会话·目录树对齐修复批，拍板来源=待拍板-20260905夜2 条目 2）

- **问题与定性**：文件管理页目录浏览卡多级子目录展开后，子树行与父目录行/文件行**横向并排**呈楼梯状而非逐级缩进（存量对齐缺陷，B-1 批实测证实非当时回归，用户拍板修复）。隔离实例（18426+新临时数据目录+dev 模式，未碰真实库）造 4 层嵌套实测修复前各层行文字左缘 x：库根 117.56 → sub1 386.50 → sub1a 624.64 → sub1a-deep 886.09、sub2 跳回 386.50（页 zoom 1.1 口径），无任何逐级关系。
- **根因**：DirTreeNodes 嵌套 `<ul>` 与行容器同为 `<li>` 直接子元素，`.rank-card li` 是 display:flex+flex-wrap——目录浏览卡的树裸 `<ul>` 无 `.dir-tree` 作用域（reset 只覆盖上传卡），嵌套子树成了水平 flex item 被排到父行右侧，行内 depth*步长 paddingLeft 在横排下失效。
- **修法（零 DOM/零交互变化）**：① prototype.css B-8 段 `.dir-tree-list{flex-basis:100%;width:100%}` 嵌套 ul 压回父行下一行（DirBrowser 顶层 ul 与 DirTree.tsx 嵌套 ul 同挂此类）；② `.dir-tree-list li` 横向 padding 归零，缩进唯一来源=DirTree.tsx 具名常量 `DIR_TREE_INDENT_PX` 14→16/级；③ 顺带修 B-5 死规则：`.dir-file-row`（0-1-0）恒被 `.rank-card li`（0-1-1）压住，padding 7px 8px/hover 底从未生效，加 `li.` 前缀使 B-5 写定值真正渲染；④ DirFileList 重取闪烁（B-5 批 reviewer P3 清偿）：useAssetsInDirectory 加 `placeholderData: keepPreviousData` + 组件 isFetching→isLoading，失效重取/切目录期间保留旧列表。
- **修复后数值**（headless 静止态实测，浅/深两态几何一致）：树行行盒左缘全部 115.38=卡片内容线；文字左缘 库根 124.17 → sub1 141.77 → sub1a 159.36 → sub1a-deep 176.97，逐级步长恒 17.6=16px×zoom1.1，sub2=141.77 与同级同线；文件行文字左缘 124.17 与库根行完全同线（§4.5 基准线）；上传卡共享组件同测通过、目录选中→文件清单切换交互零变化。tsc 0 错/build 成功/lint 改动文件 0 告警（存量 17 条不属本批）；证据目录 %TEMP%\qimeng-b8-evidence\（保留勿删）。改动文件：web/src/components/manage/{DirTree,DirBrowser,DirFileList}.tsx + web/src/hooks/use-assets.ts + web/src/styles/prototype.css；文档：HANDOVER_UI.md §5 第 18 条。
## docs(docs): B 车道收工——夜2 B 链七批全部完成+三轮对抗审查通过（2026-09-06 第七十九笔）

执行 AI：GLM-5.3-Flash（B 会话·调度落档）

- **完成批次与 commit 链**：B-1 文件管理目录树操作化·续做收尾 36af094（两 stash 依序 pop 落地，tsc/build/lint 绿+隔离实例 curl 全链沿用 %TEMP%\qimeng-b1 证据）→ B-2 全局动效现代化 e6fac87（页面过渡 token 化/卡片进场 stagger/弹层 data-state 统一/reduced-motion 归零层，纯 CSS 零 TSX 零新依赖，headless 文本实测零漂移）→ B-3 小清偿 220aba3（facets hooks 合并+收藏页排序口径 favoriteAt desc 落档）→ B-4 /assets 目录过滤协议 26c2126（openapi→make sdk→browse.sql 三查询谓词→sqlc→四类单测→GUIDE_API，reviewer 通过）+ 跟进单测 5a472b5（不存在目录空列表用例）→ B-5 目录树文件行接线 53ba308（useAssetsInDirectory+DirFileList+FileOpsDialogs 共享抽取，重命名/删除/回收站 headless 全链证据）→ B-6 M6 备忘录（仓库外 m6-next-steps.md：Termux 启动设计稿+ffmpeg 停维护后替代方案 15 组来源核实，记账 0e1fd58）→ B-7 文档漂移检测 a1ca92e（GUIDE_API 两处漂移已修：库管理漏列 2 端点+路径计数 rot；OBSERVABILITY/PROJECT_PLAN 零漂移）。自审清偿两笔：5eebfbf（reviewer P3×5）+ 40862ea（删 legacyHistoryLimit 常量，make lint 自 8344920 起首次全绿）。
- **审查结论**：reviewer 全新上下文对抗审查三轮（B-1~B-3 / B-4 / B-5+清偿）全部**通过**，零未决 P1/P2。
- **待拍板存量**：仓库外《待拍板-20260905夜2.md》条目 1（✅ 已按建议口径落地 B-4/B-5，待用户翻拍可销）、条目 2（嵌套子树对齐存量问题）、条目 3（M6 ffmpeg 选型：Termux 先行+内嵌形态 jniLibs exec 三件套）；W-4 两条仍在《待拍板-20260905夜集群.md》；A 会话新增条目 4/5 由 A 车道落档。
- **遗留移交**：①CAPABILITY_MAP 三处漂移（联想建议/推荐偏好页/相册视图已落地仍标缺口）与 openapi /import/qimeng-backup 413 声明缺口、指标埋点缺口（9 族注册恒零值）——均第七十八笔记账待后续批次；②26c2126 曾夹带 A 车道已 git rm 的 android/feature/album 两删除（内容与 M4-2 导航四化一致，仅历史归属瑕疵，已在该笔披露、不重写已推送历史），引用清理待 A 车道提交收尾。
## docs: B-7 文档漂移检测清偿（2026-09-06 第七十八笔）

执行 AI：GLM-5.3-Flash（B 会话·B-7 批）

- **范围**：四文档漂移检测（GUIDE_API↔openapi 端点集、OBSERVABILITY↔sysmon 指标注册点、CAPABILITY_MAP 三态↔实际能力、PROJECT_PLAN 勾选↔实际进度），只修文档零代码改动。
- **GUIDE_API.md 直接修复（2 处漂移）**：①速览「库管理」行补齐 b2dc7d3（migration 0007 启停批）漏更的两端点——DELETE /libraries/{id}（删库登记，级联清资产及关联、事件流保留、磁盘与回收站不动）与 PUT /libraries/{id}/enabled（停用仅隐藏浏览面，记录/统计/磁盘全保留），措辞对齐 openapi.yaml:92-130 描述，服务端 handler 实在 libraries.go:236/264；②路径计数 41→50 校正（口径=openapi 全路径总数，4a1f3da 时 36↔36 对齐后 rot——7cee625 已 48 记 40、8344920 已 50 记 41；现与 openapi.yaml 50 路径/62 操作对齐，速览表覆盖全部 50）。
- **OBSERVABILITY.md 零漂移**：指标表 9 行与 internal/sysmon/metrics.go 注册点一一对应（http_requests_total{endpoint,method,code}/duration 直方图 buckets 5ms~60s/media_bytes_total{kind}/upload_total{result}+upload_bytes_total/sse/scan/library_files/thumb_queue/trash_items+trash_bytes，11 个注册全数在册、无增删改名），不改动。**顺带发现（非文档漂移，接线缺口记账）**：除 upload 族（upload.go:84-159）外其余 9 个指标族全仓无埋点调用点（IncHTTPRequest/SetSSEConnections/SetScanDuration 等注册后零调用），/metrics 输出恒零值——属代码待接线项，待后续服务端批次清偿。
- **CAPABILITY_MAP.md 三处漂移（不在 B 车道可写清单，记账待权限方修）**：①索引与检索行缺口「检索高亮/联想建议未做」——联想建议已落地（GET /search/suggestions + web 顶栏补全 eda96cf，web/src/hooks/use-suggestions.ts），缺口应余「检索高亮」；②推荐行缺口「推荐偏好设置页 UI（M3 剩余 1 项）」——已落地（PROJECT_PLAN M3 勾选 2026-09-03，web/src/pages/SettingsPage.tsx + use-prefs.ts）；③媒体库行缺口「相册视图」——已落地（AlbumsPage 消费 GET /assets/facets 四维筛选相册页，0d4c9df，web/src/pages/AlbumsPage.tsx）。
- **PROJECT_PLAN.md 零漂移**：M0-M3 勾选与交付一致；M4 进行中（勾选属 A 车道，不动勿记）；M5/M6/M7+ 未动；本晚 B 链七批均无新里程碑勾选项。
- **附带记录（协议面缺口，openapi.yaml 非本批可写清单）**：POST /import/qimeng-backup 实际超限回 413 TOO_LARGE（httpapi/errors.go:57，0977190 落地，GUIDE_API 已记）但 openapi.yaml 该端点 responses 未声明 413——待协议侧批次补记。
## test(api): 目录过滤补不存在目录空列表用例（2026-09-06 第七十七笔）

执行 AI：GLM-5.3-Flash（B 会话·B-4 审查跟进）

- **背景**：B-4 批（第七十三笔）对抗审查通过后留 P3 一条——TestAssetListDirectoryFilter 缺「directory=不存在目录」用例，服务端该分支（200+空列表）仅有隔离实例 curl 证据（ev2）无单测锁定。
- **改动**：browse_test.go 该测试 ③ 与 ④ 之间插 ③b 用例：`?directory=no-such-dir` → 200 + 空 items + totalMatched=0（目录树展开空目录的正常分支、非错误路径，与 curl ev2 行为对齐）。零生产代码改动，协议零变化。
- **验收**：`cd server && go test ./internal/httpapi/ -count=1 -run TestAssetListDirectoryFilter -v` → `--- PASS (0.06s)`；`go test ./... -count=1` 全量全绿（httpapi 11.929s ok，各包无 FAIL）。改前核对 server/** 工作树干净（A 车道 android 改动未触碰），暂存区 `git diff --cached` 核对仅本批 2 文件。
## feat(web): 目录树文件行接线（消费 /assets directory 过滤）（2026-09-06 第七十六笔）

执行 AI：GLM-5.3-Flash（B 会话·B-5 批）

- **完成项**：文件管理页目录浏览卡「目录树文件行」接线（纯消费 B-4 第七十三笔的 GET /assets directory 过滤，协议零改动）：①`hooks/use-assets.ts` 新增 `useAssetsInDirectory(libraryId, directory, enabled)`——GET /assets 带 libraryId+directory+limit=200（协议上限具名常量 DIR_FILES_LIMIT，目录内直接子文件量级小一次拉全）；queryKey 含 libraryId+directory（切目录换键重取），挂在 ASSETS_QUERY_KEY 根键下（useMoveAsset/useDeleteAsset 的根键失效直接命中文件清单）；②`DirTree.tsx` 增「选择+行尾操作」组合模式（select 按钮与行尾操作并存——按钮不可嵌套，行容器走 .dir-row flex；上传卡/整理弹窗纯选择模式零变化）；③`DirBrowser.tsx` 目录行升级组合模式：点击目录（含根行「库根」）选中，树下方新增 `DirFileList.tsx` 列该目录直接子文件行（文件名 + 类型/大小 metric + 行内 hover 三操作 重命名/移动/删除；缺省选中库根，换库复位）；④弹窗编排抽共享 `components/manage/FileOpsDialogs.tsx`（MoveDialog + 删除确认「移入回收站」文案 + useDeleteAsset 删除流第二处出现即提取，代码卫生 6；详情页 `FileOpsButton` 改委托复用，删除后 navigate(-1) 收尾保留）——挂载形态与抽取前逐语义一致：MoveDialog 条件挂载（惰性初始化复位依赖条件挂载，首测常驻挂载翻车：预填名冻成空串、保存即误移库，已修并复测），ConfirmDialog 常驻受控（B-2 关闭动画依赖常驻挂载）；⑤prototype.css 追加 B-5 段（组合模式目录行 .dir-node flex 修正 + 文件行区样式，颜色全走既有 token，零新颜色字面量）。批量多选不做（既有待办不变）。
- **验收**：`cd web && npx tsc --noEmit -p tsconfig.app.json` 0 错；`npm --prefix web run build` 成功；`npm --prefix web run lint` 改动文件 0 告警（存量 17 条不属本批）。隔离实例（18425+新临时数据目录+dev 模式，未碰 8420 真实库）headless 文本实测：选库后缺省「库根」选中、文件行 3 行（video-a.mp4 视频·31 B / image-a2.png 图片·70 B / image-a.png 图片·70 B，行内三操作齐）；点 sub1 → 文件行切 2 行（「sub1」的直接子文件2）；行内重命名 image-b.png→renamed-b.png 生效（弹窗预填正确、DOM 文本变化、刷新后保持）；删除 image-c.png（确认弹窗文案含「移入回收站」）→ 行消失、GET /trash 可查（originalPath=sub1/image-c.png）；静止态布局数值（文件管理页目录浏览卡区无历史基线，本批实测留档为基线）：page x=96.78/y=95.69/w=1118.83（y 与 B-3 批 h2 口径一致）、目录卡与 page 同宽（spread=0）、目录树首行 y=791.53/h=36.81、文件区标题 y=861.33、首文件行 y=891.56/h=40.58；证据目录 %TEMP%\qimeng-b5-evidence\（保留勿删）。
## fix(server): 删未使用常量 legacyHistoryLimit 清 make lint 红（500 上限单一来源归 legacy_export.sql）（2026-09-06 第七十五笔）

执行 AI：GLM-5.3-Flash（B 会话·自审清偿批）

- **完成项**：删除 `server/internal/httpapi/export.go` 的未使用常量 `legacyHistoryLimit`（commit 8344920 引入，即第七十一/七十三笔「遗留」反复记账的 `make lint` 主干既有红）及其注释行；原位置留一行指针注释「history 段 500 条上限的单一来源在 store/queries/legacy_export.sql（ExportRecentOpenEvents，对齐旧库 view_history）——SQL 侧改动须同步彼处注释」（代码卫生 7 双方注释互指；SQL 侧 legacy_export.sql 注释彼处本就有，本笔未动）。行为零变化，只删常量。
- **验收**：`cd server && go build ./...` 过、`go vet ./internal/httpapi/` 过、`golangci-lint run internal/httpapi/...` 0 issues；仓库根 `make lint` exit 0（redocly + gofmt + golangci-lint + web build 全链，自 8344920 起首次全绿），TS 侧 17 条 warning 为既有存量非失败项。
## fix(web): B车道自审P3清偿（死类名/删除后目录树失效/条目措辞/时长token化）（2026-09-06 第七十四笔）

执行 AI：GLM-5.3-Flash（B 会话·自审清偿批）

- **完成项**：reviewer 对抗审查 B-1~B-3 三笔通过后所列 P3 建议清偿 + 记账措辞订正，功能零变化：①`CreateDirDialog.tsx` 删死类名 `dir-act-create`（全仓库无 CSS 定义，按钮实际渲染 `confirm-btn--cancel` 样式）；②`use-file-ops.ts` useDeleteAsset onSuccess 补失效 `DIRS_QUERY_KEY`（与 useMoveAsset 对齐，消除删除资产后目录树 fileCount 的本地即时性窗口；DIRS_QUERY_KEY 本就在该文件 import）；③第六十八笔与 HANDOVER_UI §5 第 14 条完成项首句订正——原「文件管理页目录树文件行三操作」与实际不符（三操作实际挂详情页互动行，目录树只新增新建子目录），改为「详情页互动行文件三操作（重命名/移动/删除入回收站）+ 目录树新建子目录（目录树文件行操作待 B-4/B-5 协议扩展与接线）」，只改措辞不改条目结构；④B-3 合并注释口径订正（use-assets.ts + HANDOVER_UI §5 第 16 条同步）——原「相册页 partition 恒显式传、两页参数形态天然互斥」表述不准（AlbumsPage 的 facetPartition 路排自身口径不传 partition），改为「两页参数形态不同键为主；即使同键也同参数同响应共享缓存零行为差」；⑤prototype.css B-1 段两处裸 `transition: .15s` 时长改 `var(--qm-duration-fast)`（120ms，视觉档等价，对齐 B-2 段 token 化做法）。
- **披露**：B-4 批 commit 26c2126 曾携带 A 车道已 git rm 的 2 个删除（android/feature/album，内容与 M4-2 导航四化一致，仅历史归属瑕疵；共享 master 不重写已推送历史，特此落档）。
- **验收**：`npx tsc --noEmit -p tsconfig.app.json` 0 错；`npm --prefix web run build` 成功；`npm --prefix web run lint` 改动文件 0 告警（存量 17 条不属本批）。功能零变化（改的是失效键补齐/死类名/措辞/CSS 时长 token 化），未做隔离实例重测。
## feat(api): /assets 目录过滤参数（目录树文件行数据源）（2026-09-06 第七十三笔）

执行 AI：GLM-5.3-Flash（B 会话·B-4 批）

- **协议（openapi 先行）**：GET /assets 增可选 query 参数 `directory`——库内相对目录**精确匹配**，只返回该目录**直接子文件**、不含递归子目录；空串=库根（与 GET /dirs 的 DirTree 根节点 path="" 语义对齐）；缺省=不过滤（生成 `*string`，nil/非 nil 区分）；非法路径（绝对路径/盘符/../逃逸/保留设备名等）沿用本链路 400 INVALID_PARAM（不引入 422）。响应结构零改动（复用 AssetSummary/AssetPage）。用途=文件管理页目录树「文件行」数据源（web 接线属 B-5 批）。`make sdk` 三端重建通过。
- **服务端**：①`browse.sql` 三查询（ListAssetsFilteredDesc/Asc、CountAssetsFiltered）同步插入同一谓词：`rel_path = CASE WHEN dir='' THEN file_name ELSE dir||'/'||file_name END`——file_name=rel_path 尾段不变量经 scanner/filing/upload 三写路径证实；谓词形态先经临时 probe 查询跑 sqlc v1.31.1 验证可解析（`sqlc.narg(directory)` 三处引用去重为单一 `?1` 占位符，CASE 分支内参数比较+`||` 拼接均合法，probe 件已删）；②`assets.go`：assetFilters 增 `Directory` 字段（直落 `sql.NullString{Valid:true}`，**不经 nullStr**——nullStr 把 "" 映射 NULL 会吞掉"空串=库根"；反射 applyFilters 同名落参零改动）；handler 侧先过 `filing.NormalizeRelPath`（SECURITY 红线 1 统一入口，失败 400「目录路径不合法」，filing.go move 的 targetDir 同款先例），空串不归一直接 Valid；③sqlc 重生成 browse.sql.go（git 入库生成物）。
- **新增单测**：`TestAssetListDirectoryFilter` 四类用例（种子 sub/d.jpg + sub/deep/e.mp4 直插 UpsertAsset，不需要磁盘真文件）：`directory=sub` 精确命中不含递归孙文件；`directory=`（空串）只含库根文件；`directory=sub&mediaType=video` 组合叠加；`directory=../x` 与转义形态 `%2e%2e%2fx` 均 400 INVALID_PARAM。
- **验收**：`cd server && go test ./... -count=1` 全绿；隔离实例（18424 端口+新临时数据目录+dev 模式，未碰 8420 真库）curl 实测：`directory=sub` 恰回 2 个直接子文件（deep/e.jpg 不入）、`directory=empty-dir` 空列表 totalMatched=0、`directory=sub&mediaType=image` 组合筛选恰中目标、`directory=`（空串）只回库根文件、`../x` 原文与 `%2e%2e%2fx` 均 400 INVALID_PARAM「目录路径不合法」。证据目录 `%TEMP%\qimeng-b4\`。
- **遗留**：`make lint` 败于与第七十一笔相同的主干既有问题（`server/internal/httpapi/export.go:47` `legacyHistoryLimit` unused，commit 8344920 引入）——本批改动文件 gofmt/golangci-lint/redocly 全部干净（golangci-lint 仅此 1 issue）；按 B-4 口径不做 directory 谓词索引优化（表达式谓词走全扫，万级库实测无感，留档待 profiler 说话）。
## refactor(web): facets hooks 合并+收藏页排序口径落档（2026-09-06 第七十二笔）

执行 AI：GLM-5.3-Flash（B 会话·B-3 小清偿批）

- **facets hooks 合并（reviewer 建议项清偿，HANDOVER_UI §5.9 六任务批第 3 条）**：`pages/use-collection-facets.ts`（作者集合页专用，系六任务批并行冲突规避产物）与 `hooks/use-assets.ts` 的 `useAssetFacets` 逐语义重复——收敛为单一 hook 参数化：`useAssetFacets(params, enabled = true)` 增可选 enabled（相册页 AlbumsPage 四路调用零改动），CollectionPage 改直调共享 hook，消亡文件已删除。合并前语义差异三点均保留在调用方传参、未借机改业务口径：参数面（共享版多 partition/source 两键）、enabled（集合页防无效请求需求，共享版本次补齐缺省 true）、缓存键（集合页版多 'collection' 命名空间段，去掉后同参数同响应共享缓存，两页参数形态天然互斥无键碰撞）。各页面行为零变化（铁律 7 照守：hooks 层调 API 不变）。
- **收藏 tab 缺省排序口径落档（HANDOVER_UI §5 第 16 条②，A 车道 M4-2 批对齐基线）**：MinePage 收藏 tab = `GET /assets favorite=true, sort=favoriteAt, order=desc`——按收藏时间倒序（最新收藏在前）、服务端排序（协议 sort/order 参数，前端仅 flatMap 拼页零二次排序）、limit 协议缺省 60 游标分页；无用户可调排序 UI，恒定此参数。只记录现状未改排序行为。
- **验收**：`npx tsc --noEmit -p tsconfig.app.json` 0 错、`npm run build` 成功、改动 3 文件 lint 0 告警（存量 17 条不属本批）；隔离实例（18423+新临时数据目录+dev 模式，未碰真实库）headless 文本实测：相册页四维胶囊渲染正常（分区2/作者2/角色2/类型3，值行 全部8/常规5/COS3）+点选「类型:图片」网格条数 8→7；作者集合页（COS 作者）筛选胶囊同测（作品2/类型3，点「作品A」网格 3→2）；首页网格顶沿复测 83.05 与 B-2 基线零漂移（§4.5 终值 83.1 口径），相册/集合页静止态数值在案。证据目录 `%TEMP%\qimeng-b3-evidence\`。
- **记账编号注**：本笔顺延为第七十二笔——第七十一笔已被并行 A 车道 M4-1 批（commit 88af1b9）占用，避让防撞号。
## feat(app): M4-1 登录与服务端配置——DataStore token/401 事件跳登录/退出登录（2026-09-06 第七十一笔）

执行 AI：GLM-5.3-Flash（夜2 A 车道·执行子代理）

- **完成项（HANDOVER_APP §3 M4-1 批次）**：①`:core:network` 半成品编译修复（登录响应 token 可空兜底、DataStore 工厂 import）；②新增 `:feature:login`——登录页两字段（服务器地址记忆上次+占位 `http://192.168.x.x:8420`、密码掩码）、LoginViewModel（StateFlow，提交防重入、错误分类驱动文案），「地址不通」与「密码错误」中文文案分开；③`:feature:settings` 最小登出入口（M4-6 完整设置页前只交付会话闭环）；④`:app` 壳层会话分支（Loading/LoggedIn/LoggedOut）——token 流驱动起始页 + SessionEventBus 401 事件即时退登录页（重登成功复位过期标记），AndroidManifest 补 INTERNET 权限与 `usesCleartextTraffic`（targetSdk 36 默认禁明文，NAS 内网 http 为产品形态）；⑤新增 `:core:testing` 共享测试模块（FakeAuthRepository/MainDispatcherRule，NIA 范式，避免三处复制粘贴）。
- **半成品测试可运行化（零新依赖）**：`AuthRepositoryImplTest`/`AuthInterceptorTest` 原用 JDK 内置 `com.sun.net.httpserver`，Android 库 unit test 编译类路径只有 android.jar 不含 com.sun.*——改 OkHttp 官方测试范式「fake 传输层拦截器」，8+5 用例语义与断言全保留（协议面路径路由锁定/错误分类/401 事件流）。
- **新增单测**：LoginViewModelTest 6 用例、SettingsViewModelTest 1、MainViewModelTest 4（登录态分支/401 即时退页/重登复位）。全模块 35 用例零失败。
- **实机验收（qimeng_api35 模拟器，文本证据协议）**：a. 登录 `http://10.0.2.2:8420` 成功进五 Tab 壳；b. 杀进程重启仍登录直进壳；c. 我的 Tab 退出登录回登录页且地址回填（记忆上次）；附加：错误密码实显「密码错误，请重新输入」（与地址不通分开）。证据目录 `%TEMP%\qimeng-m41-evidence\`。
- **遗留**：`make lint` 败于 server 基线既有问题（`server/internal/httpapi/export.go:47` `legacyHistoryLimit` unused，commit 8344920 引入，本车道无 server 改动权限）；模拟器 5554 端口被第三方模拟器（MI 9）占用，qimeng_api35 以 `-port 5556` 启动后跨批保持运行。
## docs(docs): B-6 M6 前置深化备忘录——Termux 一键启动设计稿+ffmpeg 停维护后替代方案扫描（2026-09-06 第七十笔）

执行 AI：GLM-5.3-Flash（B 会话·read-only 批，仓库外产出）

- **产出**：仓库外《QimengNAS/m6-next-steps.md》（约 200 行）——①Termux 形态一键启动脚本设计稿（termux-setup-storage 授权流/二进制投放路径/三层保活/伪代码含失败兜底，未实测处逐条标注「设计稿·未实测」）；②App 内嵌形态 ffmpeg 方案扫描（官方主线 9.0.1 NDK 自编译 / gomobile 桥接 / 社区分支 FFmpegKitNext·ffmpegkit-maintained·hzw1199 预编译成品，各含成熟度/维护/成本/架构契合点）。
- **关键事实（3 轮网络核实 15 组来源）**：arthenica/ffmpeg-kit 2026-07-02 归档；FFmpegKitNext 为官方续作（源码分发、Kotlin API、无 CLI）；ffmpeg 主线 9.0.1（2026-08-12）；Android 16KB 页对齐现行口径 2027-02-01。
- **建议口径**已落仓库外《待拍板-20260905夜2.md》条目 3（Termux 先行第一根烟囱；内嵌形态 jniLibs exec 三件套；不采纳 gomobile bind 与 ffmpeg-kit 系 AAR）。本笔仓库零代码改动（read-only 批）。
## feat(web): 全局动效现代化——页面过渡 token 化/卡片进场 stagger/弹层统一进出场/reduced-motion 归零层（2026-09-06 第六十九笔）

执行 AI：GLM-5.3-Flash（B 会话）

- **完成项（四件套，纯 CSS 零 TSX 改动零新依赖，只动 `web/src/tokens.css` + `web/src/styles/prototype.css` 末尾 B-2 段）**：①新增缓动 token `--qm-ease-out`（easeOutCubic）/`--qm-ease-in`（easeInCubic）具名常量，时长一律复用既有 `--qm-duration-fast/base/slow` 三档（动效 token 此前全库零消费）；②`.page` page-in 改走 token（.18s→200ms 视觉等价档），13 页切页自动重放行为不变；③`.card`（首页/搜索/相册/集合/我的五页网格共用）进场 stagger——fade+8px 上移，animation-delay 40ms/档、第 13 张起封顶 480ms 防长列表尾卡久等，无限滚动追加卡按自身序号重放属期望行为；补 hover 微交互（translateY(-2px) 浮起 + 封面阴影，新 token `--card-shadow-hover` 浅/深双值，阴影加在 .card--cover 可视面而非透明盒）；④radix 弹层 data-state 进出场统一——confirm 弹窗/标签管理/新建目录/整理弹窗（.detail-dialog 族）+ 顶栏搜索 popover + select 下拉，只用 opacity+小幅 scale/translateY，html zoom:1.1 与 popper wrapper zoom:0.909 补偿公式零接触；closed 态动画经 radix 官方 Animation 指南确认（Presence 挂起卸载，动画完才移除）。
- **reduced-motion**：新增 `@media (prefers-reduced-motion: reduce)` 全局归零层，覆盖本批新增+存量全部 animation/transition（含 infinite 循环强转 1 次、stagger 延迟一并归零防空窗）；用 0.01ms 而非 0s 保证 animationend/transitionend 照常派发——详情页点赞弹跳 onAnimationEnd 复位与 radix Presence 卸载路径不变。
- **口径**：存量散点 transition（.pill/.detail-act/.dir-action-btn 等已有效果的）保持原值不动，本批不逐处改字面量规避视觉回归；radix 组件层零改动（className 指回 prototype.css 追加段）。
- **验收结论**：`npx tsc --noEmit -p tsconfig.app.json` 0 错；`npm --prefix web run build` 成功；`npm --prefix web run lint` 存量 17 告警不属本批（本批改动为 CSS）。隔离实例（18422+新临时数据目录+dev 模式，未碰真实库）headless 文本实测：静止态零漂移（首页网格顶沿 83.05≈§4.5 静止态终值 83.1；首卡五元组 x96.78/y83.05/w263.2/h225.02 在案）；stagger 阶梯实读 0/0.04/0.08/0.12/0.16s；hover 后 transform=translateY(-2)+阴影+0.12s 过渡；confirm 弹窗 open 态 animation-name=qm-panel-in(0.2s)/遮罩 qm-fade-in、取消后 closed 态 qm-panel-out(0.12s) 且动画完才卸载（Presence 闭环实证）；emulate reduced-motion 后 page/card/存量 search 输入框 transition 全 0.01ms；html zoom=1.1 未动。证据目录 %TEMP%\qimeng-b2-evidence\（保留勿删）。
## feat(web): 文件管理目录树操作化（重命名/移动/删除入回收站/新建子目录）（2026-09-06 第六十八笔）

执行 AI：GLM-5.3-Flash（B 会话·收尾交付）

- **完成项**：详情页互动行文件三操作（重命名/移动/删除入回收站）+ 目录树新建子目录（目录树文件行操作待 B-4/B-5 协议扩展与接线）——重命名/移动（POST /assets/{id}/move，targetDir 必填 + newName 可选一端点两用：改名=同目录+新名、移动=新目录+原名；服务端保证 asset_id 与全部关联数据零改动，移动后自动重算出处/COS 富化）/删除=入回收站（DELETE /assets/{id}，铁律 4 DELETE 语义永不物理删，确认文案明示「移入回收站」）；目录行新建子目录（POST /dirs，幂等，已存在视为成功）。操作成功后目录树/回收站相关 query 失效（本地 onSuccess + 服务端 library.changed SSE 双保险，TanStack invalidate 幂等）；失败 toast 透传服务端文案。协议零改动（全走既有端点）。
- **新增代码**：`hooks/use-file-ops.ts`（useMoveAsset/useDeleteAsset/useCreateDir，铁律 7 UI 组件禁直调 API）+ `components/manage/DirBrowser.tsx`/`MoveDialog.tsx`/`CreateDirDialog.tsx` + `components/detail/FileOpsButton.tsx`（详情页已接线）。
- **验收结论**：`npx tsc --noEmit -p tsconfig.app.json` 0 错；`npm --prefix web run build` 成功；`npm --prefix web run lint` 改动 9 文件 0 告警（存量 17 告警在 router.tsx/SearchPage 等，不属本批不扩围）。隔离实例 curl 全链：新建目录 201 + 重放幂等、移动+重命名 200、目标已存在 409 TARGET_EXISTS、删除→回收站可查→恢复 200。证据目录 %TEMP%\qimeng-b1\（保留勿删）。
- **待办与待拍板**：目录树批量多选本批不做记待办；两项待拍板见仓库外《QimengNAS/待拍板-20260905夜2.md》（条目 1=目录树文件行缺协议数据源→B-4 批解决；条目 2=嵌套子树对齐存量问题）。
## docs(docs): QimengNAS 工作区清理——删除重复/过时文件，拍板指针归一夜2（2026-09-06 第六十七笔）

执行 AI：GLM-5.3（主代理·计划）

- 用户 2026-09-06 01:10 拍板：工作区文件太多，删重复/用不到的。删除 4 个：`m4-2-decisions.md`（1A/2B/3B/4A 拍板，与夜2「A 车道批次拍板与执行口径」完全重复）、`m4-env-preflight.md`（M4-0 一次性预检，AVD/环境已就绪且口径进夜2 通用环境备忘）、`待拍板-20260905午.md`（拍板已全部落地，结论并入夜2 专节与仓库 docs）、`进度盘点-20260905晨.md`（自声明一次性快照，被夜2「当前磁盘状态」取代）。
- 保留（均有夜2 任务书活引用）：派发任务书-20260905夜2、待拍板-20260905夜2（活跃台账）、待拍板-20260905夜集群（W-4 两条仍待用户拍板）、m4-2/3/6-spec-notes ×3 + m4-6-runtime-notes（M4 各批执行蓝本）、m6-ffmpeg-memo + m6-poc/（B-6/M6 依据）、00-总说明（工作区 README，顺修旧项目关系过时表述——待退役口径对齐 ADR-0015）。
- 引用订正：HANDOVER 拍板详情指针改指夜2 专节；待拍板-夜2 台台账头移除已删文件引用。
## docs(docs): 旧派发任务书（20260905夜）删除——M4 拍板执行口径并入夜2 任务书成唯一蓝本（2026-09-06 第六十六笔）

执行 AI：GLM-5.3（主代理·计划）

- 用户 2026-09-06 01:05 拍板：删除旧任务书《QimengNAS/派发任务书-20260905夜.md》，只保留当夜《派发任务书-20260905夜2.md》。
- 删除前先把旧书独有内容并入夜2（避免断链）：M4-2 拍板 1A/2B/3B/4A+A3/A4+B 组 B1~B8 执行口径+存疑五条处置、M4-6 拍板 C1~C6、M4-3 五条拍板（likedToday 已就绪段跳过）——合并为夜2「A 车道批次拍板与执行口径」节；A 会话任务书引用全部改为「HANDOVER_APP §3 + 夜2 专节」，夜2 成为自包含唯一任务书。
- HANDOVER「单批任务书」引用同步订正；旧任务书涉及的 m4-2/3/6-spec-notes 备忘录与待拍板/进度盘点文件非任务书，保留。
## docs(docs): 夜2计划两会话定稿——每会话 ≤3 并发子代理，C 车道裁撤并入 B 链（2026-09-06 第六十五笔）

执行 AI：GLM-5.3（主代理·计划）

- 用户 2026-09-06 00:50 拍板：执行=**两个持久会话**（A=Android、B=Web+协议+服务端）；**每会话同时可跑 ≤3 个子代理**（典型 1 写批 + 1~2 只读并行；两个写子代理并行仅当文件集不相交）。**账号级子代理上限撤除**——昨晚蓝本记录的「全账号 ≤4/1302 限流」经用户实测可超（保留「撞限流等 2 分钟重试」兜底一句）。
- **C 车道裁撤**（用户判其对 A/B 的审查角色不需要独立会话）：其实质工作并入 B 链——C-1 /assets 目录过滤协议扩展→B-4、C-2 M6 备忘录→B-6、C-3 文档漂移→B-7、C-4 夜终审查→各会话「续做与自审协议」多轮自审承担。B 车道恢复协议改动权（铁律 1 先 openapi 后 make sdk；A↔B 生成物竞态自愈协议保留）。
- 会话池/磁盘锁机制随两会话定稿移除。
## docs(docs): 夜2计划三会话版——新增 C 车道（协议/M6/审查）与三会话并发·竞态约束（2026-09-06 第六十四笔）

执行 AI：GLM-5.3（主代理·计划）

- 用户 2026-09-06 00:10 拍板：执行会话 2→3 个。新增 **C 车道**（api/openapi.yaml + server/** + GUIDE_API/DOMAIN_RULES/OBSERVABILITY + 仓库外备忘录）：C-1 `/assets` 目录过滤协议扩展（B-1 急停现场发现的目录树文件行数据源缺口；建议口径=精确目录过滤，已落《待拍板-20260905夜2.md》条目 1，B 端接线后置为条件批 B-6）→ C-2 M6 备忘录续写 → C-3 文档漂移检测 → C-4 夜终一致性审查。原 B 车道禁协议改动不变（协议归 C）。
- **三会话并发约束重订**：每会话在跑写批次子代理 ≤1、只读子代理 ≤1，全账号在跑合计 ≤4（~5 并发触发 1302 限流）；A↔C 的 make sdk 生成物竞态自愈协议（昨晚已验证）入蓝本共同协议 5。
- 计划书重写为三车道三会话自驱版（三段 paste-ready 开工任务书），「当前磁盘状态」节与 git 实况核对一致（stash ×2 / 半成品文件清单 / 8420 在线）。
## docs(docs): 夜2执行模式订正——双会话并行版计划书 + 首批急停现场落档（2026-09-06 第六十三笔）

执行 AI：GLM-5.3（主代理·计划）

- 用户 00:15 拍板：夜2集群执行模式改为**用户自开两个持久会话**（A=Android、B=Web）按仓库外《QimengNAS/派发任务书-20260905夜2.md》并行自驱（做完一批验收即接下一批，链条耗尽进调研批）；计划会话只出计划书不执行，其 23:40 试派的首批执行子代理已全部叫停，急停现场报告归档进蓝本「当前磁盘状态」节。
- **首批半成品留工作树未提交（后续会话续做，现场与恢复步骤见蓝本）**：B-1 文件管理增强代码基本完成（tsc/build 过、隔离实例 curl 全链证据在 %TEMP%\qimeng-b1*；含 b1-temp-orig/b1-temp-dirbrowser 两个 stash 待依序 pop）；M4-1 登录 core 层（network/data）写完但从未编译，settings.gradle 已 include 未建的 :feature:login——直接构建必失败，续做先建该模块骨架。
- 订正 HANDOVER「夜2执行中」口径为双会话并行模式；计划会话的心跳/收停自动化已删除（手动会话自守 08:30 硬停线）。
## docs(docs): 夜2集群启动落档——Android 导航四化 + M4-3 排版基准 + 无截图证据协议（2026-09-05 第六十二笔）

执行 AI：GLM-5.3（主代理·调度）

- 用户三处规格变更落档（夜间双车道集群，蓝本 = 仓库外《QimengNAS/派发任务书-20260905夜2.md》）：①**Android 导航 5 Tab→4 Tab**——「全部」更名「相册」（只改 label，route `all` 不变）、原「相册」Tab 删除，feature:album 空壳模块一并移除，M4-2 批落地；②**M4-3 详情页排版基准 = Web 现版**（B站式双栏移动端移植：媒体舞台→标题→meta 行→互动行→标签行→作者卡→接下来播放；手势/播放器规格照旧）；③**验收证据协议改无截图**（用户明令禁截图/录屏/视觉查看——敏感内容）：一律文本证据（uiautomator dump 文本树/logcat/curl JSON/单测输出/DOM 数值实测）。
- 能力地图漂移修复：备份域「缺口」→「已有」（导入端点 M3 + 导出端点 8344920 + 维护页备份卡）。
- 本笔纯文档落档，无代码改动。
## feat(api): 备份导入/导出——旧版格式导出端点 + 维护页备份卡；导入幂等修复（2026-09-05 第六十一笔）

执行 AI：GLM-5.3-Flash（主代理）

- **协议先行**：openapi 新增 `GET /api/v1/export/qimeng-backup`（响应 `LegacyBackupFile` 信封，复用 Legacy* 组件族不重复定义 17 段）→ make sdk 三端重生成。格式权威 = 旧仓库 DATA_MIGRATION_SPEC v1，段级口径入 DOMAIN_RULES §10（导出与导入互为镜像、可幂等回环）。
- **服务端**（httpapi/export.go + store/queries/legacy_export.sql）：mediaFiles 全量（recordKey 生成镜像导入 matchFiles 消歧：文件名 / 「名 @ 文件夹」/ 跨库同文件夹 #哈希）；dailyBrowse 取事件流物化表、mediaStats 事件流聚合、history 截 500、likes 聚合 {累计,最后日}；settings/scanSources/albumRules/cosWorks 恒空数组（导入端零警告）；appPrefs 只带 recommendationPrefs。
- **连带修复（回环测试暴露）**：导入端标签关联原复用业务 `AddAssetTag`（裸 INSERT），同库重复导入撞 UNIQUE 约束 500，与 §10「关联按唯一键 upsert 幂等」承诺相悖——新增 `ImportAddAssetTag`（ON CONFLICT DO NOTHING）替换。
- **web**：文件管理页新增「备份导入 / 导出」卡——导出=裸 fetch 原样字节 Blob 下载（不做二次序列化，复用 getAuthHeaders 旁路约定）；导入=lib/backup.ts 解析校验（format 前置校验早失败）→ 确认弹窗复刻旧版「检测到备份数据：X 个媒体文件 / Y 位作者 / Z 个标签 / W 条统计」语义 → SDK 导入 → 结果 toast（含 warnings 汇总）+ 资产/作者/标签/推荐/统计/排行/历史多根键失效。
- **测试与验证**：新增 TestExportQimengBackupRoundTrip（A 库导出 → B 全新实例恢复，段级计数与事件总量守恒断言 + 同批次重导幂等）与 TestExportHistoryCap500；go test ./... 全绿、tsc/build/lint 全绿。实机验证：导出 6325 文件/140 作者/6247 关联（2896 KB，同名消歧与 isCosFile 正确）；UI 导出 toast + 注入合成备份走确认弹窗后取消（真实库零变更）。全程零截图。
- **运维注意**：实机重启发现裸跑 `qimeng-server.exe`（cwd=server）会新建空 server/data 且 dev 模式关闭——服务端必须经 `启动服务端.bat` 启动（其设 QIMENG_DATA_DIR=qimeng-data + QIMENG_AUTH_DEV_MODE=1）；误启实例已清理。
- **回归修复（用户实测导入报 "Failed to fetch"）**：根因 = 全局 JSON 请求体上限 1MB（errors.go maxJSONBody，SECURITY 红线 6），真实备份 2.9MB 超限——MaxBytesReader 在服务端掐断连接时浏览器仍在上传，表现为 "Failed to fetch" 而非可读报错。修复：新增 `decodeJSONWithLimit`（per-route 限额 + 超限 413 TOO_LARGE），导入端点单独放宽到 `legacyImportMaxBody` 64MB（约数十万文件级库的余量；SECURITY 红线 6 的显式例外，已注记）；web 侧 lib/backup.ts 前置拦截同限额（BACKUP_MAX_BYTES 双写同步注记）。新增 TestImportBodyAboveGlobalLimit（~2MB 载荷导入成功）；隔离实例（18420+临时数据目录）用真实导出文件实测：2.9MB → HTTP 200、6325 文件清单 + 140 作者入账。真实库未做导入验证——导入语义是"新批次首导必回放事件"，往有统计的库导入会翻倍统计，导入只用于全新实例/迁移场景（§10 口径）。
## fix(web): 详情页自适应微调——1800 上限居中，4K 不再无限放大（2026-09-05 第六十笔）

执行 AI：GLM-5.3-Flash（主代理）

- 用户实测纯流式（第五十九笔）后反馈：内容跟着分辨率持续放大，4K 上主媒体区压迫感强、右栏相对过窄；1280×720 内置浏览器比例依旧是最好的。
- **微调**：`.detail-layout` 加回上限但换正确写法——`width: 100%; max-width: 1800px; margin-inline: auto`：≤1800 一律全填满（1080p 全屏内容区 ~1618 仍零空白），更宽（1440p/4K）整体居中不再放大。**width:100% 是关键**：flex 列子项只有 auto 边距会退化 fit-content 收缩（第五十九笔双侧留白教训），显式宽度下 auto 才是纯余量分配。右栏 `.detail-side` 改 `clamp(340px, 22vw, 400px)`——小窗 340 不动（1280 基准），大屏放到 400 平衡主区。上限 1800 是唯一调节旋钮（CSS 注释同记）。
- **四档实测（IAB 数值测量，visual px）**：1280×720 与原基准逐像素一致（96.8→1247.3 全填）；1920×1080 全填零空白（右栏 440）；2560×1440 触顶居中，左右各 225.3/225.2 对称；3840×2160 居中，左右各 865.3/865.2 对称，媒体区 1518（纯流式时 ~2900，压迫感解除）；还原 1280 后与基准一致。零截图。
## fix(web): 详情页自适应排版 + 视频全屏只显中间段修复；混合内容作者显示验证（2026-09-05 第五十九笔）

执行 AI：GLM-5.3-Flash（主代理）

- **详情页自适应排版（用户拍板要"自适应"，不要固定限宽）**：`.detail-layout` 去 `max-width: 1360px` 改纯流式填满内容区。两次实测教训都写进 CSS 注释防回退——①固定限宽：全屏时右侧留大片空白（用户原始反馈）；②中间版 `margin-inline: auto` 居中：flex 列子项带 auto 边距退化为 fit-content 收缩，实测内容区 1209px 布局仅 814px、双侧各空 ~195px（用户实测打回"现在左右都有空白"）。终版回归默认 stretch，任何窗口宽度都填满。
- **实测（IAB 数值测量，1280/1920×1080/2560×1440 三档视口）**：布局左缘=内容区 padding 线（96.8）、右缘=滚动条前可用边缘，三档 spread 均为 0；右栏贴右缘。全程按用户要求零截图，纯 DOM 数值验证。
- **视频全屏只显中间段修复（用户实机反馈）**：根因有二——①ArtPlayer 全屏的是播放器容器，`<video>` 仍是 `.asset-stage` 后代，`.asset-stage video { max-height: 68vh }` 在全屏态照常生效把画面压到屏高 68%、其余全黑；②全局 `html{zoom:1.1}` 被全屏顶层元素继承，UA 的 100%×100% 全屏尺寸再放大 1.1 倍致边缘裁切。修复（prototype.css 末尾追加）：`.asset-stage :fullscreen video { max-height: none }` + `:fullscreen { zoom: 1 }`。实测：进入全屏后 fullscreenElement=ArtPlayer 根、video 铺满 1280×720（修复前 max-height=490px）、computed max-height=none、zoom=1，ESC 正常退出。
- **混合内容作者显示验证（用户问①：作者同时有图片和视频；无代码改动，记录结论）**：取实库作者 M71Z30（62 文件=47 图+15 视频，TXT 关联 f384b69 修复后数据）——作者集合页 60 卡=15 视频卡（m:ss 时长角标）+45 图片卡（无角标）、全部有封面；图片详情页舞台渲染原图（4608×6144 居中 contain）、作者卡正常、「接下来播放」12 行全为图片（0 时长角标=同类型流）；视频详情页 ArtPlayer 舞台+作者卡+推荐全带角标。两种介质在三处入口（作者页/图片详情/视频详情）显示均正确。
## fix(server): 作者 TXT 匹配补扩展名检查——png/mp4 同基础名不再跨后缀污染（2026-09-05 第五十八笔）

执行 AI：GLM-5.3-Flash（主代理）

- **用户实机报告**：详情页「守望先锋  雾子 3.mp4」出现两位作者（cakiiBB + rwt4184）。查 TXT 原文：图集作者.txt 的 rwt4184 块写「雾子 3.png」、视频作者.txt 的 cakiiBB 块写「雾子 3.mp4」——本应各归各，实际两人同时关联了 png+mp4。
- **根因（用户指认方向正确）**：MatchWorks 翻译时丢失旧算法 `findMatchingMediaLight` 的扩展名检查（`if (hasExt) mediaExt == ext`）——基础名（去扩展名去空格）一致即命中，同名 png/mp4 跨后缀互相污染；且函数注释声称「作品名带扩展名时天然限定同名扩展名」与实现自相矛盾。连带发现既有测试 `RequiresSameExtension` 数据缺「同名不同扩展名」文件，没锁住该行为。
- **修复**（internal/authoring/match.go）：规则 1 拆两支——作品带媒体扩展名：基础名一致 **且文件扩展名一致**（小写域）；作品不带：基础名一致即命中。比较改 EqualFold 对齐旧 equals ignoreCase。规则 2（无扩展名作品的序号括号容错）不变。
- **测试**：新增 `TestMatchWorksExtCheckRealCaseKiriko`（实机数据回归：png/mp4 各归各 + 大小写不敏感命中）；补强 `RequiresSameExtension` 数据加 X.png。`go test ./...` 全绿。
- **存量修正**：重启实机服务后 POST /authors/import-txt/rebuild 统一重建（122 作者/689 关联），雾子 3.png→仅 rwt4184、雾子 3.mp4→仅 cakiiBB，rwt4184 名下与 TXT 完全一致。
- 文档：DOMAIN_RULES §6「关联方式」行补扩展名检查口径（用户实机拍板）。
## fix(web): 移除 hevc 编码兼容提示条（2026-09-05 第五十七笔）

执行 AI：GLM-5.3-Flash（主代理）

- 用户拍板：浏览器可直放 hevc，详情页顶部黄色「此视频编码为 hevc，当前浏览器可能无法直接播放…」提示条是常驻噪声，去除（元素级移除 + INCOMPATIBLE_CODECS 常量 + .codec-warn 样式段 + --codec-warn-* token 全清）。
- 播放失败兜底交回 ArtPlayer 自身错误态；项目「始终播放原件、不转码」约定不变。
- tsc / lint / build 全绿。
## feat(web): 详情页 B站式双栏排版大改——互动行/标签管理/作者卡/接下来播放 + 按钮类回归连带修复（2026-09-05 第五十六笔）

> 注：本条目标题曾丢失（29777c8 并发编辑把标题行误当替换锚删除），2026-09-06 审查清偿时按 85be719 原 diff 补回；正文未动。

执行 AI：GLM-5.3-Flash（主代理；executor 子代理因模型并发限流 4 次不可用，按兜底流程主代理亲自实现，researcher/reviewer 子代理正常派出）

- **排版（用户指定 B站详情页截图为参照，只学排版不学视觉，配色全走项目 token）**：详情页从「舞台+log-table」极简版重写为双栏——左主列=媒体舞台→标题（cosWork ?? fileName 与卡片同口径）→meta 行（浏览·播放·大小·尺寸·日期·出处）→点赞/收藏互动行→标签行；右栏=作者卡（displayName+·COS 标识+关注按钮，作者名点击进作者文件页，无作者不渲染）+「接下来播放」行式推荐栏（缩略图+时长角标+两行标题+作者副行；排除当前资产；换一批=换 seed；数据源=同类型 recommendations 流，协议无相似推荐参数，用户拍板口径）。窄窗 ≤1000px 单栏降级。新组件 `components/detail/{AuthorCard,AssetTagRow,UpNextList}.tsx`，新样式段 `.detail-*` 前缀追加 prototype.css 末尾。
- **新 hooks**（use-assets.ts）：useToggleLike（PUT like 无 body，响应 LikeState 经 patchDetail 即时回填详情缓存+资产根键失效）/useSetFavorite（显式值非 toggle）/useReplaceAssetTags（整体替换+标签池失效）/useUpNextList（recommendations 单页，seed 入缓存键，'upnext' 子族不与首页数字键碰撞，SSE 根键失效天然覆盖）。
- **标签管理弹窗**（radix Dialog 统一包封装）：标签池点选+新建（新建自动入勾选），保存一次整体替换提交（DOMAIN_RULES §7 口径，保留项一并带上）；弹窗按 open 条件挂载，勾选态 useState 惰性初始化即复位（规避 set-state-in-effect lint）。
- **动效纯 CSS 零新依赖**：按钮 hover 提亮/active 0.94 缩放、点赞图标弹跳 keyframes（onAnimationEnd 复位支持连点重触发）、点赞/收藏服务态主色实底+图标填充、推荐行 hover 提亮、换一批图标旋转、弹窗 radix 自带动画。
- **打点三件套保真 + reviewer P1 修复**：open/play/dwell 与播放进度上报原样搬移；reviewer 全新上下文对抗审查 1 轮打回——P1=UpNextList 的详情→详情导航同路由不重挂载，open 打点的 `useRef(false)` 守卫永不重置→新资产 open 永不上报（旧页面无此入口，本批新引入的路径）；修为 `reportedFor.current` 记录已上报资产 id，服务端实证直进与右栏跳转两资产 viewCount 均=1。
- **连带修复 671db67 的 `.layout button` 重置存量回归**：该重置 (0,1,1) 压过全部单类按钮的 border/background——本批浏览器实测发现作者卡 `.follow-btn--idle` 白底透明不可见，顺藤排查同病类并一次根治：pill/seg/more-filter/follow-btn(--idle)/save-btn/confirm-btn--cancel/--primary/--danger 及本批 .detail-act 共 14 处选择器加 button 前缀提级（沿用 select-trigger 先例）；span 消费的 .pill 用选择器列表双写保住非按钮用法。reviewer 另打回 P3×2 已修：管理按钮手型被 `.detail-tags .pill` (0,2,0) 压死→提为 `button.pill.detail-tag-manage`；`.upnext-thumb` 补 `.dark` 深色占位（照 `.dark .card--cover` 既有模式）。
- 验收：tsc / build / lint 全绿（新文件 0 告警）；隔离实例（18430+临时数据目录，未碰真实库）curl 9 项（like toggle 计数与当日态翻转、favorite 204 双向、tags PUT 后详情变化、follow 204、recommendations 非空）+ 浏览器全链路（浅/深/窄窗三态截图、点赞收藏关注状态回填、标签勾选保存、换一批重排、打点存活、对齐实测主列 spread=0.0/舞台顶 83.1 与首页贴顶栏节奏一致）。
- 遗留：图片查看器（缩放/沉浸）与列表上下文批次导航仍按用户拍板后置（HANDOVER_UI §5.9 发现项）；测试纪律补账——**PWA Service Worker 缓存旧构建**，改前端重 build 后浏览器须清 SW/缓存再验（本次踩坑：computed 样式陈旧与 fullPage 截图错乱皆源于此，已记 HANDOVER_UI）。
## feat(web): 悬浮按钮组——刷新 FAB 上移让位 + 回顶部按钮滚动出现 + FAB 透明底根治（2026-09-05 第五十五笔）

执行 AI：GLM-5.3-Flash（主代理，用户实机拍板「替代现在的刷新：取消透明、下滑后出现下面那个顶部，阈值实现自定」；参考图为旧版半透明样式，新版不复制透明）

- **FAB 透明底根治（「取消透明」的实锤）**：用户报告属实——`.layout button` 重置（`background: none`，特异度 0,1,1）压过单类 `.refresh-fab`（0,1,0）的 `background: var(--bg)`，浅色模式 FAB 底色自上线起一直 computed 为 rgba(0,0,0,0)（内容从按钮里透出，仅靠阴影撑形态）；暗色 `.dark .refresh-fab`（0,2,0）侥幸压过故只有暗色正常。修复：FAB 基础规则作用域提升 `.layout .refresh-fab` / `.layout .backtop-fab`（0,2,0），实测底色 rgb(255,255,255)。
- **回顶部按钮**：新增 `.backtop-fab` 固定右下 24（实心▲ +「顶部」文字，BackTopIcon 对齐参考样式），内容区滚动 >400px（AppShell `BACK_TOP_THRESHOLD`，阈值实现定的）淡入上移出现，点击平滑滚回顶部，回顶自动隐藏；隐藏态 pointer-events none + tabIndex -1 不可误触；短内容页无溢出永不出现（正确行为）。
- **刷新 FAB 上移让位**：bottom 24→88（24 底距 + 52 顶钮高 + 12 间距），位置固定不随顶钮显隐跳动。
- 滚动容器为 .content（window 不滚），监听挂 AppShell contentRef（passive）。
- 验收：tsc -b / build / make lint 全绿；浏览器实测——滚动 600 → opacity 1 / 落位 bottom 26.4，点击 → scrollTop 0 + 自动隐藏，双 FAB 底色 rgb(255,255,255)，刷新钮位置全程不跳。
## UI 微调：热榜首行卡片顶边对齐侧栏「相册」项（2026-09-05 第五十四笔）

执行 AI：GLM-5.3-Flash（主代理，用户实机拍板「这俩对齐」——侧栏相册项 ↔ 热榜首行卡片）

- 背景：「首行贴侧栏节奏」调参块（prototype.css 1069 行）老规则为「高卡片顶沿贴顶栏下缘(75)」，但热榜 tab 的周期行（第五十二笔）移到顶栏下方后占住了「首页」带，卡片流被 -11.5px 老负边距拉到贴死周期行（间隙 0），顶边高出侧栏「相册」项 13px。
- 修复：HomePage 三个 tab 共用一个 .grid 容器，tab=hot 时加 `grid--hot` 修饰类；CSS 新增 `#page-home .grid--hot { margin-top: 1.5px }`——112.5(内容区顶) + 12(内容区上内边距) + 1.5 = 126 = 「相册」项顶边（未缩放坐标）。推荐/cos 无周期行，维持「贴顶栏下缘」老规不动。
- 验收：tsc -b / build / make lint 全绿；浏览器 JS 实测（无截图）热榜首行卡片顶边 = 相册项顶边，推荐 tab 卡片位置不变。
## UI 错位收尾：radix 弹层 zoom 反向补偿 + 排行榜周期行中心回校（2026-09-05 第五十三笔）

执行 AI：GLM-5.3-Flash（主代理，接续 sess_469c58ec 错位修复批次；用户实机确认搜索面板已对齐，全程禁截图，以用户贴回的元素 Rect 与 CSS 几何模型互证）

- **radix 弹层二次放大（元素 1/2）**：全局 `html { zoom: 1.1 }` 对 body 级 Portal 弹层二次应用——Floating UI 给的是视觉坐标，写进 zoom 上下文又乘一遍 1.1（实测面板 x=614→675、宽 370→407）。补 `body > [data-radix-popper-content-wrapper] { zoom: 0.9090909 }` 反向抵消，搜索建议面板与五个 Select 下拉同类弹层一并恢复精确落位；用户实机确认搜索面板已对齐。
- **排行榜周期行中心错位（元素 3/4）**：侧栏「首页」项中心（未缩放坐标 94）应与周期行胶囊中心同线（既有拍板），但胶囊行高 37（14px 字号 × 1.5 行高 + 8px × 2 内边距）较当初调参假设的 34 高 3px，中心下沉 1.5px（实测 rect 中心 103.5 vs 105.5，渲染坐标）。`.rank-panel` 上内边距 2px→0.5px 回校：75(顶栏) + 0.5 + 37/2 = 94。旧会话「rank-panel 水平内边距 24 与内容区 40 不一致」假说证伪——系把 `padding: 12px 24px 40px` 的下边距误读成水平值，实测两者左缘同为 88，水平对齐本就成立。
- 验收：tsc -b / build / make lint 全绿；无截图，以用户贴回元素 Rect 与 CSS 几何模型互证。
## 首页排版三修：搜索面板对齐 + 排行榜卡片流统一（2026-09-05 第五十二笔）

执行 AI：GLM-5.3-Flash（主代理，用户实机反馈三点：面板没和搜索栏对齐 / 内容榜标题壳去除 / 排行榜排版与推荐一致）

- **搜索面板对齐**：PopoverAnchor 从 .search 包裹层（含图标区域，居中定位致面板右偏 80px）移到输入框本身，align 改 start，.search-pop--popper 宽度取 radix 注入的 anchor 宽——面板与输入框左对齐且同宽。
- **排行榜排版统一**：hot tab 弃 ContentRankGrid 紧凑榜单卡与 rank-card「内容榜」标题壳，改用与推荐/cos 完全相同的 MediaCard 卡片流（StreamCards 复用：触底增量/去重/到底提示/刷新语义全保留）；ContentRankGrid 组件保留（数据页 Top5 与完整榜单页仍在用）。
- 验收：tsc -b/build/make lint 全绿（17 告警既有存量）。
## UI 组件库化与共享组件收拢（2026-09-05 第五十一笔）

执行 AI：GLM-5.3-Flash（主代理派发双路研究 + 三 executor 并行，全过程三次基础设施故障均按兜底续跑恢复；reviewer 全新上下文对抗审查通过）

用户拍板「UI 尽量不手搓、组件库能用就用；同一实现收共享组件」。双路审计（手搓控件清单 + 重复实现簇）定位四类问题，三执行器并行清偿（文件互斥）：

- **E-C 控件库化**：新建 ui/popover|select|slider|switch 四组件（radix-ui 统一包 + confirm-dialog 范本口径：headless 行为层 + prototype 类样式，零 tailwind/零颜色字面量）。TopBar 搜索下拉 Popover 化（手写 document click 监听删除，白得 ESC/焦点管理；面板内容逻辑一字不动）；Switch×2（库启用/自动接收上传）、Select×5（上传目标库/目录浏览/库类型/年份区间×2）、Slider×9（推荐偏好 9 维）全部接线，原生 checkbox/range/select 零残留。
- **E-B 收拢**：新建 LoadMorePill（5 处手动翻页形态统一，when 参数消化各页渲染条件差异）与 Pill（9 处 className 拼接收拢）；四页 `pill${` 拼接 grep 清零。与首页 useAutoMore 触底自动是两种并存策略，注释互指。
- **E-A 收拢**：chart-shared.tsx（TIP_STYLE + TrendLegend，修正上批 recharts 迁移的双写遗留）；lib/rank-rows.ts（榜单行加工纯函数，DOMAIN_RULES 口径注释单源——DataPage Top5/RanksPage 全量共用）；lib/pagination.ts（lengthCursorNext 游标判据，use-assets/use-stats 共用）。
- **明确不做**：环形图两套（DonutCard N 扇区 vs gauge 单值环，形似语义不同，仅注释互指）；纯样式 tabs/胶囊（无行为逻辑）；SearchFilters/SettingsPage 的 pill 拼接（本轮范围外）。
- **验收**：reviewer 亲跑门禁（tsc -b/build/lint 17 告警 0 错误）+ 六组 grep + 逐文件 diff 对照，全部 PASS；行为差异备案两处——TopBar 下拉新增 ESC 关闭（增强）、Select 无法选回空 placeholder（radix 语义），均与基线一致。MaintenancePage Tooltip labelStyle 视觉无差异为静态论证，待实机目检。
## 折线图换 recharts：悬停提示库内置，弃手搓覆盖层（2026-09-05 第五十笔）

执行 AI：GLM-5.3-Flash（主代理；用户实机验收第四十九笔拍板「没和曲线对齐，干脆换个自带这个效果的来使用」）

手搓 HTML 覆盖层的提示点与曲线对不齐（SVG preserveAspectRatio=none 拉伸坐标系里，光标位置与最近采样点在两层之间有偏差）。按铁律 9 引入新依赖同步写 ADR-0016：选型 recharts 3.10.1（npm 最新稳定，3.x 为 React 19 原生支持线，官方文档核对 Tooltip props；落选 Chart.js/ECharts/uPlot，理由见 ADR）。

- 数据页趋势图与维护页网络图改 `LineChart/Line/Tooltip/XAxis/YAxis`：悬停提示、竖向参考线（cursor 虚线）、高亮点（activeDot）库内置、精确吸附采样点；主题色直接引用 --qm-primary/--trend-line-sub 变量；x 轴日期刻度由库自动抽稀（替代手写 label 采样行）。
- 维护页网络图 `isAnimationActive={false}`——2s 滚动刷新防动画重放闪烁。
- 删除手搓模块 components/data/trend-hover.tsx 与 prototype.css 对应覆盖层样式（.trend-legend 图例保留）；样式注释头同步。
- 验收：tsc/build/make lint 全绿（16 告警既有存量）；悬停对齐性由库保证，待用户实机目检。
## 折线图悬停数值反馈 + 图例（2026-09-05 第四十九笔）

执行 AI：GLM-5.3-Flash（主代理，用户实机拍板「没有数值、鼠标过去也不显示数据，要悬停显示所在位置数据」）

数据页「浏览与播放趋势」与维护页「网络负载」两张折线此前只有两条曲线，无数值无反馈。对齐旧版 LineChartView「气泡含系列名」语义（web 桌面端用悬停对应触屏气泡）：

- 新建共享模块 `components/data/trend-hover.tsx`：useTrendHover（光标 x 比例 → 最近采样下标，两图共用）+ TrendHoverOverlay（竖向参考线 + 各系列高亮点 + 顶部跟随的数值提示条，靠边自动水平翻转）。参考线/圆点/提示全用 HTML 绝对定位——SVG 为 preserveAspectRatio=none 拉伸，内部元素会变形。
- 数据页：悬停提示 = `分桶 label · 浏览 N · 播放 M`；维护页：`下行 X/s · 上行 Y/s`（formatBytes 口径，与卡片数值一致）。
- 两图补顶部图例（浏览/播放、下行/上行，色点与折线 stroke 同源 token）——旧版顶部图例语义补齐。
- 样式追加 prototype.css 尾部 trend-* 类（全走既有 token，零颜色字面量）。tsc/build 绿。
## 搜索补全接线 + 推荐搜索随机化：对齐旧版搜索语义（2026-09-05 第四十八笔）

执行 AI：GLM-5.3-Flash（主代理派发 executor 子代理端到端实施，主代理验收收尾）

用户拍板「搜索逻辑和推荐显示和旧版不一致，修一下做个补全」。查实两处偏差：① `/search/suggestions` 端点（S-1 建）**web 前端零接线**——旧版输入时的五维补全列表在 web 缺失；② web 空态「推荐搜索」用标签/作者按文件数 Top N（每次固定），旧版是「名字索引随机取 10 条」。检索维度本身（文件名/目录段/标签/角色/作者/出处，asset_search_text 视图）已对齐旧版，不动。

- **协议**：/search/suggestions 增 `recommend`（default false；true 且 q 空=随机五维候选 ≤limit；q 非空时忽略照常子串匹配）；make sdk 三端重建。
- **服务端**：suggestions.sql 增五随机池查询（口径逐一照抄既有 UNION 分支；DISTINCT 套子查询规避 SQLite compound SELECT 的 ORDER BY 表达式限制）；handler 随机分支按池轮转交错合并——任一维缺货由其余维填满、五维有货时天然混合。新用例：recommend 随机（type 全法值、(type,name) 唯一、limit=50 全集精确、幽灵作者不入池）与 q 非空时 recommend 被忽略。
- **Web**：新建 hooks/use-suggestions.ts（useSearchSuggestions 输入态 + useRecommendSearchWords 空态，后者 staleTime=0——每次打开面板换一批，随机语义的一部分）；TopBar 输入非空时下拉切换为补全列表（名称+右侧类型徽标，点行=填入并搜索，200ms 防抖、TanStack queryKey 隔离替代 AbortController）；空态推荐词改接 recommend 端点（删 Top N 逻辑）；prototype.css 尾部追加 pop-suggest-* 类（全走 token）。
- **验收**：go test 14 包全绿、make lint 0 错误（16 告警既有存量）、tsc/build 绿；实机见同批验证记录。
## UI 微调：内容榜数值角标去除（2026-09-05 第四十七笔）

执行 AI：GLM-5.3-Flash（主代理，用户实机拍板「内容榜的这个次数去除」）

ContentRankGrid 封面上的 rank-views 次数角标删除（该组件为数据页 Top5 / 完整榜单页 / 首页排行榜 tab 三处共用，统一去除保持同一视觉口径）；热度排序已由卡片顺序表达，不再叠加次数文本。作者榜/作者总览行等文本行的计数字段是榜单度量本体，不受影响。
## UI 微调：TXT 导入卡去重，导入区成唯一入口（2026-09-05 第四十六笔）

执行 AI：GLM-5.3-Flash（主代理，用户看实机拍板「下面的隐形入口去掉，上面那个保留」）

第四十四笔 E4 交付的「虚线导入区 + 主色按钮」双入口，用户确认嫌重复。删除按钮行中的「选择 TXT 导入」save-btn（重新匹配 pill 与隐藏 file input 保留），导入区成为唯一入口；补齐键盘可达性（tabIndex + Enter/Space 触发，对齐原生 button 语义，抵消删真按钮的可访问性损失）。功能逻辑零改动。tsc/build 绿。
## 运维：服务端改固定路径构建 + 端口级防火墙规则，根治 Windows 防火墙反复弹窗（2026-09-05 第四十五笔）

执行 AI：GLM-5.3-Flash（主代理，用户报障「防火墙允许弹窗经常出现，怕卡住任务进度」）

根因：`启动服务端.bat` 用 `go run ./cmd/qimeng`——每次启动都把二进制编译到**新的随机临时目录**（%TEMP%\go-buildXXXX\...\qimeng.exe），Windows 防火墙每次都视为陌生未签名程序弹「允许访问」；用户点允许生成的程序路径规则随即失效（指向已死的临时目录），下次启动再弹。实测机器上累积了 40 条指向死路径的 qimeng.exe 规则。

- **启动脚本**：`go run` 改为 `go build -o qimeng-server.exe ./cmd/qimeng` 后运行（exe 路径固定 = 规则永远命中；构建失败时红字提示并 pause 驻留窗口）；`*.exe` 已在 .gitignore 无需变更。
- **防火墙（管理员）**：删除 40 条死路径 qimeng.exe 旧规则；新增端口级入站规则「Qimeng Media Server 8420」（TCP 8420，**profile=专用**）——端口规则不随二进制路径变化失效，任何构建产物监听 8420 都不再弹窗。**仅放行专用网络**：SECURITY.md 红线 8「永不开公网端口」不受影响（公用网络仍拦，远程访问继续走 Tailscale 隧道方案）；用户当前网络即专用档，手机同 WiFi 访问不受影响。
- **验证**：新 bat 启动后 `server/qimeng-server.exe` 稳定监听 8420（40s 存活观察 + healthz 200）；首次重启出现过一次新旧进程交替的偶发绑定竞争（未复现），bat 窗口会驻留报错便于查看。
## Web 四修：刷新广播/COS 作者反查/作者页筛选胶囊/TXT 导入卡（2026-09-05 第四十四笔）

执行 AI：GLM-5.3-Flash（主代理派发 executor 子代理×3 并行执行；对抗审查通过后由主代理完成返工项）

用户拍板六任务批的 web 侧余项（E1 协议链见第四十三笔）：

- **E2 全局刷新**：AppShell refresh 广播 `qm:refresh`（invalidate 保留，普通页重拉 + 首页重排双通道）；返工项——事件名字面量 3 处手抄违反代码卫生约束 2，提为 lib/constants.ts QM_REFRESH_EVENT 单一来源（AppShell/HomePage 两端改引）。
- **E3 作者链路**：CollectionPage COS 作者反查两段式修复（剥「·COS」后缀，字节级对齐 authorDisplayName）；新增筛选胶囊栏（常规=角色/类型、COS=作品/类型，facets 计数排自身、authorId 恒传；单选受协议单值参数约束，注释记账）；作者切换改渲染期重置（返工建议项，清 set-state-in-effect 告警，lint 告警回落 16 条既有存量）；AuthorsPage 行点击进文件页 + 删「浏览 M 次」；DataPage 作者总览 sub 改「N 个文件」。
- **E4 TXT 导入卡**：导入入口复用 .upload-drop 虚线焦点区（图标+状态感知提示+说明小列表化），onPick/importTxt/rebuild 逻辑零改动。
- **验收**：tsc/build/lint（16 告警 0 错误）/go test 全绿；reviewer 八项质询逐项 PASS（COS 后缀 od 字节级核对、offset=0 等价性 diff 对照、facets 排自身对照 AlbumsPage、越界清单零命中）。
## 首页三 tab 无限加载：/recommendations 与 /rankings 增 offset 翻页（2026-09-05 第四十三笔）

执行 AI：GLM-5.3-Flash（主代理派发 executor 子代理执行，完整档六任务批之 E1；对抗审查通过后由主代理完成返工项）

用户拍板六任务批（并行子代理模式）之一：首页推荐/cos/排行榜三 tab 统一「下滑自动加载更多 + 刷新全量重排」（对齐旧版 GUIDE_UI：距底 ≤6 项提前加载不重排、下拉刷新 refreshSeed++、每日惩罚照跑）。此前两端点返回裸数组无分页字段，首页一次拉 60 到底即止。

- **协议**：两端点各增 `offset`（default 0 / minimum 0，与 limit 组合翻页切片；推荐流注明每次请求按当下打分排序、同流翻页由客户端按 assetId 去重兜底）；make sdk 三端重建（android 无需改调用，offset 可选）。
- **服务端**：handler 增 resolvePageOffset（负数 400，风格同 pagination.go）；推荐流 Recommend(Limit=offset+limit) 后 slicePage 切片、IncrementDailyShown 只回写返回项；rankings 同款切片（offset=0 与改前逐字节等价）。新增用例：翻页并集==全量、负 offset 400、rankings offset 翻页。
- **Web**：useRecommendations 改 useInfiniteQuery（queryKey 根不变，SSE 失效仍覆盖）；新增 useRankingsInfinite 与 use-auto-more.ts（IntersectionObserver 哨兵，rootMargin=6×220px 提前量常量）；HomePage 三 tab 触底加载、推荐/cos 渲染前按 assetId 去重（服务端重打分跨页漂移兜底）、hasNextPage 用原始页长判据；qm:refresh 监听（推荐/cos seed=Date.now() 重排回第一页、排行榜 reloadKey 重置）——事件名经对抗审查返工提为 lib/constants.ts QM_REFRESH_EVENT 单一来源（AppShell 广播端同批接线）。
- **验收**：go test 14 包全绿、make lint 0 错误（告警回落 16 条既有存量）、tsc/build 绿；reviewer 全新上下文对抗审查（门禁亲跑 + offset 语义/契约/回归/越界八项质询）仅 1 硬项（事件名字面量 3 处手抄）返工闭合。
## COS 卡片标题 + COS 推荐模式：首页 cos tab 两缺口修复（2026-09-05 第四十二笔）

执行 AI：GLM-5.3-Flash（主代理，用户报障「COS 卡片应显示作品文件夹名」「cos tab 好像没做算法」后查实并拍板执行）

两个缺口均查实为移植期偏差：① `assetToCard` 标题恒取 fileName，且协议 AssetSummary 根本没有 cosWork 字段（库里 `assets.cos_work` 落库正常，实测 facets COS 分区 152 作品行有值）——前端想显示也拿不到；② 旧版首页 COS chip 是「COS 推荐模式」（GUIDE_UI 首页节原文），web 移植时做成了 `GET /assets cosOnly=true` 的 addedDate 降序纯浏览流，十维算法未接入。

- **协议**：openapi AssetSummary 增可空 `cosWork`（描述=客户端卡片标题优先取本字段、null 回退 fileName）；GET /recommendations 增 `cosOnly`（true=COS 推荐模式，候选集限定 COS 关联资产，同套打分/权重回收/每日惩罚照跑；false=常规流缺省排除不变）。`make sdk` 三端重建。
- **服务端**：`recommend.sql` ListAssetsRecommendInput 的 COS 排除谓词改双分支（cos_only=1 走 EXISTS、=0 走 NOT EXISTS，恒两值无 NULL 三值逻辑）；rankings.go 复用同查询，显式补传 CosOnly:0（排行榜维持既有排除 COS 口径）——**首跑全量测试即被 rankings 三用例抓到漏传 NULL 整库排除，此坑注释有预警仍踩中，调用方约束升级为编译期可见的显式传参**；assets.sql 增 ListCosWorkForAssets（json_each 批量，同 ListAuthorNamesForAssets 模式）+ `fillListCosWork` 装配（/assets 与 /recommendations 两出口）；**顺带修存量缺口：UpsertAsset DO UPDATE 列表漏 `cos_work`，与 SQL 注释「Refreshed on conflict like source」明文承诺不符**，补列对齐。
- **Web**：`assetToCard` 标题改 `cosWork ?? fileName`（共享组件，相册/收藏/搜索的 COS 卡统一生效）；首页 cos tab 换 `useRecommendations(60, seed, true)` COS 推荐流并新增「换一批」（seed=Date.now() 重新打散；推荐 tab 无此按钮系现状，不在本批扩围）；useRecommendations 签名扩 seed/cosOnly（queryKey 同步入 key）。
- **测试**：新增 TestRecommendationsCosOnly（常规流隔离+COS 模式候选集+cosWork 填充/平铺 null 四断言）与 TestAssetListCosWork（/assets 出口装配）；`go test ./...` 14 包全绿、`make lint` 全绿（TS 15 警告既有存量）、`npx tsc --noEmit` 通过、web build 产物更新。
- **实机验证**（8420 重启后）：/recommendations?cosOnly=true 返回 COS 资产且 cosWork 有值（萝莉身材/NO.324 沙希女警/修道院等），常规推荐流无 COS 资产无 cosWork；/assets?cosOnly=true 三条样本 cosWork=萝莉身材。
- **文档**：DOMAIN_RULES §6（COS 卡片标题口径 + cos tab=COS 推荐模式，原纯浏览流口径废止）、GUIDE_API（/recommendations 参数行、cosWork 字段条目）。
## 文档漂移修复：PROJECT_PLAN M4 脚手架条目补勾（2026-09-05 第四十一笔）

执行 AI：GLM-5.3-Flash（主代理）

M4-0 工程基建已于 2026-09-05 交付（cdc422e，CHANGELOG 第三十二笔），但该 commit 对 PROJECT_PLAN.md 只更新了头部门禁描述（CI 四 job→五 job），M4 第一项「项目脚手架」勾选框漏勾——违反变更纪律「每完成一项勾选一项并注明 commit」。本次补勾并注明 commit（核对依据：HANDOVER_APP 批次表 M4-0 ✅ + android/ 工程骨架与 `:sdk` 生成物在盘 + commit 交付清单逐项对应）。纯文档修复，无代码改动。

- `docs/PROJECT_PLAN.md`：M4「项目脚手架」条目 [x] 并注明 commit cdc422e 与交付内容；头部「最后更新」补 2026-09-05 行。
## 服务端 M6 前置：ffmpeg/ffprobe 二进制路径配置化（2026-09-05 第四十笔）

执行 AI：GLM-5.3-Flash（执行子代理，S 车道 S-3 批次）

M6 单机形态前置小改造（依据 ADR-0015 + 仓库外 m6-ffmpeg-memo/m6-poc 预研）：服务端进手机后 ffmpeg/ffprobe 打包在 App 的 nativeLibraryDir，进程 PATH 未必可达，二进制路径不能再靠裸命令名自动发现。**契约：默认空 = 裸命令名走 PATH 自动发现，缺省行为零变化**；纯配置扩展，无 migration。

- **config**：`ThumbnailConfig` 增 `ffmpeg_path`/`ffprobe_path`（yaml 键 + env `QIMENG_THUMBNAIL_FFMPEG_PATH`/`QIMENG_THUMBNAIL_FFPROBE_PATH` 覆盖，沿用「默认值 < yaml < env」既有惯例；字符串直覆盖无非法值）。
- **thumbnail**：新增命名常量 `DefaultFFmpegBin`/`DefaultFFprobeBin`（裸命令名单一来源）与 `resolveBin` 解析（显式配置优先/空回退自动发现）；解析收敛在 `NewGenerator` 单点——`Options` 增 `FFmpegPath`/`FFprobePath`，Generator 持有解析结果，ffmpeg/ffprobe 裸命令名全部 6 处调用点（抽帧/首帧/封面流/缩放/灰度采样/探测）改走 Generator 方法取用；`ProbeVideo` 拆为 `probeVideo(ctx, ffprobeBin, path)` 核心 + 包级 `ProbeVideo`（裸名回退语义）+ `(*Generator).ProbeVideo`（配置路径出口）。
- **接线**：`scanner.New` 增 probe 参数（nil = 默认 `thumbnail.ProbeVideo` 回退语义），main 注入 `thumbs.ProbeVideo`——扫描入库与上传探测（httpapi upload 改走 `s.thumbs.ProbeVideo`）与缩略图管线共用同一 ffprobe 配置来源，路径只在装配处解析一次。
- **测试**：thumbnail/binpath_test.go 锁两分支——「显式配置优先」（配置路径原样进 exec，构造字段与 exec 错误信息双重实证）与「缺省回退自动发现」（裸命令名进 exec）；config_test.go 锁默认空值/yaml 覆盖/env 优先；ffmpeg_integration_test.go 适配方法化签名（真 ffmpeg 行为断言不变，本机实跑通过）。
- **验收**：`cd server && go test ./...` 全绿（含 thumbnail/config 现跑）；`make lint` 全绿（exit 0，TS 15 条警告为既有存量）。
## CI 修复：android/gradlew 补回可执行位——Android 门禁 job 存量红（2026-09-05 第三十九笔）

执行 AI：GLM-5.3-Flash（主代理）

W-4 批次交付时由执行代理发现、移交主代理处置的仓库级基础设施问题：CI Android job 自 M4-0（cdc422e）起持续 `./gradlew: Permission denied`（exit 126）——`android/gradlew` 入库时丢可执行位（git index 100644），Linux runner 上 wrapper 无法执行，此后所有 push（含纯 docs 提交）的 CI 全红。

- **修复**：仅改 index 文件模式为 100755（`git update-index --chmod=+x android/gradlew`），文件内容零改动；不涉三端代码与 workflow 逻辑。
- **验证**：run 33918995510（本 commit 触发）五 job 全绿——Android job 7m31s 正常执行 wrapper，存量红清零，后续 push 恢复正常门禁。
## Web 端 play/dwell 行为打点补齐：playCount/浏览时长恢复 web 侧供数（2026-09-05 第三十八笔）

执行 AI：GLM-5.3-Flash（执行子代理，W 车道 W-4 批次）

W-4 批次（打点缺口补齐，任务书=仓库外《QimengNAS/派发任务书-20260905夜.md》）。**断点定位结论**：M2 记录「DetailPage open/dwell 打点」与实际不符——`useReportView`（hooks/use-assets.ts）三种 kind 均支持，但全库唯一调用点是 AssetDetailPage 进页的 `open`，play/dwell 从未接线（040df17 全库 playCount/浏览时长为零的直接原因，属"从未实现"而非"路径未生效"）。

- **play**：`components/media/video-player.tsx` 新增 `onPlay` prop（`art.on('play')`；已核对 5.4.0 dist——该事件仅由 `art.play()` 发出，UI 大播放键 `.art-state`/控制条/空格键全走该路径，原生兜底层是 `video:play` 前缀事件不混用）；AssetDetailPage 每次起播如实逐条上报，同会话当日去重由服务端 202 幂等吸收（DOMAIN_RULES §5）。
- **dwell**：新增 `hooks/use-dwell-report.ts`——进入详情页计时，离开（卸载/详情→详情切资产）与页面隐藏（visibilitychange hidden + pagehide 兜底）flush 恰好一条；segmentRef 单点持有、取走即置空（同段重复 flush 一律 no-op，防累加口径时长虚增）；隐藏期间不计停留、回可见开新段；<1s 停留段不上报（秒数四舍五入后为 0，防零值噪声事件，数值口径不受影响）。图片与视频通用（挂详情页层级，组件零参与）；sessionId 沿用 sessionStorage UUID（ensureSessionId）。
- **隔离实例实机验收**（端口 18420+临时数据目录+dev-login+ffmpeg 造数 1 图 2 视频，未触碰 8420 真库）：headless Edge 走真实 UI 路径（点击 `.art-state` 起播）——视频起播→停留 13s→SPA 离开→图片停留 7s→离开→二次进入视频页模拟 hidden/visible 分段 3s+6s；8 条 `POST /events/view` 全 202；`GET /stats/trends?range=7d` 当日 `seconds=29`=13+7+3+6 精确吻合（无重复无遗漏）、video-a viewCount=1（两次 open 会话去重生效）/playCount=1、image-a viewCount=1/playCount=0、`GET /stats/overview` totalViews=2、`GET /rankings?period=day` 恢复供数（video-a 居首）。截图 %TEMP%/qimeng-w4-shots/（6 张：首页/起播前/播放中/离开后/图片详情/二次进入）。
- **验收**：`npx tsc --noEmit -p web/tsconfig.app.json` 0 错；`npm --prefix web run build` 成功；`npm --prefix web run lint` 改动 3 文件 0 告警（全库存量 15 条不动）。
- **文档**：HANDOVER_UI.md §5 第 13 条标记闭合 + 文头更新行。
## 服务端协议扩展 S-2：AssetSummary/AssetDetail 增 likedToday 点赞初始态字段（2026-09-05 第三十七笔）

执行 AI：GLM-5.3-Flash（执行子代理，S 车道 S-2 批次）

Q2-1 拍板（A 方案）落地：给 M4-3 详情页点赞按钮提供初始态字段，协议先行三端生成。

- **协议**（api/openapi.yaml）：AssetSummary 增可选布尔 `likedToday`，注释写明口径=当日（本地日历日）是否已点赞（每资产每日一次、次日重置，与 `PUT /assets/{assetId}/like` 响应 LikeState.likedToday 同口径）；AssetDetail 经 allOf 继承 Summary 同步获得（单点声明，避免生成物重复字段）。`make sdk` 全链重建通过（redocly → oapi-codegen → hey-api TS → openapi-generator Kotlin），三端生成物均含新字段。
- **服务端接线**：① 列表出口 `GET /assets`——`fillListLikedToday` 页大小一次批量查询二次装配（同 fillListAuthorNames 模式），批量查询 `ListLikedTodayForAssets` 新增于 queries/likes.sql（HasLikedOnDay 的 IN 批量形式，同 likes 表同 day 口径，sqlc v1.31.1 重新生成）；② 详情出口 `GET /assets/{id}`——fetchAssetStats 直接复用点赞端点既有查询 `HasLikedOnDay`（day=store.FormatDay 本地日历日）。
- **测试**：browse_test.go 增 `TestAssetLikedToday`——今日已赞/曾赞非今日/从未赞三态 × 列表/详情两出口，含"曾赞非今日 → likedToday=false 但 likeCount 保留"口径锁定与取消今日赞后两出口联动回 false；隔离实例（端口 8466 + 临时数据目录 + dev 模式，未触碰 8420 真库）curl 实测三态两出口全部正确。
- **文档**：GUIDE_API.md「关键机制」增 likedToday 字段说明 + 头部更新行。
## 晨间拍板落档：Q1~Q4 全按建议——W-4 web 打点车道开跑、M4-3/M4-6 解锁、用户手动派发模式（2026-09-05 第三十六笔）

执行 AI：GLM-5.3（主代理，ZCode 调度）

用户晨间一次性拍板四组全部按建议（详细记录=仓库外《QimengNAS/待拍板-20260905午.md》，已从询问报表转为拍板记录）：

- **Q1=A 补齐 web play/dwell 打点**：新增 W-4 批次（web 车道，可与 Android 链并行）——修通 dwell+补 play，口径 DOMAIN_RULES §5，隔离实例验收。
- **Q2 五条（M4-3 解锁）**：likedToday 协议补字段（M4-3 内协议先行，web 端增量零适配）+今日已赞再点=撤销今日赞；其他标签名字序降级；时间轴颜色 ❤/⭐ name 前缀约定；批次导航=左右滑相邻切换+预加载窗口；媒体清单=列表传已加载 ID 列表。
- **Q3 六条（M4-6 解锁）**：统计时段四档对齐 Web（含 range=day=近30天命名陷阱注释）；数字卡静态不随档；常看卡/详情四模式维持砍；我的页加推荐偏性行；GIF 进磁盘缓存；版本信息显示服务端版本。
- **Q4 记账项**：AGP9+Gradle9+compileSdk37 等 M4 全完后一次升；build-logic 收敛 M4-7 做；五 Tab 图标维持自持；App label 沿用「绮梦影库」。
- **执行模式变更**：用户手动派发（贴单批任务书开新会话），主会话只做规划不派发；六份自包含任务书（W-4/M4-1/M4-2/M4-3/M4-5/M4-6，含环境快照+08:30 硬停线）=仓库外《QimengNAS/派发任务书-20260905夜.md》。推荐派发序：Android 串行 M4-1→M4-2→M4-5→M4-6（07:00 后不新贴批），W-4 随时并行，M4-3 最重留明晚首发。
- 08:50 定时暂停自动化的收尾口径同步更新（晨报只报执行结果——拍板已全部完成）。
## 晨间复查与派发链规划：今晚 M4-1→M4-2→M4-5，M4-3/M4-6 待拍板暂缓（2026-09-05 第三十五笔）

执行 AI：GLM-5.3（主代理，ZCode 调度）

按用户晨间指令复查《进度盘点-20260905晨.md》+ HANDOVER 后的规划落档（HANDOVER 当前待办同步刷新）：

- **今晚可跑链（无拍板依赖，串行派发）**：M4-1（登录+ServerConfigDataSource）→ M4-2（列表族，含拍板 1A/2B/3B/4A 落地；备忘录 A3/A4 两处规格冲突依 DOMAIN_RULES §6/§3 已拍板口径执行=首页 cos tab 走 /assets cosOnly、搜索页「全部/常规/COS」分区胶囊缺省全部，交付报告注明出处）→ M4-5（上传主通道，重批次带 reviewer 对抗审查）。每批执行代理内置 08:30 硬停线（用户 08:50 暂停要求的前置保障）。
- **暂缓链**：M4-3（卡详情页五条拍板）→ M4-4（前置 M4-3）→ M4-6（卡 C1~C6 拍板）→ M4-7（全部前置）；M6 在 M4 后（真机 arm64 复验第一优先，需用户实体手机）。
- **待拍板汇总报表**：仓库外《QimengNAS/待拍板-20260905午.md》——★web play/dwell 打点缺口、★M4-3 五条、M4-6 C1~C6、M4-0 记账项四条，每条附候选与建议，供用户中午一次性定夺；详情页发现项已拍板（M4 后回补）不再列入。
- **08:50 定时暂停**：单次自动化今日 08:50 触发（免费 Flash 额度 09:00 截止前 10 分钟）——停派新批、收尾在途、补记报表夜间结果段、晨报归档。
## 夜间集群晨间汇总：W 链 + M4-0 + S-1 + M6 POC 完成，多会话并行归档（2026-09-05 第三十四笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode 调度；各批次署名见对应条目）

夜间集群模式首次全程运行（本会话调度 M 链 + 用户另开两会话：S-1 服务端 / M6 POC + 审查会话）。完整盘点见仓库外《进度盘点-20260905晨.md》，收工快照：

- **完成批次（均 commit+push+CI 绿）**：W-1 6690b12 / W-2 2001a20 / W-3 92f1a47（追加返工 f3da79c）/ **M4-0 cdc422e**（Android Compose 16 模块骨架+壳导航+CI 第五 job）/ **S-1 服务端协议扩展 0d4c9df**（/history 筛选+facets 子集计数+搜索建议端点+收藏历史缺省改全部，用户拍板 1A/3B）/ 拍板批五项 0a2f5d1（审查会话；倍速项经复审打回由 f3da79c 真修复）/ M6 前置 POC（会话 2，结论：amd64 实跑通过、modernc sqlite 正常、ffmpeg 成品全链路通；arm64 需真机复验——../m6-poc/POC-RESULT.md）。证据位置：各批 %TEMP%/qimeng-w1|w2|w3|m40-shots/ + 仓库外备忘录 m4-*/m6-* 全套。
- **SKIPPED**：无（W/M4 链无停手点触发）。
- **待用户处理项**：★web play/dwell 打点缺口（§5 第 13 条：整库播放次数/浏览时长为零，核查建议补，半天）；★M4-3 详情页五条协议/规格歧义（不拍板 M4-3 会中途停手）；M4-6 C1~C6 六条；详情页发现项（图片查看器/互动行/批次导航 Web 端回补与否）；M4-0 记账项（AGP9+compileSdk37 升级路线/build-logic 收敛/图标方案/App label）；搁置两项维持。
- **多会话观测**：单会话子代理 ≤3 稳定（5 并发触发账号限流 1302，SendMessage 续跑可恢复进度）；跨会话写并行靠「只 stage 本车道路径 + 共享文件提交前检查」零冲突；GitHub 两次 502 重试通过。
- **下一步**：待 5.3 复查规划后从 M4-1 起串行派发（执行链与前置见盘点文件第四节；M4-2 批将落地拍板 1A/2B/3B/4A）。
## W-3 追加返工：倍速文案真修复 + 切路由整队取消补全（2026-09-05 第三十三笔）

执行 AI：GLM-5.3-Flash（主代理调度验收；返工由原 W-3 执行子代理续聊完成）

0a2f5d1（第三十笔）复审判定 5/6 通过，唯「倍速文案」项无效且引入缺陷，本笔纠正（原 W-3 执行代理续聊返工，因其握有该文件完整上下文）：

- **倍速菜单文案（真修复）**：0a2f5d1 的 `art.setting.update({ name: 'playbackRate', html })` 实为错配——`'playbackRate'` 是右键菜单条目名，设置面板内建倍速条目名为 `'playback-rate'`，面板 update 按 name 精确查找、未命中走 add 分支，导致舍入条目原样保留 + 新增一条无 click 处理器的死行；第三十笔「实测 0.75/1.25 正确显示」的记录与 dist 实装代码路径矛盾，**该记录作废**。本笔改按 `name: 'playback-rate'` 定位内建条目、仅替换 selector 各档位显示为精确值（0.5x/0.75x/正常/1.25x/1.5x/2x/3x），选档/高亮走内建 onSelect。实测：菜单恰 7 行无舍入无死行、点 0.75x 后 `video.playbackRate === 0.75`（截图 %TEMP%/qimeng-w3-shots/10、11）。
- **切路由整队取消（W-1 冻结语义补全）**：W-1 起卸载 cleanup 只 abort 在传 XHR，串行泵会继续拾取 queued 条目后台续传，违反「切路由自动 abort 整队、不做后台续传」冻结语义——卸载时先把全部 queued 标 canceled 再 abort 在传，注释与行为对齐。
- **顺带**：UploadCard 删除 targetLibraryId undefined 死分支，未选库拦截条目按目录语义显示「/库根」（注释注明）。
- **验收**：tsc/build/lint 全绿（改动 3 文件 0 warning，存量 15 不变）；复审确认第 9/10/11①/12①/第 8 条五项修复真实有效（SSE 键收敛/入队快照/单泵真串行/拖拽 seek 归一/深色 token 统一）维持通过。
## M4-0 工程基建：Android Compose 多模块骨架 + 壳导航 + make/CI 第五门禁（2026-09-05 第三十二笔）

执行 AI：GLM-5.3-Flash（执行子代理）

按 HANDOVER_APP M4-0 任务书（ADR-0014 冻结设计）交付 Android 工程奠基石，全部验收命令与模拟器实测通过：

- **多模块骨架（冻结结构）**：`:app`（壳/导航/Hilt 装配，namespace `media.qimeng.app`）+ `:core:model`（纯 Kotlin）/`:core:network`（:sdk 封装层依赖接线）/`:core:data`（Repository 层依赖接线）/`:core:ui`（品牌主题+共享占位组件）+ `:feature:{home,all,album,favorite,history,search,author,detail,stats,settings,upload}` 空壳占位；minSdk 26 / compileSdk 36；版本唯一收口 `gradle/libs.versions.toml`（AGP 8.13.2 + Kotlin 2.3.21 + KSP 2.3.11 + Compose BOM 2026.06.01 + Hilt 2.58 等，逐项官方来源核查，链接在 toml 注释）。
- **壳导航**：单 Activity + Navigation Compose + 底部五 Tab（首页/全部/相册/数据/我的，GUIDE_UI §导航结构），saveState/restoreState+launchSingleTop 实现 Tab 保活语义；Material 3 主题色板对照 web prototype.css 换算（#4250af 系 + .dark oklch 换算值，注释逐项标注 token 来源）；五 Tab 图标为自持矢量（Material Icons 官方 path data，零新依赖）。
- **应用图标**：adaptive icon 一套按 GUIDE_UI §应用图标 资产规格接入（背景 #DDBC98 + 旧项目前景雕刻图 PNG 五密度——资产照规格书搬运，非实现代码搬运）；minSdk 26 无需 legacy mipmap。
- **:sdk 生成物接线（本笔关键攻坚）**：生成器自带 build.gradle 的 `wrapper{}` 属 Gradle 7 DSL（Gradle 8 移除）且自带 Kotlin 2.4.0 独立 buildscript，与冻结的 Gradle 8.13/Kotlin 2.3.x 冲突且禁手改——settings.gradle.kts 以 `buildFileName` 指向工程侧 `android/sdk/sdk.gradle`，该文件由 `make sdk` 的 sdk-kotlin 步骤生成（`SDK_GRADLE_FILE`，纯 ASCII 防 make.exe 代码页转码），保持「生成物一律 make sdk 重建」不变式。另一生成器缺陷：协议 sort 枚举合法值 `name` 生成的枚举项与 `kotlin.Enum.name` 冲突无法编译——用官方 `--enum-name-mappings name=nameValue` 生成期改名（线上值不变，openapi-generator 官方 Customization 文档方案）。
- **版本组合修正（预检备忘录两处建议不可行，已按实测落定）**：Compose BOM 2026.08.00 的 compose 1.12.0 要求 compileSdk 37（AAR 元数据硬门禁，assembleDebug 实测）→ 取 API 36 兼容线最新 BOM 2026.06.01（ui 1.11.4/material3 1.4.0）；同期 androidx 新 wave（navigation 2.10.0/lifecycle 2.11.0/androidx.hilt 1.4.0/activity 1.13.0）同要求 37 → 各退上一稳定线（2.9.8/2.10.0/1.3.0/1.12.4）；Hilt 2.60.1 强制 AGP 9+ → 退 2.58（AGP 8.x 兼容线末位，官方 release note）。升级统一走 AGP 9+Gradle 9+compileSdk 37 路线（后续批次决策）。
- **构建接线**：Makefile 增 `app-build`/`app-test`/`app-lint`（wrapper 构建）；.gitignore 改 `android/**/build/`；`android/local.properties` 本机 SDK（不入库）。本机 dl.google.com 不可达，Gradle 镜像走机器级 `~/.gradle/init.d`（不入仓库，CI 直连官方源不受影响）；Gradle 8.13 发行包经腾讯镜像下载并校验官方 sha256 后种子进 wrapper 缓存。
- **CI 第五 job**：`android`（checkout → make sdk 重建生成物 → temurin 21 → gradle/actions/setup-gradle@v4 → assembleDebug+testDebugUnitTest+lintDebug）；同步 PROJECT_PLAN「门禁 = CI 五 job」与 HANDOVER「五道门禁」。
- **测试**：TopLevelDestinationTest 锁定五 Tab 顺序/路由唯一性；`make app-build && make app-test && make app-lint`、`make lint` 全绿；`make sdk` 删除 android/sdk 后干净重建验证通过。
- **模拟器实测**：qimeng_api35 启动 → installDebug → 五 Tab 逐一切换截图（`%TEMP%\qimeng-m40-shots\01~05.png`）→ 关机。
## 服务端协议扩展 S-1：/history 筛选、facets 子集计数、搜索补全端点、收藏/历史缺省全部（2026-09-05 第三十一笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode 调度验收；编码由 executor 子代理完成）

用户 2026-09-05 拍板 1A（收藏/历史缺省全部）+ 3B（搜索补全端点）落地，协议先行改 `api/openapi.yaml` 后 `make sdk` 重建三端生成物，再接线服务端（纯查询层，无 migration）：

- **/history 筛选参数**：新增 `cosOnly`/`work`/`character`/`mediaType`（与 GET /assets 同名参数同语义，browse.sql 同型谓词复制进 history.sql），响应结构不变；`includeCos` schema default 改 **true**——缺省全部（常规∪COS 合并），显式 false 切常规分区、cosOnly=true 切 COS 分区。
- **缺省分区改「全部」（1A）**：/history 缺省含 COS（includeCos 缺省映射 1）；/assets `favorite=true` 且未显式传分区参数时缺省含 COS（协议侧 /assets includeCos default 保持 false，收藏流特例在 newAssetFilters 服务端分支实现）。**范围红线守住**：GET /sources、GET /recommendations、/assets 非收藏流的缺省排除口径一律未动。
- **/assets/facets 子集计数**：新增 `favorite`/`history` 两参数（子集约束非四维之一）——favorite=1 只统计收藏资产、history=1 只统计有 kind='open' 事件的资产；传入时对全部四维含分区栏统一收窄（收藏页分区芯片=收藏子集内 all/regular/cos），四维间仍按排自身口径互算；六个 facets 查询各加两谓词（恒传 0/1 避三值逻辑）。
- **新增 GET /search/suggestions**（LEGACY §C + 3B）：搜索框补全，五维候选=出处/角色/COS 作者/COS 作品/常规作者；子串匹配 ASCII 大小写不敏感；UNION 全行去重=同维同名一条、跨维同名各保留（type 字段=类型徽标分派）；长度升序再名称字典序；作者维只返回实际有关联文件的作者（EXISTS asset_authors JOIN assets）；q 空（trim 后）返回空列表；limit 默认 10 上限 50。实现形态：单 UNION 包一层 FROM 子查询再 ORDER BY——SQLite 禁止 compound SELECT 的 ORDER BY 用表达式（`length(name)` 实测报 "1st ORDER BY term does not match any column"，2026-09-05），包裹后外层为普通 SELECT 可用表达式排序，sqlc v1.31.1 接受。
- **口径变更**：收藏/历史缺省排除 COS 的旧口径就此废止（DOMAIN_RULES §6 同步改写）；首页推荐维持缺省排除。
- **生成物重建**：`make sdk` 全链通过（redocly lint → oapi-codegen v2.8.0 → hey-api TS → openapi-generator kotlin）+ `sqlc generate`（history.sql 三态+筛选、facets.sql 六查询子集谓词、suggestions.sql 新查询）。
- **测试**：httpapi 全量通过——history（缺省含 COS/includeCos=false/cosOnly/mediaType/work/character 'a+b' 全部命中）、facets（favorite/history 子集四维计数、与 mediaType 组合、排自身回归）、suggestions 新文件（五维命中/大小写折叠/同维去重/跨维同名/长度+字典序排序/默认 10 与 limit 覆写与越界 400/无文件作者不出现/空 q 空列表）、assets 收藏缺省全部+防回归；既有锁定旧口径的断言已按新拍板修正（测试注释注明 2026-09-05 用户拍板）。隔离实例 curl 实测通过（缺省 /history 含 COS、/assets?favorite=1 含 COS 收藏、facets favorite/history 子集计数、suggestions 五维与排序）。
## 拍板批五项修复：SSE 跨端刷新/上传目标快照+真串行/拖拽 seek/倍速文案/深色 token 统一（2026-09-05 第三十笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode；用户四项拍板后单会话直修，隔离实例全量实机验收）

HANDOVER_UI §5 第 9~12 条记账项经用户逐项拍板（SSE 修/上传快照修/小毛病全修/详情页发现项 M4 后回补），本轮全部落地：

- **SSE 跨端失效键（§5 第 9 条）**：新增 `web/src/lib/query-keys.ts` 查询键唯一来源（根键常量 + 子键 `[...根键, '子族名', …]` 构造纪律——TanStack 前缀匹配按逐元素相等，子键另起 `'api/v1/assets/xxx'` 首段即脱离根键覆盖是原缺陷根因，口径入模块注释）；SseBridge 失效键全部换根键常量（library.changed → 资产/库/目录/标签/作者/出处/回收站；upload.done → 资产/库/目录/推荐流）；资产族子键 list/total/detail/facets/timeline-tags 在 use-assets/use-progress 重挂根键；use-libraries/use-tags/use-trash/use-authors 键定义收敛进 query-keys。实测：web 首页停留，curl 模拟另一端上传 → 页面未刷新卡片 3→4 自动出现（修复前整体是断的）。
- **上传入队快照目标 + 队列表目标列（§5 第 10 条）**：`use-upload` 入队时刻快照 `targetLibraryId`/`targetDir`（渲染契约字段即发送依据，消除双写——执行中自查发现首版 `QueueEntry.target` 与渲染字段分离致目标列显示"—"，当即合并修复重建）；UploadCard 队列表新增「目标」列（库名/库内路径，库不可查回落 ID）。实测：选库 A 入队 → 「上传中 0%」窗口内切库 B → 落库 A、库 B 零资产、目标列恒显 `SmokeLibA/库根`。
- **上传全队列真串行（§5 第 11 条①）**：drain(批) 模型改单泵（`drainingRef`），后入队批次只入列等待顺次拾取，任意时刻至多一路传输。实测：两批 12MB 文件（批 2 在批 1 传输中入队）performance 资源计时区间零重叠。
- **拖拽 seek zoom 归一（§5 第 12 条①）**：video-player 捕获阶段拦 `mousedown` 接管官方拖拽臂（官方标志被拦不再起臂）+ document `mousemove` 视觉坐标 seek（与已验证的点击修复共用归一函数；悬停时间预览走官方自有 mousemove 不受影响）。实测：拖到视觉 70% 落点 83.9s/期望 84.0（带偏置应为 92.4）；点击回归 25% 落 29.9s/期望 30.0。
- **倍速菜单文案修正（§5 第 12 条②）**：官方标签生成 `toFixed(1)` 致 0.75/1.25 显示 0.8/1.3——`art.setting.update({name:'playbackRate', html})` 按 name 合并替换内建条目只换标签文本（官方 update API 合并既有 option；选档/高亮逻辑读 `data-value` 不依赖文案，原样生效）。实测 0.75/1.25 正确显示、1 显「正常」。§5 第 12 条③（全局 zoom 与坐标类库系统性冲突）保留待评估。
- **深色 token 写法统一（§5 第 11 条② / 第 8 条口径更新）**：13 个 `-dark` 后缀变量自 `:root` 等值迁入 `.dark` 块并改同名覆盖（与 `--bg/--elev` 同制），引用规则同步改基名——纯定义点搬移 + 引用改名，浅/深双态零视觉变化；`-dark` 口径自 HANDOVER_UI 第 8 条退役。
- **详情页发现项拍板落档**：图片查看器/详情互动行/批次导航 → 用户拍板「M4 后回补 Web，不阻塞 M4 启动」（HANDOVER_UI §5.9 末尾）。
- **验收**：`npx tsc --noEmit -p web/tsconfig.app.json` 0 错、`npm run build` 成功、`npm run lint` 15 warnings 0 errors（存量分布不变，改动文件 0 告警）；隔离实例（18420+临时数据目录，dev-login，两库 13 资产）全项实机走查，截图 %TEMP%/qimeng-decision-shots/（上传队列表含目标列 + 播放器 seek 后 00:29/02:00）。并行观测：本批执行期间磁盘出现 M4-0 Android 批次并发 WIP（server/android/CI 等）——本 commit 严格只含本批文件（web 12 文件 + HANDOVER_UI/CHANGELOG），未触碰他人 WIP；HANDOVER.md 待办让位并行批次后自行更新。
## W-3 一致性复审：文档声明逐项核验 + play/dwell 打点缺口记账（2026-09-05 第二十九笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

- **逐项核验通过（W-3 = 92f1a47）**：artplayer@5.4.0 exact 锁定（package.json/package-lock 一致）；video-player.tsx 实测 132 行——倍速静态档位表覆盖 0.5~3x（PLAYBACK_RATES 覆盖官方 `Artplayer.PLAYBACK_RATE`）/全屏/`ready` 续播 seek/highlight 打点/theme 运行时读 `--qm-primary`+MutationObserver 跟深色/StrictMode 三路清理（observer.disconnect+seek 修复解绑+art.destroy）；use-progress.ts——`PROGRESS_REPORT_INTERVAL_MS=5000` 具名常量含协议同步责任注释（严于协议建议 10s）、mutationFn 显式载荷+`{assetId,positionSeconds}` 配对、切资产以旧 id 补报后重置、卸载补报、timeline-tags select 升序；AssetDetailPage——已看完 `isWatched` 与协议口径逐字一致（`>= durationMs/1000`，openapi.yaml AssetDetail.lastPositionSeconds description 同文）、watched→起点 0+徽标、codec-warn 逻辑未动、key 切资产重建、tagsLoading 守卫、页面零 SDK 直调（铁律 7）；prototype.css W-3 段零颜色字面量（`--invert`/`--qm-primary` 均既有 token；`zoom:1.1`、68vh 两个前提实测成立）。
- **三命令复跑全绿**：`npx tsc --noEmit -p tsconfig.app.json` 0 错、`npm run build` 成功、`npm run lint` 15 warnings 0 errors——全部位于存量文件（router.tsx×13、button.tsx、SearchPage.tsx 各 1），W-3 改动文件零告警，与第二十八笔声明一致。
- **修补两处文档滞后**：HANDOVER_UI §5 第 2 条仍标「⏳ 待做——ArtPlayer 播放器 UI（现用原生 video）」→ 三待办划线全清（W-3 补 ✅ 注）；文头「最后更新」停留 09-04 未反映三批收官 → 刷新至 09-05。
- **新记账（HANDOVER_UI §5 第 13 条，待拍板）**：web 从未上报 play/dwell 事件（全局只发 open，旧裸 video 时代同样未接）——playCount、浏览时长、排行热度公式的 playCount 项从 web 侧永远无贡献；W-3 任务书「dwell/play 打点沿用 useReportView」实际是保持现状而非已接线。M4 App 端任务书已含 play/dwell 接线（HANDOVER_APP），web 是否补齐待用户拍板。
## W-3 ArtPlayer 播放器：断点续播/倍速/时间轴打点/已看完徽标（2026-09-05 第二十八笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode 调度验收；编码与冒烟由 executor 子代理完成，reviewer 子代理对抗审查打回 1 轮后返工通过）

UI 收尾三批收官（HANDOVER_UI §5.9 任务书，重批次走对抗审查）：

- **选型**：artplayer 5.4.0（npm 最新稳定，package.json exact 锁定；官方文档站 option/event + GitHub 源码核对——`highlight` 即官方进度条打点、`PLAYBACK_RATE` 为公开静态档位表，时间轴标记无需停手方案）。CHANGELOG 记选型不建 ADR（任务书口径，UI 可换层属 ADR-0008 精神）。
- **新增**：`components/media/video-player.tsx`（132 行——倍速 0.5~3x/静音/全屏/断点续播起点/打点/theme 运行时读 CSS 变量零色值进 JS + MutationObserver 跟深色切换/StrictMode 安全三路清理）；`hooks/use-progress.ts`（5s 节流具名常量严于协议建议 10s/暂停 flush/卸载补报/切资产重置 + timeline-tags 升序查询）。AssetDetailPage 裸 `<video>` 替换 + 已看完徽标（`lastPositionSeconds >= durationMs/1000` 客户端推导，恰等边界实测）。
- **执行中发现并修复**：①全局 `html{zoom:1.1}` 下 ArtPlayer 进度条**点击** seek 系统性 ×1.1（官方 getPosFromEvent 视觉 px÷布局 px 混算）——捕获阶段按纯视觉坐标归一修正，实测点 45s 落 44.97s；②已看完徽标被官方 `.art-video`/`.art-poster` 层叠覆盖——z-index:12+pointer-events:none。
- **对抗审查打回 1 轮（已返工验证）**：P1 详情→详情导航（同路由参数变化不卸载）时卸载补报把旧资产进度写进新资产（mutationFn 闭包随 render 切资产）——修法=mutationFn 收显式载荷+`{assetId, positionSeconds}` 配对存储+切资产 effect 以旧 id 补报后整体重置，靶向实测（A 播至 24s→SPA 切 B→离开：A=23.93 已报、B 无 lastPositionSeconds 污染）；P2 打点注释失实已改实并删死代码 highlightsRef。返工后全量冻结项回归通过。
- **验收证据**：tsc/build/lint 全绿（改动文件 0 warning，存量 15 不变）；隔离实例 10 图+ev.log（%TEMP%/qimeng-w3-shots/）——续播 20s 起点、倍速菜单 0.5~3.0、静音、双标记点击 seek、暂停 52.55→服务端 52.5467、离开补报 55.21、已看完 00:00+徽标、双视口 spread 0.0/0.0。
- **记账（HANDOVER_UI §5 第 11/12 条，待拍板）**：拖拽 seek 的 zoom 偏置未修（冻结只要求点击）；倍速菜单文案内建舍入（0.75 显 0.8）；全局 zoom 与坐标类库系统性冲突；上传串行仅单批+深色 token 写法并存。
## 一致性审查：W-1/W-2/第十笔文档声明逐项核验 + 代码卫生修补（2026-09-05 第二十七笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户要求核验已完成内容的文档描述与实现一致，并对代码审查：

- **逐项核验通过（文档=实现）**：W-1（use-upload：XHR+octet-stream 流式/进度回调/单条 abort/上限前置读 config/白名单不前端复制/切路由 abort 整队/串行发送/三组查询失效键；UploadCard 184 行 ≤300/队列表四列/逐条 toast；DirTree 只读/选择双模式+根行「库根 N 文件」；DIRS/ASSETS/LIBRARIES 查询键常量；format.ts dirLabel；上传卡 CSS 段零颜色字面量）；W-2（radix-ui 统一包 ^1.6.7 零新装包；冻结 API+onOpenChange?；--overlay-bg/--dialog-shadow 双 token；TrashPage 彻底删除/清空 danger、删库非 danger；window.confirm 调用清零恰剩 3 处注释；不可达空回收站分支已删）；第十笔后端四项抽查（GET/PUT /api/v1/config、client-logs 环形 200、/api/v1/healthz|readyz 免鉴权+根路径别名、上传后自动 EnrichAsset@upload.go:201）。验收口径复测（W-3 WIP 出现前测得）：tsc 0 错、lint 15 warnings 0 errors，与两批交付时一致。SseBridge 失效键缺陷与 §5 第 9 条记账相符（仍在、未修、待拍板）。
- **修补①（代码卫生，行为零变化）**：TrashPage 本地 `formatBytes` 与 `lib/format.ts` 导出版逐字重复（卫生约束#6 现有共享函数>复制粘贴；format.ts 注释本就写明「回收站/详情信息共用」，其余 5 个消费方全部走共享导入、唯独 TrashPage 自带副本）——删本地副本改 import。
- **修补②（文档状态同步）**：HANDOVER「当前待办」第 1 条补 W-1/W-2 已交付标记（commit 号）与 W-3 当前批次指向——此前恢复入口文档未反映前两批完成，新会话按「当前待办继续下一批」会误派 W-1。
- **工作树发现（不擅动）**：审查进行中观测到 W-3 会话正并发写入本工作树（夜间集群模式）——artplayer@5.4.0（经 npm 核实为最新稳定）依赖、video-player.tsx、use-progress.ts、AssetDetailPage 接线相继出现且未提交。本审查范围=已提交基线（至 2001a20），W-3 WIP 不在审查内、一律未触碰；复核时 lint 从 15→22 warnings，增量全部来自 W-3 在写文件（use-progress/video-player/AssetDetailPage），基线 15 与 W-2 交付声明一致。并发状态已同步进 HANDOVER 待办第 1 条。
- **代码审查记账（不扩围，HANDOVER_UI §5 第 10 条）**：use-upload 队列条目未在入队时快照目标 libraryId/dir——上传中途切库/切目录会改变未开始条目的去向（uploadOne 发送时读实时 options），队列表也不显示目标库；快照语义更合用户预期，但属行为变化需重跑 W-1 冒烟，待用户拍板。
## W-2 confirm 换原型风格弹窗：window.confirm 全量退役（2026-09-04 第二十六笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode 调度验收；编码与冒烟由 executor 子代理完成）

UI 收尾三批第二笔（HANDOVER_UI §5.9 任务书）：

- **新增** `web/src/components/ui/confirm-dialog.tsx`：radix AlertDialog 封装（复用已有 `radix-ui` 统一包 ^1.6.7，零新装包）；冻结 API 原样 + 必要可选 prop `onOpenChange?`（radix 受控 open 响应 ESC 所必需，主会话裁决接受并记账）；样式全走原型 token，新增 `--overlay-bg`/`--dialog-shadow` 双语义 token（§5.8 口径，零颜色字面量）；danger 语义=主文字色反底（浅色黑胶囊/深色自动反转白底黑字），不引入红色 token。
- **替换**：TrashPage 彻底删除/清空（danger）、LibraryManagePage 删库（非 danger——删库只清索引、磁盘文件不受影响，主色键）；`grep window.confirm` 实际调用清零。顺带删除 TrashPage 一处不可达空回收站分支（disabled 拦截下死代码，行为等价）。
- **验收证据**：tsc/build/lint 全绿（本批文件 0 warning，存量 15 warnings 不变）；隔离实例三弹窗×浅/深双模式+焦点环+ESC 关闭+真确认链路 9 图（%TEMP%/qimeng-w2-shots/）；ESC 实测 7 次全过、焦点环 2px var(--qm-primary)、radix 初始焦点自动落取消键。
## W-1 上传 UI 入口：文件管理页上传卡 + use-upload 队列 hook（2026-09-04 第二十五笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode 调度验收；编码与冒烟由 executor 子代理完成）

UI 收尾三批第一笔（HANDOVER_UI §5.9 任务书，夜间集群模式首个写批次）：

- **新增**：`web/src/hooks/use-upload.ts`（XHR + octet-stream 流式上传队列：进度回调/单条 abort/大小上限前置拦截（读 GET /config upload 项，超限中文提示不入网）/类型白名单不前端复制（服务端四道校验唯一口径、4xx 文案透传）/切路由自动 abort 整队）；`web/src/components/manage/UploadCard.tsx`（选库→目录树选目标→点击/拖入→队列表（名称/大小/进度条/状态）→逐条 toast + libraries/dirs/assets 三组查询本地失效）；`web/src/components/manage/DirTree.tsx`（目录树渲染从 LibraryManagePage 抽共享：只读/选择双模式）。
- **修改**：LibraryManagePage 接入上传卡；use-libraries/use-assets 提取 DIRS/ASSETS 查询键常量（字面量卫生）；format.ts 新增 dirLabel()；prototype.css 追加上传卡段（零新增颜色字面量，全 token 引用）。
- **验收证据**：`npx tsc --noEmit -p tsconfig.app.json` / `npm --prefix web run build` / `npm --prefix web run lint`（0 errors，存量 15 warnings 不变）全绿；隔离实例（18420 端口+临时数据目录，真实库零接触）UI 实走 1 图+1 视频上传：进度 26% 中间态→完成 toast→目录树 7→8 文件→首页网格出现新卡，截图 5 张存 %TEMP%/qimeng-w1-shots/；§4.5 对齐静止态实测：上传卡与同页全部行级块横向 spread 0.0px、卡间距 17.59px 与全页节奏一致。
- **发现并记账（不扩围）**：SseBridge 跨端失效键与实际查询键形态不匹配的存量缺陷（HANDOVER_UI §5 第 9 条，待拍板）；上传并发取串行（协议未约定，最保守值）；DirBrowser 根行改显「库根 N 文件」（共享抽取伴生小变化）。
## 夜间集群模式补入执行调度：主会话调度器 + 写串行读并行（2026-09-04 第二十四笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户问「Flash 无限额时段能否一个主会话派集群子代理全量执行」——评估结论：全量并行不可行（全部子代理共用同一工作树，写任务并行必互踩；批次有依赖链），全量**流水线**可行。调度规则补第 8 条「夜间集群模式」：主会话只当调度器不写码（派发/验收/更新勾选与 CHANGELOG）；写代码任务严格串行（前批 commit 后再派下批），只读任务（reviewer/调研）可并行；停手批次记 SKIPPED 跳过、继续派无依赖批次；收工产出晨间汇总（完成清单含 commit hash/SKIPPED 原因/待用户处理项）追加 CHANGELOG；推进状态以磁盘为准不靠记忆，会话压缩前确认工作树干净。
## Flash 自驱执行调度规则落档：免 5.3 逐批规划（2026-09-04 第二十三笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户要求 Flash 按任务书自驱派发执行（免 5.3 逐批规划，防额度卡死）。任务书本已自包含（昨日终审标准即"拿着就能干"），本笔补最后一块——调度规则七条落 `docs/HANDOVER.md`「执行调度」节：顺序固定（W-1~3 → M4-0~7 → M6）、每批一个会话/子代理禁连做、验收只认贴输出+截图、重批次（M4-0/3/5、W-3）自派 reviewer 对抗审查、每批完成即 commit（中断无损、恢复=新会话说「按 HANDOVER 当前待办继续下一批」）、停手即停、不扩围。5.3 收窄为停手仲裁/方向变更/验收争议时介入。
## M4/M6 规划终审对抗审查与修补：交付前防线加固（2026-09-04 第二十二笔）

执行 AI：GLM-5.3（主代理，ZCode；对抗审查由 reviewer 子代理完成，全部事实经 grep/read 独立重验）

用户要求终审全部方案并加固技术约束（后续由 5.3 规划 + Flash 执行的双模型模式实施）。reviewer 子代理以执行 AI 视角审查 12 份文档，产出 18 项判定（3 P1/5 P2/10 P3，总评"修补后可交付"），全部修补：

- **P1 三项**（均为"弱执行 AI 必踩"级）：① HANDOVER_APP 通用约束 4 补引 `LEGACY_REQUIREMENTS.md` M4 条目（标签管理/搜索维度/交互/空态/性能五组需求级结论此前执行链路读不到）；② 通用约束 7 补硬规——验收命令输出必须逐条粘贴进交付报告，未贴不算交付；③ M4-0 补"工程第一步先 `make sdk` 生成 android/sdk"（生成物 git 忽略、CI checkout 后不存在，缺此步 include ':sdk' 必挂且弱 AI 可能手写生成物）+ CI Android job 首步 make sdk（照抄 sdk-chain 模式）。
- **P2 五项**：AGENTS 路由表 UI 行补 0014/HANDOVER_APP 指向（reviewer 报告的"正文未同步"经 grep 验证为误报，正文已是 ADR-0014 口径）；进度心跳 5s 注明"严于协议建议值 10s、协议允许"（消除与 openapi/GUIDE_API 的表面矛盾）；**M4-4 打点语义改写**——服务端仅 open/play 会话去重、**dwell 累加不去重**（原表述与 DOMAIN_RULES §5 实口径不符，弱照做会时长虚增），客户端保证一次停留恰好一条 dwell；断点续播"已看完"语义冻结（lastPositionSeconds ≥ durationMs/1000 从 0 重播+徽标，协议明文客户端推导）；HANDOVER_APP 补开工前置（服务端跑法/密码/8420 在线确认，见 HANDOVER 两节）。
- **P3 十项**：版本纪律硬化（版本号须查官方来源锁定+交付报告附链接，禁凭记忆）；WorkManager 兜底周期注明 ≥15min 系统钳制；健康探测改协议面 `/api/v1/healthz`（根路径是运维别名）；M4-0 补应用图标（旧仓库 §应用图标 资产规格）；M4-0 交付物补"CI 四 job 字样→五"两处文档同步（PROJECT_PLAN 头部/HANDOVER）；ADR-0014 桥接白名单补同类自绘件（LineChartView）对齐 M4-6；INDEX 表头补状态列+0013 行注批次引用过期；HANDOVER 进度节日期残留修正；"第五笔"标签补出处；ADR-0013 废弃标注与引用关系核实协调。
- 审查正面验证通过项（未改）：GUIDE_UI 19 节名引用逐条命中、协议端点/字段（progress/facets/config/lastPositionSeconds）与代码符号（INCOMPATIBLE_CODECS/useReportView）真实存在、minSdk 26 全兼容、ServerConfigDataSource 单点五处口径一致、M6 Termux/ffmpeg/gomobile 表述准确。
## M4 二次改道「先进优先」Compose 重建 + M6 单机形态（ADR-0014/0015）（2026-09-04 第二十一笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户三项拍板：①「走先进方案为主，换 Compose」②「架构做足够先进和高度解耦，不考虑后果」③「电脑不长期开，希望手机本地顶替后端，不再维护两个项目」：

- **ADR-0014 新建（取代同日 0013，0013 标废弃但调研结论保留）**：M4 走 Kotlin + Compose(Material 3) + Hilt + Now in Android 多模块范式（:app + :core:model/network/data/ui + :feature:*，feature→core 单向依赖）；交互规格照搬 GUIDE_UI、实现全部新写；复杂自绘控件（BiliPlayerView/ZoomImageView）允许 AndroidView 桥接（清单入交付报告）；minSdk 26 / namespace media.qimeng.app。
- **ADR-0015 新建 + PROJECT_PLAN 新增 M6 里程碑（原储备顺延 M7+）**：Android 单机形态——Go 服务端交叉编译 android/arm64（modernc 纯 Go 无 CGO 为当初 ADR-0003 选型红利，数据库层零改动）+ App 连 localhost（UI 零改动）；两候选运行形态（Termux 宿主/App 内嵌 gomobile）；最大风险=ffmpeg 移动端方案（ffmpeg-kit 已停维护，Termux 形态用其包）；旧项目绮梦影库由此退役（数据迁 /import/qimeng-backup + 媒体原地注册）；实施排 M4 后、优先于 M5。
- **HANDOVER_APP 第三次重写（Compose 版）**：八批次任务书（基建含多模块骨架/登录含 ServerConfigDataSource 单点=单机形态预留/列表族/详情页含断点续播+时间轴标签/离线上报/上传/缓存+统计/验收），通用约束十含「服务器地址只经 ServerConfigDataSource 流转」。
- **配套同步**：AGENTS（项目一句话+旧项目关系节：Compose 重建+旧项目待退役口径）、AI_README_FIRST（禁止行为第 2 条+Kotlin 规范行）、android/README（技术栈二次定论+单机形态预留节）、HANDOVER（执行路线四段：UI 收尾→M4 Compose→M6 单机→M5 用户自测节奏）、INDEX（0013 废弃标注+0014/0015 两行）。
- **Go 后端先进性评估结论（答复用户，无改动）**：现有选型（协议先行/纯函数领域层/事件流统计/sqlc/纯 Go SQLite）即现代 Go 最佳实践，且纯 Go 无 CGO 与配置化数据目录两个选型正是单机形态可行性的钥匙，无需变更。
## M4 路线改道：旧 UI 整体照搬 + 数据层网络化（ADR-0013）（2026-09-04 第二十笔）

执行 AI：GLM-5.3（主代理，ZCode；旧项目架构调研由只读探索子代理完成）

用户拍板「安卓端走完全旧 UI（照搬）」并问算法提取状态，经旧项目源码调研证实可行后全线改道：

- **调研结论（照搬可行性依据）**：旧项目解耦规范——UI 全部经由单一 MediaLibraryViewModel 持有 LocalMediaRepository **接口**（实现可整体替换），实体纯 Kotlin 数据类，MediaBrowserLogic 为零 IO object 纯函数；技术栈 Coil 3.4 + Media3 1.8 + ViewBinding，与新端主力库一致。已知泄漏点：约 10 处 Fragment 直取 appPrefsManager、MediaDetailFragment 4 处 content:// URI 直消费、ViewModel 自带 contentResolver/MediaStoreObserver——均在移植批次任务书内逐条列明改造。
- **ADR-0013 新建**：照搬范围（ui/ 层 15 Fragment/19 XML/5 自定义控件 + AppContainer + 主题，包名沿用 com.qimeng.media）+ 禁搬清单（scan//ScanUseCase/AutoSyncUseCase/BackupManager/本地缩略图解码管线/旧 repository 实现）+ 后果（放弃 Compose/Hilt 定论、minSdk 26→31、observe* Flow 需 Room 缓存桥接为最大实现风险）；INDEX.md 同步。
- **HANDOVER_APP.md 按新路线重写**：八批次改序——M4-0 工程基建+登录 / M4-1 数据层网络化（风险点先行验证，接口按 UI 调用面渐进实现）/ M4-2 列表族 / M4-3 详情页（BiliPlayerView 837 行照搬+断点续播叠加+4 处 URI 消费点改造清单）/ M4-4 离线上报 / M4-5 上传 / M4-6 缓存+设置+统计页（照搬路线下统计/我的/作者页全部纳入，原"不在 M4 范围"边界作废）/ M4-7 验收；通用约束新增照搬纪律（算法不搬不复算、代码实测行为优先于规格书）。
- **铁律口径收窄（原「禁止搬运旧项目实现代码」→「禁止搬运单机生态代码」）**：AGENTS.md（项目一句话 + 旧项目关系节）、AI_README_FIRST（禁止行为第 2 条 + Kotlin 代码规范行）、android/README.md（技术栈定论全面改写：View 照搬、AppContainer、包名沿用、禁搬清单）、PROJECT_PLAN M4 条目（「Compose 复刻交互」→「旧 UI 整体移植」）、HANDOVER（当前待办第 2 条 + 领域知识库节）六处同步。
- **算法提取状态确认（用户问题答复，无代码改动）**：GUIDE_ALGORITHM 全部条目已在 M3 落进服务端并测试锁定（推荐十维+自适应回收+三后处理+冷启动/排行榜/筛选枚举/SourceMatcher 130 组/统计口径/作者双体系/日期分组/旧数据迁移）；缩略图三级解码被「服务端 ffmpeg 出图 + HTTP 直链」架构性替代，非缺失。
## 执行路线定稿：UI 收尾三批 + M4 八批次任务书（2026-09-04 第十九笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户拍板当前路线 = Web UI 收尾三项 + M4 完整任务（未导入内容两项搁置，不催不问），要求产出可交给执行 AI 直接执行的规划与约束：

- **新建 `docs/HANDOVER_APP.md`**：M4 拆八批次（M4-0 脚手架 ~ M4-7 整体验收）逐批任务书——冻结决策（applicationId=media.qimeng.app、minSdk 26、依赖白名单制、断点续播 5s 节流、上传队列并发=1、离线队列环形 5000 条等）、验收命令（make app-build/app-test/app-lint 三件套 + 每批单测）、存疑停手点九条总则；范围边界冻结=我的页/统计页不在 M4（用户拍板后可追加批次）。执行环境 2026-09-04 实测就绪（Android Studio jbr21/SDK build-tools 35/emulator WHPX 加速，仅缺 AVD 由 M4-0 首步创建）。
- **HANDOVER_UI 新增 §5.9**：UI 收尾三批任务书（顺序冻结 W-1 上传入口 → W-2 confirm 弹窗 → W-3 ArtPlayer），逐批冻结设计（上传落点=文件管理页+切路由 abort 整队；弹窗=radix AlertDialog+原型 token、不引入红色 token；ArtPlayer=不建 ADR+官方 API 撑不起时间轴标记即停手）与验收标准。**发现项记账**：现版详情页为极简重建版（裸 img/裸 video），图片查看器/互动行/批次导航三件旧壳体验不在三项待办内，是否补齐待用户拍板。
- **HANDOVER/PROJECT_PLAN 同步**：执行路线行（UI 收尾→M4→M5）、「当前待办」改写（未导入内容两项标搁置）、接手第一步补第 6 条（Android 读 HANDOVER_APP）、M4 节头指向批次表（本文件勾选仍为进度真相）。
- **现状澄清**（规划调研结论）：android/ 零应用代码（仅 README 技术栈定论 + git 忽略的 sdk/ 生成物）；「M4 播放端基座」（commit 9773213）是服务端前置件（断点续播端点+ffprobe 元数据）而非 App 代码；CI 现四 job 无 Android job，由 M4-0 补第五个。
## 搜索页默认全部 + 对齐重校准：口径变更与锚定修复（2026-09-04 第十八笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户两条拍板：搜索结果默认应为全部内容；胶囊行对齐指文档视觉对齐规则（↔ 侧栏「首页」项中心）：

- **口径变更（用户拍板）**：搜索默认分区「常规」→「全部」（includeCos=常规∪COS）——库内容大头是 COS（5558 vs 765），默认排除致多数搜索词零结果；DOMAIN_RULES §3 分区条 + §6 浏览流清单同步（原第五笔"搜索默认排除"口径废止，「常规」=主动切换）。
- **对齐锚定修复**：第十六笔把胶囊行插在页面顶部，把类型行推下 74px 破坏 HANDOVER_UI §4.5 规则 3 锚定——胶囊行移至排序行之后（两行锚位不动，横向 24px 贴线、行距交 .page gap）。
- **对齐定值重校准（根因=定值过时）**：stype-count 徽标加入后类型行高 37→48.9px，旧 -14.9px 定值在宽窄视口**本来**就偏 +2.6px（非本次引入）；窄屏（≤499px）按钮被 flex 压缩、文字竖排换行致行高暴涨 73.7px、偏差 15px——`.stype/.sort-pill/.more-filter` nowrap+flex:none、`.stype-row` margin-top -17.5px + overflow-x:auto + 滚动条视觉隐藏（防滚动条占高推下排序行）、`.s-toolbar` margin-top 1.5px、media ≤499px gap 6px。
- **终测（浏览器静止态、徽标全渲染，474px/1000px 双视口）**：类型行中心偏差 -0.3px、排序行 +0.5px、横向溢出 0（≤1px 达标）。**新增教训记账**：对齐实测必须等异步徽标渲染完成，未渲染时量到的是假达标（第十六/十七笔即栽在此）。
- 文档：DOMAIN_RULES §3/§6、HANDOVER_UI §4.5 规则 3/5 同步。
## 搜索页分区胶囊 UI 修正：对齐与排序（2026-09-04 第十七笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户实测第十六笔胶囊后反馈两点：行未对齐、顺序应为「全部」在前：

- **对齐**：分区胶囊行水平内边距 16px → 24px，与 `.stype-row` 的 24px 对齐（prototype.css:887）。
- **排序**：`PARTITION_OPTIONS` 改为 全部/常规/COS——与相册页 all/regular/cos 三态顺序一致（用户拍板）；默认选中仍为「常规」（DOMAIN_RULES §6 隔离口径不变）。
## 搜索页 COS 分区入口补缺：搜索触达 COS 内容（2026-09-04 第十六笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户反馈「首页搜索 cos 内容没返回」：

- **根因**：DOMAIN_RULES §6 隔离口径下 COS 文件默认排除在搜索流之外（第五笔拍板），服务端 `/assets` 的 cosOnly/includeCos 三态参数早已就绪，但搜索页（SearchPage）组装参数时从未传分区参数、UI 也没有分区切换入口——COS 内容（5558 项）在搜索流完全不可触达；首页 cos tab 仅浏览流无搜索框，相册页第五笔已加分区胶囊而搜索页漏了同款入口。
- **修复（复用相册页既有模式，默认口径不变）**：`search-state.ts` 状态机加 `partition` 档位（常规/COS/全部，默认常规=排除 COS 不推翻隔离口径）；`SearchPage.tsx` 类型 tab 上方加分区胶囊（复用 pill 样式）、listParams 唯一组装点映射三态（COS=cosOnly、全部=includeCos、常规=不传）；`use-assets.ts` 的 `useAssetsTotal` 加分区参数——类型徽标计数跟随分区口径，切 COS 后徽标不再是常规数字。
- **端到端实证**（dev-login 后 curl）：搜 COS 文件名片段默认 0 条 / cosOnly 1 条 / includeCos 1 条；常规分区默认口径未受影响（765 条）。无协议改动（参数已在 openapi）。
- **文档**：DOMAIN_RULES §3 全文搜索口径补「分区」条（三态开关 + 胶囊入口出处）。
## 手机局域网访问白屏修复：crypto.randomUUID 非安全上下文降级（2026-09-04 第十五笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户反馈同一网络手机打不开 Web UI（电脑 127.0.0.1 正常）：

- **网络层诊断（无代码改动）**：服务端监听 `:8420`（全网卡）、防火墙有 qimeng.exe 入站 Allow 规则（专用网络）、本机 curl `http://192.168.1.2:8420` 返回 200——网络层全通；启动横幅 `http://YOUR_IP:8420` 是占位符提示（启动服务端.bat），真实局域网 IP 为 192.168.1.2（另两个 IPv4 为 VirtualBox/WSL 虚拟网卡）。
- **根因**：手机浏览器经 `http://192.168.1.2`（HTTP + 非 localhost = 非安全上下文）访问时 `crypto.randomUUID` API 不存在，`use-session.ts` 的 `ensureSessionId()` 在 React useState 初始化路径直接调用，整应用白屏崩溃（电脑 localhost 属安全上下文故无感）。
- **修复**：`web/src/hooks/use-session.ts` 新增私有 `randomUUID()`——守卫 `typeof crypto.randomUUID === 'function'`，缺失时降级 `crypto.getRandomValues` 拼 UUID v4（该 API 非安全上下文同样可用）；`web/dist` 已重新构建，服务端 SPA 托管按请求读盘（curl 实证返回新 hash 产物），手机刷新页面即生效、无需重启服务端。
- **质量**：oxlint 0 errors（15 warnings 均为存量，与本次改动无关）；tsc 构建通过。
## 交接文档进度对齐：阶段 B 全量口径 + 上传 UI 缺口记账（2026-09-04 第十四笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户要求把质量审查与清债同步进项目文档后的补齐（CHANGELOG/HANDOVER_UI 已随第十一/十二笔更新，本笔补 HANDOVER 总交接与待办账本）：

- **HANDOVER.md 当前进度对齐**：M3 最后一项「推荐偏好设置页」标记完成（2026-09-03 阶段 B 已接真）；「UI 路线现状」改写为阶段 B 全量完成口径（九页全接真、mock 退役——原文「搜索/我的/数据页仍 mock 待接」为过时表述，审查实证 mock 已全清）；新增 2026-09-04 全库质量审查+清零归档行；「明天待办」第 1/3 条从已完成项改指剩余待办；顶部「最后更新」行同步。
- **上传 UI 入口缺口记账（2026-09-04 审查发现）**：后端 POST /assets/upload（四道校验齐全）、SSE upload.done 桥接、设置页上传配置读写均就绪，但 web 页面无上传入口（`lib/constants.ts` 的 UPLOAD_PATH 常量无消费方）——记入 HANDOVER_UI §5.2 待做与 HANDOVER「UI 路线现状」；手机直传仍以 M4 App 为主通道。

---
## 一键启动自动打开 Web UI（2026-09-04 第十三笔）

执行 AI：GLM-5.3（主代理，ZCode）

用户痛点：每次双击 `启动服务端.bat` 后不知道怎么打开 UI——"UI 就是 8420 网页、无需单独启动"这件事对非编程用户不直观，此前要自己开浏览器输网址。

- **`启动服务端.bat`**：`go run` 前派生隐藏 PowerShell 探测线程（`start "" /min` + `-WindowStyle Hidden`）——500ms 轮询 127.0.0.1:8420 直至端口就绪（上限约 60s，覆盖首次编译），就绪即用默认浏览器打开 `http://127.0.0.1:8420`；服务端起不来（编译失败等）超时静默退出不开。启动横幅补三行说明（UI 会自动打开/无需单独启动/手动地址兜底），删除原"本机也可开 8420"行改为自动打开语境。窗口输出保持纯 ASCII（cmd 代码页纪律）。
- 探测逻辑双分支验证：端口监听 → DETECTED（触发打开）；端口不通 → TIMEOUT（静默）。旧服务端仍在跑时端口已通，新窗口浏览器照常打开（由旧进程供 UI，行为合理）。
- **HANDOVER.md「怎么跑起来」**同步改写：自动打开行为 + "UI 不需要单独启动，它就是 8420 的网页"显式说明 + 浏览器没弹的手动兜底地址。

---
## 质量审查清债（Web 端）：分层纪律收紧 + prototype.css 颜色收敛（2026-09-04 第十二笔）

执行 AI：GLM-5.3（主代理，ZCode；执行子代理×1 实施 + 主代理独立验收）

- **分层纪律（铁律 7 / ADR-0008 / api-client.ts 自述 import 禁令）**：LibraryManagePage 的 DirBrowser 内联 useQuery+SDK 直调迁入 use-libraries 的 `useDirTree`（全应用唯一不住在 hooks/ 的数据获取清零）；LoginGate 的 setToken 改走 `useAuthState()` 通道；AuthGate 的 401 订阅改走 use-session 新增 `useOnAuthFailed`（latest-ref 模式：调用方传内联箭头也不会反复订阅/解绑）。组件层 `lib/api-client` 引用 grep 清零（lib/hooks 层引用合法保留）。
- **prototype.css 颜色收敛**（用户拍板：统一掉、不影响 UI）：规则体内 30 处散落颜色字面量（审查定位 22 处 + 全量 grep 新发现 8 处）等值搬入 `:root` token（新增 36 个语义化定义；`-dark` 后缀 = .dark 覆盖规则专用值，覆盖规则保留原位只把值换 var 引用；同值不同语义不强行合并）。值逐字符相同（含 rgba 空格/渐变逗号）、选择器与属性结构零改动——**视觉零变化**。口径已固化进 HANDOVER_UI §5（新增第 8 条）：此后规则体内禁止散落颜色字面量，新颜色一律先定义 token。
- 门禁：`tsc --noEmit -p tsconfig.app.json` 零错误、`npm run build` 成功（dist 已更新供 8420 托管）、组件层 api-client 引用零命中、prototype.css 规则体散落颜色清零（剩余命中全部在 :root/.dark 定义块内）。

---
## 质量审查清债（服务端）：assets.go 拆分 + main.go 常量收敛 + 写错误注释（2026-09-04 第十一笔）

执行 AI：GLM-5.3（主代理，ZCode；审查=研究子代理×3 并行抽查 + 执行子代理×1 实施 + 主代理独立验收）

背景：用户要求全面审查架构与代码质量。三路研究子代理抽查结论——纪律执行整体优秀（安全三红线/协议↔实现 60 操作双向一致/迁移零改史/生成物历史零入库全过），存在数处轻微偏离，本笔清服务端部分（2026-09-04 审查报告全文见当次会话记录）。

- **assets.go 拆分**（原 658 行超 600 警戒线，内含 135/112 行两个超百行函数且无超线注释）：按列表/详情域拆为 assets.go（558 行）+ assets_detail.go（新建 172 行）。详情端点 135→79 行——标签/作者/角色三查询收敛 `fetchAssetRefs`、统计六查询收敛 `fetchAssetStats`（what 参数保留拆分前逐阶段日志文案）；列表端点 112→91 行——Asc/Desc 两分支对称的"截断探测+行装配"收敛 `buildListPage`，`listRowView` 抹平 sqlc 两胞胎 Row（同名字段无法泛型收敛，两个薄转换标注 sqlc 固有成本）消除重复装配。`internalErr` 迁 errors.go（包内约 20 处消费，公共错误 helper 归位，注释引 SECURITY 红线 7）。纯重构零行为变化。
- **main.go 裸调度参数**（代码卫生约束 3/5 违例）：`ReadHeaderTimeout` 提为 `readHeaderTimeout` 具名常量（注释说明 Slowloris 防御语义，与 shutdownTimeout 同值属两个独立决策）；轮询周期裸 `5*time.Minute` 改复用 `scanner.DefaultPollInterval`（单一来源）。
- **3 处忽略 HTTP 写错误补注释**（page.go/errors.go/server.go 的 `_ = w.Write`，措辞对齐 auth/middleware.go:87 既有先例）。
- 门禁：go vet 0、go test 14 包全绿（httpapi 针对性用例复跑 PASS）、gofmt 干净；主代理 diff 逐行复核确认无逻辑变化（详情端点全部字段赋值与原实现逐一比对等价）。

---
## 数据页「作者总览」卡行数收敛 Top5（2026-09-04 第九笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户反馈：真库 122 位作者全量渲染致栏目过长（原型 mock 作者少未暴露）。改为与相邻榜单卡（标签榜/作者榜）一致的行数——Top5 预览（按文件数降序，作者管理页「文件数量」档同口径），两行副标题保留；总量看头注「N 位作者 · 已关注 M」，完整列表走「管理」进作者管理页。

---
## 后端四缺口清零：上传富化 + 探针协议债归位 + 配置读写端点 + 客户端异常上报（2026-09-04 第十笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode；执行×3（含模型请求失败后 SendMessage 续跑原代理 1 次）+ 对抗审查×2 + 返工 1 轮）

用户拍板清掉后端剩余 4 个记账缺口。执行子代理 A/B 并行、C 串行（共享 openapi.yaml）；两路 reviewer 对抗审查：A/B 合格，C 打回（P1 空态崩页 + P2×2 失实/缺防御），按「小修续跑原代理」规则 SendMessage 原代理一轮返工修复并补 4 个测试。

### ① 上传富化（服务端既有缺口）

- 上传落库后调用 `scanner.EnrichAsset`（尽力而为模式照回收站恢复：失败/扫描器未装配仅 Warn，不炸上传；下次该文件 size/mtime 变化重 ingest 自愈）——手动上传的文件从此有出处/角色（normal 库）与 COS 作者/cos_work（cos 库），与扫描口径一致；EnrichAsset 只写富化列，不碰上传时探测的 duration/宽高。
- 测试：normal 命中（守望先锋_天使.jpg→出处+角色）、cos 库（作者目录→作者关联+cos_work+source 隔离）、noScanner 占位仍 201，共 3 用例。

### ② 探针协议债归位

- openapi `/healthz` `/readyz` 移至 `/api/v1/healthz` `/api/v1/readyz`（免鉴权白名单），协议自述「所有路径带 /api/v1」完全成立；根路径保留为运维探针别名（docker/k8s 惯例，共用同一 handler，503 DB_UNREACHABLE 行为一致）；make sdk 三端重生成（指纹比对零漂移）；docker-compose/GUIDE_API/OBSERVABILITY/llms.txt 引用同步。`/metrics` 根路径同族遗留另记账。

### ③ 配置读写端点（设置页持久化依赖）

- `GET/PUT /api/v1/config`（ClientConfig：scan.workers 1-4/thumbEdge 200-1600/upload.maxBytesMb 64-8192/autoAccept）存 kv_settings；PUT 影子结构验「两组+四叶子键齐全+类型正确」（缺 autoAccept 曾会 bool 零值静默关掉上传闸门——审查发现，已 400 拦截）后范围校验落库。
- **生效范围（诚实口径）**：upload.maxBytesMb=min(配置文件, kv) 实时生效；autoAccept=false 实时 403 UPLOAD_DISABLED；scan.workers/thumbEdge 为**预留字段**——全库无消费点（审查实证，此前误称"重启后生效"），保存后暂不生效，UI/openapi/文档四处口径已统一，待接线后升级文案。
- 测试：缺省/回读/越界/缺字段 7 例/边界值/上传上限覆盖/403/401。

### ④ 客户端异常上报通道（维护页异常表数据源）

- `POST/GET /api/v1/client-logs`：环形缓冲存 kv_settings（容量 200 丢最旧；解析失败按空表自愈不再 500）；POST 校验条数 1~50/level 枚举/message ≤2000 rune；GET 新→旧，**空态恒返回 `"items":[]`**（审查发现的 P1：null 会让维护页白屏，服务端+web select 双保险修复）。
- Web：`lib/client-logs.ts` 全局 onerror/unhandledrejection 上报器（队列满 10 条或 30s flush，失败静默防递归）；维护页异常表接真数据（时间/级别/消息/页面四列）；设置页扫描/上传卡接 GET 回填+保存 PUT（toast 同步生效范围口径）。
- 测试：存入读序/环形覆盖/校验/损坏自愈/空态/401。

### 门禁与审查

- make sdk 三端全过零漂移；go 14 包全绿（新增 16 用例）、golangci-lint 0、gofmt 干净；web tsc/build/lint 全绿（0 errors，存量 15 warnings 基线不变）。
- 审查实证要点：白名单无路径变体绕过面；EnrichAsset 不碰探测元数据（逐条核 SQL）；scan 字段无消费点（推翻"重启后生效"）；413 构造手法（Expect: 100-continue）真实有效。

---
## 常规作者关联丢失修复：TXT 匹配重放端点 + 文件管理页「重新匹配」按钮（2026-09-04 第八笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode；执行子代理实施 + 主代理数据恢复）

用户反馈卡片作者行只有 COS 文件生效、常规文件不显示。排查结论：**第七笔协议链路本身正确**（ListAuthorNamesForAssets 无 type 过滤、装配无过滤），根因在数据层——`asset_authors` 表 regular 关联为 0（qimeng.db.bak-20260831 备份中有 810 条），2026-09-03 重建库后 COS 关联由扫描器从目录结构自动重建（5558 条），而常规关联架构上只在 TXT 导入那一刻建立、无重放入口，TXT 片段虽完整保存在 kv_settings 却无人重放。

- **`POST /authors/import-txt/rebuild`**（openapi 先行 + make sdk 三端重建）：幂等重放 kv_settings 已存全部 TXT 片段→事务内复用 `rebuildAll` 重建常规作者-文件关联；不新增/修改/删除片段；无片段返回零值。计数口径：authorsImported=合并后作者数、filesMatched=（作者,去重作品）对匹配数（与导入响应同口径）。
- **web 文件管理页 TXT 卡**新增「重新匹配」按钮（`useRebuildAuthorTxt` hook，无片段禁用，成功 toast 作者/关联计数）。
- 测试：TestAuthorsTxtRebuild（导入→手工清关联模拟丢失→重放恢复→再放幂等不翻倍→片段列表不变）+ TestAuthorsTxtRebuildEmpty（空片段 0/0）。
- **真库数据恢复**（主代理执行）：触发重放恢复 122 位常规作者、关联 764 条（8-31 备份为 810，差异为当前库文件集自然演进）；冒烟验证常规文件 authorNames 返回真实作者名。
- 文档：GUIDE_API 作者行补 rebuild 端点说明。

---
## 原型七组缺陷 web 正式端移植（协议 AssetSummary.authorNames 全链路）+ 原型 CSS 双缺陷修复 + 归档会话质量审查（2026-09-03 第七笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode；执行×3 + 对抗审查×2 子代理）

背景：用户发现第六笔修复做在 `media-ui-prototype/` 原型而日常使用的是 `web/` 正式端（8420），要求盘点 WorkBuddy 全部归档会话改动并对抗审查后完成移植。审查结论（4 笔现役提交）：4320f3e 含一处 P2（相册页缺省分区漏传 partition）但已被 7d638d5 自愈；7d638d5、8a6b63f 合格；265a1a8 原型层另有 2 处 CSS 实缺陷（TXT 导入面板样式选择器 id 脱靶、作者行副标题两行布局在 flex 单行下不成立）。本笔全部修复：

### ① 协议扩展 + 卡片作者行/时长角标全链路（api+server+web）

- **openapi.yaml 先行**：AssetSummary 新增 `authorNames: array<string>`（该资产全部作者显示名，常规∪COS；GET /assets、GET /recommendations 返回，无作者为空数组；详情/历史/排行榜端点省略）；`make sdk` 三端生成物重建（validate→go→ts→kotlin 四步全过，重跑零 diff 复核）。
- **服务端**：`ListAuthorNamesForAssets`（json_each + narg 批量形状，ASCII-only 注释）+ handler `fillListAuthorNames` 装配（页大小一次批量查询无 N+1、失败仅记日志不炸列表、map miss 填空数组）；recommend.sql 补 `duration_ms` 列（推荐流角标数据）；新增 2 测试锁定多作者码点序/COS 作者/无作者空数组/视频图片 durationMs 差异。
- **web**：`assetToCard` 收敛全站 6 个 MediaCard 调用方——up=作者行（`formatCardUp`：首作者、多作者「名 等N」、无作者回退 source）、duration 角标仅视频传（`formatDuration` m:ss / h:mm:ss，空值不渲染）；MediaCard meta 改两行结构（标题下作者行、日期次行），样式与原型第六笔逐字对齐。

### ② 作者榜口径 + 作者副标题 + 相册时间分区移植（web）

- **作者榜 = 常看作者**（DataPage/RanksPage）：过滤 viewCount>0、按浏览数降序（DataPage Top5，RanksPage 全量），行计数=浏览次数，note 改「按浏览」；标签榜 fileCount 口径未动。原实现按作品数排序且 0 浏览占位（旧缺陷，审查确认服务端 rankings 热度口径与"常看"是两套，前端口径修正）。
- **作者总览/作者管理页行副标题**：RankRowList 扩展可选 sub 字段（两行结构，「N 个文件 · 浏览 M 次」，count 空串不渲染徽标）；AuthorsPage 行同步补副标题；`authorDisplayName` 助手给 COS 作者追加「 ·COS」标识（对照原型 authorDisplayName）。
- **相册时间分区**：`dateLabel` 助手（今天/昨天/2~6 天→周X/更早 yyyy-MM-dd/空值不分组，复刻旧版 MediaBrowserLogic）；AlbumsPage 网格分组渲染（组内保持列表原序、组间按组首 modifiedAt 降序、空日期组固定最后且不渲染组头；分区/类型胶囊切换后分组纯函数自动正确，翻页同 label 归并）。

### ③ media-ui-prototype CSS 双缺陷修复（style.css）

- TXT 导入面板选择器 `#importAuthorFile/#importAuthorText` → `#authorImportFile/#authorImportText`（与 index.html/app.js 实际 id 对齐，面板样式恢复生效）。
- 作者行副标题两行布局：`.a-list li`/`.rank-card li` 加 `flex-wrap:wrap`，`.a-sub,.rank-sub2` 加 `flex-basis:100%`，`.a-list .a-sub` 加 `order:1`（按钮留首行右侧）；无副标题的普通榜单行无副作用。

### ④ 其他

- `docs/HANDOVER.md` 「特色纪律」删除「并发子代理上限 2 个」条目（用户要求，编号重排）。
- 修正第五笔 CHANGELOG「六查询」措辞（实为四查询补 source 谓词）。
- 门禁：go test 14 包全绿、golangci-lint 0 issues、gofmt 干净、`make sdk`/`sqlc generate` 重跑零 diff、web tsc/build/lint 全绿（0 errors，15 warnings 全存量）；dateLabel 6 边界用例验证通过（web 无测试框架，脚本验证后清除）。

---
## media-ui-prototype 七组缺陷修复：榜单浏览口径/作者关联副标题/首页卡片元信息/相册时间分区/COS 搜索与推荐词/作者 TXT 导入（2026-09-03 第六笔）

执行 AI：DeepSeek-V4-Flash（主代理，WorkBuddy；执行子代理 ui-fix-dev@media-ui-fix）

用户对照旧版 Android 应用（QimengMedia）逐页验收 media-ui-prototype，报 7 组行为差异，要求"内容榜=常看文件、作者榜=常看作者、作者总览=作者管理"等旧版语义落地。仅改 `media-ui-prototype/`（app.js/index.html/style.css，纯 HTML/CSS/JS + mock，无框架/无后端）：

1. **数据页作者榜 = 常看作者**：只显示浏览>0 的作者、按浏览数降序取 Top5（旧版 DataStatsFragment renderTopAuthors 口径——旧实现按作品数排且 0 浏览也显示）；完整榜页同步。
2. **作者总览 = 作者管理入口卡**：每行补「X 个文件 · 浏览 Y 次」双关联副标题（旧版 AuthorListFragment onBindViewHolder 口径），COS 作者带 ·COS 标识；关注数变化实时同步。
3. **内容榜 = 常看文件**：mock 15 条全为正浏览量、按浏览降序、0 浏览不显示（旧版 renderTopFiles 口径）。
4. **首页/相册/搜索卡片元信息**：抽 `cardInner()` 统一模板——标题下作者单独一行、日期次行；视频时长角标仅 `m:ss` 格式显示（图片/动图/空串不显示，旧版卡片结构）。
5. **相册页按时间分区**：复刻旧版 MediaBrowserLogic.dateLabel——今天/昨天/周一~周日（距今 2~6 天）/yyyy-MM-dd 分组标题 +「N 项」计数，筛选胶囊先过滤后分组、空组不渲染。
6. **COS 内容生效 + 推荐搜索**：顶栏 cos tab 过滤卡片流/搜索池；搜索关键词真正参与结果过滤（补 SEARCH_STATE.query + matchQuery 六维子串命中：文件名/作者（去@前缀）/分区/作品/角色/类型 + cos 标记，搜 cos/COS/Cosplay 命中 cos:true 条目——根因：旧实现关键词从未接线，搜任意词结果相同）；推荐搜索区从占位文案改为 mock 推荐词 chips 点击即搜。
7. **作者管理页「导入 TXT」入口**：原只有列表找不到添加方式。新增导入按钮+面板（选 .txt 文件读取 / 粘贴文本），解析兼容旧版 AuthorImportUseCase 三种格式（一行一作者/逗号别名取首/冒号文件列表取作者名），去重并入 mock 并实时刷新榜单/总览/列表，纯内存态。

审查：主代理逐条核对 diff（333+/80-）、`node --check` 通过、56 处 DOM 引用无缺失（含打回一次：搜索关键词未参与过滤已补）、matchQuery 独立单测 ALL_PASS（cos 4 条/虚空行者 2/夜景 1/夜空机位 2/空词全量 13）、本地 8099 服务 curl 200。**待用户浏览器验收后决定是否保留；提交前未动 web/server/android。**

### 已知限制（记账）

- 原型层为 mock 数据：作者浏览聚合未模拟旧版时间窗口（用 browse 字段直排）、TXT 导入不持久化（刷新复原）——真实数据版本在 web 端（阶段 B/C）接入时需按 DOMAIN_RULES 口径实现时间窗聚合与持久化。
- 上传路径不做富化（source/角色/作者/cos_work，既有缺口）；ArtPlayer 播放器 UI、confirm 原型弹窗、设置页三卡持久化、客户端异常上报通道沿用记账。
- 相册页徽标仍为 4 个独立 facets 请求（本地聚合量级可控，无合并端点需求）。

---
## 相册作者/角色行语义修正（旧版「全部」tab 口径）+ 首页 cos/排行榜 + 文件管理 TXT 卡（2026-09-03 第五笔）

执行 AI：DeepSeek-V4-Flash（主代理，WorkBuddy）

用户指出 4320f3e（第三笔）相册四维聚合没做完的三件事 + 署名排查：① 相册「作者」行应是 **COS 作者 + 常规出处分组两个集合**（现在只有 COS 作者）、角色行缺 COS（作品）；② 首页 cos/排行榜 tab 依旧不生效；③ 文件管理缺 TXT 单独卡片（旧项目「数据管理」）。另按项目记忆修正第三/四笔署名工具名 ZCode→WorkBuddy（实际运行环境）。

### ① 相册作者/角色行语义对齐旧版「全部」tab（协议+服务端+Web 同改）

- **作者行语义（用户拍板，替代原 authorId 全量作者）**：作者行 = 常规**出处分组**（kind=source，含「其他」桶 = NULL source 且非 COS 关联；COS 资产永不进出处——browse.sql 的 source_is_other 谓词全查询加 NOT EXISTS cos 排除，与旧版 groupBySource(!isCosFile) 一致）∪ **COS 作者**（kind=author）按分区合并——常规分区只出处、COS 分区只 COS 作者、全部（all）分区两者合并。常规 TXT 作者表行**不进**作者行（作者体系保留在 /authors 与集合页）。FacetBucket 加 `kind` 枚举（source/author/character/work）——同一胶囊行混合两种候选时前端按 kind 分派筛选参数。
- **角色行**：全部（all）分区 = 常规角色名（kind=character）∪ COS 作品名（kind=work）合并（第三笔只合并了 author 维度，角色维只在单分区各自生效——已修）；character 与 work 同属角色行，排自身时一起忽略。
- **FacetSourceCounts 新查询**（facets.sql）：非 COS 关联资产按 source 分组（含 NULL 行=「其他」）；FacetAuthorCounts 收敛 `au.type='cos'`；其余四查询（角色/类型/分区两维）统一补 source 谓词（author 行选中对其他三维生效）与排自身维度表文件头。
- **Web 相册页**：修分区默认值 bug——原 `partition !== regular ? {partition} : {}` 在缺省分区不传参，服务端按 all 处理致作者行语义错乱；现在 partition **恒显式传参**。作者/角色值行候选携带 kind，点出处胶囊→source 筛选、点 COS 作者→authorId、点角色→character、点 COS 作品→work；分区缺省改「全部」（= 旧版「全部」tab，两集合并排可见），切分区清作者/角色（候选命名空间随分区变化）。作者行/角色行前补「全部」胶囊（value='' 清除本行）。
- 测试：facets_test 重写 2 用例 + 夹具补 source（a.jpg 无出处→「其他」、b.jpg kemono、c.mp4 视频无出处）锁定新口径（作者行 全部=其他2+kemono1+COS作者2、regular 只出处、cos 只 COS 作者、选角色后作者行=含该角色资产的出处、选出处后角色行排自身全量等）；go test 14 包全绿。

### ② 首页 cos/排行榜 tab 生效（Web，URL 驱动）

- 根因：HomePage 忽略 `?tab=` 恒渲染推荐流；顶栏 rankPeriod 是 TopBar 私有 state，榜单数据无入口。修复：tab/周期收敛 URL 参数（`?tab=recommend|cos|hot`、`?period=day|week|month|year`，TopBar 只写、HomePage 只读，刷新直达不丢态）；`lib/home-tabs.ts` 双端共享常量。hot 内容榜 = ContentRankGrid + useRankings(period)（纯热度口径 DOMAIN_RULES §2，周期行缺省日榜）；cos = GET /assets cosOnly 浏览流（COS 独立入口落地）。

### ③ 文件管理 TXT 卡片（旧项目数据管理「TXT导入作者」）

- **服务端**：GET/DELETE `/api/v1/authors/import-txt`（openapi→make sdk→sqlc）——GET 返回已导入片段名升序；DELETE 移除片段并从**剩余片段**统一重建（204/404）。authors.go 重建核心拆 `mergeTxtSources/upsertMergedAuthors/insertLinks/rebuildAll` 四件套复用：删除路径以「删除前」全量片段为删关联目标（作者只出现在被删片段时旧关联一并清掉）、「删除后」剩余为重建来源；作者行保留不级联删（openapi 语义）。删关联**不按 type='regular' 全删**——旧项目迁移（import.go）也写 regular 关联，只能删已导入片段涉及作者的关联。
- **Web**：use-authors 增 useTxtImportedFiles/useImportAuthorTxt/useDeleteImportedTxt；文件管理页（/app/maintenance/files）新增「作者 TXT 导入」卡——选 .txt 导入（读文件内容 POST，同名覆盖）、片段列表、逐份移除（toast 导入计数/成功反馈）。
- 测试：TestAuthorsTxtManageList（GET 升序、删单片段并集不受影响、作者只在被删片段→关联清空行保留、删光→空列表、404）+ 既有导入全链回归。

### 已知限制（记账）

- 上传路径不做富化（source/角色/作者/cos_work，既有缺口）；ArtPlayer 播放器 UI、confirm 原型弹窗、设置页三卡持久化、客户端异常上报通道沿用记账。
- 相册页徽标仍为 4 个独立 facets 请求（本地聚合量级可控，无合并端点需求）。

---

执行 AI：GLM-5.3-Flash（主代理，WorkBuddy）

HANDOVER_UI §5 待办项：搜索页类型 tab 从 综合/视频/图片 三档扩为四档（+动图=mediaType animated_image，计数走 useAssetsTotal）——与相册页类型维、协议 MediaType 枚举（image/animated_image/video）对齐。纯前端小改（SearchPage.tsx 四处），协议零变化；tsc + build 通过。用户拍板暂停后续小任务（confirm 原型风格弹窗、上传路径富化两项仍记账待做）。

---
## 相册四维聚合：GET /assets/facets + migration 0008 cos_work + 相册页四维胶囊（2026-09-03 第三笔）

执行 AI：GLM-5.3-Flash（主代理，WorkBuddy）

上一会话半成品接续（HANDOVER_UI §5 待办「相册作品/角色聚合维度端点」：协议与 facets.sql/migration 0008 已写、停在生成链重建之前），本会话完成生成链、scanner 写入、handler、web 接入全链路。

### 服务端（先 openapi 后 make sdk，禁止手改生成物）

- **migration 0008_cos_work**：assets 加 `cos_work` 列（COS 库 `作者/作品/文件` 第二段目录名；NULL=无作品子目录），instr/substr 存量回填（实测 5558 行 COS 资产 5557 命中、152 去重作品）+ 部分索引 idx_assets_cos_work（WHERE cos_work IS NOT NULL）。
- **GET /assets/facets**：相册四维筛选候选端点——partitions（全部/常规/COS 恒三项，常规=全部−COS）、authors（key=AuthorID）、characters（常规分区=角色规范名、COS 分区=cos_work 作品名，NULL 不列入）、types（all/image/animated_image/video 固定四项，key 可直接回传 GET /assets 的 mediaType）；全部查询带分区/作者/角色/类型/搜索全参数。**排自身口径**：计每维候选时忽略该维自身当前选择（character 与 work 同属角色维，两者一起忽略）——前端每维独立请求各缺自身参数，即得「排除自己后还剩什么」的正确计数。
- **GET /assets** 加 `cosOnly`（只要 COS）与 `work`（按 COS 作品名筛）参数；COS 隔离升级三态开关：includeCos=1→全量、cosOnly=1→只 COS、两者皆 0→常规（排除 COS，历史默认）；cosOnly 与 includeCos 同真时 cosOnly 优先（handler 层实现，SQL 三态谓词保持中立；sqlc 三值逻辑要求 cos_only 恒传 0/1——NULL 会把非 COS 行也排除）。
- scanner：`ingestCosFile` 扫描入库写 cos_work；`recomputeCosAuthor` 作者关联重算同步覆盖 cos_work（含清空 NULL）——移动/改名后作品目录变化自动跟随。
- 顺手修既有缺口：**回收站恢复后不做富化**（TrashMeta 不存 source/角色/作者关联，恢复后 mtime 未变重扫跳过）→ 恢复 UpsertAsset 后尽力而为 EnrichAsset 重算（失败仅告警，待重扫自愈）；**上传路径不做富化属同类既有缺口，本次未修记账**。

### Web（铁律 7：hooks 层接数据，页面零 SDK 调用）

- `hooks/use-assets.ts`：AssetListParams 加 work/cosOnly；新 `useAssetFacets`（GET /assets/facets）与 `facetToOptions` 助手；导出 Partition/FacetBucket/AssetFacets 类型。
- `pages/AlbumsPage.tsx` 重写为协议四维（原两维：分区=出处+类型）：分区（全部/常规/COS，缺省「常规」延续历史口径）、作者、角色（常规分区=角色名 / COS 分区=作品名，切分区重置角色——两者命名空间不同）、类型；四个 useAssetFacets 请求各缺自身参数实现排自身徽标计数；filter-card 布局与对齐数值未动（CSS 零改动，维度行改为协议四维循环渲染）。

### 测试与实测

- go test 14 包全绿（新 facets_test 4 用例：分区栏恒三项 / 三分区四维候选 / 排自身口径 / COS 参数与 cosOnly 优先级；scanner 测试补 cos_work 断言；store 回退序列补 0008 步；search 测试显式传 cosOnly=0 防 NULL 三值逻辑）；golangci-lint 0 issues（顺手清 libraries.go 3 处既有 unconvert）；tsc（-p tsconfig.app.json）与 npm build 干净。
- curl 实测（用户 PC 真库 6413 文件）：分区栏 全部 6413 / 常规 855 / COS 5558（和守恒）；COS 分区角色栏=作品名（崩坏星穹铁道 卡芙卡 286 / 黑天使 240 / …）；COS 作者栏 cos_ 前缀双体系（蠢沫沫 2852 / …）；类型栏 all=image+animated_image+video 守恒。
- UI 浏览器静止态对齐实测未做（CSS 零改动、风险低；用户拍板自行打开 8420 验收）。

### 已知限制（记账）

- 相册页徽标 = 每维独立 facets 请求（共 4 个）各缺自身参数；本地聚合量级可控，暂无合并端点需求。
- 上传路径不做富化（source/角色/作者/cos_work 全缺，既有缺口待做）。
- `.tmp-verify/` 为上一会话验证残留目录（t8.db），未入库未删。

---
## 全部界面接真实数据：搜索/我的/数据/榜单/集合/作者/顶栏/推荐偏好/维护监控（2026-09-03 第二笔）

执行 AI：DeepSeek-V4-Flash-Vision-Exp（主代理，ZCode；研究×2、执行×3、审查×1 子代理）

用户需求："现在把所有界面都接入数据，数据页面和我的页面以及搜索等等全部接入真实数据"——web 端剩余 mock 页面（pages/mock.ts 全量退役）。流程：研究（web 现状+后端能力核对）→ 方案基线（用户拍板：数据页 6 卡换真库数据、设置页扫描/上传/界面卡本次不动）→ 执行三片 → 对抗审查（需返工 3 项已修复）→ 实测。

### 服务端协议扩展（7 点，先 openapi 后 make sdk，禁止手写 SDK）

- `GET /assets` 加 `liked` 筛选（likes 表任意日行=已赞）；`sort` 枚举加 `favoriteAt`（favorites.created_at，仅 favorite=true 语义成立）——browse.sql 三查询同步谓词/排序键。
- **新增 `GET /history`**：观看历史——每资产最近一次 open 事件时间倒序（每资产一条），FROM assets 锚定排除已删（事件流无 FK，ADR-0005）、默认排除 COS 作者关联（includeCos=true 包含）、keyset 游标 (last_viewed_at, asset_id) 分页；HistoryItem=AssetSummary+lastViewedAt+**durationMs/lastPositionSeconds**（已看完徽标数据源，reviewer 返工补齐）。
- `GET /stats/trends` range 加 `7d`（近 7 天逐日）/`90d`（近 90 天逐日）；`GET /rankings` period 加 `quarter`（近 90 天窗口，语义沿用"窗口内活跃热度榜"）。
- `AssetSummary` 加可选 `viewCount/playCount`（仅 rankings 填充实测值，浏览列表传 nil 保持轻量）；`Author` 加 `viewCount`（作者作品累计浏览次数，作者页"经常浏览"排序数据源）。
- 新测试 8 用例（history 顺序/游标/COS/已删 + durationMs 返工补断言；liked 双向；favoriteAt 排序；7d/90d 桶数与守恒；quarter 计数；authors viewCount）——`go test ./...` 14 包全绿。

### Web 接真（铁律 7：页面零 SDK 调用，数据全走 hooks）

- **共享层**：`hooks/use-assets.ts`（AssetListParams 补全 13 个协议参数、`assetToCard` 摘要→卡片映射、`useAssetsTotal` 计数徽标）；新 hooks `use-authors`（列表+关注 PUT）/`use-tags`（列表+新建）/`use-stats`（overview/trends/rankings）/`use-history`（无限翻页）/`use-prefs`（9 维 GET/PUT+四预设常量）/`use-system-status`（2s 轮询）；`lib/format.ts` 补 formatCount/formatDateTime/localDateKey；路由键常量 迁移 `lib/route-keys.ts`，**`pages/mock.ts` 全量删除**。
- **搜索页**：q 从"不参与过滤"修复为真传 FTS5（六维：文件名/路径/标签/角色/作者/出处）；类型/排序/顺位/播放次数（**playRange**，reviewer 指出原误用 viewRange 已修）/文件大小/时间范围/年份区间/标签池+模式全部真实传参；类型徽标=totalMatched 真计数；结果无限滚动+点击进详情；音频档删除（协议 MediaType 无 audio）+「+ 添加」标签=POST /tags 后选中。
- **TopBar**：搜索历史 localStorage（key qimeng_search_history，20 条去重最新在前，清空按钮）；推荐搜索词=tag/作者 fileCount Top6 合并去重取 8。
- **我的页**：资料卡（库数/文件总数/容量=useLibraries+stats/overview）；关注 Tab（followed 过滤+关注 toggle）；收藏 Tab（favorite=true + sort=favoriteAt + 加载更多）；浏览历史 Tab（今天/昨天/更早分组、已看完=lastPositionSeconds>=durationMs/1000、观看时间、标题过滤、点击进详情）。
- **数据页**：6 指标卡真库数据（总文件/图片/视频/容量/今日浏览/累计浏览，用户拍板）；时段四档（近 7/30/90 天/全部）联动 trends（7d/day/90d/all）与内容榜（week/month/quarter/all）；双环（类型分布+浏览量占比近30天）；内容 Top5=rankings（formatCount 角标+点击详情）；标签/作者榜=/tags、/authors fileCount Top5（库存口径与原型一致）；作者总览=140 位真数+已关注。
- **完整榜单页**：内容榜=rankings 全量（日/周/月/年周期由数据页入口决定，子页固定全周期——现状无周期胶囊）；标签/作者榜全量降序。
- **集合子页**：tag/author 按名找实体→真资产网格（**includeCos=true 修复**：COS 作者如"蠢沫沫"2852 文件默认被排除导致空态，reviewer 实测覆盖）。
- **作者管理页**：140 位真作者（10 位有文件）、体系胶囊 常规/COS、排序 默认/经常浏览(viewCount)/文件数量、关注 toggle PUT。
- **设置页**：新增「推荐偏好」卡——4 预设（点击即保存）+9 维滑杆（拖动后保存），预设数值逐字抄 DOMAIN_RULES §1.3 表，PUT 全量 9 字段（协议整体替换语义）；其余三卡按用户拍板不动。
- **维护页性能监控**：4 圆环（CPU/内存/系统盘/存储合计=system/status 实时）、指标卡（上下行速率=2s 轮询本地差分、存储合计、运行时长）、网络曲线（60 点环形采样）；客户端异常表改空态（无上报通道，记账）。
- 新组件：`components/data/{TrendChart,DonutCard,ContentRankGrid,RankRowList}`、`pages/SearchFilters.tsx`+`search-state.ts`（搜索页拆 259 行内）；维护页速率曲线本地实现。

### 实测（用户 PC 真库：4 库/6413 文件/188.9 GB）

- 搜索 "2b" → 38 条"尼尔 机械纪元 2B"真资产；搜索"请不要带走我"（mock 历史词）→ 真无结果提示（证明链路真）；类型徽标 视频 392/图片 462。
- 我的页：资料卡 4 库/6413 文件/188.9GB；收藏 0 空态；浏览历史"今天 · 7"条真记录（9-3 16:53~16:54 观看）。
- 数据页：6 卡/趋势（09-02、09-03 两桶）/双环（图片 93.6%）/内容榜 Top5（最终幻想/拳皇/铁拳8/守望先锋）/作者榜（蠢沫沫 2852/橙子喵酱 451/…）/作者总览 140 位。
- 集合页：作者"蠢沫沫"·2852 个文件真网格（修复前空态）。
- 作者关注 toggle：关注↔已关注双向（PUT 生效+恢复原状）。
- 推荐偏好：点"新鲜优先"→9 滑杆变 0.10/0.05/.../0.35（与预设表逐字一致）→恢复"均衡推荐"→GET 回读默认值；首页推荐流正常渲染。
- 维护页：CPU 8%/内存 16.1/31.7GB/系统盘/存储合计 3382.2/4652.1GB/下行 7KB/s 上行 8KB/s/运行时长 0 天（重启后真实值）。
- 顶栏：推荐搜索=蠢沫沫/橙子喵酱/…；输入 2b 回车→URL /app/search?q=2b+历史入库+重新聚焦显示"搜索历史 · 2b"。
- 端点级：8 个新端点/参数 curl 全 200；history 返回 durationMs=14000（返工后）。

### 对抗审查（reviewer，全新上下文）与返工

- **需返工 3 项已修复**：① history 缺 durationMs/lastPositionSeconds→"已看完"徽标失效（history.sql SELECT 补列+组装+测试断言+curl 验证 14000）；② 维护页未交付（system/status 轮询+曲线+空态，本次补齐）；③ 搜索"播放次数"误映射 viewRange→playRange（DOMAIN_RULES §3 两行口径分开）。
- 审查独立重跑：go test 14 包全绿、tsc 干净、生成物符号核验通过、mock 残留 0。

### 已知限制（记账）

- 搜索首查较慢（FTS5 子串 instr 全表扫描，非本次引入；6413 文件约 2-4s）。
- 相册「作品/角色」聚合维度端点待做（阶段 B 已接分区+类型两维）；设置页三卡保存待配置端点；客户端异常上报通道未建（维护页空态）；ArtPlayer 播放器 UI 待做；SW 旧缓存会短暂显示旧 UI（PWA 特性，清缓存/等待新版接管）。
- 数据页内容榜子页固定全周期（无周期胶囊）；"本周"按周一为起点。

---
## 本机启动脚本开启开发免密登录：QIMENG_AUTH_DEV_MODE=1（2026-09-03）

执行 AI：DeepSeek-V4-Flash-Vision-Exp（主代理，ZCode）

用户需求："客户端启动服务器登录就要密码，输入后还显示密码错误，在没彻底做好之前都不需要密码"。根因：服务端 `auth_dev_mode` 默认 false 且 `启动服务端.bat` 未设置该 env——web 端免密通道（LoginGate 挂载自动调 `/auth/dev-login`）404 后回退到密码表单，输错密码（管理密码记录为 test-password-001）即报"密码错误"。按 HANDOVER 用户约定（项目未完成前不要密码流程）恢复免密。

### 变更

- `启动服务端.bat`：新增 `set QIMENG_AUTH_DEV_MODE=1`（英文注释注明生产/远程部署必须移除，指向 docs/SECURITY.md「开发模式」节）。
- 文档：SECURITY.md「开发模式」节新增"本机开发脚本"说明；HANDOVER.md 用户约定第 1 条同步（已默认写入启动脚本）。

### 实测（用户 PC 实机）

- 重启服务（旧的 qimeng.exe 进程已停止）后：`POST /api/v1/auth/dev-login` → 200 返回 64 位 hex token；带 token `POST /api/v1/auth/verify` → 204；首页 SPA 正常（title 绮梦影库）。
- 用户浏览器刷新 `http://127.0.0.1:8420` 即免密直达 UI；旧 token 因 dev-login 重铸失效属预期（与 /auth/login 同语义，签发即重铸）。

---
## 阶段 B 浏览链路接真实数据：首页推荐流/相册/详情页（图片大图+视频直链播放）（2026-09-03）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户需求："我自己添加库去测试，可以显示实际内容"——浏览面从 mock 换成真数据。服务端零改动（M1~M4 协议基座全就绪），纯 Web 接线。

### Web 实现

- `hooks/use-assets.ts`（浏览面数据层）：`useRecommendations`（GET /recommendations，M3 十维算法）、`useAssetsInfinite`（GET /assets 游标无限滚动，参数含 libraryId/mediaType/source/sort/order/q）、`useAssetDetail`（GET /assets/{id}，签名原件直链）、`useSources`（GET /sources 出处分组计数）、`useReportView`（POST /events/view 行为上报）。
- `lib/format.ts`：formatDuration/formatShortDate/formatBytes（卡片角标与详情信息共用，纯函数）。
- **HomePage**：推荐流真数据（60 张签名缩略图卡 + 真推荐排序），卡片点击进详情；mock 卡片数据退役（MOCK_HOME_CARDS 删除）。
- **AlbumsPage**：真数据两段式筛选——分区维度=GET /sources（47 组真出处计数，null 名兜底"其他"）、类型维度=MediaType 三档；排序文案映射协议（精选=default/最新=最旧=fileDate/按名称=name）；「加载更多」游标分页。mock 的"作品/角色"维度待聚合端点（记账 HANDOVER_UI §5）。
- **AssetDetailPage**（新，/app/asset/:assetId）：图片/动图=签名原件直链大图（.asset-stage 黑底 contain）；视频=原生 `<video controls>` 直链播放（ArtPlayer 播放器 UI 升级为独立后续任务）；**不兼容编码提示**（hevc/av1 等黄条提示，不转码——"永远发原件"约定）；进入即上报 open 事件（会话去重）；信息卡（文件名/大小/出处/时长/浏览播放次数）。prototype.css 追加段 +3 条（asset-stage/codec-warn）。
- router 注册 /app/asset/:assetId； mock.ts 清理退役数据。

### 实测（用户 PC 实机库：样例测试 90 图 + 测试收藏库 7355 文件/434 视频）

- 首页 60 张卡全部签名缩略图渲染、0 坏图、真推荐排序；点卡片 → 详情大图（orig 直链 200）。
- 相册分区维度真出处计数（守望先锋 242/火焰纹章 108/英雄联盟 67/崩坏 星穹铁道 59…）；类型=视频 → 434 个视频列表。
- 视频详情页 `<video>` 直链播放（用户本人验证 OK）；open 打点入库。
- 过程修复：类型维度点击值误用 label（"视频"）而非枚举（"video"）致筛选空——label/value 拆分；SW 旧缓存干扰实测（unregister+caches 清理后正常，属测试环境非产品问题）。

### 已知限制（记账）

- 存量视频 durationMs/codec 为 null（M4 已知限制：size+mtime 未变不触发重探）——卡片无时长角标、兼容性提示不生效（播放本身不受影响）。
- 搜索页/集合页仍为 mock 池（其点击不产生真实资产跳转）；搜索接 FTS5、我的页 /stats 接线待做。

---
## 库启用/停用开关 + ADR-0012 库类型可扩展体系（2026-09-03）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户需求：②库加开启/关闭选项——**关闭但不删除任何记录**（停用=浏览面隐藏，可随时恢复）；③把"库 = 内容组织方式 + 识别方式 + 展示方式"的体系理解写入文档，方便以后 AI 按 ADR 接入全新文件夹方式的库。另确认①的既有讨论：浏览器直链不兼容的编码（HEVC/AV1 等）播不了——方案 = ffprobe 编码检测（协议 0006 已铺）+ 前端按 codec 提示，**不做转码**（与"永远发原件"强偏好一致，转码档位在 M6+ 储备）。

### 数据库（migration 0007，只加不改 ADR-0011）

- `libraries.enabled INTEGER NOT NULL DEFAULT 1`；down 逆序 DROP COLUMN。TestMigrateDownThenUp 插入 0007 第一步（后续步骤顺延）。

### 协议（api/openapi.yaml → make sdk 重生成）

- `Library.enabled: boolean`（default true）+ 新端点 `PUT /api/v1/libraries/{libraryId}/enabled`（body {enabled}，204/404）。

### 服务端

- 4 个用户面查询加库开关谓词（browse.sql 两个列表+计数、recommend.sql 推荐输入池——搜索谓词在 browse 主查询内一并覆盖）：停用库资产从浏览/搜索/推荐消失。
- 有意不过滤的边界（migration 注释固化）：详情/签名直链（已获取 assetId 稳定）、管理面库列表（文件管理页需看到并重新启用）、磁盘文件/扫描、统计与事件流（用户拍板"不删记录"）。
- handler `PutApiV1LibrariesLibraryIdEnabled`（先查存在性 → SetLibraryEnabled → 广播 library.changed）；ListLibraries 响应带 enabled。
- 新增 `TestLibraryEnabledFilter`（停用→列表空/管理面保留 enabled=false/详情 200/启用恢复/未知库 404）+ TestMigrateDownThenUp 扩步；go test ./... 全绿。

### Web

- `use-libraries.ts` 加 `useSetLibraryEnabled`；文件管理页库表格加「启用」开关列（settings-switch 样式，toggle 即时生效 + toast 反馈）。

### 踩坑记录

- sqlc 对 SQL 注释/谓词顺序敏感：`WHERE AND EXISTS` 语法错误（谓词作首条件不带 AND）；flow mapping 内 description 含 `{libraryId}` 花括号被 YAML 解析为嵌套集合（redocly "missed comma"）——协议描述禁用花括号字面量。

---
## 维护页实际功能：文件管理（库管理）+ 回收站子页（2026-09-03）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户需求：①我的页关注列表不出现未关注作者；②维护页「文件管理」「回收站」两张入口卡从 mock 占位变成实际可用的子页面——文件管理 = 管理文件库（用户自己添加/删除库来测试的入口，2026-09-02 拍板）；③另回答了"早期局域网看视频"问题（验收页仍在，新 UI 接视频待拍板）。

### 协议（api/openapi.yaml → make sdk 三端重生成）

- 新端点 `DELETE /api/v1/libraries/{libraryId}`（删库此前协议/服务端均缺，M1 只建了 store 查询）：204/404。删除语义 = 库登记行级联清除该库全部 assets 及关联（外键 ON DELETE CASCADE，migrations/0001）；**view_events 事件流保留**（历史统计，0001 注释不变量）；**磁盘文件与回收站条目不动**（铁律 4）。

### 服务端

- httpapi/libraries.go `DeleteApiV1LibrariesLibraryId`：先查存在性（DeleteLibrary 对不存在 id 影响 0 行不报错，无法事后区分 404）→ 删行 → 广播 library.changed。已知限制：扫描进行中删除 → 扫描 goroutine 的 upsert 因外键失败自然终止（调试场景可接受，注释说明）。
- browse_test.go `TestDeleteLibrary`：204 + 列表移除 + browse 空数组（级联断言）+ 重复删 404。go test ./... 全绿。

### Web（阶段 B 首批真数据页面）

- hooks：`use-libraries.ts`（列表/注册/重扫/删库）、`use-trash.ts`（列表/恢复/单删/清空）——TanStack Query + unwrapSdkResult，铁律 7 合规。
- `pages/LibraryManagePage.tsx`（/app/maintenance/files）：库表格（名称/类型/路径/计数/扫描态 + 重新扫描/删除 confirm）+ 注册表单（名称/绝对路径/kind select，注册成功自动补发扫描——POST /libraries 语义不自动扫）+ 目录浏览卡（/dirs DirTree 递归，未选库禁用查询）。
- `pages/TrashPage.tsx`（/app/maintenance/trash）：条目表格（原路径/大小/删除时间）+ 恢复（冲突自动重命名）+ 彻底删除 + 清空，三者全部 window.confirm 二次确认（原型风格弹窗阶段 B 后续替换）。
- MaintenancePage：两卡接导航；文件管理卡去「接入点预留」badge；回收站卡计数改真实数据（useTrash）。
- MinePage：关注作者列表只显示已关注（用户拍板）。prototype.css 追加段 +2 条最小样式（entry-card--link / settings-field select）。

### 实测（临时测试库全链）

注册「临时测试库」→ 列表即时出现 → 扫描 fileCount=3 → 目录树（根 2 + 子目录A 1）→ API 删 1 资产 → 回收站页 1 条（入口卡计数同步 1）→ UI 恢复 → 空态且文件归位磁盘 → 再删 → UI 彻底删除（confirm 文案正确）→ 空态 → 删库 204/重复 404 → 磁盘文件全部保持（恢复归位/彻底删除真物理删均符合语义）→ 临时目录清理。

---
## 排行榜行点击 → 标签/作者集合子页（2026-09-03）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户反馈：数据页排行卡与完整榜单页的标签/作者行点击无反应，应与旧项目一致——点击弹出类似相册的子页面，列出该标签/作者的所有文件。本次补齐该交互闭环（阶段 A mock 语义）。

### 实现（web/）

- 新集合子页 `pages/CollectionPage.tsx` + 路由 `/app/collection/:kind/:name`（kind=tag/author）：page-head（名称 + 「标签/作者 · N 个文件 · mock 数据」）+ 相册同款 `.media-grid` 媒体卡网格 + 空态；侧栏返回按钮走历史栈可回退。
- 数据联动（mock 内自洽）：`pages/mock.ts` 新增 `TAG_AGG`（分区维度值 × 内容池计数，降序）、`AUTHOR_AGG`（MOCK_AUTHORS × up 出现次数，降序）、`filesByTag/filesByAuthor`（hitTag 同语义/up 子串）与 `COLLECTION_TAG/AUTHOR` kind 常量；数据页 Top5 与完整榜单页全量行的名称/数字改由聚合**派生**（原写死假数字与 mock 池脱节，点开必为空态；卡片样式零变化，阶段 B 换真聚合接口）。
- 行点击接线：DataPage `RankList` 加 `onSelect`（标签榜/作者榜/作者总览三处）；RanksPage 行 onClick（内容榜 rank-item 不动——单文件详情页属阶段 B）。

### 验证

- tsc + build 全绿；浏览器实测：数据页点「样例」→ /app/collection/tag/样例 4 卡 ✓；点「绮梦」→ /app/collection/author/绮梦 3 卡 ✓；完整标签榜（7 行真实计数）点「摄影」→ 2 卡 ✓；侧栏返回回榜单页 ✓。

---
## UI 原型移植 web 端 React 重建（阶段 A · mock）（2026-09-02）

执行 AI：GLM-5.3-Flash（主代理 + 2 执行子代理 + 1 审查子代理，ZCode）

`media-ui-prototype/` 原型敲定后按既定顺序第 1 步：九页移植进 web 正式代码，视觉/交互与原型逐像素对齐；数据仍为 mock（阶段 B 接真实 API）。

### 移植产出（web/）

- `styles/prototype.css`：原型 style.css 原样迁移（类名/对齐负 margin 零改动），main.tsx 中后于 index.css 导入使原型变量层最后生效；追加段仅 2 条（#root 高度 + `.rank-cards` 窄屏单列回退——HANDOVER_UI §5 记账债）。审查修复：`*` reset 注释说明与 preflight 等价、`button` reset 收窄 `.layout`（原全局无层级规则会压过 shadcn utilities）、`--accent` 让位（原型激活色统一走 `--qm-primary`，不再改写基建层 hover 灰）。
- `components/shell/`：AppShell（侧栏+顶栏+内容区+悬浮刷新，切页滚顶）、Sidebar（返回=历史回退/导航/主题月亮/维护/设置）、TopBar（推荐/cos/排行榜 tabs 挂 `/app/home?tab=` searchParams、搜索框+历史面板+窗口键装饰、排行榜周期行单选重置日榜）、icons（原型 SVG 逐字复刻）。
- `components/media/MediaCard.tsx`：合并原型 renderCard/mediaCardHtml（HANDOVER_UI §5 记账债第二笔）。
- `pages/` ×10：九页组件 + mock.ts（原型数据搬运，含 12 张首页卡全量、ALBUM 四维、AUTHORS 15 位、搜索标签池；阶段 B 接真数据时删减）。封面 14 张入 `web/public/covers/`，mock 引用根绝对路径 `/covers/...`（嵌套路由下相对路径会 404）。
- `router.tsx`：九页 lazy 挂 `/app` 下 AppShell；根路径 Navigate 到 /app/home。
- 主题：`lib/theme.ts` 手动选择优先（localStorage `qimeng_theme`，月亮按钮 toggle）+ 无选择跟随系统；**修 initTheme 不恢复 stored 选择的 bug**（刷新丢深色）；index.html 首帧内联脚本与 theme.ts 语义统一（读 stored、守卫系统同步、theme-color #0f0f0f/#ffffff 双写互指）。
- 常量收敛：`LOCALE_ZH`（zh-Hans-CN ×2）、`RANK_CONTENT/TAGS/AUTHORS`（rank 键单一来源）。

### 验证

- tsc（`-p tsconfig.app.json`，注意根 tsconfig 是 solution-style 直接 `tsc --noEmit` 是假通过）+ vite build + PWA 全绿；oxlint 11 warnings 0 errors（9 条 router lazy fast-refresh + 1 条 SearchPage useEffect 重置态——语义所需已注释 + 1 条基线）。
- 浏览器实测（IAB 1188×742，**禁 page-in 动画后测量=静止态等价**；IAB 文档恒 hidden 动画时钟冻结，属环境特性）：八页对齐纪律零偏差——首页网格 83.1、我的/设置卡 82.9、相册维度行 89.2、数据时段行 89.3、维护/榜单/作者 h2 顶 80.5，横向全部在 96.8 内容线上，搜索框中心与顶栏中心重合 629.2。
- 交互冒烟（DOM 事件派发，IAB 吞合成输入属环境限制）：排行榜 tab→周期行 ✓、相册维度/值/排序/空态 ✓、数据页→榜单子页/作者管理 ✓、作者搜索/排序/关注 toggle/空态 ✓、搜索回车→类型计数/标签池增删级联 ✓、我的三 tab/历史搜索/关注 ✓、主题 toggle+持久化 ✓、保存提示 ✓。
- reviewer 对抗审查：P0 首页 12 卡补回（误删 2 张广告卡——原型删的是卡内元素不是卡片）、P1 index.html/theme.ts 统一、prototype.css 全局污染收窄，全部修复复测。

### 已知差异与记账

- 顶栏 tab 激活态挂在 URL（离开首页即丢，回首页重置"推荐"）——原型 JS 态跨页保留，URL 驱动为 web 语义，已记录待用户裁决。
- 作者页搜索图标复用统一 SearchIcon（r=8 vs 原型 7，差 ≤1px）；sonner toast 主题仍跟系统（next-themes 未接，记账）。
- oxlint：router lazy fast-refresh 警告 ×9 属模式性警告，不修。

---
## M4 播放端协议与服务端基座（2026-09-02）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

播放器接入（Web 用 ArtPlayer / Android 用 Media3）的地基：断点续播 + 编码元数据。播放器 UI 接线不在本条目范围。

### 协议（api/openapi.yaml，redocly 通过 → make sdk 三端重生成）

- 新端点 `PUT /api/v1/assets/{assetId}/progress`：断点续播进度上报（ProgressUpdate.positionSeconds，秒）。语义 = **最新状态而非统计事实**：服务端只保留每资产最新位置，不写 ViewEvent 事件流、不参与 playCount（DOMAIN_RULES §5 播放计数仍走 play 事件 + 会话当日去重）；建议客户端 10s 心跳 + 暂停/离开播放页各补一次。
- AssetSummary 新增 `durationMs`（自 AssetDetail 上移，allOf 展平后 Go 生成物无变化，TS/Kotlin 列表页即取时长）与 `lastPositionSeconds`（nullable；"已看完"口径 = >= durationMs/1000 由客户端推导，服务端不算徽标）。
- AssetDetail 新增 `videoCodec`/`audioCodec`（nullable，ffprobe codec_name）——Web 端据此判断浏览器直链兼容性（null = 尝试播放、失败再兜底）。

### 服务端

- migration `0006_playback`（只加不改，ADR-0011）：assets 加 `last_position_seconds REAL` + `video_codec/audio_codec TEXT` 三列；down 逆序回滚；进度不建独立表、不加事件 kind（挂 assets 行，删除资产即连带清理，无孤儿数据）。
- ffprobe 解析扩展（thumbnail/ffmpeg.go）：ProbeResult 增加 VideoCodec/AudioCodec；ProbeVideo 由"循环内遇首个视频流即 return"改为"全流扫描后构造"——音频流可能排在视频流之后，提前 return 会漏采。
- scanner 接线：入库/重探时把 codec_name 写入新列（空串转 NULL）。**已知限制**：size+mtime 未变的存量视频不触发重探（变更检测跳过 ffprobe），codec 维持 null 直到文件变化或重扫策略扩展。
- 详情查询 GetAssetWithLibrary 补三列；AssetDetail 响应带出 lastPositionSeconds/videoCodec/audioCodec（列表接口本轮不带——消费场景是播放页先取详情；列表带出留待浏览历史端点设计时一起）。
- 新 handler `PutApiV1AssetsAssetIdProgress`（httpapi/playback.go）：UPDATE 影响行数 0 → 404（assets 行删除即回收站/物理删除后，无需先查存在性）；进度上报不 bump updated_at（播放器状态而非内容变化）。

### 测试

- playback_test.go：上报/读回 + 覆盖语义（二次上报替换不累加）+ 未知资产 404 + 不产生事件流行数断言。
- TestMigrateDownThenUp 增补 0006 回退步骤（该测试按设计随 migration 演进扩步）。
- ProbeVideo 集成测试补 codec 断言（lavfi 合成 mp4 = h264/无音轨），新增带音轨用例（h264/aac，验证音频流后置时不漏采）。

---
## UI 原型：榜单改造 + 作者管理页 + 对齐实测动画污染修正（2026-09-02）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

### 数据页排行榜

- 数字徽章全删（15 个含前三名彩色），榜单改纯行式；内容榜独占全宽一行（条目为首页同款封面卡：16:9 封面 + 底部渐变 + 右下数值徽标 + 两行标题，数据页 5 张/子页 15 张网格）。
- 标签榜/作者榜/作者总览三卡等宽并排（3 列轨道，rank-grid 复用）。
- 作者总览卡（终态）：行式列表与左右两卡同构（作者名+作品数 Top 5）+「管理」入口进作者管理页 + 统计行；中间经历过的字徽胶囊流样式已按用户要求删除（author-chips 规则清零）。

### 作者管理页（新子页 #page-authors）

- 「作者总览·管理」进入（不再跳我的页关注作者），显示全部 15 位作者（mock：12 常规 + 3 COS）。
- 体系胶囊（全部/常规/COS，DOMAIN_RULES §6 双体系）+ 旧版排序三项（默认/经常浏览/文件数量，搜索页 sort-pill 同款）+ 名字搜索实时过滤；行 = 作者名 + 关注按钮（内存态 toggle；体系小标与作品数按用户要求删除，体系仅作胶囊筛选用）；列表行字徽头像按用户要求删除。
- **标题行视觉对齐基准修正（用户拍板）**：大标题（page-head h2）的对齐基准由「nav-item 整体中心」改为「侧栏首页图标块顶部」——26px 的 h2 与 26px 图标块顶对齐即视觉对齐（数字上中心对齐但视觉上用户仍判不对齐的根因）。三页 page-head -4.5→-13.8px，实测 h2 顶 80.5 vs 图标顶 80.8（差 0.3px）。

### 数据页作者总览卡

- 卡内实现与标签榜/作者榜统一（rank-card 行式列表：作者名+作品数，Top 5），字徽胶囊流样式删除（author-chips 规则清零）；「管理」入口与统计行保留，三卡等高（grid 拉伸，无固定高度）。

### 对齐实测动画污染修正（HANDOVER_UI §4.5 新增第 5 条）

- 根因：`.page:not([hidden])` 的 page-in 动画（0.18s translateY 4px）污染切页瞬间的实测——此前入档的负 margin 全是动画中间帧假值，静止态普遍偏差 4~5px（用户肉眼发现后揪出）。
- 静止态重校 8 处（终值）：三页标题行 -13.8（视觉基准=图标块顶，见下）、数据 seg-row -5.8、相册 filter-card -5.9、搜索 stype-row -14.9、我的/设置卡 -11.6、**首页网格 -11.5**（卡片顶沿 83.1 与我的/设置卡 82.9 同线）。修后全量复测偏差 ≤0.6px。
- **大标题视觉对齐基准修正（用户拍板）**：page-head h2 的基准由「nav-item 整体中心」改为「侧栏首页图标块顶部」——同高块顶对齐才视觉齐平（数字中心对齐用户仍判不对齐的根因）。
- 流程修正入档 §4.5 第 5 条：此后对齐实测必须等动画结束（300ms）再取 rect。

### reviewer 审查修复（对抗性审查，总评可交付）

- `--elev: var(--elev)` 自引用（深色表层面 token 失效致 .card--cover/.search input 深色背景透明）→ 落实际值 #26262a，.dark .pop-chip 字面量同步收编为 var(--elev)。
- 删 `.a-list b` 死规则（作品数元素已按要求移除）与 `.rank-grid--2` 冗余声明（与 .rank-grid 定义重复）。
- 留档建议（移植 web 前处理）：renderCard/mediaCardHtml 输出结构相同应抽共享；.rank-cards 未纳入窄屏单列回退。已记 HANDOVER_UI §5 第 7 条。

---

## UI 原型对齐修正（2026-09-02）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

### media-ui-prototype（用户逐项反馈修正）

- **顶栏激活横线对齐参考截图**：修复 `.tabs` 类名撞名——「我的」页 `.tabs { border-bottom + padding-bottom:10px }` 污染顶栏 tab 行，致 tab 行下方多出全宽浅色底线、按钮上移 5.8px；我的页规则作用域限定为 `#page-mine .tabs`。激活短横线（`.tab.active::after`）由贴字位置（bottom:8px）下移至文字下方约 0.9 倍字高处（bottom:-6px，实测间隔≈16 CSS px），与桌面客户端截图逐像素测量一致。
- **导航行与侧边栏逐项纵向对齐**（用户拍板，参照客户端热门页结构）：热门态 tab 行保持可见，「综合热门/排行榜」为顶栏下方独立行、行中心对齐侧栏「首页」项中心（subnav padding-top 2px + 侧栏 nav margin-top 14px）；「日/月/周/年」为二级行下方独立行、行中心对齐侧栏「相册」项中心（margin-top 23px）；返回箭头下移 6px 与顶栏 tab 行中心对齐。实测三组中心偏差 ≤0.5px。切页/切 tab 时日月周年行同步收起（防残留）。
- **排行榜导航精简为单行**（用户拍板）：顶栏第三 tab「热门」更名「排行榜」；「综合热门/排行榜」二级行整体删除（subnav/subtab 样式与逻辑清零）；「日/月/周/年」改为「日榜/月榜/周榜/年榜」并上移占据原二级行位置（行中心仍对齐侧栏「首页」项，偏差 0.4px）；切走再切回自动重置日榜。
- **右下角悬浮刷新**：视口右下固定悬浮按钮（白底圆角卡 52px + 深色刷新图标 + 投影，全页面常显、不随内容滚动）；静态原型点击仅图标旋转一圈反馈，未接真实刷新。
- **搜索结果页**（用户拍板）：顶栏搜索框回车或点历史词进入；类型 tabs（综合/视频/图片/音频 + 实时计数徽标，「综合」无徽标参照截图）；排序行只保留「综合排序/最多点击」（删最新发布/最多弹幕/最多收藏）；「更多筛选 ▼」面板按用户反馈精简为六行：顺位/播放次数（观看次数删除）/文件大小/时间范围（点「按年份区间」展开起止年下拉）/标签模式（模糊·精确单列一行）/标签（多选）；分区/作品/角色/作者四行删除（维度筛选保留在相册页）。标签池支持增删（DOMAIN_RULES §7 口径）：「+ 添加」→ 内联输入回车/失焦入池（去重、过滤非法字符、按名称升序展示），标签胶囊 hover 显 × 删除并级联清理筛选选中态；原型为内存态刷新即还原。类型与标签为真实过滤（mock 13 文件，新增 1 条音频），「最多点击」按播放数真排序；区间类（播放/大小/时间/年份）无 mock 数据仅样式切换。搜索框有词显示清除按钮；每次新搜索重置上次筛选。
- **全页面首行贴侧栏节奏**（用户拍板，AskUserQuestion 确认方案）：各页第一行按搜索页同款纪律对齐——相册维度行/数据时段行/维护标题行中心 ↔ 侧栏「首页」项中心（margin-top -10/-11/-9px，实测偏差 0.3/0.8/0.1px），我的资料卡/设置表单卡等高卡片顶沿贴顶栏下缘（-16px，实测齐平）；首页卡片不受影响。锚点定值已写入 HANDOVER_UI.md §4.5 纪律第 3 条。
- **我的页/相册页整改**（用户逐项反馈）：搜索历史「清空」修复（.pop-block 的 display 未补 [hidden] 规则致失效，现整块隐藏）；相册筛选区去卡片盒（边框/底色/内边距清零，行缘与网格 24px 线同线）；我的页删除头像（资料卡+关注列表）并简化关注卡为「作者名 + N 个作品」；关注按钮接通双向切换（已关注灰描边 ↔ 关注主色实底）；浏览历史卡弃缩略占位改真实封面（与首页同款图+徽标浮层）；历史搜索框接通真实过滤（标题/作者子串、不分大小写、空组连标题隐藏）。
- **跨页宽度跳变根治**：.content 加 scrollbar-gutter: stable——有/无滚动条的页面内容等宽（此前我的-关注页比滚动页宽 6px，切页整体跳宽）。全页扫测：五页所有容器 left 96.8 / right 1247.3，spread=0。
- **修复清除按钮未搜索时常驻**（用户反馈）：`.search-clear` 的 display:flex 覆盖 hidden 的 UA 默认 none（与 .page/.tabs 同类坑，第三次踩中），补 `.search-clear[hidden]{display:none}`；HANDOVER_UI.md 踩坑记录泛化为通用规则——带 hidden 用法的类必须补 `[hidden]` 显式规则，新增组件自查。
- **侧栏返回按钮接通历史栈**：此前返回按钮无事件（纯装饰）。现 showPage 记录页面历史（同页不重复压栈），返回按钮回退到上一页——搜索后返回进搜索前所在页（首页/相册等均正确）、tab 显隐与周期行状态随之恢复，无历史时不动（原型内存栈，刷新还原）。
- **搜索页对齐修正 + 对齐纪律入档**（用户拍板"做好对齐、写入 UI 开发规则、不再反复提醒"）：搜索筛选面板 margin 24px→0，与类型行/工具栏/结果网格左右边缘同线（实测五块容器 left spread=0、right spread=0）；类型行/排序行套用侧栏纵向对齐纪律——类型行按钮中心 ↔ 侧栏「首页」项中心（margin-top -20px）、排序行胶囊中心 ↔ 侧栏「相册」项中心（padding-top 2px），实测中心偏差 0.1/0.4px；HANDOVER_UI.md 新增「§4.5 对齐纪律」硬规则——每次 UI 改动交付前必须浏览器实测横向同线/列内对齐/纵向对齐并附数字，目测不交付。
- 文档同步：HANDOVER_UI.md（最后更新行、§4 导航描述、§4.5 对齐纪律、原型踩坑与对齐规则记录）。

---
## UI 原型收尾 + v1 旧壳删除（2026-09-01）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode）

### media-ui-prototype 桌面客户端风格原型（用户拍板的主路线，全部 mock）

- 侧栏（64px 窄栏：返回箭头 + 首页/相册/我的/数据 + 底部 明暗主题/维护/设置）；顶栏「推荐/cos/热门」只在首页显示，搜索框顶栏框内居中 + 搜索下拉面板（搜索历史胶囊可展开 + 推荐搜索占位）；热门二级导航（综合热门/排行榜 → 日/月/周/年，固定贴顶栏不随内容滚动）。
- 六页搬入（自 panel-demo，mock 数据）：相册（两段式胶囊筛选：维度切换/值行计数/超两行收起展开/即时过滤/空态）、我的（头部资料卡 + 关注作者/收藏作品/浏览历史历史流样式）、数据（时间筛选/指标/趋势/双环/排行三卡）、维护（性能圆环+网络曲线+工具卡+日志表）、设置（表单卡+保存提示）。
- 明暗主题切换（月亮按钮，`.dark` 全站深色变量）；媒体卡统一（封面+时长角标+标题+作者·日期，相册/收藏/首页同款）。
- 布局修正：整体放大 10%（`html{zoom:1.1}`）与 `.layout` 高度 100vh→100%（100vh 被 zoom 放大导致侧栏底部溢出）；`.page/.m-pane/.tabs` 的 `[hidden]` 显式 display:none（display:flex 覆盖 hidden 的堆放 bug）。
- 硬编码治理：重复颜色提为设计 token（--accent-2/--accent-3/--faint/--hover-bg/--icon-idle/--icon-faint/--ok）；删除死变量（--up-orange、未用的 --qm-* 项）与死代码（.hint 等）。
- 措辞中性化（用户要求）：原型内移除全部平台命名痕迹（标题/注释/数据字段/任务书），含目录改名 media-ui-prototype。

### v1/v2 旧 UI 删除（用户拍板：参考搬入界面的上一版 UI 全部删除，只保留当前原型）

- 删除 web 端 v1 旧壳（AppShell 五 Tab 用户端）：`web/src/pages/`（8 页 + _shared）、`components/{admin,asset,charts,organize,upload,viewer,misc}/`、`components/layout/AppShell.tsx`、`components/ui/` 中未被保留代码引用的文件、旧 hooks（use-assets/use-asset-detail/use-dirs/use-engagement/use-libraries/use-recommendations/use-sources/use-system-status/use-tags/use-timeline-tags/use-trash/use-upload/shared）。
- **v2（panel-demo）也删除**：`panel-demo/`（五页面板+mock）、`components/tremor/*`、`lib/tremor.ts`、`lib/format.ts`（无引用）、`tailwind-variants`/`recharts`/`@remixicon/react`/`motion`/`@radix-ui/react-*` 单包依赖（npm uninstall）。
- 保留：`components/auth/`、`components/layout/{AuthGate,RootLayout,SseBridge}`、`components/ui/{button,input,sonner}.tsx`、`hooks/{use-session,use-sse-events}`、`lib/{api-client,constants,sse,utils}`、`api/*`、`@immich/ui`（index.css 主题来源，保留）。
- `router.tsx` 重写为占位页 + `/app`（AuthGate 门禁占位）；**`npm --prefix web run build` 通过**（PWA 预缓存 9 项）。


| 文档 | 已更新 |
|---|---|
| docs/HANDOVER_UI.md | 全文重写（原型现状/六页/中性化/v1 删除/待办/工作树） |
| docs/HANDOVER.md | UI 路线现状与待办表述更新（v1 已删，原文参考措辞中性化） |
## Immich 风格主题 token + 开发免密通道（2026-08-31）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode）

### Immich 风格主题移植（用户选型：只换主题 token，不动布局）

按用户要求以 Immich 为 UI 视觉基准（能力地图同布局类项目），从 `@immich/ui@0.86.0`（**MIT 许可**）提取完整色板（oklch 双套 light/dark，primary/success/danger/warning/info 全套 50-950 色阶）落地到 shadcn 变量层：

| 位置 | 改动 |
|---|---|
| `web/src/index.css` | 主色 #4250af（浅）/浅蓝 oklch(0.836 0.074 258.58)（深）；深色背景 #0a0a0a、卡面 #212121 档位；图表色换 Immich 语义色系；圆角 0.625→0.75rem（媒体卡片 12px 口径）；focus ring 跟随主色 |
| `web/src/tokens.css` | 圆角口径注释更新（`--qm-*` 映射层自动联动，组件零改动，符合 ADR-0008） |

字体保留 Geist（Immich 的 Google Sans 许可不开放）。设计规格存档 `dev-tools/immich-ref/DESIGN_SPEC.md`（仓库外参考目录）。

### 开发免密通道（用户约定 1：项目未完成前不要密码流程）

| 位置 | 改动 |
|---|---|
| `api/openapi.yaml` | 新增 POST /api/v1/auth/dev-login（security: []；404=未开启，生产语义上"端点不存在"） |
| `server/internal/config` | `auth_dev_mode`（yaml + env QIMENG_AUTH_DEV_MODE，默认 false） |
| `server/internal/httpapi/authapi.go` | dev-login：未开启恒 404；开启则免密签发 token（未初始化自动建 admin 占位；与 /auth/login 同语义，签发即重铸旧 token 失效） |
| `server/internal/httpapi/server.go` | topRouter 免鉴权清单双同步（dev-login 加入，注释标注协议联动责任） |
| `server/internal/httpapi/auth_dev_test.go` | 3 用例：关闭 404 / 开启自动初始化+签发可用 / 二次调用重铸且旧 token 失效 |
| `web/src/hooks/use-session.ts` | `useDevLogin`（retry: 0；404 是业务预期） |
| `web/src/components/auth/LoginGate.tsx` | 挂载时先试 dev-login，命中直进 UI；404/失败静默回退正常表单（生产零行为变化） |

### 测试库与约定记录

- 测试库：样例相册 `<本地相册目录>\相册\2017 12 9～10  样例`（90 张 JPG，kind=normal）注册进本地开发库（qimeng-data）——用户约定 3。
- 三条用户约定（免密调试/不单拉前端走 8420/测试库）已记录 docs/HANDOVER.md「用户约定」节；SECURITY.md 新增「开发模式」节（红线 5 单点例外边界与禁止组合）；GUIDE_API.md 认证行补 dev-login。

验证：make sdk-validate/sdk-go/sdk-ts 通过；`go test ./...` 全绿（新增 dev-login 3 用例）；web `tsc+build+lint` 全 0 错误；dev 模式实跑免密直达 UI。

---
## Web 适配补丁：/dirs libraryId 必填 + 详情库 ID 接线（2026-08-31，紧随 M3 收尾）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode）

上一条提交把 `/dirs` 的 libraryId 标为必填后，CI 的 web job（先从 openapi.yaml 重建 TS 再 tsc）暴露前端调用点类型不兼容——本地曾因 TS 生成物未随 `make sdk` 重建而假绿（补丁起：本机 tsc 验证改用真实退出码且先重建生成物）。适配：

| 位置 | 改动 |
|---|---|
| `web/src/hooks/use-dirs.ts` | useDirTree 未传 libraryId 时查询禁用（协议必填参数，enabled 保护 + 防御性错误）；useDirCreate 入参 `libraryId` 改必填 |
| `web/src/pages/DetailPage.tsx` | MoveDialog 的 libraryId 改用 `AssetDetail.libraryId`（M3 收尾协议已补字段，替换"取第一库"workaround），库列表加载瞬态仍以第一库兜底 |
| `web/src/components/organize/MoveDialog.tsx` | 文件头接口文档更新（协议缺口的描述移除） |

验证：`tsc --noEmit`/`npm run build`/`npm run lint` 真实退出码全 0；CI web job 复跑可复现通过。

文档：`CHANGELOG.md` 本条。

---
## M3 后端收尾：遗留三项清零 + 协议债修复（2026-08-31）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode；研究子代理摸底 + 主代理实施）

把 M3 完成后挂账的收尾尾巴一次清完，顺带清掉用户指认的测试残留路径：

| 改动 | 位置 | 要点 |
|---|---|---|
| 协议三处 | `api/openapi.yaml` | `/dirs` GET 参数与 POST body 的 `libraryId` 标 required（此前实现强制、协议未标——M2 UI 段记账的协议债）；`AssetDetail` 补 `libraryId`（多库场景客户端定位整理目标）；新增 `GET/PUT /sources/custom`（`CustomSources{names}` schema，整体替换）——make sdk 三端重建 |
| custom_sources 写入端点 | `httpapi/sources.go` + `scanner/enrich.go` | PUT=整体替换：服务端规范化（trim+去空+去重+升序）→ 持久化 kv_settings（`authoring.SettingKeyCustomSources`）→ 运行中 matcher 同步刷新（Scanner.UpdateCustomSources）→ 后台逐库 `RecomputeEnrichment` 存量重算（4 路并发，仅 normal 库；完成发 library.changed）。**存量传导关键**：资产 size+mtime 未变时全量扫描只跳过，不显式重算已入库 source 列永不更新。GET 无记录回空数组（匹配引擎空集即等价） |
| filing cos 库作者映射修正 | `scanner/enrich.go` `EnrichAsset`/`recomputeCosAuthor` + `scanner.go` `applyMoveMerge` | EnrichAsset 去掉 cos 早退：cos 分支按当前 rel 首段目录重算（先删旧关联再挂新作者；库根直放只清不挂）；扫描移动合并（作者目录整体改名场景）在改路径后同样重算。normal 库行为不变（文件名不变不重算是正确语义） |
| 孤立 COS 作者清理 | `scanner/enrich.go` `cleanupOrphanCosAuthors` + 新查询 | 全量扫描收尾与 fsnotify 增量删除后自动清理零关联 cos_ 作者（旧项目 deleteOrphanCosAuthors 语义）；全库范围（隔离口径下 normal 资产不会关联 cos_ 作者）。清空后出现在 /authors 列表的残留作者不再需要人工处理 |
| 新查询 | `store/queries/authors.sql`（sqlc 重建） | `DeleteAssetAuthorsByAssetID`（先删后插覆盖）、`DeleteOrphanCosAuthors`（NOT EXISTS 清理）、`ListAssetsForEnrichmentByLibrary`（存量重算输入） |
| 测试 | `scanner/enrich_test.go` +4、`httpapi/sources_test.go` +1、`browse_test.go` 断言 +1 | cos 单资产移动重算/作者目录改名移动合并重算+旧作者清理/删除目录后孤立清理/自定义出处存量重算；custom 端点闭环（空→PUT 规范化→回读+持久化形态一致→清空）；详情携带 libraryId |
| 环境清理 | `server/data/` 已删除 | 用户指认的"测试用路径"：config 默认 `./data` 的裸启动残留（空库：0 库/0 资产/0 用户 + media-secret），与正式数据目录 `qimeng-data/`（测试媒体库 7347 文件）无关；另清 HANDOVER 中对已不存在临时文件的路径引用 |
| 卫生顺手 | `httpapi/authapi.go` | 上上条 commit 带入的注释 `///` 笔误与 CRLF 行尾修正（gofmt 合规；CI golangci 不查 gofmt 所以漂移至今） |

验证：`go test ./... -count=1` 15 包全绿（新增 ~7 用例）、golangci-lint 0 issues、`gofmt -l` 空、`go vet` 干净、redocly 0 error（make sdk 走完验证链）、web `tsc --noEmit` 通过。

文档：`GUIDE_API.md`（自定义出处机制 + 端点表）、`DOMAIN_RULES.md`（§4 自定义出处/§6 COS 作者清理与目录变更/§9 cos 移动映射 + 最后更新）、`HANDOVER.md`（进度/待办/环境清理）、`CAPABILITY_MAP.md`（M2/M3 三态同步）、`PROJECT_PLAN.md`（M3 收尾勾选）、`CHANGELOG.md` 本条。

---
## 线上实机部署与登录链路修复（2026-08-30）

执行 AI：GLM-5.3-Flash（主代理）

线上实机暴露的死锁缺陷：token 仅 setup 响应明文一次，换浏览器/清缓存/新设备后无任何找回通道（前端 verify 模式要求粘贴当年 token，实际不可用；实机表现为用户浏览器永远无法进入）。修复：

- 协议：新增 `POST /auth/login`（security: []，请求复用 AuthSetupRequest，响应 AuthToken；401=密码错误）——make sdk 三端重建
- 后端：argon2id 比对 + 单用户单 token 重铸（登录成功新 token 覆盖旧哈希，旧 token 失效）；topRouter 免鉴权清单同步（与协议双写的第二处）
- 前端：LoginGate「已初始化」分支从"粘贴 token"改为"密码登录"（useAuthLogin hook），登录成功 token 落 localStorage 后无需再输
- 测试：3 用例（正确密码换新 token 且旧 token 失效/错误密码 401/连续登录各自有效）；go test 全绿、lint 0、web tsc + build 通过

- 第二层修复（commit 3d0d908）：`unwrapSdkResult` 把 hey-api 的 HTTP 状态码并进错误对象——错误体只有 {code,message}，LoginGate 判断 409 永远失败，把"已初始化，请登录"误判成"初始化失败，请重试"（M2 冒烟只测过全新库 setup 成功路径，409 分支从未走到）。此修解释了用户实机看到的全部报错。

**实机数据**：注册「测试收藏库」库（<本地相册目录>\1\HHH）扫描 7347 文件（图 6917/视频 430/20.7GB）；导入 `4 作者` 两个 TXT（122 位作者、824 关联）；出处自动匹配 47 组（守望先锋 232 等）。用户浏览器实测登录进主界面。实机状态与明天待办见 `HANDOVER.md`「线上实机状态」节。

文档：`GUIDE_API.md`（认证行）、`HANDOVER.md`（线上实机状态节）、`CHANGELOG.md` 本条。

---
## M3 后端算法移植全量完成（2026-08-30）

执行 AI：GLM-5.3-Flash（主代理 + 执行子代理 A/B/C 三路并行/串行 + maker-checker 审查）

M3 六项中五项后端完成（推荐偏好设置页属 UI 按用户指示不动），501 stub 全部清零（notImplemented 机制退役）：

| 模块 | 产出 |
|---|---|
| stats 统计 | migration 0005（asset_daily_stats 文件×天物化表 + authors.followed + libraries.kind）；`internal/stats` 趋势分桶纯函数（周一对齐/动态分桶/总和守恒，旧项目 StatsFormatHelperTest 7 例全译+6 补充）；/stats/overview /stats/trends 接线；打点路径 open/play 会话去重（dwell 秒数累加不去重）+ 物化表同步累加 |
| sourcematcher | `internal/sourcematcher` 130 组内置检索表整表翻译（475 变体/1388 角色——**131 系旧文档把 data class 定义行误计，DOMAIN_RULES §4 已勘误**）+ 匹配引擎（长度降序前缀/角色剥离数字保护/多出处"+"分段/结果缓存 8192）；旧 SourceMatcherTest 23 例照译+7 新增（文档化无测试行为） |
| authoring 作者体系 | `internal/authoring` TXT 三格式解析 + 文件名匹配规则（精确/序号括号容错/hasExt 判定）+ authorId 生成（旧 AuthorImportUseCaseTest 13 例照译；分片存储系旧 Android CursorWindow 规避不实现）；authors.sql + GET /authors、POST /authors/import-txt（统一重建：跨 TXT 并集）、PUT /authors/{id}/follow 三端点接线 |
| scanner 富化 | ingestFile 按 libraries.kind 分派：normal 走 SourceMatcher（source+asset_characters 落库）、cos 按目录结构建 cos_ 作者；移动/重命名（filing 写入路径）显式重算 EnrichAsset（文件名变而 size+mtime 不变不会触发重 ingest）；自定义出处从 kv_settings 装载 |
| 迁移端点 | 协议补 LegacyBackupImport 17 段 schema + LegacyImportResult（此前 /import/qimeng-backup 零建模，违反协议先行已修正）+ make sdk 三端重建；实现：文件名匹配（folderName 消歧）/作者 cos_ 前缀保留/标签/时间轴/收藏/关注 upsert + 事件回放（dailyBrowse 全量 + mediaStats 差额 + history 补漏，总量守恒——测试锁定 open=明细+差额+补漏）+ 同 exportedAtMillis 批次幂等锚点 + scanSources/settings/albumRules 不导入进 warnings |
| 协议增量 | Library.kind（normal/cos，注册 COS 作者库）+ LibraryCreate.kind + 迁移请求/响应 schema；openapi 3.1 nullable 写法修正（type: [x, "null"]） |

测试：`go test ./...` 13 包全绿（新增 ~60 用例）；golangci-lint 0 issues；redocly 0 error。文档：PROJECT_PLAN（M3 勾选）、HANDOVER、GUIDE_API（库 kind/统计口径/迁移语义/出处富化）、ARCHITECTURE（§2 图+§5 边界表加 sourcematcher/authoring）、DOMAIN_RULES（§4 勘误 130）。

遗留（记 HANDOVER）：COS 孤立作者清理（作者目录改名/删除后残留，旧项目有对应能力）、custom_sources 写入端点（协议未定义，UI 路线一起做）、filing API 改名的 cos 库作者映射修正（normal 库已覆盖）。

---
## UI 方向定稿：Tremor 风格面板成为正式 UI 基底（2026-08-29/30）

执行 AI：GLM-5.3-Flash（主代理 + 执行子代理多轮迭代，浏览器原型逐版验收）

M2 UI 提交后 UI 方向经用户多轮拍板演进，本条存档方向变化与原型产出：

| 轮次 | 决策/产出 |
|---|---|
| 桌面媒体中心改版（已弃用） | AppShell 双布局（桌面侧边栏/移动底栏）+ 绮梦紫品牌主题（index.css）+ 卡片质感/响应式网格——用户后改为以 Tremor 风格面板为基底，此轮品牌色与质感改法保留在代码中复用 |
| 管理面板原型（panel-demo） | 选型 Tremor Raw（copy-paste 组件，Tailwind v4 + React 19 兼容）+ Recharts；14 组件 + lib/tremor 工具；经 V1→V3.3 五轮浏览器验收迭代 |
| **正式 UI 结构定稿**（用户拍板） | 单人使用，管理面板与用户端**合并为一个界面**。侧栏五项：①首页（桌面 PC 式卡片流：单击右侧详情面板/双击播放占位）②相册（旧版全部页逻辑：**两段式胶囊**——维度行「分区/作品/角色/类型」+ 值行带级联计数、展开收起，维度按条目类型区分）③我的（头部信息卡 + Tabs：数据统计/浏览历史）④维护（性能监控圆环+网络负载 + 文件管理/回收站/日志合并）⑤设置（固定最下） |
| 技术说明 | 官方 PC 客户端闭源；布局参考 PiliPala/tiajinsha 等开源实现的交互语义，组件以自有 Tremor/Tailwind 体系重做；保持 Web 形态（PWA），Electron 客户端方案评估后弃（维护成本高、NAS 自用无必要） |

原型现状：全部 mock 数据；`/panel-demo` 路由独立于现有用户端壳（用户端 AppShell 一并存留，最终以 panel-demo 结构为正式界面逐步替换）。待办：接真实数据（首页/相册接资产列表+签名直链缩略图、维护页接硬件监控 API、数据页依赖 M3 统计）、品牌色统一、提交后逐步替换用户端。

本 commit 同时收录工作树中的并行 M3 后端改动（recommend 十维推荐端点实装、rankings/prefs 端点、settings/daily_shown 存储层、stubs 替换、golangci G404 豁免——go test 全绿），为保进度按用户指示合并存档。

文档：`HANDOVER.md`（UI 方向定稿节）、`CHANGELOG.md` 本条、`.gitignore`（server/data 运行时目录）。

---
## M2 UI 段页面组装与集成验收（2026-08-29）

执行 AI：GLM-5.3-Flash（主代理组装 DetailPage + 修复与集成；Organize/Trash/Stats 三页由执行子代理完成；reviewer 子代理对抗审查通过）

接手前会话因额度暂停的 M2 UI 中间态（executor-C/D 停工：31 个 TS 构建报错 + Detail/Organize/Stats/Trash 四页仍为 6 行占位），本次完成页面组装、集成检查与全链路冒烟：

| 改动 | 位置 | 要点 |
|---|---|---|
| 31 个构建报错清零 | `Dashboard.tsx`/`RecommendPage.tsx`/`labels.ts`/`use-asset-move.ts`/`LibraryManager.tsx`/`AllAssetsPage.tsx` | Dashboard 解构修复（`const { data } = useSystemStatus()`）；RecommendPage 提取 `hotItems/favoriteItems`（`data?.items ?? []`）做 undefined 保护；`LibraryScanState` 改从 `Library['scanState']` 派生（generated 无独立导出类型，协议扩展自动跟随）；3 处 unused 删除 |
| 详情页组装（主代理） | `pages/DetailPage.tsx`（全新约 300 行） | 媒体区双分支（image/animated→ImageViewer；video→thumbUrl 预览封面/VideoPlayer 两态）+ chrome 轻操作层（返回/信息 + 点赞/收藏/标签/移动/删除）+ 批次导航（location.state `DetailBatchState`，navigate replace 防堆栈增长）+ open/dwell 打点（离开 ≥1s 才报）+ TagManager/InfoSheet/MoveDialog/删除确认四弹层；外层 `key={assetId}` 重挂载复位模式（免复位 effect，React Compiler purity 合规）；useLike 无本地基线时以详情 likeCount 显示（likeTouched 区分） |
| 整理/回收站/统计三页组装（执行子代理） | `pages/OrganizePage.tsx`/`TrashPage.tsx`/`StatsPage.tsx` | Organize：库选择 + DirTree + useDirCreate 新建目录（客户端拦路径分隔符）+ DropZone→useUploadQueue→UploadQueue（失败 toast ref 去重）；Trash：useTrash 四 hook + 恢复/单删/清空全部二次确认；Stats：getApiV1StatsOverview（后端 501 stub 期间 retry:false）+ "统计尚未上线"空态 + 库概览卡（扫描状态徽标） |
| MoveDialog 接线 bug 修复 | `components/organize/MoveDialog.tsx` + `DetailPage.tsx` | 冒烟发现：服务端 `/api/v1/dirs` libraryId 业务必填（dirs.go 有测试锁定），而 MoveDialog 无参调用 useDirTree 必 400。修复：MoveDialog 增 `libraryId?` prop 透传；DetailPage 传第一库 id（AssetDetail 协议无 libraryId 字段，多库场景记为协议债） |
| 集成检查与验收 | — | Toaster 挂载/SSE 单连接多实例/error-text 口径/相册跳转 source 参数五项确认；隔离实例（临时数据目录 + ffmpeg 造 2 图 1 视频）browser 冒烟全链路通过：设密→注册库→扫描→列表分组→视频播放（TimelineBar/倍速/默认静音）→点赞收藏标签→新建目录→移动→删除→回收站恢复→统计空态→仪表盘真数据→相册兜底分组；上传 UI 受 IAB 无 file chooser 限制，XHR 通道页面内直调验证 201 |

验证：`npm run build` 0 错误（tsc -b + vite + PWA sw.js 产物）、`npm run lint` 0 错误 10 警告（逐一核对全部为基建层遗留、本次新写文件 0 警告）、`go test ./...` 全绿（确认 M3 并行改动未破坏）、reviewer 子代理全新上下文对抗审查通过（独立重跑三项验证）。

文档：`PROJECT_PLAN.md`（M2 UI 九项勾选）、`HANDOVER.md`（完成记录 + 提交待办）、`CHANGELOG.md` 本条。**git 未提交**——工作树混有并行 M3 后端改动，提交拆分策略待用户确认。

---
## M2 UI 段随附后端四件套（2026-08-29）

执行 AI：DeepSeek-V4-Flash（执行子代理，ZCode）

M2 Web UI 段的随附后端改动——缩略图抽帧策略对齐、标签排序、出处列表端点、服务端 SPA 托管，全部走"协议先行 → 生成 → 接线 → 测试 → 文档"：

| 改动 | 位置 | 要点 |
|---|---|---|
| 缩略图抽帧策略对齐 §11 | `server/internal/thumbnail/`（blackframe.go/ffmpeg.go/generate.go/cachekey.go + 测试） | **内嵌封面优先**（ffprobe `disposition.attached_pic` 探测，`-map 0:<流索引>` 抽封面流）→ 按时长 **35% 代表帧** → **黑白扩散序列**（35%→25%→45%→15%→55%→5%→65%→0ms）：新增白帧判定（全部 >240）、取不到时长短路 0ms、候选点毫秒级去重（"不重复取同一帧"）；旧"0s→1s→2s→3s→5s"逐点序列废弃。**缓存键升版本段**：SHA-256(`"v2:"+assetId+":"+size`)，旧缓存全部自然失效重建（孤儿交给对账清理，符合"永不因数量上限删除"）；golden 向量与注释同步。性能分工（实时用首帧/代表帧仅预生成）**M3 预留**，代码注释标注 |
| 标签排序 | `tags.sql`/`browse.sql` + migration `0004_asset_tag_created_at` + `tags.go` + 测试 | GET /tags 改**名称升序**（筛选面板口径，LEGACY_REQUIREMENTS §A）；详情 tags 改**关联时间倒序**（最新添加置顶）——asset_tags 无关联时间列，0004 补 created_at（只加不改，epoch 回填 + NOT NULL DEFAULT，down 可回滚）；替换式 PUT 写入当前时刻（替换即刷新、重添加天然置顶），同批毫秒内按标签创建时间倒序 tie-break |
| 出处列表 `GET /api/v1/sources` | openapi + `store/queries/sources.sql` + `httpapi/sources.go` + `sources_test.go` | 按出处规范名分组计数（name=null=无出处文件，显示层兜底"其他"），fileCount 降序；`includeCos` 默认 false，复用 browse.sql 的 COS 排除形态（口径与资产列表一致）；三端 SDK 经 `make sdk` 全量重新生成 |
| 服务端 SPA 托管 + /_debug/ | `config`（WebConfig `web.static_dir`/`QIMENG_WEB_STATIC_DIR`，默认 `../web/dist`，空=禁用）+ `httpapi/server.go`/`spa.go` + `Makefile web-build` | 静态目录存在且 index.html 可读 → `/` 托管构建产物（`/assets/**`、`/icons/**` immutable 长缓存、HTML no-cache、非文件路径回退 index.html、`os.Root` 防路径穿越、文件系统错误回退日志留痕）；目录缺失 → 回退内嵌验收页（M1 行为不变，服务不挂）；验收页固定挂 `/_debug/`（SPA 可用时也可访问，免鉴权）；`/api/` 前缀与 `/metrics` 维持原鉴权不吞 404 |

验证：`gofmt -l` 空、`go test ./... -count=1` 全绿（thumbnail 黑白/扩散/去重/封面/缓存键版本化 + httpapi 标签排序/出处/SPA 用例 + store 迁移 0004 down/up）、`go build ./...` 通过、`make sdk` 三端生成无漂移（redocly OK）。

文档：`DOMAIN_RULES.md` §3（sources 端点一句）/§7（标签排序两行）/§11（实现状态=已对齐，性能分工 M3 预留）/「最后更新」加日期；`GUIDE_API.md` 分组表与全局约定；`HANDOVER.md` 进度；`CHANGELOG.md` 本条。`PROJECT_PLAN.md` 未动（主 AI 统一勾选）。

---
## M2 收尾：FTS5 全文搜索 + upload.done SSE 载荷 schema（2026-08-29）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode）

M2 最后一个后端任务——全文搜索落地，全链路（协议→迁移→查询→接口→测试）：

| 改动 | 位置 | 要点 |
|---|---|---|
| 迁移 0002（up/down） | `server/migrations/0002_search_fts` | trigram 分词 FTS5 虚表（为什么不是 unicode61：中文任意子串检索）；聚合 VIEW `asset_search_text`（文件名/文件夹路径/标签/作者/角色/出处六维拼词，空格分隔防跨维度假命中）；11 个同步触发器（assets 主表 3 + 标签/角色/作者关联表 8）——全部写路径（upsert/移动改名/恢复/标签增删改/作者改名）自动维护索引，业务零感知；存量回填语句 |
| 浏览三查询 q 谓词 | `store/queries/browse.sql`（Desc/Asc/Count 同步） | `json_each` 把空格分词结果转 AND 语义；每词 instr 子串匹配任一维度——**关键取舍**：trigram 的 `MATCH` 不支持 2 字短词（'尼尔' 命中 0，SQLite 3.53.3 实测），LIKE 的 `%`/`_` 转义又遇 sqlc v1.31.1 不支持 FTS 虚表列 `LIKE ... ESCAPE`（最小复现定位），定案 `instr(lower(), lower())`——contains 语义 + ASCII 大小写折叠 + 通配符纯字面（'100%完结' 可搜）；3 万行基准每词 <150ms |
| search 包 | `internal/search`（doc.go + search.go） | 职责收敛为关键词解析（ParseQuery，空格分词/多词 AND）与索引重建（RebuildIndex，Clear+Fill 运维兜底）；索引增量维护在迁移触发器，不重复开发 |
| 索引失效/重建 | `store/queries/search.sql`（RebuildAssetsFtsClear/Fill） | 幂等重建路径，测试覆盖"清空索引→RebuildIndex→恢复命中" |
| 协议 | `api/openapi.yaml` | `q` 描述更新（空格分词/多词 AND/六维子串/与筛选叠加）；SSE `upload.done` 载荷 schema 补齐（UploadDoneEvent，代码侧 events.UploadDoneEvent 结构化载荷替换原 assetId 裸字符串） |
| 测试 | search 单测（ParseQuery/RebuildIndex）+ httpapi 端到端 4 用例 | 六维命中/多词 AND/大小写/通配符字面/与筛选叠加/触发器同步（移动、作者改名、删标签级联）/重建兜底/参数共存——13 个新用例；store 回滚测试适配两步迁移 |

验证：go test ./... 全绿（9 包）、go vet 通过、golangci-lint 门禁绿、make sdk 三端生成无漂移、web tsc 通过。

文档：`DOMAIN_RULES.md` §3 新增「全文搜索口径」节（分词/AND/六维/子串/叠加/索引说明，行为唯一权威）；`GUIDE_API.md` 搜索实现状态；`HANDOVER.md` 进度与剩余任务（M2 后端全清）；`PROJECT_PLAN.md` M2 勾选；`CHANGELOG.md` 本条。

---
## 代码卫生排查与硬编码收敛（2026-08-29）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

三端代码卫生排查（Web 端 Explore 子代理 + Go 端主代理逐项 grep），修复发现的 4 类硬编码，并把排查标准沉淀为 AI 编码规范：

| 修复 | 位置 | 要点 |
|---|---|---|
| 媒体直链路径前缀常量化 | httpapi `server.go`（常量定义 + 免鉴权判定）、`assets.go`/`upload.go`/`media.go`（引用点） | `mediaPathPrefix`/`mediaPathOrig`/`mediaPathThumb` 单一来源——签名协议字符串原散落 4 处手抄，一致性原靠人肉保证 |
| 分页语义抽共享 | 新增 httpapi `pagination.go` | `defaultPageLimit`/`maxPageLimit`/`resolvePageLimit` 收敛 assets 与 recommendations 两端点重复的默认值+范围校验+错误消息（第 2 次复制粘贴触发警戒线）；与 openapi.yaml 的双写同步责任入注释 |
| 缩略图缓存头常量化 | `media.go` | `thumbCacheControl`（max-age 一年 + immutable，语义注释） |
| readyz 超时常量化 | `server.go` | `readyzTimeout`（3s，与包内既有命名常量风格对齐） |

验证：gofmt 无差异、go vet 通过、go build 通过、go test ./... 全绿。

文档：`AI_README_FIRST.md` 新增「代码卫生约束」节（魔法值零容忍/第二次即提取/协议双写同步责任/安全字符串单一来源/调度参数禁内联/复用优先级/禁隐式调度耦合），警戒线表增「魔法字面量」行；`AGENTS.md` 警戒线索引同步。Web 端排查结论：手写代码干净，生成 SDK 未接线、baseUrl 配置策略留待 SDK 接线任务。Android 端仅生成物不适用。
## M2 后端先行：六批端点接线（2026-08-27 ~ 2026-08-29）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户拍板 M2 拆为"后端先行、UI 后置"（PROJECT_PLAN M2 节记录）。六批端点从 501 占位转真实实现，全部走"协议先行 → make sdk → 接线 → 测试 → 文档"流程：

| 批次 | 内容 | 要点 |
|---|---|---|
| sysmon 接线 | /system/status + /metrics | 协议补 perCore；挂载点动态=DataDir+全部库根（sysStatusAdapter 现查库表）；version 常量单一来源；真机冒烟（20 核差分）通过 |
| 回收站五端点 | DELETE asset / trash CRUD | TrashMeta 扩 LibraryID/MediaType；恢复=UpsertAsset 重建（asset_id 不变，浏览历史保留——ADR-0005 无外键红利）；恢复冲突自动重命名；遍历两遍式 os.Root 防 TOCTOU（gosec 提示真修，未开 nolint）；恢复语义与"绑定不还原"已知限制入 DOMAIN_RULES §9 |
| 移动/重命名 | POST /assets/{id}/move | 冲突 409（移动不覆盖）；失败回滚文件移动（扫描器自愈为兜底）；targetDir 空串=库根（目录语义与资产路径语义区分） |
| 推荐流占位 | GET /recommendations | M2 热度占位（viewCount 降序），seed 忽略，M3 十维算法只换实现 |
| 目录树 | GET/POST /dirs | 遍历磁盘（空目录可作整理目标）；POST 幂等；穿越拒绝 |
| 标签体系 | /tags 三件套 + /assets/{id}/tags + timeline-tags 三件套 | store 新增 tags.sql 十查询（sqlc 注释须纯 ASCII——中文注释使解析器报错，已验证入档）；替换式绑定走事务；跨资产时间轴删除隔离 |
| 上传 | POST /assets/upload | 协议补 libraryId 必填参数（多库定位）；config upload.max_bytes（默认 2GB）；流式接收（Content-Length 预判 + MaxBytesReader 兜底）；冲突自动重命名；media_type 用 scanner.ClassifyMedia 唯一口径；视频探测同 scanner"失败留空"语义；upload.done 最小载荷发布（SSE 载荷 schema 待补协议） |

验证：全部测试绿（本轮新增 30+ 用例）、golangci-lint 0 issues、make sdk 三端重建通过、修复 ineffassign 揭示的 err 遮蔽真 bug（upload.go Copy 错误曾会被吞）。

文档同步：PROJECT_PLAN（勾选+执行顺序调整）、HANDOVER（剩余任务表收敛为 FTS5 一项）、DOMAIN_RULES §9（上传/恢复语义）、GUIDE_API（参数行）、OBSERVABILITY（接线状态）。

已知遗留：本机 redocly 有 Windows libuv 退出崩溃 bug（校验本身通过，CI Linux 不受影响）；migrations/0001 的 trash_items 表未使用（回收站真相源是 meta 文件，filing 包设计），下轮评估是否在 0002+ 清理或启用。

---
## 底层重构与文档体系升级（2026-08-26，c7d4839）

执行 AI：DeepSeek-V4（主代理 + 执行子代理）

三片底层重构（代码侧已落地，本条目连同文档一并归档）：

| 项 | 要点 |
|---|---|
| 探针鉴权定案 | `/readyz` 免鉴权定案：openapi.yaml:380 标 `security: []`；topRouter 注释改为"以 yaml security 元数据为准" |
| Makefile 落地 | server-run / server-test / web-dev / web-test 真实可用；`make lint` = redocly + golangci-lint + TS；docker-build 标 TODO(M5) 如实占位 |
| golangci-lint 门禁 | 新增 `server/.golangci.yml`（v2.13.1，11 linter）；depguard 两条红线：非 httpapi 包禁 import httpapi/gen；业务包与 sysmon 禁 import httpapi（cmd 组合根例外） |
| CI 对齐 | server job 插入 golangci-lint 步骤（golangci-lint-action@v9）；生成物不入库 + CI 重建链保持 |
| 缩略图档位单一来源 | `server/internal/thumbnail/cachekey.go` 常量（SizeSmall/SizeGrid/SizePreview）；config.Thumbnail.LongSide 默认 0=回落 SizeGrid；Config.Token 与 QIMENG_TOKEN 已删 |
| stubs 注释对齐 | httpapi/stubs.go 各端点里程碑注释与 PROJECT_PLAN 对齐（sysmon 接线 = M2） |

文档体系升级（本条目主体）：

- 新增 ADR-0009（SDK 生成物不入库防漂移）、ADR-0010（模块边界三层强制）、ADR-0011（数据库演进式迁移纪律）+ `docs/adr/INDEX.md` 决策索引 + adr 模板升级 Nygard 五段
- 新增 `docs/CAPABILITY_MAP.md`（能力三态表 + 对标 Jellyfin/Immich/Plex 缺口清单，AI 主动提案依据）
- 新增根 `llms.txt`（llms.txt v2 格式文档导航）
- AGENTS.md / AI_README_FIRST.md 增补模块边界、迁移纪律、生成物禁手改、commit scope 约定、Go/React 警戒线
- ARCHITECTURE §5.1 模块边界强制小节、§10 CI 四 job 实况对齐 ci.yml；OBSERVABILITY/GUIDE_API readyz 免鉴权口径统一；HANDOVER search 包现状更正；PROJECT_PLAN 补常驻任务与验收门禁
## M1 收尾：一键启动脚本（2026-08-22，`1ae33eb`）

执行 AI：GLM-5.3（主代理）

双击 `启动服务端.bat` 即可启动服务端（显示局域网 IP 列表 + 防火墙提示 + 全路径调 go 规避 PATH 截断）。三次迭代修复：中文 echo 在 cmd 代码页下破坏后续命令解析 → 全 ASCII；`go` 不在继承 PATH → 全路径调用。已实测 healthz 通。
## M1 服务端核心闭环完成（2026-08-22，`4a42acd`）

执行 AI：GLM-5.3（主代理 + 执行子代理 ×8）

九个模块全部落地、9 包测试全绿、真实二进制集成验收通过（详见 PROJECT_PLAN M1 勾选）。"9 包"口径：9 个有代码包（auth/config/events/filing/httpapi/scanner/store/sysmon/thumbnail）测试全绿；recommend/search/stats 三个 doc.go 空壳包暂无测试；sysmon 属 M2 提前件随 M1 交付。各模块与执行方式：

| 模块 | 执行 | 要点 |
|---|---|---|
| auth 鉴权 | 子代理 | argon2id PHC + token 哈希 + HMAC 签名直链 + chi 中间件（59 用例） |
| store 存储 | 子代理 | SQLite(modernc) 14 表 + golang-migrate + sqlc keyset 分页（9 测试） |
| thumbnail 缩略图 | 子代理 | ffmpeg 封装 + 黑帧检测（全部采样制）+ 工作池 + 真实 ffmpeg 集成测试（16 用例） |
| events 事件 | 子代理 | 进程内总线（慢订阅者丢弃隔离）+ SSE handler（13 用例双轮稳定） |
| filing 文件安全 | 子代理 | 路径穿越全变体/MIME 魔数/上传四道校验/回收站布局（142 子用例） |
| scanner 扫描器 | 子代理 | 全量/fsnotify 增量/轮询兜底/移动合并启发式（12 测试 -count=3 稳定） |
| httpapi 接口层 | 子代理 | 全浏览闭环端点 + 中文极简验收页 + 端到端测试 |
| sysmon 监控 | 子代理 | gopsutil 快照 + prometheus 业务指标（10 用例；M2 提前件） |
| 接线与收尾 | 主代理 | wire.go 扫描适配器、FinishScan/SetScanner、sm=256 档、自噬防御修复与验收 |

**集成验收实测发现并修复**：数据目录配置在库内时缩略图缓存（webp 白名单格式）被扫描器自噬入库——加两道防线（注册互斥校验 DATA_DIR_CONFLICT + scanner SkipDir/watch 过滤）并补测试。移动合并真机验证通过（改名后 asset_id 不变）。
## 协议变更：删除按需缩放副本（2026-08-22，`4a1f3da`）

执行 AI：GLM-5.3（主代理）

用户明确要求"查看永远发原件"：删除 `/media/preview` 端点与 `previewUrl` 字段，三端 SDK 重新生成。同步更新铁律 1（总说明）、ARCHITECTURE §7、GUIDE_API。同 commit 附带：DOMAIN_RULES 黑帧判定语义澄清（全部采样制）、ADR-0005 事件流无外键实现澄清、m4v 白名单补齐。
## CI 与仓库（2026-08-22，`848ef29`/`11a0fdb`）

执行 AI：GLM-5.3（主代理）

GitHub 私有仓库 `Surtr42u/qimeng-media` 建立；Actions 四道门禁（redocly 协议校验 / Go vet+test+build / Web tsc+build / make sdk 三端生成链）首跑全绿；actions 升级 v5/v6 + Go 缓存路径修复。
## M0 地基完成（2026-08-22，`1625db1`）

执行 AI：GLM-5.3（主代理 + 执行子代理 ×3）

| 项 | 执行 | 要点 |
|---|---|---|
| 协议定稿 v0.1.0 | 主代理 | 评审补 9 处缺口（排序/顺位/includeCos/sessionId/标签删除/时间轴删除/作者关注/回收站清空/缩略图尺寸）+ 修复草案 2 处语法错误；redocly 0 error |
| server 骨架 | 子代理 | go.mod + 12 包 doc.go + chi/slog + config（默认 :8420 测试锁定）+ healthz |
| web 骨架 | 子代理 | Vite+React+TS 严格 + Tailwind v4 + shadcn(radix/nova) + TanStack Query + --qm-* 设计 token |
| make sdk 三端链 | 子代理 | oapi-codegen v2.8.0 / @hey-api 0.99.0 / openapi-generator+JDK17（免安装 zip 方案） |
| GUIDE_API.md | 主代理 | 36 端点分组速览 + 关键机制导读 |

环境侧（不入库）：JDK17 免安装（dev-tools/jdk17）、choco make 4.4.1、winget 不可用结论，均记入 TOOLCHAIN_GUIDE。
