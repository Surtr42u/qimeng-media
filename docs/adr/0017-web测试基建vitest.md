# ADR-0017 Web 测试基建选型 vitest

- 状态：已接受（2026-09-07，预落档——实施排入《QimengNAS/任务E-Web卷.md》E6 批，允许夜间免授权执行；2026-09-08 F4 批闭环：CI web job 已接入 npm test（ci.yml web job build 步骤后），本 ADR 待办清零）
- 背景：2026-09-07 晚 Web 对抗审查发现 P2 缺口——`web/package.json` 零测试命令、零 test runner 依赖，可直接单测的纯函数模块（`lib/sse.ts` 帧解析、`lib/pagination.ts`、`lib/format.ts`、分组/映射函数）零覆盖；集成冒烟只靠人工纪律，不可回归。
- 决策：引入 **vitest**（仅 devDependency，不触碰运行时依赖与 vite 配置主链），为 `web/src/lib/` 纯函数层建立单元测试；`package.json` 增加 `test` 脚本；CI 的 web job 后续跟进接入（不在 E6 批范围，记入待办）。
- 理由：项目 web 构建链已是 vite 生态（`npm run build` 基于 vite/PWA 插件），vitest 与之同源、共享配置心智，零额外转译层；对比 jest 需要额外 ts/jest 转译配置。版本号由执行批次当次查 npm registry 锁最新稳定版（铁律 8：不凭记忆写版本）。
- 影响：`web/package.json`、`web/package-lock.json`、`web/src/lib/*.test.ts`（新增）；ADR-0016（recharts）先例同款"Web 侧引入依赖需 ADR"口径。
- 网络例外：《任务E》夜间执行允许访问 npm registry（仅限安装 vitest 相关 devDependency），其余仍遵守任务书的外网限制。
