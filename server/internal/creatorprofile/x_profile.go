package creatorprofile

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

const maxXBiographyRunes = 1000 // OpenAPI CreatorXProfile.biography同步预算。
const maxXHandleBytes = 15

// XHandle只接受HTTPS账号主页，拒绝推文、搜索、查询参数及其他站点。
func XHandle(raw string) string {
	u, err := publicURL(raw)
	if err != nil || u.RawQuery != "" {
		return ""
	}
	host := strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
	if host != "x.com" && host != "twitter.com" {
		return ""
	}
	handle := strings.TrimSuffix(strings.TrimPrefix(u.Path, "/"), "/")
	if len(handle) == 0 || len(handle) > maxXHandleBytes {
		return ""
	}
	for _, c := range handle {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_') {
			return ""
		}
	}
	switch strings.ToLower(handle) {
	case "home", "search", "explore", "notifications", "messages", "settings", "i", "intent":
		return ""
	}
	return strings.ToLower(handle)
}

func validateXProfile(profile *XProfile) error {
	if profile == nil {
		return nil
	}
	if profile.URL == "" {
		if profile.Biography != "" {
			return ErrInvalid
		}
		return nil
	}
	if XHandle(profile.URL) == "" || !utf8.ValidString(profile.Biography) || utf8.RuneCountInString(profile.Biography) > maxXBiographyRunes {
		return ErrInvalid
	}
	for _, c := range profile.Biography {
		if unicode.IsControl(c) && c != '\n' && c != '\r' && c != '\t' {
			return ErrInvalid
		}
	}
	return nil
}
