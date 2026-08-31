// import_test.go：旧版迁移端点测试（DOMAIN_RULES §10 映射 + 幂等批次）。
// 用例语义对照：段级计数、总量守恒回放（dailyBrowse 全量 + mediaStats 差额
// + history 补漏）、同批次重复导入事件不翻倍、cos_ 前缀保留、可选段缺省。
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
)

// queryAssetID 按文件名查首个资产 id（测试造数后的取 id 辅助）。
func queryAssetID(t *testing.T, e *testEnv, fileName string) string {
	t.Helper()
	var id string
	if err := e.conn.QueryRow(`SELECT asset_id FROM assets WHERE file_name=? ORDER BY created_at LIMIT 1`, fileName).Scan(&id); err != nil {
		t.Fatalf("查资产 %s 失败: %v", fileName, err)
	}
	return id
}

// seedLegacyFiles 手工再造两行同名资产（不同子目录），验证 folderName 消歧。
func seedLegacyFiles(t *testing.T, e *testEnv) (sub1ID, sub2ID string) {
	t.Helper()
	sub1ID, sub2ID = "01900000-0000-7000-8000-00000000aa01", "01900000-0000-7000-8000-00000000aa02"
	now := e.clock.Now().Format(time.RFC3339Nano)
	for _, seed := range []struct {
		id, dir string
	}{
		{sub1ID, "sub1"}, {sub2ID, "sub2"},
	} {
		if _, err := e.q.UpsertAsset(t.Context(), db.UpsertAssetParams{
			AssetID: seed.id, LibraryID: e.libID,
			RelPath: seed.dir + "/dup.jpg", FileName: "dup.jpg",
			MediaType: "image", SizeBytes: 10, Mtime: now, CreatedAt: now, UpdatedAt: now,
		}); err != nil {
			t.Fatalf("造同名资产失败: %v", err)
		}
	}
	return sub1ID, sub2ID
}

// importBackup 构造备份请求并 POST，返回响应结构。
func importBackup(t *testing.T, e *testEnv, req gen.LegacyBackupImport) (int, gen.LegacyImportResult) {
	t.Helper()
	raw, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("序列化备份失败: %v", err)
	}
	resp := e.do(t, http.MethodPost, "/api/v1/import/qimeng-backup", string(raw))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("迁移响应状态异常: %d", resp.StatusCode)
	}
	var res gen.LegacyImportResult
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		t.Fatalf("解析迁移结果失败: %v", err)
	}
	return resp.StatusCode, res
}

// countEvents 统计事件表（kind 空串 = 全部）。
func countEvents(t *testing.T, e *testEnv, kind string) int {
	t.Helper()
	q := "SELECT COUNT(*) FROM view_events"
	args := []any{}
	if kind != "" {
		q += " WHERE kind = ?"
		args = append(args, kind)
	}
	var n int
	if err := e.conn.QueryRow(q, args...).Scan(&n); err != nil {
		t.Fatalf("统计事件失败: %v", err)
	}
	return n
}

// sumDwellSeconds 统计 dwell 事件秒数总和。
func sumDwellSeconds(t *testing.T, e *testEnv) int64 {
	t.Helper()
	var s sql.NullInt64
	if err := e.conn.QueryRow("SELECT SUM(seconds) FROM view_events WHERE kind='dwell'").Scan(&s); err != nil {
		t.Fatalf("统计停留秒失败: %v", err)
	}
	return s.Int64
}

// TestImport_formatMismatch_returns400：format 校验（协议 400 语义）。
func TestImport_formatMismatch_returns400(t *testing.T) {
	e := newTestEnv(t)
	code, _ := importBackup(t, e, gen.LegacyBackupImport{Format: "other", Data: gen.LegacyBackupData{}})
	if code != http.StatusBadRequest {
		t.Fatalf("format 不符应 400，got %d", code)
	}
}

