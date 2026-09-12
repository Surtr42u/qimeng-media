package httpapi

// #9 台账清欠的并发回归测试（上传/移动 TOCTOU：两段式"探测冲突名→
// rename"曾无互斥，并发同名请求会解析出同一冲突名、第二次 rename 静默
// 覆盖第一次；清欠后关键段持 filing.WithLibraryGate 库锁）。
//
// 运行要求：必须带 -race（CI server job 的 go test 即 -race）：
//
//	go test -race -run 'TestUploadConcurrentSameName|TestMoveUploadNoOverwrite' ./internal/httpapi/ -count=1 -v
//
// 说明：worker goroutine 内不做任何 t.Fatal/t.Errorf（testing 的 Fatal
// 只允许在测试主 goroutine 调用）——结果经 channel 交回主 goroutine 统一
// 断言，失败信息随结果携带。

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// uploadWorkerResult 单路并发上传的结果（在 worker goroutine 收集，
// 主 goroutine 断言）。
type uploadWorkerResult struct {
	index   int
	status  int
	rawBody []byte
	err     error
}

// postUpload 发一路上传请求（worker 专用：不经 testEnv.uploadBytes，
// 那条路径在失败时会 t.Fatalf，不允许在非测试 goroutine 调用）。
func postUpload(env *testEnv, libraryID, dir, filename string, body []byte, index int, ch chan<- uploadWorkerResult) {
	url := env.ts.URL + "/api/v1/assets/upload?libraryId=" + libraryID +
		"&dir=" + dir + "&filename=" + filename
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		ch <- uploadWorkerResult{index: index, err: err}
		return
	}
	req.Header.Set("Authorization", "Bearer "+env.token)
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		ch <- uploadWorkerResult{index: index, err: err}
		return
	}
	defer func() { _ = resp.Body.Close() }()
	raw, err := io.ReadAll(resp.Body)
	ch <- uploadWorkerResult{index: index, status: resp.StatusCode, rawBody: raw, err: err}
}

// TestUploadConcurrentSameName：N=8 路并发同名上传（#9 回归）。断言：
// 全部 201；N 个响应 relPath 两两不同；每个最终文件存在且字节数与各自
// 请求体一致（无覆盖）。请求体各不相同（同一合法 JPEG 头 + 路号后缀）——
// 若 bodies 全同，即使发生覆盖字节数断言也发现不了。
func TestUploadConcurrentSameName(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	const n = 8
	bodies := make([][]byte, n)
	for i := range bodies {
		bodies[i] = append(append([]byte{}, jpg...), []byte(fmt.Sprintf("|payload-%02d", i))...)
	}

	ch := make(chan uploadWorkerResult, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			postUpload(env, env.libID, "", "race.jpg", bodies[i], i, ch)
		}(i)
	}
	wg.Wait()
	close(ch)

	relPaths := make(map[string]int, n)
	byIndex := make([]uploadWorkerResult, n)
	for r := range ch {
		if r.err != nil {
			t.Fatalf("并发上传请求 %d 失败: %v", r.index, r.err)
		}
		if r.status != http.StatusCreated {
			t.Fatalf("并发上传 %d 期望 201（自动重命名永不 409），得到 %d: %s",
				r.index, r.status, r.rawBody)
		}
		byIndex[r.index] = r
	}
	for i := 0; i < n; i++ {
		var d gen.AssetDetail
		if err := json.Unmarshal(byIndex[i].rawBody, &d); err != nil {
			t.Fatalf("解析上传 %d 响应失败: %v", i, err)
		}
		if d.RelPath == nil || *d.RelPath == "" {
			t.Fatalf("上传 %d 响应缺 relPath", i)
		}
		relPaths[*d.RelPath]++
		// 最终文件存在且字节数与各自请求体一致：发生覆盖时败者的字节数
		// 断言（或两两不同断言）必然失败。
		p := filepath.Join(env.media, filepath.FromSlash(*d.RelPath))
		fi, err := os.Stat(p)
		if err != nil {
			t.Fatalf("上传 %d 的最终文件未落盘 %s: %v", i, p, err)
		}
		if fi.Size() != int64(len(bodies[i])) {
			t.Errorf("上传 %d 文件 %s 字节数 = %d, 期望 %d（疑似被并发同名覆盖）",
				i, *d.RelPath, fi.Size(), len(bodies[i]))
		}
	}
	for rel, cnt := range relPaths {
		if cnt > 1 {
			t.Errorf("relPath %s 被 %d 路上传同时解析（TOCTOU：冲突解析未串行化）", rel, cnt)
		}
	}
	if len(relPaths) != n {
		t.Errorf("去重后 relPath 数 = %d, 期望 %d", len(relPaths), n)
	}
}

