package auth

import (
	"regexp"
	"testing"
)

// TestGenerateTokenUnique 唯一性：连续生成 100 个互不相同。
// token 即会话凭证，碰撞等于接管对方会话。
func TestGenerateTokenUnique(t *testing.T) {
	const n = 100
	seen := make(map[string]struct{}, n)
	for i := 0; i < n; i++ {
		token, err := GenerateToken()
		if err != nil {
			t.Fatalf("第 %d 次生成报错: %v", i, err)
		}
		if _, dup := seen[token]; dup {
			t.Fatalf("第 %d 次生成的 token 重复: %s", i, token)
		}
		seen[token] = struct{}{}
	}
}

// TestGenerateTokenFormat 32 字节熵 → hex 小写 64 字符。
func TestGenerateTokenFormat(t *testing.T) {
	token, err := GenerateToken()
	if err != nil {
		t.Fatalf("生成报错: %v", err)
	}
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(token) {
		t.Fatalf("token 应为 64 位 hex 小写，实际: %s", token)
	}
}

// TestTokenHashDeterministic 确定性：同 token 哈希恒定（库存比对的前提），
// 且哈希与明文、不同 token 的哈希互不相同。
func TestTokenHashDeterministic(t *testing.T) {
	token := "abc123-测试-token"
	h1, h2 := TokenHash(token), TokenHash(token)
	if h1 != h2 {
		t.Fatalf("同 token 哈希应确定: %s != %s", h1, h2)
	}
	if h1 == token {
		t.Fatal("哈希不得等于明文")
	}
	if TokenHash(token+"x") == h1 {
		t.Fatal("不同 token 哈希碰撞")
	}
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(h1) {
		t.Fatalf("哈希应为 64 位 hex: %s", h1)
	}
}

// TestVerifyTokenHash 恒时比较契约：匹配 true，不匹配/长度不同 false。
func TestVerifyTokenHash(t *testing.T) {
	stored := TokenHash("the-real-token")
	if !VerifyTokenHash(stored, TokenHash("the-real-token")) {
		t.Fatal("相同哈希应匹配")
	}
	if VerifyTokenHash(TokenHash("forged-token"), stored) {
		t.Fatal("不同哈希不应匹配")
	}
	if VerifyTokenHash("", stored) {
		t.Fatal("空串不应匹配")
	}
}
