package research

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// evidenceDirName 是默认证据目录名（DataDir 下）。
const evidenceDirName = "research-evidence"

// evidencePermDir / evidencePermFile：证据只给服务进程可读写（可能含公开
// 但敏感的检索线索，不需要同机其他用户可读）。
const (
	evidencePermDir  = 0o700
	evidencePermFile = 0o600
)

// EvidenceStore 负责把研究证据落盘到 <dir>/<日期>/<前缀>-<时刻>-<随机>.json。
// 证据只含公开字段与哈希：剥会话参数、不存图片字节、不写 token/凭据，
// 也不入数据库（研究不是业务数据）。
type EvidenceStore struct {
	dir string
	now func() time.Time
}

// NewEvidenceStore 构造证据存储（dir 为空时禁用落盘）。
func NewEvidenceStore(dir string, now func() time.Time) *EvidenceStore {
	if now == nil {
		now = time.Now
	}
	return &EvidenceStore{dir: dir, now: now}
}

// Enabled 报告是否配置了证据目录。
func (e *EvidenceStore) Enabled() bool { return e != nil && e.dir != "" }

type evidenceError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type evidenceItem struct {
	Title       string            `json:"title,omitempty"`
	URL         string            `json:"url,omitempty"`
	Domain      string            `json:"domain,omitempty"`
	Author      string            `json:"author,omitempty"`
	ID          string            `json:"id,omitempty"`
	PublishedAt string            `json:"publishedAt,omitempty"`
	Extra       map[string]string `json:"extra,omitempty"`
}

type evidenceProvider struct {
	Kind      string         `json:"kind"`
	Provider  string         `json:"provider"`
	Operation string         `json:"operation"`
	Target    string         `json:"target"`
	Status    string         `json:"status"`
	FetchedAt string         `json:"fetchedAt"`
	LatencyMs int64          `json:"latencyMs"`
	Items     []evidenceItem `json:"items"`
	Error     *evidenceError `json:"error,omitempty"`
	SavedAt   string         `json:"savedAt"`
}

type evidenceLens struct {
	Kind        string         `json:"kind"`
	Status      string         `json:"status"`
	FrameSha256 string         `json:"frameSha256"`
	TextSha256  string         `json:"textSha256,omitempty"`
	FetchedAt   string         `json:"fetchedAt,omitempty"`
	ElapsedMs   int64          `json:"elapsedMs,omitempty"`
	TopTitles   []LensTitle    `json:"topTitles"`
	TopDomains  []string       `json:"topDomains"`
	Cites       []LensCite     `json:"cites"`
	Links       []LensLink     `json:"links"`
	ResultURL   string         `json:"resultUrl,omitempty"`
	Error       *evidenceError `json:"error,omitempty"`
	SavedAt     string         `json:"savedAt"`
}

// SaveProvider 记录一次 provider 查询的证据（URL 统一剥会话参数）。
func (e *EvidenceStore) SaveProvider(name Name, q Query, res ProviderResult) error {
	if !e.Enabled() {
		return nil
	}
	doc := evidenceProvider{
		Kind:      "provider",
		Provider:  string(name),
		Operation: string(q.Operation),
		Target:    q.Target,
		Status:    string(res.Status),
		FetchedAt: res.FetchedAt.Format(time.RFC3339),
		LatencyMs: res.LatencyMs,
		SavedAt:   e.now().Format(time.RFC3339),
	}
	for _, item := range res.Items {
		doc.Items = append(doc.Items, evidenceItem{
			Title:       item.Title,
			URL:         sanitizeURL(item.URL),
			Domain:      item.Domain,
			Author:      item.Author,
			ID:          item.ID,
			PublishedAt: item.PublishedAt,
			Extra:       item.Extra,
		})
	}
	if res.Err != nil {
		doc.Error = &evidenceError{Code: res.Err.Code, Message: res.Err.Message}
	}
	return e.save(string(name), doc)
}

// SaveLens 记录一次 Lens 反查的证据（只记哈希与启发式摘要，不存图片）。
func (e *EvidenceStore) SaveLens(res LensResult) error {
	if !e.Enabled() {
		return nil
	}
	doc := evidenceLens{
		Kind:        "lens",
		Status:      string(res.Status),
		FrameSha256: res.FrameSHA256,
		TextSha256:  res.TextSHA256,
		ElapsedMs:   res.ElapsedMs,
		TopTitles:   res.TopTitles,
		TopDomains:  res.TopDomains,
		Cites:       sanitizeCites(res.Cites),
		Links:       sanitizeLinks(res.Links),
		ResultURL:   sanitizeURL(res.ResultURL),
		SavedAt:     e.now().Format(time.RFC3339),
	}
	if res.FetchedAt != nil {
		doc.FetchedAt = res.FetchedAt.Format(time.RFC3339)
	}
	if res.Err != nil {
		doc.Error = &evidenceError{Code: res.Err.Code, Message: res.Err.Message}
	}
	return e.save("lens", doc)
}

// sanitizeCites / sanitizeLinks 把引用与外链剥掉会话参数（空 URL 丢弃）。
func sanitizeCites(cites []LensCite) []LensCite {
	out := make([]LensCite, 0, len(cites))
	for _, cite := range cites {
		clean := sanitizeURL(cite.URL)
		if clean == "" {
			continue
		}
		out = append(out, LensCite{Title: cite.Title, URL: clean, Domain: cite.Domain})
	}
	return out
}

func sanitizeLinks(links []LensLink) []LensLink {
	out := make([]LensLink, 0, len(links))
	for _, link := range links {
		clean := sanitizeURL(link.URL)
		if clean == "" {
			continue
		}
		out = append(out, LensLink{Title: link.Title, URL: clean, Domain: link.Domain})
	}
	return out
}

// save 原子写入一份证据（先写临时文件再 rename，避免半截文件）。
func (e *EvidenceStore) save(prefix string, payload any) error {
	if !e.Enabled() {
		return nil
	}
	now := e.now()
	dir := filepath.Join(e.dir, now.Format("2006-01-02"))
	if err := os.MkdirAll(dir, evidencePermDir); err != nil {
		return fmt.Errorf("research: 创建证据目录失败: %w", err)
	}
	data, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		return fmt.Errorf("research: 序列化证据失败: %w", err)
	}
	data = append(data, '\n')
	suffix, err := randomHex(4)
	if err != nil {
		return err
	}
	name := fmt.Sprintf("%s-%s-%s.json", prefix, now.Format("150405"), suffix)
	path := filepath.Join(dir, name)
	temp := path + ".tmp"
	if err := os.WriteFile(temp, data, evidencePermFile); err != nil {
		return fmt.Errorf("research: 写入证据失败: %w", err)
	}
	if err := os.Rename(temp, path); err != nil {
		_ = os.Remove(temp)
		return fmt.Errorf("research: 落盘证据失败: %w", err)
	}
	return nil
}

// randomHex 生成 n 字节随机数的十六进制串（证据文件名去重后缀）。
func randomHex(n int) (string, error) {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("research: 生成随机后缀失败: %w", err)
	}
	return hex.EncodeToString(buf), nil
}
