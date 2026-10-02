import type { KnipConfig } from 'knip'

// knip 配置：孤儿依赖/死文件门禁（2026-10-02 手搓治理批引入，CI web job 同步执行）。
// 门禁口径：依赖/开发依赖/未声明/未解析/死文件六类零孤儿为过——「未用导出/重复
// 导出」类属代码卫生后续批（存量 35 项已于引入时记档 CHANGELOG，不在本门禁拦截面）。
// 本地跑 `npx knip`（不带 --include）可得含导出面的完整报告。
const config: KnipConfig = {
  // 入口补充：index.html→main.tsx 由 vite/vitest 插件自动识别，无需手列。
  // openapi-ts.config.ts 是 CLI（CI `npx openapi-ts -f`）消费的配置文件，不在
  // import 图可达范围——列为入口后 knip 的 openapi-ts 插件随之识别生成链依赖，
  // 故无需 ignoreDependencies 豁免（豁免越少越好，理由记不下的不豁免）。
  entry: ['src/api/openapi-ts.config.ts'],
  // 协议生成物不入库（.gitignore，ADR-0009），`npx openapi-ts` 重建后才有实体：
  // CI 先生成后检（与 tsc 同序），本地无实体时 @/api/generated 会报 Unresolved——
  // 这是环境前置而非孤儿，不设 ignore 豁免（生成内容不受人工维护，死代码分析
  // 噪音由门禁口径排除，不靠此文件清单）。
}

export default config
