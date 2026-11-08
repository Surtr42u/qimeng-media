package httpapi

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/deviceapply"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

const deviceApplyTestFingerprint = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) {
	clear(p)
	return len(p), nil
}

func newDeviceApplyHTTPTestServer(t *testing.T) (*Server, *deviceapply.Service) {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "deviceapply-http-test.db"))
	if err != nil {
		t.Fatalf("打开临时库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移临时库失败: %v", err)
	}
	clock := func() time.Time { return time.Date(2026, 11, 7, 4, 0, 0, 0, time.UTC) }
	svc := deviceapply.New(deviceapply.Options{
		Conn: conn, Queries: db.New(conn),
		RootDir: filepath.Join(t.TempDir(), "device-apply-packages"),
		Now:     clock,
		Logger:  slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	return &Server{
		deviceApply: svc,
		logger:      slog.New(slog.NewTextHandler(io.Discard, nil)),
	}, svc
}

func deviceApplyDigest(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func deviceApplyMaster(size int) []byte {
	data := make([]byte, size)
	for i := range data {
		data[i] = byte(i%249) + 1
	}
	return data
}

// deviceApplyManifest builds a manifest with one segment per uploaded file.
func deviceApplyManifest(target string, master, backup []byte) []byte {
	segments := make([]map[string]interface{}, 0, 2)
	if master != nil {
		segments = append(segments, map[string]interface{}{
			"kind": "masterTxt", "bytes": len(master), "sha256": deviceApplyDigest(master),
		})
	}
	if backup != nil {
		segments = append(segments, map[string]interface{}{
			"kind": "v2Backup", "bytes": len(backup), "sha256": deviceApplyDigest(backup),
		})
	}
	raw, _ := json.Marshal(map[string]interface{}{
		"targetDeviceId": target, "protocolVersion": 1,
		"rulesFingerprint": deviceApplyTestFingerprint,
		"segments":         segments,
	})
	return raw
}

func deviceApplyForm(t *testing.T, manifest []byte, master, backup []byte) (*bytes.Buffer, string) {
	t.Helper()
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	if manifest != nil {
		if err := writer.WriteField("manifest", string(manifest)); err != nil {
			t.Fatalf("写 manifest 失败: %v", err)
		}
	}
	add := func(field, filename string, data []byte) {
		part, err := writer.CreateFormFile(field, filename)
		if err != nil {
			t.Fatalf("创建段字段失败: %v", err)
		}
		if _, err := part.Write(data); err != nil {
			t.Fatalf("写段字段失败: %v", err)
		}
	}
	if master != nil {
		add("masterTxt", "masterTxt.txt", master)
	}
	if backup != nil {
		add("v2Backup", "v2Backup.json", backup)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("关闭 multipart 失败: %v", err)
	}
	return body, writer.FormDataContentType()
}

func postDeviceApply(t *testing.T, s *Server, manifest, master, backup []byte) *httptest.ResponseRecorder {
	t.Helper()
	body, contentType := deviceApplyForm(t, manifest, master, backup)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/device/apply-tasks", body)
	req.Header.Set("Content-Type", contentType)
	rec := httptest.NewRecorder()
	s.PostApiV1DeviceApplyTasks(rec, req)
	return rec
}

func createDeviceApplyHTTP(t *testing.T, s *Server, master, backup []byte) gen.DeviceApplyTask {
	t.Helper()
	rec := postDeviceApply(t, s, deviceApplyManifest("phone-a", master, backup), master, backup)
	if rec.Code != http.StatusCreated {
		t.Fatalf("创建返回 %d: %s", rec.Code, rec.Body.String())
	}
	var task gen.DeviceApplyTask
	if err := json.Unmarshal(rec.Body.Bytes(), &task); err != nil {
		t.Fatalf("解析创建响应失败: %v", err)
	}
	return task
}

func decodeDeviceApplyTask(t *testing.T, rec *httptest.ResponseRecorder) gen.DeviceApplyTask {
	t.Helper()
	var task gen.DeviceApplyTask
	if err := json.Unmarshal(rec.Body.Bytes(), &task); err != nil {
		t.Fatalf("解析任务响应失败: %v", err)
	}
	return task
}

func deviceApplyErrorCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var apiErr gen.Error
	if err := json.Unmarshal(rec.Body.Bytes(), &apiErr); err != nil {
		t.Fatalf("解析错误响应失败: %v", err)
	}
	return apiErr.Code
}

func TestDeviceApplyCreateAndListHTTP(t *testing.T) {
	s, _ := newDeviceApplyHTTPTestServer(t)
	master := deviceApplyMaster(1024)
	backup := deviceApplyMaster(2048)
	task := createDeviceApplyHTTP(t, s, master, backup)
	if task.Status != gen.DeviceApplyStatusPending || len(task.Segments) != 2 {
		t.Fatalf("创建响应异常: %+v", task)
	}
	if task.TargetDeviceId != "phone-a" || task.ProtocolVersion != 1 || len(task.Segments) != 2 {
		t.Fatalf("创建元数据异常: %+v", task)
	}
	for _, segment := range task.Segments {
		if segment.Kind != gen.DeviceApplySegmentKindMasterTxt && segment.Kind != gen.DeviceApplySegmentKindV2Backup {
			t.Fatalf("段类型异常: %s", segment.Kind)
		}
	}

	list := func(params gen.GetApiV1DeviceApplyTasksParams) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		s.GetApiV1DeviceApplyTasks(rec, httptest.NewRequest(http.MethodGet, "/", nil), params)
		return rec
	}
	rec := list(gen.GetApiV1DeviceApplyTasksParams{TargetDevice: "phone-a"})
	if rec.Code != http.StatusOK {
		t.Fatalf("列表返回 %d: %s", rec.Code, rec.Body.String())
	}
	var page gen.DeviceApplyTaskList
	if err := json.Unmarshal(rec.Body.Bytes(), &page); err != nil {
		t.Fatalf("解析列表失败: %v", err)
	}
	if len(page.Items) != 1 || page.Items[0].Id != task.Id || page.Items[0].SegmentCount != 2 || page.NextCursor != nil {
		t.Fatalf("列表异常: %+v", page)
	}

	claimed := gen.DeviceApplyTaskStatus("claimed")
	empty := list(gen.GetApiV1DeviceApplyTasksParams{TargetDevice: "phone-a", Status: &claimed})
	if empty.Code != http.StatusOK {
		t.Fatalf("状态过滤返回 %d", empty.Code)
	}
	var filtered gen.DeviceApplyTaskList
	_ = json.Unmarshal(empty.Body.Bytes(), &filtered)
	if len(filtered.Items) != 0 {
		t.Fatalf("claimed 过滤应为空: %+v", filtered.Items)
	}

	other := list(gen.GetApiV1DeviceApplyTasksParams{TargetDevice: "phone-b"})
	var otherPage gen.DeviceApplyTaskList
	_ = json.Unmarshal(other.Body.Bytes(), &otherPage)
	if len(otherPage.Items) != 0 {
		t.Fatalf("其他设备不应看到任务: %+v", otherPage.Items)
	}

	zero, tooLarge := 0, deviceapply.MaxListLimit+1
	badCursor := "not-a-cursor"
	cases := map[string]gen.GetApiV1DeviceApplyTasksParams{
		"empty-target": {TargetDevice: ""},
		"zero-limit":   {TargetDevice: "phone-a", Limit: &zero},
		"large-limit":  {TargetDevice: "phone-a", Limit: &tooLarge},
		"bad-cursor":   {TargetDevice: "phone-a", Cursor: &badCursor},
	}
	for name, params := range cases {
		rec := list(params)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("%s 返回 %d，期望 400: %s", name, rec.Code, rec.Body.String())
		}
	}
	if code := deviceApplyErrorCode(t, list(cases["bad-cursor"])); code != codeInvalidCursor {
		t.Fatalf("非法游标错误码 = %s", code)
	}
}

