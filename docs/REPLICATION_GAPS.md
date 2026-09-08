# REPLICATION_GAPS - Android 复刻差距清单（任务H H0 交付，2026-09-09）

> **用途**：任务I（Android页面复刻卷）各页批的**唯一批次依据**——每批只做本页差距条目，逐条清偿、逐条勾选；清单没有的差距不擅自扩展（记待办停手问）。
> **基准**：GUIDE_UI.md = `<旧项目目录>\docs\GUIDE_UI.md`（旧仓库）+ `QimengNAS\旧版UI实录`（uiautomator dump）。**裁决优先级 = 用户拍板（含《待拍板-20260907.md》台账口径）> GUIDE_UI.md > Web 现版**。
> **调研方法**：2026-09-09 三 researcher 并行只读调研（残留核查+首页 / 搜索·统计·我的 / 收藏·历史·作者·详情），全部条目以**当前代码实测**定位；未采信实录 README「差异速查」表（写于 09-06，多已被任务D 清偿）。
> **视觉口径**：结构/交互语义差距才进批次；颜色/间距/圆角进各页「用户自调区」，reviewer 不得以像素级差异打回。
> **条目状态标记**：`差距`（行动项）/ `缺失`（从零补）/ `拍板豁免`（不进批次）/ `协议缺口`（进台账 §4）/ `存疑`（已由主会话裁决的注明裁决号 R*）。

## 0. 在效拍板保护清单（reviewer 打回前先查；下列条目一律不得判为差距）

