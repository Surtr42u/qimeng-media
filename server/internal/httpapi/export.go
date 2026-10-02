// export.go：旧版格式备份导出端点（新库 → qimeng_backup.json，DOMAIN_RULES §10
// 逆向映射，格式权威 = 旧仓库 DATA_MIGRATION_SPEC v1）。
//
// 段级口径（与 import.go 的导入映射互为镜像，回环经 POST /import/qimeng-backup
// 幂等导入）：
//   - mediaFiles：全量资产；recordKey 生成镜像导入侧 matchFiles 消歧规则
//     （文件名唯一 = 文件名；同名 = 「文件名 @ 文件夹名」；同名同文件夹
//     （跨库）再追加 #路径哈希）——folderName 恒取 rel_path 父目录段，保证
//     导入侧 pickAssetByFolder 能按同规则消歧；
//   - dailyBrowse：取事件流物化表（asset x day，唯一真相源是 view_events，
//     物化表是其派生缓存）；mediaStats：事件流按资产聚合（open/play 计数、
//     dwell 秒数、最后打开时刻）；
//   - history：最近 500 条 open 事件（对齐旧库 view_history 上限）；
//   - likes：按点赞行聚合为 { 累计次数, 最后点赞日 }（新库行 = 资产 x 日，
//     旧格式无逐日明细，COUNT(*) 即最接近的累计值）；
//   - txtFragments：kv imported_txt_sources 全量逐字导出（DOMAIN_RULES
//     §10「TXT 片段」；导入端在 authors/authorMediaRefs 段之前按同名合并）；
//   - settings/scanSources/albumRules/cosWorks：恒导出空数组（SAF 目录是设备
//     本机概念、COS 作品目录与出处均为派生信息，导入端对空段零警告）；
//   - appPrefs：只带 recommendationPrefs（kv_settings 原样 JSON，与导入端
//     importPrefs 同键）。
package httpapi

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"path"
	"strings"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

const (
	// 旧格式信封常量（DATA_MIGRATION_SPEC §3）。legacyBackupFormat 与
	// import.go 的请求校验共用一处定义（代码卫生：同一字面量单一来源）。
	legacyBackupFormat  = "qimeng_backup"
	legacyAppIdentifier = "com.qimeng.media"
	legacySchemaVersion = 1

	// history 段 500 条上限的单一来源在 store/queries/legacy_export.sql（ExportRecentOpenEvents，对齐旧库 view_history）——SQL 侧改动须同步彼处注释。

	// recordKey 同名消歧分隔符（旧规则「文件名 @ 文件夹名」与「#路径哈希」）。
	recordKeySep     = " @ "
	recordKeyHashSep = "#"

	// 同名同文件夹时追加的路径哈希长度（sha256 前 8 hex，仅消歧无需抗碰撞）。
	recordKeyHashLen = 8
)

