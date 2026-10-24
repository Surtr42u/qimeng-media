package scanner

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
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
