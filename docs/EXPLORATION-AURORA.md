# EXPLORATION-AURORA —— 全面玻璃化重做探索笔记（ui/app-aurora-explore 分支专属）

> 分支：`ui/app-aurora-explore`（基于主线悬浮玻璃坞批 1fcd489）。实验性质，**可能不合入主线**。
> 日期：2026-10-03。所有实验的开关单源：`android/core/ui/.../glass/ExploreConfig.kt`，
> 逐项翻 false 即精确回到主线观感（每个消费点都有回退分支）。本文件是该分支唯一新增文档。

## 探索 1：全面极光画布

**动机**：主线 AuroraBackdrop 只在玻璃材质档渲染（`usesBackdrop` 门控），SOLID/CLASSIC 档
是纯色底——玻璃语言退化成「半透明面板浮在灰底上」，极光的「活」没了。把极光提升为全局
常驻背景，配合内容面半透明化，让辉光从内容缝隙透出（对标 Web 端 Aurora Glass 的画布层思路）。

**做法**：
- 壳层 `QimengNavHost`：极光渲染条件 `usesBackdrop` → `usesBackdrop || GLOBAL_AURORA_CANVAS`。
  SOLID 纯色坞/CLASSIC M3 底栏仍是不透明体，压在极光之上可辨性不降；`qimengBackdropSource`
  捕获层挂载条件**未动**（SOLID/CLASSIC 零采样开销的口径不变——极光只是普通背景，坞不需要采样它）。
- 内容面半透明化切口（`GLASS_TOP_ROW`）：首页搜索胶囊 + 两枚顶栏图标钮 Surface 实色 →
  GlassSurface 玻璃面。页面**根背景本就透明**（HomeScreen/AllScreen 根是裸 Column，
  无自绘背景），真正挡光的是页面内的实色卡面——首页顶行是最小切口。
  相册页 QimengTitleRow 的筛选钮是多页共用件（含作者页），本探索**不动**（见「候选未做」）。

**overdraw 记档**：极光 = 1 屏基底矩形 + 4 团径向渐变（单 InfiniteTransition 只触发 draw
无效化，不重组）；扩展到 SOLID/CLASSIC 增加的即这一层，真玻璃档本就支付同量级成本。
内容透明化不增加层数（实色面 → 半透明面层数不变，只是混合而非遮盖）。中端机可承受；
低端机若实测掉帧，回退开关翻 false 即可。

**观感自评**：暗色档预期收益最大——夜基底 + 四团辉光从搜索胶囊和图标钮周围透出，
首页不再「死灰」；浅色档辉光有 0.55 衰减系数，变化克制。SOLID/CLASSIC 档铺极光后
「材质档选择」的意义被稀释（纯色坞也活在极光上），主线化时要想清楚这是特性还是混乱源。

**风险**：CLASSIC 档的设计意图是「逐字回到改前观感」的保底回退档，铺极光后它不再逐字——
若把本探索合主线，建议 CLASSIC 档保持纯色底（开关按材质档取值而非全局常量），保底语义才完整。

## 探索 2：玻璃卡片上内容（GlassCard/GlassSurface 首批消费方）

**动机**：主线 GlassCard/GlassSurface 落地后零消费方（除了坞的降级档），玻璃卡在列表
滚动下的观感与性能没人验证过。本分支让它有第一批真实消费方。

**做法**：
- **统计页 5 类卡全换**（`GLASS_STAT_CARDS`）：MetricCell（无点击）/趋势卡/分布入口卡/
  内容榜卡/常看作者标签卡（Surface onClick）→ GlassCard。卡几何（20dp 圆角/内边距/字号）
  逐项保留，只换面材质。交互差异：Surface(onClick) 的 ripple 换成 GlassCard 的
  pressScale spring 按压缩放（无 ripple）——玻璃语言下「按压即缩放」比 ripple 更贴。
- **媒体网格卡玻璃化**（`GLASS_GRID_CARDS`，QimengMediaGrid AssetCard）：缩略图外圈垫
  GlassSurface 玻璃衬底，缩略图四周留 4dp 玻璃沿（`EXPLORE_GLASS_CARD_INSET`），
  旧版极简卡规格（外 padding 5dp/圆角 24px 换算/角标/无按压缩放冻结项）逐项保留。
  内容 lambda（cardBody）两档容器共用引用，杜绝双份维护。

