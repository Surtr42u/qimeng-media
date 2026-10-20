# API：作者编辑与词表

> [API 目录与全局约定](../GUIDE_API.md)。本页解释用法，字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；正文日期只标记机制来源。

## 作者

端点：GET /authors、**POST /authors、PUT/DELETE /authors/{id}**、**GET /authors/suggest、GET/PUT /authors/source-vocabulary、GET/PUT /authors/{id}/sources**、POST/GET/DELETE /authors/import-txt、**GET /authors/import-txt/export**、POST /authors/import-txt/rebuild、**GET/PUT /authors/mirror**、PUT /authors/{id}/follow

常规/COS 双体系（type 区分）；
TXT 三格式自动识别统一重建（GET=已导入片段列表〔**2026-09-25 起条目带 `importedAt`**（RFC3339，旧数据零值=最旧）〕、DELETE=移除片段并从剩余片段重建〔204/404〕，第五笔（历史档）新增——旧版数据管理「TXT导入作者」卡数据源；
POST …/rebuild=用已存片段幂等重放重建关联〔不增删片段，2026-09-04 新增——库重建后常规关联丢失的自救入口〕）；
**2026-09-25 上传挂靠批四新路径**：`GET /authors/suggest`（q 对 displayName 子串匹配 ASCII 大小写不敏感——displayName 含 " / " 连接别名、别名片段命中同一人；
只返回常规作者（type=regular，含零关联）；
q trim 空→空列表；
limit 缺省 10、1..50 越界 400；
displayName 升序）；
`GET /authors/sources`（全部片段来源区去重词表 {name,authorCount}，authorCount 降序、name 升序=常用优先；
**与资产出处分区（SourceMatcher/custom_sources）互不相干禁止混用**，DOMAIN_RULES §6）→ **2026-09-25 资产编辑批（ADR-0024）退役删除**（个人片段词表，随上传挂靠参数批），改由以下两组承载——`GET/PUT /authors/source-vocabulary`（通用来源词表=**来源建议唯一数据源**：kv_settings `author_source_vocabulary`；
GET 无记录=空数组；
PUT 整体替换——trim+去重后即生效词表、空数组=清空，单项含控制字符/超 500 rune 400 INVALID_PARAM、**原始数组超 32 项 400**；
服务端手动维护小清单，Web 设置页维护卡；
来源=「获取渠道/平台名」非 §4 作品出处，**与资产出处分区（SourceMatcher/custom_sources）互不相干禁止混用**，DOMAIN_RULES §6；
**出厂自动预填一次**〔2026-09-25 上传暂存批：kv 键不存在时——片段来源区按不同作者数统计、≥2 保留〔单作者个人地址排除〕、排除链接形态〔http/www./含点号域名〕、authorCount 降序 name 升序 cap 20、有结果才写键；
PUT 恒写键故用户清空不复活〕）；
`GET/PUT /authors/{id}/sources`（常规作者片段来源区读写——GET=作者块所在片段来源区〔无块=空数组；
作者不存在 404；
COS 作者 400〕、PUT 带 `mode=replace\|append`〔缺省 replace=整体替换该作者块来源区；
append=并入去重永不覆盖〔上传流程自动挂靠语义〕〕〔无块=最近导入片段新建块，无片段自动建「上传自动挂靠.txt」〕，校验同通用词表）；
`GET /authors/import-txt/export?filename=`（片段原文 text/plain 下载，Content-Disposition attachment + filename*=UTF-8''转码；
匿名片段 filename 空/缺省=最近一份、下载名「作者清单.txt」；
404=不存在；
导出内容往返可再导入不触发 409）；
`GET/PUT /authors/mirror`（{path, fragmentFilename}；
path 空=关闭（默认）、非空必须绝对路径否则 400；
fragmentFilename 空=最近导入的片段；
PUT 成功即尝试一次镜像刷新；
SECURITY.md「作者总表镜像」）；
**2026-10-13 作者管理族（ADR-0024 修订 5）**：`POST /authors`（body {displayName}，maxLength 200）按名新建作者——仅作者行不关联文件〔规范身份命中已有常规作者则复用并返回既有行真实计数；
未命中新建 origin=client；
别名分隔=两个以上空白或「 / 」，身份取首别名 GenerateAuthorID；
非法名 400 带原因、与 COS 行撞名 409〕；
`PUT /authors/{id}`（body {displayName}）改名——TXT 全部片段的块编号行原地重写 + 身份迁移〔新 id 时 followed/created_at/origin 随迁、asset_authors 关联改挂、上传条目改指〕，同身份幂等 200、撞其他既有作者 409 不合并、COS 400；
`DELETE /authors/{id}` 删除——TXT 块 + 关联 + 作者行三清、文件保留〔DOMAIN_RULES §6〕，不触发统一重建（终态等价）、不存在 404、COS 400；
三端点共用 403 之外同款鉴权。**回写身份自洽（同日事故修复）**：程序化写块的别名列表由 `authoring.AliasesForAuthorID` 唯一重建，保证块回读 id == 目标作者行 id（修复「选了来源后保存无效」——显示名单别名含「 / 」被盲目拆分致块 id 漂移）；
**别名分隔修订**：CanonicalAuthorNames 现按两个以上空白与「 / 」拆分（显示名往返恒等），无空白斜杠不拆。**重导入保护**：POST import-txt 增可选 `conflictResolution=keep\|remove`（其他值 400）——同文件名重导入且新内容缺少上传写入条目时缺省 **409 TxtImportConflict**{filename, authors[{authorId, displayName, works[], sources[]}]}（只列缺失项，禁止静默丢弃），keep=缺失条目自动并回新内容后替换落库（响应 `mergedUploadEntries`=并回行数）、remove=按明示移除（元数据一并清除）；
格式 C 不触发（不存片段）；
关注布尔；
2026-09-03 响应加 viewCount（作者作品累计浏览次数）

