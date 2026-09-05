# HANDOVER-UI - 桌面客户端风格媒体库 UI 交接说明

> 写给下一位专做 UI 的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 最后更新：2026-09-05（**W-4 web play/dwell 打点补齐交付，§5 第 13 条闭合**——新增 `hooks/use-dwell-report.ts` + VideoPlayer `onPlay` 派发，隔离实例实测 play/dwell 落库与分段恰一条）；当日 UI 收尾三批 W-1/W-2/W-3 全部交付（§5.9 各批 ✅ 注，W-3 reviewer 打回 1 轮返工后通过）；当日 W-3 一致性复审：文档声明逐项核验通过、tsc/build/lint 复跑全绿、修补 §5 第 2 条与文头两处滞后，新记账 §5 第 13 条 web 从未上报 play/dwell。2026-09-04 §5.9 UI 收尾执行批次定稿：W-1 上传入口/W-2 弹窗/W-3 ArtPlayer 三批任务书 + 详情页发现项记账；用户拍板 UI 收尾后启动 M4，M4 任务书见 `docs/HANDOVER_APP.md`。第十笔：后端四缺口清零——上传路径富化（上传后自动 EnrichAsset）、探针协议债归位（/api/v1/healthz|readyz 免鉴权+根路径运维别名）、设置页配置持久化（GET/PUT /api/v1/config；scan 两项为预留字段暂未接入管线、保存后暂不生效；upload 两项实时生效）、客户端异常上报通道（POST/GET /api/v1/client-logs，维护页异常表接真数据）；对抗审查打回 1 轮（P1 空态崩页/scan 失实口径/autoAccept 缺键静默关闸）已返工修复。此前 09-03 第七笔：第六笔原型七组缺陷移植 web 正式端（AssetSummary.authorNames 全链路、卡片作者行+时长角标、作者榜浏览口径、作者副标题+·COS、相册时间分区）+ 原型 2 处 CSS 缺陷修复；后续 09-04 作者总览卡收敛 Top5 预览。此前第五/六笔见 CHANGELOG）
> 用途：新开的 AI 会话直接读本文档即可接手 UI 工作，无需回看本会话记录。
> 主交接文档（后端/进度/约定）仍以 `docs/HANDOVER.md` 为准，本文档只覆盖 UI 路线。

## 1. 任务背景与当前路线（用户拍板）

1. 用户要求复刻桌面 PC 客户端 UI，并把这个界面的**侧边栏**移植进 qimeng-media 项目。
2. **UI 主路线 = `media-ui-prototype/`**（桌面客户端风格的静态原型：纯 HTML/CSS/JS）。
3. **v2（`web/src/panel-demo/`）也已删除（2026-09-01）**——web 端只保留鉴权基建（AuthGate/RootLayout/SseBridge/LoginGate）与生成 SDK、共享工具，首页为占位提示；
   ~~待原型敲定后把功能移植进 web 重建页面~~ **已完成（2026-09-02，阶段 A mock）**：九页+壳层已用 React 重建进 web（`web/src/pages/`、`components/shell/`、`styles/prototype.css`），访问 `http://127.0.0.1:8420/` 即原型界面；**接真实数据（阶段 B）未启动**。
4. **v1 旧壳与 v2 panel-demo 已于 2026-09-01 彻底删除**（v1：pages/、旧壳专属 components、旧 hooks、/legacy 与 /detail；v2：panel-demo/、components/tremor/、lib/tremor、相应依赖包），web 端仅保留鉴权基建与工具层。
5. 原型内已**去除所有平台命名痕迹**（标题、注释、数据字段、任务书），措辞统一中性化；后续新代码同样不提及。

## 2. 真机调研方法（可复用）

桌面客户端是 Electron 应用，可用 Chrome DevTools 协议（CDP）读取内部 DOM。

```bash
# ① 确保旧进程退出（未带调试参数启动的进程不会监听端口）
taskkill /F /IM 客户端.exe
# ② 以调试模式启动（保持运行，另开终端/后台）
"C:\Program Files\...\客户端.exe" --remote-debugging-port=9222
# ③ 列出页面（主页 = index.html#/page/home/recommends；player.html = 播放器）
curl http://127.0.0.1:9222/json
# ④ 连接 ws://127.0.0.1:9222/devtools/page/{id} 发 Runtime.evaluate 读 DOM / Page.captureScreenshot 截图
```

现成抓取脚本（在 `%TEMP%`，可拷到工作区复用）：

| 脚本 | 用法 | 功能 |
|---|---|---|
| `read-dom.mjs` | `node read-dom.mjs <pageId> [depth] [nodes]` | 通用 DOM 树（tag/id/class/role/文本/href/可见性过滤） |
| `card-extractor.mjs` | `node card-extractor.mjs <pageId> [out.json]` | 推荐卡片结构化（id/cover 真实封面/lines 分行的 播放·互动·时长·标题·作者·日期）；%TEMP% 中的旧脚本可按此改名取用 |

踩坑记录：
- 卡片根容器定位 = 沿 `a[href*="/video/VID"]` 向上找 **textContent 含"不感兴趣"的最近祖先**（卡片 hover 菜单特征文本）
- `node -e` 内联脚本传中文会乱码；用脚本文件 + 结果写入 utf8 文件再查看（终端管道显示乱码不全代表数据坏）
- 封面要过滤 `data:` 打头的占位 img，拿 `img.src`（真实地址形如 `//cdn.example/archive/<hash>.jpg@672w_378h_1c_!pc-common-cover-h.webp`）
- 本地 curl 可访问原图床，但 ZCode 内嵌浏览器（IAB）加载不了 → 原型图片全部下载到本地

## 3. 原型实测参数（复刻依据）

客户端测试窗口 **1188×742**；白色底；14px 系统字体栈（-apple-system/BlinkMacSystemFont/Helvetica Neue）。

| 区域 | 尺寸 | 说明 |
|---|---|---|
| 左侧边栏 | **64px 宽、通栏** | 顶部返回箭头（灰）→ 页面导航（图标+12px 文字）→ 底部图标组（明暗主题/维护/设置，20px 线形图标，hover 圆角灰底） |
| 顶栏 | **75px 高** | 左：分类 tab；中：搜索框（顶栏框内居中）；右：窗口键（—□×） |
| 分类 tab | 320×44 | 直播/推荐(激活)/热门/追番/影视；16px；激活态主色加粗 + 底部 22×3 圆角短横线（现为 推荐/cos/热门 三项） |
| 搜索框 | **336×34**，圆角 17 | 聚焦主色描边；聚焦弹出居中下拉面板（搜索历史+推荐搜索） |
| 卡片流 | **4 列**（minmax ≈230px，gap 20-24） | 封面 16:9 圆角 8px |
| 标题 | 14px/20px 两行截断 | 卡片 hover 变主色 |

