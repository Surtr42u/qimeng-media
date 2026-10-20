# API：媒体浏览、检索与直链

> [API 目录与全局约定](../GUIDE_API.md)。本页解释用法，字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；正文日期只标记机制来源。

## 库管理

端点：GET/POST /libraries、DELETE /libraries/{id}、PUT /libraries/{id}/enabled、POST /libraries/{id}/scan

注册媒体目录、触发全量扫描（进度走 SSE）；
POST 可选 `kind`（normal 默认 / cos——COS 作者库按 `作者/作品/文件` 目录结构扫描建 cos_ 作者，DOMAIN_RULES §6）；
DELETE 删库登记（级联清该库全部资产及关联——标签/作者关联/收藏/点赞/统计物化，事件流保留，磁盘文件与回收站不动，404=库不存在）；
PUT 启用/停用开关（enabled=false 仅从浏览列表/搜索/推荐隐藏，记录/事件流/统计/回收站/磁盘全保留，详情与签名直链不过滤，404=库不存在）；
POST 重复根路径 409 CONFLICT（UNIQUE(root_path)，服务端已实现，2026-09-20 审计清偿批 R6 补声明）

## 实时推送

端点：GET /events

SSE：scan.progress / library.changed / thumbnail.progress / upload.done / **favorite.changed / like.changed**（2026-10-01 ADR-0029：收藏/点赞变更通知，载荷同构 `{"assetId": string}`——写路径成功后发布、失败路径零发布、重复收藏幂等 no-op 照发；
**不 bump 修订号**，ADR-0026 语义边界不变；
事件是变更信号不是状态面，消费方收到后重拉权威状态；
运行时事件面不入 openapi，客户端按事件名解析——未知事件名按 SSE 规范忽略，零害）

## 资产浏览

端点：GET /assets、GET /assets/facets、GET/DELETE /assets/{id}、**PUT /assets/{id}/authors**、GET /sources、GET/PUT /sources/custom、GET/PUT /sources/custom-groups

唯一列表口径（筛选/排序/搜索全参数化；
**2026-09-09 协议批：`source`/`character`/`work` 多值化**——数组参数，**同维内 OR、与其余筛选维 AND**，`source` 可含「其他」桶，`character` 每元素仍为 `a+b` 组合出镜表达式，单值=单元素数组向后兼容；
详见 DOMAIN_RULES §3 多值段）；
2026-09-06 加 `directory` 目录过滤——库内相对目录精确匹配只含**直接子文件**（不含递归子目录），空串=库根、缺省=不过滤，文件管理页目录树「文件行」数据源，非法路径 400 INVALID_PARAM；
2026-09-03 加 `liked` 点赞筛选与 `sort=favoriteAt` 收藏时间排序、`cosOnly`/`work` 参数——COS 隔离升级三态开关，见「关键机制」；
**2026-09-05 起 `favorite=true` 缺省含 COS**〔收藏流缺省=常规∪COS，1A 拍板；
显式 includeCos=false 仍切常规、cosOnly 优先逻辑不变，非收藏流缺省排除不变〕）；
DELETE=进回收站；
**facets**（2026-09-03 新增，第五笔（历史档）语义修正）：相册四维筛选候选计数（partitions 恒三项 all/常规/COS、types 固定四项；
**作者行=常规出处分组 ∪ COS 作者**〔常规分区只出处、COS 分区只 COS 作者、all 合并；
出处分组排除 COS 关联资产，NULL source=「其他」桶；
常规 TXT 作者不进本行〕、**角色行**〔常规=角色名、COS=cos_work 作品名、all 合并〕），每候选带 **kind**（source/author/character/work）=回传哪个筛选参数；
**排自身口径**——计每维候选时忽略该维自身选择（前端每维独立请求各缺自身参数；
**2026-09-09 协议批起 source/authorId 对全部行照常收窄**——作者集合页固定传 authorId 即可让作品维候选按作者收窄）；
**子集约束**（2026-09-05 S-1）：`favorite=1`/`history=1` 非四维之一，传入时对全部四维含分区栏统一收窄（收藏页分区芯片=收藏子集内 all/常规/COS）；
出处列表（按规范名分组的文件计数，fileCount 降序，name=null=无出处文件，默认排除 COS）；
自定义出处整体替换（见「关键机制」）；
**2026-09-25 资产编辑批（ADR-0024）新增** PUT /assets/{id}/authors——整体替换资产作者关联（body `{authorIds}`，空数组=解除全部常规关联；
编辑页保存，同事务更新 asset_authors；
语义见「关键机制」作者挂靠编辑）