func TestDeviceApplyCreateValidationHTTP(t *testing.T) {
	s, _ := newDeviceApplyHTTPTestServer(t)
	master := deviceApplyMaster(32)

	postRaw := func(contentType string, body io.Reader) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/device/apply-tasks", body)
		req.Header.Set("Content-Type", contentType)
		rec := httptest.NewRecorder()
		s.PostApiV1DeviceApplyTasks(rec, req)
		return rec
	}
	if rec := postRaw("application/json", strings.NewReader("{}")); rec.Code != http.StatusBadRequest {
		t.Fatalf("非 multipart 返回 %d，期望 400", rec.Code)
	}

	// 缺少 manifest。
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, _ := writer.CreateFormFile("masterTxt", "master.txt")
	_, _ = part.Write(master)
	_ = writer.Close()
	if rec := postRaw(writer.FormDataContentType(), body); rec.Code != http.StatusBadRequest {
		t.Fatalf("缺 manifest 返回 %d，期望 400", rec.Code)
	}

	// manifest 不是 JSON。
	if rec := postDeviceApply(t, s, []byte("{"), master, nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 manifest 返回 %d，期望 400", rec.Code)
	}

	// 声明摘要与实际文件不符。
	manifest := deviceApplyManifest("phone-a", master, nil)
	wrong := bytes.Replace(manifest, []byte(deviceApplyDigest(master)), []byte(deviceApplyDigest(deviceApplyMaster(33))), 1)
	if rec := postDeviceApply(t, s, wrong, master, nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("摘要不符返回 %d，期望 400: %s", rec.Code, rec.Body.String())
	}

	// 指纹非法。
	badFingerprint := bytes.Replace(manifest, []byte(deviceApplyTestFingerprint), []byte("abcd"), 1)
	if rec := postDeviceApply(t, s, badFingerprint, master, nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("指纹非法返回 %d，期望 400", rec.Code)
	}

	// 段文件自身超过 1MiB：协议层直接 413（不进入 manifest 校验）。
	overSizedMaster := deviceApplyMaster(deviceapply.MaxMasterTxtBytes + 1)
	if rec := postDeviceApply(t, s, deviceApplyManifest("phone-a", overSizedMaster, nil), overSizedMaster, nil); rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("段文件超限返回 %d，期望 413: %s", rec.Code, rec.Body.String())
	}

	// manifest 声明 masterTxt 超过 1MiB。
	var parsed map[string]interface{}
	_ = json.Unmarshal(manifest, &parsed)
	parsed["segments"].([]interface{})[0].(map[string]interface{})["bytes"] = deviceapply.MaxMasterTxtBytes + 1
	oversized, _ := json.Marshal(parsed)
	if rec := postDeviceApply(t, s, oversized, master, nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("超 1MiB 声明返回 %d，期望 400", rec.Code)
	}

	// 未知文本字段。
	body = &bytes.Buffer{}
	writer = multipart.NewWriter(body)
	_ = writer.WriteField("manifest", string(manifest))
	_ = writer.WriteField("bogus", "1")
	part, _ = writer.CreateFormFile("masterTxt", "master.txt")
	_, _ = part.Write(master)
	_ = writer.Close()
	if rec := postRaw(writer.FormDataContentType(), body); rec.Code != http.StatusBadRequest {
		t.Fatalf("未知字段返回 %d，期望 400", rec.Code)
	}

	// 重复 manifest 字段。
	body = &bytes.Buffer{}
	writer = multipart.NewWriter(body)
	_ = writer.WriteField("manifest", string(manifest))
	_ = writer.WriteField("manifest", string(manifest))
	part, _ = writer.CreateFormFile("masterTxt", "master.txt")
	_, _ = part.Write(master)
	_ = writer.Close()
	if rec := postRaw(writer.FormDataContentType(), body); rec.Code != http.StatusBadRequest {
		t.Fatalf("重复 manifest 返回 %d，期望 400", rec.Code)
	}

	// 整体请求体超过 66MiB：用生成式读取器避免在测试里分配 66MiB。
	header := "--boundary\r\nContent-Disposition: form-data; name=\"manifest\"\r\n\r\n" +
		string(manifest) +
		"\r\n--boundary\r\nContent-Disposition: form-data; name=\"v2Backup\"; filename=\"v2.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n"
	bodyReader := io.MultiReader(strings.NewReader(header), io.LimitReader(zeroReader{}, deviceApplyMaxBody+1024))
	rec := postRaw("multipart/form-data; boundary=boundary", bodyReader)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("超大请求体返回 %d，期望 413: %s", rec.Code, rec.Body.String())
	}
	if code := deviceApplyErrorCode(t, rec); code != codeTooLarge {
		t.Fatalf("413 错误码 = %s", code)
	}
}