主题色（已统一为侧边栏主色）：`#4250af`（激活 tab/激活侧栏项），激活深色 `#35428f`；文字 `#18191C`；次要 `#9499A0`；分割 `#E3E5E7`。深色模式由侧栏月亮按钮切换（`.dark`，主色 oklch(0.836 0.074 258.58)）。

## 4. 原型现状（media-ui-prototype/，已完成并逐页截图验证）

```
media-ui-prototype/
├── index.html   # 六个主导航页（首页/相册/我的/数据/维护/设置）+ 三个子页（搜索结果页/完整榜单页 #page-ranks/作者管理页 #page-authors）+ 侧栏 + 顶栏 + 搜索面板
├── style.css    # 全部样式（设计 token 集中在 :root/.dark，颜色无重复字面量；--elev 为深色表层面 token）
├── app.js       # 首页卡片渲染 + 相册筛选 + 页面切换/返回历史栈 + 主题切换 + 搜索结果页（类型/排序/筛选面板/标签池增删/历史搜索）+ 榜单子页单榜切换（RANK_PAGE_TITLES/data-panel）+ 作者管理页（AUTHORS mock + renderAuthors：体系胶囊/排序三项/搜索过滤/关注 toggle）+ 页面内交互
├── covers/      # 本地化封面/头像（14 张）
└── data/        # home-dom.json / home-cards.json（真机抓取档案）
```

| 导航 | 页面 | 状态 |
|---|---|---|
| 首页 | 卡片流（12 张真机卡，已删播放/互动/点赞数/作者头像/广告/hover 菜单——用户拍板）；网格顶沿贴顶栏下缘（`#page-home .grid` margin-top -11.5px，静止态实测与我的/设置卡同线 83.1/82.9） | ✓ |
| 相册 | 两段式胶囊筛选（维度行 4 维+值行带计数+展开/收起+排序）+ 即时过滤内容网格（13 卡、mock 空态）；筛选区无卡片盒（2026-09-02 用户拍板对齐：行缘与网格 24px 线同线） | ✓ |
| 我的 | 头部资料卡（无头像）+ Tabs；关注作者卡=作者名+N 个作品+关注按钮（双向切换 已关注灰 ↔ 关注主色，无头像）；浏览历史=真封面历史流（与首页同款图，已看完/进度/时间徽标）+ 搜索框真实过滤（标题/作者子串，空组连标题隐藏） | ✓ |
| 数据 | 时间筛选 + 6 指标 + 趋势线 + 内容分布双环 + 排行榜（2026-09-02 二轮改造：数字徽章全删；**内容榜独占全宽一行**、条目=首页同款封面卡 rank-item（16:9 封面+右下数值徽标+两行标题）；标签榜/作者榜/作者总览三卡等宽 3 列轨道；**作者总览卡**=行式列表（作者名+作品数 Top5）+「管理」入口进作者管理页——实现与左右两卡完全一致，用户拍板） | ✓ |
| 维护 | 性能监控（4 圆环+指标卡+网络负载曲线）+ 维护工具（文件管理/回收站+日志表） | ✓ |
| 设置 | 扫描/上传/界面表单卡 + 保存提示 | ✓ |
| （子页）完整榜单页 | #page-ranks：数据页排行卡「查看全部」进入，**只显示点进的那一个榜**（JS 按 data-rank 切 data-panel + 标题联动）；内容榜=15 张封面卡网格，标签/作者榜=行式列表 | ✓ |
| （子页）作者管理页 | #page-authors：「作者总览·管理」进入（不再跳我的页），**全部 15 位作者**（mock 12 常规+3 COS）；体系胶囊 全部/常规/COS + 旧版排序三项 默认/经常浏览/文件数量 + 名字搜索实时过滤 + 关注按钮内存态 toggle；行=作者名+关注按钮（体系小标/作品数/字徽头像均按用户要求删除） | ✓ |
| （顶栏） | 推荐/cos/排行榜 只在首页显示；搜索面板（搜索历史胶囊+展开更多+推荐搜索占位） | ✓ |
| 排行榜周期行 | 顶栏第三 tab 为「排行榜」（2026-09-02 用户拍板，原「热门」更名、综合热门/排行榜二级行删除）；下方唯一导航行=日榜/月榜/周榜/年榜胶囊，行中心对齐侧栏「首页」项中心 | ✓ |
| 悬浮刷新 | 右下角固定悬浮按钮：白底圆角卡（52px/圆角14）+ 深色刷新图标 + 投影，全页面常显；原型点击仅旋转反馈未接刷新 | ✓ |
| 搜索结果页 | 搜索框回车/点历史词进入（顶栏 tab 与周期行隐藏）；侧栏返回按钮回退到搜索前页面（showPage 页面历史栈，navHistory）；类型 tabs 综合/视频/图片/音频+计数徽标；排序行仅「综合排序/最多点击」（用户拍板）；「更多筛选」面板六行（用户拍板精简）：顺位/播放次数/文件大小/时间范围（按年份区间→起止年下拉）/标签模式（模糊·精确）/标签（多选+池增删：+添加入池、hover×删除级联，名称升序，内存态）；类型与标签筛选真实生效，区间类仅样式；分区/作品/角色/作者维度筛选只在相册页；新搜索自动重置筛选 | ✓ |

注：推荐搜索占位「推荐搜索词将在接入推荐参数后生成」——推荐参数（9 维权重）**未接入**（用户拍板等做完再一起接）。

原型踩坑记录（2026-09-02）：
- **`hidden` 属性失效通用坑**：任何类只要设置了 `display`（flex/grid/block…），就会覆盖 `hidden` 的 UA 默认 `display:none`，元素常驻显示。已踩三次：`.page/.m-pane/.tabs`、`.subnav`、`.search-clear`（未搜索时清除按钮常驻即此因）。规则：给带 `hidden` 用法的类一律补 `类名[hidden] { display: none; }`，新增组件时自查。
- 顶栏 tab 行曾被「我的」页的 `.tabs { border-bottom + padding-bottom:10px }` 撞名污染：tab 行下多出全宽浅色底线（即用户指出的"左边下方横线不对"的元凶之一）且按钮上移 5.8px。已将我的页规则作用域限定为 `#page-mine .tabs`——新增页面级样式时避免使用 `.tabs/.tab` 这类全局名。
- 激活短横线（`.tab.active::after`）位置=文字下方约 0.9 倍字高间隔处（`bottom:-6px`，实测间隔≈16 CSS px），参照桌面客户端截图逐像素测量得出，勿改回贴字位置。
- 导航纵向对齐规则（用户拍板，调侧栏/行距前先读）：返回箭头中心 ↔ 顶栏 tab 行中心（`.sidebar--back` margin-top 6px）；排行榜周期行（日榜/月榜/周榜/年榜）胶囊中心 ↔ 侧栏「首页」项中心（`.rank-panel` padding-top 2px + `.sidebar--pages` margin-top 14px）。改任一出行高/边距都要连锁重算，改后浏览器实测中心偏差应 ≤1px。
- **style.css 换行符混用（CRLF 为主 + 少量 LF）**：脚本改 CSS 时正则必须写 `\r?\n`，精确字符串锚点会静默失配（已踩）；同理 HTML/JS 也有混用风险，脚本改完必须 grep 验证 + `node --check`。

