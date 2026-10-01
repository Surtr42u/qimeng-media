# ADR-0031：Web 全新设计语言「绮梦流光 · Aurora Glass」——暗色优先液态玻璃 + token 体系 v2

## 背景（Context）

M2 落地的 Web 视觉层是桌面客户端原型移植（`styles/prototype.css`，v1）：亮色优先、白底实心卡片、
色彩单源挂在 `@immich/ui` 主题包上（`--qm-*` ← `--immich-ui-*` 桥接）。项目定位是「媒体全存 NAS」
的大屏消费与管理端，需要：

1. 暗色优先的媒体消费氛围（媒体库场景深色是主形态）；
2. 现代设计语言（liquid glass / glassmorphism 一派：半透明材质 + 毛玻璃 + 微动效），
   视觉上与旧原型形成代际差；
3. token 体系仍是唯一合法颜色来源（ADR-0008 / web/README 铁律不松动）；
4. **功能守恒**：20 个路由页、全部数据流（TanStack Query hooks）、类名契约与组件 DOM 不动——
   重设计只发生在视觉层与少量展示性文案。

备选方案：a) 沿用 v1 换色微调（不满足「设计语言级」要求，否）；b) 引入组件库/动效库重写组件层
（违反「不引重型依赖」，组件层重写风险大，否）；c) **保留类名契约、整体重写样式层 + token 层**（采纳）。

## 决策（Decision）

**1. 设计语言**：「绮梦流光 · Aurora Glass」——三层结构：

- **画布层**：深靛底色 + 三团低饱和极光 radial-gradient（`body::before` fixed 装饰层，不随滚动），
  给玻璃提供可模糊的色彩基底；
- **玻璃层**：面板 = 半透明表面色 + `backdrop-filter: blur(22px) saturate(1.5)` + 发丝描边
  + 顶缘内高光（`--qm-glass-highlight`，mask-composite 实现的 1px 渐变描边）+ 轻落影；
  浮层（弹窗/下拉/查看器）用更高遮盖力的 strong 档；
- **内容层**：文字四级灰阶、极光紫主色（承旧版 #4250af 色相带，暗色下提亮）、图表五色
  （主紫 + 青/品红/琥珀/警示红）。

明暗双主题：**暗色为设计主角（暗色优先）**；浅色是完整度相同的「晨雾玻璃」变体。
主题默认值从「无手动选择时跟随系统」改为「**无手动选择时默认深色**」（`lib/theme.ts` +
`index.html` 首帧内联脚本两处同语义双写；月亮按钮切换并持久化行为不变）。

**2. token 体系 v2**（`web/src/tokens.css` 重写，仍是组件颜色唯一合法来源）：

- `--qm-*` 语义层：画布（`--qm-bg`/`--qm-aurora`）、文字四级、主色三件套（primary/strong/soft + on-accent）、
  玻璃材质组（surface/strong/soft/chip/hover/hover-strong、border/strong、glass-highlight、glass-blur/saturate）、
  阴影四级 + lift、功能色、图表五色、遮罩；