| # | 拍板 | 落档 |
|---|---|---|
| ① | 首页筛选入口不做（2026-09-06） | HomeScreen.kt 注释 |
| ② | 药丸进页默认收起、切维度行强制展开（2026-09-07，覆盖 GUIDE_UI B8「切模式默认展开」） | AlbumFilterState.kt |
| ③ | 历史页无清除按钮（协议无 DELETE /history） | HistoryScreen.kt 注释 |
| ④ | 相册 Tab 已删、全部页（feature:all/**）整卷只读冻结 | settings.gradle.kts / TopLevelDestination.kt 实证 |
| ⑤ | 统计行容量段「N 文件 · X GB」降级纯计数（协议无容量数据源） | AllScreen.kt |
| ⑥ | G2 作者总览卡进「我的」页保留（2026-09-08 用户原话） | SettingsScreen.kt |
| ⑦ | 详情页视频全屏两级制（竖屏全屏→可选横屏）；详情胶囊进页默认收起（2026-09-07） | VideoStage.kt / DetailTagSheet.kt |
| ⑧ | 详情页整理/删除文件操作钮、作者集合页路由、详情 pager 行/UpNext 卡——**超规格新增**：GUIDE_UI 无对应条目，I 卷按「保留/裁决」记档，不判差距不判达标 | G1a/G1b |
| ⑨ | **列表族悬浮药丸面板形态保留**（H0 裁决 R1：收藏/历史/作者页的 QimengFloatingPillPanel 半屏悬浮形态，B5 批按旧版实录「FrameLayout 叠放」裁定+G5 记档在案，覆盖 GUIDE_UI L344-348 文档流描述） | QimengPills.kt 注释 |
| ⑩ | 我的页数据管理/数据备份/兼容性检查三入口**范围豁免**（H0 裁决 R7：HANDOVER_APP §2 范围行明示「单机生态页（数据备份/数据管理/扫描）无对应服务端能力，不做」） | HANDOVER_APP §2 |

## 1. 相册残留核查结论（H0 任务①）

**结论：无功能性残留。** 全仓（android/ 下 app/feature/core，含 settings.gradle.kts，大小写各搜一遍，排除 build/ 产物）核查：

- `settings.gradle.kts`：无 feature:album include（模块清单 login/home/all/favorite/history/search/author/detail/stats/settings/upload）。
- `QimengNavHost.kt`：目的地仅 HOME/ALL/STATS/SETTINGS + SEARCH/FAVORITE/HISTORY/AUTHORS/AUTHOR_COLLECTION/UPLOAD/DETAIL，无 album 路由。
- `TopLevelDestination.kt`：无 ALBUM 枚举，四 Tab = 首页/相册(all)/数据/我的；`strings.xml` 仅 `tab_all`=「相册」（拍板④）。

三类**非功能性引用**记档（不清理、不进批次）：
1. **命名承接**（语义已指向现「相册」Tab=feature:all，命名自洽）：TopLevelDestination.kt AlbumIcon、QimengIcons.kt AlbumIcon 定义（注释已注明 2026-09-05 语义转移）、ClientPrefsRepositories.kt 的 albumColumns/KEY_ALBUM_COLUMNS（键名指 route all）、TopLevelDestinationTest.kt 注释。全部页冻结区不动。
2. **生成物/协议层**（禁手改）：android/sdk 的 LegacyBackupData/LegacyImportResult/DefaultApi 中 albumRules 字段（openapi 旧备份导入格式，DOMAIN_RULES §10，与被删 Tab 无关）。
3. **冻结区正常承接**（任务书明确不算残留）：feature:all 的 AlbumViewModel/AllScreen、core:model 的 AlbumDim/AlbumFilterState/FourDimPills 及 favorite/history 对其复用。

## 2. 特殊归宿（三页不进差距清单）

### 2.1 上传页 / 2.2 登录页：无复刻基准
GUIDE_UI.md（482 行）grep「上传」「登录」均 0 匹配；实录 16 页清单均无此二页（H0 实证）。**I8 只做 insets/可用性健康走查**（G3 系统栏修复已落，只核查不返工；对照 Web 现版大致相似即可）。

### 2.3 相册详情页：→ 全部页承接（冻结区），零工作量，任务I 不得重建
GUIDE_UI §相册详情页（L362-369）逐条已由冻结的全部页承接（H0 逐条实证）：两芯片（角色/类型）=四维芯片行超集（AllScreen.kt dividerBeforeIndex）；常规按角色分组=COS 按作品分组=DateGrouping+AlbumFilterState 角色行 kind 分派（有单测）；角色/类型药丸=FourDimPills+QimengValuePillFlow（拍板②收起语义）；筛选面板/列数/双指缩放=AllScreen 既有接线；计数行=纯计数（拍板⑤）。「锁定出处」语义=AuthorCollectionScreen 路由承接（QimengNavHost.kt）。

## 3. 差距清单（按页；建议批次 = 任务I 批号）

### 3.1 首页（feature:home → I1）

| GUIDE_UI 条目 | 状态 | 差距描述（当前代码定位） |
|---|---|---|
| §下拉刷新 L86「清空**所有 tab** 的排序缓存」 | 差距（R3 裁决进批次） | 现实现 refresh() 只刷新当前 tab（HomeViewModel.kt:129-135），另两 tab 保持旧缓存——刷新推荐后切排行榜仍是旧数据。改法：refresh 清三 tab 缓存，非当前 tab 标脏、切入时懒重拉 |
| §下拉刷新 L89 **点赞后返回自动重排**（likeVersion 指纹维度） | 缺失（I1 核心项） | 全仓 grep likeVersion 0 命中；详情页点赞返回后推荐/排行不重拉不重排。「浏览退出保持原样」半边天然满足。改法：core:data 增本地点赞变更指纹（如 LikeMutationTracker），HomeScreen 返回时指纹变则重拉当前 tab 排行榜与推荐流（重拉效果由服务端打分决定，客户端不做语义假设）；SSE 无 like 事件（openapi.yaml:133），本地感知是协议内唯一路径 |
| §UI约束 L312 三胶囊按下缩放反馈 0.92→1.0 | 缺失（低优先微交互） | QimengSegPill.kt:40-59 全仓胶囊单源无 scale 动画；一处补齐全局生效（在 H1 瘦身后组件形态上补） |

已核一致要点：三 tab 语义/排行榜四档周期/横滑切换/搜索框跳转/双列网格/距底≤6 项增量/刷新 seed++/切 tab 不重拉/熄屏不刷新/点卡传已加载批次/空态/双击回顶。

用户自调区：列数控件形式（文字「N列」vs 旧版图标 ic_grid_1/2，QimengTitleRow 已有图标款可换）；横滑手感参数；HOME_TITLE 与搜索占位文案资源化（代码卫生）。

### 3.2 搜索（feature:search → I2）

**总体判定：高度对齐，无结构差距。** 三层状态/推荐搜索/搜索历史≤20 去重/建议行五维标签/结果态/返回逐层退/双指缩放均已实现且与 GUIDE_UI 逐字一致（实录 README 差异速查 #8 已被任务D 清偿，实测确认）。

| GUIDE_UI 条目 | 状态 | 差距描述 |
|---|---|---|
| L130 键盘适配（SOFT_INPUT_ADJUST_NOTHING 语义） | 存疑（R12 裁决→I2 走查项） | View 体系 windowSoftInputMode 在 Compose edge-to-edge 下无直接对应（AndroidManifest 未设）；I2 以实测判定 IME 不遮输入框/结果即可，非复刻硬差距 |

已核一致要点：顶栏三件/空态两区+清除/建议行结构从短到长/≤10 条/点建议即搜/结果 3 列/词保留/点搜索栏回补全态/返回族/六维匹配 includeCos/日期分组+pinch（列数不持久化=旧仓库源码判读）。

用户自调区：搜索框胶囊底色/前置图标、建议行 padding、词丸流样式、空态文案字样。

### 3.3 数据统计（feature:stats → I3；工作量勿低估：≈从零补 4 卡+1 个统计详情页）

**旧 M4-6 的 C1（90 天四档）/C2（数字卡静态）/C3（砍详情页）拍板均被 2026-09-08「完全复刻」新拍板覆盖**（C3 另被 H2 明确推翻），下列差距无拍板保护：

| GUIDE_UI 条目 | 状态 | 差距描述（当前代码定位） |
|---|---|---|
| L208 时间范围**三档 7天/30天/全部** | 差距（R10 裁决回改） | 现状四档含 90 天（StatsRange.kt:8-13，任务G 对齐 Web 产物）；回改三档 |
| L208 时间范围**全局联动**（数字卡/趋势/排行/分布全跟随） | 差距 | 现状仅趋势随档重拉（StatsViewModel.kt:55-68），数字卡静态只拉一次（:49-51,70-75）；联动方案=窗口浏览/播放/时长由 /stats/trends 桶求和（协议够，含 playCount/seconds 字段）；需配序号防重（对齐 GUIDE L212） |
| L209-211 数字卡 6 指标（总浏览/总播放/总浏览时长+总文件/总占用/平均浏览次数） | 差距 | 现状指标集不同（总文件/图片/视频/库容量/今日浏览/总浏览，StatsScreen.kt:83-92 静态）；「平均浏览次数」分母无协议源→协议缺口 §4-#31a，先做其余 5 指标 |
| L213 趋势卡**点击数据点高亮+数值气泡** | 差距 | 现渲染层=QimengTrendLineChart（Vico，StatsScreen.kt:128 调用，H2 交付）；marker/persistentMarkers 参数位已预留（ADR-0018），I3 实装交互 |
| L213 趋势卡点击进**分类型趋势详情**+右上「分类型趋势 ›」 | 差距 | TrendCard（StatsScreen.kt:117 起）无 onClick 无入口；注释「C3 砍详情」旧拍板已被 2026-09-08「完全复刻」覆盖（ADR-0018 同步记档） |
| L214 分布统计小入口卡（文字卡+点击进分布详情） | 缺失 | 无对应 composable；来源维度协议缺口见 §4-#31b，类型库存部分协议够先做 |
| L215 常看文件卡（Top3+点击进常看详情） | 缺失 | 协议口径缺口 §4-#31c（/rankings 是累计热度非窗口浏览次数） |
| L216 常看作者与标签卡（Top3+点击进详情） | 缺失 | 标签榜无端点 §4-#31d；作者可累计 viewCount 近似 |
| L218-224 详情页跳转链（卡片进详情/条目跳文件·作者·搜索携词） | 缺失 | 无路由注册（QimengNavHost.kt）；标签跳搜索携词需 Search 路由加 initialQuery 参数（SearchScreen.kt:57-62 现签名无） |
| L225-234 **统计详情页四模式**（分类型趋势/常看文件两排序切换/常看作者 Top15+标签 Top10/分布对比） | 缺失 | 整页缺失；分类型趋势多系列**复用 H2 的 Vico 封装**（mediaType 三次调用拼系列，协议够）；Top20 排行复用 QimengRankCard。注意：封装现无图例（legend）能力——多系列若需系列名标识，I3 评估扩封装或在调用点自组 Compose 图例行（H3 收官审查记档） |
| L229 Top20 排行（相对第一名进度条）+ L247 空态 | 缺失 | 随详情页 |

已核一致要点：「数据」Tab/趋势「全部」动态分桶（服务端 DOMAIN_RULES §5 口径）/趋势空态文案/数据源单一真相（/stats/trends）。

用户自调区：卡片底色、数字卡格内排版、趋势图高度/线宽/点半径/面积透明度、X 轴标签抽稀上限。

### 3.4 我的（feature:settings → I4）

| GUIDE_UI 条目 | 状态 | 差距描述（当前代码定位） |
|---|---|---|
| L252 图片/视频数量卡片（实录 mine.txt 两卡「图片 5721」「视频 414」） | 缺失 | 页首无数量卡（ServerUrlCard 占位）；协议够=/stats/overview imageCount/videoCount 纯计数（不触拍板⑤，该豁免仅指容量） |
| L253+L268 **主题色彩**行（不可点击纯展示，副文案「跟随手机白天/深色模式自动切换」） | 缺失（R8 裁决进批次） | 入口行族无该行（SettingsScreen.kt:104-120）；EntryRow onClick=null 形态已具备（:299-320） |
| 行结构「标题+副文案」两行（实录 mine.txt 逐字） | 差距（轻） | 现状 EntryRow 单行 label+右侧灰 detail；推荐偏好行 detail 被挪用显示当前预设名（:107），与实录副文案「调整首页推荐算法的权重偏好」不同源 |
| L257-266 数据管理/数据备份/兼容性检查三入口+独立页 | **拍板豁免⑩**（R7） | 单机语义，NAS 版无对应；范围行明示不做 |
| L256 兼容性检查 | **拍板豁免⑩** | 同上 |

已核一致要点：收藏/浏览历史/作者管理三行/推荐偏好 BottomSheet 四预设逐字+整行应用+高亮/作者总览卡（拍板⑥，不进回改候选）。

用户自调区：入口行 Surface 底色圆角、卡片配色、PrefsBottomSheet 行高选中色、退出登录钮样式。
存疑记档：AuthorOverviewCard 总览行点击进作者集合页注释标「待 G1b 批接线」（SettingsScreen.kt:219,269）——I4 开工时核实是否已清偿。

### 3.5 收藏 + 浏览历史（feature:favorite/history → I5）

| GUIDE_UI 条目 | 状态 | 差距描述（当前代码定位） |
|---|---|---|
| §公共UI工具 L300 + §全部页 L149 **双指缩放 2-5 列**（收藏/历史两页） | 差距（R2 裁决：图标豁免不覆盖手势） | 两页固定 3 列无 pinch（FavoriteScreen.kt:53,148 / HistoryScreen.kt:111,151）；QimengGridPinchGesture 组件已有、无人接线 |
| 同上 **列数持久化**（收藏→updateGridColumnsAll） | 缺失 | ClientPrefsRepositories.kt:79-95 仅 home/album 两键，无共用档键；三页（含作者文件）共用全部页档位——**只读引用，不改 feature:all** |
| §收藏页 L403/L406、§浏览历史 L389 作品/角色**多选** | 差距→**协议缺口 §4-#29** | AlbumFilterState.kt:32-33 单值模型（author/character: FacetOption?）+toggleSelection 替换式（:175-180）；/assets source/character/work 均单值参数——多选需协议扩展，I5 不做，等拍板 |
| §收藏页 L410 详情页返回**自动刷新** | 差距 | FavoriteViewModel.kt:82-84 仅 init 拉一次；详情 toggleFavorite 后返回列表不反映。补 resume 重拉 |
| §浏览历史 L391（Flow 自动性语义）返回自动刷新 | 差距 | 详情页浏览上报后 lastViewedAt 已变，返回不自动重拉（HistoryViewModel 仅 init/refresh）；Flow 自动性服务端化后丢失，resume 重拉补偿 |
| §浏览历史 L386/L388 **「作品」维缺失** | 差距→**协议缺口 §4-#30** | HISTORY_DIMS 无 AUTHOR（HistoryViewModel.kt:33）；/history 无 source/authorId 参数（openapi:236-243 仅 work/character/mediaType）；facets history=1 子集已就绪，补维只差协议参数——I5 不做，等拍板 |
| §浏览历史 L390 清空按钮 | **拍板豁免③** | 协议无 DELETE /history |

已核一致要点：四芯片字样逐字/分区药丸全·常规·COS/角色行 COS 分派/悬浮面板（拍板⑨）/计数行/3 列网格/下拉刷新/空态双分支逐字/历史日期分组+cursor 分页。

用户自调区：两页同套组件——胶囊配色、统计行字号色、面板阴影内边距、网格间距、日期组头样式。

### 3.6 作者（feature:author → I6）

| GUIDE_UI 条目 | 状态 | 差距描述（当前代码定位） |
|---|---|---|
| §芯片栏配置对比 L68 **常规=作品/角色/类型（无分区）** | 缺失（新增整套，非回改） | AuthorCollectionScreen 现状裸网格无芯片栏（AuthorCollectionScreen.kt:33-36 KDoc 明示）；复用 FourDimPills 维度子集+H1 瘦身后组件 |
| §芯片栏配置对比 L69 **COS=角色/类型**（角色按作品分组） | 缺失 | 同上 |
| §导航结构 L36 + §全部页 L149 **缩放列数 2-5 + 列数持久化**（作者文件页，共用全部页档） | 缺失 | COLLECTION_COLUMNS=3 固定（AuthorCollectionScreen.kt:24），无 pinch 无持久化键 |
| §芯片栏配置对比 L73 排序=单钮「排序 ▾」对所有分类生效 | 差距（形态，R6 裁决按 GUIDE_UI 回改） | G2 改三枚排序胶囊直排（AuthorScreen.kt:103-114，Web AuthorsPage 形态，无拍板保护）；功能已覆盖（对体系+关键词后排序生效）——回改单钮+下拉形态 |
| 作者管理列表计数行/RankCard 行/行分隔线 | 超规格新增（拍板⑧类记档） | G2 对齐 Web 产物，GUIDE_UI 无条目；保留/裁决 |
| 作者集合页计数行「作者 · N 个文件」/空态「该作者下暂无内容。」 | 超规格新增（记档，R 裁决保留） | Web CollectionPage 文案，GUIDE_UI 无条目 |

已核一致要点：全部/常规/COS 三胶囊/按名搜索/下拉刷新/关注 toggle 乐观翻转+回滚/行点击进作者文件页（路由入栈隐底栏）/日期分组/快速转跳落点。

用户自调区：关注胶囊配色、RankCard 描边、搜索框胶囊样式、分隔线颜色。
协议面：作者集合页芯片体系所需协议**已全部就绪**（/assets authorId+mediaType+source+character+work；facets authorId 收窄）——纯前端缺失，无台账项。

### 3.7 详情（feature:detail → I7；开工前先读 §4-#33 基准裁决）

**总述**：GUIDE_UI 基准=全屏沉浸 4 层（媒体 edge-to-edge＋上下渐变 chrome＋单击显隐）；现状=G1a/G1b 按 Web AssetDetailPage 做的排版页。基准已切回 GUIDE_UI（任务H 主代理拍板），凡冲突且无拍板保护者列差距；台账 #33 立知情条（2026-09-05「Web 排版基准」拍板 vs 2026-09-08「完全复刻」，建议口径=沉浸复刻先行，翻案低成本）。

| GUIDE_UI 条目 | 状态 | 差距描述（当前代码定位） |
|---|---|---|
| §详情页 L158-160 **沉浸 4 层组织**＋顶/底渐变遮罩操作层 | 差距（结构核心） | 排版态媒体缩 68vh 舞台（DetailStage.kt:25）；渐变 chrome 层不存在 |
| §沉浸浏览 L271-276 **单击显隐 chrome+系统栏**、▶ 跟随 chrome | 差距 | 图片态单击=开全屏覆盖层（ImageStage.kt:11-14）；视频态单击归播放器手势（VideoStage.kt:104-106）；chromeVisible 回调悬空（DetailScreen.kt:145） |
| §详情页 L171 **信息钮+信息 BottomSheet**（文件名/出处/尺寸/时长） | 缺失 | 无信息钮无弹窗（meta 行 DetailSections.kt:220-257 无文件名/时长）；协议已给 width/height/durationMs，零解码成本 |
| §详情页 L172 **快速转跳**（底部按钮→作者列表 BottomSheet） | 缺失（部分被作者卡名字链接替代） | DetailSections.kt:526-572 直跳作者集合页，无转跳弹窗 |
| §沉浸浏览 L279 **播放中按返回先退 chrome 浏览模式** | 缺失 | 无 BackHandler 拦截，播放中系统返回直接 pop 路由 |
| §详情页 L163 视频海报态**横滑切换** | 差距 | VideoStage.kt:104-106 明示不接 onSiblingNavigate——旧版预览态可横滑浏览 |
| §详情页 L172 标签管理**逐条即时语义**（chip 关闭即时移除/输入即时生效/最近添加置顶） | 差距→**协议缺口 §4-#32a** | 现状草稿式多选+保存整体替换（DetailTagSheet.kt:52-58、DetailViewModel.kt:532-554 PUT tags）；PUT /assets/{id}/tags 整体替换是唯一端点——I7 不做，等拍板 |
| §详情页 L166 冷启动空库 5×300ms 有限重试 | **豁免**（R5 裁决） | 服务端化后「空库」由服务端扫描管理，场景弱化；现状错误态+手动重试满足健壮性，不进批次 |
| §详情页 L167 写入 30s 防抖自动同步 | 不适用 | 服务端化 PUT 即落库 |
| §缩略图 L284 首页 1 列 960x540 清晰度档 | 存疑→不进批次 | 服务端缩略图三档架构替代（sm/md 直链），无按列数升档机制；归 §4 记档 |

已核一致要点：缩放手势 0.5~5x/双击 1.8/智能分层 4096（ZoomImageView.kt:423-428,233-238）/断点续播+已看完徽标/BiliPlayerView G1~G9 全链（5s 隐藏/2x 长按/倍速四档/三档 seek 上限/时间轴标签红金色单源）/全屏两级制（拍板⑦）/兄弟 push 叠栈返回链。

用户自调区：舞台黑底钳制比例、互动钮配色/bounce 参数、meta 分隔色、68vh 数值、底部留白档。

### 3.8 上传 + 登录（→ I8 只走查，无复刻基准）

见 §2.1/§2.2。走查清单：insets 健康（G3 修复后回归确认）/键盘遮挡/错误态可用/对照 Web 现版大致相似。

## 4. 协议缺口归类（已进《待拍板-20260907.md》#29~#33，不进 I 卷批次，零协议红线）

| 台账# | 内容 | 影响 |
|---|---|---|
| #29 | 多选筛选协议缺口：/assets、/history 的 source/character/work 均单值参数——收藏/历史作品/角色多选（GUIDE_UI L403/406/389）需协议扩展 | I5 相关条目冻结 |
| #30 | /history 缺 source/authorId 参数——历史页「作品」维（L386/388）无法支撑；facets history=1 已就绪 | I5 相关条目冻结 |
| #31 | 统计页协议缺口组：a)「平均浏览次数」分母（窗口内有浏览的文件数）无端点；b) 来源（常规/COS）维度统计无参数无端点；c) 常看文件口径（/rankings=累计热度非窗口浏览次数、无按时长数据源）；d) 标签榜无端点、常看作者仅累计 viewCount 可近似 | I3 只做协议内可达成部分 |
| #32 | 标签协议子项：a) PUT /assets/{id}/tags 整体替换是唯一端点，旧版「逐条即时移除」语义无支撑；b) 时间轴标签颜色/预设类型不在协议（客户端按 name 前缀约定兜底，跨端一致性存疑） | I7 相关条目冻结 |
| #33 | 详情页基准知情条：2026-09-05「详情页=Web 现版排版基准」拍板 vs 2026-09-08「完全复刻」拍板冲突；建议口径=按任务书沉浸复刻先行（用户最新拍板优先），用户翻案随时低成本回改 | I7 方向（按建议口径执行，非已拍板；台账 #33 保持开放直至用户表态） |

另记档（非缺口）：点赞变更无 SSE 推送（I1 本地指纹路径已定为协议内方案）；缩略图按列数升档（服务端架构替代）；翻件 i/N 断链=既有 #21。

## 5. H0 验收自查

- [x] 清单覆盖 8 页（首页/搜索/数据统计/我的/收藏/浏览历史/作者/详情）无遗漏；上传/登录/相册详情以 §2 记档结论覆盖。
- [x] 每条差距可定位到 GUIDE_UI.md 具体条目（节名+行号）与当前代码具体文件。
- [x] 相册残留核查结论独立成节（§1）。
- [x] 协议缺口直接归类进台账（§4），不进批次。
