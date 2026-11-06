package research

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// pixivProvider 走 Pixiv 网页的公开 ajax 接口（/ajax/...，实测 2026-11-06
// 未登录可读，但必须带 Referer https://www.pixiv.net/）：
// author = /ajax/user/{id}?full=1，work = /ajax/illust/{id}。
// 注意 full=1 不含作品列表，author 只回账号资料；作品反查要单独查 work。
type pixivProvider struct {
	client Fetcher
	base   string
}

// Name 实现 Provider。
func (p *pixivProvider) Name() Name { return NamePixiv }

// Operations 实现 Provider（v1 矩阵：author、work）。
func (p *pixivProvider) Operations() []Operation {
	return []Operation{OperationAuthor, OperationWork}
}

// Query 实现 Provider。
func (p *pixivProvider) Query(ctx context.Context, q Query) (ProviderResult, error) {
	switch q.Operation {
	case OperationAuthor:
		return p.queryAuthor(ctx, q)
	case OperationWork:
		return p.queryWork(ctx, q)
	default:
		return ProviderResult{}, ErrUnsupportedOperation
	}
}

// pixivEnvelope 是 Pixiv ajax 的统一信封（error/message/body）。
type pixivEnvelope struct {
	Error   bool            `json:"error"`
	Message string          `json:"message"`
	Body    json.RawMessage `json:"body"`
}

// pixivHeaders 是 Pixiv ajax 的必要请求头（缺 Referer 会被拒）。
func pixivHeaders() map[string]string {
	return map[string]string{
		"Referer":          "https://www.pixiv.net/",
		"Accept":           "application/json",
		"X-Requested-With": "XMLHttpRequest",
	}
}

type pixivUser struct {
	UserID  string `json:"userId"`
	Name    string `json:"name"`
	Image   string `json:"image"`
	Comment string `json:"comment"`
	Webpage string `json:"webpage"`
}

type pixivIllust struct {
	IllustID      string `json:"illustId"`
	IllustTitle   string `json:"illustTitle"`
	IllustComment string `json:"illustComment"`
	ID            string `json:"id"`
	Title         string `json:"title"`
	Description   string `json:"description"`
	UserID        string `json:"userId"`
	UserName      string `json:"userName"`
	CreateDate    string `json:"createDate"`
	UploadDate    string `json:"uploadDate"`
	PageCount     int    `json:"pageCount"`
	IllustType    int    `json:"illustType"`
	Tags          *struct {
		Tags []struct {
			Tag string `json:"tag"`
		} `json:"tags"`
	} `json:"tags"`
}

// queryAuthor 查询画师账号资料。
func (p *pixivProvider) queryAuthor(ctx context.Context, q Query) (ProviderResult, error) {
	userID, ok := digitsOnly(q.Target)
	if !ok {
		return ProviderResult{}, ErrInvalidTarget
	}
	req := Request{
		Method: http.MethodGet,
		URL:    p.base + "/ajax/user/" + url.PathEscape(userID) + "?full=1",
		Header: pixivHeaders(),
	}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	body, result, ok := decodePixivEnvelope(resp.Body)
	if !ok {
		return result, nil
	}
	var user pixivUser
	if err := json.Unmarshal(body, &user); err != nil || strings.TrimSpace(user.UserID) == "" {
		return invalidResponse("Pixiv 用户"), nil
	}
	link := "https://www.pixiv.net/users/" + user.UserID
	extra := map[string]string{}
	if comment := cleanText(user.Comment); comment != "" {
		extra["comment"] = truncateRunes(comment, 500)
	}
	if user.Webpage != "" {
		extra["webpage"] = user.Webpage
	}
	if user.Image != "" {
		extra["avatarUrl"] = user.Image
	}
	return itemsResult([]Item{{
		Title:  strings.TrimSpace(user.Name),
		URL:    link,
		Domain: hostOf(link),
		Author: user.Name,
		ID:     user.UserID,
		Extra:  extra,
	}}), nil
}

// queryWork 查询单个作品详情。
func (p *pixivProvider) queryWork(ctx context.Context, q Query) (ProviderResult, error) {
	workID, ok := digitsOnly(q.Target)
	if !ok {
		return ProviderResult{}, ErrInvalidTarget
	}
	req := Request{
		Method: http.MethodGet,
		URL:    p.base + "/ajax/illust/" + url.PathEscape(workID),
		Header: pixivHeaders(),
	}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	body, result, ok := decodePixivEnvelope(resp.Body)
	if !ok {
		return result, nil
	}
	var illust pixivIllust
	if err := json.Unmarshal(body, &illust); err != nil {
		return invalidResponse("Pixiv 作品"), nil
	}
	id := illust.IllustID
	if id == "" {
		id = illust.ID
	}
	if strings.TrimSpace(id) == "" {
		return invalidResponse("Pixiv 作品"), nil
	}
	title := strings.TrimSpace(illust.IllustTitle)
	if title == "" {
		title = strings.TrimSpace(illust.Title)
	}
	link := "https://www.pixiv.net/artworks/" + id
	extra := map[string]string{"userId": illust.UserID}
	if illust.PageCount > 0 {
		extra["pageCount"] = strconv.Itoa(illust.PageCount)
	}
	if illust.Tags != nil && len(illust.Tags.Tags) > 0 {
		tags := make([]string, 0, len(illust.Tags.Tags))
		for _, t := range illust.Tags.Tags {
			if tag := strings.TrimSpace(t.Tag); tag != "" {
				tags = append(tags, tag)
			}
		}
		if len(tags) > 0 {
			extra["tags"] = truncateRunes(strings.Join(tags, ", "), 300)
		}
	}
	if illust.CreateDate != "" {
		extra["createDate"] = illust.CreateDate
	}
	publishedAt := illust.UploadDate
	if publishedAt == "" {
		publishedAt = illust.CreateDate
	}
	return itemsResult([]Item{{
		Title:       title,
		URL:         link,
		Domain:      hostOf(link),
		Author:      illust.UserName,
		ID:          id,
		PublishedAt: publishedAt,
		Extra:       extra,
	}}), nil
}

// decodePixivEnvelope 解析信封：error=true 视为平台明确不存在/不可读。
func decodePixivEnvelope(body []byte) (json.RawMessage, ProviderResult, bool) {
	var env pixivEnvelope
	if err := json.Unmarshal(body, &env); err != nil {
		return nil, invalidResponse("Pixiv "), false
	}
	if env.Error {
		message := "Pixiv 没有该对象"
		if trimmed := cleanText(env.Message); trimmed != "" {
			message = "Pixiv: " + truncateRunes(trimmed, 200)
		}
		return nil, ProviderResult{Status: StatusNotFound, Err: &ResultError{Code: "NOT_FOUND", Message: message}}, false
	}
	if len(env.Body) == 0 || string(env.Body) == "null" {
		return nil, ProviderResult{Status: StatusNotFound, Err: &ResultError{Code: "NOT_FOUND", Message: "Pixiv 没有该对象"}}, false
	}
	return env.Body, ProviderResult{}, true
}
