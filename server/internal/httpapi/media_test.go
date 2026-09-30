// media_test.go：countingResponseWriter 的 ReadFrom 委托与字节计数锁定。
//
// http.ServeContent 内部用 io.CopyN 输出 body——wrapper 实现 io.ReaderFrom
// 时底层连接的 sendfile 零拷贝才可用；本测试锁定两条路径（底层支持/
// 不支持 ReaderFrom）下 media_bytes_total 的计数口径都精确不丢。
package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestCountingResponseWriter_readFromDelegated：底层 writer 实现 io.ReaderFrom
// 时（生产环境即 net/http 的 response，sendfile 路径），countingResponseWriter
// 必须委托其 ReadFrom，且累计字节数与实发字节精确相等。
func TestCountingResponseWriter_readFromDelegated(t *testing.T) {
	bottom := &rfRecorder{ResponseWriter: httptest.NewRecorder()}
	cw := &countingResponseWriter{ResponseWriter: bottom}
	payload := "media-bytes-through-readfrom"
	n, err := cw.ReadFrom(strings.NewReader(payload))
	if err != nil {
		t.Fatalf("ReadFrom 失败: %v", err)
	}
	if !bottom.readFromCalled {
		t.Error("未委托底层 writer 的 ReadFrom（sendfile 零拷贝被断路）")
	}
	if n != int64(len(payload)) {
		t.Errorf("ReadFrom 返回字节数不符: got %d want %d", n, int64(len(payload)))
	}
	if cw.n != n {
		t.Errorf("media 字节计数与实发不符: 计数 %d 实发 %d（计数丢失会导致 media_bytes_total 偏小）", cw.n, n)
	}
}

// TestCountingResponseWriter_readFromFallback：底层不支持 io.ReaderFrom 时
// （如测试环境的 ResponseRecorder），走 io.Copy 兜底且计数口径不变。
func TestCountingResponseWriter_readFromFallback(t *testing.T) {
	bottom := httptest.NewRecorder()
	cw := &countingResponseWriter{ResponseWriter: bottom}
	payload := "fallback-copy-path"
	n, err := cw.ReadFrom(strings.NewReader(payload))
	if err != nil {
		t.Fatalf("ReadFrom 失败: %v", err)
	}
	if n != int64(len(payload)) || cw.n != n {
		t.Errorf("兜底路径计数不符: 返回 %d 计数 %d want %d", n, cw.n, int64(len(payload)))
	}
	if bottom.Body.String() != payload {
		t.Errorf("兜底路径内容未写出: got %q", bottom.Body.String())
	}
}

// TestMediaOrigCacheControl（ADR-0027 审查遗留 P2 清偿）：orig 直链 200 成功
// 响应必须带 `Cache-Control: private, max-age=<窗口秒数>`——exp 窗口对齐后
// 同窗 URL 逐字节恒定，显式 max-age 才有意义（private：授权语义绑在 URL
// 签名上，禁止共享缓存代发）。Range/签名语义由 browse_test 锁定，本用例
// 只锁响应头。
func TestMediaOrigCacheControl(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	detail := env.detail(t, a.id)
	resp, err := http.Get(env.ts.URL + *detail.OrigUrl)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("orig 期望 200，得到 %d", resp.StatusCode)
	}
	want := "private, max-age=" + strconv.Itoa(int(DefaultTokenTTL/time.Second))
	if got := resp.Header.Get("Cache-Control"); got != want {
		t.Errorf("Cache-Control = %q, want %q", got, want)
	}
}
