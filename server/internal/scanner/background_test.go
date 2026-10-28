package scanner

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"

	"sync/atomic"

	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
	"testing"
)

func TestBackgroundScanLogIncludesWorkAndSuppressesEmptyInfo(t *testing.T) {
	e := newTestEnv(t, newProbeStub(nil, nil).call)
	var output bytes.Buffer
	e.s.logger = slog.New(slog.NewJSONHandler(&output, nil))
	if err := e.s.scanBackground(context.Background(), e.lib, "轮询扫描"); err != nil {
		t.Fatal(err)
	}
	if output.Len() != 0 {
		t.Fatalf("空扫不能刷 Info: %s", output.String())
	}
	e.writeFile(t, "a.jpg", 17)
	if err := e.s.scanBackground(context.Background(), e.lib, "轮询扫描"); err != nil {
		t.Fatal(err)
	}
	var record map[string]any
	if err := json.Unmarshal(output.Bytes(), &record); err != nil {
		t.Fatal(err)
	}
	if record["scanned"] != float64(1) || record["added"] != float64(1) || record["elapsedMs"] == nil || record["level"] != "INFO" {
		t.Fatalf("有变化必须保留工作量和耗时: %v", record)
	}
	output.Reset()
	e.s.logger = slog.New(slog.NewJSONHandler(&output, &slog.HandlerOptions{Level: slog.LevelDebug}))
	if err := e.s.scanBackground(context.Background(), e.lib, "轮询扫描"); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(output.Bytes(), &record); err != nil {
		t.Fatal(err)
	}
	if record["level"] != "DEBUG" || record["scanned"] != float64(1) || record["added"] != float64(0) {
		t.Fatalf("空扫调试日志必须可见: %v", record)
	}
}

func TestPollLibrariesRecoversChildPanicAndReleasesAdmission(t *testing.T) {
	ctx := context.Background()
	var calls atomic.Int32
	e := newTestEnv(t, func(context.Context, string) (*thumbnail.ProbeResult, error) {
		calls.Add(1)
		panic("synthetic probe failure")
	})
	e.writeFile(t, "panic.mp4", 17)
	good, err := e.q.CreateLibrary(ctx, db.CreateLibraryParams{
		ID: "healthy-poll-library", Name: "healthy", RootPath: t.TempDir(),
		Kind: LibraryKindNormal, CreatedAt: e.lib.CreatedAt,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := e.q.UpsertSetting(ctx, db.UpsertSettingParams{Key: "client_config", Value: `{"scan":{"workers":1}}`, UpdatedAt: e.lib.CreatedAt}); err != nil {
		t.Fatal(err)
	}
	// 单工作槽：panic必须释放名额，后续健康库及下一批才有机会完成。
	healthy := *e
	healthy.lib = good
	healthy.writeFile(t, "good.jpg", 17)
	for range 2 {
		if err := e.s.pollLibraries(ctx, []db.Library{e.lib, good}); err != nil {
			t.Fatal(err)
		}
		if _, err := e.q.GetAssetByPath(ctx, db.GetAssetByPathParams{LibraryID: good.ID, RelPath: "good.jpg"}); err != nil {
			t.Fatalf("健康库未完成: %v", err)
		}
	}
	if calls.Load() != 2 {
		t.Fatalf("panic库下一批未重试: %d", calls.Load())
	}
	if _, err := e.s.Scan(ctx, good); err != nil {
		t.Fatalf("并发闸门未释放: %v", err)
	}
}
