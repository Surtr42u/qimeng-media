package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func portablePayload(t *testing.T, e *testEnv) gen.LegacyBackupImport {
	t.Helper()
	_, file := exportBackup(t, e)
	raw, err := json.Marshal(file)
	if err != nil {
		t.Fatal(err)
	}
	var req gen.LegacyBackupImport
	if err := json.Unmarshal(raw, &req); err != nil {
		t.Fatal(err)
	}
	return req
}

func requireImport(t *testing.T, e *testEnv, p gen.LegacyBackupImport, n int) {
	t.Helper()
	code, res := importBackup(t, e, p)
	if code != http.StatusOK {
		t.Fatalf("import=%d", code)
	}
	assertCount(t, "eventsReplayed", res.EventsReplayed, n)
}

// 同时覆盖 UUID、legacy 内容键和无键历史；原样导回与实时补传交叉不双计。
func TestPortableBackupConservesEventsAcrossRoundTrips(t *testing.T) {
	a := newTestEnv(t)
	aid := queryAssetID(t, a, "a.jpg")
	report := gen.ViewEventReport{AssetId: uuid.MustParse(aid), Kind: gen.Open, SessionId: "roundtrip", StartedAt: a.clock.Now(), ClientEventId: uuid.New()}
	postViewReport(t, a, report)
	legacy := traversalPayload(a.clock.Now().UnixMilli())
	if code, _ := importBackup(t, a, legacy); code != http.StatusOK {
		t.Fatal(code)
	}
	if err := a.q.InsertViewEvent(t.Context(), db.InsertViewEventParams{AssetID: aid, Kind: "dwell", SessionID: "pre-key-source", StartedAt: store.FormatTimestamp(a.clock.Now()), Seconds: sql.NullInt64{Int64: 17, Valid: true}}); err != nil {
		t.Fatal(err)
	}
	original := portablePayload(t, a)
	n := len(*original.Data.ViewEvents)
	requireImport(t, a, original, 0)
	if countEvents(t, a, "") != n {
		t.Fatal("A->A changed count")
	}
	b := newTestEnv(t)
	bid := queryAssetID(t, b, "a.jpg")
	report.AssetId = uuid.MustParse(bid)
	postViewReport(t, b, report) // 实时补传先于备份
	requireImport(t, b, original, n-1)
	requireImport(t, b, original, 0)
	postViewReport(t, b, report) // 备份后同事件再实时重试
	report.ClientEventId = uuid.New()
	report.Kind = gen.Dwell
	report.Seconds = ptr(float64(23))
	postViewReport(t, b, report) // B 独立新增事件
	back := portablePayload(t, b)
	requireImport(t, a, back, 1)
	requireImport(t, a, back, 0)
	if countEvents(t, a, "") != n+1 || countEvents(t, b, "") != n+1 {
		t.Fatal("A->B->A did not conserve events")
	}
	// 跨端 recordKey 可变化，原始事件时间、来源、秒数和稳定身份必须完全一致。
	byID := func(p gen.LegacyBackupImport) map[string]gen.PortableViewEvent {
		out := map[string]gen.PortableViewEvent{}
		for _, e := range *p.Data.ViewEvents {
			e.RecordKey = ""
			out[e.EventId] = e
		}
		return out
	}
	if !reflect.DeepEqual(byID(back), byID(portablePayload(t, a))) {
		t.Fatal("event provenance drift")
	}
}

func TestPortableBackupRejectsIncompletePayloadBeforeWrites(t *testing.T) {
	e := newTestEnv(t)
	p := portablePayload(t, e)
	p.Data.ViewEvents = nil
	if code, _ := importBackup(t, e, p); code != http.StatusBadRequest {
		t.Fatal(code)
	}
	p = portablePayload(t, e)
	f := (*p.Data.MediaFiles)[0]
	*p.Data.MediaFiles = append(*p.Data.MediaFiles, f)
	if code, _ := importBackup(t, e, p); code != http.StatusBadRequest {
		t.Fatal(code)
	}
}

