// Package creatorprofile 管理作者公开附属档案、审阅候选与有界公开来源维护；不改变TXT身份。
package creatorprofile

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

const KeyPrefix = "creator_profile:"
const RevisionKeyPrefix = "creator_profile_revision:"

var (
	ErrNotFound = errors.New("作者不存在")
	ErrConflict = errors.New("档案已更新或正在刷新，请重新读取")
	ErrInvalid  = errors.New("作者档案参数不合法")
)

// Write 对应 OpenAPI CreatorProfileWrite；revision 为每次持久化的乐观并发版本。
type Write struct {
	Revision     int64     `json:"revision"`
	Nickname     string    `json:"nickname"`
	Biography    string    `json:"biography"`
	AvatarURL    string    `json:"avatarUrl"`
	HomepageURL  string    `json:"homepageUrl"`
	EvidenceURLs []string  `json:"evidenceUrls"`
	Status       string    `json:"status"`
	AutoRefresh  bool      `json:"autoRefresh"`
	FeedURL      string    `json:"feedUrl"`
	TrackUpdates bool      `json:"trackUpdates"`
	XProfile     *XProfile `json:"xProfile,omitempty"`
}

// XProfile保留账号简介原文，独立于人工整理简介及其他平台证据。
type XProfile struct {
	URL         string  `json:"url"`
	Biography   string  `json:"biography"`
	BiographyZh *string `json:"biographyZh,omitempty"`
}
type Candidate struct {
	Nickname     string   `json:"nickname"`
	Biography    string   `json:"biography"`
	AvatarURL    string   `json:"avatarUrl"`
	HomepageURL  string   `json:"homepageUrl"`
	EvidenceURLs []string `json:"evidenceUrls"`
}
type Update struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	URL         string `json:"url"`
	PublishedAt string `json:"publishedAt"`
}
type Profile struct {
	Write
	AvatarData    []byte    `json:"-"`
	AuthorID      string    `json:"authorId"`
	LastCheckedAt string    `json:"lastCheckedAt"`
	LastSuccessAt string    `json:"lastSuccessAt"`
	LastError     string    `json:"lastError"`
	Candidate     Candidate `json:"candidate"`
	Updates       []Update  `json:"updates"`
}

// 图片保存在同一附属档案内，随身份迁移/删除；普通资料API不携带图片正文。
type storedProfile struct {
	Profile
	AvatarData []byte `json:"avatarData,omitempty"`
}

func Empty(id string) Profile {
	return Profile{AuthorID: id, Write: Write{Status: "empty", EvidenceURLs: []string{}}, Candidate: Candidate{EvidenceURLs: []string{}}, Updates: []Update{}}
}
func Load(ctx context.Context, q *db.Queries, id string) (Profile, error) {
	p := Empty(id)
	value, err := q.GetSetting(ctx, KeyPrefix+id)
	if errors.Is(err, sql.ErrNoRows) {
		p.Revision, err = revisionFloor(ctx, q, id)
		return p, err
	}
	if err != nil {
		return p, err
	}
	stored := storedProfile{Profile: p}
	if err = json.Unmarshal([]byte(value), &stored); err != nil {
		return p, fmt.Errorf("读取作者附属档案: %w", err)
	}
	p = stored.Profile
	p.AvatarData = stored.AvatarData
	normalize(&p)
	return p, nil
}
func Persist(ctx context.Context, q *db.Queries, p Profile, now time.Time) error {
	normalize(&p)
	value, err := json.Marshal(storedProfile{Profile: p, AvatarData: p.AvatarData})
	if err != nil {
		return err
	}
	if err = q.UpsertSetting(ctx, db.UpsertSettingParams{Key: KeyPrefix + p.AuthorID, Value: string(value), UpdatedAt: store.FormatTimestamp(now)}); err != nil {
		return err
	}
	return q.UpsertSetting(ctx, db.UpsertSettingParams{Key: RevisionKeyPrefix + p.AuthorID, Value: strconv.FormatInt(p.Revision, 10), UpdatedAt: store.FormatTimestamp(now)})
}
func revisionFloor(ctx context.Context, q *db.Queries, id string) (int64, error) {
	value, err := q.GetSetting(ctx, RevisionKeyPrefix+id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	floor, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("读取作者档案版本墓碑: %w", err)
	}
	if floor < 0 {
		return 0, ErrInvalid
	}
	return floor, nil
}

