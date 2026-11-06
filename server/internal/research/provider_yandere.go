package research

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// yandereProvider 走 yande.re 的公开 JSON 接口（实测 2026-11-06 可直连）：
// artist = /artist.json?name=，tags = /post.json?tags=&limit=。
type yandereProvider struct {
	client Fetcher
	base   string
}

// Name 实现 Provider。
func (p *yandereProvider) Name() Name { return NameYandere }

// Operations 实现 Provider（v1 矩阵：artist、tags）。
func (p *yandereProvider) Operations() []Operation {
	return []Operation{OperationArtist, OperationTags}
}

// Query 实现 Provider。
func (p *yandereProvider) Query(ctx context.Context, q Query) (ProviderResult, error) {
	switch q.Operation {
	case OperationArtist:
		return p.queryArtist(ctx, q)
	case OperationTags:
		return p.queryTags(ctx, q)
	default:
		return ProviderResult{}, ErrUnsupportedOperation
	}
}

type yandereArtist struct {
	ID    int64           `json:"id"`
	Name  string          `json:"name"`
	Alias json.RawMessage `json:"alias"`
	URLs  []struct {
		URL string `json:"url"`
	} `json:"urls"`
}

type yanderePost struct {
	ID         int64  `json:"id"`
	Tags       string `json:"tags"`
	CreatedAt  int64  `json:"created_at"`
	FileURL    string `json:"file_url"`
	SampleURL  string `json:"sample_url"`
	PreviewURL string `json:"preview_url"`
	Source     string `json:"source"`
	Score      int64  `json:"score"`
}

// queryArtist 按画师名查 artist 记录（name 为模糊匹配，取回多条由 limit 收敛）。
func (p *yandereProvider) queryArtist(ctx context.Context, q Query) (ProviderResult, error) {
	name := strings.TrimSpace(q.Target)
	if name == "" || len([]rune(name)) > MaxTargetRunes {
		return ProviderResult{}, ErrInvalidTarget
	}
	values := url.Values{}
	values.Set("name", name)
	req := Request{Method: http.MethodGet, URL: p.base + "/artist.json?" + values.Encode()}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	var artists []yandereArtist
	if err := json.Unmarshal(resp.Body, &artists); err != nil {
		return invalidResponse("yande.re 画师"), nil
	}
	limit := trimLimit(q)
	items := make([]Item, 0, limit)
	for _, artist := range artists {
		if len(items) >= limit {
			break
		}
		if artist.ID == 0 || strings.TrimSpace(artist.Name) == "" {
			continue
		}
		extra := map[string]string{}
		if aliases := stringList(artist.Alias); len(aliases) > 0 {
			extra["alias"] = truncateRunes(strings.Join(aliases, ", "), 300)
		}
		if len(artist.URLs) > 0 && artist.URLs[0].URL != "" {
			extra["url"] = artist.URLs[0].URL
		}
		items = append(items, Item{
			Title:  strings.TrimSpace(artist.Name),
			URL:    "https://yande.re/artist/show/" + strconv.FormatInt(artist.ID, 10),
			Domain: "yande.re",
			Author: artist.Name,
			ID:     strconv.FormatInt(artist.ID, 10),
			Extra:  extra,
		})
	}
	return itemsResult(items), nil
}

// queryTags 按标签查最近帖子。
func (p *yandereProvider) queryTags(ctx context.Context, q Query) (ProviderResult, error) {
	tags := strings.TrimSpace(q.Target)
	if tags == "" || len([]rune(tags)) > MaxTargetRunes {
		return ProviderResult{}, ErrInvalidTarget
	}
	limit := trimLimit(q)
	values := url.Values{}
	values.Set("tags", tags)
	values.Set("limit", strconv.Itoa(limit))
	req := Request{Method: http.MethodGet, URL: p.base + "/post.json?" + values.Encode()}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	var posts []yanderePost
	if err := json.Unmarshal(resp.Body, &posts); err != nil {
		return invalidResponse("yande.re 帖子"), nil
	}
	items := make([]Item, 0, limit)
	for _, post := range posts {
		if len(items) >= limit {
			break
		}
		if post.ID == 0 {
			continue
		}
		extra := map[string]string{}
		if post.FileURL != "" {
			extra["fileUrl"] = post.FileURL
		}
		if post.Source != "" {
			extra["source"] = post.Source
		}
		extra["score"] = strconv.FormatInt(post.Score, 10)
		if tags := cleanText(post.Tags); tags != "" {
			extra["tags"] = truncateRunes(tags, 300)
		}
		items = append(items, Item{
			Title:       truncateRunes(cleanText(post.Tags), 120),
			URL:         "https://yande.re/post/show/" + strconv.FormatInt(post.ID, 10),
			Domain:      "yande.re",
			ID:          strconv.FormatInt(post.ID, 10),
			PublishedAt: unixSecondsToRFC3339(post.CreatedAt),
			Extra:       extra,
		})
	}
	return itemsResult(items), nil
}
