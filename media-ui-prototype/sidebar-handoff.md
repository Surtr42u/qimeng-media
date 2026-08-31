# 执行任务书：三段式侧边栏 → media-ui-prototype（桌面复刻 UI）

> 给「全新执行 AI」的完整指令。本文件自包含：不需要任何会话上下文，读本文件即可开工。
> 来源：三段式侧边栏由另一个 UI 实验会话制作（样式数值对齐 Immich 官方 @immich/ui@0.86.0，MIT 许可，无版权障碍；图标用近似 SVG/CSS 绘制，非任何官方素材）。

## 0. 背景（为什么做这件事）

用户对多条 AI 路线做了对比，结论：
- **UI 主路线 = media-ui-prototype**（桌面客户端风格的静态原型：纯 HTML/CSS/JS，已有顶栏 75px、4 列卡片流、12 张真实数据卡，视觉验证通过）。
- **侧边栏 = 本任务书第 4 节的三段式**（256px：品牌区 + 主导航 + Library 分组 + 底部状态卡）——用户认为这个元素比原型左侧的 64px 窄栏更好，**用三段式替换原型侧栏**。
- 面板（panel-demo）与旧壳内的**真实功能**（相册/统计/管理等）暂保留，等本原型敲定后再移植功能——本轮**不做**功能接入。

## 1. 目标目录与现状

- 原型目录：`<本地工作区>\media-ui-prototype\`
  - `index.html`（侧边栏 + 顶栏 + 卡片流结构）
  - `style.css`（桌面客户端原型样式：`--sidebar-w: 64px`、`--header-h: 75px`、`--pink:#fb7299` 等）
  - `app.js`（12 张卡片真实数据 + 渲染；`data-page` 切页标记）
  - `serve.mjs`（静态服务器，`http://127.0.0.1:8099/`）
  - `covers/`（本地化的封面/头像）、`data/`（DOM/卡片 JSON）
- 原型技术栈：**纯原生 HTML/CSS/JS**（无框架、无构建，node 内置 http 静态服务）。
- 现有侧边栏（将被替换）：`.sidebar`（64px，返回按钮 + 首页/精选/动态/我的 + 未登录头像 + 底部 6 项图标列表）。

## 2. 任务（只做这些）

1. **替换侧边栏**：删除 `.sidebar`（64px 窄栏）的内容块，换成第 4 节的 `qm-sidebar` 三段式（HTML + CSS）。
2. **内容区联动**：把 `.main`/内容区的左侧留白从 `64px` 改为 `256px`（或对应 margin/width 计算，务必同步比例，参考现有实现方式）。
3. **导航联动**：三段式侧边栏沿用原型 `data-page` 约定（active 标记当前页），点击切页逻辑并入现有 app.js（如已有切页机制，只替换 DOM 结构与类名）。
4. **验证**：`node serve.mjs 8099` → 截图检查：左栏三段式 + 顶栏/卡片流原样 + 深色模式可选。

## 3. 禁止事项

- 不改顶栏、卡片流、`app.js` 的数据与渲染逻辑（已有 12 张真实卡片数据，原样保留）。
- 不引入框架/构建工具（原型的工艺是纯 HTML/CSS/JS + node http，保持一致）。
- 不使用官方素材（logo、图标用近似 SVG/CSS，与现有 covers/ 套路一致）。
- 不移植 panel-demo / qimeng-media web（React 项目）的任何代码进来——本任务只发生**在原型目录内**。

## 4. 三段式侧边栏规格与代码

### 4.1 视觉规格

| 项 | 值 |
|---|---|
| 侧栏宽 | 256px（`flex-shrink:0`，`height:100%`）|
| 内边距 | `pt-8 px-4`（顶部 2rem / 左右 1rem），`overflow-y:auto` |
| 品牌区 | 32×32 圆角 8px 主色块（金字「绮」）+ 产品名「绮梦影库」+ 副题「NAS 媒体库」 |
| 主导航 | 3 项：首页(active) / 全部 / 发现；`rounded-lg`(8px) + `px-3 py-2` + 图标 20px + 文案 14px 中粗，`gap-3` |
| Library 分组 | 小标题 12px 中粗 muted（「Library」，`pt-5 pb-1 px-3`）→ 4 项：收藏 / 相册 / 归档 / 回收站 |
| 选中态 | 背景 = 主色 10% 混合（`color-mix(in oklab, #4250af 10%, transparent)`），文字/图标用主色 |
| hover | `background: #f5f5f5`（浅色）/ `#1f1f1f`（深色） |
| 底部状态区 | `margin-top:auto` + 顶边线分隔；三行 12px muted：存储 / 服务 / 版本（占位文案，本轮不接接口）|
| 配色变量 | 建议新增：`--qm-primary:#4250af`、`--qm-muted:#9499a0`、`--qm-divider:#e6e6e6`、`--qm-bg:#fff`；深色：主色 `oklch(0.836 0.074 258.58)`、bg `#0a0a0a`、divider `#2a2a2a`、muted `#a5a5a5`（沿用原型现有深色切换逻辑挂到 `.dark` 类即可）|

### 4.2 HTML（替换 index.html 中 `<aside class="sidebar">…</aside>`）

