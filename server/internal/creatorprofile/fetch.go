package creatorprofile

import (
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"time"

	"golang.org/x/net/html"
)

const fetchTimeout = 15 * time.Second
const maxDocumentBytes = 1 << 20
const maxAvatarBytes = 2 << 20
const maxUpdates = 30

var blockedNetworks = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("240.0.0.0/4"), netip.MustParsePrefix("2001::/23"),
	netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("192.0.0.0/24"), netip.MustParsePrefix("192.0.2.0/24"), netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("198.51.100.0/24"), netip.MustParsePrefix("203.0.113.0/24"), netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("64:ff9b::/96"), netip.MustParsePrefix("2002::/16"),
}

func publicIP(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, p := range blockedNetworks {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}
func publicURL(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil || len(raw) > 2000 || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" || (u.Port() != "" && u.Port() != "443") {
		return nil, ErrInvalid
	}
	host := strings.ToLower(u.Hostname())
	if host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".local") || !strings.Contains(host, ".") {
		return nil, ErrInvalid
	}
	if ip, e := netip.ParseAddr(host); e == nil && !publicIP(ip) {
		return nil, ErrInvalid
	}
	return u, nil
}

// 每次连接先解析并验证全部地址，直接拨已验证IP；禁环境代理、重定向与连接复用，防DNS重绑。
func fetch(ctx context.Context, raw string, limit int64) ([]byte, string, error) {
	u, err := publicURL(raw)
	if err != nil {
		return nil, "", err
	}
	transport := &http.Transport{Proxy: nil, DisableKeepAlives: true, TLSHandshakeTimeout: fetchTimeout, ResponseHeaderTimeout: fetchTimeout,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			host, port, e := net.SplitHostPort(address)
			if e != nil {
				return nil, e
			}
			ips, e := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
			if e != nil {
				return nil, e
			}
			if len(ips) == 0 {
				return nil, ErrInvalid
			}
			for _, ip := range ips {
				if !publicIP(ip) {
					return nil, ErrInvalid
				}
			}
			dialer := net.Dialer{Timeout: fetchTimeout}
			return dialer.DialContext(ctx, network, net.JoinHostPort(ips[0].String(), port))
		}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: fetchTimeout, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("不跟随公开来源重定向") }}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("User-Agent", "QimengMedia-PublicProfile/1.0")
	resp, err := client.Do(req)
	if err != nil {
		return nil, "", err
	}
	data, readErr := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	closeErr := resp.Body.Close()
	if readErr != nil || closeErr != nil {
		return nil, "", errors.Join(readErr, closeErr)
	}
	if resp.StatusCode != http.StatusOK || int64(len(data)) > limit {
		return nil, "", errors.New("公开来源响应失败或超限")
	}
	return data, resp.Header.Get("Content-Type"), nil
}
func readMetadata(ctx context.Context, raw string) (Candidate, error) {
	body, contentType, err := fetch(ctx, raw, maxDocumentBytes)
	if err != nil {
		return Candidate{}, err
	}
	if !strings.Contains(strings.ToLower(contentType), "html") {
		return Candidate{}, errors.New("主页不是HTML")
	}
	return parseMetadata(body, raw)
}
func parseMetadata(body []byte, raw string) (Candidate, error) {
	c := Candidate{HomepageURL: raw, EvidenceURLs: []string{raw}}
	// 官方HTML5 tokenizer支持未加引号属性和script原始文本，不执行页面代码。
	decoder := html.NewTokenizer(bytes.NewReader(body))
	decoder.SetMaxBuf(maxDocumentBytes)
	for {
		kind := decoder.Next()
		if kind == html.ErrorToken {
			if decoder.Err() != io.EOF {
				return c, decoder.Err()
			}
			break
		}
		if kind != html.StartTagToken && kind != html.SelfClosingTagToken {
			continue
		}
		start := decoder.Token()
		if start.Data == "body" {
			break
		}
		if start.Data != "meta" {
			continue
		}
		var key, value string
		for _, a := range start.Attr {
			switch a.Key {
			case "property", "name":
				key = strings.ToLower(a.Val)
			case "content":
				value = strings.TrimSpace(a.Val)
			}
		}
		switch key {
		case "og:title":
			c.Nickname = value
		case "og:description", "description":
			if c.Biography == "" {
				c.Biography = value
			}
		}
	}
	if c.Nickname == "" && c.Biography == "" {
		return c, errors.New("主页没有可用公开元数据")
	}
	if len([]rune(c.Nickname)) > 200 {
		c.Nickname = string([]rune(c.Nickname)[:200])
	}
	if len([]rune(c.Biography)) > 2000 {
		c.Biography = string([]rune(c.Biography)[:2000])
	}
	return c, nil
}