// TestImport_fullPipeline：全段导入主链——计数、库内落点、总量守恒。
func TestImport_fullPipeline(t *testing.T) {
	e := newTestEnv(t)
	sub1, sub2 := seedLegacyFiles(t, e)

	req := gen.LegacyBackupImport{
		Format: "qimeng_backup", SchemaVersion: 1, AppIdentifier: "com.qimeng.media",
		ExportedAtMillis: ptr(int64(1756400000000)),
		Data: gen.LegacyBackupData{
			MediaFiles: &[]gen.LegacyMediaFile{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", SizeBytes: 100, ModifiedAtMillis: 1},
				{RecordKey: "b.jpg", FileName: "b.jpg", MediaType: "image", SizeBytes: 100, ModifiedAtMillis: 2},
				{RecordKey: "c.mp4", FileName: "c.mp4", MediaType: "video", SizeBytes: 100, ModifiedAtMillis: 3},
				{RecordKey: "dup.jpg @ sub1", FileName: "dup.jpg", FolderName: ptr("sub1"), MediaType: "image", SizeBytes: 1, ModifiedAtMillis: 4},
				{RecordKey: "dup.jpg @ sub2", FileName: "dup.jpg", FolderName: ptr("sub2"), MediaType: "image", SizeBytes: 1, ModifiedAtMillis: 5},
				{RecordKey: "missing.jpg", FileName: "missing.jpg", MediaType: "image", SizeBytes: 1, ModifiedAtMillis: 6},
			},
			Authors: &[]gen.LegacyAuthor{
				{AuthorId: "author_a", DisplayName: "作者A", CreatedAtMillis: ptr(int64(1000))},
				{AuthorId: "cos_author_b", DisplayName: "COS作者B", CreatedAtMillis: ptr(int64(1000))},
			},
			AuthorMediaRefs: &[]gen.LegacyAuthorMediaRef{
				{AuthorId: "author_a", RecordKey: "a.jpg", FileName: "a.jpg", IsMatched: ptr(true)},
				{AuthorId: "cos_author_b", RecordKey: "c.mp4", FileName: "c.mp4"},
				{AuthorId: "author_a", RecordKey: "dup.jpg @ sub2", FileName: "dup.jpg"},  // folderName 消歧 → sub2
				{AuthorId: "author_a", RecordKey: "missing.jpg", FileName: "missing.jpg"}, // 资产未匹配 → skipped
			},
			Tags: &[]gen.LegacyTag{{Name: "风景"}, {Name: "人像"}},
			MediaTagRefs: &[]gen.LegacyMediaTagRef{
				{RecordKey: "b.jpg", TagName: "风景"},  // createdAtMillis 缺省 → 回退导入时刻
				{RecordKey: "b.jpg", TagName: "不存在"}, // 标签未匹配 → skipped
			},
			DailyBrowse: &[]gen.LegacyDailyBrowse{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", DayStartMillis: 1756377600000, ViewCount: ptr(3), PlayCount: ptr(0), TotalBrowseSeconds: ptr(int64(30))},
				{RecordKey: "c.mp4", FileName: "c.mp4", MediaType: "video", DayStartMillis: 1756377600000, ViewCount: ptr(0), PlayCount: ptr(2), TotalBrowseSeconds: ptr(int64(120))},
			},
			MediaStats: &[]gen.LegacyMediaStats{
				// 差额口径：a.jpg 累计 5 - 明细 3 = 2 条 open 合成；c.mp4 明细已覆盖无差额
				{RecordKey: "a.jpg", FileName: "a.jpg", ViewCount: ptr(5), PlayCount: ptr(0), TotalBrowseSeconds: ptr(int64(30)), LastOpenedAtMillis: ptr(int64(1756460000000))},
				{RecordKey: "c.mp4", FileName: "c.mp4", ViewCount: ptr(0), PlayCount: ptr(2), TotalBrowseSeconds: ptr(int64(120))},
			},
			History: &[]gen.LegacyHistoryEntry{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", OpenedAtMillis: 1756460000000}, // dailyBrowse 有行 → 不回放
				{RecordKey: "b.jpg", FileName: "b.jpg", MediaType: "image", OpenedAtMillis: 1756460000000}, // 无明细 → 补漏 1 条
			},
			Likes: &[]gen.LegacyLike{
				{RecordKey: "a.jpg", LikeCount: ptr(3), LastLikeDate: ptr("2026-08-28")}, // 累计 3 无法逐日还原 → 1 行 + warning
			},
			Favorites: &[]string{"b.jpg"},
			TimelineTags: &[]gen.LegacyTimelineTag{
				{RecordKey: "c.mp4", FileName: "c.mp4", TimeMillis: 5000, Name: "高能", CreatedAtMillis: ptr(int64(1000))},
			},
			FollowedAuthorIds: &[]string{"author_a", "ghost_id"},
			AppPrefs: &gen.LegacyAppPrefs{RecommendationPrefs: &gen.RecommendPrefs{
				TagRelevance: ptr(float32(0.30)), MaxRandom: ptr(float32(0.35)),
			}},
			ScanSources: &[]gen.LegacyScanSource{{UriString: "content://x", DisplayName: "旧目录", AddedAtMillis: ptr(int64(1))}},
			Settings:    &[]gen.LegacySetting{{Key: "k", Value: "v"}},
			AlbumRules:  &[]map[string]any{{"rule": 1}},
		},
	}
	code, res := importBackup(t, e, req)
	if code != http.StatusOK {
		t.Fatalf("迁移应 200，got %d", code)
	}

	// 计数断言
	if got := derefVal(res.MediaFilesTotal); got != 6 {
		t.Errorf("MediaFilesTotal = %d, want 6", got)
	}
	if got := derefVal(res.AssetsMatched); got != 5 {
		t.Errorf("AssetsMatched = %d, want 5", got)
	}
	if got := derefVal(res.AuthorsImported); got != 2 {
		t.Errorf("AuthorsImported = %d, want 2", got)
	}
	if got := derefVal(res.AuthorRefsImported); got != 3 {
		t.Errorf("AuthorRefsImported = %d, want 3", got)
	}
	if got := derefVal(res.AuthorRefsSkipped); got != 1 {
		t.Errorf("AuthorRefsSkipped = %d, want 1", got)
	}
	if got := derefVal(res.TagsImported); got != 2 {
		t.Errorf("TagsImported = %d, want 2", got)
	}
	if got := derefVal(res.TagRefsImported); got != 1 || derefVal(res.TagRefsSkipped) != 1 {
		t.Errorf("TagRefs = %d/%d, want 1/1", derefVal(res.TagRefsImported), derefVal(res.TagRefsSkipped))
	}
	// 总量守恒：open = 明细 3 + 差额 2 + 补漏 1 = 6；play = 明细 2；dwell 2 条（a 30s + c 120s）
	if got := countEvents(t, e, "open"); got != 6 {
		t.Errorf("open 事件 = %d, want 6", got)
	}
	if got := countEvents(t, e, "play"); got != 2 {
		t.Errorf("play 事件 = %d, want 2", got)
	}
	if got := countEvents(t, e, "dwell"); got != 2 {
		t.Errorf("dwell 事件 = %d, want 2", got)
	}
	if got := sumDwellSeconds(t, e); got != 150 {
		t.Errorf("停留秒合计 = %d, want 150", got)
	}
	if got := derefVal(res.EventsReplayed); got != 10 {
		t.Errorf("EventsReplayed = %d, want 10", got)
	}
	if got := derefVal(res.LikesImported); got != 1 || derefVal(res.FavoritesImported) != 1 {
		t.Errorf("Likes/Favorites = %d/%d, want 1/1", derefVal(res.LikesImported), derefVal(res.FavoritesImported))
	}
	if got := derefVal(res.TimelineTagsImported); got != 1 {
		t.Errorf("TimelineTagsImported = %d, want 1", got)
	}
	if got := derefVal(res.FollowedAuthorsMarked); got != 1 {
		t.Errorf("FollowedAuthorsMarked = %d, want 1", got)
	}
	if res.PrefsImported == nil || !*res.PrefsImported {
		t.Error("PrefsImported 应为 true")
	}
	warnings := []string{}
	if res.Warnings != nil {
		warnings = *res.Warnings
	}
	if len(warnings) < 4 {
		t.Errorf("warnings 至少 4 条（点赞差额/scanSources/settings/albumRules），got %d: %v", len(warnings), warnings)
	}

	// 库内落点：cos_ 前缀作者 type=cos；同名消歧落 sub2 对应资产
	var cosType string
	if err := e.conn.QueryRow(`SELECT type FROM authors WHERE id='cos_author_b'`).Scan(&cosType); err != nil || cosType != "cos" {
		t.Errorf("cos 作者 type = %q, err=%v, want cos", cosType, err)
	}
	if got := authorRefAsset(t, e, "cos_author_b"); got != queryAssetID(t, e, "c.mp4") {
		t.Errorf("cos 作者应关联 c.mp4 资产，got %q", got)
	}
	if got := authorRefAsset(t, e, "author_a"); got == sub2 || got != queryAssetID(t, e, "a.jpg") {
		t.Errorf("author_a 首个关联应为 a.jpg 资产，got %q", got)
	}
	var dupAsset string
	if err := e.conn.QueryRow(`SELECT aa.asset_id FROM asset_authors aa
		WHERE aa.author_id='author_a' AND aa.asset_id IN (?, ?)`, sub1, sub2).Scan(&dupAsset); err != nil || dupAsset != sub2 {
		t.Errorf("dup.jpg @ sub2 应消歧到 sub2 资产，got %q err=%v", dupAsset, err)
	}

	// 关注标记 + 偏好落 kv_settings
	var followed int
	if err := e.conn.QueryRow(`SELECT followed FROM authors WHERE id='author_a'`).Scan(&followed); err != nil || followed != 1 {
		t.Errorf("author_a followed = %d err=%v, want 1", followed, err)
	}
	var prefsRaw string
	if err := e.conn.QueryRow(`SELECT value FROM kv_settings WHERE key='recommend_prefs'`).Scan(&prefsRaw); err != nil {
		t.Fatalf("偏好未写入: %v", err)
	}
	var prefs gen.RecommendPrefs
	if err := json.Unmarshal([]byte(prefsRaw), &prefs); err != nil {
		t.Fatalf("偏好 JSON 解析失败: %v", err)
	}
	if prefs.TagRelevance == nil || *prefs.TagRelevance != 0.30 {
		t.Errorf("偏好 tagRelevance 应 0.30，got %v", prefs.TagRelevance)
	}

	// 时间轴标签落点
	var tl int
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM timeline_tags WHERE name='高能'`).Scan(&tl); err != nil || tl != 1 {
		t.Errorf("时间轴标签 = %d err=%v, want 1", tl, err)
	}
}

// authorRefAsset 查作者第一个关联资产（消歧与落点断言用）。
func authorRefAsset(t *testing.T, e *testEnv, authorID string) string {
	t.Helper()
	var assetID string
	if err := e.conn.QueryRow(`SELECT asset_id FROM asset_authors WHERE author_id=? LIMIT 1`, authorID).Scan(&assetID); err != nil {
		t.Fatalf("查作者关联失败: %v", err)
	}
	return assetID
}

// TestImport_idempotentReplay：同批次重复导入——事件零回放、关联不翻倍。
func TestImport_idempotentReplay(t *testing.T) {
	e := newTestEnv(t)
	req := gen.LegacyBackupImport{
		Format: "qimeng_backup", SchemaVersion: 1, AppIdentifier: "com.qimeng.media",
		ExportedAtMillis: ptr(int64(1756400000000)),
		Data: gen.LegacyBackupData{
			MediaFiles: &[]gen.LegacyMediaFile{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", SizeBytes: 100, ModifiedAtMillis: 1},
			},
			Authors: &[]gen.LegacyAuthor{{AuthorId: "author_a", DisplayName: "作者A"}},
			AuthorMediaRefs: &[]gen.LegacyAuthorMediaRef{
				{AuthorId: "author_a", RecordKey: "a.jpg", FileName: "a.jpg"},
			},
			DailyBrowse: &[]gen.LegacyDailyBrowse{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", DayStartMillis: 1756377600000, ViewCount: ptr(2)},
			},
			Likes: &[]gen.LegacyLike{
				{RecordKey: "a.jpg", LikeCount: ptr(1), LastLikeDate: ptr("2026-08-28")}, // PK 冲突风险段（ImportAddLike DO NOTHING）
			},
			TimelineTags: &[]gen.LegacyTimelineTag{
				{RecordKey: "a.jpg", FileName: "a.jpg", TimeMillis: 3000, Name: "高能"}, // 内容去重段（ImportInsertTimelineTag NOT EXISTS）
			},
		},
	}
	if code, _ := importBackup(t, e, req); code != http.StatusOK {
		t.Fatalf("首次导入应 200")
	}
	first := countEvents(t, e, "")
	var firstLikes, firstTL int
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM likes`).Scan(&firstLikes); err != nil {
		t.Fatalf("统计点赞失败: %v", err)
	}
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM timeline_tags`).Scan(&firstTL); err != nil {
		t.Fatalf("统计时间轴失败: %v", err)
	}
	code, res := importBackup(t, e, req)
	if code != http.StatusOK {
		t.Fatalf("重复导入应 200")
	}
	if got := derefVal(res.EventsReplayed); got != 0 {
		t.Errorf("同批次重复导入 EventsReplayed = %d, want 0", got)
	}
	if got := countEvents(t, e, ""); got != first {
		t.Errorf("重复导入后事件数 %d 变化了，want %d（幂等）", got, first)
	}
	var refs int
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM asset_authors`).Scan(&refs); err != nil || refs != 1 {
		t.Errorf("重复导入后关联数 = %d err=%v, want 1（ON CONFLICT 不翻倍）", refs, err)
	}
	var likes, tl int
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM likes`).Scan(&likes); err != nil || likes != firstLikes {
		t.Errorf("重复导入后点赞行 = %d err=%v, want %d（幂等不翻倍不 500）", likes, err, firstLikes)
	}
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM timeline_tags`).Scan(&tl); err != nil || tl != firstTL {
		t.Errorf("重复导入后时间轴 = %d err=%v, want %d（内容去重）", tl, err, firstTL)
	}
}

