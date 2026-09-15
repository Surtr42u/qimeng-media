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
// 期望值由 sha256("v2:a:512") / sha256("v2:a:1024") / sha256("v2:b:512")
// 独立计算得出（2026-08-29 随抽帧策略对齐 §11 升 "v2" 版本段——升级即换键，
// 旧缓存全部自然失效重建，见 cachekey.go 版本段注释）。
func TestCacheKeyGolden(t *testing.T) {
	cases := []struct {
		assetID string
		size    Size
		want    string
	}{
		{"a", SizeGrid, "1d398268b53acd89df9246c6a402750b4315c8ff0cadab66fe477f41eb3d1240"},
		{"a", SizePreview, "942886b6111d2c6139ceefb664834204f0584cb858daa7f30efc4b21639acc0c"},
		{"b", SizeGrid, "ccbe8199c7539324dd0543bef42f99b4f0924b912ea76c47bda5456ff6e888a8"},
	}
	for _, tc := range cases {
		if got := CacheKey(tc.assetID, tc.size); got != tc.want {
			t.Errorf("CacheKey(%q, %d) = %s，黄金向量期望 %s（键算法被意外变更？）",
				tc.assetID, int(tc.size), got, tc.want)
		}
	}
}

// TestCacheKeyStrategyVersionDiffers 锁定版本段语义：同资产同尺寸在 v2 前的
// 旧键（裸 assetId:size）必须与当前键不同——策略升级 = 换键 = 旧缓存全部
// 失效重建（DOMAIN_RULES §11"抽帧位置策略变更后旧缩略图缓存必须失效重建"）。
func TestCacheKeyStrategyVersionDiffers(t *testing.T) {
	if got := CacheKey("a", SizeGrid); got != cacheKeyGolden("v2:a:512") {
		t.Fatalf("版本段参与哈希失效：got %s", got)
	}
	legacy := cacheKeyGolden("a:512")
	if CacheKey("a", SizeGrid) == legacy {
		t.Fatal("新旧键相同：策略升级后旧缓存不会失效（版本段未生效）")
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
