package httpapi

// config.go（客户端配置 GET/PUT /api/v1/config）测试：
// GET 无记录回缺省、PUT 落库回读、PUT 越界 400、
// upload.maxBytesMb 覆盖生效（min 语义）、autoAccept=false 上传 403、无 token 401。

import (
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// getConfig 读取客户端配置（带 token）。
func getConfig(t *testing.T, env *testEnv) gen.ClientConfig {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/config", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /config 期望 200，得到 %d", resp.StatusCode)
	}
	var cfg gen.ClientConfig
	if err := json.NewDecoder(resp.Body).Decode(&cfg); err != nil {
		t.Fatalf("解析配置响应失败: %v", err)
	}
	return cfg
}

// putConfig 提交配置（带 token），返回响应（调用方 close）。
func (e *testEnv) putConfig(t *testing.T, body string) *http.Response {
	t.Helper()
	return e.do(t, http.MethodPut, "/api/v1/config", body)
}

// TestClientConfigDefault：无存储记录 → 缺省值（workers 2 / thumbEdge 800 /
// maxBytesMb 2048 / autoAccept true，openapi ClientConfig default 字段）。
func TestClientConfigDefault(t *testing.T) {
	env := newTestEnv(t)
	cfg := getConfig(t, env)
	if cfg.Scan.Workers != 2 || cfg.Scan.ThumbEdge != 800 {
		t.Errorf("扫描缺省 = %+v, want {workers:2 thumbEdge:800}", cfg.Scan)
	}
	if cfg.Upload.MaxBytesMb != 2048 || !cfg.Upload.AutoAccept {
		t.Errorf("上传缺省 = %+v, want {maxBytesMb:2048 autoAccept:true}", cfg.Upload)
	}
}

// TestClientConfigPutRoundtrip：PUT 合法值 → 200 回显存储后全量；GET 回读一致。
func TestClientConfigPutRoundtrip(t *testing.T) {
	env := newTestEnv(t)
	body := `{"scan":{"workers":4,"thumbEdge":1200},"upload":{"maxBytesMb":512,"autoAccept":false}}`
	resp := env.putConfig(t, body)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT /config 期望 200，得到 %d", resp.StatusCode)
	}
	var echo gen.ClientConfig
	if err := json.NewDecoder(resp.Body).Decode(&echo); err != nil {
		t.Fatalf("解析 PUT 响应失败: %v", err)
	}
	if echo.Scan.Workers != 4 || echo.Scan.ThumbEdge != 1200 ||
		echo.Upload.MaxBytesMb != 512 || echo.Upload.AutoAccept {
		t.Errorf("PUT 回显 ≠ 提交值: %+v", echo)
	}
	cfg := getConfig(t, env)
	if cfg.Scan.Workers != 4 || cfg.Scan.ThumbEdge != 1200 ||
		cfg.Upload.MaxBytesMb != 512 || cfg.Upload.AutoAccept {
		t.Errorf("GET 回读不一致: %+v", cfg)
	}
}

// TestClientConfigPutOutOfRange：三字段分别越界 → 400 INVALID_PARAM，
// 且不落库（GET 仍回缺省）。
func TestClientConfigPutOutOfRange(t *testing.T) {
	env := newTestEnv(t)
	cases := []struct {
		name string
		body string
	}{
		{"workers 低于下限", `{"scan":{"workers":0,"thumbEdge":800},"upload":{"maxBytesMb":2048,"autoAccept":true}}`},
		{"workers 高于上限", `{"scan":{"workers":5,"thumbEdge":800},"upload":{"maxBytesMb":2048,"autoAccept":true}}`},
		{"thumbEdge 低于下限", `{"scan":{"workers":2,"thumbEdge":100},"upload":{"maxBytesMb":2048,"autoAccept":true}}`},
		{"thumbEdge 高于上限", `{"scan":{"workers":2,"thumbEdge":1601},"upload":{"maxBytesMb":2048,"autoAccept":true}}`},
		{"maxBytesMb 低于下限", `{"scan":{"workers":2,"thumbEdge":800},"upload":{"maxBytesMb":63,"autoAccept":true}}`},
		{"maxBytesMb 高于上限", `{"scan":{"workers":2,"thumbEdge":800},"upload":{"maxBytesMb":8193,"autoAccept":true}}`},
	}
	for _, c := range cases {
		resp := env.putConfig(t, c.body)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("%s：期望 400，得到 %d", c.name, resp.StatusCode)
		}
	}
	cfg := getConfig(t, env)
	if cfg.Scan.Workers != 2 || cfg.Upload.MaxBytesMb != 2048 {
		t.Errorf("越界 PUT 不应落库，GET = %+v", cfg)
	}
}

