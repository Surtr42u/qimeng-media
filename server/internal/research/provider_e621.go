package research

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// e621Provider 走 e621 的公开 JSON 接口（实测 2026-11-06：必须带自定义
// User-Agent，否则 403）：post = /posts/{id}.json，tags = /posts.json?tags=。
type e621Provider struct {
	client Fetcher
	base   string
}

// Name 实现 Provider。
func (p *e621Provider) Name() Name { return NameE621 }

// Operations 实现 Provider（v1 矩阵：post、tags）。
func (p *e621Provider) Operations() []Operation {
	return []Operation{OperationPost, OperationTags}
}

// Query 实现 Provider。
func (p *e621Provider) Query(ctx context.Context, q Query) (ProviderResult, error) {
	switch q.Operation {
	case OperationPost:
		return p.queryPost(ctx, q)
	case OperationTags:
		return p.queryTags(ctx, q)
	default:
		return ProviderResult{}, ErrUnsupportedOperation
	}
}

// e621Headers 是 e621 要求的自定义 UA（缺省会 403）。
func e621Headers() map[string]string {
	return map[string]string{
		"User-Agent": "QimengMedia-Research/1.0 (author/work verification pipeline)",
		"Accept":     "application/json",
	}
}

type e621Post struct {
	ID          int64  `json:"id"`
	CreatedAt   string `json:"created_at"`
	Description string `json:"description"`
	Rating      string `json:"rating"`
	Score       struct {
		Total int64 `json:"total"`
	} `json:"score"`
	File struct {
		URL string `json:"url"`
	} `json:"file"`
	Preview struct {
		URL string `json:"url"`
	} `json:"preview"`
	Tags struct {
		General   []string `json:"general"`
		Artist    []string `json:"artist"`
		Copyright []string `json:"copyright"`
		Character []string `json:"character"`
	} `json:"tags"`
}

// queryPost 查询单个帖子。
func (p *e621Provider) queryPost(ctx context.Context, q Query) (ProviderResult, error) {
	postID, ok := digitsOnly(q.Target)
	if !ok {
		return ProviderResult{}, ErrInvalidTarget
	}
	req := Request{
		Method: http.MethodGet,
		URL:    p.base + "/posts/" + url.PathEscape(postID) + ".json",
		Header: e621Headers(),
	}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	var envelope struct {
		Post *e621Post `json:"post"`
	}
	if err := json.Unmarshal(resp.Body, &envelope); err != nil {
		return invalidResponse("e621 帖子"), nil
	}
	if envelope.Post == nil || envelope.Post.ID == 0 {
		return invalidResponse("e621 帖子"), nil
	}
	return itemsResult([]Item{e621Item(*envelope.Post)}), nil
}

// queryTags 按标签搜索帖子。
func (p *e621Provider) queryTags(ctx context.Context, q Query) (ProviderResult, error) {
	tags := strings.TrimSpace(q.Target)
	if tags == "" || len([]rune(tags)) > MaxTargetRunes {
		return ProviderResult{}, ErrInvalidTarget
	}
	limit := trimLimit(q)
	values := url.Values{}
	values.Set("tags", tags)
	values.Set("limit", strconv.Itoa(limit))
	req := Request{
		Method: http.MethodGet,
		URL:    p.base + "/posts.json?" + values.Encode(),
		Header: e621Headers(),
	}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	var envelope struct {
		Posts []e621Post `json:"posts"`
	}
	if err := json.Unmarshal(resp.Body, &envelope); err != nil {
		return invalidResponse("e621 帖子"), nil
	}
	items := make([]Item, 0, limit)
	for _, post := range envelope.Posts {
		if len(items) >= limit {
			break
		}
		if post.ID == 0 {
			continue
		}
		items = append(items, e621Item(post))
	}
	return itemsResult(items), nil
}

// e621Item 把帖子映射为协议 item（标题取描述首行，标签摘要进 extra）。
func e621Item(post e621Post) Item {
	link := "https://e621.net/posts/" + strconv.FormatInt(post.ID, 10)
	extra := map[string]string{}
	if post.File.URL != "" {
		extra["fileUrl"] = post.File.URL
	}
	if post.Preview.URL != "" {
		extra["previewUrl"] = post.Preview.URL
	}
	if post.Rating != "" {
		extra["rating"] = post.Rating
	}
	extra["score"] = strconv.FormatInt(post.Score.Total, 10)
	if len(post.Tags.Artist) > 0 {
		extra["artistTags"] = truncateRunes(strings.Join(post.Tags.Artist, ", "), 300)
	}
	if len(post.Tags.General) > 0 {
		extra["tags"] = truncateRunes(strings.Join(post.Tags.General, ", "), 300)
	}
	title := truncateRunes(cleanText(firstLine(post.Description)), 200)
	if title == "" {
		title = "e621 post " + strconv.FormatInt(post.ID, 10)
	}
	author := ""
	if len(post.Tags.Artist) > 0 {
		author = strings.Join(post.Tags.Artist, ", ")
	}
	return Item{
		Title:       title,
		URL:         link,
		Domain:      hostOf(link),
		Author:      truncateRunes(author, 200),
		ID:          strconv.FormatInt(post.ID, 10),
		PublishedAt: post.CreatedAt,
		Extra:       extra,
	}
}
