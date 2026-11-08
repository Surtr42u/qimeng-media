package research

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// waybackProvider 走 Wayback Machine 的 CDX 接口（web.archive.org，
// 实测 2026-11-06 可直连）：operation=url 时按目标 URL 列存档快照。
// 这是"线索"而不是作者证据：存档只证明某 URL 在某个时刻存在。
type waybackProvider struct {
	client Fetcher
	base   string
}

// Name 实现 Provider。
func (p *waybackProvider) Name() Name { return NameWayback }

// Operations 实现 Provider（v1 矩阵：url）。
func (p *waybackProvider) Operations() []Operation { return []Operation{OperationURL} }

// Query 实现 Provider。
func (p *waybackProvider) Query(ctx context.Context, q Query) (ProviderResult, error) {
	if q.Operation != OperationURL {
		return ProviderResult{}, ErrUnsupportedOperation
	}
	target := strings.TrimSpace(q.Target)
	if target == "" || len([]rune(target)) > MaxTargetRunes {
		return ProviderResult{}, ErrInvalidTarget
	}
	parsed, err := url.Parse(target)
	if err != nil || parsed.Hostname() == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return ProviderResult{}, ErrInvalidTarget
	}
	limit := trimLimit(q)
	values := url.Values{}
	values.Set("url", target)
	values.Set("output", "json")
	values.Set("fl", "timestamp,original,mimetype,statuscode")
	values.Set("limit", strconv.Itoa(limit))
	// collapse=digest：同一内容只留最早一条快照，避免同一页面在原站改版
	// 后被几十条 URL 变体刷屏。
	values.Set("collapse", "digest")
	req := Request{Method: http.MethodGet, URL: p.base + "/cdx/search/cdx?" + values.Encode()}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	var rows [][]string
	if err := json.Unmarshal(resp.Body, &rows); err != nil {
		return invalidResponse("Wayback CDX"), nil
	}
	if len(rows) <= 1 {
		return itemsResult(nil), nil
	}
	// 第一行是列名（fl 指定时的固定表头），按表头定位列，避免依赖顺序。
	header := rows[0]
	index := make(map[string]int, len(header))
	for i, name := range header {
		index[strings.ToLower(strings.TrimSpace(name))] = i
	}
	cell := func(row []string, name string) string {
		if idx, ok := index[name]; ok && idx < len(row) {
			return strings.TrimSpace(row[idx])
		}
		return ""
	}
	items := make([]Item, 0, limit)
	for _, row := range rows[1:] {
		if len(items) >= limit {
			break
		}
		timestamp := cell(row, "timestamp")
		original := cell(row, "original")
		if timestamp == "" || original == "" {
			continue
		}
		extra := map[string]string{}
		if mimetype := cell(row, "mimetype"); mimetype != "" {
			extra["mimetype"] = mimetype
		}
		if status := cell(row, "statuscode"); status != "" {
			extra["statusCode"] = status
		}
		items = append(items, Item{
			Title:       truncateRunes(original, 200),
			URL:         "https://web.archive.org/web/" + timestamp + "/" + original,
			Domain:      hostOf(original),
			ID:          timestamp,
			PublishedAt: compactTimestamp(timestamp),
			Extra:       extra,
		})
	}
	return itemsResult(items), nil
}