## 详情面关联溯源 `origin`/`createdAtMillis`

（2026-10-02，ADR-0032 独立提案落地）：`GET /assets/{id}`（及同响应模型的 `PUT /assets/{id}/authors` 200、上传 201）的 `tags`/`authors` 条目由 Tag/Author 扩展为 `AssetDetailTag`/`AssetDetailAuthor`（allOf 叠加，既有字段不动）——新增可空 `origin`（ADR-0032 受控词表 client/txt/import/local-sync/scanner/legacy，与备份导出 LegacyMediaTagRef/LegacyAuthorMediaRef.origin 同名同义）+ 可空 `createdAtMillis`（关联成立毫秒）；
null/缺省=不可考（存量行 NULL/epoch 哨兵如实记不可考，绝不落 1970 字面量）。**纯透传、口径零变化**（DOMAIN_RULES §10 裁决语义不涉读取面）；
`characters` 不携带溯源（扫描器派生、origin 恒 scanner，且 AssetSummary 内为 string[] 共享列表面）。详情条目专属视图——标签池 `GET /tags` 与作者列表 `GET /authors` 仍返回原 Tag/Author 不带溯源。Web 详情页作者/标签区呈现来历小字（如「客户端 · 10-1 12:30」，不可考不显示），Android 端消费留待 ui/app-redesign 合并后批次

## 作者挂靠编辑（2026-09-25，ADR-0023/0024）