- 布局/圆角/间距/动效/层级 token：`--qm-sidebar-w: 72px`、`--qm-header-h: 64px`、圆角四档
  （sm 10px / md 14px / lg 18px / xl 24px / full）、间距 4px 基数六档不变、动效时长三档
  （140/240/420ms）+ 缓动三档（out/in/**新增 spring** back-out）、z 阶梯七档
  （overlay 10 → modal-panel 51，数值关系沿用 v1 约定）；
- **shadcn 桥接反向**：v1 是 `--qm-* ← --immich-ui-*`，v2 改为 shadcn 变量（`--background` 等）`← --qm-*`
  （index.css 桥接层）——`--qm-*` 成为全站唯一色彩事实源；组件代码仍只准消费 `--qm-*`；
- **旧名兼容别名块**：存量 TSX 内联样式/图表组件引用的 v1 变量名（`--text-main`/`--hover-bg`/`--pop-chip-hover-bg`/
  `--trend-line-sub` 等）全部指向 `--qm-*` 单源，注释标明「新代码禁用」，为后续 TSX 清理留通道；
- `@immich/ui` 主题包退役：CSS import 与 package.json 依赖一并移除（无 TS 引用）。

**3. 样式层整体替换**：`styles/prototype.css` 删除，新 `styles/glass.css` 按相同类名契约全量重写
（约 400 个选择器全覆盖，13 个分节）。关键取舍：

- **类名契约与组件 DOM 不动**（MediaCard/Sidebar/TopBar/各页 JSX 零结构改动）——逻辑层零风险；
- v1 的「首行贴侧栏节奏」负 margin 锚点体系（-11.5px/-13.8px/-17.5px 等像素锚）退役，
  改为 `.content` 统一节奏——旧锚位依赖 75px 顶栏 + zoom 1.1 的像素推算，跨 DPI/缩放脆弱；
- 保留 v1 的**全局 zoom 1.1** 及其全部配套补偿（radix popper wrapper 0.9091、图片查看器 `--zoom-inverse`、
  `:fullscreen zoom:1`、ArtPlayer `.art-settings` 钉位）——这些都是实证修复，与视觉语言无关；
- 动效只增不改纲：卡片进场 stagger、radix data-state 进出场、详情叠加层入场、点赞回弹沿用既有机制，
  时长/缓动全部走 token；新增**明暗切换 View Transition**（`document.startViewTransition` 全屏交叉溶解，
  不支持的浏览器同步直切，渐进增强零成本）；`prefers-reduced-motion` 全局归零层沿用。

**4. backdrop-filter 降级策略**（写死在 glass.css §13）：`@supports not (backdrop-filter...)` 时
只替换 surface 组变量为**同色不透明等价值** + 模糊参数归零 + 叠加层/查看器/遮罩转实底——
布局、层级、圆角零变化，只失去透光质感。触发面：Firefox <103、部分 Android WebView、
禁用硬件加速的环境。Safari 走 `-webkit-backdrop-filter` 双写。

**5. 少量组件/配置同步**（非视觉逻辑）：`vite.config.ts` 开发代理目标支持 `QIMENG_DEV_PROXY_TARGET`
环境变量覆盖（默认 8420 行为不变，隔离调试实例用）；PWA manifest theme_color/background_color 与
theme-color meta 改随暗色画布（`#0b0c16`/`#f3f3f9` 三处双写）；设置页「主题模式」文案对齐暗色默认。

**刻意不做**：不引入任何新运行时依赖（动效零 JS 库，全 CSS/原生 API）；不改 openapi 协议；
不动 hooks/生成 SDK/路由结构；不做路由级 view transition（与叠加组保态机制交互风险大，收益低）；
桌面壳（ADR-0020 复用本 Web UI）零改动自动继承新视觉。

## 状态（Status）

Accepted（2026-10-02）

## 后果（Consequences）

- 正面：视觉代际差达成；暗色优先贴合媒体消费场景；token 单源后品牌换色只动 tokens.css 一处；
  glass 材质参数（blur/saturate）与阴影/圆角全部可调；类名契约保住功能守恒与后续并行的 App 端对齐。
- 代价：玻璃材质在低端设备有 GPU 合成成本（backdrop-filter 面数控制在壳层+浮层，卡片是主要承受面，
  已通过 @supports 降级与 blur 档位收口）；旧名别名块是过渡态技术债，TSX 内联 var 引用清理后应删；
  v1 像素锚位体系退役意味着与旧原型的对齐纪元结束（后续以本 ADR 为视觉规范源）。
- 受影响文件：`web/src/tokens.css`、`web/src/index.css`、`web/src/styles/glass.css`（新，删
  `styles/prototype.css`）、`web/src/lib/theme.ts`、`web/src/pages/SettingsPage.tsx`（文案）、
  `web/index.html`、`web/vite.config.ts`、`web/package.json`（-@immich/ui）、`web/README.md`、
  `docs/HANDOVER.md`、`docs/CHANGELOG.md`。
