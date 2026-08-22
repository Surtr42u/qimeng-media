package auth

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// newAuthHarness 搭一个最小可复用的中间件测试架：
// 单 token 库（map 模拟 store）+ 记录 ctx 中 Principal 的后端 handler。
func newAuthHarness(t *testing.T) (handler http.Handler, validToken string, gotPrincipal **Principal) {
	t.Helper()
	validToken = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
	stored := TokenHash(validToken)

	var captured *Principal
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p, ok := PrincipalFromContext(r.Context())
		if !ok {
			t.Error("通过鉴权后 ctx 中应有 Principal")
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		captured = p
		w.WriteHeader(http.StatusOK)
	})
	mw := RequireToken(func(_ context.Context, tokenHash string) bool {
		// 模拟接入层：查库 + 恒时比较（与真实 store 的契约一致）
		return VerifyTokenHash(tokenHash, stored)
	})
	return mw(next), validToken, &captured
}

// TestRequireTokenRejects 表驱动锁定 401 拒绝面：无头、非 Bearer、空 token、伪造 token。
func TestRequireTokenRejects(t *testing.T) {
	handler, validToken, _ := newAuthHarness(t)
	tests := []struct {
		name   string
		header string // 空 = 不带 Authorization
	}{
		{"无 Authorization 头", ""},
		{"非 Bearer scheme（Basic）", "Basic dXNlcjpwYXNz"},
		{"非 Bearer scheme（Token）", "Token " + validToken},
		{"只有 scheme 无 token", "Bearer "},
		{"只有 Bearer 单词", "Bearer"},
		{"伪造 token", "Bearer f" + validToken[1:]},
		{"token 多了空格（取第二段会带上空格→哈希不匹配）", "Bearer " + validToken + " extra"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/api/v1/libraries", nil)
			if tt.header != "" {
				req.Header.Set("Authorization", tt.header)
			}
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)

			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("期望 401，实际 %d", rec.Code)
			}
			var body struct {
				Code    string `json:"code"`
				Message string `json:"message"`
			}
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatalf("401 响应不是合法 JSON: %v（body=%q）", err, rec.Body.String())
			}
			if body.Code != "UNAUTHORIZED" {
				t.Fatalf("code 应为 UNAUTHORIZED，实际 %q", body.Code)
			}
			if body.Message == "" {
				t.Fatal("message 不应为空")
			}
		})
	}
}

// TestRequireTokenAccepts 合法 token 通过且 ctx 注入 Principal：
// scheme 大小写容错（RFC 7235），注入的是哈希而非明文。
func TestRequireTokenAccepts(t *testing.T) {
	handler, validToken, got := newAuthHarness(t)
	tests := []struct {
		name   string
		scheme string
	}{
		{"标准 Bearer", "Bearer"},
		{"小写 bearer", "bearer"},
		{"大写 BEARER", "BEARER"},
		{"混合大小写 BeArEr", "BeArEr"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/api/v1/libraries", nil)
			req.Header.Set("Authorization", tt.scheme+" "+validToken)
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)

			if rec.Code != http.StatusOK {
				t.Fatalf("合法 token 应 200，实际 %d（body=%q）", rec.Code, rec.Body.String())
			}
			if (*got) == nil {
				t.Fatal("handler 已 200 但未捕获到 Principal")
			}
			if (*got).TokenHash != TokenHash(validToken) {
				t.Fatalf("ctx 中应是 token 哈希，实际 %q", (*got).TokenHash)
			}
			if (*got).TokenHash == validToken {
				t.Fatal("ctx 中不得出现明文 token")
			}
		})
	}
}

// TestPrincipalFromContextEmpty 未挂中间件的请求（或空 ctx）读取 Principal
// 应得到 ok=false，供 handler 判"路由漏挂鉴权"。
func TestPrincipalFromContextEmpty(t *testing.T) {
	if _, ok := PrincipalFromContext(context.Background()); ok {
		t.Fatal("空 ctx 不应取到 Principal")
	}
}
