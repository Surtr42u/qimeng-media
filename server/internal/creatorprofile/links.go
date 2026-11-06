package creatorprofile

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	"qimeng-media/server/internal/store/db"
)

// VerifiedLinks reads saved public evidence without decoding offline images.
func VerifiedLinks(ctx context.Context, q *db.Queries, id string) ([]string, error) {
	raw, err := q.GetSetting(ctx, KeyPrefix+id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var profile Write
	if err := json.Unmarshal([]byte(raw), &profile); err != nil {
		return nil, err
	}
	if profile.Status != "verified" {
		return nil, nil
	}
	links := []string{profile.HomepageURL}
	if profile.XProfile != nil {
		links = append(links, profile.XProfile.URL)
	}
	return append(links, profile.EvidenceURLs...), nil
}
