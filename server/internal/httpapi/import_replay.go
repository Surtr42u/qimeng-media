// import_replay.go：旧版备份迁移的事件回放段（import.go 按文件警戒线
// 拆出的组成部分）——dailyBrowse/mediaStats/history → view_events 的
// 转换、批次幂等锚点与物化表重建。映射口径总纲见 import.go 文件头；
// 语义唯一权威是 docs/DOMAIN_RULES §10。
package httpapi

import (
	"database/sql"
	"errors"
	"strconv"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// replayEvents 事件回放（mediaStats/dailyBrowse/history → view_events），
// 同批次重复导入整体跳过；回放与批次标记同事务，中途失败重跑不翻倍。
func (imp *legacyImport) replayEvents(req *gen.LegacyBackupImport) {
	if imp.aborted {
		return
	}
	batch := "0"
	if req.ExportedAtMillis != nil {
		batch = strconv.FormatInt(*req.ExportedAtMillis, 10)
	}
	prev, err := imp.s.q.GetSetting(imp.ctx, settingKeyLegacyImportBatch)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		imp.fail("读取迁移批次标记", err)
		return
	}
	if err == nil && prev == batch {
		imp.res.EventsReplayed = ptr(0)
		// 同批次跳过回放仍重建物化表：若上次导入事件已提交而重建失败，
		// 这里是唯一自愈入口（Rebuild 幂等，成本 = 一次全量事件聚合）。
		imp.rebuildDailyStats()
		return
	}

	n := 0
	tx, err := imp.s.conn.BeginTx(imp.ctx, nil)
	if err != nil {
		imp.fail("开启回放事务", err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	qx := imp.s.q.WithTx(tx)

	n += imp.replayDailyBrowse(qx, req)
	if !imp.aborted {
		n += imp.replayStatsGap(qx, req)
	}
	if !imp.aborted {
		n += imp.replayHistory(qx, req)
	}
	if !imp.aborted {
		err := qx.UpsertSetting(imp.ctx, db.UpsertSettingParams{
			Key: settingKeyLegacyImportBatch, Value: batch,
			UpdatedAt: store.FormatTimestamp(imp.s.now()),
		})
		if err != nil {
			imp.fail("写入迁移批次标记", err)
		}
	}
	if imp.aborted {
		return
	}
	if err := tx.Commit(); err != nil {
		imp.fail("提交事件回放", err)
		return
	}
	imp.res.EventsReplayed = ptr(n)
	// 物化表同步：回放只写事件流，asset_daily_stats（趋势/统计的数据源）
	// 若不同步，导入后 /stats/trends 对全部历史返回空、与数字卡口径分叉。
	// 全量重建 = 事件流唯一真相源的自然推论（§5），迁移场景一次成本低。
	imp.rebuildDailyStats()
}

// rebuildDailyStats 物化表全量重建（失败即中止导入响应；幂等可重跑）。
func (imp *legacyImport) rebuildDailyStats() {
	if imp.aborted {
		return
	}
	if err := imp.s.RebuildAssetDailyStatsFromEvents(imp.ctx); err != nil {
		imp.fail("重建按天统计物化表", err)
	}
}

// insertEvent 回放单条事件；返回是否实际写入。kind 用协议枚举常量
// （gen.Open/Play/Dwell），与真实上报通道（engagement.go）同一来源，
// 禁止手抄字符串（代码卫生约束 1/2）。
func (imp *legacyImport) insertEvent(qx *db.Queries, assetID string, kind gen.ViewEventReportKind, at time.Time, seconds int64) bool {
	if assetID == "" {
		return false
	}
	err := qx.InsertViewEvent(imp.ctx, db.InsertViewEventParams{
		AssetID: assetID, Kind: string(kind), SessionID: legacyImportSession,
		StartedAt: store.FormatTimestamp(at),
		Seconds:   sql.NullInt64{Int64: seconds, Valid: seconds > 0},
	})
	if err != nil {
		imp.fail("回放浏览事件", err)
		return false
	}
	return true
}

// replayKind 按次数回放 open/play 事件：同基准时刻按 replayStagger 秒
// 错开（dailyBrowse 天明细与 mediaStats 差额两条路径共用的铺开方式，
// 原两处逐字重复循环抽此共享）。
func (imp *legacyImport) replayKind(qx *db.Queries, assetID string, kind gen.ViewEventReportKind, base time.Time, count int) int {
	n := 0
	for i := 0; i < count && !imp.aborted; i++ {
		if imp.insertEvent(qx, assetID, kind, base.Add(time.Duration(i)*replayStagger), 0) {
			n++
		}
	}
	return n
}

// replayDailyBrowse 天明细全量回放：viewCount 条 open、playCount 条 play
// （同日内按秒错开），浏览秒数合成为一条 dwell；返回写入条数。
func (imp *legacyImport) replayDailyBrowse(qx *db.Queries, req *gen.LegacyBackupImport) int {
	n := 0
	for _, row := range sliceOrEmpty(req.Data.DailyBrowse) {
		assetID := imp.keyToAsset[row.RecordKey]
		base := time.UnixMilli(row.DayStartMillis).Add(replayNoonOffset)
		n += imp.replayKind(qx, assetID, gen.Open, base, val(row.ViewCount))
		n += imp.replayKind(qx, assetID, gen.Play, base, val(row.PlayCount))
		if secs := val64(row.TotalBrowseSeconds); secs > 0 && !imp.aborted {
			if imp.insertEvent(qx, assetID, gen.Dwell, base, secs) {
				n++
			}
		}
		if imp.aborted {
			return n
		}
	}
	return n
}

// daySums 按 recordKey 汇总 dailyBrowse 明细（mediaStats 差额的扣减基数）。
func daySums(req *gen.LegacyBackupImport) map[string][3]int64 {
	sums := make(map[string][3]int64)
	for _, row := range sliceOrEmpty(req.Data.DailyBrowse) {
		prev := sums[row.RecordKey]
		sums[row.RecordKey] = [3]int64{
			prev[0] + int64(val(row.ViewCount)),
			prev[1] + int64(val(row.PlayCount)),
			prev[2] + val64(row.TotalBrowseSeconds),
		}
	}
	return sums
}

// replayStatsGap mediaStats 差额合成：累计值 − dailyBrowse 之和的缺口按
// 条数补齐（open/play 各补 gap 条，统计口径 = 数事件条数；秒数差额是量纲
// 不是条数，合成一条 dwell 携带全部缺口秒）。时间戳取最后打开/更新时刻
// （再退导入时刻），同文件多条按秒错开。
func (imp *legacyImport) replayStatsGap(qx *db.Queries, req *gen.LegacyBackupImport) int {
	n := 0
	sums := daySums(req)
	for _, st := range sliceOrEmpty(req.Data.MediaStats) {
		if imp.aborted {
			return n
		}
		assetID := imp.keyToAsset[st.RecordKey]
		sum := sums[st.RecordKey]
		atMs := fallbackMillis(st.LastOpenedAtMillis, st.UpdatedAtMillis, req.ExportedAtMillis)
		if atMs == 0 {
			atMs = imp.s.now().UnixMilli() // 全空时间戳兜底导入时刻，避免落 1970 铺出数百空桶
		}
		base := time.UnixMilli(atMs)
		n += imp.replayKind(qx, assetID, gen.Open, base, int(int64(val(st.ViewCount))-sum[0]))
		n += imp.replayKind(qx, assetID, gen.Play, base, int(int64(val(st.PlayCount))-sum[1]))
		if imp.aborted {
			return n
		}
		if gap := val64(st.TotalBrowseSeconds) - sum[2]; gap > 0 {
			if imp.insertEvent(qx, assetID, gen.Dwell, base, gap) {
				n++
			}
		}
	}
	return n
}

// fallbackMillis 依次取非空毫秒值，全空返回 0（调用方兜底导入时刻）。
func fallbackMillis(vals ...*int64) int64 {
	for _, v := range vals {
		if v != nil {
			return *v
		}
	}
	return 0
}

// replayHistory 补漏回放：仅当文件在 dailyBrowse 无任何行时，把 history
// 明细转为 open 事件（§10「截取导入」；有明细的文件再回放会重复计数）。
func (imp *legacyImport) replayHistory(qx *db.Queries, req *gen.LegacyBackupImport) int {
	n := 0
	hasBrowse := make(map[string]bool, len(sliceOrEmpty(req.Data.DailyBrowse)))
	for _, row := range sliceOrEmpty(req.Data.DailyBrowse) {
		hasBrowse[row.RecordKey] = true
	}
	for _, h := range sliceOrEmpty(req.Data.History) {
		if imp.aborted {
			return n
		}
		if hasBrowse[h.RecordKey] {
			continue
		}
		if imp.insertEvent(qx, imp.keyToAsset[h.RecordKey], gen.Open, time.UnixMilli(h.OpenedAtMillis), 0) {
			n++
		}
	}
	return n
}
