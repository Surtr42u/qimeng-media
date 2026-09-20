// export_test.go：旧版格式备份导出端点测试（DOMAIN_RULES §10 逆向映射 + 回环）。
// 主用例造数覆盖全部可导出段 → GET /export/qimeng-backup 断言信封与段级内容
// （recordKey 同名消歧、last_opened_at NULL 分支、空段恒空数组）→ 导出 JSON
// 原样回灌 POST /import/qimeng-backup 断言段级计数与事件总量守恒 → 同批次
// 重复导入事件不翻倍。历史 500 条截取单独一用例。
package httpapi

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedExportData 在 newTestEnv 的 3 个已扫描资产上补齐各段造数，返回校验
// 所需的计数快照。事件与物化表成对写入（与生产写路径一致）。
func seedExportData(t *testing.T, e *testEnv) {
	t.Helper()
	ctx := t.Context()
	aID := queryAssetID(t, e, "a.jpg")
	bID := queryAssetID(t, e, "b.jpg")
	cID := queryAssetID(t, e, "c.mp4")

	// 事件 + 物化表：a.jpg 3 open/2 play/120s dwell；b.jpg 1 open；c.mp4 仅
	// 2 play（无 open——覆盖 mediaStats.last_opened_at NULL 分支）。
	base := time.Date(2026, 8, 20, 10, 0, 0, 0, time.UTC)
	stamp := store.FormatTimestamp(base)
	insert := func(assetID, kind string, seconds int64) {
		t.Helper()
		if err := e.q.InsertViewEvent(ctx, db.InsertViewEventParams{
			AssetID: assetID, Kind: kind, SessionID: "seed",
			StartedAt: stamp, Seconds: secondsOf(seconds),
		}); err != nil {
			t.Fatalf("造事件失败: %v", err)
		}
	}
	for i := 0; i < 3; i++ {
		insert(aID, "open", 0)
	}
	for i := 0; i < 2; i++ {
		insert(aID, "play", 0)
	}
	insert(aID, "dwell", 120)
	for i := 0; i < 2; i++ {
		insert(cID, "play", 0)
	}
	insert(bID, "open", 0)
	seedDaily := func(assetID string, view, play int, secs int64) {
		t.Helper()
		if err := e.q.UpsertAssetDailyStats(ctx, db.UpsertAssetDailyStatsParams{
			AssetID: assetID, Day: store.FormatDay(base),
			ViewCount: int64(view), PlayCount: int64(play), BrowseSeconds: secs,
		}); err != nil {
			t.Fatalf("造按天统计失败: %v", err)
		}
	}
	seedDaily(aID, 3, 2, 120)
	seedDaily(bID, 1, 0, 0)

	// 点赞两日（聚合导出 = 累计 2 / 最后 2026-08-21）+ 收藏 c.mp4。
	for _, day := range []string{"2026-08-20", "2026-08-21"} {
		if _, err := e.q.AddLikeOnDayIdempotent(ctx, db.AddLikeOnDayIdempotentParams{AssetID: aID, Day: day, CreatedAt: stamp}); err != nil {
			t.Fatalf("造点赞失败: %v", err)
		}
	}
	if _, err := e.q.AddFavorite(ctx, db.AddFavoriteParams{AssetID: cID, CreatedAt: stamp}); err != nil {
		t.Fatalf("造收藏失败: %v", err)
	}

	// 作者（常规 + cos_ 前缀）+ 关联 + 时间轴标签 + 关注。
	if err := e.q.ImportUpsertAuthor(ctx, db.ImportUpsertAuthorParams{
		ID: "seed_author", DisplayName: "示例作者", Type: "regular", CreatedAt: stamp,
	}); err != nil {
		t.Fatalf("造作者失败: %v", err)
	}
	if err := e.q.ImportUpsertAuthor(ctx, db.ImportUpsertAuthorParams{
		ID: "cos_seed_cos", DisplayName: "示例COS", Type: "cos", CreatedAt: stamp,
	}); err != nil {
		t.Fatalf("造COS作者失败: %v", err)
	}
	if err := e.q.ImportAddAssetAuthor(ctx, db.ImportAddAssetAuthorParams{AssetID: aID, AuthorID: "seed_author"}); err != nil {
		t.Fatalf("造作者关联失败: %v", err)
	}
	tag, err := e.q.CreateTag(ctx, db.CreateTagParams{ID: "seed-tag", Name: "示例标签", CreatedAt: stamp})
	if err != nil {
		t.Fatalf("造标签失败: %v", err)
	}
	if err := e.q.AddAssetTag(ctx, db.AddAssetTagParams{AssetID: aID, TagID: tag.ID, CreatedAt: stamp}); err != nil {
		t.Fatalf("造标签关联失败: %v", err)
	}
	if err := e.q.ImportInsertTimelineTag(ctx, db.ImportInsertTimelineTagParams{
		ID: "seed-tl", AssetID: cID, TimeMillis: 30000, Name: "示例打点", CreatedAt: stamp,
		AssetID_2: cID, TimeMillis_2: 30000, Name_2: "示例打点",
	}); err != nil {
		t.Fatalf("造时间轴标签失败: %v", err)
	}
	if _, err := e.q.ImportMarkAuthorFollowed(ctx, "cos_seed_cos"); err != nil {
		t.Fatalf("造关注失败: %v", err)
	}

	// 推荐偏好（kv_settings，与生产写入同键同格式）。
	prefs := gen.RecommendPrefs{TagRelevance: ptr(0.22), MaxRandom: ptr(0.3)}
	raw, err := json.Marshal(prefs)
	if err != nil {
		t.Fatalf("序列化偏好失败: %v", err)
	}
	if err := e.q.UpsertSetting(ctx, db.UpsertSettingParams{
		Key: settingKeyRecommendPrefs, Value: string(raw), UpdatedAt: stamp,
	}); err != nil {
		t.Fatalf("造偏好失败: %v", err)
	}
}

