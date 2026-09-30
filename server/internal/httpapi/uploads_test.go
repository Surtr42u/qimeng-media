package httpapi

// 断点续传上传端点测试（ADR-0028）：真 JPEG 分片续传全链（create→PATCH×3
// 跨 offset→complete 201 入库 + 修订号 bump）、断点探测、offset 不匹配 409
// 权威回滚、超 size 400、单片超限 413、两道关口四道校验（create 白名单/上限、
// complete 魔数终检）、complete 同名自动重命名（与直传 TestUploadConflictRename
// 同断言口径）、dir 子目录落位与穿越拒绝（与直传同口径）、DELETE 后 404、
// 过期清扫（注入时钟）、401。

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/uploadsess"
)

// createUploadSession 发起会话创建，返回会话快照（调用方断言状态码后使用）。
func (e *testEnv) createUploadSessionRaw(t *testing.T, body string) *http.Response {
	t.Helper()
	return e.do(t, http.MethodPost, "/api/v1/uploads", body)
}

func (e *testEnv) createUploadSession(t *testing.T, libraryID, fileName string, size int64) gen.UploadSession {
	return e.createUploadSessionDir(t, libraryID, "", fileName, size)
}

// createUploadSessionDir 带目标子目录创建会话（dir 空 = 库根，与直传 dir 参数同语义）。
func (e *testEnv) createUploadSessionDir(t *testing.T, libraryID, dir, fileName string, size int64) gen.UploadSession {
	t.Helper()
	resp := e.createUploadSessionRaw(t, fmt.Sprintf(`{"libraryId":%q,"fileName":%q,"size":%d,"dir":%q}`, libraryID, fileName, size, dir))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("创建上传会话期望 201，得到 %d", resp.StatusCode)
	}
	var sess gen.UploadSession
	if err := decodeBody(resp, &sess); err != nil {
		t.Fatalf("解析会话响应失败: %v", err)
	}
	return sess
}

// patchUpload 追加分片（octet-stream 原始字节 + query offset）。
func (e *testEnv) patchUpload(t *testing.T, id string, offset int64, body []byte) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodPatch,
		fmt.Sprintf("%s/api/v1/uploads/%s?offset=%d", e.ts.URL, id, offset), bytes.NewReader(body))
	if err != nil {
		t.Fatalf("构造分片请求失败: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+e.token)
	req.Header.Set("Content-Type", "application/octet-stream")
	return e.doRaw(t, req)
}

func (e *testEnv) completeUpload(t *testing.T, id string) *http.Response {
	t.Helper()
	return e.do(t, http.MethodPost, "/api/v1/uploads/"+id+"/complete", "")
}

func (e *testEnv) uploadSessionByID(t *testing.T, id string) (gen.UploadSession, int) {
	t.Helper()
	resp := e.do(t, http.MethodGet, "/api/v1/uploads/"+id, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return gen.UploadSession{}, resp.StatusCode
	}
	var sess gen.UploadSession
	if err := decodeBody(resp, &sess); err != nil {
		t.Fatalf("解析会话响应失败: %v", err)
	}
	return sess, resp.StatusCode
}

// assertNoSessionTemp 断言分片临时目录已无残留文件（complete/清扫后）。
func assertNoSessionTemp(t *testing.T, dataDir string) {
	t.Helper()
	entries, err := os.ReadDir(filepath.Join(dataDir, uploadsess.TempDirName))
	if err != nil {
		t.Fatalf("读分片临时目录失败: %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("分片临时目录应已清空，残留 %d 个文件", len(entries))
	}
}

// TestUploadSessionFullFlow 全链：create → PATCH×3（含跨 offset 续传，中间
// 插一次断点探测）→ complete 201 → 资产可见 + 修订号 bump + 临时目录清空。
func TestUploadSessionFullFlow(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 320, 240)
	// 三片不等长切分（4/中/尾），覆盖"跨 offset 续传"。
	cut1, cut2 := 4, len(jpg)*2/3
	chunks := [][]byte{jpg[:cut1], jpg[cut1:cut2], jpg[cut2:]}

	base := env.revisionNow(t)
	sess := env.createUploadSession(t, env.libID, "resume-flow.jpg", int64(len(jpg)))
	if sess.Offset != 0 || sess.Size != int64(len(jpg)) {
		t.Fatalf("新会话 offset/size = %d/%d, want 0/%d", sess.Offset, sess.Size, len(jpg))
	}

	var committed int64
	for i, chunk := range chunks {
		resp := env.patchUpload(t, sess.Id.String(), committed, chunk)
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("第 %d 片期望 200，得到 %d", i+1, resp.StatusCode)
		}
		var after gen.UploadSession
		if err := decodeBody(resp, &after); err != nil {
			t.Fatalf("解析分片响应失败: %v", err)
		}
		committed += int64(len(chunk))
		if after.Offset != committed {
			t.Fatalf("第 %d 片后 offset = %d, want %d", i+1, after.Offset, committed)
		}
		// 跨 offset 续传：中段先用 GET 探测服务端权威 offset 再续传。
		if i == 0 {
			probe, code := env.uploadSessionByID(t, sess.Id.String())
			if code != http.StatusOK || probe.Offset != committed {
				t.Fatalf("断点探测 offset = %d（code %d）, want %d", probe.Offset, code, committed)
			}
		}
	}

	resp := env.completeUpload(t, sess.Id.String())
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("complete 期望 201，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := decodeBody(resp, &d); err != nil {
		t.Fatalf("解析 complete 响应失败: %v", err)
	}
	if d.RelPath == nil || *d.RelPath != "resume-flow.jpg" {
		t.Errorf("relPath = %v, want resume-flow.jpg", d.RelPath)
	}
	// 入库走既有管线：修订号经 library.changed 订阅 bump（异步，轮询等待）。
	env.waitRevision(t, base+1)
	if _, ok := env.assetIDByName(t, "resume-flow.jpg"); !ok {
		t.Error("complete 后列表未出现新资产")
	}
	// 磁盘文件与源字节一致、临时目录已清空。
	onDisk, err := os.ReadFile(filepath.Join(env.media, "resume-flow.jpg"))
	if err != nil {
		t.Fatalf("读落盘文件失败: %v", err)
	}
	if !bytes.Equal(onDisk, jpg) {
		t.Error("落盘文件与分片拼装结果不一致")
	}
	assertNoSessionTemp(t, env.dataDir)
	// 会话已终结：GET 404。
	if _, code := env.uploadSessionByID(t, sess.Id.String()); code != http.StatusNotFound {
		t.Errorf("complete 后 GET 期望 404，得到 %d", code)
	}
}

