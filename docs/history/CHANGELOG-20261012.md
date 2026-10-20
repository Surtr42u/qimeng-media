# CHANGELOG 分期历史：第五百三十一笔

原条目完整保留；现状以当前专题为准。

## fix(server+docs): 旧项目别名空壳作者清理（migration 0017 入库）+ 作者 skrev 剔除 KassioppiaVA——手机端作者管理与桌面镜像收敛（2026-10-12 第五百三十一笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **0017 迁移入库（补记 2026-10-11 手机端排查，PC 库当日已应用）**：旧项目 qimeng-backup 导入对「规范身份已存在、但名字写法不同」的作者另落了 6 行独立作者（身份 id 取自别名单词：`dude017`/`fgnilin`/`kassioppiava`/`ryan_reos`/`skrevenant`/`therealzoh`，与规范身份同一人却零关联零文件——手机端实测 153 位 vs 规范 147 位）。别名写法已按解析器口径（`authoring.ParseAuthorBlocks`：编号行全部别名以 " / " 连接为 displayName）并入规范行；空壳行由 migration 0017 清理——三重守卫（白名单 6 id + `origin='import'` + 零关联）、幂等可重跑；down 为纯数据迁移占位（无结构回退，测试与灾备链路可用）。测试：`TestLegacyAliasAuthorPrune`（空壳行全删/三重守卫不误伤两子用例）+ `TestMigrateDownThenUp` 链首插入 0017 down 验证步；`go test ./internal/store/` 全绿。
- **作者 skrev 剔除 KassioppiaVA（2026-10-12 用户要求，数据修复，零代码改动）**：手机端作者 `skrev` 显示名 `skrev / KassioppiaVA / SKRevenant` → `skrev / SKRevenant`，桌面镜像《作者总表.txt》随之收敛。路径=取服务端片段原文、仅改名称行别名（全库唯一一处 KassioppiaVA）→ `POST /authors/import-txt`（同 filename 覆盖 + 统一重建；重导入保护零冲突，127 作者块/791 关联重算）→ 镜像自动刷新重写桌面文件。验证：DB display_name / 片段原文 / 桌面镜像 / GET /authors 四处一致（镜像与片段逐字节同哈希）、skrev 关联 3 文件与作者总数 147 不变、全库 kv 扫描零 KassioppiaVA 残留。
- **已知边界（记档）**：旧项目备份包内该作者原名 `KassioppiaVA / skrev` 属历史档案未动——重导入含这 6 行空壳的旧包会重新落行，重跑 0017 即清（HANDOVER §5 记档级）。
- **文档**：CHANGELOG.md（本笔）、HANDOVER.md（头部更新 + 记档级一条）、migration 0017 注释勘正（引文改为解析器口径 + 10-12 收敛交叉引用）。