// GetApiV1ExportQimengBackup 导出旧版格式全量备份（附件下载）。
func (s *Server) GetApiV1ExportQimengBackup(w http.ResponseWriter, r *http.Request) { //nolint:revive // 生成接口要求的方法名
	exp := &legacyExport{s: s, ctx: r.Context()}
	file, err := exp.build()
	if err != nil {
		s.internalErr(w, "导出旧版格式备份", err)
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="qimeng_backup.json"`)
	writeJSON(w, http.StatusOK, file)
}

// legacyExport 聚拢导出过程中的共享状态与查询句柄。
type legacyExport struct {
	s   *Server
	ctx context.Context
}

// exportAsset 是资产行 + 派生 recordKey 的绑定（各段都经它取关联键）。
type exportAsset struct {
	row       db.ExportListAssetsRow
	recordKey string
}

// build 汇齐 17 段数据并组装信封；任一查询错误即中止（500，无副作用可重试）。
func (exp *legacyExport) build() (*gen.LegacyBackupFile, error) {
	assets, err := exp.loadAssets()
	if err != nil {
		return nil, err
	}
	byID := make(map[string]*exportAsset, len(assets))
	for _, a := range assets {
		byID[a.row.AssetID] = a
	}

	data := gen.LegacyBackupData{}
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
	data.Settings = &[]gen.LegacySetting{}
	data.ScanSources = &[]gen.LegacyScanSource{}
	data.AlbumRules = &[]map[string]interface{}{}
	data.CosWorks = &[]gen.LegacyCosWork{}

	return &gen.LegacyBackupFile{
		Format:           legacyBackupFormat,
		SchemaVersion:    legacySchemaVersion,
		AppIdentifier:    legacyAppIdentifier,
		ExportedAtMillis: ptr(exp.s.now().UnixMilli()),
		Data:             data,
	}, nil
}

// assignRecordKeys 按旧规则派生 recordKey（见包注释）；rows 已按
// (file_name, rel_path) 有序，同名分组与碰撞判定确定性成立。
func assignRecordKeys(rows []db.ExportListAssetsRow) []*exportAsset {
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
			if seen[key] { // 跨库同名同文件夹：追加路径哈希（旧规则第三类 key）
				sum := sha256.Sum256([]byte(r.RelPath))
				key += recordKeyHashSep + hex.EncodeToString(sum[:])[:recordKeyHashLen]
			}
		}
		seen[key] = true
		assets = append(assets, &exportAsset{row: r, recordKey: key})
	}
	return assets
}

// loadAssets 拉全量资产行并派生 recordKey。
func (exp *legacyExport) loadAssets() ([]*exportAsset, error) {
	rows, err := exp.s.q.ExportListAssets(exp.ctx)
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
func (exp *legacyExport) fillFiles(data *gen.LegacyBackupData, assets []*exportAsset) error {
	files := make([]gen.LegacyMediaFile, 0, len(assets))
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
		files = append(files, gen.LegacyMediaFile{
			RecordKey:           a.recordKey,
			FileName:            a.row.FileName,
			DisplayName:         ptr(strings.TrimSuffix(a.row.FileName, path.Ext(a.row.FileName))),
			Extension:           ptr(strings.TrimPrefix(path.Ext(a.row.FileName), ".")),
			MediaType:           gen.LegacyMediaFileMediaType(a.row.MediaType),
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
func (exp *legacyExport) fillAuthors(data *gen.LegacyBackupData, byID map[string]*exportAsset) error {
	authorRows, err := exp.s.q.ExportAuthors(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询作者: %w", err)
	}
	authors := make([]gen.LegacyAuthor, 0, len(authorRows))
	for _, r := range authorRows {
		createdAt, err := millisOf(r.CreatedAt)
		if err != nil {
			return fmt.Errorf("作者 %s created_at 解析: %w", r.ID, err)
		}
		authors = append(authors, gen.LegacyAuthor{
			AuthorId:        r.ID,
			DisplayName:     r.DisplayName,
			CreatedAtMillis: &createdAt,
			Origin:          &r.Origin, // 溯源透传（ADR-0032）：原始通道原样带出
		})
	}
	data.Authors = &authors

	followed, err := exp.s.q.ExportFollowedAuthorIDs(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询关注作者: %w", err)
	}
	data.FollowedAuthorIds = &followed

	refRows, err := exp.s.q.ExportAssetAuthors(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询作者关联: %w", err)
	}
	refs := make([]gen.LegacyAuthorMediaRef, 0, len(refRows))
	for _, r := range refRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		ref := gen.LegacyAuthorMediaRef{
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
func (exp *legacyExport) fillTxtFragments(data *gen.LegacyBackupData) error {
	sources, err := authorattach.LoadSources(exp.ctx, exp.s.q)
	if err != nil {
		return fmt.Errorf("查询已导入 TXT 片段: %w", err)
	}
	frags := make([]gen.LegacyTxtFragment, 0, len(sources))
	for _, src := range sources {
		var importedAt int64
		if src.ImportedAt != "" {
			if ms, err := millisOf(src.ImportedAt); err == nil {
				importedAt = ms
			}
		}
		frags = append(frags, gen.LegacyTxtFragment{
			Filename:         src.Filename,
			Content:          src.Content,
			ImportedAtMillis: importedAt,
		})
	}
	data.TxtFragments = &frags
	return nil
}

// fillTags 标签 + 文件标签关联段（关联带 createdAt，对齐 v1.19 冗余字段）。
func (exp *legacyExport) fillTags(data *gen.LegacyBackupData, byID map[string]*exportAsset) error {
	tagRows, err := exp.s.q.ExportTags(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询标签: %w", err)
	}
	tags := make([]gen.LegacyTag, 0, len(tagRows))
	for _, r := range tagRows {
		createdAt, err := millisOf(r.CreatedAt)
		if err != nil {
			return fmt.Errorf("标签 %s created_at 解析: %w", r.Name, err)
		}
		tags = append(tags, gen.LegacyTag{Name: r.Name, CreatedAtMillis: &createdAt})
	}
	data.Tags = &tags

	refRows, err := exp.s.q.ExportAssetTags(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询标签关联: %w", err)
	}
	refs := make([]gen.LegacyMediaTagRef, 0, len(refRows))
	for _, r := range refRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		ref := gen.LegacyMediaTagRef{RecordKey: a.recordKey, TagName: r.TagName, Origin: &r.Origin}
		if ms, err := millisOf(r.CreatedAt); err == nil {
			ref.CreatedAtMillis = &ms
		}
		refs = append(refs, ref)
	}
	data.MediaTagRefs = &refs
	return nil
}

// fillTimelineTags 时间轴标签段（资产被删的孤儿行 JOIN 不出，跳过即可）。
func (exp *legacyExport) fillTimelineTags(data *gen.LegacyBackupData, byID map[string]*exportAsset) error {
	rows, err := exp.s.q.ExportTimelineTags(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询时间轴标签: %w", err)
	}
	tags := make([]gen.LegacyTimelineTag, 0, len(rows))
	for _, r := range rows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		createdAt, err := millisOf(r.CreatedAt)
		if err != nil {
			return fmt.Errorf("时间轴标签 %s created_at 解析: %w", r.AssetID, err)
		}
		tags = append(tags, gen.LegacyTimelineTag{
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
func (exp *legacyExport) fillLikesFavorites(data *gen.LegacyBackupData, byID map[string]*exportAsset) error {
	likeRows, err := exp.s.q.ExportLikeAggregates(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询点赞: %w", err)
	}
	likes := make([]gen.LegacyLike, 0, len(likeRows))
	for _, r := range likeRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		likes = append(likes, gen.LegacyLike{
			RecordKey:    a.recordKey,
			LikeCount:    ptr(int(r.LikeCount)),
			LastLikeDate: textOrNull(r.LastDay),
		})
	}
	data.Likes = &likes

	favRows, err := exp.s.q.ExportFavorites(exp.ctx)
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
// （最近 500 条 open）三段。事件无外键，被删资产的事件仍在流里——JOIN 语义
// 由 here 的 byID 缺失跳过承担。
func (exp *legacyExport) fillStats(data *gen.LegacyBackupData, byID map[string]*exportAsset) error {
	totalRows, err := exp.s.q.ExportEventTotals(exp.ctx)
	if err != nil {
		return fmt.Errorf("聚合事件统计: %w", err)
	}
	stats := make([]gen.LegacyMediaStats, 0, len(totalRows))
	for _, r := range totalRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		st := gen.LegacyMediaStats{
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

	dayRows, err := exp.s.q.ExportDailyBrowse(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询按天统计: %w", err)
	}
	daily := make([]gen.LegacyDailyBrowse, 0, len(dayRows))
	for _, r := range dayRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		dayStart, err := dayMillis(r.Day)
		if err != nil {
			return fmt.Errorf("按天统计日界 %s 解析: %w", r.Day, err)
		}
		daily = append(daily, gen.LegacyDailyBrowse{
			RecordKey:          a.recordKey,
			FileName:           a.row.FileName,
			MediaType:          gen.LegacyDailyBrowseMediaType(a.row.MediaType),
			DayStartMillis:     dayStart,
			ViewCount:          ptr(int(r.ViewCount)),
			PlayCount:          ptr(int(r.PlayCount)),
			TotalBrowseSeconds: &r.BrowseSeconds,
		})
	}
	data.DailyBrowse = &daily

	openRows, err := exp.s.q.ExportRecentOpenEvents(exp.ctx)
	if err != nil {
		return fmt.Errorf("查询浏览历史: %w", err)
	}
	history := make([]gen.LegacyHistoryEntry, 0, len(openRows))
	for _, r := range openRows {
		a := byID[r.AssetID]
		if a == nil {
			continue
		}
		openedAt, err := millisOf(r.StartedAt)
		if err != nil {
			return fmt.Errorf("浏览事件时间解析: %w", err)
		}
		history = append(history, gen.LegacyHistoryEntry{
			RecordKey:      a.recordKey,
			FileName:       a.row.FileName,
			MediaType:      gen.LegacyHistoryEntryMediaType(a.row.MediaType),
			OpenedAtMillis: openedAt,
		})
	}
	data.History = &history
	return nil
}

// fillPrefs recommendationPrefs（kv_settings 原样 JSON）——解析失败降级为
// 缺省该段（导出是只读 dump，不因单段坏值整体 500），记日志供排查。
func (exp *legacyExport) fillPrefs(data *gen.LegacyBackupData) {
	raw, err := exp.s.q.GetSetting(exp.ctx, settingKeyRecommendPrefs)
	if err != nil {
		return // ErrNoRows（从未设置过）同路径：appPrefs 段缺省
	}
	var prefs gen.RecommendPrefs
	if err := json.Unmarshal([]byte(raw), &prefs); err != nil {
		exp.s.logger.Warn("导出备份：推荐偏好解析失败，appPrefs 段缺省", "error", err)
		return
	}
	data.AppPrefs = &gen.LegacyAppPrefs{
		Version:             ptr(legacySchemaVersion),
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
