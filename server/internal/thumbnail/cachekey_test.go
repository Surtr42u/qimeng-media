package thumbnail

import (
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
// 算法（拼接格式/哈希）意外变更 = 全库缩略图一夜变孤儿，必须在这里当场失败。
// 期望值由 sha256("a:512") / sha256("a:1024") / sha256("b:512") 独立计算得出。
func TestCacheKeyGolden(t *testing.T) {
	cases := []struct {
		assetID string
		size    Size
		want    string
	}{
		{"a", SizeGrid, "0768138cadd1f870dc1df9e6b001ea5aa302f08e69882304d1d27894ed5723d1"},
		{"a", SizePreview, "673bbd8929cc9b97e171728f1e62df5247488475b6a8f1762ef7bcb1b81b4d57"},
		{"b", SizeGrid, "47490747162376c7090bafdb63b75535ce47c21fe87626c32beb7eab8306a41b"},
	}
	for _, tc := range cases {
		if got := CacheKey(tc.assetID, tc.size); got != tc.want {
			t.Errorf("CacheKey(%q, %d) = %s，黄金向量期望 %s（键算法被意外变更？）",
				tc.assetID, int(tc.size), got, tc.want)
		}
	}
}

// TestThumbPathLayout 锁定目录布局：dataDir/thumbs/{key[:2]}/{key}.webp。
func TestThumbPathLayout(t *testing.T) {
	key := CacheKey("asset-1", SizeGrid)
	dataDir := filepath.Join("srv", "data")
	got := ThumbPath(dataDir, key)
	want := filepath.Join("srv", "data", "thumbs", key[:2], key+".webp")
	if got != want {
		t.Fatalf("ThumbPath = %s，期望 %s", got, want)
	}
	if len(key) < 2 {
		t.Fatalf("键长度不足以切两级目录前缀: %q", key)
	}
}