func TestPortableThreeLibrariesDoNotCrossFiles(t *testing.T) {
	src := newTestEnv(t)
	add := func(e *testEnv, n string) string {
		t.Helper()
		ts := store.FormatTimestamp(e.clock.Now())
		lib, err := e.q.CreateLibrary(t.Context(), db.CreateLibraryParams{ID: uuid.NewString(), Name: n, RootPath: t.TempDir(), Kind: "normal", CreatedAt: ts})
		if err != nil {
			t.Fatal(err)
		}
		id := uuid.NewString()
		if _, err = e.q.UpsertAsset(t.Context(), db.UpsertAssetParams{AssetID: id, LibraryID: lib.ID, RelPath: "folder/same.jpg", FileName: "same.jpg", MediaType: "image", SizeBytes: 1, Mtime: ts, CreatedAt: ts, UpdatedAt: ts}); err != nil {
			t.Fatal(err)
		}
		return id
	}
	ids := []string{add(src, "one"), add(src, "two"), add(src, "three")}
	for i, id := range ids {
		for range i + 1 {
			if err := src.q.InsertViewEvent(t.Context(), db.InsertViewEventParams{AssetID: id, Kind: "open", SessionID: "seed", StartedAt: store.FormatTimestamp(src.clock.Now())}); err != nil {
				t.Fatal(err)
			}
		}
	}
	p := portablePayload(t, src)
	seen := map[string]bool{}
	for _, f := range *p.Data.MediaFiles {
		if seen[f.RecordKey] {
			t.Fatal("duplicate recordKey")
		}
		seen[f.RecordKey] = true
	}
	dst := newTestEnv(t)
	target := []string{add(dst, "one"), add(dst, "two"), add(dst, "three")}
	requireImport(t, dst, p, 6)
	for i, id := range target {
		rows, err := dst.q.CountAssetEvents(t.Context(), id)
		if err != nil || len(rows) != 1 || rows[0].Cnt != int64(i+1) {
			t.Fatalf("cross-file merge: %v %v", rows, err)
		}
	}
	// 无可考库名时三个同路径不可猜测。
	for i := range *p.Data.MediaFiles {
		(*p.Data.MediaFiles)[i].LibraryName = nil
	}
	empty := newTestEnv(t)
	add(empty, "one")
	add(empty, "two")
	add(empty, "three")
	requireImport(t, empty, p, 0)
}

func TestRestoreRebuildsOnlyRetainedAssetDailyStats(t *testing.T) {
	e := newTestEnv(t)
	id := queryAssetID(t, e, "a.jpg")
	postViewEvent(t, e, id, gen.Open, "restore-history", e.clock.Now())
	resp := e.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatal(resp.StatusCode)
	}
	items := e.trashList(t)
	resp = e.do(t, http.MethodPost, "/api/v1/trash/"+*items[0].Id+"/restore", "")
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatal(resp.StatusCode)
	}
	rows, err := e.q.SumDailyStatsBetween(t.Context(), db.SumDailyStatsBetweenParams{FromDay: "", ToDay: "9999-12-31"})
	if err != nil || len(rows) != 1 || rows[0].ViewCount != 1 {
		t.Fatalf("restore totals=%v err=%v", rows, err)
	}
	if countEvents(t, e, "open") != 1 {
		t.Fatal("restore changed history")
	}
}

func TestRestoreStatsFailureKeepsRetryIntent(t *testing.T) {
	e := newTestEnv(t)
	id := queryAssetID(t, e, "a.jpg")
	postViewEvent(t, e, id, gen.Open, "restore-retry", e.clock.Now())
	r := e.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
	closeBody(r)
	items := e.trashList(t)
	if _, err := e.conn.Exec(`CREATE TRIGGER fail_restore_stats BEFORE INSERT ON asset_daily_stats BEGIN SELECT RAISE(ABORT,'synthetic statistics failure'); END`); err != nil {
		t.Fatal(err)
	}
	r = e.do(t, http.MethodPost, "/api/v1/trash/"+*items[0].Id+"/restore", "")
	closeBody(r)
	if r.StatusCode != http.StatusInternalServerError {
		t.Fatal(r.StatusCode)
	}
	if _, err := e.q.GetAsset(t.Context(), id); err != sql.ErrNoRows {
		t.Fatalf("asset row should roll back: %v", err)
	}
	if len(e.trashList(t)) != 1 {
		t.Fatal("retry intent lost")
	}
	if _, err := e.conn.Exec(`DROP TRIGGER fail_restore_stats`); err != nil {
		t.Fatal(err)
	}
	r = e.do(t, http.MethodPost, "/api/v1/trash/"+*items[0].Id+"/restore", "")
	closeBody(r)
	if r.StatusCode != http.StatusOK {
		t.Fatal(r.StatusCode)
	}
	var views int
	if err := e.conn.QueryRow(`SELECT SUM(view_count) FROM asset_daily_stats WHERE asset_id=?`, id).Scan(&views); err != nil || views != 1 {
		t.Fatalf("retry views=%d err=%v", views, err)
	}
}

func TestRecordKeysStableAndUniqueEvenWithLiteralSuffix(t *testing.T) {
	rows := []db.ExportListAssetsRow{
		{AssetID: "a", FileName: "same.jpg", RelPath: "folder/same.jpg"},
		{AssetID: "b", FileName: "same.jpg", RelPath: "folder/same.jpg"},
		{AssetID: "c", FileName: "same.jpg", RelPath: "folder/same.jpg"},
		{AssetID: "d", FileName: "same.jpg @ folder#b#1", RelPath: "same.jpg @ folder#b#1"},
	}
	keys := func(rows []db.ExportListAssetsRow) map[string]string {
		seen := map[string]bool{}
		result := map[string]string{}
		for _, a := range assignRecordKeys(rows) {
			if seen[a.recordKey] {
				t.Fatal("collision")
			}
			seen[a.recordKey] = true
			result[a.row.AssetID] = a.recordKey
		}
		return result
	}
	want := keys(rows)
	for i, j := 0, len(rows)-1; i < j; i, j = i+1, j-1 {
		rows[i], rows[j] = rows[j], rows[i]
	}
	if !reflect.DeepEqual(want, keys(rows)) {
		t.Fatal("input ordering changed keys")
	}
}