ADR-0023 的上传挂靠参数（`authorId`/`authorName`/`source`）已随 ADR-0024 退役——上传回归纯上传，挂靠入口迁资产编辑页；
TXT 片段本体=唯一真相、写入内容与手工片段同构、统一重建/重放后不丢不重、挂靠与入库同事务等语义不变。**编辑端点三件套**：`PUT /assets/{id}/authors`（body `{authorIds, newAuthorNames?}` 整体替换资产作者关联，字段都空/缺省=解除全部常规关联；
**2026-10-11 修订：可按名新建**——newAuthorNames 逐名按规范身份匹配已有常规作者则复用（沿用显示名不重复建行）、未命中才新建（作者行 origin=client + 作者块 + 作品行），非法名/超 20 项 400，ADR-0024 修订 4；
新增=把资产**当前文件名**作为作品行追加进对应作者块——落点：作者块所在数组序第一个片段，未命中并入最近导入片段，无片段自动建「上传自动挂靠.txt」，已存在行不重复追加；
**块不存在时新建块，新块与上一块之间空三行**——纯排版约定，解析器跳过空行，2026-10-10 规范；
**保存自愈**=对作者全集逐位幂等追加作品行（关联在而文本缺行的漂移保存即愈，行已存在零写入）；
移除=遍历**全部**片段删该资产作品行——行文本精确匹配优先、trim+小写+去空格归一匹配回退，**归一保留扩展名**，png/mp4 同名互不误伤；
同事务更新 asset_authors，200 回 AssetDetail；
仅 `capabilities.authorAttach=true` 的库接受否则 400）；
`GET/PUT /authors/{id}/sources`（作者片段来源区读写，PUT 带 `mode=replace\|append`——缺省 replace=整体替换该作者块来源区〔编辑页语义〕、append=并入去重永不覆盖既有来源〔上传流程自动挂靠语义，重复提交幂等零写入〕，落点规则同上，校验同通用词表）；
`GET/PUT /authors/source-vocabulary`（通用来源词表，见「作者」行）。**上传流程自动挂靠（ADR-0024 修订）**：上传协议保持纯上传；
App 上传单文件 201 后客户端依次调 `PUT /assets/{id}/authors`（authorIds 单项，新资产零关联无覆盖风险）与 `PUT /authors/{id}/sources`（mode=append）；
挂靠失败不重试上传（防文件重复入库），条目落「已入库待挂靠」态由编辑页补挂；
批次默认（库/作者/来源）持久化、入队时刻快照继承；
暂存列表/逐项编辑已随 2026-09-29 直传化退役，选完即传。**kv 上传条目修剪与登记（2026-10-11 修订）**：编辑变更成功后同步修剪 `imported_txt_upload_entries` 中内容已不存在的登记条目（编辑是有意变更，防重导入 409 误报）；
编辑路径本次实际新追加的作品行同上传口径**登记**（2026-10-11 起，重导入保护范围 = 上传写入条目 + 编辑写入作品行；
来源区写入仍不登记）。**来源口径**：来源=「获取渠道/平台名」（如「论坛乙」），**不是** §4 的作品出处；
来源建议唯一数据源=通用来源词表（服务端手动维护小清单，个人片段来源区词表退出建议场景）；
来源仅记录永不参与匹配，与资产出处分区（SourceMatcher/custom_sources）互不相干（DOMAIN_RULES §6）。**重导入保护**：同文件名重导入新内容缺登记条目→缺省 409 二选一（keep 自动并回/remove 明示移除，见「作者」行）；
保护范围=上传写入条目 + 2026-10-11 起编辑写入的作品行（见上「kv 上传条目修剪与登记」）。**镜像**：GET/PUT /authors/mirror 配置本地 txt 绝对路径，服务端→本地单向投影、临时文件+rename 原子写、尽力而为（失败仅告警绝不阻塞核心操作，SECURITY.md「作者总表镜像」；
挂点含资产作者/来源编辑保存后、TXT 导入落库后、片段删除后、镜像配置保存后、备份导入的 TXT 片段段后、本机同步通道 TXT 导入后）。**已知缺口**：资产改名/移动不自动回写片段作品行（匹配不到的残留行由统一重建重新关联兜底；
2026-10-11 起对改名资产再保存一次编辑页会以当前文件名补写新行）；
同片段跨块同名作者 RemoveWorks 只清首遇块（与解析器身份口径一致）