## 列表计数与执行边界

GET /assets 首屏返回 totalMatched；dateCounts=true 时同一次分组查询生成有效日期桶和总数，未知/非法 mtime 仍计入总数但不输出日期桶，时区偏移与既有日界口径不变。翻页不重复总数/分桶。无额外筛选的默认/日期页走现有索引，其他筛选严格回退完整查询；调用参数、游标和响应未变。策略与可重复性能验证见 [筛选专题](../domain/filtering.md#查询执行策略与性能边界)。

## 搜索补全

端点：GET /search/suggestions

2026-09-05 新增（LEGACY §C + 用户 3B 拍板）：搜索框输入补全，五维候选=出处/角色/COS 作者/COS 作品/常规作者；
q 子串匹配大小写不敏感（DOMAIN_RULES §3 口径）；
合并去重后**按名称长度升序再名称字典序**，同维同名去重、跨维同名各保留（`type` 字段=类型徽标分派）；
作者维只返回实际有关联文件的作者；
q 空串且未开 recommend 返回空列表；
**2026-09-05 增 `recommend` 参数**（true 且 q 空=随机五维候选 ≤limit 条，旧版「推荐搜索随机取 10 条」语义；
q 非空时忽略）；
limit 默认 10 上限 50

## 观看历史

端点：GET /history

2026-09-03 新增：每资产最近一次 open 事件时间倒序（每资产一条），点击产生；
排除已删资产；
cursor 分页；
响应 HistoryItem = AssetSummary + lastViewedAt。**2026-09-05 起 COS 缺省全部**（常规∪COS 合并，1A 拍板）：includeCos schema default=true，显式 false 切常规分区；
`cosOnly`/`work`/`character`/`mediaType` 筛选（与 GET /assets 同名参数同语义，历史子集内叠加）；
**2026-09-09 协议批：work/character 多值化（数组，同维内 OR、跨维 AND）+ 增可选 `source`/`authorId`**（#30，语义同 GET /assets 同名参数）

## 媒体文件

端点：GET /media/orig、/media/thumb（size=sm/md/lg）

HMAC 签名直链；
原图/视频支持 Range，查看始终发送原件，不按需缩放。缩略图缓存和旧版本 URL 的行为见本页「缩略图表示版本」。

## 排序

`sort` 八键（default/fileDate/addedDate/viewCount/playCount/sizeBytes/name/favoriteAt——favoriteAt=收藏时间，仅 favorite 筛选时语义成立）+ `order`，语义见 DOMAIN_RULES §3

## COS 隔离（三态开关，2026-09-03 起）

`includeCos=1`→全量（不限制）、`cosOnly=1`→只 COS、两者皆 0→常规（排除 COS 作者关联文件，历史默认）；
`cosOnly` 与 `includeCos` 同真时 **cosOnly 优先**。`work`（COS 作品名，migration 0008 `assets.cos_work` 列）与 `cosOnly` 组合即 COS 分区按作品筛。DOMAIN_RULES §6 隔离口径的端点面。**缺省口径差异（2026-09-05 用户拍板）**：/assets 非收藏流与 GET /sources、GET /recommendations 维持缺省排除；
**/history 与 /assets favorite=true 收藏流缺省改为全部**（常规∪COS 合并；
/history 的 includeCos schema default=true，/assets 的 default 保持 false——收藏流特例在服务端分支实现）

## COS 卡片标题字段 `cosWork`

（2026-09-05）：AssetSummary 新增可空字符串（`assets.cos_work`，migration 0008；
仅 COS 库扫描赋值）。`GET /assets` 与 `GET /recommendations`（含 cosOnly）出口经批量装配填充；
常规资产与无作品子目录（`作者/文件` 平铺）的 COS 资产为 null——客户端卡片标题优先取本字段、null 回退 fileName（用户口径：COS 卡显示作品文件夹名而非裸文件名）

## 详情 md 档直链 `thumbUrlMd`

（2026-09-18）：AssetDetail 新增可选字符串——md 档（512，与列表网格同源、开机回填唯一预生成档）签名直链，与详情 `thumbUrl`（lg 1024 最终档）并存。背景：lg 不在预生成范围，客户端首开详情直接挂 lg 会触发服务端现场 ffmpeg 全管线生成（秒级起步 + 生成队列排队）；
客户端海报先用本字段立即显示、lg 就绪后换上。`GET /assets/{id}` 恒填充

## 最近浏览时刻毫秒整型 `lastViewedAt`

（2026-09-20 审计清偿批 R8 响应侧根修）：AssetDetail.lastViewedAt 由 date-time 字符串统一为 int64 Unix 毫秒（nullable，null=从未浏览），与 HistoryItem.lastViewedAt 同型——原字符串面经全客户端检索零消费（Web 仅消费 HistoryItem 毫秒面、Android 未映射该字段），三端 SDK 同批再生无兼容面；
先例：lastPositionSeconds format double 根修

## 搜索与筛选叠加

`q`（FTS5 全文）与全部筛选参数同时生效。实现状态：M2 已实现（2026-08-29）——迁移 0002 建 FTS5 trigram 索引+聚合视图+触发器全集自动同步，`internal/search` 负责关键词解析与索引重建；
详细语义见 DOMAIN_RULES §3 全文搜索口径

## 出处/角色富化

（M3）：扫描入库与移动/重命名时服务端按文件名匹配 130 组内置出处表（`internal/sourcematcher`），写 `assets.source` 与 `asset_characters`；
未命中 source 为 NULL，显示层兜底"其他"。作者双体系（TXT 导入 + COS 目录扫描）见 DOMAIN_RULES §6，作者端点已全量接线（501 已清零）

## 自定义出处

（`GET/PUT /sources/custom`，M3 收尾）：用户手动添加的分区名自动加入识别（DOMAIN_RULES §4）。PUT 为整体替换，服务端规范化（trim+去空+去重+升序）后持久化到 kv_settings `custom_sources`，存储形态=匹配引擎生效形态；
空数组=清空。PUT 成功后：运行中匹配引擎立即替换，且服务端**后台自动对全部常规库资产重算出处/角色**（资产 size+mtime 未变时全量扫描只跳过，不显式重算 stock 数据不会更新）——重算完成发 `library.changed` 事件，客户端收到后刷新即可；
重算期间源/角色写以匹配引擎当前名单为准，与扫描并发不破坏最终一致性

## 自定义出处组（检索词表维护，2026-10-03，ADR-0033）

`GET/PUT /sources/custom-groups`——带角色检索表的词层（canonical+variants+characters），与内置 130 组及裸名层按 canonical **合并**（同名=并入扩变体/角色，新名=追加新组）。PUT 整体替换，服务端规范化（trim+去空+去重+重复 canonical 取首个+**canonical 自并入自身变体与别名**）后持久化 kv_settings `custom_source_groups`（存储形态=引擎输入形态）；
空数组=清空回退内置基线。生效与存量重算同「自定义出处」（引擎同步替换+后台全常规库重算+`library.changed`）。容量上限（组 256/变体 64/角色 128/别名 32/单串 100 rune）超限 400 INVALID_PARAM；
**词条含乱码替换符（U+FFFD）同样 400**（2026-10-03 加固：非 UTF-8 载荷被 JSON 解码器静默替换后落库=永不命中的死词条）。设计给 AI/运维随时补词条（如新角色），无需改代码重编译。**stopWords 可选字段（2026-10-03 停用词层）**：兜底提取跳过的描述词（触手/白丝类，内置基线 触手/白丝/婚纱/多角色/多角色酒吧 恒生效），GET 返回用户追加层；
PUT 缺省=保持现值、显式空数组=清空回基线（与 groups 整体替换语义不同，防忘带误清空）；
存储键 kv_settings.custom_stop_words，变更同样触发全库重算；
**2026-10-04 起 App 端消费本端点——「词表合并同步」（备份页一键：两端 GET → 客户端并集合并〔折叠键 union、同键组并入、变体/角色/别名/停用词并集去重，词表只增不删故合并无损〕→ 合并结果显式 PUT 回两端收敛同一并集，取代 0034 覆盖式下发，ADR-0035）与「词表维护」直接编辑（本机通道整体 PUT，ADR-0035），协议零变化**；
**同日 Web 端消费——「词表维护」子页**（用户拍板「移植到文件管理中，功能和 ui 布局和 app 的一致」：数据管理 hub 文件管理域 `/app/maintenance/files/vocabulary-edit`，GET 回显 + 内存编辑 + 整体 PUT + useBlocker 放弃确认，ADR-0035 web 移植；
编辑对象=**当前连接服务器**（标准鉴权），非 App 的本机通道；
与「来源词表」子页 /authors/source-vocabulary 是两张不同的表）**

## 缩略图表示版本

GET `/media/thumb/{assetId}` 新增表示版本 v：当前版本使用 immutable；旧版本 410/no-store，缺省版本 private/no-cache。表示版本随原件 size+mtime、策略、输出格式及配置变化；强 ETag 另含实际输出字节哈希，交由 ServeContent 处理条件请求。
