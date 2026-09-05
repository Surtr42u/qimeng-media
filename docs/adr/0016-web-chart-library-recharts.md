# ADR-0016：Web 图表库选型 recharts

- 状态：已接受（2026-09-05）
- 决策人：用户（「曲线悬停对不齐，干脆换个自带这个效果的来使用」）
- 执行：GLM-5.3-Flash（主代理）

## 背景

数据页「浏览与播放趋势」与维护页「网络负载」两张折线图需要悬停数值反馈。
先手搓 HTML 覆盖层实现（CHANGELOG 第四十九笔），但提示点与曲线对不齐——
SVG 是 preserveAspectRatio=none 拉伸坐标系，光标位置与最近采样点的映射在
HTML 层与 SVG 层之间出现偏差。用户拍板改为引入自带悬停能力的图表库。

## 决策

引入 **recharts 3.10.1**（npm 当前最新稳定；3.x 为 React 19 原生支持线，
官方文档核对过 Tooltip 的 contentStyle/itemStyle/labelStyle/cursor 与
isAnimationActive 形态）。两处折线图改用 LineChart/Line/Tooltip/XAxis/YAxis：

- 悬停提示（Tooltip）、竖向参考线（cursor）、高亮点（activeDot）均为库内置，
  精确吸附采样点；
- 主题色直接引用 `--qm-primary` / `--trend-line-sub` 等 CSS 变量（SVG 属性
  接受 CSS 变量），明暗主题随 token 自动生效；
- 维护页网络图 2s 滚动刷新，`isAnimationActive={false}` 防止持续重放动画。

## 落选方案

- **Chart.js（react-chartjs-2）**：canvas 渲染，主题化靠 JS 传常量，与本项目
  CSS 变量 token 体系割裂，深色主题需双份配置。
- **ECharts**：能力过剩，包体对本项目过大。
- **uPlot**：极小极快，但命令式 API，React 集成与声明式风格不符。

## 影响

- web 新增运行时依赖 `recharts`（含 d3 子依赖；本地单用户 NAS 场景包体无碍）。
- 后续 web 新增图表统一走 recharts，不再手搓 SVG 折线。
- ADR-0014 的依赖白名单仅约束 Android 端，本决策不影响。
- 受影响文件：`web/package.json`、`web/src/components/data/TrendChart.tsx`、
  `web/src/pages/MaintenancePage.tsx`（网络负载图）、`web/src/styles/prototype.css`。
