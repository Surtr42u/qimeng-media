# web/ — React Web/PWA

> M2 里程碑在此开发。动手前必读：`../AI_README_FIRST.md`、`../docs/adr/0008`（UI 解耦纪律）。

## 技术栈（定论，禁止擅改）

React + Vite + TypeScript(strict) + Tailwind CSS + shadcn/ui + TanStack Query + Framer Motion + PWA 插件。

## 分层纪律（铁律）

```
src/
├── api/        # make sdk 生成的客户端（禁止手改）
├── hooks/      # TanStack Query hooks：全部数据获取在此层
├── components/ # 纯渲染组件：禁止 import api、禁止业务规则
├── tokens.css  # 设计 token（颜色/间距/圆角/动效时长），组件只引用变量
└── pages/
```

- 组件内出现 `fetch/axios/api.` = 违规
- 例外：`lib/upload-chunked.ts` 传输层 XHR 直连不走生成 SDK（abort 支持缺失+403 拦截器误伤，ADR-0028 记档）
- 出现硬编码颜色 = 违规（一律 `var(--qm-*)`）
- 数据转换/业务判断放 hooks 或服务端，不进组件
