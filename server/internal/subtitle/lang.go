package subtitle

import "strings"

// 语言段规范化与展示名（协议 subtitles[].lang / label / isDefault 的单一来源）。
//
// 文件名里的语言写法五花八门（zh / zh_cn / chs / GB、en / eng…），服务端统一
// 规范成 BCP 47 再下发，三端只消费结果、不再各自造词表（2026-11-28 外挂字幕
// 第二批，用户口径「label 由服务端给，别各自造」）。
const (
	// LangUndetermined 未定语言：文件名没有语言段（纯 xxx.ass）或无法识别的语言段。
	// 这类轨仍要出现在列表里，不能静默吞掉。
	LangUndetermined = "und"

	langZhHans = "zh-Hans"
	langZhHant = "zh-Hant"
)

// preferredLangs 默认轨偏好顺序：有简体中文选简体，其次繁体；都没有则不预选。
// 只用于 isDefault 标记（客户端不再自行判断中文）。
var preferredLangs = []string{langZhHans, langZhHant}

// NormalizeLang 把文件名里的语言段规范成 BCP 47。
//
// 已知别名（大小写、下划线/连字符混用都接受）：
//   - zh / zh-cn / zh-hans / chs / sc / gb → zh-Hans
//   - zh-tw / zh-hk / zh-hant / cht / tc / big5 → zh-Hant
//   - en / eng → en；ja / jp / jpn → ja；ko / kor → ko
//
// 空段返回 und；其余形如 BCP 47 的串按规范大小写返回（主标签小写、两位地区
// 大写、四位脚本首字母大写）；不合法形态同样返回 und。
func NormalizeLang(raw string) string {
	tag := strings.ReplaceAll(strings.TrimSpace(raw), "_", "-")
	if tag == "" {
		return LangUndetermined
	}
	switch strings.ToLower(tag) {
	case "zh", "zh-cn", "zh-hans", "chs", "sc", "gb":
		return langZhHans
	case "zh-tw", "zh-hk", "zh-hant", "cht", "tc", "big5":
		return langZhHant
	case "en", "eng":
		return "en"
	case "ja", "jp", "jpn":
		return "ja"
	case "ko", "kor":
		return "ko"
	}
	return canonicalBCP47(tag)
}

// LangLabel 语言码 → 人类可读标签（协议 subtitles[].label 的唯一来源）。
// 未知语言原样返回标签本身（不伪造中文名）；und = 未标注。
func LangLabel(lang string) string {
	switch lang {
	case langZhHans:
		return "中文（简体）"
	case langZhHant:
		return "中文（繁体）"
	case "en":
		return "English"
	case "ja":
		return "日本語"
	case "ko":
		return "한국어"
	case LangUndetermined:
		return "未标注"
	default:
		return lang
	}
}

// canonicalBCP47 校验并规范化 BCP 47 形态：主标签 2-8 位字母，子标签 1-8 位
// 字母数字（两位子标签视为地区 → 大写；四位字母视为脚本 → 首字母大写；
// 其余小写）。任一子标签不合法即整串判 und（宁可标未定，也不把乱码当语言码）。
func canonicalBCP47(tag string) string {
	parts := strings.Split(tag, "-")
	if len(parts[0]) < 2 || len(parts[0]) > 8 || !allAlpha(parts[0]) {
		return LangUndetermined
	}
	out := make([]string, 0, len(parts))
	out = append(out, strings.ToLower(parts[0]))
	for _, part := range parts[1:] {
		if len(part) == 0 || len(part) > 8 || !allAlphaNumeric(part) {
			return LangUndetermined
		}
		switch {
		case len(part) == 2 && allAlpha(part):
			out = append(out, strings.ToUpper(part))
		case len(part) == 4 && allAlpha(part):
			out = append(out, strings.ToUpper(part[:1])+strings.ToLower(part[1:]))
		default:
			out = append(out, strings.ToLower(part))
		}
	}
	return strings.Join(out, "-")
}

func allAlpha(s string) bool {
	for _, r := range s {
		if (r < 'a' || r > 'z') && (r < 'A' || r > 'Z') {
			return false
		}
	}
	return true
}

func allAlphaNumeric(s string) bool {
	for _, r := range s {
		if (r < 'a' || r > 'z') && (r < 'A' || r > 'Z') && (r < '0' || r > '9') {
			return false
		}
	}
	return true
}
