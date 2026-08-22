package auth

import (
	"errors"
	"regexp"
	"testing"
)

// TestHashPasswordRoundTrip 锁定核心契约：正确密码验证通过，
// 错误密码返回 (false, nil)——err 只用于"库中哈希坏了"，不用于密码错误。
func TestHashPasswordRoundTrip(t *testing.T) {
	tests := []struct {
		name     string
		password string
	}{
		{"常见密码", "hunter2"},
		{"含中文与空格", "绮梦 影库 2026!"},
		{"空密码也要能存取", ""},
		{"长密码", string(make([]byte, 256))},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			phc, err := HashPassword(tt.password)
			if err != nil {
				t.Fatalf("HashPassword 报错: %v", err)
			}
			ok, err := VerifyPassword(tt.password, phc)
			if err != nil {
				t.Fatalf("正确密码验证报错: %v", err)
			}
			if !ok {
				t.Fatal("正确密码验证未通过")
			}
			ok, err = VerifyPassword(tt.password+"x", phc)
			if err != nil {
				t.Fatalf("密码不匹配不应报错（返回 err=nil）: %v", err)
			}
			if ok {
				t.Fatal("错误密码验证通过")
			}
		})
	}
}

// TestHashPasswordPHCFormat 锁定输出格式（PHC 规范子集）：
// 盐 16 字节 → B64 22 字符；哈希 32 字节 → B64 43 字符。
func TestHashPasswordPHCFormat(t *testing.T) {
	phc, err := HashPassword("format-check")
	if err != nil {
		t.Fatalf("HashPassword 报错: %v", err)
	}
	re := regexp.MustCompile(`^\$argon2id\$v=19\$m=65536,t=1,p=4\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$`)
	if !re.MatchString(phc) {
		t.Fatalf("PHC 格式不符: %s", phc)
	}
}

// TestHashPasswordRandomSalt 同密码两次哈希必须不同（盐随机），
// 否则攻击者可用彩虹表按哈希值直接比对出同密码用户。
func TestHashPasswordRandomSalt(t *testing.T) {
	a, err := HashPassword("same-password")
	if err != nil {
		t.Fatalf("第一次哈希报错: %v", err)
	}
	b, err := HashPassword("same-password")
	if err != nil {
		t.Fatalf("第二次哈希报错: %v", err)
	}
	if a == b {
		t.Fatal("两次哈希输出相同，盐未随机化")
	}
}

// TestVerifyPasswordMalformedPHC 锁定解析防御面：任何畸形串返回
// (false, 非 nil error) 且错误可用 IsMalformedPHC 识别。
// 所有用例都在解析阶段失败，不会触发昂贵的 argon2 计算。
func TestVerifyPasswordMalformedPHC(t *testing.T) {
	tests := []struct {
		name string
		phc  string
	}{
		{"空串", ""},
		{"纯文本", "not-a-phc"},
		{"缺哈希段", "$argon2id$v=19$m=65536,t=1,p=4$c2FsdHNhbHRzYWx0c2FsdA"},
		{"多出一段", "$argon2id$v=19$m=65536,t=1,p=4$c2FsdHNhbHRzYWx0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4$extra"},
		{"算法不对", "$argon2i$v=19$m=65536,t=1,p=4$c2FsdHNhbHRzYWx0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"算法大写", "$ARGON2ID$v=19$m=65536,t=1,p=4$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"旧版本", "$argon2id$v=16$m=65536,t=1,p=4$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"参数缺 p", "$argon2id$v=19$m=65536,t=1$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"参数顺序错", "$argon2id$v=19$t=1,m=65536,p=4$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"参数带 keyid", "$argon2id$v=19$m=65536,t=1,p=4,keyid=aXNhY2tleQ$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"内存为 0", "$argon2id$v=19$m=0,t=1,p=4$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"内存超防御上限（防 DoS）", "$argon2id$v=19$m=9999999999,t=1,p=4$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"并行度超 255", "$argon2id$v=19$m=65536,t=1,p=999$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"参数非数值", "$argon2id$v=19$m=abc,t=1,p=4$c2FsdHNhbHRzYWx0c2F0c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"盐坏 Base64", "$argon2id$v=19$m=65536,t=1,p=4$!!!非法!!!$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"哈希坏 Base64", "$argon2id$v=19$m=65536,t=1,p=4$c2FsdHNhbHRzYWx0c2F0c2FsdA$@@@@@"},
		{"盐带 padding（PHC 不允许）", "$argon2id$v=19$m=65536,t=1,p=4$c2FsdHNhbHRzYWx0c2F0c2FsdA==$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
		{"盐过短（<8 字节）", "$argon2id$v=19$m=65536,t=1,p=4$c2FsdA$dGhpc2lzYXZlcnlsb25naGFzaDEyMzQ1Njc4"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ok, err := VerifyPassword("whatever", tt.phc)
			if ok {
				t.Fatal("畸形 PHC 竟然验证通过")
			}
			if err == nil {
				t.Fatal("畸形 PHC 应返回非 nil error")
			}
			if !IsMalformedPHC(err) {
				t.Fatalf("错误应可被 IsMalformedPHC 识别，实际: %v", err)
			}
		})
	}
}

// TestVerifyPasswordErrorSemantics 错误语义对照：密码错误 → err 为 nil；
// 库中哈希坏了 → err 非 nil。调用方（login handler）依赖该区分。
func TestVerifyPasswordErrorSemantics(t *testing.T) {
	phc, err := HashPassword("right-password")
	if err != nil {
		t.Fatalf("HashPassword 报错: %v", err)
	}
	if _, err := VerifyPassword("wrong-password", phc); err != nil {
		t.Fatalf("密码错误应返回 err=nil，实际: %v", err)
	}
	_, err = VerifyPassword("any-password", "garbage")
	if !errors.Is(err, errMalformedPHC) {
		t.Fatalf("畸形哈希应返回 errMalformedPHC，实际: %v", err)
	}
}
