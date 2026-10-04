// import.go：旧版数据迁移端点（qimeng_backup.json → 新库，DOMAIN_RULES §10）。
//
// 映射总纲：作者/标签/关联/时间轴/收藏按唯一键 upsert（重复导入天然不翻倍）；
// mediaStats/dailyBrowse/history/likes/favorites 转换为 ViewEvent 回放与
// likes/favorites 行，统计从事件重建。批次标记 = kv_settings 的
// exportedAtMillis：同批次重复导入时事件回放整体跳过（快速路径；跨批次
// 幂等由事件内容键 client_event_id 保证，见 import_replay.go），段级
// upsert 照常执行。
//
// TXT 片段段（§10「TXT 片段」）在 authors/authorMediaRefs 段之前处理：
// 片段导入触发统一重建（重建只删「片段涉及作者」的关联），若放在后处理
// 位置会冲掉备份携带的 authorMediaRefs 关联；同名内容相同幂等跳过、内容
// 不同走 keep 保护（绝不 remove），单片段失败 Warn 跳过不中止整体。
//
// 事件回放口径（总量守恒，与旧库数字一致）：
//   - dailyBrowse 是旧库「每文件每天」明细（唯一真相源）→ 全量回放；
//   - mediaStats 只补差额（累计值 − dailyBrowse 之和，dailyBrowse 行缺失的
//     旧版本备份由此保总量）；history 仅当该文件在 dailyBrowse 无任何行时
//     回放（§10「截取导入」的补漏语义，避免与天明细重复计数）；
//   - 旧 likes 只有累计次数与最后点赞日，无法还原逐日记录 → 只落最后
//     一天一行，差额计入 warnings（热度口径按回放后 likes 行数计）。
//
// 段级去向不导入的：settings/scanSources（SAF 目录概念不存在，提示重新
// 注册库）、albumRules（新架构无对应能力）、appPrefs 除 recommendationPrefs
// 外的字段——全部进 warnings。
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"path"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

const (
	// 与 prefs.go 的 settingKeyRecommendPrefs 同包共用；本文件新增批次标记。
	// 批次锚点（快速路径）：值 = 上次成功导入的 exportedAtMillis（十进制
	// 字符串），同批次整体跳过回放；跨批次幂等由事件内容键保证。
	settingKeyLegacyImportBatch = "legacy_import_batch"

	// 回放事件会话标识：标记数据来源是旧备份迁移（区别于真实浏览会话）。
	legacyImportSession = "legacy-import"

	// 旧备份只有天粒度时间（dayStartMillis=当天零点），统一偏移到当天正午
	// 回放，避免按零点落事件时因时区解读差异漂到相邻日界；同日多条
	// open/play 再按 1 秒错开保持事件顺序可区分（按分钟错开在大 viewCount
	// 下会跨入相邻日界，秒级在日粒度聚合下无歧义）。
	replayNoonOffset = 12 * time.Hour
	replayStagger    = time.Second

	// 旧版备份导入的请求体上限：单文件全量 JSON 随库规模增长（实测 6325
	// 文件 ≈ 2.9MB，已超全局 maxJSONBody 1MB——用户实测表现为浏览器侧
	// "Failed to fetch"），故本端点单独放宽到 64MB（约可容纳数十万文件级
	// 库的备份，仍远低于上传通道的磁盘级上限）。
	// 同步责任：web/src/lib/backup.ts 的 BACKUP_MAX_BYTES 前置拦截与此双写，
	// 改动时两侧同步。SECURITY 红线 6 的显式例外。
	legacyImportMaxBody = 64 << 20
)