// TestUploadSessionOffsetMismatch：offset 不等权威值 → 409 + 权威 UploadSession；
// 客户端按权威 offset 重同步后从断点续传成功。
func TestUploadSessionOffsetMismatch(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	half := len(jpg) / 2
	sess := env.createUploadSession(t, env.libID, "offset-case.jpg", int64(len(jpg)))

	resp := env.patchUpload(t, sess.Id.String(), 0, jpg[:half])
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("首片期望 200，得到 %d", resp.StatusCode)
	}

	// 从旧断点 0 重发（模拟客户端状态陈旧）→ 409 响应体即权威状态。
	resp = env.patchUpload(t, sess.Id.String(), 0, jpg[:half])
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("陈旧 offset 期望 409，得到 %d", resp.StatusCode)
	}
	var auth gen.UploadSession
	if err := decodeBody(resp, &auth); err != nil {
		t.Fatalf("解析 409 权威会话失败: %v", err)
	}
	if auth.Offset != int64(half) {
		t.Errorf("409 权威 offset = %d, want %d", auth.Offset, half)
	}
	// 按权威 offset 重同步后续传剩余半片成功。
	resp = env.patchUpload(t, sess.Id.String(), auth.Offset, jpg[half:])
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("重同步续传期望 200，得到 %d", resp.StatusCode)
	}
}

// TestUploadSessionOverflow：累计超出会话声明 size → 400。
func TestUploadSessionOverflow(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	// 声明 size 比实际字节少 1：最后一片触发累计超限。
	sess := env.createUploadSession(t, env.libID, "overflow.jpg", int64(len(jpg)-1))
	resp := env.patchUpload(t, sess.Id.String(), 0, jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("累计超 size 期望 400，得到 %d", resp.StatusCode)
	}
	// 会话保留（回滚到 0，可按正确文件重传）。
	snap, code := env.uploadSessionByID(t, sess.Id.String())
	if code != http.StatusOK || snap.Offset != 0 {
		t.Errorf("超限后会话应保留且 offset=0，得到 code=%d offset=%d", code, snap.Offset)
	}
}