## 4.5 对齐纪律（用户拍板 2026-09-02：写入 UI 开发规则，AI 每次改动自查，用户不再提醒）

**每一次 UI 改动，交付前必须过以下对齐自查（浏览器 `getBoundingClientRect` 实测，不允许目测）：**

1. **横向同线**：同一页面里所有"行级块"——导航行、工具栏、筛选面板、卡片网格——左右边缘必须与内容区 24px 内缩线完全对齐（容器级 left/right spread = 0）。面板/卡片容器不得比相邻行多缩进（案例：搜索筛选面板曾 margin 24px 导致比工具栏突出一截，已改 `margin: 12px 0 0`）；需要留白只能加内边距（padding 向内），不能动外缘。**滚动条也算**：`.content` 已加 `scrollbar-gutter: stable` 预留滚动条位（有/无滚动条的页面内容等宽，切页不跳宽），勿删。
2. **列内对齐**：多行"标签+选项"结构（如 .f-row）的选项列必须共用固定标签宽，保证各行选项起始 x 一致。
3. **纵向对齐**：按上方踩坑记录第 3 条的导航纵向对齐规则执行；改行高/边距后连锁重算。搜索页同规则（2026-09-02 补）：类型行按钮中心 ↔ 侧栏「首页」项中心（`.stype-row` margin-top **-17.5px**，2026-09-04 重校——stype-count 徽标加入后行高增至 48.9px，旧 -14.9px 宽窄视口均偏 +2.6px；配 `.stype` nowrap+flex:none 防窄屏压缩换行暴涨行高，`.stype-row` overflow-x:auto + 滚动条隐藏兜底，media ≤499px gap 6px）、排序行胶囊中心 ↔ 侧栏「相册」项中心（`.s-toolbar` margin-top 1.5px，原 padding-top 2px 连锁重校）。终测（2026-09-04，474px/1000px 双视口徽标全渲染静止态）：类型行 -0.3px、排序行 +0.5px。**全部页面首行同规则**（2026-09-02 用户拍板"首行贴侧栏节奏"，数值均为静止态实测）：细行中心 ↔ 首页项中心——相册维度行（`.filter-card` margin-top -5.9px）、数据时段行（`#page-data>.seg-row` -5.8px）、维护页标题行（`.page-head` -13.8px）；高卡片顶沿贴顶栏下缘(82.5)——我的资料卡/设置表单卡（margin-top -11.6px，实测 82.9）、首页网格（`#page-home .grid` -11.5px，实测 83.1）。改任一页行高都要连锁重算并实测。
3b. **大标题（page-head h2）的视觉对齐基准 = 侧栏首页图标块顶部，而非 nav-item 整体中心**（2026-09-02 用户拍板）：18px 大标题与 26px 图标块都是竖向块，顶对齐才视觉齐平；按"整体中心对齐"数字达标但用户仍判不对齐（本条教训来自作者管理页）。三页 page-head margin-top -13.8px，实测 h2 顶 80.5 vs 图标顶 80.8。榜单子页/作者管理页/维护页适用。
4. **改完即测**：交付信息里必须附实测数字（边缘 spread、中心偏差），实测不达标不算完成。此前每处对齐问题都是用户先发现的——从本条起 AI 先测后交。
5. **实测必须在 page-in 动画结束后取值（2026-09-02 重大教训）**：`.page:not([hidden]) { animation: page-in .18s ease-out }` 的 `translateY(4px)` 会污染切页瞬间的 getBoundingClientRect——showPage 后立即实测读到的全是动画中间帧（普遍假性偏高 ~4px）。本轮用动画污染值校准过的一批负 margin 全部偏差 4~5px，用户肉眼发现后按静止态（await 300ms）重测修正。**此后一切对齐实测必须 `await sleep(300)` 后再取 rect**，本节旧实测数字凡未注明"静止态"的均不可信。静止态终值（第二首轮校正）：三页标题行 -13.8（视觉基准=图标块顶，见 3b）、数据 seg-row -5.8、相册 filter-card -5.9、搜索 stype-row -17.5（2026-09-04 二次重校，见规则 3）、我的/设置卡 -11.6、首页网格 -11.5。**新增教训（2026-09-04）**：对齐实测必须等异步数据（徽标计数等）渲染完成——徽标把行高从 37 撑到 48.9，未渲染时量到的对齐是假达标。

## 5. 待办与约定