// secondsOf int64 → sql.NullInt64（open/play 传 0 落 NULL，与生产口径一致）。
func secondsOf(v int64) (n sql.NullInt64) {
	return sql.NullInt64{Int64: v, Valid: v > 0}
}

// exportBackup GET 导出端点并解析为信封结构。
func exportBackup(t *testing.T, e *testEnv) (http.Header, gen.LegacyBackupFile) {
	t.Helper()
	resp := e.do(t, http.MethodGet, "/api/v1/export/qimeng-backup", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("导出响应状态异常: %d", resp.StatusCode)
	}
	var file gen.LegacyBackupFile
	if err := json.NewDecoder(resp.Body).Decode(&file); err != nil {
		t.Fatalf("解析导出备份失败: %v", err)
	}
	return resp.Header, file
}

func TestExportQimengBackupRoundTrip(t *testing.T) {
	e := newTestEnv(t)
	seedExportData(t, e)
	// 同名对（不同子目录）：验证 recordKey「文件名 @ 文件夹名」与导入侧消歧回环。
	sub1ID, sub2ID := seedLegacyFiles(t, e)
	_ = sub1ID
	_ = sub2ID

	header, file := exportBackup(t, e)
	if cd := header.Get("Content-Disposition"); !strings.Contains(cd, "qimeng_backup.json") {
		t.Fatalf("Content-Disposition 缺失: %q", cd)
	}
	if file.Format != "qimeng_backup" || file.SchemaVersion != 1 || file.AppIdentifier != "com.qimeng.media" {
		t.Fatalf("信封字段异常: %+v", file)
	}
	if file.ExportedAtMillis == nil || *file.ExportedAtMillis <= 0 {
		t.Fatalf("exportedAtMillis 缺失: %+v", file.ExportedAtMillis)
	}
	data := file.Data

	// mediaFiles：3 扫描 + 2 同名对；同名对 recordKey 按父目录段消歧。
	if data.MediaFiles == nil || len(*data.MediaFiles) != 5 {
		t.Fatalf("mediaFiles 段异常: %d", len(derefSlice(data.MediaFiles)))
	}
	dupKeys := map[string]bool{}
	for _, f := range *data.MediaFiles {
		if f.FileName == "dup.jpg" {
			dupKeys[f.RecordKey] = true
		}
	}
	if !dupKeys["dup.jpg @ sub1"] || !dupKeys["dup.jpg @ sub2"] {
		t.Fatalf("同名 recordKey 消歧异常: %v", dupKeys)
	}

	// 作者/关注/关联/标签段。
	if data.Authors == nil || len(*data.Authors) != 2 {
		t.Fatalf("authors 段异常: %+v", data.Authors)
	}
	if data.FollowedAuthorIds == nil || len(*data.FollowedAuthorIds) != 1 || (*data.FollowedAuthorIds)[0] != "cos_seed_cos" {
		t.Fatalf("followedAuthorIds 段异常: %+v", data.FollowedAuthorIds)
	}
	if data.AuthorMediaRefs == nil || len(*data.AuthorMediaRefs) != 1 {
		t.Fatalf("authorMediaRefs 段异常: %+v", data.AuthorMediaRefs)
	}
	if data.Tags == nil || len(*data.Tags) != 1 || data.MediaTagRefs == nil || len(*data.MediaTagRefs) != 1 {
		t.Fatalf("标签段异常: %+v %+v", data.Tags, data.MediaTagRefs)
	}
	if data.TimelineTags == nil || len(*data.TimelineTags) != 1 {
		t.Fatalf("timelineTags 段异常: %+v", data.TimelineTags)
	}

	// 点赞聚合 + 收藏。
	if data.Likes == nil || len(*data.Likes) != 1 {
		t.Fatalf("likes 段异常: %+v", data.Likes)
	}
	like := (*data.Likes)[0]
	if like.RecordKey != "a.jpg" || like.LastLikeDate == nil || *like.LastLikeDate != "2026-08-21" {
		t.Fatalf("点赞聚合异常: %+v", like)
	}
	if data.Favorites == nil || len(*data.Favorites) != 1 || (*data.Favorites)[0] != "c.mp4" {
		t.Fatalf("favorites 段异常: %+v", data.Favorites)
	}

	// 统计三段：mediaStats 3 行（c.mp4 无 open → LastOpenedAt 为空）；
	// dailyBrowse 2 行（c.mp4 无物化行）；history = open 事件 4 条。
	if data.MediaStats == nil || len(*data.MediaStats) != 3 {
		t.Fatalf("mediaStats 段异常: %+v", data.MediaStats)
	}
	for _, st := range *data.MediaStats {
		if st.RecordKey == "c.mp4" && st.LastOpenedAtMillis != nil {
			t.Fatalf("c.mp4 无 open 事件，lastOpenedAtMillis 应缺省: %+v", st)
		}
	}
	if data.DailyBrowse == nil || len(*data.DailyBrowse) != 2 {
		t.Fatalf("dailyBrowse 段异常: %+v", data.DailyBrowse)
	}
	if data.History == nil || len(*data.History) != 4 {
		t.Fatalf("history 段异常: %+v", data.History)
	}

	// 无对应能力段恒空数组。
	if data.Settings == nil || len(*data.Settings) != 0 ||
		data.ScanSources == nil || len(*data.ScanSources) != 0 ||
		data.AlbumRules == nil || len(*data.AlbumRules) != 0 ||
		data.CosWorks == nil || len(*data.CosWorks) != 0 {
		t.Fatalf("空段应导出为空数组: %+v", data)
	}
	// appPrefs.recommendationPrefs 原样带出。
	if data.AppPrefs == nil || data.AppPrefs.RecommendationPrefs == nil ||
		data.AppPrefs.RecommendationPrefs.TagRelevance == nil || *data.AppPrefs.RecommendationPrefs.TagRelevance != 0.22 {
		t.Fatalf("appPrefs 段异常: %+v", data.AppPrefs)
	}

	// —— 回环：导出 JSON 原样导入第二个全新实例（迁移真实场景：A 库导出 → B 库恢复）——
	e2 := newTestEnv(t)
	seedLegacyFiles(t, e2) // 与 e1 同名同目录结构，保证按文件名+文件夹全部命中
	raw, err := json.Marshal(file)
	if err != nil {
		t.Fatalf("序列化回环载荷失败: %v", err)
	}
	var roundTrip gen.LegacyBackupImport
	if err := json.Unmarshal(raw, &roundTrip); err != nil {
		t.Fatalf("回环载荷转换失败: %v", err)
	}
	code, res := importBackup(t, e2, roundTrip)
	if code != http.StatusOK {
		t.Fatalf("回环导入状态异常: %d", code)
	}
	assertCount(t, "mediaFilesTotal", res.MediaFilesTotal, 5)
	assertCount(t, "assetsMatched", res.AssetsMatched, 5) // 同名对经 folderName 消歧命中
	assertCount(t, "authorsImported", res.AuthorsImported, 2)
	assertCount(t, "authorRefsImported", res.AuthorRefsImported, 1)
	assertCount(t, "tagsImported", res.TagsImported, 1)
	assertCount(t, "tagRefsImported", res.TagRefsImported, 1)
	// 事件守恒：dailyBrowse 全量（3+2+1 dwell + 1 open + 2 play = 9）回放，
	// mediaStats 差额 0，history 因 daily 行存在全跳过。
	assertCount(t, "eventsReplayed", res.EventsReplayed, 9)
	assertCount(t, "likesImported", res.LikesImported, 1)
	assertCount(t, "favoritesImported", res.FavoritesImported, 1)
	assertCount(t, "timelineTagsImported", res.TimelineTagsImported, 1)
	assertCount(t, "followedAuthorsMarked", res.FollowedAuthorsMarked, 1)
	if res.PrefsImported == nil || !*res.PrefsImported {
		t.Fatalf("prefsImported 应为 true: %+v", res.PrefsImported)
	}

	// 事件总量守恒：e1 造数 9 条不被回环污染；e2 全新实例回放后恰为 9。
	if n := countEvents(t, e, ""); n != 9 {
		t.Fatalf("导出源实例事件被污染: %d", n)
	}
	if n := countEvents(t, e2, ""); n != 9 {
		t.Fatalf("恢复实例事件总量异常: %d", n)
	}

	// 同批次重复导入：事件回放整体跳过（幂等批次锚）。
	_, res2 := importBackup(t, e2, roundTrip)
	assertCount(t, "eventsReplayed-2nd", res2.EventsReplayed, 0)
}

