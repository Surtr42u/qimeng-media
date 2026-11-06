package research

import (
	"bytes"
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"golang.org/x/net/html"
)

// bingProvider 走 Bing 的 HTML 搜索页（实测 2026-11-06 可直连，10 条
// b_algo、无验证码）：operation=search 时返回网页结果。
// 这是"检索线索"而不是平台证据：结果只能提示去哪里继续核验。
type bingProvider struct {
	client Fetcher
	base   string
}

// Name 实现 Provider。
func (p *bingProvider) Name() Name { return NameBing }

// Operations 实现 Provider（v1 矩阵：search）。
func (p *bingProvider) Operations() []Operation { return []Operation{OperationSearch} }

// Query 实现 Provider。
func (p *bingProvider) Query(ctx context.Context, q Query) (ProviderResult, error) {
	if q.Operation != OperationSearch {
		return ProviderResult{}, ErrUnsupportedOperation
	}
	query := strings.TrimSpace(q.Target)
	if query == "" || len([]rune(query)) > MaxTargetRunes {
		return ProviderResult{}, ErrInvalidTarget
	}
	limit := trimLimit(q)
	values := url.Values{}
	values.Set("q", query)
	values.Set("setlang", "zh-Hans")
	values.Set("count", strconv.Itoa(limit))
	req := Request{
		Method: http.MethodGet,
		URL:    p.base + "/search?" + values.Encode(),
		Header: map[string]string{
			"Accept":          "text/html,application/xhtml+xml",
			"Accept-Language": "zh-CN,zh;q=0.9,en;q=0.6",
		},
	}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	items := parseBingResults(resp.Body, limit)
	return itemsResult(items), nil
}

// parseBingResults 从 Bing 结果页提取 b_algo 条目：取每条结果的首个 http(s)
// 链接与 h2 标题（HTML5 tokenizer，不执行页面脚本、不构造 DOM）。
func parseBingResults(body []byte, limit int) []Item {
	tokenizer := html.NewTokenizer(bytes.NewReader(body))
	items := make([]Item, 0, limit)
	depth := 0
	var current *Item
	var title strings.Builder
	captureTitle := false
	for {
		kind := tokenizer.Next()
		if kind == html.ErrorToken {
			break
		}
		switch kind {
		case html.StartTagToken:
			name, _ := tokenizer.TagName()
			tag := string(name)
			if depth == 0 {
				if tag == "li" && tokenizerHasClass(tokenizer, "b_algo") {
					depth = 1
					current = &Item{}
					title.Reset()
					captureTitle = false
				}
				continue
			}
			switch tag {
			case "li":
				depth++
			case "a":
				if current != nil && current.URL == "" {
					if href, ok := tokenizerAttr(tokenizer, "href"); ok && isHTTPURL(href) {
						current.URL = strings.TrimSpace(href)
					}
				}
			case "h2":
				captureTitle = true
			}
		case html.TextToken:
			if captureTitle && current != nil {
				title.Write(tokenizer.Text())
			}
		case html.EndTagToken:
			if depth == 0 {
				continue
			}
			name, _ := tokenizer.TagName()
			switch string(name) {
			case "h2":
				if current != nil && current.Title == "" {
					current.Title = cleanText(title.String())
				}
				captureTitle = false
				title.Reset()
			case "li":
				depth--
				if depth == 0 && current != nil {
					if current.URL != "" {
						current.Domain = hostOf(current.URL)
						if current.Title == "" {
							current.Title = current.URL
						}
						items = append(items, *current)
					}
					current = nil
					if len(items) >= limit {
						return items
					}
				}
			}
		}
	}
	return items
}

// tokenizerHasClass 判断当前起始标签的 class 是否包含指定类名。
func tokenizerHasClass(z *html.Tokenizer, want string) bool {
	for {
		key, value, more := z.TagAttr()
		if string(key) == "class" {
			for _, class := range strings.Fields(string(value)) {
				if class == want {
					return true
				}
			}
			return false
		}
		if !more {
			return false
		}
	}
}

// tokenizerAttr 读取当前起始标签的属性值。
func tokenizerAttr(z *html.Tokenizer, want string) (string, bool) {
	for {
		key, value, more := z.TagAttr()
		if string(key) == want {
			return string(value), true
		}
		if !more {
			return "", false
		}
	}
}
