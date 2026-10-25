package portablebackup

import (
	"database/sql"
	"fmt"
	"time"

	"qimeng-media/server/internal/eventstats"
	"qimeng-media/server/internal/store/db"
)

// fillPortableEvents 保留原始身份、时刻、秒数和来源；聚合段只作查看，不作为导入事件源。
func (exp *backupExport) fillPortableEvents(data *BackupData, byID map[string]*exportAsset) error {
	rows, err := exp.q.ExportPortableEvents(exp.ctx)
	if err != nil {
		return err
	}
	events := make([]ViewEvent, 0, len(rows))
	for _, row := range rows {
		a := byID[row.AssetID]
		if a == nil {
			continue
		}
		events = append(events, ViewEvent{
			EventId: row.EventKey, RecordKey: a.recordKey,
			Kind: row.Kind, SessionId: row.SessionID,
			StartedAt: row.StartedAt, Seconds: nullInt64ToPtr(row.Seconds), Day: nullStringPtr(row.Day),
		})
	}
	data.ViewEvents = &events
	return nil
}

func nullStringPtr(v sql.NullString) *string {
	if !v.Valid {
		return nil
	}
	return &v.String
}

// validatePortableBackup 在任何写入前拒绝不完整的新格式，禁止静默降级聚合回放。
func validatePortableBackup(req *BackupImport) error {
	if req.SchemaVersion != SchemaVersion {
		return fmt.Errorf("仅支持本项目的 v%d 备份，旧版及未知版本不再导入", SchemaVersion)
	}
	if req.Data.ViewEvents == nil {
		return fmt.Errorf("v2 备份缺少 viewEvents")
	}
	keys := make(map[string]bool)
	for _, f := range sliceOrEmpty(req.Data.MediaFiles) {
		if f.RecordKey == "" || keys[f.RecordKey] {
			return fmt.Errorf("重复或空 recordKey")
		}
		keys[f.RecordKey] = true
	}
	eventIDs := make(map[string]bool)
	for _, e := range *req.Data.ViewEvents {
		at, err := time.Parse(time.RFC3339Nano, e.StartedAt)
		if err != nil || at.IsZero() || e.EventId == "" || !keys[e.RecordKey] {
			return fmt.Errorf("无效的事件身份、引用或时刻")
		}
		if e.Kind != eventOpen && e.Kind != eventPlay && e.Kind != eventDwell {
			return fmt.Errorf("无效的事件类型")
		}
		if e.Seconds != nil && *e.Seconds < 0 {
			return fmt.Errorf("事件秒数不得为负")
		}
		if eventIDs[e.EventId] {
			return fmt.Errorf("重复的事件身份")
		}
		eventIDs[e.EventId] = true
	}
	return nil
}

func (imp *backupImport) replayPortableEvents(req *BackupImport) {
	if imp.err != nil {
		return
	}
	n := 0
	imp.importSegment("原生事件", func(q *db.Queries) {
		for _, e := range *req.Data.ViewEvents {
			assetID := imp.keyToAsset[e.RecordKey]
			if assetID == "" {
				continue
			}
			key := sql.NullString{String: e.EventId, Valid: true}
			exists, err := q.PortableEventExists(imp.ctx, key)
			if err != nil {
				imp.fail("检查事件身份", err)
				return
			}
			if exists.Bool {
				continue
			}
			secs := sql.NullInt64{}
			if e.Seconds != nil {
				secs = sql.NullInt64{Int64: *e.Seconds, Valid: true}
			}
			day := sql.NullString{}
			if e.Day != nil {
				day = sql.NullString{String: *e.Day, Valid: true}
			}
			added, err := q.InsertViewEventIdempotent(imp.ctx, db.InsertViewEventIdempotentParams{
				AssetID: assetID, ClientEventID: key, Kind: e.Kind, SessionID: e.SessionId,
				StartedAt: e.StartedAt, Seconds: secs, Day: day,
			})
			if err != nil {
				imp.fail("合并原生事件", err)
				return
			}
			n += int(added)
		}
	})
	imp.res.EventsReplayed = &n
	imp.rebuildDailyStats()
}

// pickPortableAsset 本地身份优先；跨库逐层消歧，无唯一证据绝不取最早行。
func pickPortableAsset(f MediaFile, rows []db.ListAssetNameIndexRow, byID map[string]string) string {
	if f.AssetId != nil && byID[*f.AssetId] != "" {
		return *f.AssetId
	}
	if len(rows) == 1 {
		return rows[0].AssetID
	}
	var matches []db.ListAssetNameIndexRow
	if f.RelPath == nil {
		return ""
	}
	for _, r := range rows {
		if r.RelPath == *f.RelPath {
			matches = append(matches, r)
		}
	}
	if len(matches) == 1 {
		return matches[0].AssetID
	}
	var found string
	for _, r := range matches {
		if f.LibraryName != nil && r.LibraryName == *f.LibraryName {
			if found != "" {
				return ""
			}
			found = r.AssetID
		}
	}
	return found
}

// rebuildDailyStats 依据现有事件重建派生统计，不改变事件真相或旧身份。
func (imp *backupImport) rebuildDailyStats() {
	if imp.err != nil {
		return
	}
	if err := eventstats.Rebuild(imp.ctx, imp.s.conn); err != nil {
		imp.fail("重建按天统计物化表", err)
	}
}
