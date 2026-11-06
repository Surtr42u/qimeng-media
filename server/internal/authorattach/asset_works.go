package authorattach

import (
	"context"
	"time"

	"qimeng-media/server/internal/store/db"
)

// RecordAssetWorks makes existing regular links reproducible from TXT. Apply's
// conflict-safe inserts retain link timestamps and provenance already present.
// The caller owns the transaction, including any asset filename change.
func (s *Service) RecordAssetWorks(ctx context.Context, qtx *db.Queries, now time.Time, assetID string) error {
	asset, err := qtx.GetAsset(ctx, assetID)
	if err != nil {
		return err
	}
	authors, err := qtx.ListAssetAuthorRefs(ctx, assetID)
	if err != nil {
		return err
	}
	for _, author := range authors {
		if author.Type != "regular" {
			continue
		}
		if _, err := s.Apply(ctx, qtx, now, AttachRequest{AssetID: assetID, FinalName: asset.FileName, AuthorID: author.ID}); err != nil {
			return err
		}
	}
	return nil
}
