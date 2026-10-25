# 已退役的v1备份映射历史

> 2026-10-25按用户要求退役旧纯本地项目v1导入。以下仅保留旧映射依据，不是当前接口承诺；当前规则见[便携备份专题](../domain/migration.md)，决策见[ADR-0050](../adr/0050-退役旧项目v1备份导入.md)。已经入库的历史事件与原身份继续保留，不随退役删除。

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


### 旧 v1 导出布局（兼容说明，当前导出为 v2）

下段记录旧格式的 recordKey 与聚合布局，不能将旧映射/聚合回放用于 v2。v2 的当前身份/映射规则见本节开头；仍保留的兼容聚合段不参与 v2 事件导入。

**导出（新库 → qimeng_backup.json，GET /api/v1/export/qimeng-backup，2026-09-05 第六十一笔（历史档））**：逆向映射与导入互为镜像，可经导入端点幂等回环——mediaFiles 全量导出（recordKey 生成规则：文件名唯一 = 文件名；同名 = 「文件名 @ 文件夹名」；同名同文件夹（跨库）追加 #路径哈希；folderName 恒取 rel_path 父目录段，与导入侧 pickAssetByFolder 消歧互认）；dailyBrowse 由事件流物化表（asset x day）导出、mediaStats 由事件流按资产聚合（事件流是唯一真相源，物化表仅派生缓存）；history 导出全部 open 事件倒序（2026-10-04 用户拍板无上限，原「最近 500 条对齐旧库 view_history」兼容截取废止——事件行百字节级、全量仅 MB 级备份增量）；likes 按点赞行（资产 x 日）聚合为 { 累计次数, 最后点赞日 }（旧格式无逐日明细，行数即最接近的累计值）；settings/scanSources/albumRules/cosWorks 恒导出空数组（SAF 目录为设备本机概念、COS 作品目录与出处均为派生信息，导入端对空段零警告）；appPrefs 只带 recommendationPrefs（kv_settings 原样 JSON）。