// TestMoveUploadNoOverwrite：并发 1 路 move（a.jpg 改名 race.jpg）+ 1 路
// 同名目标上传 race.jpg（#9 回归）。锁串行化后结局只有两种且都无字节
// 丢失——move 先抢到锁：move 200、上传自动重命名 race (2).jpg；上传先
// 抢到锁：上传占住 race.jpg、move 409、a.jpg 原地不动。两种结局下
// race.jpg 与另一份文件都必须字节完整。
func TestMoveUploadNoOverwrite(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	uploadBody := append(append([]byte{}, jpg...), []byte("|upload-side")...)

	origA, err := os.ReadFile(filepath.Join(env.media, "a.jpg"))
	if err != nil {
		t.Fatalf("读取种子文件 a.jpg 失败: %v", err)
	}
	id, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("测试前置失败：a.jpg 不在列表")
	}

	// move 与 upload 同时发起（结果经 channel 交回，worker 内不碰 testing）。
	type moveResult struct {
		status  int
		rawBody []byte
		err     error
	}
	moveCh := make(chan moveResult, 1)
	uploadCh := make(chan uploadWorkerResult, 1)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		req, err := http.NewRequest(http.MethodPost, env.ts.URL+"/api/v1/assets/"+id+"/move",
			bytes.NewReader([]byte(`{"targetDir":"","newName":"race.jpg"}`)))
		if err != nil {
			moveCh <- moveResult{err: err}
			return
		}
		req.Header.Set("Authorization", "Bearer "+env.token)
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			moveCh <- moveResult{err: err}
			return
		}
		defer func() { _ = resp.Body.Close() }()
		raw, err := io.ReadAll(resp.Body)
		moveCh <- moveResult{status: resp.StatusCode, rawBody: raw, err: err}
	}()
	go func() {
		defer wg.Done()
		postUpload(env, env.libID, "", "race.jpg", uploadBody, 0, uploadCh)
	}()
	wg.Wait()
	close(moveCh)
	close(uploadCh)

	mv := <-moveCh
	if mv.err != nil {
		t.Fatalf("move 请求失败: %v", mv.err)
	}
	up := <-uploadCh
	if up.err != nil {
		t.Fatalf("上传请求失败: %v", up.err)
	}
	if up.status != http.StatusCreated {
		t.Fatalf("上传期望 201，得到 %d: %s", up.status, up.rawBody)
	}
	if mv.status != http.StatusOK && mv.status != http.StatusConflict {
		t.Fatalf("move 期望 200（先抢到锁）或 409（目标已被上传占住），得到 %d: %s",
			mv.status, mv.rawBody)
	}
	var d gen.AssetDetail
	if err := json.Unmarshal(up.rawBody, &d); err != nil {
		t.Fatalf("解析上传响应失败: %v", err)
	}
	if d.RelPath == nil {
		t.Fatal("上传响应缺 relPath")
	}
	rel := *d.RelPath

	// 上传侧结果文件字节完整（无论谁先抢到锁）。
	upBytes, err := os.ReadFile(filepath.Join(env.media, filepath.FromSlash(rel)))
	if err != nil {
		t.Fatalf("上传结果文件 %s 未落盘: %v", rel, err)
	}
	if !bytes.Equal(upBytes, uploadBody) {
		t.Errorf("上传结果文件 %s 字节不一致（疑似被覆盖损坏）", rel)
	}
	// race.jpg 必然存在且与两路操作之一完全一致（无字节丢失）。
	raceBytes, err := os.ReadFile(filepath.Join(env.media, "race.jpg"))
	if err != nil {
		t.Fatalf("race.jpg 未落盘: %v", err)
	}
	aBytes, aErr := os.ReadFile(filepath.Join(env.media, "a.jpg"))
	switch mv.status {
	case http.StatusOK:
		// move 赢得锁：race.jpg = 原 a.jpg；上传被挤到 race (2).jpg；旧位无残留。
		if !bytes.Equal(raceBytes, origA) {
			t.Errorf("move 200 但 race.jpg 字节既非原 a.jpg 也异常（疑似覆盖事故）")
		}
		if rel != "race (2).jpg" {
			t.Errorf("move 200 时上传应被自动重命名，relPath = %s, 期望 race (2).jpg", rel)
		}
		if !os.IsNotExist(aErr) {
			t.Errorf("move 200 后旧位置 a.jpg 应不存在（err=%v）", aErr)
		}
	case http.StatusConflict:
		// 上传赢得锁：race.jpg = 上传体；move 409；a.jpg 原地完好。
		if !bytes.Equal(raceBytes, uploadBody) {
			t.Errorf("move 409（上传先占住目标）但 race.jpg 字节与上传体不一致")
		}
		if rel != "race.jpg" {
			t.Errorf("move 409 时上传应落在目标名，relPath = %s, 期望 race.jpg", rel)
		}
		if aErr != nil {
			t.Errorf("move 409 后 a.jpg 应原地不动: %v", aErr)
		} else if !bytes.Equal(aBytes, origA) {
			t.Errorf("move 409 后 a.jpg 字节被改动")
		}
	}
}
