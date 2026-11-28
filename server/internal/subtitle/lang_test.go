package subtitle

import "testing"

// TestNormalizeLang 覆盖用户口径的全部别名与大小写/下划线混用形态。
func TestNormalizeLang(t *testing.T) {
	cases := map[string]string{
		"zh": "zh-Hans", "ZH": "zh-Hans", "zh-cn": "zh-Hans", "zh_cn": "zh-Hans",
		"zh-CN": "zh-Hans", "zh-hans": "zh-Hans", "chs": "zh-Hans", "sc": "zh-Hans",
		"GB": "zh-Hans", "gb": "zh-Hans",
		"zh-tw": "zh-Hant", "zh_tw": "zh-Hant", "zh-hant": "zh-Hant", "cht": "zh-Hant",
		"tc": "zh-Hant", "big5": "zh-Hant", "zh-HK": "zh-Hant",
		"en": "en", "EN": "en", "eng": "en",
		"ja": "ja", "jp": "ja", "jpn": "ja",
		"ko": "ko", "kor": "ko", "KO": "ko",
		// 空/非法 → und（不猜测，也不丢轨）
		"": "und", "  ": "und", "??": "und", "zh cn": "und", "zh..cn": "und",
		// 通用 BCP 47 形态按规范大小写返回
		"pt-br": "pt-BR", "fr": "fr", "de-1996": "de-1996", "sr-latn": "sr-Latn", "yue": "yue",
	}
	for raw, want := range cases {
		if got := NormalizeLang(raw); got != want {
			t.Fatalf("NormalizeLang(%q) = %q, want %q", raw, got, want)
		}
	}
}

// TestLangLabel 标签来自服务端单源（与 Web/Android 共用），未知语言原样。
func TestLangLabel(t *testing.T) {
	cases := map[string]string{
		"zh-Hans": "中文（简体）",
		"zh-Hant": "中文（繁体）",
		"en":      "English",
		"ja":      "日本語",
		"ko":      "한국어",
		"und":     "未标注",
		"pt-BR":   "pt-BR",
	}
	for lang, want := range cases {
		if got := LangLabel(lang); got != want {
			t.Fatalf("LangLabel(%q) = %q, want %q", lang, got, want)
		}
	}
}
