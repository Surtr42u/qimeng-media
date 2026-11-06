package research

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// steamProvider 走 Steam Web API 的 GetPublishedFileDetails（POST 表单，
// 实测 2026-11-06 可直连）：operation=workshop 时按创意工坊 ID 查条目。
type steamProvider struct {
	client Fetcher
	base   string
}

// Name 实现 Provider。
func (p *steamProvider) Name() Name { return NameSteam }

// Operations 实现 Provider（v1 矩阵：workshop）。
func (p *steamProvider) Operations() []Operation { return []Operation{OperationWorkshop} }

// steamEnvelope 是 GetPublishedFileDetails 的响应结构。
type steamEnvelope struct {
	Response struct {
		Result      int               `json:"result"`
		ResultCount int               `json:"resultcount"`
		FileDetails []steamFileDetail `json:"publishedfiledetails"`
	} `json:"response"`
}

type steamFileDetail struct {
	PublishedFileID string `json:"publishedfileid"`
	Result          int    `json:"result"`
	Title           string `json:"title"`
	Description     string `json:"description"`
	Creator         string `json:"creator"`
	TimeCreated     int64  `json:"time_created"`
	TimeUpdated     int64  `json:"time_updated"`
	FileURL         string `json:"file_url"`
	PreviewURL      string `json:"preview_url"`
	ConsumerAppID   int    `json:"consumer_app_id"`
	Visibility      int    `json:"visibility"`
}

// Query 实现 Provider（只支持 workshop）。
func (p *steamProvider) Query(ctx context.Context, q Query) (ProviderResult, error) {
	if q.Operation != OperationWorkshop {
		return ProviderResult{}, ErrUnsupportedOperation
	}
	fileID, ok := digitsOnly(q.Target)
	if !ok {
		return ProviderResult{}, ErrInvalidTarget
	}
	values := url.Values{}
	values.Set("itemcount", "1")
	values.Set("publishedfileids[0]", fileID)
	req := Request{
		Method:      http.MethodPost,
		URL:         p.base + "/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
		Body:        []byte(values.Encode()),
		ContentType: "application/x-www-form-urlencoded",
		Header:      map[string]string{"Accept": "application/json"},
	}
	resp, terminal := fetchUpstream(ctx, p.client, req)
	if terminal != nil {
		return *terminal, nil
	}
	var envelope steamEnvelope
	if err := json.Unmarshal(resp.Body, &envelope); err != nil {
		return invalidResponse("Steam Web API"), nil
	}
	// result 字段是 Steam 的调用结果码：1=成功，9=不存在；其余按上游异常。
	if envelope.Response.Result != 0 && envelope.Response.Result != 1 {
		if envelope.Response.Result == 9 {
			return itemsResult(nil), nil
		}
		return ProviderResult{Status: StatusProviderUnavailable, Err: &ResultError{
			Code: "UPSTREAM_ERROR", Message: "Steam 返回 result=" + strconv.Itoa(envelope.Response.Result),
		}}, nil
	}
	items := make([]Item, 0, 1)
	for _, detail := range envelope.Response.FileDetails {
		if detail.Result == 9 {
			continue
		}
		if strings.TrimSpace(detail.PublishedFileID) == "" {
			continue
		}
		link := "https://steamcommunity.com/sharedfiles/filedetails/?id=" + detail.PublishedFileID
		extra := map[string]string{}
		if detail.Creator != "" {
			extra["creator"] = detail.Creator
		}
		if detail.ConsumerAppID != 0 {
			extra["appId"] = strconv.Itoa(detail.ConsumerAppID)
		}
		if detail.PreviewURL != "" {
			extra["previewUrl"] = detail.PreviewURL
		}
		if detail.FileURL != "" {
			extra["fileUrl"] = detail.FileURL
		}
		if desc := cleanText(detail.Description); desc != "" {
			extra["description"] = truncateRunes(desc, 500)
		}
		items = append(items, Item{
			Title:       truncateRunes(cleanText(detail.Title), 200),
			URL:         link,
			Domain:      "steamcommunity.com",
			Author:      detail.Creator,
			ID:          detail.PublishedFileID,
			PublishedAt: unixSecondsToRFC3339(detail.TimeCreated),
			Extra:       extra,
		})
	}
	return itemsResult(items), nil
}