// PostApiV1ImportQimengBackup 迁移主流程：匹配→TXT 片段→作者→标签→事件
// 回放→点赞收藏→时间轴→关注→偏好→warnings。任一数据库错误即中止（500），
// 幂等性保证修复后重跑不翻倍。TXT 片段段必须在 authors/authorMediaRefs 段
// 之前（§10「TXT 片段」：片段导入触发统一重建，后处理会冲掉备份携带的
// 关联数据）。
func (s *Server) PostApiV1ImportQimengBackup(w http.ResponseWriter, r *http.Request) { //nolint:revive // 生成接口要求的方法名
	var req gen.LegacyBackupImport
	if !decodeJSONWithLimit(w, r, &req, legacyImportMaxBody) {
		return
	}
	if req.Format != legacyBackupFormat { // 常量定义在 export.go（同包共用，单一来源）
		writeErr(w, http.StatusBadRequest, codeBadRequest, "format 必须为 qimeng_backup")
		return
	}

	imp := &legacyImport{s: s, ctx: r.Context(), w: w}
	imp.matchFiles(req.Data.MediaFiles)
	imp.importTxtFragments(req.Data.TxtFragments)
	imp.importAuthors(req.Data.Authors, req.Data.AuthorMediaRefs)
	imp.importTags(req.Data.Tags, req.Data.MediaTagRefs)
	imp.replayEvents(&req)
	imp.importLikesFavorites(req.Data.Likes, req.Data.Favorites)
	imp.importTimelineTags(req.Data.TimelineTags)
	imp.markFollowed(req.Data.FollowedAuthorIds)
	imp.importPrefs(req.Data.AppPrefs)
	imp.staticWarnings(req.Data)

	for _, warn := range imp.warnings {
		s.logger.Warn("旧版迁移提示", "warning", warn)
	}
	if !imp.aborted {
		imp.res.Warnings = &imp.warnings // 始终非 nil，空时序列化为 []
		// 全量导入改变了资产/行为/标签等推荐输入，推荐缓存失效
		s.invalidateRecommendCache()
		// 导入不发布 library.changed（与推荐缓存同款直调口径），修订号在
		// 此显式推进（revision.go 的 bump 链清单）。
		s.bumpLibraryRevision()
		writeJSON(w, http.StatusOK, imp.res)
	}
}

// legacyImport 聚拢迁移过程中的共享状态：库句柄、响应器、资产映射、
// 结果累计。aborted 置位后各段直接跳过（错误已写入响应）。
type legacyImport struct {
	s          *Server
	ctx        context.Context
	w          http.ResponseWriter
	aborted    bool
	res        gen.LegacyImportResult
	warnings   []string          // 累积提示，结束时回填 res.Warnings
	keyToAsset map[string]string // recordKey → 匹配到的 asset_id
	// recordKey → 备份侧标签组改动毫秒（§10 标签组同步语义；nil = 备份未带
	// 时间 = 未知，导入端回退并集合并）。
	keyToTagsTime map[string]*int64
}

// fail 写 500 并置中止标记（后续段跳过；幂等保证修复后重跑不翻倍）。
func (imp *legacyImport) fail(stage string, err error) {
	imp.aborted = true
	imp.s.internalErr(imp.w, stage, err)
}

// warn 追加一条提示（去重：同文案只记一次，回放差额类提示按类聚合）。
func (imp *legacyImport) warn(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	for _, w := range imp.warnings {
		if w == msg {
			return
		}
	}
	imp.warnings = append(imp.warnings, msg)
}