**性能写法要点**（主线化时照抄）：GlassSurface = drawBehind 三笔（体/高光/描边），
零 blur 零 elevation（网格小面板禁投影，GlassSurface KDoc 既有口径）；可视卡 6~24 张 ×
3 笔纯 draw，滚动时只重绘不重组；半透明体=每卡区域多一层混合。LazyGrid 复用不受影响
（contentType 分型未动）。

**观感自评**：统计页玻璃卡预期「一眼可见的档次提升」，深浅两主题玻璃面自带受光边；
网格卡玻璃沿偏细（4dp），2 列大图下存在感弱、5 列小图下可能显脏（沿+缝叠加密度高），
实机建议先看相册页 5 列档。

**风险**：网格卡是全 App 共用件（首页三流/相册/收藏/历史/搜索/作者集合全部消费），
玻璃档一旦上线即全页面变脸——主线化必须按页面粒度灰度而非全局开关；另外
AssetCard KDoc 冻结过「旧版极简卡」视觉（任务L L1 拍板），合主线需重新走拍板。

## 探索 3：坞体形态实验（FloatingTabDock 参数化，不覆盖主线实现）

三个变体全部做成 FloatingTabDock 的新增可选参数（默认 false=主线逐帧一致），
壳层按 ExploreConfig 传入：

### 3a 胶囊液态拉伸（`DOCK_LIQUID_PILL_STRETCH`）
- **做法**：任务原案「velocity→scaleX 1.0~1.15」，落点改为**距目标距离比例**映射：
  `t = |targetValue − 当前值| / 槽宽`，scaleX = 1 + 0.15t，scaleY = 1 − 0.06t（体积守恒近似）。
  出发 t=1 最宽、飞行收窄、落位 t=0 回原形；spring 过冲时 t 复升=回弹再甩一次（液态尾）。
- **为什么不用真速度**：Animatable 无逐帧速度回调，真速度须在 draw 层外持久化状态
  （引入重组/额外失效）；距离比例同样纯读 Animatable 状态、只在 draw 阶段算、零额外重组，
  且视觉上与速度映射等价（速度峰=距离峰，都在行程中段）。
- **参数**：拉伸上限 0.15（任务区间顶格；再大出「果冻」失真）、压缩 0.06。
- **自评**：与单胶囊 spring 位移天然联动，预期「果冻在坞里滑」的观感成立；风险是
  scaleX 拉伸时胶囊两端越过相邻槽内边距（视觉侵入邻槽呼吸位），1.15 档侵入 0.075×胶囊宽
  ≈2~3dp，可感知但不脏。

### 3b 标签渐隐式（`DOCK_LABEL_FADE` + `DOCK_COMPACT_HEIGHT`）
- **做法**：未选中项标签 alpha→0（0.9 缩放），选中项 140ms 淡入+展开到 1.0；
  标签布局空间**恒保留**（alpha 动画不动布局），坞内几何零跳位；配合坞体降档
  62dp→54dp（`COMPACT_DOCK_HEIGHT`，壳层按开关组合计算后经 dockHeight 参数传入）。
- **自评**：两档高度对比的结论要实机看——54dp 档内容净高 24 图标+2 间距+16 标签=42dp，
  上下各余 6dp，标签淡入时稍挤但成立；预期「图标坞」的轻盈感明显，代价是标签可读性
  （未选中项语义只剩图标，好在四 Tab 图标辨识度高 + a11y 语义未动）。
- **风险**：`bottomClearance` 让位口径不受影响（max(坞档, CLASSIC 80dp) 恒 80dp），
  但若未来 CLASSIC 保底高度下调，紧凑档 64dp 净让位就参与 max 竞争——记档防坑。

### 3c 选中图标点缀（`DOCK_ICON_ACCENT`）
- **做法**：图标上方 4dp 小圆点（选中 150ms 淡入，offset 出盒不占布局）+ 图标着色
  onSurfaceVariant↔primary 150ms 渐变过渡（主线=瞬变）。点用 graphicsLayer 读动画态，
  纯 draw 阶段；54dp 紧凑坞内点上缘距坞顶 1dp，不触 pill 圆角（点在槽位水平中点，
  远离坞两端圆角区）。
- **自评**：着色渐变是三个变体里「性价比之王」——150ms 的过渡把选中切换的生硬瞬变
  抹平，成本接近零；小圆点在渐隐标签模式下承担「第几个选中」的空间冗余提示，配合 3b
  是成套语言。单独开 3c 不开 3b 时，点上缘距坞顶 4dp 更从容。