func TestRebuildDoesNotEraseConcurrentCommittedDelta(t *testing.T) {
	e := newTestEnv(t)
	ctx := context.Background()
	id := queryAssetID(t, e, "a.jpg")
	postViewEvent(t, e, id, gen.Open, "before", e.clock.Now())
	tx, err := e.conn.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	q := e.q.WithTx(tx)
	if err = q.InsertViewEvent(ctx, db.InsertViewEventParams{AssetID: id, Kind: "open", SessionID: "concurrent", StartedAt: store.FormatTimestamp(e.clock.Now())}); err != nil {
		t.Fatal(err)
	}
	if err = q.UpsertAssetDailyStats(ctx, db.UpsertAssetDailyStatsParams{AssetID: id, Day: store.FormatDay(e.clock.Now()), ViewCount: 1}); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- e.s.RebuildAssetDailyStatsFromEvents(ctx) }()
	deadline := time.Now().Add(2 * time.Second)
	for e.conn.Stats().InUse < 2 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if e.conn.Stats().InUse < 2 {
		t.Fatal("rebuild did not obtain connection")
	}
	time.Sleep(100 * time.Millisecond)
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	if err = <-done; err != nil {
		t.Fatal(err)
	}
	var views int64
	if err = e.conn.QueryRow(`SELECT SUM(view_count) FROM asset_daily_stats WHERE asset_id=?`, id).Scan(&views); err != nil {
		t.Fatal(err)
	}
	if views != 2 {
		t.Fatalf("events=2 daily=%d", views)
	}
}

func TestThumbnailVersionsInvalidateOldRepresentations(t *testing.T) {
	e := newTestEnv(t)
	id := queryAssetID(t, e, "a.jpg")
	first := e.detail(t, id)
	fetch := func(url, tag string) *http.Response {
		t.Helper()
		req, err := http.NewRequest(http.MethodGet, e.ts.URL+url, nil)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("If-None-Match", tag)
		r, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	r := fetch(*first.ThumbUrlMd, "")
	tag := r.Header.Get("ETag")
	closeBody(r)
	if r.StatusCode != 200 {
		t.Fatal(r.StatusCode)
	}
	// 同版本缓存重生为不同字节，ETag 必须变化（探针无需 ffmpeg 改策略）。
	row, err := e.q.GetAsset(t.Context(), id)
	if err != nil {
		t.Fatal(err)
	}
	version := e.s.thumbs.RepresentationVersion(row.SizeBytes, row.Mtime)
	dst := e.s.thumbs.VersionedPath(id, e.s.thumbSize(gen.Md), version)
	if err = os.WriteFile(dst, []byte("synthetic-regenerated-thumbnail"), 0o644); err != nil {
		t.Fatal(err)
	}
	r = fetch(*first.ThumbUrlMd, tag)
	nextTag := r.Header.Get("ETag")
	closeBody(r)
	if r.StatusCode != 200 || nextTag == tag {
		t.Fatal("regeneration returned stale validator")
	}
	// 修改原件且走正式扫描同款 upsert 后，新 URL/磁盘身份改变，旧 URL 不发新内容。
	at := parseStoreTime(row.Mtime).Add(time.Second)
	if err = os.Chtimes(filepath.Join(e.media, "a.jpg"), at, at); err != nil {
		t.Fatal(err)
	}
	if _, err = e.q.UpsertAsset(t.Context(), db.UpsertAssetParams{AssetID: id, LibraryID: e.libID, RelPath: row.RelPath, FileName: row.FileName, MediaType: row.MediaType, SizeBytes: row.SizeBytes, Mtime: store.FormatTimestamp(at), CreatedAt: row.CreatedAt, UpdatedAt: store.FormatTimestamp(at)}); err != nil {
		t.Fatal(err)
	}
	fresh := e.detail(t, id)
	if *fresh.ThumbUrlMd == *first.ThumbUrlMd {
		t.Fatal("replacement URL unchanged")
	}
	r = fetch(*first.ThumbUrlMd, nextTag)
	closeBody(r)
	if r.StatusCode != 410 || r.Header.Get("Cache-Control") != "no-store" {
		t.Fatal("old version served new content")
	}
	r = fetch(*fresh.ThumbUrlMd, nextTag)
	closeBody(r)
	if r.StatusCode != 200 {
		t.Fatal(r.StatusCode)
	}
	legacyURL := strings.Split(*fresh.ThumbUrlMd, "&v=")[0]
	r = fetch(legacyURL, "")
	closeBody(r)
	if strings.Contains(r.Header.Get("Cache-Control"), "immutable") {
		t.Fatal("unversioned URL immutable")
	}
}
