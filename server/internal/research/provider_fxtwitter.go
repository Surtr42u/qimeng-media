package research

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// fxTwitterProvider 走 FxTwitter 的公开只读 API（api.fxtwitter.com）：
// author = /{handle}，post = /status/{id}。实测 2026-11-06 无需登录可直连。
type fxTwitterProvider struct {
	client Fetcher
	base   string
}

// Name 实现 Provider。
func (p *fxTwitterProvider) Name() Name { return NameFxTwitter }

// Operations 实现 Provider（v1 矩阵：author、post）。
func (p *fxTwitterProvider) Operations() []Operation {
	return []Operation{OperationAuthor, OperationPost}
}

// Query 实现 Provider。
func (p *fxTwitterProvider) Query(ctx context.Context, q Query) (ProviderResult, error) {
	switch q.Operation {
	case OperationAuthor:
		return p.queryAuthor(ctx, q)
	case OperationPost:
		return p.queryPost(ctx, q)
	default:
		return ProviderResult{}, ErrUnsupportedOperation
	}
}

// fxEnvelope 是 FxTwitter 的统一信封（code/message + 业务字段）。
type fxEnvelope struct {
	Code    int      `json:"code"`
	Message string   `json:"message"`
	User    *fxUser  `json:"user"`
	Tweet   *fxTweet `json:"tweet"`
}