func TestDeviceApplyClaimResultCancelHTTP(t *testing.T) {
	s, _ := newDeviceApplyHTTPTestServer(t)
	task := createDeviceApplyHTTP(t, s, deviceApplyMaster(64), nil)

	claim := func(id uuid.UUID) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		s.PostApiV1DeviceApplyTasksIdClaim(rec, httptest.NewRequest(http.MethodPost, "/", nil), id)
		return rec
	}
	result := func(id uuid.UUID, body gen.DeviceApplyResultRequest) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(body)
		req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()
		s.PostApiV1DeviceApplyTasksIdResult(rec, req, id)
		return rec
	}
	cancel := func(id uuid.UUID) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		s.PostApiV1DeviceApplyTasksIdCancel(rec, httptest.NewRequest(http.MethodPost, "/", nil), id)
		return rec
	}

	first := claim(task.Id)
	if first.Code != http.StatusOK {
		t.Fatalf("领取返回 %d: %s", first.Code, first.Body.String())
	}
	claimed := decodeDeviceApplyTask(t, first)
	if claimed.Status != gen.DeviceApplyStatusClaimed || claimed.ClaimedAt == nil {
		t.Fatalf("领取响应异常: %+v", claimed)
	}
	second := claim(task.Id)
	if second.Code != http.StatusOK {
		t.Fatalf("重复领取返回 %d，期望 200", second.Code)
	}
	if replay := decodeDeviceApplyTask(t, second); !replay.ClaimedAt.Equal(*claimed.ClaimedAt) {
		t.Fatalf("重复领取改变了 claimedAt: %v != %v", replay.ClaimedAt, claimed.ClaimedAt)
	}

	// 非法回传体：JSON 错误与 pending 状态都不是 500。
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader("{"))
	req.Header.Set("Content-Type", "application/json")
	badJSON := httptest.NewRecorder()
	s.PostApiV1DeviceApplyTasksIdResult(badJSON, req, task.Id)
	if badJSON.Code != http.StatusBadRequest {
		t.Fatalf("非法回传 JSON 返回 %d，期望 400", badJSON.Code)
	}

	hash := gen.DeviceApplyLocalHash{Kind: gen.DeviceApplySegmentKindMasterTxt, Sha256: deviceApplyDigest(deviceApplyMaster(64))}
	appliedBody := gen.DeviceApplyResultRequest{
		Status:            gen.DeviceApplyResultStatusApplied,
		Readback:          &map[string]interface{}{"authors": float64(1), "nested": map[string]interface{}{"b": float64(2), "a": float64(1)}},
		DeviceLocalHashes: &[]gen.DeviceApplyLocalHash{hash},
	}
	appliedRec := result(task.Id, appliedBody)
	if appliedRec.Code != http.StatusOK {
		t.Fatalf("回传返回 %d: %s", appliedRec.Code, appliedRec.Body.String())
	}
	applied := decodeDeviceApplyTask(t, appliedRec)
	if applied.Status != gen.DeviceApplyStatusApplied || applied.Result == nil || applied.Result.Status != gen.DeviceApplyResultStatusApplied {
		t.Fatalf("回传响应异常: %+v", applied)
	}
	if applied.Result.Readback["authors"] != float64(1) || len(applied.Result.DeviceLocalHashes) != 1 {
		t.Fatalf("回读字段异常: %+v", applied.Result)
	}

	replayBody := appliedBody
	replayBody.Readback = &map[string]interface{}{"nested": map[string]interface{}{"a": float64(1), "b": float64(2)}, "authors": float64(1)}
	replayRec := result(task.Id, replayBody)
	if replayRec.Code != http.StatusOK {
		t.Fatalf("相同结果重放返回 %d，期望 200: %s", replayRec.Code, replayRec.Body.String())
	}
	if replay := decodeDeviceApplyTask(t, replayRec); !replay.FinishedAt.Equal(*applied.FinishedAt) {
		t.Fatalf("重放改变了 finishedAt: %v != %v", replay.FinishedAt, applied.FinishedAt)
	}
	changed := appliedBody
	changed.Status = gen.DeviceApplyResultStatusPartial
	if rec := result(task.Id, changed); rec.Code != http.StatusConflict {
		t.Fatalf("不同结果重放返回 %d，期望 409", rec.Code)
	}
	if rec := claim(task.Id); rec.Code != http.StatusConflict {
		t.Fatalf("终态领取返回 %d，期望 409", rec.Code)
	}
	if rec := cancel(task.Id); rec.Code != http.StatusConflict {
		t.Fatalf("终态取消返回 %d，期望 409", rec.Code)
	}

	// 未领取回传 409；取消后领取/回传 409；重复取消幂等。
	pending := createDeviceApplyHTTP(t, s, deviceApplyMaster(16), nil)
	if rec := result(pending.Id, gen.DeviceApplyResultRequest{Status: gen.DeviceApplyResultStatusApplied}); rec.Code != http.StatusConflict {
		t.Fatalf("未领取回传返回 %d，期望 409", rec.Code)
	}
	cancelledRec := cancel(pending.Id)
	if cancelledRec.Code != http.StatusOK {
		t.Fatalf("取消返回 %d: %s", cancelledRec.Code, cancelledRec.Body.String())
	}
	if again := cancel(pending.Id); again.Code != http.StatusOK {
		t.Fatalf("重复取消返回 %d，期望 200", again.Code)
	}
	if rec := claim(pending.Id); rec.Code != http.StatusConflict {
		t.Fatalf("取消后领取返回 %d，期望 409", rec.Code)
	}
	if rec := result(pending.Id, gen.DeviceApplyResultRequest{Status: gen.DeviceApplyResultStatusApplied}); rec.Code != http.StatusConflict {
		t.Fatalf("取消后回传返回 %d，期望 409", rec.Code)
	}

	// 未领取先取消（pending -> cancelled）。
	another := createDeviceApplyHTTP(t, s, deviceApplyMaster(16), nil)
	if rec := cancel(another.Id); rec.Code != http.StatusOK {
		t.Fatalf("pending 取消返回 %d", rec.Code)
	}

	missing := uuid.New()
	for name, rec := range map[string]*httptest.ResponseRecorder{
		"claim":  claim(missing),
		"result": result(missing, gen.DeviceApplyResultRequest{Status: gen.DeviceApplyResultStatusApplied}),
		"cancel": cancel(missing),
	} {
		if rec.Code != http.StatusNotFound {
			t.Fatalf("%s 缺失任务返回 %d，期望 404", name, rec.Code)
		}
		if code := deviceApplyErrorCode(t, rec); code != codeNotFound {
			t.Fatalf("%s 404 错误码 = %s", name, code)
		}
	}

	detail := httptest.NewRecorder()
	s.GetApiV1DeviceApplyTasksId(detail, httptest.NewRequest(http.MethodGet, "/", nil), missing)
	if detail.Code != http.StatusNotFound {
		t.Fatalf("详情缺失返回 %d，期望 404", detail.Code)
	}
}

