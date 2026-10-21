# 2026-10-13 变更历史

> 从近期 CHANGELOG 完整归档第五百三十三笔，正文与署名保持原样；当前状态查 HANDOVER。

## fix(server+api+app+web): 作者管理族三端点（新建/改名/删除）+ 回写身份自洽修复（「选了来源后保存无效」事故）+ 手机端作者数据收敛（2026-10-13 第五百三十三笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **事故与根因（用户 2026-10-13 实测双症状）**：① 编辑页给作者加来源「选了后保存无效」（回读恒空）；② 作者列表出现多个零关联空壳变体（同日连造 3 个 hornyherring 变体）。根因链：按名新建输入 `hornyherring / Horny Herring Studios`（单空格斜杠）未被别名拆分（旧口径只拆两个以上空白）→ 落成**单别名含「 / 」字面**的作者行（id=整串派生 `hornyherring__horny_herring_studios`）；而回写路径 `displayNameAliases` 又盲目按「 / 」拆分显示名重建编号行 → 写出的块派生 id = `hornyherring`（错误身份）→ 来源/作品写进错块、按作者行 id 回读永远为空；每次保存再向片段追加一个错误身份重复块（实测片段积 11 个）。
- **修复（server，回写身份自洽不变量）**：新增 `authoring.AliasesForAuthorID(displayName, authorID)` 为程序化写块别名列表唯一来源——形态①按别名拆分归一、形态②单别名含斜杠字面整串保留，两形态依序尝试并保证「块回读 id == 目标作者行 id」；都推不出（病态存量）拒绝写入转 400 提示先改名。调用点全改走（`ReplaceAuthorSources`/`appendAssetWorkLines`/`Apply`）。`CanonicalAuthorNames` 拆分口径修订：两个以上空白 + 「 / 」同视分隔（显示名往返恒等，无空白斜杠不拆）——按名新建不再产生单别名斜杠字面。测试：authoring 四函数回归（含事故形态往返锁定）、authorattach 事故回归（单别名斜杠行写来源 → 块身份自洽 + 回读可读）。
- **作者管理族三端点（ADR-0024 修订 5，草案备选「只改 DB」被否：TXT 唯一真相）**：`POST /authors` 按名新建（仅作者行不关联文件；规范身份命中复用/未命中新建；非法名 400 带原因、COS 撞名 409）；`PUT /authors/{authorId}` 改名（全部片段块编号行原地重写 + 身份迁移：followed/created_at/origin 随迁、asset_authors 关联改挂、上传条目改指；同身份幂等；撞其他既有身份 409 不合并）；`DELETE /authors/{authorId}` 删除（TXT 块 + 关联 + 行三清、条目同清、**文件保留**〔DOMAIN_RULES §6〕；不触发统一重建，终态等价）。文本手术纯函数 `authoring.RemoveAuthorBlocks`/`RewriteAuthorBlockNames`（与解析器共用块边界口径）。编排下沉 `authorattach/manage.go`（ADR-0019）；sqlc 四条查询（DeleteAuthor/UpdateAuthorDisplayName/RenameAssetAuthors/CopyAuthorWithNewID）；httpapi 新文件 `authors_manage.go`（哨兵映射 404/400/409；`GetApiV1Authors` 行装配抽 `authorFromListRow` 共用防漂移）。
- **三端接线**：openapi 三端点 + `make sdk` 三端重生成 + sdk.lock（257 条）；**App**：`AuthorRepository` 扩 createAuthor/renameAuthor/deleteAuthor（4xx 语义化拒绝映射 `AuthorManageRejectedException`）+ `AuthorViewModel` 对话框状态机（新建/改名/删除三态；成功关闭+重拉+提示横幅；失败保持对话框透出原因；输入 200 rune 封顶协议双写）+ `AuthorScreen` 行尾「⋮」下拉菜单（改名/删除）+ 搜索行「新建作者」胶囊 + 删除确认（明示「关联文件保留」）+ 无 MoreVert 图标补入 core:ui；**Web**：`use-authors` 三 hook（失效作者族 + 改名/删除连带资产族）+ `AuthorsPage` 行内 pill（改名/删除，stopPropagation）+ 排序行「新建作者」+ 共享件抽出 `components/ui/prompt-dialog.tsx`（词表维护弹窗同形态第 2 次出现即抽，词表页转调）与 ConfirmDialog danger 二次确认。测试：App `AuthorViewModelTest` 6 例全绿；Web vitest 320 全绿。
- **手机端作者数据收敛（用户库，run-as 直读内嵌库 + 离线手术，改动前全库备档）**：三空壳（Horny Herring Studios / hornyherring / hornyherring_Horny Herring Studios 变体）按「以手机端为准」口径收敛——片段 126 号块原地改双别名 `hornyherring  Horny Herring Studios` + 补作品行「守望先锋  天使 15.mp4」+ 来源区保留并补入用户反复试加未果的 `r34`；块 128~140 十一个乱块全清；DB 三行删除 + 关联改挂（作者总数 150→147、片段 1625→1554 行）；隔离实例真重建验证终态稳定（唯一 hornyherring、1 文件、来源读写恢复）；**PC 端随后对齐同名**（同文件经 PC 服务端 import-txt 通道把块 126 改双别名，id/关联不动——两端显示名一致、id 一致）。手机端镜像文件（作者总表.txt）同步推送为修复后内容。
- **验证**：server 全量测试 19 包全绿；authorattach/authoring/httpapi 新测全绿；App `:feature:author` 单测 + 编译过；Web build/lint（0 error）/vitest 过；gofmt/go vet 干净。
- **联带**：手机端 debug 包覆盖安装（同 applicationId 同 debug 签名保数据，换取 run-as 直读内嵌库；最终以 release 包回归装机）。