// TestExportHistoryCap500 history 段按旧库 view_history 上限截取最近 500 条。
func TestExportHistoryCap500(t *testing.T) {
	e := newTestEnv(t)
	aID := queryAssetID(t, e, "a.jpg")
	base := time.Date(2026, 8, 20, 10, 0, 0, 0, time.UTC)
	for i := 0; i < 505; i++ {
		if err := e.q.InsertViewEvent(t.Context(), db.InsertViewEventParams{
			AssetID: aID, Kind: "open", SessionID: "seed",
			StartedAt: store.FormatTimestamp(base.Add(time.Duration(i) * time.Minute)),
			Seconds:   secondsOf(0),
		}); err != nil {
			t.Fatalf("造事件失败: %v", err)
		}
	}
	_, file := exportBackup(t, e)
	if file.Data.History == nil || len(*file.Data.History) != 500 {
		t.Fatalf("history 应截取 500 条: %d", len(derefSlice(file.Data.History)))
	}
}

// TestImportBodyAboveGlobalLimit 备份导入端点的 64MB 专属限额：>1MB 全局
// JSON 上限的备份（6325 文件实测 ≈2.9MB）必须正常导入而非被红线拦截。
// 修复前用户实测：MaxBytesReader 掐断连接 → 浏览器 "Failed to fetch"。
func TestImportBodyAboveGlobalLimit(t *testing.T) {
	e := newTestEnv(t)
	// ~5000 条 × ~400B 字段 ≈ 2MB，稳定超 1MB 全局上限
	files := make([]gen.LegacyMediaFile, 0, 5000)
	for i := range 5000 {
		name := fmt.Sprintf("file-%04d-%s.mp4", i, strings.Repeat("p", 200))
		files = append(files, gen.LegacyMediaFile{
			RecordKey: name, FileName: name,
			MediaType:        gen.LegacyMediaFileMediaType("video"),
			SizeBytes:        100,
			ModifiedAtMillis: 1700000000000,
		})
	}
	code, res := importBackup(t, e, gen.LegacyBackupImport{
		Format: "qimeng_backup", SchemaVersion: 1, AppIdentifier: "com.qimeng.media",
		Data: gen.LegacyBackupData{MediaFiles: &files},
	})
	if code != http.StatusOK {
		t.Fatalf("大备份导入应成功，状态 %d", code)
	}
	assertCount(t, "mediaFilesTotal", res.MediaFilesTotal, 5000)
	assertCount(t, "assetsMatched", res.AssetsMatched, 0) // 无同名资产，仅清单导入
}

// derefSlice 可选段安全取长（nil 记 -1 便于失败信息定位）。
func derefSlice[T any](p *[]T) []T {
	if p == nil {
		return nil
	}
	return *p
}

// assertCount 计数断言（生成物字段全为 *int，nil 视为 0 报错前先展开）。
func assertCount(t *testing.T, name string, got *int, want int) {
	t.Helper()
	v := 0
	if got != nil {
		v = *got
	}
	if v != want {
		t.Fatalf("%s = %d, want %d", name, v, want)
	}
}