1. ~~原型各页达成视觉验收后 → 把功能移植进 web 端重建页面~~ **已完成（2026-09-02 阶段 A）**：web/src/pages/ 九页 + components/shell/ 壳层 + styles/prototype.css；记账债两笔已还（renderCard/mediaCardHtml 合并为 `components/media/MediaCard.tsx`；`.rank-cards` 窄屏单列回退已补进 prototype.css 追加段）。
2. **接真实数据（阶段 B，2026-09-03 全部完成）**：✅ 已接——首页推荐流/相册列表（2026-09-03 升级四维胶囊：分区 all/常规/COS + 作者 + 角色·作品 + 类型，GET /assets/facets 排自身口径，见 CHANGELOG「相册四维聚合」条目）/详情页（`hooks/use-assets.ts`、`pages/AssetDetailPage.tsx`、`lib/format.ts`）+ 维护页文件管理（库 CRUD/目录树）/回收站（`hooks/use-libraries.ts`、`use-trash.ts`）+ **搜索页（FTS5 多维筛选全真，q 已传后端）/我的页（资料卡/关注/收藏/浏览历史三 Tab 全真）/数据页（6 指标卡真库数据/趋势/双环/内容 Top5/标签榜/作者榜/作者总览）/完整榜单页/集合子页（tag/author 真网格，注意 COS 作者集合要 includeCos）/作者管理页（140 位真作者+关注 toggle）/顶栏搜索建议（localStorage 历史+tags/authors 推荐词）/设置页推荐偏好卡（9 维滑杆+4 预设）/维护页性能监控（system/status 2s 轮询+本地差分速率曲线）**。接数据时已删 `pages/mock.ts`（常量迁移 `lib/route-keys.ts`）。~~ArtPlayer 播放器 UI~~（✅ 2026-09-05 W-3 完成，见 §5.9）、~~confirm 换原型风格弹窗~~（✅ 2026-09-04 W-2 完成，见 §5.9）、~~上传 UI 入口~~（✅ 2026-09-04 W-1 完成，见 §5.9）——三项待办全清。✅ 第十笔（2026-09-04）清掉三项：**设置页扫描/上传卡持久化**（GET/PUT `/api/v1/config`——scan.workers/thumbEdge 为预留字段暂未接入管线、保存后暂不生效；upload 上限/开关实时生效）、**客户端异常上报通道**（POST/GET `/api/v1/client-logs` 环形缓冲 200 条，维护页异常表接真数据，`lib/client-logs.ts` 全局上报器）、**上传路径富化**（上传后自动 EnrichAsset，出处/角色/COS 作者与扫描同口径）。（搜索页类型档已加「动图」2026-09-03；服务端协议新增：AssetSummary.authorNames、POST /authors/import-txt/rebuild、GET/PUT /api/v1/config、POST/GET /api/v1/client-logs、/api/v1/healthz|readyz 归位——见 GUIDE_API/CHANGELOG。）
   - **第五笔（2026-09-03，相册/首页/文件管理三处收尾，见 CHANGELOG「相册作者/角色行语义修正」）**：相册作者行语义改为**旧版「全部」tab 口径**——作者行胶囊 = 常规出处分组 ∪ COS 作者（候选带 kind：source=筛选 source 参数、author=authorId；出处组「其他」桶=无出处常规文件恒排末尾），角色行 all 分区 = 角色 ∪ COS 作品（kind=character/work 分派 character/work 参数）；**分区缺省由「常规」改「全部」**（同流看两集合，隔离浏览切 常规/COS 分区；切分区清作者/角色选择——候选 kind 命名空间随分区变）；作者/角色值行候选前补「全部」胶囊（点它清本行）。实现要点：facets 请求 **partition 恒显式传**（此前缺省分区省略参数→服务端按 all 算，作者行语义错乱的根因）；同值不同 kind 的胶囊激活按 value+kind 判（角色与 COS 作品可能同名）。
   - **首页 cos/排行榜 tab 落地**（原恒渲染推荐流）：tab/周期收敛 URL（`?tab=recommend|cos|hot` + `?period=day|week|month|year`），`lib/home-tabs.ts` 双端共享常量，TopBar 只写 HomePage 只读（刷新/直达不丢态）；cos=GET /assets cosOnly 流（独立入口）；hot=ContentRankGrid + useRankings(period)，周期行缺省日榜。
   - **文件管理「作者 TXT 导入」卡**（`pages/LibraryManagePage.tsx`，旧项目数据管理「TXT导入作者」）：选 .txt 导入（POST，同名覆盖，toast 作者/匹配计数）+ 片段列表 + 逐份移除（DELETE 后从剩余片段重建关联）；hooks 在 `use-authors.ts`（useTxtImportedFiles/useImportAuthorTxt/useDeleteImportedTxt）。
   - **第六笔（2026-09-03，media-ui-prototype 对照旧版逐页修复，见 CHANGELOG「media-ui-prototype 七组缺陷修复」）**：数据页作者榜=常看作者（浏览>0 降序 Top5，0 浏览不显示——旧实现按作品数排且未过滤）、内容榜=常看文件（只含已浏览）、作者总览=作者管理入口卡（每行「X 个文件 · 浏览 Y 次」+ COS 标识）、首页/相册/搜索卡片统一 `cardInner()`（标题下作者单独行，时长角标仅 `m:ss` 视频）、相册按时间分区（dateLabel：今天/昨天/周X/yyyy-MM-dd +「N 项」，筛选后重分组）、搜索关键词真正过滤（SEARCH_STATE.query + matchQuery 六维子串命中，搜 cos 命中 COS 内容）+ 推荐词点击即搜、作者管理页补「导入 TXT」（三格式解析，纯内存态）。⚠️ 这些是**原型层（mock）修复**（已提交 265a1a8）；当时"web 端多数口径天然成立"的判断经审查**不成立**（作者榜排序口径在 web 前端 DataPage/RanksPage，服务端 rankings 是另一套热度口径），实际缺口由第七笔补齐。
   - **第七笔（2026-09-03，第六笔移植 web 正式端，见 CHANGELOG「原型七组缺陷 web 正式端移植」）**：协议 AssetSummary 新增 `authorNames`（GET /assets、GET /recommendations 返回，make sdk 三端重建）；卡片作者行+时长角标（`assetToCard` 收敛全部 MediaCard 调用方：up=formatCardUp 首作者「名 等N」/回退 source、duration 仅视频 m:ss/h:mm:ss，MediaCard meta 两行）；作者榜=常看作者（DataPage Top5 / RanksPage 全量，viewCount>0 按浏览降序，note「按浏览」）；作者总览/管理页行副标题「N 个文件 · 浏览 M 次」（RankRowList 扩展可选 sub）+ `authorDisplayName` 给 COS 作者加「 ·COS」；相册时间分区（`lib/format.ts` dateLabel + AlbumsPage 分组：组内原序、组间组首 modifiedAt 降序、空日期组最后不渲染组头）。原型另修 2 处 CSS 缺陷（TXT 面板 `#authorImportFile/#authorImportText` 选择器对齐、副标题 flex-wrap+flex-basis:100%+order:1 两行布局）。后续（09-04 用户反馈）：数据页作者总览卡改 **Top5 行预览**（按文件数降序，与相邻榜单卡行数一致——真库 122 作者全量渲染致栏目过长；完整列表在作者管理页，总量看卡头注）。
3. 阶段 A 交互与原型的已知差异（用户裁决项）：顶栏 tab 激活态挂 URL（离开首页即丢）；作者页搜索图标 r=8（原型 7，≤1px）；sonner toast 主题跟系统未跟月亮按钮（next-themes 未接）。
4. 跑法：不要单独拉前端——统一访问 `http://127.0.0.1:8420`（后端托管 `web/dist`）；改前端先 `npm --prefix web run build`。
   原型目录（media-ui-prototype/）保留作对照基准，单独预览：`cd media-ui-prototype && node serve.mjs 8099`。