// IsEmpty 检查资料而非版本；删除/重建后的空档案仍保留单调版本墓碑。
func IsEmpty(p Profile) bool {
	return p.XProfile == nil && p.Status == "empty" && p.Nickname == "" && p.Biography == "" && p.AvatarURL == "" && p.HomepageURL == "" && len(p.EvidenceURLs) == 0 && !p.AutoRefresh && !p.TrackUpdates && p.FeedURL == "" && p.Candidate.Nickname == "" && p.Candidate.Biography == "" && p.Candidate.AvatarURL == "" && p.Candidate.HomepageURL == "" && len(p.Candidate.EvidenceURLs) == 0 && len(p.Updates) == 0
}
func normalize(p *Profile) {
	if p.EvidenceURLs == nil {
		p.EvidenceURLs = []string{}
	}
	if p.Candidate.EvidenceURLs == nil {
		p.Candidate.EvidenceURLs = []string{}
	}
	if p.Updates == nil {
		p.Updates = []Update{}
	}
}

// Persist/Move 均在调用方事务内执行，档案与版本墓碑必须原子提交。
func Move(ctx context.Context, q *db.Queries, oldID, newID string, now time.Time) error {
	p, err := Load(ctx, q, oldID)
	if err != nil {
		return err
	}
	floor, err := revisionFloor(ctx, q, newID)
	if err != nil {
		return err
	}
	p.AuthorID = newID
	p.Revision = max(p.Revision, floor) + 1
	if err = Persist(ctx, q, p, now); err != nil {
		return err
	}
	// 调用方随后删除旧作者；0021/0022在同一事务内清资料并推进旧ID版本。
	return nil
}
func Validate(w Write) error {
	if err := validateXProfile(w.XProfile); err != nil {
		return err
	}
	if w.Revision < 0 || len(w.EvidenceURLs) > 8 {
		return ErrInvalid
	}
	if w.Status != "empty" && w.Status != "candidate" && w.Status != "verified" {
		return ErrInvalid
	}
	for _, v := range []struct {
		s string
		n int
	}{{w.Nickname, 200}, {w.Biography, 2000}} {
		if !utf8.ValidString(v.s) || utf8.RuneCountInString(v.s) > v.n {
			return ErrInvalid
		}
		for _, r := range v.s {
			if unicode.IsControl(r) && r != '\n' && r != '\t' {
				return ErrInvalid
			}
		}
	}
	for _, u := range append([]string{w.HomepageURL, w.AvatarURL, w.FeedURL}, w.EvidenceURLs...) {
		if u != "" {
			if _, err := publicURL(u); err != nil {
				return ErrInvalid
			}
		}
	}
	if w.AutoRefresh && (w.Status != "verified" || w.HomepageURL == "") {
		return fmt.Errorf("%w: 自动维护需要已核实主页", ErrInvalid)
	}
	if w.TrackUpdates && (w.Status != "verified" || w.FeedURL == "") {
		return fmt.Errorf("%w: 动态追踪需要已核实作者及RSS/Atom地址", ErrInvalid)
	}
	if w.Status == "verified" && (w.HomepageURL == "" || len(w.EvidenceURLs) == 0) {
		return fmt.Errorf("%w: 核实需主页和证据", ErrInvalid)
	}
	for _, evidence := range w.EvidenceURLs {
		if evidence == "" {
			return ErrInvalid
		}
	}
	if strings.TrimSpace(w.Nickname) != w.Nickname {
		return ErrInvalid
	}
	return nil
}

// ValidateProfile 用于不可信备份的写前校验；读回后才有资格恢复，不能夹带任意链接。
func ValidateProfile(p Profile) error {
	if err := Validate(p.Write); err != nil {
		return err
	}
	c := p.Candidate
	if err := Validate(Write{Nickname: c.Nickname, Biography: c.Biography, AvatarURL: c.AvatarURL, HomepageURL: c.HomepageURL, EvidenceURLs: c.EvidenceURLs, Status: "candidate"}); err != nil {
		return err
	}
	if len(p.Updates) > maxUpdates {
		return ErrInvalid
	}
	for _, update := range p.Updates {
		if len(update.ID) > 2000 || len(update.Title) > 2000 || len(update.PublishedAt) > 200 {
			return ErrInvalid
		}
		if _, err := publicURL(update.URL); err != nil {
			return err
		}
	}
	if len(p.LastError) > 2000 || len(p.LastCheckedAt) > 100 || len(p.LastSuccessAt) > 100 {
		return ErrInvalid
	}
	for _, stamp := range []string{p.LastCheckedAt, p.LastSuccessAt} {
		if stamp != "" {
			if _, err := time.Parse(time.RFC3339, stamp); err != nil {
				return ErrInvalid
			}
		}
	}
	return nil
}