```html
<aside class="qm-sidebar">
  <div class="qm-sidebar--brand">
    <span class="qm-sidebar--logo">绮</span>
    <div>
      <p class="qm-sidebar--name">绮梦影库</p>
      <p class="qm-sidebar--sub">NAS 媒体库</p>
    </div>
  </div>

  <nav class="qm-sidebar--nav">
    <a class="qm-nav-item active" data-page="home"><!-- svg: 首页 -->首页</a>
    <a class="qm-nav-item" data-page="all"><!-- svg: 全部 -->全部</a>
    <a class="qm-nav-item" data-page="discover"><!-- svg: 发现 -->发现</a>
  </nav>

  <p class="qm-sidebar--group">Library</p>
  <nav class="qm-sidebar--nav">
    <a class="qm-nav-item" data-page="favorites"><!-- svg: 心形 -->收藏</a>
    <a class="qm-nav-item" data-page="albums"><!-- svg: 相册 -->相册</a>
    <a class="qm-nav-item" data-page="archive"><!-- svg: 档案盒 -->归档</a>
    <a class="qm-nav-item" data-page="trash"><!-- svg: 垃圾桶 -->回收站</a>
  </nav>

  <div class="qm-sidebar--status">
    <p>存储 · 5.3 GB / 30 GB 已用</p>
    <p>服务 · 运行中 · Dev</p>
    <p>版本 · 0.1.0 · 2026-08-31</p>
  </div>
</aside>
```

> 图标：用 24 viewBox SVG 近似（首页=房子、全部=相册方格、发现=罗盘、收藏=心形、相册=相册、归档=档案盒、回收站=垃圾桶），描边风格与原型现有 SVG 一致即可。

### 4.3 CSS（追加到 style.css，类名前缀 `qm-` 避免与原型冲突）

```css
.qm-sidebar {
  width: 256px; flex-shrink: 0; height: 100%; overflow-y: auto;
  display: flex; flex-direction: column;
  padding: 2rem 1rem 0;
  border-right: 1px solid var(--qm-divider, #e6e6e6);
  background: var(--qm-bg, #fff);
}
.qm-sidebar--brand { display: flex; align-items: center; gap: 0.75rem; padding: 0 0.75rem 1.5rem; }
.qm-sidebar--logo {
  width: 2rem; height: 2rem; border-radius: 0.5rem;
  background: var(--qm-primary, #4250af); color: #fff;
  display: grid; place-items: center; font-weight: 700;
}
.qm-sidebar--name { font-size: 0.875rem; font-weight: 600; }
.qm-sidebar--sub { font-size: 0.75rem; color: var(--qm-muted, #9499a0); }
.qm-sidebar--nav { display: flex; flex-direction: column; gap: 0.25rem; }
.qm-nav-item {
  display: flex; align-items: center; gap: 0.75rem;
  padding: 0.5rem 0.75rem; border-radius: 0.5rem;
  font-size: 0.875rem; font-weight: 500; color: inherit; text-decoration: none;
  transition: background-color 0.15s;
}
.qm-nav-item svg { width: 1.25rem; height: 1.25rem; flex-shrink: 0; }
.qm-nav-item:hover { background: var(--qm-hover, #f5f5f5); }
.qm-nav-item.active {
  background: color-mix(in oklab, var(--qm-primary, #4250af) 10%, transparent);
  color: var(--qm-primary, #4250af);
}
.qm-sidebar--group {
  padding: 1.25rem 0.75rem 0.25rem;
  font-size: 0.75rem; font-weight: 500; letter-spacing: 0.02em;
  color: var(--qm-muted, #9499a0);
}
.qm-sidebar--status {
  margin-top: auto;
  border-top: 1px solid var(--qm-divider, #e6e6e6);
  padding: 1rem 0 1.5rem 0.75rem;
}
.qm-sidebar--status p { font-size: 0.75rem; line-height: 1.6; color: var(--qm-muted, #9499a0); }
```

### 4.4 整合要点

- 内容区左留白联动：原型内 `.main`/内容容器若基于 `--sidebar-w: 64px`，改为 `--sidebar-w: 256px`（或直接改 calc），保证右侧内容不遮不漏。
- `app.js`：若原有 `const nav = document.querySelectorAll('.nav-item')` 之类的绑定，把选择器同步为 `.qm-nav-item`；`data-page` 值对照第 4.2 节，demo 页只有 home 数据时，非 home 项可先置灰/提示"尚未实现"，保持原型可点击状态即可。

## 5. 验收标准

1. `http://127.0.0.1:8099/` 打开：**左栏为三段式**（品牌区/导航/Library/状态区），右侧 顶栏与 4 列卡片流与原版一致；
2. 首页选中态为淡蓝紫底 + 主色字；hover 有反馈；点击导航项只切换 active（数据不崩）；
3. 深色模式（若原型已实现）下侧栏跟随变深，配色用第 4.1 节深色值；
4. 浏览器截图存档，并在最终回复里给出与替换前的对比说明。

## 6. 与「原型 UI 文档」的关系

用户会安排原 桌面客户端复刻会话把它的 UI 也整理成独立文档/文件（如 `media-ui-prototype/ui-spec.md`）。若执行时该文件已存在，以它为准核对顶栏/卡片流规格；否则按现有 `index.html`/`style.css` 源码为准（已完成且验证通过，无需重建）。