// val / val64 可选计数解引用（生成物把未标 required 的数字字段全部生成为
// 指针；旧备份「可选字段缺省」语义统一按零值处理）。
func val(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

func val64(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}

// sliceOrEmpty nil 指针安全解引用（旧备份可选段缺省语义，协议约定
// 导入端不假设全字段存在）。
func sliceOrEmpty[T any](p *[]T) []T {
	if p == nil {
		return nil
	}
	return *p
}

// nowOrMillis 可选毫秒时间戳转存储格式；nil 或 ≤0 一律回退 now。≤0 与缺省
// 同义：旧备份"未携带"的哨兵形态（0/负值是坏值不是 1970 纪元）——回退导入
// 时刻（真实入账事件）。否则会落 1970 字面量，与 asset_tags.created_at 的
// epoch「不可考」哨兵（migration 0004）混淆成"可考章+纪元时间"的自相矛盾行，
// 违反 DOMAIN_RULES §10 / ADR-0032 裁决①（时间缺省回退导入时刻）。
func nowOrMillis(m *int64, now time.Time) string {
	if m == nil || *m <= 0 {
		return store.FormatTimestamp(now)
	}
	return store.FormatTimestamp(time.UnixMilli(*m))
}

// matchFiles 按文件名精确匹配建 recordKey→asset 映射（§10）。同名多命中
// 时用旧 folderName 与 rel_path 的父目录段消歧，仍无法区分取最早入库行。
// R1（审计 2026-09-20）：旧实现按备份文件逐条 ListAssetsByFileName 查库
// （N+1，数千文件=数千次 SQL）；改一次全表建 fileName→行集 内存索引，
// 消歧与 tie-break 语义不变（ORDER BY created_at 保持最早入库行优先）。
func (imp *legacyImport) matchFiles(files *[]gen.LegacyMediaFile) {
	if imp.aborted {
		return
	}
	list := sliceOrEmpty(files)
	imp.res.MediaFilesTotal = ptr(len(list))
	imp.keyToAsset = make(map[string]string, len(list))
	imp.keyToTagsTime = make(map[string]*int64, len(list))
	rows, err := imp.s.q.ListAssetNameIndex(imp.ctx)
	if err != nil {
		imp.fail("按文件名匹配资产", err)
		return
	}
	byName := make(map[string][]db.ListAssetNameIndexRow, len(rows))
	for _, row := range rows {
		byName[row.FileName] = append(byName[row.FileName], row)
	}
	for _, f := range list {
		if assetID := pickAssetByFolder(byName[f.FileName], f.FolderName); assetID != "" {
			imp.keyToAsset[f.RecordKey] = assetID
		}
		imp.keyToTagsTime[f.RecordKey] = f.TagsUpdatedAtMillis
	}
	imp.res.AssetsMatched = ptr(len(imp.keyToAsset))
}

// pickAssetByFolder 唯一命中直接用；多命中优先父目录段等于旧 folderName
// 的行（recordKey 规则「文件名 @ 文件夹」的等价消歧）。
func pickAssetByFolder(rows []db.ListAssetNameIndexRow, folderName *string) string {
	switch {
	case len(rows) == 0:
		return ""
	case len(rows) == 1:
		return rows[0].AssetID
	}
	if folderName != nil {
		for _, row := range rows {
			if path.Base(path.Dir(row.RelPath)) == *folderName {
				return row.AssetID
			}
		}
	}
	return rows[0].AssetID
}

// importSegment 段级事务骨架（WithTx 模式照抄同包 import_replay.go 先例）：
// fn 拿到事务绑定的 Queries 执行整段写入，段内任一语句失败经 imp.fail 记
// 段名返回、整段回滚；全部成功才提交。**段级原子而非整个导入一个事务**：
// 段间独立（作者段成功、标签段失败重跑只补标签段），配合各段幂等 upsert
// 重跑不翻倍。fn 内失败沿用各段既有的 fail 段名（哪段失败报哪段不变），
// 本函数只补开/提交两个新事务边界段名。importTxtFragments 不走本骨架：
// importTxt 内部自开事务（authors.go），外层再包会同连接嵌套取写锁自死锁
// （_txlock=immediate + busy_timeout 等自己），且单片段失败本就是跳过不中止。
func (imp *legacyImport) importSegment(stage string, fn func(qx *db.Queries)) {
	if imp.aborted {
		return
	}
	tx, err := imp.s.conn.BeginTx(imp.ctx, nil)
	if err != nil {
		imp.fail("开启"+stage+"事务", err)
		return
	}
	defer func() { _ = tx.Rollback() }() // 提交成功后 Rollback 是无害 no-op
	fn(imp.s.q.WithTx(tx))
	if imp.aborted {
		return
	}
	if err := tx.Commit(); err != nil {
		imp.fail("提交"+stage+"事务", err)
	}
}

// importAuthors：作者 upsert（authorId 原样保留含 cos_ 前缀，type 由前缀
// 推导，§6/§10）+ 关联写入（资产未匹配的计入 skipped）。整段单事务（原先
// 逐行 autocommit，中途失败会留下作者已建而关联缺失的半段状态，重跑虽可
// 幂等补齐但中间窗口内读侧看到半成品；段级原子后窗口消除）。
func (imp *legacyImport) importAuthors(authors *[]gen.LegacyAuthor, refs *[]gen.LegacyAuthorMediaRef) {
	imp.importSegment("作者", func(qx *db.Queries) {
		for _, a := range sliceOrEmpty(authors) {
			authorType := "regular"
			if hasCosPrefix(a.AuthorId) {
				authorType = "cos"
			}
			err := qx.ImportUpsertAuthor(imp.ctx, db.ImportUpsertAuthorParams{
				ID: a.AuthorId, DisplayName: a.DisplayName, Type: authorType,
				CreatedAt: nowOrMillis(a.CreatedAtMillis, imp.s.now()),
				// 溯源（ADR-0032）：透传对端原始来历，缺省/非法兜底 import；
				// 既有行仅在 legacy（不可考）时被补证（DOMAIN_RULES §10，查询内裁决）。
				Origin: store.NormalizeOrigin(a.Origin),
			})
			if err != nil {
				imp.fail("导入作者", err)
				return
			}
			imp.bump(&imp.res.AuthorsImported)
		}
		for _, ref := range sliceOrEmpty(refs) {
			assetID, ok := imp.keyToAsset[ref.RecordKey]
			if !ok {
				imp.bump(&imp.res.AuthorRefsSkipped)
				continue
			}
			err := qx.ImportAddAssetAuthor(imp.ctx, db.ImportAddAssetAuthorParams{
				AssetID: assetID, AuthorID: ref.AuthorId,
				CreatedAt: store.NullTimestamp(nowOrMillis(ref.CreatedAtMillis, imp.s.now())),
				Origin:    store.NormalizeOrigin(ref.Origin),
			})
			if err != nil {
				imp.fail("导入作者关联", err)
				return
			}
			imp.bump(&imp.res.AuthorRefsImported)
		}
	})
}

// hasCosPrefix：COS 作者 id 约定前缀（0001 表注释 / DOMAIN_RULES §6）。
func hasCosPrefix(id string) bool {
	const prefix = "cos_"
	return len(id) >= len(prefix) && id[:len(prefix)] == prefix
}

// importTxtFragments TXT 片段段（§10「TXT 片段」，调用点在 authors/
// authorMediaRefs 段之前）：逐片段合并——目标库无同名片段 → 直接导入；
// 同名且内容相同 → 幂等跳过；同名但内容不同 → 走 import-txt 的 keep 语义
// （目标端上传写入条目并回后替换重建，绝不 remove）。单片段失败记 Warn 跳
// 过，不中止整体导入（对齐备份导入对未匹配数据的宽容姿态）。旧备份无该段
// （nil）→ 零处理、响应计数缺省（向后兼容）。
func (imp *legacyImport) importTxtFragments(frags *[]gen.LegacyTxtFragment) {
	if imp.aborted || frags == nil {
		return
	}
	existing, err := authorattach.LoadSources(imp.ctx, imp.s.q)
	if err != nil {
		imp.fail("读取已导入 TXT 片段", err)
		return
	}
	byName := make(map[string]string, len(existing))
	for _, src := range existing {
		byName[src.Filename] = src.Content
	}
	imported, skipped := 0, 0
	for _, f := range *frags {
		if content, ok := byName[f.Filename]; ok && content == f.Content {
			skipped++
			continue
		}
		filename := f.Filename
		// 溯源章（ADR-0032）：备份 TXT 片段段触发的重建盖 import——关联
		// 在本端的确立通道是备份导入（对端原始来历随 authorMediaRefs 段
		// 透传，见 importAuthors）。
		if _, err := imp.s.importTxt(imp.ctx, &filename, f.Content, resolutionKeep, store.OriginImport); err != nil {
			imp.s.logger.Warn("备份 TXT 片段导入失败，跳过", "filename", f.Filename, "error", err)
			skipped++
			continue
		}
		imported++
	}
	imp.res.TxtFragmentsImported = &imported
	imp.res.TxtFragmentsSkipped = &skipped
}

// bump 计数指针安全自增（生成物字段全部 *int 可选）。
func (imp *legacyImport) bump(p **int) {
	if *p == nil {
		*p = ptr(0)
	}
	**p++
}

// importTags：按 name 去重建标签（旧 tagId 忽略）+ 关联写入（§10 标签组同步
// 语义）。关联按资产聚合后逐资产判定：备份侧时间与库内 tag_set_updated_at 都
// 已知且备份较新 → 该资产标签组整体替换（清空重挂 + 库内时间改写为备份时间）；
// 其余情况并集合并（只增不删）且不改库内时间——并集结果没有单一来源时刻，
// 宁可保留「未知」也不造假版本。整段单事务（替换路径的清空+重挂+改时间是
// 三步联动写，autocommit 下中途失败会留下清空未挂回的半段状态）。
func (imp *legacyImport) importTags(tags *[]gen.LegacyTag, refs *[]gen.LegacyMediaTagRef) {
	imp.importSegment("标签", func(qx *db.Queries) {
		tagIDs := make(map[string]string, len(sliceOrEmpty(tags)))
		for _, t := range sliceOrEmpty(tags) {
			id, err := imp.upsertTag(qx, t)
			if err != nil {
				imp.fail("导入标签", err)
				return
			}
			tagIDs[t.Name] = id
		}
		// 按资产聚合备份关联：同一资产的多个 ref 可能来自不同 recordKey（同名
		// 消歧变体），备份时间取已知值中的最大者（新者生效，保守不丢新改动）。
		type assetPlan struct {
			refs     []gen.LegacyMediaTagRef
			backupMs int64
			hasTime  bool
		}
		plans := make(map[string]*assetPlan)
		for _, ref := range sliceOrEmpty(refs) {
			assetID, okAsset := imp.keyToAsset[ref.RecordKey]
			_, okTag := tagIDs[ref.TagName]
			if !okAsset || !okTag {
				imp.bump(&imp.res.TagRefsSkipped)
				continue
			}
			plan := plans[assetID]
			if plan == nil {
				plan = &assetPlan{}
				plans[assetID] = plan
			}
			plan.refs = append(plan.refs, ref)
			if ms := imp.keyToTagsTime[ref.RecordKey]; ms != nil && *ms > 0 {
				if !plan.hasTime || *ms > plan.backupMs {
					plan.backupMs = *ms
				}
				plan.hasTime = true
			}
		}
		now := imp.s.now()
		for assetID, plan := range plans {
			server, err := qx.GetAsset(imp.ctx, assetID)
			if err != nil {
				imp.fail("查询资产标签组时间", err)
				return
			}
			// 库内时间已知性：'' 哨兵或解析失败一律视为未知（§10 回退并集）。
			serverKnown := false
			var serverMs int64
			if server.TagSetUpdatedAt != "" {
				if ms, err := millisOf(server.TagSetUpdatedAt); err == nil {
					serverKnown, serverMs = true, ms
				}
			}
			if !plan.hasTime || !serverKnown || plan.backupMs <= serverMs {
				// 并集合并（缺省路径）：只增不删，不改库内时间。
				for _, ref := range plan.refs {
					err := qx.ImportAddAssetTag(imp.ctx, db.ImportAddAssetTagParams{
						AssetID: assetID, TagID: tagIDs[ref.TagName],
						CreatedAt: nowOrMillis(ref.CreatedAtMillis, now),
						Origin:    store.NormalizeOrigin(ref.Origin),
					})
					if err != nil {
						imp.fail("导入标签关联", err)
						return
					}
					imp.bump(&imp.res.TagRefsImported)
				}
				continue
			}
			// 备份较新：整体替换（清空重挂；时间改写为备份时刻——同备份重导时
			// 备份时间 == 库内时间，落回并集路径，幂等性不破）。
			if err := qx.DeleteAssetTags(imp.ctx, assetID); err != nil {
				imp.fail("替换标签组清空", err)
				return
			}
			for _, ref := range plan.refs {
				err := qx.ImportAddAssetTag(imp.ctx, db.ImportAddAssetTagParams{
					AssetID: assetID, TagID: tagIDs[ref.TagName],
					CreatedAt: nowOrMillis(ref.CreatedAtMillis, now),
					Origin:    store.NormalizeOrigin(ref.Origin),
				})
				if err != nil {
					imp.fail("替换标签组挂载", err)
					return
				}
				imp.bump(&imp.res.TagRefsImported)
			}
			if err := qx.TouchAssetTagSet(imp.ctx, db.TouchAssetTagSetParams{
				AssetID:         assetID,
				TagSetUpdatedAt: store.FormatTimestamp(time.UnixMilli(plan.backupMs)),
			}); err != nil {
				imp.fail("改写标签组改动时间", err)
				return
			}
			imp.bump(&imp.res.AssetsTagsReplaced)
		}
	})
}

// upsertTag 按 name 查重，缺则建（id 用新 UUID，旧 tagId 不迁移）。
// qx 是段事务绑定的 Queries（importTags 整段单事务，见 importSegment）。
func (imp *legacyImport) upsertTag(qx *db.Queries, t gen.LegacyTag) (string, error) {
	existing, err := qx.GetTagByName(imp.ctx, t.Name)
	if err == nil {
		return existing.ID, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	created, err := qx.CreateTag(imp.ctx, db.CreateTagParams{
		ID: uuid.NewString(), Name: t.Name,
		CreatedAt: nowOrMillis(t.CreatedAtMillis, imp.s.now()),
	})
	if err != nil {
		return "", err
	}
	imp.bump(&imp.res.TagsImported)
	return created.ID, nil
}

// importLikesFavorites：likes 落最后点赞日一行（累计次数差额进 warnings，
// 旧备份只有 lastLikeDate 无逐日记录）；favorites 幂等 upsert。整段单事务。
func (imp *legacyImport) importLikesFavorites(likes *[]gen.LegacyLike, favorites *[]string) {
	imp.importSegment("点赞收藏", func(qx *db.Queries) {
		largeLike := 0
		for _, l := range sliceOrEmpty(likes) {
			assetID := imp.keyToAsset[l.RecordKey]
			if assetID == "" {
				continue
			}
			if l.LastLikeDate != nil && *l.LastLikeDate != "" {
				err := qx.ImportAddLike(imp.ctx, db.ImportAddLikeParams{
					AssetID: assetID, Day: *l.LastLikeDate,
					CreatedAt: store.FormatTimestamp(imp.s.now()),
				})
				if err != nil {
					imp.fail("导入点赞", err)
					return
				}
				imp.bump(&imp.res.LikesImported)
			}
			if l.LikeCount != nil && *l.LikeCount > 1 {
				largeLike++
			}
		}
		if largeLike > 0 {
			imp.warn("%d 个文件的累计点赞次数无法逐日还原（旧备份仅有最后点赞日），热度口径按回放后的点赞行数计", largeLike)
		}
		for _, key := range sliceOrEmpty(favorites) {
			assetID := imp.keyToAsset[key]
			if assetID == "" {
				continue
			}
			if _, err := qx.AddFavorite(imp.ctx, db.AddFavoriteParams{
				AssetID: assetID, CreatedAt: store.FormatTimestamp(imp.s.now()),
			}); err != nil {
				imp.fail("导入收藏", err)
				return
			}
			imp.bump(&imp.res.FavoritesImported)
		}
	})
}

// importTimelineTags：时间轴标签（§10 timelineTags → timeline_tags）。
// 整段单事务。
func (imp *legacyImport) importTimelineTags(tags *[]gen.LegacyTimelineTag) {
	imp.importSegment("时间轴标签", func(qx *db.Queries) {
		for _, t := range sliceOrEmpty(tags) {
			assetID := imp.keyToAsset[t.RecordKey]
			if assetID == "" {
				continue
			}
			createdAt := nowOrMillis(t.CreatedAtMillis, imp.s.now())
			if err := qx.ImportInsertTimelineTag(imp.ctx, db.ImportInsertTimelineTagParams{
				ID: uuid.NewString(), AssetID: assetID, TimeMillis: t.TimeMillis,
				Name: t.Name, CreatedAt: createdAt,
				// sqlc 对 WHERE 重复列生成 _2 字段：内容去重键 = (asset, time, name)
				AssetID_2: assetID, TimeMillis_2: t.TimeMillis, Name_2: t.Name,
			}); err != nil {
				imp.fail("导入时间轴标签", err)
				return
			}
			imp.bump(&imp.res.TimelineTagsImported)
		}
	})
}

// markFollowed：关注标记（§6 布尔量；未知 authorId 计数进 warnings）。
// 整段单事务。
func (imp *legacyImport) markFollowed(ids *[]string) {
	imp.importSegment("关注标记", func(qx *db.Queries) {
		unknown := 0
		for _, id := range sliceOrEmpty(ids) {
			affected, err := qx.ImportMarkAuthorFollowed(imp.ctx, id)
			if err != nil {
				imp.fail("标记关注作者", err)
				return
			}
			if affected > 0 {
				imp.bump(&imp.res.FollowedAuthorsMarked)
			} else {
				unknown++
			}
		}
		if unknown > 0 {
			imp.warn("%d 个关注 authorId 未匹配到作者（备份 authors 段缺失该 id），已忽略", unknown)
		}
	})
}

// importPrefs：recommendationPrefs 写入 kv_settings（与 PUT /recommendations/
// prefs 同键同格式）；appPrefs 其余字段忽略。
func (imp *legacyImport) importPrefs(prefs *gen.LegacyAppPrefs) {
	if imp.aborted || prefs == nil || prefs.RecommendationPrefs == nil {
		return
	}
	raw, err := json.Marshal(prefs.RecommendationPrefs)
	if err != nil {
		imp.fail("序列化推荐偏好", err)
		return
	}
	err = imp.s.q.UpsertSetting(imp.ctx, db.UpsertSettingParams{
		Key: settingKeyRecommendPrefs, Value: string(raw),
		UpdatedAt: store.FormatTimestamp(imp.s.now()),
	})
	if err != nil {
		imp.fail("导入推荐偏好", err)
		return
	}
	imp.res.PrefsImported = ptr(true)
}

// staticWarnings：不导入段的提示（§10：settings/scanSources 提示重新配置；
// albumRules 无对应能力；TXT 分片引导走 import-txt 端点）。
func (imp *legacyImport) staticWarnings(data gen.LegacyBackupData) {
	if n := len(sliceOrEmpty(data.ScanSources)); n > 0 {
		imp.warn("scanSources %d 条不导入（SAF 目录概念不存在），请重新注册媒体库；旧 COS 目录请以 kind=cos 注册", n)
	}
	if n := len(sliceOrEmpty(data.Settings)); n > 0 {
		imp.warn("settings %d 条不导入；其中 TXT 分片请通过 POST /authors/import-txt 重新导入以重建作者关联", n)
	}
	if n := len(sliceOrEmpty(data.AlbumRules)); n > 0 {
		imp.warn("albumRules %d 条忽略：新架构无对应能力", n)
	}
	if n := len(sliceOrEmpty(data.CosWorks)); n > 0 {
		imp.warn("cosWorks %d 条不逐条导入（作品为 COS 目录派生信息，无文件级映射可迁）；COS 作者已按 authors 段建立，文件关联请以 kind=cos 重新注册库并扫描重建", n)
	}
	imp.warn("库目录配置需重新确认：请核对注册库根路径与旧扫描目录的对应关系")
}