5. 铁律 7：UI 组件禁止直接调 API、禁止内嵌业务规则——接数据走 hooks/客户端逻辑层（阶段 A 已遵守：pages 内零 SDK 调用）。
6. UI 工作另见 `docs/adr/0008`（UI 解耦策略）。
7. web 端类型检查必须用 `npx tsc --noEmit -p tsconfig.app.json`——根 tsconfig 是 solution-style，裸 `tsc --noEmit` 是假通过。
8. **prototype.css 颜色 token 口径（2026-09-04 用户拍板：统一收敛、不影响 UI）**：规则体内禁止散落颜色字面量（hex/rgb/hsl/rgba 及含颜色的 shadow/gradient 整值）——新颜色一律先在 `:root` 定义语义化 token 再以 `var()` 引用；`.dark` 专属值**统一放 `.dark` 块内同名覆盖**（2026-09-05 用户拍板统一写法，原「`-dark` 后缀变量放 `:root`」口径退役——与 `--bg/--elev` 同制，13 个 `-dark` 后缀变量已等值迁移、引用规则改基名，浅/深双态零视觉变化）；平行 token 族（`--accent-*`/`--text-*` 等不带 `--qm-` 前缀）是原型层自有体系，属合法——与 tokens.css 的 shadcn 桥接 `--qm-*` 并存，靠 main.tsx 导入顺序保证原型覆盖。存量 30 处散落字面量已于 2026-09-04 全部等值收敛（视觉零变化，见 CHANGELOG「质量审查清债（Web 端）」条目）；组件层（.tsx/.ts）零硬编码颜色的既有状态继续维持。
9. **SSE 跨端失效键缺陷（✅ 2026-09-05 用户拍板修复）**：缺陷（2026-09-04 W-1 执行中发现）——`components/layout/SseBridge.tsx` 失效键 `['assets']/['libraries']/['tags']/['trash']` 与实际查询键 `['api/v1/…']` 形态不匹配，TanStack 数组前缀匹配一条不命中，其他端（Android/其他标签页）上传/库变更 → web 列表自动刷新整体是断的。修法已落地：新增 `lib/query-keys.ts` 查询键唯一来源（根键 + 子键 `[...根键, '子族名', …]` 构造纪律，逐元素前缀匹配口径入注释），SseBridge 换根键失效（library.changed → 资产/库/目录/标签/作者/出处/回收站根键；upload.done → 资产/库/目录/推荐根键），资产族子键（list/total/detail/facets/timeline-tags）全部重挂根键。隔离实例实测：web 停在首页，curl 模拟另一端上传 → 页面未刷新卡片 3→4 自动出现。
10. **上传队列目标快照（✅ 2026-09-05 用户拍板修复）**：缺陷（2026-09-05 一致性审查发现）——`hooks/use-upload.ts` 的 `uploadOne` 发送时读实时 `optionsRef.current`，批量上传进行中切换目标库/目录会改写排队条目的目标；队列表不显示目标库。修法已落地：入队时刻快照 `targetLibraryId`/`targetDir`（渲染契约字段即发送依据，不双写）+ 队列表新增「目标」列（库名/库内路径，查不到库名回落显示 ID）。隔离实例实测：选库 A 入队 → 「上传中 0%」窗口内切库 B → 文件落库 A、库 B 零资产、目标列保持 `SmokeLibA/库根`。
11. **上传「串行」口径 + 深色 token 写法（✅ 2026-09-05 用户拍板两项均修）**：①原记账「串行仅限单批 drain，跨批次会两路并发」→ 修为全队列**单泵真串行**（`drainingRef` 单路循环，后入队批次只入列等待顺次拾取）；performance 资源计时实测两批上传区间零重叠。②原记账「`.dark` 块覆盖与 `-dark` 后缀两种写法并存」→ 统一为 `.dark` 块内同名覆盖（第 8 条口径），零视觉变化。
12. **W-3 播放器存疑点（2026-09-05 用户拍板：①②修复，③保留）**：①进度条**拖拽** seek 的 zoom ×1.1 偏置 ✅ 已修——捕获阶段拦 `mousedown` 接管官方拖拽臂 + document `mousemove` 视觉坐标 seek（与点击修复共用归一函数；悬停预览不受影响），实测拖到 70% 落点 83.9s/期望 84.0（带偏置应为 92.4）；②倍速菜单文案内建舍入 ✅ 已修（0a2f5d1 首修无效已纠正：`name:'playbackRate'` 实为右键菜单条目名、面板内建名是 `'playback-rate'`，update 未命中落 add 分支产生死行且第三十笔「实测」记录作废；现按 `'playback-rate'` 定位内建条目仅换 selector 档位文案为精确值，实测菜单恰 7 行无死行、点 0.75x 实际生效，见 CHANGELOG 第三十三笔）；③全局 `zoom:1.1` 与第三方坐标型类库系统性冲突——**保留待评估**（后续引入新坐标类库时再议收敛 zoom 口径）。
13. **web 从未上报 play/dwell 事件（✅ 2026-09-05 W-4 补齐闭合）**：原缺陷（2026-09-05 W-3 一致性复审发现）——DOMAIN_RULES §5 口径 = 视频点击播放上报 `play`（同会话只计一次，服务端去重）、详情停留上报 `dwell`（累加语义、一次停留恰好一条）；但 web 全局只发 `open`（AssetDetailPage 进页一次），旧裸 `<video>` 时代同样未接，M2 记录「open/dwell 打点」与实际不符（复核实况：`useReportView` 三种 kind 均支持，但全库只有 `open` 一个调用点，play/dwell 从未接线）。后果：详情页「播放」计数、排行榜热度公式（viewCount+playCount+likeCount）与浏览时长统计从 web 侧恒无贡献。**修法已落地（W-4）**：新增 `hooks/use-dwell-report.ts`（进入计时/离开·切资产·页面隐藏 flush，segmentRef 单点持有取走即置空——同段重复 flush no-op，保证一次停留恰一条；隐藏期间不计停留、回可见开新段；<1s 停留段不上报防零值噪声）+ VideoPlayer 新增 `onPlay` 派发（`art.on('play')`，ArtPlayer 5.4 该事件仅由 `art.play()` 发出、UI 播放键/控制条/空格全走此路径；客户端如实逐次上报，会话去重由服务端 202 幂等吸收）+ AssetDetailPage 接线两者。隔离实例（18420+临时数据目录，未碰真实库）headless 实测：视频起播→停留 13s→SPA 离开、图片停留 7s→离开、二次进入视频页模拟 hidden/visible 分段（3s+6s）——8 条事件全 202，`GET /stats/trends?range=7d` seconds=**29**=13+7+3+6 精确吻合、video-a viewCount=1（两次 open 会话去重）/playCount=1、image-a viewCount=1、`GET /rankings` 恢复供数；截图 %TEMP%/qimeng-w4-shots/。