// TestImport_newBatchReplays：不同 exportedAtMillis 视为新批次，事件重新回放。
func TestImport_newBatchReplays(t *testing.T) {
	e := newTestEnv(t)
	base := gen.LegacyBackupImport{
		Format: "qimeng_backup", SchemaVersion: 1, AppIdentifier: "com.qimeng.media",
		Data: gen.LegacyBackupData{
			MediaFiles: &[]gen.LegacyMediaFile{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", SizeBytes: 100, ModifiedAtMillis: 1},
			},
			DailyBrowse: &[]gen.LegacyDailyBrowse{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", DayStartMillis: 1756377600000, ViewCount: ptr(1)},
			},
		},
	}
	req1, req2 := base, base
	req1.ExportedAtMillis = ptr(int64(1000))
	req2.ExportedAtMillis = ptr(int64(2000))
	importBackup(t, e, req1)
	_, res := importBackup(t, e, req2)
	if got := derefVal(res.EventsReplayed); got != 1 {
		t.Errorf("新批次应重新回放 1 条，got %d", got)
	}
}

// derefVal *int 安全取值（nil = 0，便于断言）。
func derefVal(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

// EnrichAsset 测试假扫描器的富化空实现（真实重算由 scanner 包 enrich_test 覆盖）。
func (f *fakeScanner) EnrichAsset(context.Context, string, string) error { return nil }

// UpdateCustomSources 测试假扫描器的自定义出处空实现（真实逻辑在 scanner 包）。
func (f *fakeScanner) UpdateCustomSources(context.Context, []string) error { return nil }

// RecomputeEnrichment 测试假扫描器的存量重算空实现（真实逻辑在 scanner 包）。
func (f *fakeScanner) RecomputeEnrichment(context.Context, string) error { return nil }
