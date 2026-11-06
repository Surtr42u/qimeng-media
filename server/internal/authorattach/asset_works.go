package authorattach

import (
	"context"
	"errors"
	"time"

	"qimeng-media/server/internal/store/db"
)

// RecordAssetWorks makes existing regular links reproducible from TXT without
// rewriting author rows, association timestamps, or provenance.
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
		if _, err := s.Apply(ctx, qtx, now, AttachRequest{AssetID: assetID, FinalName: asset.FileName, AuthorID: author.ID, recordWorksOnly: true}); err != nil {
			// Portable v2 deliberately retains legacy opaque IDs. TXT has no ID
			// column, so an unrepresentable identity must retain its real link.
			if errors.Is(err, ErrDisplayNameIdentityMismatch) {
				s.logger().Warn("历史作者身份无法往返TXT，保留既有关联并由v2备份传输", "err", err)
				continue
			}
			return err
		}
	}
	return nil
}