## 5.9 UI 收尾执行批次（2026-09-04 规划定稿，用户拍板启动）

三项待办的执行级任务书，顺序冻结 **W-1 → W-2 → W-3**（功能缺口优先、最重殿后）；每批交付 = 代码 + 实机截图 + `npx tsc --noEmit -p web/tsconfig.app.json` + `npm --prefix web run build` + `npm --prefix web run lint` 全绿。通用约束：§4.5 对齐纪律（改布局必须静止态实测）、§5.8 颜色 token 口径、铁律 7（UI 组件禁直调 API，走 hooks）、AI_README_FIRST 代码卫生。

### W-1 上传 UI 入口（功能缺口：后端全就绪、页面无入口）

> **✅ 完成（2026-09-04 夜间集群首个写批次）**：文件管理页新增上传卡（选库→目录树选目标→点击/拖入→队列表 名称/大小/进度条/状态→逐条 toast+三组查询本地失效）；新增 `hooks/use-upload.ts`（XHR octet-stream 流式/进度回调/abort/上限前置拦截（读 GET /config upload 项）/类型白名单不前端复制/切路由整队取消）+ `components/manage/DirTree.tsx`（目录树只读/选择双模式共享抽取）。tsc/build/lint 全绿；隔离实例（18420+临时数据目录，未碰真实库）UI 实走 1 图+1 视频上传：进度 26% 中间态→完成 toast→目录树 7→8 文件→首页新卡（截图 %TEMP%/qimeng-w1-shots/）；§4.5 静止态实测横向 spread 0.0px、卡间距 17.59px 与全页节奏一致。记账：SseBridge 跨端失效键存量缺陷（§5 第 9 条）、上传并发=串行（协议未约定取最保守）、DirBrowser 根行改显「库根 N 文件」。

- **现状**：`POST /assets/upload` 四道校验 + 冲突自动重命名 + SSE `upload.done` 桥接 + 设置页上传配置（`use-config.ts`）全就绪；`lib/constants.ts` 的 `UPLOAD_PATH` 已定义未消费。
- **落点（冻结设计）**：文件管理页（`pages/LibraryManagePage.tsx`）新增「上传」卡——选库（页面已有库上下文）→ 目录树选目标目录（复用既有目录树）→ 文件选择/拖拽 → 队列列表（文件名/大小/进度条/状态）→ 完成后 toast + 相关 query invalidate 刷新（SSE 事件已有桥接，不重复造轮子）。
- **实现约束**：XHR + `application/octet-stream` 流式（沿用既有约定）+ libraryId 必填；上限前置提示读 `GET /api/v1/config` upload 项超限拦截（中文提示），类型白名单不在前端复制（服务端四道校验为唯一口径，4xx 透传服务端文案）；新 hook `hooks/use-upload.ts`（进度回调/abort）；组件 ≤300 行。
- **取消语义（冻结）**：切路由自动 abort 整队——不做后台续传（Web 端无先例，用户未拍板；App 端上传才是主通道）。
- **验收**：实机 8420 冒烟——传 1 图 + 1 视频：进度可见 → 完成toast → 目录树/列表出现新文件（截图）；新卡片过 §4.5 对齐自查（首行贴侧栏节奏，附实测数字）。
- **存疑停手**：无。

### W-2 confirm 换原型风格弹窗

> **✅ 完成（2026-09-04 夜间集群第二批）**：`components/ui/confirm-dialog.tsx`（radix AlertDialog 封装，走已有 `radix-ui` 统一包 ^1.6.7 零新装包；API 按冻结实现，另加必要可选 prop `onOpenChange?`——radix 受控 open 模式响应 ESC 所必需，主会话裁决接受）；prototype.css 增 `--overlay-bg`/`--dialog-shadow` 双 token + `.confirm-*` 段（零新增颜色字面量；danger=主文字色反底，浅色黑胶囊/深色自动反转，不引红）。替换点：TrashPage 彻底删除/清空（danger）、LibraryManagePage 删库（**非 danger**——删库只清索引不动磁盘文件，按冻结语义主色键）。window.confirm 全库清零（仅剩 3 条注释）。tsc/build/lint 全绿（改动文件 0 warning）；隔离实例 9 图（三弹窗×浅/深+焦点环+ESC+真确认链路）存 %TEMP%/qimeng-w2-shots/；ESC 实测 7 次全过、焦点环 2px 主色、初始焦点自动落取消键。

- **组件**：`components/ui/confirm-dialog.tsx`——radix `@radix-ui/react-alert-dialog`（shadcn 体系内，M0 技术栈定论覆盖，不算新重依赖；shadcn add 或手写以 web 现有配置为准）；样式全部走原型 token（`var(--accent-*)` 等），**禁止新增颜色字面量**（§5.8）。
- **API（冻结）**：`ConfirmDialog { open, title, description, confirmText, cancelText, danger?, onConfirm }`。
- **替换点**：已知 3 处 `window.confirm`——`TrashPage.tsx:35/48`、`LibraryManagePage.tsx:243`；执行时 `grep -rn "window.confirm" web/src` 兜底全量替换。
- **danger 语义（冻结）**：物理删除/清空类主按钮深色强调，**不引入红色新 token**（原型色板无红），用户不满意再调。
- **验收**：三处操作各一截图（含深色模式）+ 焦点环/ESC 关闭（radix 自带）+ 检查命令全绿。
- **存疑停手**：无。

### W-3 ArtPlayer 播放器 UI

> **✅ 完成（2026-09-05 夜间集群第三批·重批次含对抗审查）**：artplayer@5.4.0 精确锁定（npm 最新稳定，官方文档站+GitHub 源码核对；`highlight` 官方 API 支撑时间轴打点，未触发停手）。新增 `components/media/video-player.tsx`（132 行：倍速 0.5~3x 覆盖公开静态档位表/静音/全屏/续播起点/打点/theme 运行时读 `--qm-primary`+MutationObserver 跟深色）+ `hooks/use-progress.ts`（5s 节流具名常量/暂停 flush/卸载补报/切资产配对重置 + timeline-tags 升序）。AssetDetailPage 裸 video 替换+已看完徽标（`>=` 恰等边界实测）。隔离实例全冻结项实测（续播 20s/倍速菜单/静音/双标记点击 seek 44.97s/暂停 52.55→服务端 52.5467/离开补报/已看完 00:00 重播）+双视口 spread 0.0。执行中发现并修复两坑：全局 `zoom:1.1` 致进度条点击 seek ×1.1（捕获阶段视觉坐标归一修正）、已看完徽标被官方层覆盖（z-index:12+pointer-events:none）。reviewer 全新上下文对抗审查**打回 1 轮**（P1 详情→详情导航卸载补报跨资产进度污染——mutationFn 改显式载荷+{assetId,position} 配对+切资产以旧 id 补报重置，靶向实测 B 资产零污染；P2 注释失实已改实），返工后全量回归通过、三命令绿。存疑点记账 §5 第 12 条。

