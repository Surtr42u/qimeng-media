# CHANGELOG 分期历史：第五百三十一至五百三十二笔

原条目完整保留；现状以当前专题为准。

## fix(app+web): 作者管理搜索大小写不敏感——大写作者名小写搜不到（2026-10-12 第五百三十二笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **背景（用户实测反馈）**：作者管理页搜作者时大小写敏感——作者名多含大写（`BubbleB`/`Regina3D`/`SKRevenant` 等），输全小写（如 `bubbleb`）零命中；中文不受影响。
- **修复（两端同口径；纯子串语义不变、仅折叠大小写）**：App `applyAuthorRows`（core:model 纯函数，关键词 `trim()` 后 `contains(kw, ignoreCase=true)`）；Web `AuthorsPage` 关键词双侧 `toLowerCase()`。扫描面：App 客户端关键词过滤仅此一处；Web 历史页 `groupHistory` 早已双侧 lowercase（无改动）。**不改分隔符语义**——「 / 」连接的显示名仍按段子串匹配。
- **测试**：`AuthorRowsTest` 新增「名字搜索大小写不敏感」（小写命中大写名/大写命中/混排/别名段整体小写，6 例全绿）；Android 门禁 `assembleRelease + testDebugUnitTest + lintDebug` BUILD SUCCESSFUL；Web `tsc --noEmit` 零错 + vitest 320 例全绿 + `web/dist` 重建（服务端按磁盘托管 web/dist，无需重启）。
- **装机（用户拍板「用昨天的方法」后执行）**：release APK 同签名（SHA-256 `8f62ba55…`）原地覆盖装机真机（`adb install -r` Success，2026-10-12 21:0x）。内置修复走 release 包即可，无需 debug 包（debug 仅调试用途，装机口径不变）。
- **手机端数据同步（KassioppiaVA 收尾）**：手机侧《作者总表.txt》（`1/HHH/4 作者/`）已推送修正版（与电脑端逐字节同哈希，第 1046 行 `78  skrev  SKRevenant`）；**手机内嵌库（本机模式）仍是旧显示名**——内嵌服务端 dev 共享密钥每次拉起随机生成、不落盘不进日志（adb 侧无法走 API），收敛通道 = App「我的→数据管理→作者 TXT 导入」重导同一份 txt（用户执行，步骤已交付；App 内置库路径 `/data/data` 私有、adb 不可达，只读诊断亦不可行）。
- **文档**：CHANGELOG.md（本笔）、HANDOVER.md（头部更新）。

## fix(server+docs): 旧项目别名空壳作者清理（migration 0017 入库）+ 作者 skrev 剔除 KassioppiaVA——手机端作者管理与桌面镜像收敛（2026-10-12 第五百三十一笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **0017 迁移入库（补记 2026-10-11 手机端排查，PC 库当日已应用）**：旧项目 qimeng-backup 导入对「规范身份已存在、但名字写法不同」的作者另落了 6 行独立作者（身份 id 取自别名单词：`dude017`/`fgnilin`/`kassioppiava`/`ryan_reos`/`skrevenant`/`therealzoh`，与规范身份同一人却零关联零文件——手机端实测 153 位 vs 规范 147 位）。别名写法已按解析器口径（`authoring.ParseAuthorBlocks`：编号行全部别名以 " / " 连接为 displayName）并入规范行；空壳行由 migration 0017 清理——三重守卫（白名单 6 id + `origin='import'` + 零关联）、幂等可重跑；down 为纯数据迁移占位（无结构回退，测试与灾备链路可用）。测试：`TestLegacyAliasAuthorPrune`（空壳行全删/三重守卫不误伤两子用例）+ `TestMigrateDownThenUp` 链首插入 0017 down 验证步；`go test ./internal/store/` 全绿。
- **作者 skrev 剔除 KassioppiaVA（2026-10-12 用户要求，数据修复，零代码改动）**：手机端作者 `skrev` 显示名 `skrev / KassioppiaVA / SKRevenant` → `skrev / SKRevenant`，桌面镜像《作者总表.txt》随之收敛。路径=取服务端片段原文、仅改名称行别名（全库唯一一处 KassioppiaVA）→ `POST /authors/import-txt`（同 filename 覆盖 + 统一重建；重导入保护零冲突，127 作者块/791 关联重算）→ 镜像自动刷新重写桌面文件。验证：DB display_name / 片段原文 / 桌面镜像 / GET /authors 四处一致（镜像与片段逐字节同哈希）、skrev 关联 3 文件与作者总数 147 不变、全库 kv 扫描零 KassioppiaVA 残留。
- **已知边界（记档）**：旧项目备份包内该作者原名 `KassioppiaVA / skrev` 属历史档案未动——重导入含这 6 行空壳的旧包会重新落行，重跑 0017 即清（HANDOVER §5 记档级）。
- **文档**：CHANGELOG.md（本笔）、HANDOVER.md（头部更新 + 记档级一条）、migration 0017 注释勘正（引文改为解析器口径 + 10-12 收敛交叉引用）。
