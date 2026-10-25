// export.go：便携 v2 备份导出（新库 → qimeng_backup.json，DOMAIN_RULES §10
// 映射见当前 OpenAPI/DOMAIN_RULES §10；旧 DATA_MIGRATION_SPEC 仅作 v1 历史依据）。
//
// 段级口径（与 import.go 的导入映射互为镜像，回环经 POST /import/qimeng-backup
// 身份合并；v1 聚合回放仅用于兼容）：
//   - mediaFiles：全量资产；recordKey 全份唯一，v2 结合资产ID/相对路径/库名安全匹配
//     （文件名唯一 = 文件名；同名 = 「文件名 @ 文件夹名」；同名同文件夹
//     （跨库）再追加 #资产ID#序号）——folderName 恒取 rel_path 父目录段，保证
//     v1 旧工具仍可读取 folderName，v2 歧义跳过；
//   - dailyBrowse：取事件流物化表（asset x day，唯一真相源是 view_events，
//     物化表是其派生缓存）；mediaStats：事件流按资产聚合（open/play 计数、
//     dwell 秒数、最后打开时刻）；
//   - history：全部 open 事件倒序（2026-10-04 起无上限，原 500 条兼容截取废止）；
//   - likes：按点赞行聚合为 { 累计次数, 最后点赞日 }（新库行 = 资产 x 日，
//     旧格式无逐日明细，COUNT(*) 即最接近的累计值）；
//   - txtFragments：kv imported_txt_sources 全量逐字导出（DOMAIN_RULES
//     §10「TXT 片段」；导入端在 authors/authorMediaRefs 段之前按同名合并）；
//   - settings/scanSources/albumRules/cosWorks：恒导出空数组（SAF 目录是设备
//     本机概念、COS 作品目录与出处均为派生信息，导入端对空段零警告）；
//   - appPrefs：只带 recommendationPrefs（kv_settings 原样 JSON，与导入端
//     importPrefs 同键）。
package portablebackup

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"path"
	"sort"
	"strings"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

const (
	// 旧格式信封常量（DATA_MIGRATION_SPEC §3）。Format 与
	// import.go 的请求校验共用一处定义（代码卫生：同一字面量单一来源）。
	Format              = "qimeng_backup"
	legacyAppIdentifier = "com.qimeng.media"
	// 偏好对象自身的格式号，与备份信封schemaVersion独立。
	prefsFormatVersion = 1
	// v2 保留事件身份；协议侧 BackupData.viewEvents 改动须同步此处。
	SchemaVersion = 2

	// history 段已无上限（2026-10-04 用户拍板：所有数据长期持久，完整 open 事件随
	// 备份携带；原「最近 500 条对齐旧库 view_history」截取废止）——导出口径单一来源
	// 在 store/queries/legacy_export.sql（ExportRecentOpenEvents，无 LIMIT 全量倒序），
	// SQL 侧改动须同步彼处注释。

	// recordKey 同名消歧分隔符（旧规则「文件名 @ 文件夹名」与「#资产ID#序号」）。
	recordKeySep     = " @ "
	recordKeyHashSep = "#"
)

// backupExport 聚拢导出过程中的共享状态与查询句柄。
type backupExport struct {
	s   *Service
	ctx context.Context
	q   *db.Queries
}

// exportAsset 是资产行 + 派生 recordKey 的绑定（各段都经它取关联键）。
type exportAsset struct {
	row       db.ExportListAssetsRow
	recordKey string
}

// build 从同一事务快照组装备份，任一查询错误即中止（500，可重试）。
func (exp *backupExport) build() (*BackupFile, error) {
	tx, err := exp.s.conn.BeginTx(exp.ctx, nil)
	if err != nil {
		return nil, err
	}
	defer func() { exp.s.rollbackTx(exp.ctx, "导出备份快照", tx) }()
	exp.q = exp.s.q.WithTx(tx)
	file, err := exp.buildSnapshot()
	if err != nil {
		return nil, err
	}
	return file, tx.Commit()
}

