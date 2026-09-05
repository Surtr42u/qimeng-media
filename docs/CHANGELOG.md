# CHANGELOG - 变更历史

本文件归档项目历次功能/修复/决策变更，每条对应 git 提交（commit hash 标注）。

## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型（真实命名，品牌-版本），便于追溯每次改动由谁完成。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。
- 署名自查（2026-09-05 补）：每条变更由执行会话先确认自身实际运行模型的真实名称再署名（GLM-5.3 与 GLM-5.3-Flash 是两个不同模型名），禁止沿用上一会话或上一条目的署名行；历史条目真实署名不动。

---

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