// TestUploadSessionChunkTooLarge：单片超过 32MB 上限 → 413（协议选型写进
// openapi 描述；这里是包级常量 uploadsess.MaxChunkBytes 的端到端锁定）。
func TestUploadSessionChunkTooLarge(t *testing.T) {
	env := newTestEnv(t)
	total := uploadsess.MaxChunkBytes + 1
	sess := env.createUploadSession(t, env.libID, "huge-chunk.jpg", total)
	// 单片 32MB+1（全 0 字节即可：413 判定发生在魔数终检之前）。
	resp := env.patchUpload(t, sess.Id.String(), 0, make([]byte, total))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("单片超限期望 413，得到 %d", resp.StatusCode)
	}
	// 会话保留且 offset 回滚到 0，可换小分片重试。
	snap, code := env.uploadSessionByID(t, sess.Id.String())
	if code != http.StatusOK || snap.Offset != 0 {
		t.Errorf("超限后会话应保留且 offset=0，得到 code=%d offset=%d", code, snap.Offset)
	}
}

// TestUploadSessionCreateRejects：create 关口的可前置校验——白名单外扩展名
// 400、size 超上限 413、库不存在 400、size<1 400。
func TestUploadSessionCreateRejects(t *testing.T) {
	env := newTestEnv(t)

	resp := env.createUploadSessionRaw(t, fmt.Sprintf(`{"libraryId":%q,"fileName":"virus.exe","size":10}`, env.libID))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("白名单外扩展名期望 400，得到 %d", resp.StatusCode)
	}

	env.cfg.Upload.MaxBytes = 1024 // 本用例限 1KB（Server 与 Manager 共用同一 cfg 指针）
	resp = env.createUploadSessionRaw(t, fmt.Sprintf(`{"libraryId":%q,"fileName":"big.jpg","size":2048}`, env.libID))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Errorf("声明 size 超上限期望 413，得到 %d", resp.StatusCode)
	}
	env.cfg.Upload.MaxBytes = 0 // 恢复默认

	resp = env.createUploadSessionRaw(t, `{"libraryId":"no-such-lib","fileName":"x.jpg","size":10}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("库不存在期望 400，得到 %d", resp.StatusCode)
	}

	resp = env.createUploadSessionRaw(t, fmt.Sprintf(`{"libraryId":%q,"fileName":"x.jpg","size":0}`, env.libID))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("size<1 期望 400，得到 %d", resp.StatusCode)
	}
}

// TestUploadSessionCompleteValidation：complete 关口的魔数终检——.jpg 装
// 文本 400（与直传 TestUploadMimeMismatch 同断言口径），且会话与临时文件
// 保留（客户端可修正重试）；未传完 complete 400。
func TestUploadSessionCompleteValidation(t *testing.T) {
	env := newTestEnv(t)
	payload := []byte("this is not a jpeg at all........")

	// 未传完：offset(0) != size → 400。
	incomplete := env.createUploadSession(t, env.libID, "partial.jpg", int64(len(payload)))
	resp := env.completeUpload(t, incomplete.Id.String())
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("未传完 complete 期望 400，得到 %d", resp.StatusCode)
	}

	// 传完了但魔数不符 → 400；会话保留可重试。
	sess := env.createUploadSession(t, env.libID, "evil.jpg", int64(len(payload)))
	resp = env.patchUpload(t, sess.Id.String(), 0, payload)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("分片期望 200，得到 %d", resp.StatusCode)
	}
	resp = env.completeUpload(t, sess.Id.String())
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("魔数不符 complete 期望 400，得到 %d", resp.StatusCode)
	}
	if _, code := env.uploadSessionByID(t, sess.Id.String()); code != http.StatusOK {
		t.Errorf("终检失败后会话应保留，GET 得到 %d", code)
	}
	if _, err := os.Stat(filepath.Join(env.media, "evil.jpg")); !os.IsNotExist(err) {
		t.Error("终检失败不应有文件落进库")
	}
}

// TestUploadSessionConflictRename：complete 同名冲突自动重命名 "基名 (2).ext"
// （与直传 TestUploadConflictRename 逐字同断言；DOMAIN_RULES §9 永不 409）。
func TestUploadSessionConflictRename(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	// 直传先占 same.jpg。
	first := env.uploadBytes(t, env.libID, "", "same.jpg", jpg)
	_ = first.Body.Close()
	if first.StatusCode != http.StatusCreated {
		t.Fatalf("前置直传期望 201，得到 %d", first.StatusCode)
	}

	sess := env.createUploadSession(t, env.libID, "same.jpg", int64(len(jpg)))
	resp := env.patchUpload(t, sess.Id.String(), 0, jpg)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("分片期望 200，得到 %d", resp.StatusCode)
	}
	resp = env.completeUpload(t, sess.Id.String())
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("冲突 complete 期望 201（自动重命名），得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := decodeBody(resp, &d); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if d.RelPath == nil || *d.RelPath != "same (2).jpg" {
		t.Errorf("冲突重命名 relPath = %v, 期望 same (2).jpg", d.RelPath)
	}
}

// TestUploadSessionDeleteAndSweep：DELETE 放弃（204 → GET 404 → 临时目录
// 清空）；过期清扫按注入时钟回收（活动后拨 24h+，清扫 → GET 404）。
func TestUploadSessionDeleteAndSweep(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	sess := env.createUploadSession(t, env.libID, "dropped.jpg", int64(len(jpg)))
	resp := env.patchUpload(t, sess.Id.String(), 0, jpg[:10])
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("分片期望 200，得到 %d", resp.StatusCode)
	}
	resp = env.do(t, http.MethodDelete, "/api/v1/uploads/"+sess.Id.String(), "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("DELETE 期望 204，得到 %d", resp.StatusCode)
	}
	if _, code := env.uploadSessionByID(t, sess.Id.String()); code != http.StatusNotFound {
		t.Errorf("DELETE 后 GET 期望 404，得到 %d", code)
	}
	assertNoSessionTemp(t, env.dataDir)

	// 未知会话 DELETE 同样 404。
	resp = env.do(t, http.MethodDelete, "/api/v1/uploads/00000000-0000-0000-0000-000000000001", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("未知会话 DELETE 期望 404，得到 %d", resp.StatusCode)
	}

	// 过期清扫（注入时钟）：创建后不活动，拨过 SessionTTL 清扫即回收。
	stale := env.createUploadSession(t, env.libID, "stale.jpg", int64(len(jpg)))
	entries, rerr := os.ReadDir(filepath.Join(env.dataDir, uploadsess.TempDirName))
	if rerr != nil || len(entries) == 0 {
		t.Fatalf("前置失败：分片临时目录应存在且非空（err=%v）", rerr)
	}
	env.clock.advance(uploadsess.SessionTTL + time.Minute)
	env.s.sweepUploadSessionsOnce()
	if _, code := env.uploadSessionByID(t, stale.Id.String()); code != http.StatusNotFound {
		t.Errorf("过期清扫后 GET 期望 404，得到 %d", code)
	}
	assertNoSessionTemp(t, env.dataDir)
}

// TestUploadSessionDirSubdir：create 带 dir → complete 落位库内子目录
//（与直传 TestUploadJPEGToSubdir 同断言口径；dir 在 create 时校验规范化并存入
// 会话，complete 只认会话内持有的值落位）。
func TestUploadSessionDirSubdir(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	sess := env.createUploadSessionDir(t, env.libID, "画作/2024", "sub.jpg", int64(len(jpg)))
	resp := env.patchUpload(t, sess.Id.String(), 0, jpg)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("分片期望 200，得到 %d", resp.StatusCode)
	}
	resp = env.completeUpload(t, sess.Id.String())
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("complete 期望 201，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := decodeBody(resp, &d); err != nil {
		t.Fatalf("解析 complete 响应失败: %v", err)
	}
	if d.RelPath == nil || *d.RelPath != "画作/2024/sub.jpg" {
		t.Errorf("relPath = %v, 期望 画作/2024/sub.jpg", d.RelPath)
	}
	// 子目录真实落盘 + 临时目录已清空（dir 存入会话而非每片重报的旁证）。
	if _, err := os.Stat(filepath.Join(env.media, "画作", "2024", "sub.jpg")); err != nil {
		t.Errorf("文件未落位子目录: %v", err)
	}
	assertNoSessionTemp(t, env.dataDir)
}

// TestUploadSessionDirRejects：dir 穿越形态 create 即拒绝 400（与直传同一
// resolveUploadDir 入口：../ 逃逸/绝对路径/a.. 收敛/盘符），且不产生会话。
func TestUploadSessionDirRejects(t *testing.T) {
	env := newTestEnv(t)
	for _, dir := range []string{"../escape.jpg", "/abs", "sub/..", `C:\evil`, "画作/../../out"} {
		resp := env.createUploadSessionRaw(t,
			fmt.Sprintf(`{"libraryId":%q,"fileName":"x.jpg","size":10,"dir":%q}`, env.libID, dir))
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("dir=%q 期望 400，得到 %d（body=%s）", dir, resp.StatusCode, body)
		}
		if !strings.Contains(string(body), codeInvalidParam) {
			t.Errorf("dir=%q 错误码应为 %s，body=%s", dir, codeInvalidParam, body)
		}
	}
	// 全部 create 被拒 = 无会话产生。临时目录由首次 Create 惰性创建，这里
	// 容忍目录不存在，只断言"存在即无残留"。
	if entries, err := os.ReadDir(filepath.Join(env.dataDir, uploadsess.TempDirName)); err == nil && len(entries) != 0 {
		t.Errorf("dir 被拒后不应有分片临时文件，残留 %d 个", len(entries))
	}
}

// TestUploadSessionTamperedAfterSeal：Seal 之后、落位之前分片成品被塞入
// 1 字节（违规客户端并发 PATCH 在 rename 间隙写入的确定性等价），落位必须
// 拒绝（size 复核 → errSealedSizeMismatch → complete 400）且库内无坏文件、
// 损坏成品随失败路径清理（第四百一十九笔 P2-4 收口）。
//
// 走 placeSealedInLibrary 而非 HTTP complete 的原因：complete 入口的 Seal
// 会先把文件兜底截断回权威 size——complete 之前的篡改必被治愈，持续存活的
// 损坏只存在于 Seal 与 rename 之间的间隙（该窗口两条系统调用，HTTP 层无法
// 确定性插入），故按该间隙的成品形态直接构造输入，锁定复核拒绝行为。
func TestUploadSessionTamperedAfterSeal(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	sess := env.createUploadSession(t, env.libID, "tampered.jpg", int64(len(jpg)))
	resp := env.patchUpload(t, sess.Id.String(), 0, jpg)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("分片期望 200，得到 %d", resp.StatusCode)
	}
	// 此刻会话已具 Seal 语义（offset==size，文件截断到权威字节数）。向成品
	// 尾部追加 1 字节模拟 rename 间隙的并发写入。临时路径经目录枚举获得
	//（uploadsess 不外泄 tempPath；目录内只有本会话一个 .part 文件）。
	tmpDir := filepath.Join(env.dataDir, uploadsess.TempDirName)
	entries, err := os.ReadDir(tmpDir)
	if err != nil || len(entries) != 1 {
		t.Fatalf("前置失败：分片临时目录应有 1 个文件（err=%v, n=%d）", err, len(entries))
	}
	partPath := filepath.Join(tmpDir, entries[0].Name())
	f, err := os.OpenFile(partPath, os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		t.Fatalf("打开分片临时文件失败: %v", err)
	}
	if _, err := f.Write([]byte{0xFF}); err != nil {
		_ = f.Close()
		t.Fatalf("写入篡改字节失败: %v", err)
	}
	if err := f.Close(); err != nil {
		t.Fatalf("关闭分片临时文件失败: %v", err)
	}

	lib, err := env.q.GetLibrary(context.Background(), env.libID)
	if err != nil {
		t.Fatalf("查询库失败: %v", err)
	}
	seal := uploadsess.SealedSession{
		ID: sess.Id.String(), LibraryID: env.libID,
		FileName: "tampered.jpg", Dir: "",
		Size: int64(len(jpg)), Path: partPath,
	}
	_, _, _, perr := env.s.placeSealedInLibrary(lib, seal)
	if !errors.Is(perr, errSealedSizeMismatch) {
		t.Fatalf("篡改成品的落位期望 errSealedSizeMismatch，得到 %v", perr)
	}
	// 库内无坏文件：落位从未发生。
	if _, err := os.Stat(filepath.Join(env.media, "tampered.jpg")); !os.IsNotExist(err) {
		t.Error("被篡改的分片不得落进库")
	}
	// 损坏成品（已被预置为 staging）已随失败路径清理，临时目录无残留。
	assertNoSessionTemp(t, env.dataDir)
}

// TestUploadSessionUnauthorized：无 token 401（新端点继承全局 Bearer）。
func TestUploadSessionUnauthorized(t *testing.T) {
	env := newTestEnv(t)
	resp, err := http.Get(env.ts.URL + "/api/v1/uploads/00000000-0000-0000-0000-000000000002")
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token 期望 401，得到 %d", resp.StatusCode)
	}
}