func (exp *backupExport) buildSnapshot() (*BackupFile, error) {
	assets, err := exp.loadAssets()
	if err != nil {
		return nil, err
	}
	byID := make(map[string]*exportAsset, len(assets))
	for _, a := range assets {
		byID[a.row.AssetID] = a
	}

	data := BackupData{}
	if err := exp.fillFiles(&data, assets); err != nil {
		return nil, err
	}
	if err := exp.fillAuthors(&data, byID); err != nil {
		return nil, err
	}
	if err := exp.fillTxtFragments(&data); err != nil {
		return nil, err
	}
	if err := exp.fillTags(&data, byID); err != nil {
		return nil, err
	}
	if err := exp.fillTimelineTags(&data, byID); err != nil {
		return nil, err
	}
	if err := exp.fillLikesFavorites(&data, byID); err != nil {
		return nil, err
	}
	if err := exp.fillStats(&data, byID); err != nil {
		return nil, err
	}
	exp.fillPrefs(&data)
	// 新架构无对应能力的段恒空数组：旧版 App 恢复按缺段容错，导入端对空段零警告。
	data.Settings = &[]Setting{}
	data.ScanSources = &[]ScanSource{}
	data.AlbumRules = &[]map[string]interface{}{}
	data.CosWorks = &[]CosWork{}

	return &BackupFile{
		Format:           Format,
		SchemaVersion:    SchemaVersion,
		AppIdentifier:    legacyAppIdentifier,
		ExportedAtMillis: ptr(exp.s.now().UnixMilli()),
		Data:             data,
	}, nil
}

// assignRecordKeys 以资产 ID 排序，输入行顺序变化不影响结果；每次追加后复查全局唯一性。
func assignRecordKeys(rows []db.ExportListAssetsRow) []*exportAsset {
	rows = append([]db.ExportListAssetsRow(nil), rows...)
	sort.Slice(rows, func(i, j int) bool { return rows[i].AssetID < rows[j].AssetID })
	counts := make(map[string]int, len(rows))
	for _, r := range rows {
		counts[r.FileName]++
	}
	seen := make(map[string]bool, len(rows))
	assets := make([]*exportAsset, 0, len(rows))
	for _, r := range rows {
		key := r.FileName
		if counts[r.FileName] > 1 {
			key = r.FileName + recordKeySep + path.Base(path.Dir(r.RelPath))
		}
		base := key
		// 每次追加都复查全局碰撞（文件名本身也可能包含分隔符）。
		for n := 1; seen[key]; n++ {
			key = base + recordKeyHashSep + r.AssetID + recordKeyHashSep + fmt.Sprint(n)
		}
		seen[key] = true
		assets = append(assets, &exportAsset{row: r, recordKey: key})
	}
	return assets
}

// loadAssets 拉全量资产行并派生 recordKey。
func (exp *backupExport) loadAssets() ([]*exportAsset, error) {
	rows, err := exp.q.ExportListAssets(exp.ctx)
	if err != nil {
		return nil, fmt.Errorf("查询资产全量: %w", err)
	}
	return assignRecordKeys(rows), nil
}

// millisOf 库内统一时间戳 → 毫秒；兼容 RFC3339Nano 兜底（测试造数走该格式）。
func millisOf(ts string) (int64, error) {
	t, err := time.Parse(store.TimestampLayout, ts)
	if err != nil {
		t, err = time.Parse(time.RFC3339Nano, ts)
	}
	if err != nil {
		return 0, err
	}
	return t.UnixMilli(), nil
}

// dayMillis 「YYYY-MM-DD」（服务器本地时区日界）→ 当天零点毫秒。
// 回环校验：导入侧按 UnixMilli 落事件再 FormatDay 取本地日界，恒还原同一天。
func dayMillis(day string) (int64, error) {
	t, err := time.ParseInLocation(store.DayLayout, day, time.Local)
	if err != nil {
		return 0, err
	}
	return t.UnixMilli(), nil
}

// textOrNull sqlc 对 MAX(TEXT) 生成 interface{}；运行时值只有 string/nil 两种。
func textOrNull(v interface{}) *string {
	s, ok := v.(string)
	if !ok || s == "" {
		return nil
	}
	return &s
}

