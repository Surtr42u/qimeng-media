package eventstats

import (
	"context"
	"database/sql"
	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/store/db"
	"time"
)

// Rebuild 使用 store.Open 的 immediate 事务；真相读取与替换不会跨写入快照。
func Rebuild(ctx context.Context, conn *sql.DB) error {
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if err := RebuildInTx(ctx, db.New(tx), ""); err != nil {
		return err
	}
	return tx.Commit()
}

// RebuildInTx 重建指定资产；空 ID 为全量。q 必须绑定持写锁事务。
func RebuildInTx(ctx context.Context, q *db.Queries, assetID string) error {
	var input []stats.MaterializedEvent
	live := map[string]bool{}
	if assetID != "" {
		live[assetID] = true
		rows, err := q.ListAssetViewEvents(ctx, assetID)
		if err != nil {
			return err
		}
		for _, r := range rows {
			input = append(input, stats.MaterializedEvent{AssetID: r.AssetID, Kind: r.Kind, StartedAt: r.StartedAt, Seconds: r.Seconds.Int64})
		}
		if err := q.DeleteAssetDailyStats(ctx, assetID); err != nil {
			return err
		}
	} else {
		rows, err := q.ListAllViewEvents(ctx)
		if err != nil {
			return err
		}
		ids, err := q.ListAllAssetIDs(ctx)
		if err != nil {
			return err
		}
		for _, id := range ids {
			live[id] = true
		}
		for _, r := range rows {
			input = append(input, stats.MaterializedEvent{AssetID: r.AssetID, Kind: r.Kind, StartedAt: r.StartedAt, Seconds: r.Seconds.Int64})
		}
		if err := q.DeleteAllAssetDailyStats(ctx); err != nil {
			return err
		}
	}
	for _, r := range stats.MaterializeDaily(input, live, time.Local) {
		if err := q.UpsertAssetDailyStats(ctx, db.UpsertAssetDailyStatsParams{AssetID: r.AssetID, Day: r.Day, ViewCount: r.Views, PlayCount: r.Plays, BrowseSeconds: r.Seconds}); err != nil {
			return err
		}
	}
	return nil
}
