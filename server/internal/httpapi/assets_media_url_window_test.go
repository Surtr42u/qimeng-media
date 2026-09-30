// assets_media_url_window_test.go：签名直链 exp 窗口对齐（ADR-0027）语义锁定。
// 四组用例：同窗恒定 / 跨窗轮换 / 有效期界 (ttl, 2*ttl] / URL 级逐字节恒定。
// 全部用注入时刻，不依赖真实时间、不 sleep（与 auth 包测试同一纪律）。
package httpapi

import (
	"testing"
	"time"
)

// at 构造 UTC 注入时刻（窗口对齐按 Unix 纪元绝对时间，时区无关；
// UTC 让用例里的"几点"与纪元边界一一对应，读起来不换算）。
func at(hour, min, sec int) time.Time {
	return time.Date(2026, 9, 30, hour, min, sec, 0, time.UTC)
}

// TestMediaURLExpirySameWindowStable 同窗恒定：同一窗口（[06:00,12:00)）
// 内两个不同时刻（10:23 与 11:59）得到相同 exp——浏览器缓存可命中的前提。
func TestMediaURLExpirySameWindowStable(t *testing.T) {
	ttl := 6 * time.Hour
	ten23 := mediaURLExpiry(at(10, 23, 0), ttl)
	eleven59 := mediaURLExpiry(at(11, 59, 59), ttl)
	if ten23 != eleven59 {
		t.Fatalf("同窗口 exp 应恒定: %d != %d", ten23, eleven59)
	}
}

// TestMediaURLExpiryRotatesAcrossWindow 跨窗轮换：窗口边界两侧（11:59 与
// 12:01）exp 不同——URL 每日至多轮换 4 次（窗长 6h）。
func TestMediaURLExpiryRotatesAcrossWindow(t *testing.T) {
	ttl := 6 * time.Hour
	before := mediaURLExpiry(at(11, 59, 59), ttl)
	after := mediaURLExpiry(at(12, 0, 1), ttl)
	if before == after {
		t.Fatalf("跨窗口 exp 应轮换: 恒为 %d", before)
	}
}

// TestMediaURLExpiryValidityBounds 有效期界：任意时刻 exp-now 恒
// > ttl 且 ≤ 2*ttl。锁死"纯取窗口上界不叠加 ttl"备选方案的回归——
// 那会在窗口尾产出几分钟内过期的 URL（ADR-0027 已否决该方案）。
func TestMediaURLExpiryValidityBounds(t *testing.T) {
	ttl := 6 * time.Hour
	// 一天逐 15 分钟步进：恰好命中窗口边界（12:00 → 有效期=2*ttl 含端点）
	// 与窗口尾；另补亚秒尾时刻锁 Unix 秒取整后的下界。
	for m := 0; m <= 24*60; m += 15 {
		now := at(0, 0, 0).Add(time.Duration(m) * time.Minute)
		validity := time.Duration(mediaURLExpiry(now, ttl)-now.Unix()) * time.Second
		if validity <= ttl || validity > 2*ttl {
			t.Fatalf("时刻 %s 有效期 %v 越界，应 ∈ (ttl, 2*ttl]", now, validity)
		}
	}
	tail := time.Date(2026, 9, 30, 11, 59, 59, 999999999, time.UTC)
	validity := time.Duration(mediaURLExpiry(tail, ttl)-tail.Unix()) * time.Second
	if validity <= ttl {
		t.Fatalf("窗口尾亚秒时刻有效期 %v 应 > ttl", validity)
	}
}

// TestSignedMediaURLSameWindowByteIdentical URL 级恒定：同一资产在同一窗口
// 两次生成 origUrl/thumbUrl 逐字节相同（若签名引入随机量在此暴露）；跨窗
// 轮换则必然不同。用真实生成函数 signedMediaURL/thumbURL（非直接调
// mediaURLExpiry），锁定端到端的 URL 字符串稳定性。
func TestSignedMediaURLSameWindowByteIdentical(t *testing.T) {
	ttl := 6 * time.Hour
	secret := []byte("window-test-secret") // 明显合成测试密钥（SECURITY 仓库卫生）
	s := &Server{secret: secret, ttl: ttl}
	assetID := "00000000-0000-0000-0000-000000000001"
	origPath := mediaPathOrig + assetID

	s.now = func() time.Time { return at(10, 23, 0) }
	origA := s.signedMediaURL(origPath)
	thumbA := s.thumbURL(assetID, "lg")

	s.now = func() time.Time { return at(11, 59, 59) } // 同窗口另一时刻
	origB := s.signedMediaURL(origPath)
	thumbB := s.thumbURL(assetID, "lg")

	if origA != origB {
		t.Fatalf("同窗 origUrl 应逐字节恒定:\n%s\n%s", origA, origB)
	}
	if thumbA != thumbB {
		t.Fatalf("同窗 thumbUrl 应逐字节恒定:\n%s\n%s", thumbA, thumbB)
	}

	s.now = func() time.Time { return at(12, 0, 1) } // 跨入下一窗口
	origC := s.signedMediaURL(origPath)
	if origA == origC {
		t.Fatalf("跨窗 origUrl 应轮换: 恒为 %s", origA)
	}
}