// TestClientConfigPutIncomplete：四叶子键缺任一（或类型不符）→ 400
// ——防缺 autoAccept 被 bool 零值 false 静默关闸；显式 autoAccept:false
// 是合法关闸（200 且落库 false）。
func TestClientConfigPutIncomplete(t *testing.T) {
	env := newTestEnv(t)
	cases := []struct{ name, body string }{
		{"缺 autoAccept", `{"scan":{"workers":2,"thumbEdge":800},"upload":{"maxBytesMb":2048}}`},
		{"缺 maxBytesMb", `{"scan":{"workers":2,"thumbEdge":800},"upload":{"autoAccept":true}}`},
		{"缺 workers", `{"scan":{"thumbEdge":800},"upload":{"maxBytesMb":2048,"autoAccept":true}}`},
		{"缺 thumbEdge", `{"scan":{"workers":2},"upload":{"maxBytesMb":2048,"autoAccept":true}}`},
		{"缺 upload 整组", `{"scan":{"workers":2,"thumbEdge":800}}`},
		{"缺 scan 整组", `{"upload":{"maxBytesMb":2048,"autoAccept":true}}`},
		{"workers 类型错", `{"scan":{"workers":"2","thumbEdge":800},"upload":{"maxBytesMb":2048,"autoAccept":true}}`},
	}
	for _, c := range cases {
		resp := env.putConfig(t, c.body)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("%s：期望 400，得到 %d", c.name, resp.StatusCode)
		}
	}
	// 显式 false 合法（与"缺键=false"本质不同）
	resp := env.putConfig(t, `{"scan":{"workers":2,"thumbEdge":800},"upload":{"maxBytesMb":2048,"autoAccept":false}}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("显式 autoAccept:false 期望 200，得到 %d", resp.StatusCode)
	}
	if cfg := getConfig(t, env); cfg.Upload.AutoAccept {
		t.Errorf("显式 false 应落库为 false，GET = %+v", cfg.Upload)
	}
}

// TestClientConfigPutBoundaries：界内边界值（workers 1/4、thumbEdge 200/1600、
// maxBytesMb 64/8192）全部放行 200。
func TestClientConfigPutBoundaries(t *testing.T) {
	env := newTestEnv(t)
	for _, body := range []string{
		`{"scan":{"workers":1,"thumbEdge":200},"upload":{"maxBytesMb":64,"autoAccept":true}}`,
		`{"scan":{"workers":4,"thumbEdge":1600},"upload":{"maxBytesMb":8192,"autoAccept":false}}`,
	} {
		resp := env.putConfig(t, body)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("界内边界值期望 200，得到 %d（body=%s）", resp.StatusCode, body)
		}
	}
}

// TestUploadMaxBytesMbOverride：PUT maxBytesMb=64 后上传实时收到 64MB 上限
// ——小文件 201；ContentLength 谎报 65MB → 413（覆盖前默认 2GB 不会拦）。
// min 语义：kv 提到 8192MB 时配置文件默认 2GB 仍更严，3GB 谎报照拦。
func TestUploadMaxBytesMbOverride(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 32, 32)

	// 覆盖上限到 64MB：小文件照常 201
	resp := env.putConfig(t, `{"scan":{"workers":2,"thumbEdge":800},"upload":{"maxBytesMb":64,"autoAccept":true}}`)
	_ = resp.Body.Close()
	ok := env.uploadBytes(t, env.libID, "", "within-override.jpg", jpg)
	defer func() { _ = ok.Body.Close() }()
	if ok.StatusCode != http.StatusCreated {
		t.Fatalf("64MB 上限内上传期望 201，得到 %d", ok.StatusCode)
	}

	// ContentLength 谎报 65MB（body 实际 1 字节）：第一道 ContentLength 判定即 413
	over := env.uploadFakeLength(t, env.libID, "", "fake-65m.jpg", 65<<20)
	defer func() { _ = over.Body.Close() }()
	if over.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("覆盖后 65MB 期望 413，得到 %d", over.StatusCode)
	}

	// min 语义：kv 放大到 8192MB 也不能越过配置文件默认 2GB
	resp = env.putConfig(t, `{"scan":{"workers":2,"thumbEdge":800},"upload":{"maxBytesMb":8192,"autoAccept":true}}`)
	_ = resp.Body.Close()
	over2 := env.uploadFakeLength(t, env.libID, "", "fake-3g.jpg", 3<<30)
	defer func() { _ = over2.Body.Close() }()
	if over2.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("kv 放大后 3GB 仍应被配置文件 2GB 拦下（413），得到 %d", over2.StatusCode)
	}
}

// TestUploadAutoAcceptDisabled：PUT autoAccept=false 后上传 403
// UPLOAD_DISABLED；恢复 true 后上传照常（实时生效、无缓存）。
func TestUploadAutoAcceptDisabled(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 32, 32)

	resp := env.putConfig(t, `{"scan":{"workers":2,"thumbEdge":800},"upload":{"maxBytesMb":2048,"autoAccept":false}}`)
	_ = resp.Body.Close()
	blocked := env.uploadBytes(t, env.libID, "", "blocked.jpg", jpg)
	_ = blocked.Body.Close()
	if blocked.StatusCode != http.StatusForbidden {
		t.Fatalf("autoAccept=false 上传期望 403，得到 %d", blocked.StatusCode)
	}

	resp = env.putConfig(t, `{"scan":{"workers":2,"thumbEdge":800},"upload":{"maxBytesMb":2048,"autoAccept":true}}`)
	_ = resp.Body.Close()
	ok := env.uploadBytes(t, env.libID, "", "reopened.jpg", jpg)
	defer func() { _ = ok.Body.Close() }()
	if ok.StatusCode != http.StatusCreated {
		t.Fatalf("恢复 autoAccept=true 后上传期望 201，得到 %d", ok.StatusCode)
	}
}

// TestConfigUnauthorized：无 token 读配置 401（PUT/上传通道同链路，
// topRouter default 分支统一要求 Bearer）。
func TestConfigUnauthorized(t *testing.T) {
	env := newTestEnv(t)
	resp, err := http.Get(env.ts.URL + "/api/v1/config")
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token GET /config 期望 401，得到 %d", resp.StatusCode)
	}
}

// zeroReader 恒输出零字节（配合 LimitReader 控流，不预分配大缓冲）。
type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 0
	}
	return len(p), nil
}

// uploadFakeLength 发起 ContentLength 谎报的上传：零字节流现生成（无大分配）
// + Expect: 100-continue——服务端在第一道 ContentLength 判定即回 413，
// 客户端收到最终响应后不再发送 body，测试无真实大流量。
// （直接对已知长度 reader 改 ContentLength 会被 Go http 客户端本地校验拒绝：
// "ContentLength=... with Body length ..."，故用不透明 reader + Expect。）
func (e *testEnv) uploadFakeLength(t *testing.T, libraryID, dir, filename string, fakeLen int64) *http.Response {
	t.Helper()
	url := e.ts.URL + "/api/v1/assets/upload?libraryId=" + libraryID +
		"&dir=" + dir + "&filename=" + filename
	req, err := http.NewRequest(http.MethodPost, url,
		io.NopCloser(io.LimitReader(zeroReader{}, fakeLen)))
	if err != nil {
		t.Fatalf("构造请求失败: %v", err)
	}
	req.ContentLength = fakeLen
	req.Header.Set("Expect", "100-continue")
	req.Header.Set("Authorization", "Bearer "+e.token)
	req.Header.Set("Content-Type", "application/octet-stream")
	return e.doRaw(t, req)
}