type feedEntry struct {
	ID    string `xml:"id"`
	GUID  string `xml:"guid"`
	Title string `xml:"title"`
	Links []struct {
		Href string `xml:"href,attr"`
		Rel  string `xml:"rel,attr"`
		Text string `xml:",chardata"`
	} `xml:"link"`
	Published string `xml:"published"`
	Updated   string `xml:"updated"`
	PubDate   string `xml:"pubDate"`
}

func readFeed(ctx context.Context, raw string) ([]Update, error) {
	data, _, err := fetch(ctx, raw, maxDocumentBytes)
	if err != nil {
		return nil, err
	}
	return parseFeed(data)
}
func parseFeed(data []byte) ([]Update, error) {
	var feed struct {
		XMLName xml.Name
		Entries []feedEntry `xml:"entry"`
		Channel struct {
			Items []feedEntry `xml:"item"`
		} `xml:"channel"`
	}
	if err := xml.Unmarshal(data, &feed); err != nil {
		return nil, err
	}
	var entries []feedEntry
	switch feed.XMLName.Local {
	case "rss":
		entries = feed.Channel.Items
	case "feed":
		entries = feed.Entries
	default:
		return nil, errors.New("不支持的订阅格式")
	}
	updates := make([]Update, 0, maxUpdates)
	seen := map[string]bool{}
	for _, entry := range entries {
		if len(entry.Title) > 2000 || len(entry.ID) > 2000 || len(entry.GUID) > 2000 || len(entry.Published) > 200 || len(entry.PubDate) > 200 || len(entry.Updated) > 200 {
			continue
		}
		link := ""
		for _, l := range entry.Links {
			if l.Rel == "" || l.Rel == "alternate" {
				link = l.Href
				if link == "" {
					link = strings.TrimSpace(l.Text)
				}
				break
			}
		}
		if _, e := publicURL(link); e != nil {
			continue
		}
		id := entry.ID
		if id == "" {
			id = entry.GUID
		}
		if id == "" {
			id = link
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		published := entry.Published
		if published == "" {
			published = entry.PubDate
		}
		if published == "" {
			published = entry.Updated
		}
		updates = append(updates, Update{ID: id, Title: entry.Title, URL: link, PublishedAt: published})
		if len(updates) == maxUpdates {
			break
		}
	}
	return updates, nil
}
func (s *Service) Avatar(ctx context.Context, id string) ([]byte, string, error) {
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	case <-ctx.Done():
		return nil, "", ctx.Err()
	}
	p, err := s.Get(ctx, id)
	if err != nil {
		return nil, "", err
	}
	if p.AvatarURL == "" {
		return nil, "", ErrNotFound
	}
	data, _, err := fetch(ctx, p.AvatarURL, maxAvatarBytes)
	if err != nil {
		return nil, "", err
	}
	kind := http.DetectContentType(data)
	switch kind {
	case "image/png", "image/jpeg", "image/gif", "image/webp":
		return data, kind, nil
	default:
		return nil, "", fmt.Errorf("不支持的头像类型")
	}
}
