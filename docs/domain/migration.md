# 旧数据迁移映射（qimeng_backup.json → 新库）

> 本专题是领域规则正文；[领域目录](../DOMAIN_RULES.md)保留原 §编号。公式与常量保持原义，标记「逐字遵守」者未经用户明确要求不得改变。

## 10. 旧数据迁移映射（qimeng_backup.json → 新库）

业务宿主为 `portablebackup`：独立文件 DTO，不依赖 HTTP/gen；有界 JSON 解码、状态码和附件头留在 HTTP。v2 写入前校验、v1/v2身份匹配、TXT→作者→标签→事件→点赞收藏→时间轴→关注→偏好原顺序保留。各段仍独立提交；后段失败保留已提交前段并返回原阶段错误，可幂等重试，已提交数据同步失效推荐/推进修订号并通知连接客户端，避免失败响应遮住部分提交。取消前未提交则不通知；TXT 单片段失败仍警告跳过，keep 与镜像刷新沿用 authorimport。导出全部查询绑定同一个事务快照，任何段查询失败中止，偏好缺省兼容边界不扩大。既有全量备份/64MiB限额不变，本次不是流式容器或历史截断改造。

**当前便携格式 v2（2026-10-19，ADR-0040）**：本节旧聚合回放仅适用于 schemaVersion=1。当前导出 v2 必带 `viewEvents`（允许空数组），保留事件身份、原始会话/来源、UTC 毫秒时间、可空秒数及原日键；导入只按事件身份幂等合并，绝不再回放同份 mediaStats/dailyBrowse/history。已有 clientEventId（包括 legacy 内容键）原样保留；无键历史由 migration 0019 的旁表分配持久 portable 身份，不改历史事件原文；首次导回自身也能认出。v2 缺事件段/重复 recordKey/非法事件拒绝并且不写入。v1 的无键/聚合信息损失边界继续有效；已发生的重复不会被擅自删除。

**v2 文件映射**：recordKey 只负责备份内关联，全份唯一；按资产 ID 排序后逐次查重，碰撞追加资产 ID 与序号。导出可选 assetId/libraryName/relPath（不含绝对根）；导入本库 assetId 命中优先，跨库按文件名→相对路径→库名逐层唯一消歧，仍歧义或多个源条目指向同一目标时全部相关条目跳过并告警，绝不套用 v1「取最早」兜底。未匹配数据按既有跳过口径，媒体仍须先行。A→A、A→B→A 与实时补传交叉按事件身份守恒；不同历史来源本来就已丢失身份的聚合记录无法逆推重合关系。


### v1 聚合兼容导入（仅 schemaVersion=1）

| 旧段 | 新去向 |
|---|---|
| mediaFiles | 按文件名匹配目标库**既有**资产建立 asset_id 映射（不创建资产） |
| settings/scanSources | 提示用户重新配置库目录（SAF 目录概念不存在） |
| authors + authorMediaRefs + cosWorks | 作者表 + 关联（cos_ 前缀保留） |
| tags + mediaTagRefs | 标签 + 关联 |
| mediaStats + dailyBrowse + history | **转换为 ViewEvent 回放导入**（dailyBrowse 全量回放；mediaStats 只补差额 = 累计值−dailyBrowse 之和；history 仅当该文件无任何 dailyBrowse 行时补漏——「截取导入」的防重复计数语义；时间戳从明细还原：天粒度统一偏移正午、同日多条按秒错开），统计从事件重建（2026-09-07 三口径补记） |
| likes + favorites | **非事件回放**：likes 落最后点赞日一行（累计次数差额进 warnings，旧备份无逐日明细）、favorites 幂等 upsert |
| timelineTags | 时间轴标签 |
| history | 截取导入（新架构事件流无 500 条上限） |
| appPrefs.recommendationPrefs | 用户推荐偏好 |

迁移端点幂等（重复导入按内容去重不翻倍）；幂等范围明示：作者/标签/关联/时间轴/收藏/点赞按唯一键或内容键 upsert 天然幂等，事件回放以 exportedAtMillis 为批次锚点——**同批次**重复导入整体跳过（快速路径，跨批次幂等由内容键保证）；导入回放按确定性内容键幂等去重（键=回放路径前缀:资产:事件类:日/序号，写 client_event_id 唯一索引拦重）——同一来源换新批次再导入只增量补写新事件，浏览统计不再重复累计；统计缺口（gap）合成事件按『路径:资产:类:序号』identity 稳定复用，缺口收窄时旧事件不回收（宁少勿重）；dwell 秒数取首写值。上报路径 POST /events/view 自 2026-09-10 起以 `clientEventId` 为幂等唯一键（migration 0010）；导入回放内容键一律带 `legacy:` 前缀，与客户端 UUID 天然区分，两语境不冲突；cosWorks 段不逐条导入（作品为 COS 目录派生信息、无文件级映射可迁），cos_ 作者由 authors 段建立、文件关联以 kind=cos 重新扫描重建。

