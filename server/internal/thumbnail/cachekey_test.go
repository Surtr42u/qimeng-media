package thumbnail

import (
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
	"regexp"
	"testing"
)

// hexPattern 校验键是纯小写 hex（SHA-256 hex 的标准形态）。
var hexPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// TestCacheKeyStable 同输入必须同输出：键是磁盘文件名，抖动意味着缓存永远不命中。
func TestCacheKeyStable(t *testing.T) {
	first := CacheKey("asset-1", SizeGrid)
	for i := 0; i < 10; i++ {
		if got := CacheKey("asset-1", SizeGrid); got != first {
			t.Fatalf("同输入不同输出：第一次 %s，第 %d 次 %s", first, i+1, got)
		}
	}
	if !hexPattern.MatchString(first) {
		t.Fatalf("键必须是 64 位小写 hex，得到 %q", first)
	}
}

// TestCacheKeyDiffers 不同资产或不同尺寸必须不同键：同键意味着尺寸互相覆盖。
func TestCacheKeyDiffers(t *testing.T) {
	base := CacheKey("asset-1", SizeGrid)
	others := map[string]string{
		"不同尺寸":   CacheKey("asset-1", SizePreview),
		"不同资产":   CacheKey("asset-2", SizeGrid),
		"拼接歧义对照": CacheKey("asset-1:", SizeGrid), // 分隔符语义验证："a:"+512 ≠ "a":512
	}
	for name, key := range others {
		if key == base {
			t.Errorf("%s 应产生不同键，却与基准键相同: %s", name, key)
		}
	}
}

// TestCacheKeyGolden 黄金向量回归锁定：键是磁盘缓存与 HTTP 缓存头的公共名字，
// 算法（版本段/拼接格式/哈希）意外变更 = 全库缩略图一夜变孤儿，必须在这里
// 当场失败。
// 期望值由 sha256("v4:a:512") / sha256("v4:a:1024") / sha256("v4:b:512")
// 独立计算得出（2026-10-04 随静图降级档 mjpeg 质量 4→2 升 "v4" 版本段——
// 升级即换键，v3 旧缓存全部自然失效重建，见 cachekey.go 版本段注释）。
func TestCacheKeyGolden(t *testing.T) {
	cases := []struct {
		assetID string
		size    Size
		want    string
	}{
		{"a", SizeGrid, "f17a7105eb5be5d05f73edc2c06b95a771c44e84a59d3ebc85874d1b33fcaea9"},
		{"a", SizePreview, "01d0b4f779dde6b8472f51cec41493cdc2e3d757be3e31cf3cd54bd4f72c71fd"},
		{"b", SizeGrid, "48c265e18d5713b1e12d86c48160e969f38c4abf8cccac3a40d4de247338b03c"},
	}
	for _, tc := range cases {
		if got := CacheKey(tc.assetID, tc.size); got != tc.want {
			t.Errorf("CacheKey(%q, %d) = %s，黄金向量期望 %s（键算法被意外变更？）",
				tc.assetID, int(tc.size), got, tc.want)
		}
	}
}

// TestCacheKeyStrategyVersionDiffers 锁定版本段语义：同资产同尺寸在当前版本
// 段（v4）与紧邻上一代（v3，降级档质量提升前的策略）及最初的裸键（无版本段）
// 下的键必须互不相同——策略升级 = 换键 = 旧缓存全部失效重建（DOMAIN_RULES
// §11"抽帧位置策略变更后旧缩略图缓存必须失效重建"）。策略再升版时此处随
// 实现同步滚动：把 "v4" 行换新值、上一行值改成旧当前值。
func TestCacheKeyStrategyVersionDiffers(t *testing.T) {
	if got := CacheKey("a", SizeGrid); got != cacheKeyGolden("v4:a:512") {
		t.Fatalf("版本段参与哈希失效：got %s", got)
	}
	previous := cacheKeyGolden("v3:a:512") // 上一代版本段（v3，降级档质量提升前）
	if CacheKey("a", SizeGrid) == previous {
		t.Fatal("与上一代版本段键相同：策略升级后 v3 旧缓存不会失效")
	}
	legacy := cacheKeyGolden("a:512") // 最初的无版本段裸键
	if CacheKey("a", SizeGrid) == legacy {
		t.Fatal("与无版本段裸键相同：版本段未生效")
	}
}

// cacheKeyGolden 用独立表达式复算 sha256（不经 CacheKey 实现，防止测试
// 与实现同错——例如两者都忘了分隔符时测试仍会通过）。
func cacheKeyGolden(input string) string {
	sum := sha256.Sum256([]byte(input))
	return hex.EncodeToString(sum[:])
}

// TestThumbPathLayout 锁定目录布局：dataDir/thumbs/{key[:2]}/{key}{ext}
// （ext 由静图格式决定，webp/.webp、jpeg/.jpg，见 stillformat.go）。
func TestThumbPathLayout(t *testing.T) {
	key := CacheKey("asset-1", SizeGrid)
	dataDir := filepath.Join("srv", "data")
	got := ThumbPath(dataDir, key, ".webp")
	want := filepath.Join("srv", "data", "thumbs", key[:2], key+".webp")
	if got != want {
		t.Fatalf("ThumbPath = %s，期望 %s", got, want)
	}
	gotJPEG := ThumbPath(dataDir, key, ".jpg")
	wantJPEG := filepath.Join("srv", "data", "thumbs", key[:2], key+".jpg")
	if gotJPEG != wantJPEG {
		t.Fatalf("ThumbPath(jpeg) = %s，期望 %s", gotJPEG, wantJPEG)
	}
	if len(key) < 2 {
		t.Fatalf("键长度不足以切两级目录前缀: %q", key)
	}
}
