package authorattach

// vocabulary_prefill_test.go：通用来源词表自动预填的回归（纯函数表驱动 +
// 真库 EnsureSourceVocabulary 一次性语义）。锁定口径：多人共用平台保留、
// 单作者个人词排除、链接/域名形态排除、排序与 cap 20、无合格词不写键、
// 键存在（PUT 过或已预填）永不再预填。

import (
	"context"
	"fmt"
	"reflect"
	"testing"

	"qimeng-media/server/internal/authoring"
)

// block 是测试用作者块速记（首别名为身份名，来源行原样传入）。
func block(name string, sources ...string) authoring.AuthorBlock {
	return authoring.AuthorBlock{AuthorNames: []string{name}, Sources: sources}
}

func TestPrefillSourceVocabulary(t *testing.T) {
	cases := []struct {
		name   string
		blocks []authoring.AuthorBlock
		want   []string
	}{
		{
			"多人共用平台保留、单作者词与链接形态排除",
			[]authoring.AuthorBlock{
				block("甲", "lofter", "https://yandex.com/abc", "甲的私人x页"),
				block("乙", "lofter", "forum-c", "乙的私人x页"),
				block("丙", "lofter", "site-a.cr"),
			},
			[]string{"lofter"},
		},
		{
			"同作者跨片段重复引用不累加（块 id 去重）",
			[]authoring.AuthorBlock{
				block("甲", "lofter"),
				block("甲", "lofter"),
				block("乙", "forum-c"),
			},
			[]string{},
		},
		{
			"域名形态（含点号）排除、无点号平台名不受影响",
			[]authoring.AuthorBlock{
				block("甲", "site-a.cr", "lofter", "www.example.com", "forum-c"),
				block("乙", "site-a.cr", "lofter", "forum-c"),
			},
			[]string{"forum-c", "lofter"},
		},
		{
			"http/www. 大小写不敏感排除",
			[]authoring.AuthorBlock{
				block("甲", "HTTPS://Fanbox.cc/one", "WWW.site.org"),
				block("乙", "https://fanbox.cc/two", "www.site.org"),
			},
			[]string{},
		},
		{
			"排序：authorCount 降序、name 升序",
			[]authoring.AuthorBlock{
				block("甲", "冷门站", "lofter", "forum-c"),
				block("乙", "冷门站", "lofter"),
				block("丙", "冷门站"),
			},
			[]string{"冷门站", "lofter"},
		},
		{
			"上限 20 截断",
			[]authoring.AuthorBlock{
				block("甲", sharedWords(prefillMaxItems+5)...),
				block("乙", sharedWords(prefillMaxItems+5)...),
			},
			sharedWords(prefillMaxItems),
		},
		{
			"控制字符词排除（ValidSourceWord 同款校验）",
			[]authoring.AuthorBlock{
				block("甲", "bad\tword", "lofter"),
				block("乙", "bad\tword", "lofter"),
			},
			[]string{"lofter"},
		},
		{
			"无块/无别名块忽略",
			[]authoring.AuthorBlock{
				{Sources: []string{"lofter"}},
				block("甲", "lofter"),
			},
			[]string{},
		},
		{"空输入 → 空切片", nil, []string{}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := PrefillSourceVocabulary(c.blocks)
			if !reflect.DeepEqual(got, c.want) {
				t.Fatalf("PrefillSourceVocabulary=%#v, want %#v", got, c.want)
			}
		})
	}
}

// sharedWords 造 n 个被两位作者共用的词（site00.. 两位零填充，字典序即
// 数值序，便于断言截断后保留的正是 name 升序的前 n 个）。
func sharedWords(n int) []string {
	out := make([]string, 0, n)
	for i := 0; i < n; i++ {
		out = append(out, fmt.Sprintf("site%02d", i))
	}
	return out
}

// TestEnsureSourceVocabulary 一次性预填的 kv 语义（真库）：
// 无片段/无合格词 → 不写键（未来片段导入后自然重试）；有共用词 → 预填并
// 写键；键已存在（已预填或用户 PUT 过含清空）→ 永不再预填。
func TestEnsureSourceVocabulary(t *testing.T) {
	ctx := context.Background()
	q := newTestDB(t)
	ensure := func() ([]string, bool) {
		t.Helper()
		got, err := EnsureSourceVocabulary(ctx, q, testNow)
		if err != nil {
			t.Fatalf("EnsureSourceVocabulary 失败: %v", err)
		}
		_, ok, err := loadSourceVocabularyStored(ctx, q)
		if err != nil {
			t.Fatalf("读词表键失败: %v", err)
		}
		return got, ok
	}

	// 无片段 → 键不存在、返回空、不写键。
	if got, ok := ensure(); ok || len(got) != 0 {
		t.Fatalf("无片段 Ensure=（%v, 键存在=%v），want（空, false）", got, ok)
	}

	// 只有单作者个人词 → 仍无合格词、不写键。
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "a.txt", Content: "1  作者甲\n来源\nlofter\n甲的私人x页\n作品\na.jpg\n"},
	}); err != nil {
		t.Fatalf("写片段失败: %v", err)
	}
	if got, ok := ensure(); ok || len(got) != 0 {
		t.Fatalf("单作者词不应预填：%v, 键存在=%v", got, ok)
	}

	// 再导入第二位作者（共用 lofter）→ 预填 [lofter] 并写键。
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "a.txt", Content: "1  作者甲\n来源\nlofter\n作品\na.jpg\n"},
		{Filename: "b.txt", Content: "1  作者乙\n来源\nlofter\n乙的私人x页\n作品\nb.jpg\n"},
	}); err != nil {
		t.Fatalf("写片段失败: %v", err)
	}
	if got, ok := ensure(); !ok || !reflect.DeepEqual(got, []string{"lofter"}) {
		t.Fatalf("预填=%v, 键存在=%v, want（[lofter], true）", got, ok)
	}

	// 键已存在（已预填）→ 新增第三位作者引入新共用词不再触发预填。
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "a.txt", Content: "1  作者甲\n来源\nlofter\n作品\na.jpg\n"},
		{Filename: "b.txt", Content: "1  作者乙\n来源\nlofter\n作品\nb.jpg\n"},
		{Filename: "c.txt", Content: "1  作者丙\n来源\nlofter\nforum-c\n作品\nc.jpg\n"},
	}); err != nil {
		t.Fatalf("写片段失败: %v", err)
	}
	if got, ok := ensure(); !ok || !reflect.DeepEqual(got, []string{"lofter"}) {
		t.Fatalf("键已存在不得再预填：%v, 键存在=%v", got, ok)
	}

	// 用户 PUT 过（清空写空数组）→ 永不覆盖。
	if err := SaveSourceVocabulary(ctx, q, testNow, []string{}); err != nil {
		t.Fatalf("PUT 空词表失败: %v", err)
	}
	if got, ok := ensure(); !ok || len(got) != 0 {
		t.Fatalf("用户清空后不得再预填：%v, 键存在=%v", got, ok)
	}
}