**版本穿越已知边界（2026-10-02 记档，风险接受）**：内容键幂等（2026-09-18）落地**前**导入的旧代事件不带 client_event_id——同一备份**换新批次**（exportedAtMillis 不同）重导时，唯一索引拦不住无键行，同逻辑事件重放叠加 viewing 统计（同批次重导有批次锚快速路径兜底，不受影响；穿越守恒面由 import_traversal_test.go 锁定：旧代库态同批次重导守恒 + 内容键三代链不叠加）。已知风险接受，待数据清洗移除存量无键行后复评。

### 两版本共有的关联与载荷规则

v2 文件映射以本节「v2 文件映射」为准，下列未匹配处理、标签组裁决、溯源及 TXT 片段规则两版本共用。

**未匹配与载荷边界（2026-10-01 文档澄清，第四百二十五笔）**：导入不创建/删除媒体资产，也不主动清理已有事件；标签组可按下文的较新时间整体替换，TXT按keep规则重建涉及作者的关联，不能概括为所有元数据只增不减；目标库没有同名文件（recordKey 匹配不上既有资产）时，该文件的作者/标签关联计入响应 skipped 计数，行为数据（浏览/点赞/收藏/时间轴）静默跳过，assetsMatched/mediaFilesTotal 可看匹配率。备份不含媒体文件本体（文件先行走上传/本机同步通道）；TXT 作者片段自 2026-10-01 起随备份迁移（见本节「TXT 片段」段，import-txt 手动通道保留为备用）。

**标签组同步语义（2026-09-20 用户拍板）**：资产标签组带改动时间——`assets.tag_set_updated_at`（migration 0012 新增，RFC3339，`''` = 未知/旧数据），任何标签组变更（替换式 PUT、单关联解绑、删除标签级联清关联、导入改写）随动写服务器当前时刻；备份 mediaFiles 条目新增可选 `tagsUpdatedAtMillis`（该资产标签组最后改动毫秒；缺省或 ≤0 = 未知，旧版备份恒缺省）。导入按资产逐个判定：**两侧时间都已知且备份 > 库内** → 该资产标签组整体替换为备份集（清空后按 mediaTagRefs 重挂）并把库内时间改写为备份时间、替换资产数计入结果 `assetsTagsReplaced`；其余情况（任一侧未知 / 备份 ≤ 库内）维持并集合并（只增不删）且**不改写库内时间**——并集结果没有单一来源时刻，宁可保留「未知」也不造假版本。导出恒带 `tagsUpdatedAtMillis`（库内 `''` → 字段缺省）。标签池（tags 段）始终并集 upsert，不参与时间判定。