type fxUser struct {
	ID          string `json:"id"`
	ScreenName  string `json:"screen_name"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Followers   int64  `json:"followers"`
	Joined      string `json:"joined"`
	AvatarURL   string `json:"avatar_url"`
	Website     *struct {
		URL string `json:"url"`
	} `json:"website"`
}

type fxTweet struct {
	ID        string `json:"id"`
	URL       string `json:"url"`
	Text      string `json:"text"`
	CreatedAt string `json:"created_at"`
	Likes     int64  `json:"likes"`
	Retweets  int64  `json:"retweets"`
	Replies   int64  `json:"replies"`
	Author    struct {
		ID         string `json:"id"`
		ScreenName string `json:"screen_name"`
		Name       string `json:"name"`
	} `json:"author"`
}

// queryAuthor 查询账号主页资料。
func (p *fxTwitterProvider) queryAuthor(ctx context.Context, q Query) (ProviderResult, error) {
	handle, ok := fxHandle(q.Target)
	if !ok {
		return ProviderResult{}, ErrInvalidTarget
	}
	req := Request{Method: http.MethodGet, URL: p.base + "/" + url.PathEscape(handle)}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	env, result, ok := decodeFxEnvelope(resp.Body)
	if !ok {
		return result, nil
	}
	if env.User == nil || strings.TrimSpace(env.User.ScreenName) == "" {
		return invalidResponse("FxTwitter 用户"), nil
	}
	user := env.User
	link := "https://x.com/" + user.ScreenName
	extra := map[string]string{
		"screenName": user.ScreenName,
		"followers":  strconv.FormatInt(user.Followers, 10),
	}
	if user.Joined != "" {
		extra["joined"] = user.Joined
	}
	if desc := cleanText(user.Description); desc != "" {
		extra["description"] = truncateRunes(desc, 500)
	}
	if user.Website != nil && user.Website.URL != "" {
		extra["website"] = user.Website.URL
	}
	if user.ID != "" {
		extra["userId"] = user.ID
	}
	return itemsResult([]Item{{
		Title:  strings.TrimSpace(user.Name),
		URL:    link,
		Domain: "x.com",
		Author: user.ScreenName,
		ID:     user.ID,
		Extra:  extra,
	}}), nil
}

// queryPost 查询单条帖子。
func (p *fxTwitterProvider) queryPost(ctx context.Context, q Query) (ProviderResult, error) {
	statusID, ok := fxStatusID(q.Target)
	if !ok {
		return ProviderResult{}, ErrInvalidTarget
	}
	req := Request{Method: http.MethodGet, URL: p.base + "/status/" + url.PathEscape(statusID)}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	env, result, ok := decodeFxEnvelope(resp.Body)
	if !ok {
		return result, nil
	}
	if env.Tweet == nil || strings.TrimSpace(env.Tweet.ID) == "" {
		return invalidResponse("FxTwitter 帖子"), nil
	}
	tweet := env.Tweet
	link := strings.TrimSpace(tweet.URL)
	if link == "" {
		link = fmt.Sprintf("https://x.com/%s/status/%s", tweet.Author.ScreenName, tweet.ID)
	}
	text := cleanText(tweet.Text)
	title := truncateRunes(cleanText(firstLine(tweet.Text)), 200)
	extra := map[string]string{
		"likes":    strconv.FormatInt(tweet.Likes, 10),
		"retweets": strconv.FormatInt(tweet.Retweets, 10),
		"replies":  strconv.FormatInt(tweet.Replies, 10),
	}
	if text != "" {
		extra["text"] = truncateRunes(text, 1000)
	}
	if tweet.Author.Name != "" {
		extra["authorName"] = tweet.Author.Name
	}
	if tweet.Author.ID != "" {
		extra["authorId"] = tweet.Author.ID
	}
	return itemsResult([]Item{{
		Title:       title,
		URL:         link,
		Domain:      hostOf(link),
		Author:      tweet.Author.ScreenName,
		ID:          tweet.ID,
		PublishedAt: tweet.CreatedAt,
		Extra:       extra,
	}}), nil
}

// decodeFxEnvelope 解析信封并按 code 分类（HTTP 200 也可能带 4xx code）。
func decodeFxEnvelope(body []byte) (fxEnvelope, ProviderResult, bool) {
	var env fxEnvelope
	if err := json.Unmarshal(body, &env); err != nil {
		return fxEnvelope{}, invalidResponse("FxTwitter "), false
	}
	switch {
	case env.Code == 200 || env.Code == 0:
		return env, ProviderResult{}, true
	case env.Code == http.StatusNotFound:
		return fxEnvelope{}, ProviderResult{Status: StatusNotFound, Err: &ResultError{Code: "NOT_FOUND", Message: "该账号或帖子不存在"}}, false
	case env.Code == http.StatusUnauthorized || env.Code == http.StatusForbidden:
		return fxEnvelope{}, ProviderResult{Status: StatusBlocked, Err: &ResultError{Code: "BLOCKED", Message: "FxTwitter 拒绝访问"}}, false
	case env.Code == http.StatusTooManyRequests:
		return fxEnvelope{}, ProviderResult{Status: StatusRateLimited, Err: &ResultError{Code: "RATE_LIMITED", Message: "FxTwitter 限流"}}, false
	default:
		return fxEnvelope{}, ProviderResult{Status: StatusProviderUnavailable, Err: &ResultError{
			Code: "UPSTREAM_ERROR", Message: fmt.Sprintf("FxTwitter 返回 code=%d", env.Code),
		}}, false
	}
}

// fxHandle 归一账号标识：接受 @name、name 或 x.com/twitter.com 账号主页。
func fxHandle(target string) (string, bool) {
	trimmed := strings.TrimSpace(target)
	if trimmed == "" {
		return "", false
	}
	if strings.HasPrefix(strings.ToLower(trimmed), "http://") || strings.HasPrefix(strings.ToLower(trimmed), "https://") {
		u, err := url.Parse(trimmed)
		if err != nil {
			return "", false
		}
		switch strings.ToLower(u.Hostname()) {
		case "x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com":
		default:
			return "", false
		}
		segments := strings.Split(strings.Trim(u.Path, "/"), "/")
		if len(segments) == 0 {
			return "", false
		}
		trimmed = segments[0]
	}
	handle := strings.TrimPrefix(trimmed, "@")
	if len(handle) == 0 || len(handle) > 15 {
		return "", false
	}
	for _, r := range handle {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_') {
			return "", false
		}
	}
	return handle, true
}

// fxStatusID 从帖子 ID 或含 /status/{id} 的链接中取数字 ID。
func fxStatusID(target string) (string, bool) {
	trimmed := strings.TrimSpace(target)
	if id, ok := digitsOnly(trimmed); ok {
		return id, true
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return "", false
	}
	segments := strings.Split(strings.Trim(u.Path, "/"), "/")
	for i := 0; i+1 < len(segments); i++ {
		if segments[i] == "status" || segments[i] == "statuses" {
			return digitsOnly(segments[i+1])
		}
	}
	return "", false
}
