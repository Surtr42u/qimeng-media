package mediawrite

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/uploadsess"
)

// UploadReceiptTTL 与 OpenAPI 上传重放契约双向同步。过期由清扫任务删除。
const UploadReceiptTTL = 7 * 24 * time.Hour

// MaxSourceMtime 是 9999-12-31 的毫秒上界，与 OpenAPI sourceMtime 同步。
const MaxSourceMtime int64 = 253402300799999

// uploadGateCount 限制进程内锁数量；偶发哈希碰撞只使两项上传依次执行。
const uploadGateCount = 64

var (
	ErrUploadMetadata    = errors.New("上传任务 ID 或源修改时间不合法")
	ErrUploadConflict    = errors.New("上传任务 ID 已用于不同参数")
	ErrUploadedAssetGone = errors.New("该上传已经完成，但资产已被删除")
)

func validateUploadMetadata(id string, mtime *int64) error {
	if id != "" {
		parsed, err := uuid.Parse(id)
		if err != nil || parsed.String() != id {
			return ErrUploadMetadata
		}
	}
	if mtime != nil && (*mtime < 0 || *mtime > MaxSourceMtime) {
		return ErrUploadMetadata
	}
	return nil
}

// 参数摘要只约束同一任务，不把文件名相同当作内容相同。源字节不额外整读。
func uploadRequestHash(mode, library, name, dir string, size int64, mtime *int64) string {
	modified := "unknown"
	if mtime != nil {
		modified = strconv.FormatInt(*mtime, 10)
	}
	// %q 转义字段内换行和分隔符，避免不同参数拼出同一摘要输入。
	encoded := fmt.Sprintf("%q\n%q\n%q\n%q\n%d\n%s", mode, library, name, dir, size, modified)
	return fmt.Sprintf("%x", sha256.Sum256([]byte(encoded)))
}

// 固定条带锁不随历史任务数量增长；相同 ID 的直传/创建/完成共用同一把锁。
func (s *Service) lockUpload(id string) func() {
	key := sha256.Sum256([]byte(id))
	gate := &s.uploadGates[int(key[0])%len(s.uploadGates)]
	gate.Lock()
	return gate.Unlock
}

func (s *Service) uploadReceipt(ctx context.Context, id string) (db.UploadReceipt, error) {
	r, err := s.q.GetUploadReceipt(ctx, id)
	if err == nil && r.CompletedAt < s.now().Add(-UploadReceiptTTL).UnixMilli() {
		// ID 不再可重放；先按同一时钟删除旧凭据，避免唯一键阻挡合法的新任务。
		if err := s.PruneUploadReceipts(ctx); err != nil {
			return db.UploadReceipt{}, err
		}
		return db.UploadReceipt{}, sql.ErrNoRows
	}
	return r, err
}

func (s *Service) replayUpload(ctx context.Context, id, hash string) (db.Asset, bool, error) {
	r, err := s.uploadReceipt(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return db.Asset{}, false, nil
	}
	if err != nil {
		return db.Asset{}, true, err
	}
	if hash != "" && r.RequestHash != hash {
		return db.Asset{}, true, ErrUploadConflict
	}
	asset, err := s.q.GetAsset(ctx, r.AssetID)
	if errors.Is(err, sql.ErrNoRows) {
		return db.Asset{}, true, ErrUploadedAssetGone
	}
	return asset, true, err
}

// UploadSnapshot 同时识别在途会话和已提交凭据，已完成后由 complete 重放结果。
func (s *Service) UploadSnapshot(ctx context.Context, id string) (uploadsess.Snapshot, error) {
	r, err := s.uploadReceipt(ctx, id)
	if err == nil {
		return uploadsess.Snapshot{ID: id, Offset: r.SizeBytes, Size: r.SizeBytes}, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return uploadsess.Snapshot{}, err
	}
	return s.uploads.Snapshot(id)
}

func (s *Service) PruneUploadReceipts(ctx context.Context) error {
	return s.q.DeleteExpiredUploadReceipts(ctx, s.now().Add(-UploadReceiptTTL).UnixMilli())
}