// fillFiles mediaFiles 段：全量资产 + 派生 recordKey / folderName / 类型枚举。
func (exp *backupExport) fillFiles(data *BackupData, assets []*exportAsset) error {
	files := make([]MediaFile, 0, len(assets))
	for _, a := range assets {
		mtime, err := millisOf(a.row.Mtime)
		if err != nil {
			return fmt.Errorf("资产 %s mtime 解析: %w", a.row.AssetID, err)
		}
		indexedAt, err := millisOf(a.row.CreatedAt)
		if err != nil {
			return fmt.Errorf("资产 %s created_at 解析: %w", a.row.AssetID, err)
		}
		folder := path.Base(path.Dir(a.row.RelPath))
		isDup := strings.Contains(a.recordKey, recordKeySep)
		// 标签组改动时间（§10 标签组同步语义）：'' 哨兵（旧数据/未知）→ 字段缺省，
		// 导入端按未知回退并集合并，不参与新旧判定。
		var tagsAt *int64
		if a.row.TagSetUpdatedAt != "" {
			ms, err := millisOf(a.row.TagSetUpdatedAt)
			if err != nil {
				return fmt.Errorf("资产 %s tag_set_updated_at 解析: %w", a.row.AssetID, err)
			}
			tagsAt = &ms
		}
		files = append(files, MediaFile{
			AssetId: &a.row.AssetID, LibraryName: &a.row.LibraryName, RelPath: &a.row.RelPath,
			RecordKey:           a.recordKey,
			FileName:            a.row.FileName,
			DisplayName:         ptr(strings.TrimSuffix(a.row.FileName, path.Ext(a.row.FileName))),
			Extension:           ptr(strings.TrimPrefix(path.Ext(a.row.FileName), ".")),
			MediaType:           MediaFileMediaType(a.row.MediaType),
			FolderName:          &folder,
			SizeBytes:           a.row.SizeBytes,
			ModifiedAtMillis:    mtime,
			Width:               nullIntToPtr(a.row.Width),
			Height:              nullIntToPtr(a.row.Height),
			DurationMillis:      nullInt64ToPtr(a.row.DurationMs),
			IsDuplicateName:     ptr(isDup),
			IsCosFile:           ptr(a.row.LibraryKind == "cos"),
			IndexedAtMillis:     &indexedAt,
			TagsUpdatedAtMillis: tagsAt,
		})
	}
	data.MediaFiles = &files
	return nil
}

// fillAuthors 作者 + 关联 + 关注段（关联按 asset_authors 全量导出；新架构
// 无 isMatched 来源标记，缺省即旧版可选语义）。
func (exp *backupExport) fillAuthors(data *BackupData, byID map[string]*exportAsset) error {
	authorRows, err := exp.q.ExportAuthors(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询作者: %w", err)
	}
	authors := make([]Author, 0, len(authorRows))
	for _, r := range authorRows {
		createdAt, err := millisOf(r.CreatedAt)
		if err != nil {
			return fmt.Errorf("作者 %s created_at 解析: %w", r.ID, err)
		}
		authors = append(authors, Author{
			AuthorId:        r.ID,
			DisplayName:     r.DisplayName,
			CreatedAtMillis: &createdAt,
			Origin:          &r.Origin, // 溯源透传（ADR-0032）：原始通道原样带出
		})
	}
	data.Authors = &authors

	followed, err := exp.q.ExportFollowedAuthorIDs(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询关注作者: %w", err)
	}
	data.FollowedAuthorIds = &followed

	refRows, err := exp.q.ExportAssetAuthors(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询作者关联: %w", err)
	}
	refs := make([]AuthorMediaRef, 0, len(refRows))
	for _, r := range refRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		ref := AuthorMediaRef{
			AuthorId:  r.AuthorID,
			RecordKey: a.recordKey,
			FileName:  a.row.FileName,
			Origin:    &r.Origin, // 溯源透传（ADR-0032）
		}
		// created_at 可空（0016 存量行 NULL=不可考）：缺省字段=对端未知，
		// 导入端回退其导入时刻（DOMAIN_RULES §10）。
		if r.CreatedAt.Valid {
			if ms, err := millisOf(r.CreatedAt.String); err == nil {
				ref.CreatedAtMillis = &ms
			}
		}
		refs = append(refs, ref)
	}
	data.AuthorMediaRefs = &refs
	return nil
}