## 探索 4：微交互打磨

- **触觉反馈**（`DOCK_HAPTICS`）：tab 点击发 `HapticFeedbackType.TextHandleMove` 轻档
  （任务指定档位）。grep 全项目无既有 haptics 惯例（0 命中），故直接用系统标准
  LocalHapticFeedback，无先例可循——主线化前建议真机确认轻档是否太轻（备选 LongPress 档）。
- **图标交叉旋入**：选中点亮瞬间图标带 6° 旋入（rotateZ 与 iconScale 同读一根 spring，
  scale→1 时 rotate→0 归零，零第二根动画成本）。合并进 DockNavItem 图标 graphicsLayer。
- **高光扫过（可选项）**：**评估后未做**。PressScale 是全 App 微交互单源，扫光效果要求
  在按压缩放层上再叠 draw 层扫掠渐变，会改变所有消费方（GlassCard/GlassIconButton/
  QimengSegPill 族）的按压观感——单源组件的实验半径过大；且玻璃面的受光边+高光纱已经
  是静态「受光」语言，动态扫光容易腻。若要做，建议只对 GlassCard 做独立 wrapper 而非动
  PressScale 本体，留待下一轮探索。

## 探索 5：内容转场实验（高风险，默认关）

- **开关**：`TAB_ENTER_FADE_TRANSITION = false`（ExploreConfig 内最长注释，红线依据写明）。
- **做法**：常驻层可见性翻转时，**只给进入屏**加 90ms（≈5 帧@60fps）alpha 0.35→1 +
  scale 0.98→1（LinearOutSlowInEasing）。退出屏仍瞬时 alpha=0（原机制逐字不动）——
  两页永不全不透明同屏，任务U9 的叠影根因不复活；半透明帧露出的底是极光画布（探索 1
  已全局常驻）而非旧页内容。首次驻留（冷启动/进程恢复首屏）不动画（对齐坞胶囊首帧
  snapTo 的零入场口径）。开关关时进入屏不挂任何 graphicsLayer（连层都不建），逐帧=主线。
- **风险**：弱机上 90ms 内新屏组合未完成会「先透明后内容弹出」；本实验与主线红线
  （任务U9/L2「瞬切防叠影」）方向相反，**未经用户实机拍板不得翻 true 合入主线**。

## 候选未做（记档防丢）

- 相册页 QimengTitleRow 筛选钮玻璃化：多页共用件（相册/作者页），改动半径超本轮探索，
  留作下一轮（做法同首页顶行，开关 + 分支）。
- 顶栏 QimengTopBar 容器色半透明化：覆盖页有幕帘垫底（不透明 background），改它要先动
  幕帘逻辑，牵扯 pushed 页可读性，不值当。
- PressScale 玻璃高光扫过：见探索 4，未做的理由已记档。
- GlassCard 到 QimengRankCard/作者卡等其他卡面的推广：等探索 2 实机结论先行。

## 主线化建议（初判，待实机验证后修订）

| 实验项 | 初判 | 理由 |
|---|---|---|
| 1 全局极光画布 | **建议主线化，但 CLASSIC 档例外** | 视觉收益最大；CLASSIC 保底语义需保持纯色底 |
| 1 首页顶行玻璃化 | 建议主线化 | 最小切口、零几何变化、回退干净 |
| 2 统计页玻璃卡 | 建议主线化 | GlassCard 首批消费方验证了滚动性能写法 |
| 2 网格卡玻璃衬底 | **缓行，需重新拍板** | 全页面共用 + 与任务L L1「旧版极简卡」拍板冲突 |
| 3a 胶囊液态拉伸 | 建议主线化 | 纯 draw 层零重组，与 spring 天然联动 |
| 3b 标签渐隐+紧凑坞 | 待实机对比两档高度 | 可读性代价需真机确认 |
| 3c 点缀点+着色渐变 | **强烈建议主线化** | 成本近零、观感收益明确的性价比之王 |
| 4 触觉反馈 | 建议主线化（真机定档位） | 项目首个 haptics，先立惯例 |
| 4 图标旋入 | 可选 | 6° 很克制，留不留看用户 |
| 5 内容转场 | **默认丢弃，除非用户实机拍板** | 与主线红线方向相反 |
