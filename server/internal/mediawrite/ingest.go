package mediawrite

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"

	"github.com/google/uuid"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
)

// IngestPlacedUpload 是直传（POST /assets/upload）与分片续传（complete）
// 两条上传通道共用的入库管线：文件已落到库内最终路径后调用——指标计量、
// DB 行、视频探测、富化、upload.done / library.changed 事件（后者经事件
// 订阅推动库内容修订号 bump，ADR-0026）、文件数指标一步不缺。
// 调用方保证 finalPath 处的文件已过四道校验与冲突解析（finalName 即落盘名）；
// 返回资产领域对象；签名直链和响应字段由接口层装配。
func (s *Service) IngestPlacedUpload(ctx context.Context, libraryID, finalPath, relPath, finalName, mediaType string) (db.Asset, error) {
	sysmon.Default.IncUpload(sysmon.UploadOK)
	// 元数据：size/mtime 以落盘事实为准；视频探测失败留空（scanner 同语义）。
	fi, err := os.Stat(finalPath)
	if err != nil {
		return db.Asset{}, fmt.Errorf("读取上传文件信息: %w", err)
	}
	// 上传字节计量以落盘事实为准（fi.Size()）：此处曾只计魔数头字节数
	// （≤512B），upload_bytes_total 严重少计（OBSERVABILITY「累计字节」口径）。
	sysmon.Default.AddUploadBytes(float64(fi.Size()))
	params_ := db.UpsertAssetParams{
		AssetID:   newUploadAssetID(),
		LibraryID: libraryID,
		RelPath:   relPath,
		FileName:  finalName,
		MediaType: mediaType,
		SizeBytes: fi.Size(),
		Mtime:     store.FormatTimestamp(fi.ModTime()),
		CreatedAt: store.FormatTimestamp(s.now()),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}
	if mediaType == scanner.MediaTypeVideo {
		// 探测走 Generator 出口（s.thumbs 必填，见 Deps 校验）：ffprobe 路径
		// 与缩略图管线/扫描探测同源（thumbnail.ffprobe_path，空=PATH 自动发现）。
		if probeRes, perr := s.thumbs.ProbeVideo(ctx, finalPath); perr != nil {
			s.logger.Warn("上传视频元数据探测失败，留空待重探", "path", relPath, "err", perr)
		} else if probeRes != nil {
			params_.DurationMs = sql.NullInt64{Int64: probeRes.Duration.Milliseconds(), Valid: true}
			params_.Width = sql.NullInt64{Int64: int64(probeRes.Width), Valid: true}
			params_.Height = sql.NullInt64{Int64: int64(probeRes.Height), Valid: true}
		}
	}
	// 入库（独立单写事务），细节见 persistUploadedAsset。
	var asset db.Asset
	err = filing.WithLibraryGate(libraryID, func() error {
		latest, err := os.Stat(finalPath)
		if err != nil {
			return err
		}
		if latest.Size() != fi.Size() || !latest.ModTime().Equal(fi.ModTime()) {
			return errors.New("上传落点在探测期间变化")
		}
		asset, err = s.persistUploadedAsset(ctx, params_)
		return err
	})
	if err != nil {
		return db.Asset{}, fmt.Errorf("入库上传资产: %w", err)
	}
	// 富化补齐（与 scanner ingest、trash 恢复同口径）：上方 UpsertAsset 只写
	// 基础列 + 探测元数据，富化列（normal=出处/角色，cos=作者关联/cos_work）
	// 缺失会让手动上传的资产在出处筛选、作者聚合里隐身。EnrichAsset 只写
	// source/characters（UpdateAssetSource/DeleteAssetCharacters）与 cos 作者
	// 关联/cos_work（UpdateAssetCosWork），不触碰 duration/宽高等探测列——
	// 与上面的探测元数据互补不冲突。尽力而为：失败/扫描器未装配（noScanner）
	// 都不让上传失败（文件已落盘、记录已入库），富化列缺失可由下次该文件
	// size/mtime 变化重 ingest 自愈（同 trash 恢复语义）。
	if err := s.enrichAsset(ctx, libraryID, asset.AssetID); err != nil {
		s.logger.Warn("上传落库后富化失败（待重扫自愈）", "assetId", asset.AssetID, "err", err)
	}
	// upload.done 载荷型为 events.UploadDoneEvent（协议：UploadDoneEvent schema）；
	// SSE 客户端据此做上传完成刷新。
	if err := s.bus.Publish(events.Event{Topic: events.TopicUploadDone, Payload: events.UploadDoneEvent{AssetID: asset.AssetID}}); err != nil {
		s.logger.Warn("发布上传完成事件失败", "err", err)
	}
	s.PublishLibraryChanged()
	// 上传成功改变了库内文件数：library_files 指标在此刷新（变更点推送，
	// 其余三个时机见 refreshLibraryFileMetrics 注释）。
	s.RefreshLibraryFileMetrics()
	return asset, nil
}

// persistUploadedAsset 把资产行写入独立单写事务（SQLite 单写者，单条
// UpsertAsset + Commit）：文件已落盘后入库；失败时资产行不落库（文件已
// 落盘但无 DB 行 = 既有语义不变，重传自愈，不得半更新）。
func (s *Service) persistUploadedAsset(ctx context.Context, row db.UpsertAssetParams) (db.Asset, error) {
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return db.Asset{}, err
	}
	defer func() {
		if rollbackErr := tx.Rollback(); rollbackErr != nil && !errors.Is(rollbackErr, sql.ErrTxDone) {
			s.logger.Warn("回滚上传事务失败", "err", rollbackErr)
		}
	}()
	qtx := s.q.WithTx(tx)
	asset, err := qtx.UpsertAsset(ctx, row)
	if err != nil {
		return db.Asset{}, err
	}
	return asset, tx.Commit()
}

// newUploadAssetID 与 scanner.newAssetID 同规则（UUIDv7，防御性回退 v4）。
// 两处写入方共享"时间有序主键"语义（ADR-0004）。
func newUploadAssetID() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}