// fillTxtFragments TXT 片段段（§10「TXT 片段」）：kv imported_txt_sources
// 全量逐字导出（不截断不转换），作者表随备份全量同步。importedAt 缺省
// （旧片段）或解析失败按 0 落载荷——片段正文才是迁移的承重数据，导出不因
// 元数据坏值整体 500。
func (exp *backupExport) fillTxtFragments(data *BackupData) error {
	sources, err := authorattach.LoadSources(exp.ctx, exp.q)
	if err != nil {
		return fmt.Errorf("查询已导入 TXT 片段: %w", err)
	}
	frags := make([]TxtFragment, 0, len(sources))
	for _, src := range sources {
		var importedAt int64
		if src.ImportedAt != "" {
			if ms, err := millisOf(src.ImportedAt); err == nil {
				importedAt = ms
			}
		}
		frags = append(frags, TxtFragment{
			Filename:         src.Filename,
			Content:          src.Content,
			ImportedAtMillis: importedAt,
		})
	}
	data.TxtFragments = &frags
	return nil
}

// fillTags 标签 + 文件标签关联段（关联带 createdAt，对齐 v1.19 冗余字段）。
func (exp *backupExport) fillTags(data *BackupData, byID map[string]*exportAsset) error {
	tagRows, err := exp.q.ExportTags(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询标签: %w", err)
	}
	tags := make([]Tag, 0, len(tagRows))
	for _, r := range tagRows {
		createdAt, err := millisOf(r.CreatedAt)
		if err != nil {
			return fmt.Errorf("标签 %s created_at 解析: %w", r.Name, err)
		}
		tags = append(tags, Tag{Name: r.Name, CreatedAtMillis: &createdAt})
	}
	data.Tags = &tags

	refRows, err := exp.q.ExportAssetTags(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询标签关联: %w", err)
	}
	refs := make([]MediaTagRef, 0, len(refRows))
	for _, r := range refRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		ref := MediaTagRef{RecordKey: a.recordKey, TagName: r.TagName, Origin: &r.Origin}
		if ms, err := millisOf(r.CreatedAt); err == nil {
			ref.CreatedAtMillis = &ms
		}
		refs = append(refs, ref)
	}
	data.MediaTagRefs = &refs
	return nil
}

// fillTimelineTags 时间轴标签段（资产被删的孤儿行 JOIN 不出，跳过即可）。
func (exp *backupExport) fillTimelineTags(data *BackupData, byID map[string]*exportAsset) error {
	rows, err := exp.q.ExportTimelineTags(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询时间轴标签: %w", err)
	}
	tags := make([]TimelineTag, 0, len(rows))
	for _, r := range rows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		createdAt, err := millisOf(r.CreatedAt)
		if err != nil {
			return fmt.Errorf("时间轴标签 %s created_at 解析: %w", r.AssetID, err)
		}
		tags = append(tags, TimelineTag{
			RecordKey:       a.recordKey,
			FileName:        a.row.FileName,
			TimeMillis:      r.TimeMillis,
			Name:            r.TagName,
			CreatedAtMillis: &createdAt,
		})
	}
	data.TimelineTags = &tags
	return nil
}

// fillLikesFavorites 点赞（按资产行数聚合）+ 收藏（recordKey 列表）。
func (exp *backupExport) fillLikesFavorites(data *BackupData, byID map[string]*exportAsset) error {
	likeRows, err := exp.q.ExportLikeAggregates(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询点赞: %w", err)
	}
	likes := make([]Like, 0, len(likeRows))
	for _, r := range likeRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		likes = append(likes, Like{
			RecordKey:    a.recordKey,
			LikeCount:    ptr(int(r.LikeCount)),
			LastLikeDate: textOrNull(r.LastDay),
		})
	}
	data.Likes = &likes

	favRows, err := exp.q.ExportFavorites(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询收藏: %w", err)
	}
	favorites := make([]string, 0, len(favRows))
	for _, r := range favRows {
		if a := byID[r]; a != nil {
			favorites = append(favorites, a.recordKey)
		}
	}
	data.Favorites = &favorites
	return nil
}

