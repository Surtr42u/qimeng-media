# HANDOVER-UI - 桌面客户端风格媒体库 UI 交接说明

> 写给下一位专做 UI 的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 最后更新：2026-09-03（相册页四维胶囊接真数据：分区 all/常规/COS + 作者 + 角色·作品 + 类型，GET /assets/facets 排自身口径——§5 已更新；变更细节见 CHANGELOG「相册四维聚合」条目。此前 2026-09-02：原型移植进 web 端 React 重建（阶段 A mock））
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
3. **纵向对齐**：按上方踩坑记录第 3 条的导航纵向对齐规则执行；改行高/边距后连锁重算。搜索页同规则（2026-09-02 补）：类型行按钮中心 ↔ 侧栏「首页」项中心（`.stype-row` margin-top -14.9px）、排序行胶囊中心 ↔ 侧栏「相册」项中心（`.s-toolbar` padding-top 2px）。**全部页面首行同规则**（2026-09-02 用户拍板"首行贴侧栏节奏"，数值均为静止态实测）：细行中心 ↔ 首页项中心——相册维度行（`.filter-card` margin-top -5.9px）、数据时段行（`#page-data>.seg-row` -5.8px）、维护页标题行（`.page-head` -13.8px）；高卡片顶沿贴顶栏下缘(82.5)——我的资料卡/设置表单卡（margin-top -11.6px，实测 82.9）、首页网格（`#page-home .grid` -11.5px，实测 83.1）。改任一页行高都要连锁重算并实测。
3b. **大标题（page-head h2）的视觉对齐基准 = 侧栏首页图标块顶部，而非 nav-item 整体中心**（2026-09-02 用户拍板）：18px 大标题与 26px 图标块都是竖向块，顶对齐才视觉齐平；按"整体中心对齐"数字达标但用户仍判不对齐（本条教训来自作者管理页）。三页 page-head margin-top -13.8px，实测 h2 顶 80.5 vs 图标顶 80.8。榜单子页/作者管理页/维护页适用。
4. **改完即测**：交付信息里必须附实测数字（边缘 spread、中心偏差），实测不达标不算完成。此前每处对齐问题都是用户先发现的——从本条起 AI 先测后交。
5. **实测必须在 page-in 动画结束后取值（2026-09-02 重大教训）**：`.page:not([hidden]) { animation: page-in .18s ease-out }` 的 `translateY(4px)` 会污染切页瞬间的 getBoundingClientRect——showPage 后立即实测读到的全是动画中间帧（普遍假性偏高 ~4px）。本轮用动画污染值校准过的一批负 margin 全部偏差 4~5px，用户肉眼发现后按静止态（await 300ms）重测修正。**此后一切对齐实测必须 `await sleep(300)` 后再取 rect**，本节旧实测数字凡未注明"静止态"的均不可信。静止态终值（第二首轮校正）：三页标题行 -13.8（视觉基准=图标块顶，见 3b）、数据 seg-row -5.8、相册 filter-card -5.9、搜索 stype-row -14.9、我的/设置卡 -11.6、首页网格 -11.5。

## 5. 待办与约定

1. ~~原型各页达成视觉验收后 → 把功能移植进 web 端重建页面~~ **已完成（2026-09-02 阶段 A）**：web/src/pages/ 九页 + components/shell/ 壳层 + styles/prototype.css；记账债两笔已还（renderCard/mediaCardHtml 合并为 `components/media/MediaCard.tsx`；`.rank-cards` 窄屏单列回退已补进 prototype.css 追加段）。
2. **接真实数据（阶段 B，2026-09-03 全部完成）**：✅ 已接——首页推荐流/相册列表（2026-09-03 升级四维胶囊：分区 all/常规/COS + 作者 + 角色·作品 + 类型，GET /assets/facets 排自身口径，见 CHANGELOG「相册四维聚合」条目）/详情页（`hooks/use-assets.ts`、`pages/AssetDetailPage.tsx`、`lib/format.ts`）+ 维护页文件管理（库 CRUD/目录树）/回收站（`hooks/use-libraries.ts`、`use-trash.ts`）+ **搜索页（FTS5 多维筛选全真，q 已传后端）/我的页（资料卡/关注/收藏/浏览历史三 Tab 全真）/数据页（6 指标卡真库数据/趋势/双环/内容 Top5/标签榜/作者榜/作者总览）/完整榜单页/集合子页（tag/author 真网格，注意 COS 作者集合要 includeCos）/作者管理页（140 位真作者+关注 toggle）/顶栏搜索建议（localStorage 历史+tags/authors 推荐词）/设置页推荐偏好卡（9 维滑杆+4 预设）/维护页性能监控（system/status 2s 轮询+本地差分速率曲线）**。接数据时已删 `pages/mock.ts`（常量迁移 `lib/route-keys.ts`）。⏳ 待做——ArtPlayer 播放器 UI（现用原生 video）、confirm 换原型风格弹窗（2026-09-03 用户拍板暂停，仅记账）、设置页扫描/上传/界面卡持久化（需配置端点）、客户端异常上报通道（维护页异常表空态）、上传路径富化（服务端既有缺口，用户拍板暂停）。（搜索页类型档已加「动图」2026-09-03；本批服务端新增协议：/assets liked+favoriteAt、GET /assets/facets、GET /history、trends 7d/90d、rankings quarter、AssetSummary viewCount/playCount、Author.viewCount——见 GUIDE_API/CHANGELOG。）
3. 阶段 A 交互与原型的已知差异（用户裁决项）：顶栏 tab 激活态挂 URL（离开首页即丢）；作者页搜索图标 r=8（原型 7，≤1px）；sonner toast 主题跟系统未跟月亮按钮（next-themes 未接）。
4. 跑法：不要单独拉前端——统一访问 `http://127.0.0.1:8420`（后端托管 `web/dist`）；改前端先 `npm --prefix web run build`。
   原型目录（media-ui-prototype/）保留作对照基准，单独预览：`cd media-ui-prototype && node serve.mjs 8099`。
5. 铁律 7：UI 组件禁止直接调 API、禁止内嵌业务规则——接数据走 hooks/客户端逻辑层（阶段 A 已遵守：pages 内零 SDK 调用）。
6. UI 工作另见 `docs/adr/0008`（UI 解耦策略）。
7. web 端类型检查必须用 `npx tsc --noEmit -p tsconfig.app.json`——根 tsconfig 是 solution-style，裸 `tsc --noEmit` 是假通过。

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