- **现状**：`pages/AssetDetailPage.tsx` 视频是裸 `<video controls>`（v1 旧壳的 VideoPlayer 已随 v1 删除，现版无倍速/无静音记忆/无标记）；断点续播协议基座已就绪（commit 9773213）。
- **依赖**：npm `artplayer`（版本执行时查最新稳定，锁进 package.json）；**不建 ADR**——UI 可换层（ADR-0008 精神），CHANGELOG 记录选型即可。
- **组件**：`components/media/video-player.tsx` 封装 ArtPlayer（React 封装注意实例销毁/unmount 清理），AssetDetailPage 替换 `<video>`。
- **功能清单（冻结，"现状没有"的都要补齐）**：倍速菜单（0.5~3x）、静音、全屏；断点续播——进页取 detail 的 `lastPositionSeconds`（协议字段）为起点（**已看完判定**：`lastPositionSeconds >= durationMs/1000` 时从 0 重播并显示已看完徽标，客户端推导，协议明文）+ 播放中每 5s 节流 `PUT /assets/{id}/progress`（具名常量，严于协议建议值 10s、协议允许；暂停/离开立即上报，新 hook `hooks/use-progress.ts`）；时间轴标签打点 + 点击 seek 跳转回看（端点以 GUIDE_API 为准，无标签不渲染该层）；dwell/play 打点沿用 `useReportView`；编码兼容提示保留（`codec-warn` 逻辑不动）。
- **ArtPlayer 定制能力**（自定义进度条打点/控制栏）执行时读官方文档确认；**官方 API 若无法支撑时间轴标记 → 停手报用户**（候选方案：进度条上自绘绝对定位覆盖层）。
- **验收**：实机播放各功能截图（倍速/静音/续播起点/标记跳转）+ 组件 ≤300 行 + 桌面/窄屏两档视口自检 + 检查命令全绿。
- **存疑停手**：见上。

### 发现项（不在本三批范围，待用户拍板，执行 AI 不擅自扩）

2026-09-04 规划审查发现现版详情页为极简重建版，以下旧壳（M2 v1）曾有、原型版未建的体验**不在三项待办内**，是否补齐待用户拍板：图片查看器（双指缩放/双击还原/左右预加载/沉浸）；详情互动行（点赞/收藏/标签管理弹窗）；批次导航（上一件/下一件）。候选归宿：并入 W-3 一次做完整详情页，或 M4 后回补 Web。**【2026-09-05 用户拍板：M4 后回补 Web，不阻塞 M4 启动；回补批次待排】**——**同日详情页 B站式排版大改已回补其中两项：详情互动行（点赞/收藏/标签管理弹窗）✅ + 推荐式浏览（「接下来播放」右栏替代批次导航）✅；仍后置：图片查看器、列表上下文批次导航（见上方「详情页 B站式双栏排版大改」节第 8 条）**

### 六任务批（2026-09-05 用户 /subagent 并行派发，主代理调度 + reviewer 对抗审查）

用户六项 UI 反馈一批清偿（E1 协议链另见 CHANGELOG 第四十三笔）：
1. **全局刷新按钮生效化**（E2）：AppShell refresh 在 invalidateQueries 之外广播 `qm:refresh`（常量 QM_REFRESH_EVENT=lib/constants.ts 单一来源，对抗审查返工项）；HomePage 监听执行旧版 refreshSeed++ 重排（推荐/cos seed=Date.now() 回第一页、排行榜重置分页）——普通页 invalidate 重拉 + 首页显式重排，两通道分工注释在 AppShell。
2. **COS 作者反查修复**（E3）：CollectionPage 原按 displayName===name 精确匹配，COS 作者行名带「·COS」后缀必落空态——改两段式（精确→剥后缀，COS_DISPLAY_SUFFIX 模块常量与 format.ts authorDisplayName 字节级一致），数据页/榜单/相册页全部 /app/collection/author/{name} 入口一并修复。
3. **作者页筛选胶囊**（E3，对齐旧版 GUIDE_UI 作者文件页）：常规作者=角色/类型、COS 作者=作品/类型，胶囊带计数（GET /assets/facets，排自身口径，authorId 恒传），点已选切换/不收起、「收起 ▲」折叠；**每维单选**（协议 work/character/mediaType 单值参数，旧版同维多选集合不可表达——记账为协议约束，需多选先扩协议）；作者切换经渲染期重置（React 官方模式，非 useEffect——清 set-state-in-effect 告警）。局部 hook use-collection-facets.ts 系并行冲突规避产物，与 useAssetFacets 近重复，后续可合并（reviewer 建议项 3，不阻塞）。
4. **删「浏览 N 次」**（E3）：DataPage 作者总览行与 AuthorsPage 行副标题改「N 个文件」（旧版规格无次数元素，R2 核实）；AuthorsPage 行加点击进作者文件页（关注按钮 stopPropagation 防误跳）。
5. **TXT 导入卡醒目化**（E4）：LibraryManagePage 导入入口复用 UploadCard 的 .upload-drop 虚线焦点区语言（图标+状态感知提示），说明改紧凑小列表，功能逻辑零改动。**同日用户实机拍板去掉下方重复的 save-btn 按钮，导入区成唯一入口**（补 tabIndex+Enter/Space 键盘可达性，f039b98）。

### 详情页 B站式双栏排版大改（2026-09-05，用户指定 B站详情页截图为排版参照，只学排版不学视觉）

> ✅ 完成（第四十六笔；主代理兜底实现——executor 子代理模型并发限流 4 次不可用，researcher/reviewer 正常；reviewer 全新上下文对抗审查 1 轮打回后通过）