// fillStats mediaStats（事件流按资产聚合）+ dailyBrowse（物化表）+ history
// （全部 open 事件倒序，2026-10-04 起无上限）三段。事件无外键，被删资产的事件仍在
// 流里——JOIN 语义由 here 的 byID 缺失跳过承担。
func (exp *backupExport) fillStats(data *BackupData, byID map[string]*exportAsset) error {
	if err := exp.fillPortableEvents(data, byID); err != nil {
		return err
	}
	totalRows, err := exp.q.ExportEventTotals(exp.ctx)
	if err != nil {
		return fmt.Errorf("聚合事件统计: %w", err)
	}
	stats := make([]MediaStats, 0, len(totalRows))
	for _, r := range totalRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		st := MediaStats{
			RecordKey:          a.recordKey,
			FileName:           a.row.FileName,
			ViewCount:          ptr(int(r.ViewCount)),
			PlayCount:          ptr(int(r.PlayCount)),
			TotalBrowseSeconds: &r.BrowseSeconds,
		}
		if opened := textOrNull(r.LastOpenedAt); opened != nil {
			if ms, err := millisOf(*opened); err == nil {
				st.LastOpenedAtMillis = &ms
			}
		}
		if last := textOrNull(r.LastEventAt); last != nil {
			if ms, err := millisOf(*last); err == nil {
				st.UpdatedAtMillis = &ms
			}
		}
		stats = append(stats, st)
	}
	data.MediaStats = &stats

	dayRows, err := exp.q.ExportDailyBrowse(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询按天统计: %w", err)
	}
	daily := make([]DailyBrowse, 0, len(dayRows))
	for _, r := range dayRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		dayStart, err := dayMillis(r.Day)
		if err != nil {
			return fmt.Errorf("按天统计日界 %s 解析: %w", r.Day, err)
		}
		daily = append(daily, DailyBrowse{
			RecordKey:          a.recordKey,
			FileName:           a.row.FileName,
			MediaType:          DailyBrowseMediaType(a.row.MediaType),
			DayStartMillis:     dayStart,
			ViewCount:          ptr(int(r.ViewCount)),
			PlayCount:          ptr(int(r.PlayCount)),
			TotalBrowseSeconds: &r.BrowseSeconds,
		})
	}
	data.DailyBrowse = &daily

	openRows, err := exp.q.ExportRecentOpenEvents(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询浏览历史: %w", err)
	}
	history := make([]HistoryEntry, 0, len(openRows))
	for _, r := range openRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		openedAt, err := millisOf(r.StartedAt)
		if err != nil {
			return fmt.Errorf("浏览事件时间解析: %w", err)
		}
		history = append(history, HistoryEntry{
			RecordKey:      a.recordKey,
			FileName:       a.row.FileName,
			MediaType:      HistoryEntryMediaType(a.row.MediaType),
			OpenedAtMillis: openedAt,
		})
	}
	data.History = &history
	return nil
}

// fillPrefs recommendationPrefs（kv_settings 原样 JSON）——解析失败降级为
// 缺省该段（导出是只读 dump，不因单段坏值整体 500），记日志供排查。
func (exp *backupExport) fillPrefs(data *BackupData) {
	raw, err := exp.q.GetSetting(exp.ctx, RecommendPrefsKey)
	if err != nil {
		return // ErrNoRows（从未设置过）同路径：appPrefs 段缺省
	}
	var prefs RecommendPrefs
	if err := json.Unmarshal([]byte(raw), &prefs); err != nil {
		exp.s.logger.Warn("导出备份：推荐偏好解析失败，appPrefs 段缺省", "error", err)
		return
	}
	data.AppPrefs = &AppPrefs{
		Version:             ptr(prefsFormatVersion),
		RecommendationPrefs: &prefs,
	}
}

// nullIntToPtr / nullInt64ToPtr 探测元数据可空列 → 协议可选字段。
func nullIntToPtr(v sql.NullInt64) *int {
	if !v.Valid {
		return nil
	}
	return ptr(int(v.Int64))
}

func nullInt64ToPtr(v sql.NullInt64) *int64 {
	if !v.Valid {
		return nil
	}
	return &v.Int64
}
