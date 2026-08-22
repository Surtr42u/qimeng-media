package auth

import (
	"errors"
	"testing"
	"time"
)

// fixedClock 返回注入时钟：所有时序用例用它，不依赖真实时间、不 sleep。
func fixedClock(t time.Time) func() time.Time { return func() time.Time { return t } }

// TestSignMediaURLRoundTrip 签名→校验通过（含中文/空格路径）。
func TestSignMediaURLRoundTrip(t *testing.T) {
	secret := []byte("unit-test-secret")
	now := time.Unix(1_000_000, 0)
	tests := []struct {
		name string
		path string
	}{
		{"普通路径", "/media/orig/lib1/movies/电影A/file.mp4"},
		{"含空格与特殊字符", "/media/orig/lib1/a b+c~!@#$%^&()[]{}.mp4"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			exp, sig := SignMediaURL(tt.path, now.Add(6*time.Hour), secret)
			if err := VerifyMediaSignature(tt.path, exp, sig, secret, fixedClock(now)); err != nil {
				t.Fatalf("合法直链校验失败: %v", err)
			}
		})
	}
}

// TestVerifyMediaSignatureRejects 表驱动锁定拒绝面：篡改、过期、错密钥。
// 过期用例用注入时钟推进到 exp 之后，不 sleep。
func TestVerifyMediaSignatureRejects(t *testing.T) {
	secret := []byte("unit-test-secret")
	other := []byte("attacker-secret")
	now := time.Unix(2_000_000, 0)
	path := "/media/orig/lib1/pic.jpg"
	exp, sig := SignMediaURL(path, now.Add(6*time.Hour), secret)

	tests := []struct {
		name    string
		path    string
		sig     string
		secret  []byte
		nowFunc func() time.Time
		wantErr error
	}{
		{"篡改路径", path + "/extra", sig, secret, fixedClock(now), ErrSignatureInvalid},
		{"去掉一级路径", "/media/thumb/lib1/pic.jpg", sig, secret, fixedClock(now), ErrSignatureInvalid},
		{"伪造签名", path, "deadbeef", secret, fixedClock(now), ErrSignatureInvalid},
		{"不同 secret（=全部直链吊销）", path, sig, other, fixedClock(now), ErrSignatureInvalid},
		{"过期（now 越过 exp）", path, sig, secret, fixedClock(now.Add(6*time.Hour + time.Second)), ErrSignatureExpired},
		{"篡改且过期（先验签）", path + "/x", sig, secret, fixedClock(now.Add(7 * time.Hour)), ErrSignatureInvalid},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := VerifyMediaSignature(tt.path, exp, tt.sig, tt.secret, tt.nowFunc)
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("期望错误 %v，实际 %v", tt.wantErr, err)
			}
		})
	}
}

// TestVerifyMediaSignatureExpiryBoundary 过期边界：exp 时刻本身有效
// （now == exp 通过），仅 now > exp 过期——锁定协议对 handler 的一致含义。
func TestVerifyMediaSignatureExpiryBoundary(t *testing.T) {
	secret := []byte("unit-test-secret")
	path := "/media/orig/lib1/pic.jpg"
	expTime := time.Unix(3_000_000, 0)
	exp, sig := SignMediaURL(path, expTime, secret)
	if err := VerifyMediaSignature(path, exp, sig, secret, fixedClock(expTime)); err != nil {
		t.Fatalf("exp 时刻应仍有效: %v", err)
	}
	err := VerifyMediaSignature(path, exp, sig, secret, fixedClock(expTime.Add(time.Second)))
	if !errors.Is(err, ErrSignatureExpired) {
		t.Fatalf("exp 之后应报 ErrSignatureExpired，实际: %v", err)
	}
}

// TestSignMediaURLDelimiterNoAmbiguity 锁定 "\n" 定界符的协议意义：
// message = path+"\n"+exp，路径与时间的拼接不可能产生歧义等价。
func TestSignMediaURLDelimiterNoAmbiguity(t *testing.T) {
	secret := []byte("unit-test-secret")
	// path="a\n1" exp=2 与 path="a" exp=12 的 message 若无定界符会相同；
	// 有定界符则必然不同 → 签名不同。
	exp1, sig1 := SignMediaURL("a\n1", time.Unix(2, 0), secret)
	exp2, sig2 := SignMediaURL("a", time.Unix(12, 0), secret)
	if sig1 == sig2 {
		t.Fatalf("拼接歧义未消除: (%d,%s) 与 (%d,%s) 签名相同", exp1, sig1, exp2, sig2)
	}
	// 各自校验通过（互不冒充）
	if err := VerifyMediaSignature("a\n1", exp1, sig1, secret, fixedClock(time.Unix(1, 0))); err != nil {
		t.Fatalf("path=a\\n1 校验失败: %v", err)
	}
	if err := VerifyMediaSignature("a", exp2, sig2, secret, fixedClock(time.Unix(1, 0))); err != nil {
		t.Fatalf("path=a 校验失败: %v", err)
	}
}

// TestSignMediaURLDeterministic 同输入签名确定（浏览器缓存/重放同 URL 的前提）。
func TestSignMediaURLDeterministic(t *testing.T) {
	secret := []byte("unit-test-secret")
	at := time.Unix(4_000_000, 0)
	exp1, sig1 := SignMediaURL("/media/orig/a.jpg", at, secret)
	exp2, sig2 := SignMediaURL("/media/orig/a.jpg", at, secret)
	if exp1 != exp2 || sig1 != sig2 {
		t.Fatal("同输入签名应确定")
	}
	if exp1 != at.Unix() {
		t.Fatalf("exp 应为 expiresAt 的 Unix 秒: %d != %d", exp1, at.Unix())
	}
}
