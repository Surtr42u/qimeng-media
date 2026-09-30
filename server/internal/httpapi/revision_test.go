package httpapi

// 库内容修订号端点与 bump 链端到端测试：复用 testEnv（真实 SQLite + 真实
// 文件 + Bearer 鉴权链）。锁定：鉴权（401）、基线响应、事件驱动 bump
// （上传 / 扫描完成经 library.changed 订阅，异步送达 → 轮询断言）与显式
// bump（回收站物理删除/清空，同步落库 → 精确值断言）。
//
// 计数口径：本测试环境每次动作恰好发布/触发一次 bump（fakeScanner 不发
// 事件、测试内无并发写者），故全部断言用精确值；事件订阅是异步投递，
// 涉及事件路径的断言统一走 waitRevision 轮询到期望值。

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
)

// revisionNow 经端点读当前修订号（走真实 API，同时锁定响应形状）。
func (e *testEnv) revisionNow(t *testing.T) int64 {
	t.Helper()
	resp := e.do(t, http.MethodGet, "/api/v1/library/revision", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("修订号端点期望 200，得到 %d", resp.StatusCode)
	}
	var body gen.LibraryRevision
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("解析修订号响应失败: %v", err)
	}
	return body.Revision
}

// waitRevision 轮询直到端点修订号达到 want（事件订阅异步投递的确定性
// 等待）；超时以最后一次读值报错。
func (e *testEnv) waitRevision(t *testing.T, want int64) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		got := e.revisionNow(t)
		if got == want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("修订号未在时限内到达 %d（最后读值 %d）", want, got)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// TestLibraryRevisionBaselineAndAuth 基线与鉴权：无 token 401（新端点与
// /api/v1/assets 同级 Bearer 保护，未进免鉴权清单）；基线 = 服务启动时
// COUNT(assets)+1（本环境启动时 0 资产 → 1；假扫描直写入库不发布
// library.changed，故基线后仍为 1）。
func TestLibraryRevisionBaselineAndAuth(t *testing.T) {
	env := newTestEnv(t)
	resp, err := http.Get(env.ts.URL + "/api/v1/library/revision")
	if err != nil {
		t.Fatalf("无 token 请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("无 token 期望 401，得到 %d", resp.StatusCode)
	}
	if got := env.revisionNow(t); got != 1 {
		t.Errorf("基线期望 1（COUNT(0)+1），得到 %d", got)
	}
}

// TestLibraryRevisionBumpOnScanFinish 事件订阅 bump：FinishScan 发布
// library.changed → 订阅协程自增（server.go New 的一处订阅覆盖扫描链）。
func TestLibraryRevisionBumpOnScanFinish(t *testing.T) {
	env := newTestEnv(t)
	base := env.revisionNow(t)
	env.s.FinishScan(env.libID, false)
	env.waitRevision(t, base+1)
}

// TestLibraryRevisionBumpOnUploadAndTrash 全链路：上传（事件 bump）→
// 移入回收站（事件 bump）→ 物理删除单条（显式同步 bump）→ 再造一条后
// 清空回收站（显式同步 bump）。精确值断言。
func TestLibraryRevisionBumpOnUploadAndTrash(t *testing.T) {
	env := newTestEnv(t)
	base := env.revisionNow(t)

	// 上传 → 事件驱动 bump（异步）。
	resp := env.uploadBytes(t, env.libID, "", "rev-test.jpg", makeJPG(t, "", 64, 48))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("上传期望 201，得到 %d", resp.StatusCode)
	}
	env.waitRevision(t, base+1)

	// 移入回收站 → 事件驱动 bump（异步）。
	id, ok := env.assetIDByName(t, "rev-test.jpg")
	if !ok {
		t.Fatal("测试前置失败：上传文件不在列表")
	}
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("删除期望 200，得到 %d", resp.StatusCode)
	}
	env.waitRevision(t, base+2)

	// 物理删除回收站单条 → 显式同步 bump：请求返回即已落库，精确 +1。
	items := env.trashList(t)
	if len(items) != 1 || items[0].Id == nil {
		t.Fatalf("回收站期望 1 条，得到 %d", len(items))
	}
	resp = env.do(t, http.MethodDelete, "/api/v1/trash/"+*items[0].Id, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("物理删除期望 204，得到 %d", resp.StatusCode)
	}
	if got := env.revisionNow(t); got != base+3 {
		t.Errorf("物删后修订号期望 %d，得到 %d", base+3, got)
	}

	// 清空回收站（再造一条：先上传+移入，等事件 bump 落定）→ 显式同步 bump。
	resp = env.uploadBytes(t, env.libID, "", "rev-test2.jpg", makeJPG(t, "", 64, 48))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("二次上传期望 201，得到 %d", resp.StatusCode)
	}
	env.waitRevision(t, base+4)
	id2, ok := env.assetIDByName(t, "rev-test2.jpg")
	if !ok {
		t.Fatal("测试前置失败：二次上传文件不在列表")
	}
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+id2, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("二次删除期望 200，得到 %d", resp.StatusCode)
	}
	env.waitRevision(t, base+5)
	resp = env.do(t, http.MethodDelete, "/api/v1/trash", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("清空期望 204，得到 %d", resp.StatusCode)
	}
	if got := env.revisionNow(t); got != base+6 {
		t.Errorf("清空后修订号期望 %d，得到 %d", base+6, got)
	}
}
