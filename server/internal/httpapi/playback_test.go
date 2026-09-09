package httpapi

// 播放进度上报（PUT /assets/{id}/progress，migration 0006）：
//   - 上报 → 详情读回断点续播位置
//   - 重复上报只保留最新值（"最新状态"语义，非累加）
//   - 资产不存在（已删除）→ 404
//   - 进度不进 view_events 事件流（与 ViewEvent 的分工，ADR-0005 口径不变）

import (
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// videoAssetID 取假扫描入库的 c.mp4 资产 ID。
func videoAssetID(t *testing.T, env *testEnv) string {
	t.Helper()
	rows, err := env.conn.Query("SELECT asset_id FROM assets WHERE file_name = 'c.mp4'")
	if err != nil {
		t.Fatalf("查询视频资产失败: %v", err)
	}
	defer func() { _ = rows.Close() }()
	if !rows.Next() {
		t.Fatal("c.mp4 未入库")
	}
	var id string
	if err := rows.Scan(&id); err != nil {
		t.Fatalf("读取 asset_id 失败: %v", err)
	}
	return id
}

// putProgress 上报一次播放进度并断言响应码。
func putProgress(t *testing.T, env *testEnv, assetID string, seconds float64, wantCode int) {
	t.Helper()
	body, err := json.Marshal(gen.ProgressUpdate{PositionSeconds: float64(seconds)})
	if err != nil {
		t.Fatalf("构造进度请求失败: %v", err)
	}
	resp := env.do(t, http.MethodPut, "/api/v1/assets/"+assetID+"/progress", string(body))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != wantCode {
		t.Fatalf("进度上报期望 %d，得到 %d", wantCode, resp.StatusCode)
	}
}

func TestPlaybackProgressReportAndReadBack(t *testing.T) {
	env := newTestEnv(t)
	id := videoAssetID(t, env)

	putProgress(t, env, id, 100.0, http.StatusNoContent)

	// 覆盖语义：第二次上报替换第一次，而非累加/并列
	putProgress(t, env, id, 250.5, http.StatusNoContent)

	detail := env.detail(t, id)
	if detail.LastPositionSeconds == nil || *detail.LastPositionSeconds != 250.5 {
		t.Fatalf("续播位置 = %v, want 250.5", detail.LastPositionSeconds)
	}
}

func TestPlaybackProgressNotInEventStream(t *testing.T) {
	env := newTestEnv(t)
	id := videoAssetID(t, env)

	putProgress(t, env, id, 42, http.StatusNoContent)

	rows, err := env.conn.Query("SELECT COUNT(*) FROM view_events WHERE asset_id = ?", id)
	if err != nil {
		t.Fatalf("查询事件流失败: %v", err)
	}
	defer func() { _ = rows.Close() }()
	if !rows.Next() {
		t.Fatal("COUNT 查询无结果行")
	}
	var n int
	if err := rows.Scan(&n); err != nil {
		t.Fatalf("读取计数失败: %v", err)
	}
	if n != 0 {
		t.Fatalf("进度上报不应产生事件流行数 = %d, want 0", n)
	}
}

func TestPlaybackProgressUnknownAsset404(t *testing.T) {
	env := newTestEnv(t)
	putProgress(t, env, "00000000-0000-0000-0000-000000000000", 10, http.StatusNotFound)
}