func TestDeviceApplySegmentHTTP(t *testing.T) {
	s, _ := newDeviceApplyHTTPTestServer(t)
	master := deviceApplyMaster(256)
	task := createDeviceApplyHTTP(t, s, master, nil)
	download := func(id uuid.UUID, kind gen.DeviceApplySegmentKind) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		s.GetApiV1DeviceApplyTasksIdSegmentsKind(rec, httptest.NewRequest(http.MethodGet, "/", nil), id, kind)
		return rec
	}

	// 未领取也可下载（pending/claimed 都是活动态）。
	okRec := download(task.Id, gen.DeviceApplySegmentKindMasterTxt)
	if okRec.Code != http.StatusOK {
		t.Fatalf("下载返回 %d: %s", okRec.Code, okRec.Body.String())
	}
	if ct := okRec.Header().Get("Content-Type"); ct != contentTypeOctetStream {
		t.Fatalf("Content-Type = %q", ct)
	}
	if got := okRec.Header().Get("Content-Length"); got != "256" {
		t.Fatalf("Content-Length = %q", got)
	}
	if got := okRec.Header().Get(headerContentSHA256); got != deviceApplyDigest(master) {
		t.Fatalf("X-Content-SHA256 = %q", got)
	}
	if !bytes.Equal(okRec.Body.Bytes(), master) {
		t.Fatal("段字节不一致")
	}

	if rec := download(task.Id, gen.DeviceApplySegmentKindV2Backup); rec.Code != http.StatusNotFound {
		t.Fatalf("未声明段返回 %d，期望 404", rec.Code)
	}
	if rec := download(task.Id, gen.DeviceApplySegmentKind("bogus")); rec.Code != http.StatusBadRequest {
		t.Fatalf("非法段类型返回 %d，期望 400", rec.Code)
	}
	if rec := download(uuid.New(), gen.DeviceApplySegmentKindMasterTxt); rec.Code != http.StatusNotFound {
		t.Fatalf("缺失任务下载返回 %d，期望 404", rec.Code)
	}

	claimRec := httptest.NewRecorder()
	s.PostApiV1DeviceApplyTasksIdClaim(claimRec, httptest.NewRequest(http.MethodPost, "/", nil), task.Id)
	if claimRec.Code != http.StatusOK {
		t.Fatalf("领取返回 %d", claimRec.Code)
	}
	raw, _ := json.Marshal(gen.DeviceApplyResultRequest{Status: gen.DeviceApplyResultStatusApplied})
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	resultRec := httptest.NewRecorder()
	s.PostApiV1DeviceApplyTasksIdResult(resultRec, req, task.Id)
	if resultRec.Code != http.StatusOK {
		t.Fatalf("回传返回 %d: %s", resultRec.Code, resultRec.Body.String())
	}
	if rec := download(task.Id, gen.DeviceApplySegmentKindMasterTxt); rec.Code != http.StatusConflict {
		t.Fatalf("终态下载返回 %d，期望 409", rec.Code)
	}
}
