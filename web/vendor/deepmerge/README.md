# deepmerge（本地安全回移植）

`@rollup/plugin-node-resolve@16`（经 `vite-plugin-pwa` → `workbox-build` 引入）固定依赖
`deepmerge@^4.2.2`。上游 4.3.1 是最后一个发布版本，维护者已拒绝修复并关闭相关 PR；
GHSA-gcqx-w48m-w2h4 / CVE-2026-93753 目前无已发布补丁版本。

本目录按上游 4.3.1 的发布产物 `dist/cjs.js` 回移植
[PR #274](https://github.com/TehShrike/deepmerge/pull/274)
（head `c5ad573121574db1c625b0aabcc6114ed7df6c5a`）的改动：

- 新增 `propertyIsForbidden(key)`，对 `__proto__` 键无论 target 形状一律跳过；
- `mergeObject` 复制 target 与遍历 source 时都应用该守卫。

`dist/cjs.js` 是上游已打包的发布入口（内联 `is-mergeable-object`，无运行时依赖），
本目录未改动其模块形态；`index.d.ts` 与 `LICENSE` 原样取自上游 4.3.1。

版本号 `4.3.2` 是本地补丁发布号，不代表上游发布。`web/package.json` 通过
`workspaces` 将该目录作为 `deepmerge` 包链接进依赖树，满足父包的 `^4.2.2` 范围。

- 来源：`deepmerge@4.3.1` npm 包（integrity
  `sha512-3sUqbMEc77XqpdNO7FRyRog+eW3ph+GYCbj+rK+uYyRMuwsVy0rMiVtPn+QJlKFvWP/1PYpapqYn0Me2knFn+A==`）
- 许可：MIT，见 `LICENSE`
- 退役条件：上游发布包含该守卫的正式版本后，移除 `workspaces` 中的
  `vendor/deepmerge` 条目与本目录，并重新核对锁文件与安全告警。
