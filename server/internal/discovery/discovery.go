// Package discovery 提供独立于周期排行的混合发现；选择为纯函数，读库编排不依赖 HTTP。
package discovery

import (
	"context"
	"encoding/json"
	"math/rand"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

const recentWindow = 30 * 24 * time.Hour
const (
	Unseen     = "unseen"
	Rediscover = "rediscover"
	Interest   = "interest"
)

type Candidate struct {
	ID           string
	LastActivity time.Time
}
type Pick struct {
	ID     string
	Reason string
}

// Select 按未看/久别/近期三池轮流取项，池内同 seed 可复现打散；短池由其他池补齐。
// 不更改输入、不产生重复项、不依赖客户端评分或历史热度榜。
func Select(candidates []Candidate, seed int64, limit int, now time.Time) []Pick {
	pools := make([][]Pick, 3)
	cutoff := now.Add(-recentWindow)
	for _, candidate := range candidates {
		pool, reason := 2, Interest
		if candidate.LastActivity.IsZero() {
			pool, reason = 0, Unseen
		} else if candidate.LastActivity.Before(cutoff) {
			pool, reason = 1, Rediscover
		}
		pools[pool] = append(pools[pool], Pick{ID: candidate.ID, Reason: reason})
	}
	rng := rand.New(rand.NewSource(seed))
	for _, pool := range pools {
		rng.Shuffle(len(pool), func(i, j int) { pool[i], pool[j] = pool[j], pool[i] })
	}
	out := make([]Pick, 0, min(max(limit, 0), len(candidates)))
	for index := 0; len(out) < limit; index++ {
		before := len(out)
		for _, pool := range pools {
			if index < len(pool) && len(out) < limit {
				out = append(out, pool[index])
			}
		}
		if len(out) == before {
			break
		}
	}
	return out
}

type Entry struct {
	Asset  db.ListDiscoverySummariesRow
	Reason string
}

// Load 只装载候选最近活动与当前页摘要；摘要阶段重查启用库，允许并发变更造成短组。
func Load(ctx context.Context, q *db.Queries, seed int64, limit int, now time.Time) ([]Entry, error) {
	rows, err := q.ListDiscoveryCandidates(ctx)
	if err != nil {
		return nil, err
	}
	candidates := make([]Candidate, 0, len(rows))
	for _, row := range rows {
		var last time.Time
		if row.LastActivityAt != "" {
			last, err = time.Parse(store.TimestampLayout, row.LastActivityAt)
			if err != nil {
				return nil, err
			}
		}
		candidates = append(candidates, Candidate{ID: row.AssetID, LastActivity: last})
	}
	picks := Select(candidates, seed, limit, now)
	ids := make([]string, len(picks))
	reasons := make(map[string]string, len(picks))
	for i, pick := range picks {
		ids[i] = pick.ID
		reasons[pick.ID] = pick.Reason
	}
	payload, err := json.Marshal(ids)
	if err != nil {
		return nil, err
	}
	page, err := q.ListDiscoverySummaries(ctx, string(payload))
	if err != nil {
		return nil, err
	}
	out := make([]Entry, 0, len(page))
	for _, row := range page {
		out = append(out, Entry{Asset: row, Reason: reasons[row.AssetID]})
	}
	return out, nil
}
