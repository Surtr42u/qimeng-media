package research

import (
	"encoding/json"
	"net/url"
	"strings"
	"time"
)

// sessionParamKeys 是证据落盘前要剥掉的会话类查询参数（大小写不敏感）。
// 为什么要剥：研究目标常从浏览器复制，URL 里可能带会话/签名参数；证据
// 文件只该记录"查了什么公开对象"，不落凭据类参数。
var sessionParamKeys = map[string]bool{
	"auth": true, "code": true, "cookie": true, "csrf": true, "exp": true,
	"key": true, "session": true, "sessionid": true, "sig": true,
	"signature": true, "token": true, "access_token": true, "refresh_token": true,
}

// sanitizeURL 去掉 userinfo、fragment 与会话类查询参数（保留其他参数），
// 解析失败原样返回空串。
func sanitizeURL(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return ""
	}
	u, err := url.Parse(trimmed)
	if err != nil || u.Hostname() == "" {
		return ""
	}
	u.User = nil
	u.Fragment = ""
	if u.RawQuery != "" {
		values := url.Values{}
		for key, list := range u.Query() {
			if sessionParamKeys[strings.ToLower(key)] {
				continue
			}
			for _, v := range list {
				values.Add(key, v)
			}
		}
		u.RawQuery = values.Encode()
	}
	return u.String()
}

// truncateRunes 按 Unicode 码点截断（避免切坏多字节字符）。
func truncateRunes(s string, max int) string {
	if max <= 0 {
		return ""
	}
	runes := []rune(s)
	if len(runes) <= max {
		return s
	}
	return string(runes[:max])
}

// firstLine 取文本首个非空行（帖子/描述类字段的标题化惯例）。
func firstLine(text string) string {
	for _, line := range strings.Split(text, "\n") {
		if trimmed := strings.TrimSpace(line); trimmed != "" {
			return trimmed
		}
	}
	return ""
}

// stringList 把 JSON 字段宽松解析为字符串列表：兼容字符串或字符串数组
// （平台对 alias/sources 等字段的口径并不统一）。
func stringList(raw json.RawMessage) []string {
	if len(raw) == 0 {
		return nil
	}
	var single string
	if err := json.Unmarshal(raw, &single); err == nil {
		if strings.TrimSpace(single) == "" {
			return nil
		}
		return []string{single}
	}
	var list []string
	if err := json.Unmarshal(raw, &list); err == nil {
		return list
	}
	return nil
}

// unixSecondsToRFC3339 把 Unix 秒归一为 RFC3339（0/非法值返回空串）。
func unixSecondsToRFC3339(seconds int64) string {
	if seconds <= 0 {
		return ""
	}
	return time.Unix(seconds, 0).UTC().Format(time.RFC3339)
}

// compactTimestamp 把 CDX 的 14 位时间戳（YYYYMMDDhhmmss）归一为 RFC3339。
func compactTimestamp(raw string) string {
	if len(raw) != 14 {
		return raw
	}
	t, err := time.Parse("20060102150405", raw)
	if err != nil {
		return raw
	}
	return t.UTC().Format(time.RFC3339)
}

// digitsOnly 校验并返回纯数字 ID（帖子/作品/工坊 ID 用）。
func digitsOnly(raw string) (string, bool) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" || len(trimmed) > 30 {
		return "", false
	}
	for _, r := range trimmed {
		if r < '0' || r > '9' {
			return "", false
		}
	}
	return trimmed, true
}

// cleanText 去掉首尾空白并压掉控制字符（搜索结果/描述进协议前统一清洗）。
func cleanText(text string) string {
	cleaned := strings.Map(func(r rune) rune {
		if r == '\n' || r == '\t' || r == '\r' {
			return ' '
		}
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, text)
	return strings.TrimSpace(cleaned)
}