**关联溯源与行级合并裁决（2026-10-02，ADR-0032）**：作者/标签关联与作者行的每一行携带来历证据——`created_at`（关联成立时刻，RFC3339 固定毫秒文本，库内统一口径）+ `origin`（写入通道受控词表：`client`=HTTP 客户端端点〔web/app 共用，服务端不区分 UA〕、`txt`=TXT 片段统一重建手动导入、`import`=备份导入、`local-sync`=本机同步自动导入、`scanner`=扫描器派生〔角色行/COS 作者〕、`legacy`=0016 迁移回填存量行=不可考；常量单源 `server/internal/store/provenance.go`）。时间哨兵：asset_authors/asset_characters 新列 NULL=不可考（存量行如实回填 NULL，禁止伪造时间）；asset_tags.created_at 沿用 0004 既有 epoch 哨兵（语义等价：都表示不可考）；asset_characters 的 created_at 语义=当前重算发生时刻（先删后插派生数据，重算即刷新）。**备份透传**：导出把库内原始来历原样带出（`LegacyAuthor.origin`、`LegacyAuthorMediaRef.createdAtMillis+origin`、`LegacyMediaTagRef.origin`，可空=旧版备份无此信息；asset_characters 是扫描器派生数据不入备份）——A 端手工创建的关联到 B 端仍是 `client`，来历不因搬运失真。**导入行级裁决**：① 词表校验——透传 origin 非法或缺省 → 兜底盖 `import`，透传时间缺省/≤0 → 兜底记导入时刻（入账时刻是真实事件，不算伪造）；② **补证（heal）**——既有行不可考（origin='legacy' 或时间哨兵 NULL/epoch）时被透传值补证升级：不可考不等于永久不可知，对端有据时以对端为据；③ **首写优先（keep）**——既有行可考时绝不覆盖：重复导入幂等，保守不篡改既有结论。注意补证与 keep 只约束**导入裁决**：TXT 重建/tags PUT 等替换式路径先删后插，行章随重建通道刷新、不参与补证与 keep（重挂=新章，与「最近添加置顶」既有口径一致）。标签组**集级**「谁新听谁」裁决维持上一段 tag_set_updated_at 口径不变，本次升级的是**行级**溯源（并集/替换两路径的逐行盖章与补证）。view_events 不在此列：事件流已有 session_id/client_event_id 行级溯源（migration 0010/0014），其三代回放键空间问题属导入回放专项另行处理。**读取面（2026-10-02 详情透传，ADR-0032 独立提案落地）**：详情响应的作者/标签关联条目（AssetDetailTag/AssetDetailAuthor）纯透传行级 `origin`+`createdAtMillis`（可空；NULL/epoch 哨兵归一为 null/缺省=不可考，≤0 同待遇）——只读零裁决，本段补证/keep 语义不涉读取面；角色行不入详情溯源面（扫描器派生 origin 恒 scanner、时间为重算时刻，AssetSummary 的 characters 是 string[] 共享列表面）；标签池/作者列表端点不带溯源（详情条目专属视图）。

### 旧 v1 导出布局（兼容说明，当前导出为 v2）

下段记录旧格式的 recordKey 与聚合布局，不能将旧映射/聚合回放用于 v2。v2 的当前身份/映射规则见本节开头；仍保留的兼容聚合段不参与 v2 事件导入。

**导出（新库 → qimeng_backup.json，GET /api/v1/export/qimeng-backup，2026-09-05 第六十一笔（历史档））**：逆向映射与导入互为镜像，可经导入端点幂等回环——mediaFiles 全量导出（recordKey 生成规则：文件名唯一 = 文件名；同名 = 「文件名 @ 文件夹名」；同名同文件夹（跨库）追加 #路径哈希；folderName 恒取 rel_path 父目录段，与导入侧 pickAssetByFolder 消歧互认）；dailyBrowse 由事件流物化表（asset x day）导出、mediaStats 由事件流按资产聚合（事件流是唯一真相源，物化表仅派生缓存）；history 导出全部 open 事件倒序（2026-10-04 用户拍板无上限，原「最近 500 条对齐旧库 view_history」兼容截取废止——事件行百字节级、全量仅 MB 级备份增量）；likes 按点赞行（资产 x 日）聚合为 { 累计次数, 最后点赞日 }（旧格式无逐日明细，行数即最接近的累计值）；settings/scanSources/albumRules/cosWorks 恒导出空数组（SAF 目录为设备本机概念、COS 作品目录与出处均为派生信息，导入端对空段零警告）；appPrefs 只带 recommendationPrefs（kv_settings 原样 JSON）。

### TXT 片段（两版本共有）

**TXT 片段（txtFragments 段）（2026-10-01）**：备份载荷并入 TXT 作者片段，作者表随备份全量同步。**导出**：全量已导入 TXT 片段逐字导出（`filename`/`content`/`importedAtMillis`，来自 kv `imported_txt_sources`），不截断不转换。**导入**：该段在 authors/authorMediaRefs 段**之前**处理（片段导入触发统一重建，若后处理会冲掉备份携带的关联数据）；逐片段合并——目标库无同名片段 → 直接导入；同名且内容相同 → 跳过（幂等）；同名但内容不同 → 按 import-txt 的 keep 语义处理（目标端上传写入条目并回后替换重建，绝不 remove）；旧备份无该段 → 零处理（向后兼容）。**导入响应计数**：`txtFragmentsImported`（新增+替换数）/ `txtFragmentsSkipped`（未产生变更的跳过数＝内容相同幂等跳过＋单片段导入失败跳过，失败有 Warn 日志不中止整个导入）。keep 路径下目标端 kv 内容为「备份∪上传条目」合并结果≠备份原文，同一备份重复导入恒计 imported=1、终态内容幂等不变（口径如此，非缺陷）。