1. **布局**：详情页从「舞台+log-table」极简版重写为双栏：左主列=媒体舞台→标题（cosWork ?? fileName 与卡片同口径）→meta 行（浏览·播放·大小·尺寸·日期·出处，间隔点）→点赞/收藏互动行→标签行；右栏=作者卡（displayName+·COS+关注，作者名点击进作者文件页，无作者不渲染）+「接下来播放」行式推荐栏（缩略图+时长角标+两行标题+作者副行，排除当前资产，换一批=换 seed，同类型 recommendations 流——协议无相似推荐参数，用户拍板口径）。窄窗 ≤1000px 单栏降级。新组件 `components/detail/{AuthorCard,AssetTagRow,UpNextList}.tsx`，新样式段在 prototype.css 末尾（.detail-* 前缀 + #page-asset 作用域）。
2. **新 hooks**（use-assets.ts）：useToggleLike（PUT like 无 body，响应 LikeState 即时回填详情缓存 patchDetail+根键失效）/useSetFavorite（显式值非 toggle）/useReplaceAssetTags（整体替换+TAGS 池失效）/useUpNextList（recommendations 单页，seed 入缓存键，'upnext' 子族不与首页数字键碰撞）。
3. **标签管理弹窗**（radix Dialog 统一包）：标签池点选+新建（新建自动入勾选），保存一次整体替换提交（DOMAIN_RULES §7）；弹窗按 open 条件挂载（useState 惰性初始化即复位，规避 set-state-in-effect）。
4. **动效纯 CSS 零新依赖**：hover 提亮/active 0.94 缩放、点赞弹跳 keyframes（onAnimationEnd 复位重触发）、服务态主色实底+图标 fill、换一批图标旋转、radix 弹窗自带。
5. **打点保真 + reviewer P1 修复**：open/play/dwell/进度上报原样搬移；P1=UpNextList 详情→详情导航同路由不重挂载，open 守卫 useRef(false) 永不重置→新资产 open 漏报——修为 `reportedFor.current` 记已上报资产 id，服务端实证（直进与右栏跳转两资产 viewCount 均=1）。
6. **连带修复 671db67 的 `.layout button` 重置存量回归**：重置 (0,1,1) 压过全部单类按钮的 border/background（实测 .follow-btn--idle 白底透明不可见）——pill/seg/more-filter/follow-btn(--idle)/save-btn/confirm-btn--cancel/--primary/--danger + detail-act 共 14 处选择器加 button 前缀提级（select-trigger 先例）；span 消费的 .pill 用选择器列表双写。同日 reviewer P3×2 已修：管理按钮手型提为 `button.pill.detail-tag-manage`、`.dark .upnext-thumb` 深色占位照 .dark .card--cover 模式补齐。
7. **验收**：tsc/build/lint 全绿（新文件 0 告警）；隔离实例 curl 9 项（like toggle 计数翻转/favorite 双向/tags 替换/follow/recommendations）+ 浏览器全链路（浅/深/窄窗、交互回填、标签全流程、换一批、打点存活、对齐 spread=0.0/舞台顶 83.1）。
8. **遗留**：图片查看器（缩放/沉浸）与批次导航仍按用户拍板后置；测试注意——PWA Service Worker 会缓存旧构建，改前端重 build 后浏览器要清 SW/缓存再验（本次实测踩坑：computed 样式陈旧+fullPage 截图错乱均源于此）。
9. **编码兼容提示条已移除（2026-09-05 第五十七笔，用户拍板）**：用户浏览器可直放 hevc，详情页顶部 .codec-warn 黄条是常驻噪声——元素级移除（含 INCOMPATIBLE_CODECS 常量、.codec-warn 样式段、--codec-warn-* token 全清）；播放失败兜底交回 ArtPlayer 错误态。


## 6. 工作树现状（2026-09-02 三轮：原型移植 web 重建，接手必读）

- **本轮改动已全部提交**（阶段 A 移植 + reviewer 修复 + 文档，commit 见 CHANGELOG「UI 原型移植 web 端 React 重建」条目）；上一轮 UI 原型二轮迭代与 M4 播放端基座也已在 cea4e9e / 9773213 提交。
- **web 端新结构速查**：`styles/prototype.css`（原型 CSS 原样+追加段，须后于 index.css 导入）→ `components/shell/{AppShell,Sidebar,TopBar,icons}.tsx`（壳层）→ `components/media/MediaCard.tsx`（卡片共用）→ `pages/`（九页+mock.ts）→ `router.tsx`（/app 下懒加载）。主题在 `lib/theme.ts` + `web/index.html` 内联脚本（双写互指，key `qimeng_theme`）。
- **库启用开关（2026-09-03）**：文件管理页每个库行有「启用」开关（`useSetLibraryEnabled`）——停用仅隐藏浏览面（首页/相册/搜索/推荐），记录/事件流/统计/磁盘全保留；管理面不过滤。体系背景见 ADR-0012（新 kind 接入清单，用户以后加全新文件夹方式的库按它执行）。
- **维护页实际功能（2026-09-03，阶段 B 首批真数据页）**：文件管理卡 → `/app/maintenance/files`（`pages/LibraryManagePage.tsx`：库 CRUD + 注册后自动补扫 + 目录树，删库端点 `DELETE /libraries/{libraryId}` 本次新上协议）；回收站卡 → `/app/maintenance/trash`（`pages/TrashPage.tsx`：恢复/彻底删除/清空，confirm 二次确认待换原型风格弹窗）。hooks 在 `hooks/use-libraries.ts`、`use-trash.ts`。关注列表只显示已关注（用户拍板）。回收站 confirm/入口卡实测记录见 CHANGELOG 同日条目。
- **集合子页（2026-09-03 用户需求，原型没有的新页）**：数据页/完整榜单页点标签/作者行 → `/app/collection/:kind/:name`（`pages/CollectionPage.tsx`，相册同款网格列出该标签/作者的所有文件——旧项目语义）。标签/作者榜单 mock 已改为 mock 池真实聚合（`TAG_AGG`/`AUTHOR_AGG`，保证点击必有内容）；内容榜 rank-item 点击未接（单文件详情页属阶段 B）。
- 对齐纪律（§4.5）在 web 端同样适用；web 端实测注意：IAB/后台标签页 `document.hidden` 时 page-in 动画时钟冻结在 from 帧（translateY 4px 污染实测）——测量前禁动画（注入 `.page{animation:none!important}`）即静止态等价。
- 历史：v1/v2 旧 UI 删除（0831184）、原型初版（978976c）、二轮榜单+作者管理（cea4e9e）、M4 播放端基座（9773213）。

## 7. 给下一位 AI 的起点建议

1. **先读 §4（原型现状）、§4.5（对齐纪律——硬规则，尤其第 5 条动画污染）、踩坑记录**，再动代码；对齐类改动交付必须附浏览器静止态实测数字（边缘 spread / 中心偏差 ≤1px）。
2. 浏览器打开 `http://127.0.0.1:8099/`（服务若未起：`cd media-ui-prototype && node serve.mjs 8099`）逐页过一遍：侧栏切页 + 月亮切深色 + 数据页排行三卡（内容榜封面卡/作者总览「管理」进作者管理页）+ 搜索回车进结果页 + 返回按钮 + 悬浮刷新。
3. 用户会继续逐项提修改；改完按 §4.5 自查（记得静止态实测），不用等用户提醒对齐。
