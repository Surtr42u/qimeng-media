# web/ — React Web/PWA

> M2 里程碑在此开发。动手前必读：`../AI_README_FIRST.md`、`../docs/adr/0008`（UI 解耦纪律）、`../docs/adr/0031`（设计语言 Aurora Glass）。

## 技术栈（定论，禁止擅改）

React + Vite + TypeScript(strict) + Tailwind CSS 4 + shadcn/ui + TanStack Query + PWA 插件。
动效零 JS 库（全 CSS / 原生 View Transition API，ADR-0031）。

## 设计语言（ADR-0031「绮梦流光 · Aurora Glass」，2026-10-02 起）

- **暗色优先**：无手动选择时默认深色（不再跟随系统）；浅色是完整度相同的「晨雾玻璃」变体。
  主题默认值在 `index.html` 首帧内联脚本与 `lib/theme.ts` 双写，改动须两处同步。
- **视觉规范源 = `src/tokens.css`（token 体系 v2）**：画布极光（`--qm-aurora`）、玻璃材质组
  （surface/strong/soft + glass-blur/saturate + glass-highlight）、文字四级、主色三件套、图表五色、
  阴影/圆角/动效时长/缓动/层级全 token 化；shadcn 主题变量在 `src/index.css` 从 `--qm-*` 反向桥接
  （shadcn ← qm 单向）。组件**只准消费 `--qm-*`**（旧名兼容别名仅供存量代码，新代码禁用）。
- **主题样式层 = `src/styles/glass.css`**（v1 `prototype.css` 已删）：类名契约与组件 DOM 不动，
  改视觉只动这个文件；backdrop-filter 不支持时经 `@supports` 整组退化为不透明面板（布局零变化）。
- 全局 `html { zoom: 1.1 }` 及其三处补偿（radix popper wrapper / 图片查看器 `--zoom-inverse` /
  `:fullscreen zoom:1`）是 load-bearing 实证修复，改动 zoom 须同步 glass.css 内注释标注的互指点。

## 分层纪律（铁律）

```
src/
├── api/        # make sdk 生成的客户端（禁止手改）
├── hooks/      # TanStack Query hooks：全部数据获取在此层
├── components/ # 纯渲染组件：禁止 import api、禁止业务规则
├── tokens.css  # 设计 token（颜色/玻璃材质/间距/圆角/动效/层级），组件只引用变量
├── styles/     # 主题样式层 glass.css（类名契约的视觉实现）
└── pages/
```

- 组件内出现 `fetch/axios/api.` = 违规
- 例外：`lib/upload-chunked.ts` 传输层 XHR 直连不走生成 SDK（abort 支持缺失+403 拦截器误伤，ADR-0028 记档）
- 出现硬编码颜色 = 违规（一律 `var(--qm-*)`）
- 数据转换/业务判断放 hooks 或服务端，不进组件
- 开发代理目标默认 8420；隔离调试实例可用 `QIMENG_DEV_PROXY_TARGET=http://127.0.0.1:<port>` 覆盖
