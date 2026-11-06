package research

import (
	"net/url"
	"strings"
)

// 各 provider 的默认基地址（测试经 Options.Endpoints 覆盖为 httptest 地址）。
const (
	fxTwitterBase = "https://api.fxtwitter.com"
	pixivBase     = "https://www.pixiv.net"
	yandereBase   = "https://yande.re"
	e621Base      = "https://e621.net"
	waybackBase   = "https://web.archive.org"
	steamBase     = "https://api.steampowered.com"
	bingBase      = "https://www.bing.com"
)

// providerOrder 是 v1 固定矩阵顺序（context 与注册表共用，客户端可直接渲染）。
var providerOrder = []Name{NameFxTwitter, NamePixiv, NameYandere, NameE621, NameWayback, NameSteam, NameBing}

// providerBaseURLs 是默认基地址表。
var providerBaseURLs = map[Name]string{
	NameFxTwitter: fxTwitterBase,
	NamePixiv:     pixivBase,
	NameYandere:   yandereBase,
	NameE621:      e621Base,
	NameWayback:   waybackBase,
	NameSteam:     steamBase,
	NameBing:      bingBase,
}

// registry 是 provider 注册表（固定矩阵，无运行期注册）。
type registry struct {
	providers map[Name]Provider
	order     []Name
}

// newRegistry 用同一个安全抓取器构造全部 provider；endpoints 非空时覆盖
// 基地址（测试注入 httptest 假上游；生产留空）。
func newRegistry(client Fetcher, endpoints map[Name]string) *registry {
	base := func(name Name) string {
		if override, ok := endpoints[name]; ok && strings.TrimSpace(override) != "" {
			return strings.TrimRight(strings.TrimSpace(override), "/")
		}
		return providerBaseURLs[name]
	}
	r := &registry{providers: make(map[Name]Provider, len(providerOrder)), order: append([]Name(nil), providerOrder...)}
	r.providers[NameFxTwitter] = &fxTwitterProvider{client: client, base: base(NameFxTwitter)}
	r.providers[NamePixiv] = &pixivProvider{client: client, base: base(NamePixiv)}
	r.providers[NameYandere] = &yandereProvider{client: client, base: base(NameYandere)}
	r.providers[NameE621] = &e621Provider{client: client, base: base(NameE621)}
	r.providers[NameWayback] = &waybackProvider{client: client, base: base(NameWayback)}
	r.providers[NameSteam] = &steamProvider{client: client, base: base(NameSteam)}
	r.providers[NameBing] = &bingProvider{client: client, base: base(NameBing)}
	return r
}

// lookup 按名取 provider。
func (r *registry) lookup(name Name) (Provider, bool) {
	p, ok := r.providers[name]
	return p, ok
}

// allowedHosts 汇总全部基地址的主机名（安全客户端白名单唯一来源）。
func allowedHosts(endpoints map[Name]string) []string {
	seen := make(map[string]struct{}, len(providerOrder))
	hosts := make([]string, 0, len(providerOrder))
	for _, name := range providerOrder {
		base := providerBaseURLs[name]
		if override, ok := endpoints[name]; ok && strings.TrimSpace(override) != "" {
			base = strings.TrimSpace(override)
		}
		u, err := url.Parse(base)
		if err != nil || u.Hostname() == "" {
			continue
		}
		host := strings.ToLower(u.Hostname())
		if _, ok := seen[host]; ok {
			continue
		}
		seen[host] = struct{}{}
		hosts = append(hosts, host)
	}
	return hosts
}

// supports 报告 provider 是否支持该 operation。
func supports(p Provider, op Operation) bool {
	for _, candidate := range p.Operations() {
		if candidate == op {
			return true
		}
	}
	return false
}

// itemsResult 把 items 收敛为终态：有内容 = ok，空 = not_found。
func itemsResult(items []Item) ProviderResult {
	if len(items) == 0 {
		return ProviderResult{Status: StatusNotFound, Err: &ResultError{Code: "NOT_FOUND", Message: "没有匹配结果"}}
	}
	if len(items) > MaxItems {
		items = items[:MaxItems]
	}
	return ProviderResult{Status: StatusOk, Items: items}
}

// trimLimit 让 provider 只取需要的条数（itemLimit 已 <= MaxItems）。
func trimLimit(q Query) int { return q.itemLimit() }
