package mediawrite

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"golang.org/x/sync/errgroup"
)

func TestDirectReceiptConcurrentReplayExpiryAndTombstone(t *testing.T) {
	svc, library, media, body := uploadFixture(t)
	ctx := context.Background()
	now := time.Now()
	svc.now = func() time.Time { return now }
	mtime := time.Date(2020, 1, 2, 3, 4, 5, 0, time.UTC).UnixMilli()
	input := DirectUpload{UploadID: uuid.NewString(), LibraryID: library, Filename: "receipt.png",
		ContentLength: int64(len(body)), SourceMtime: &mtime}
	var group errgroup.Group
	for range 4 {
		group.Go(func() error {
			request := input
			request.Body = bytes.NewReader(body)
			_, err := svc.Direct(ctx, request)
			return err
		})
	}
	if err := group.Wait(); err != nil {
		t.Fatal(err)
	}
	receipt, err := svc.q.GetUploadReceipt(ctx, input.UploadID)
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(filepath.Join(media, input.Filename))
	if err != nil || info.ModTime().UnixMilli() != mtime {
		t.Fatalf("源时间未保持: %v %v", info, err)
	}
	assertUploadRows(t, svc, 1, 1)
	// 新服务没有内存请求状态；数据库凭据仍返回原资产，不读取 nil Body。
	restarted := New(Options{Conn: svc.conn, Queries: svc.q, Bus: svc.bus, Config: svc.cfg, Now: svc.now})
	t.Cleanup(func() {
		if err := restarted.uploads.Close(); err != nil {
			t.Error(err)
		}
	})
	replayed, err := restarted.Direct(ctx, input)
	if err != nil || replayed.AssetID != receipt.AssetID {
		t.Fatalf("重启重放不一致: %+v %v", replayed, err)
	}
	changed := input
	changed.Filename = "changed.png"
	if _, err := restarted.Direct(ctx, changed); !errors.Is(err, ErrUploadConflict) {
		t.Fatalf("参数冲突未拒绝: %v", err)
	}
	if err := svc.q.DeleteAsset(ctx, receipt.AssetID); err != nil {
		t.Fatal(err)
	}
	if _, err := restarted.Direct(ctx, input); !errors.Is(err, ErrUploadedAssetGone) {
		t.Fatalf("删除墓碑未拒绝: %v", err)
	}
	now = now.Add(UploadReceiptTTL + time.Second)
	input.Body = bytes.NewReader(body)
	if _, err := restarted.Direct(ctx, input); err != nil {
		t.Fatal("过期凭据阻挡新上传", err)
	}
	assertUploadRows(t, svc, 1, 1)
}

func TestChunkedReceiptReplaysAfterRestartAndSharesAssetTransaction(t *testing.T) {
	svc, library, _, body := uploadFixture(t)
	ctx := context.Background()
	request := SessionUpload{UploadID: uuid.NewString(), LibraryID: library, Filename: "receipt.png", Size: int64(len(body))}
	snapshot, err := svc.CreateUploadSession(ctx, request)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.uploads.Append(snapshot.ID, 0, bytes.NewReader(body)); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.conn.Exec("CREATE TRIGGER synthetic_receipt_failure BEFORE INSERT ON upload_receipts BEGIN SELECT RAISE(ABORT,'synthetic failure'); END"); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.CompleteUploadSession(ctx, snapshot.ID); err == nil {
		t.Fatal("凭据写入失败必须使资产事务回滚")
	}
	assertUploadRows(t, svc, 0, 0)
	if _, err := svc.conn.Exec("DROP TRIGGER synthetic_receipt_failure"); err != nil {
		t.Fatal(err)
	}
	asset, err := svc.CompleteUploadSession(ctx, snapshot.ID)
	if err != nil {
		t.Fatal(err)
	}
	restarted := New(Options{Conn: svc.conn, Queries: svc.q, Bus: svc.bus, Config: svc.cfg})
	t.Cleanup(func() {
		if err := restarted.uploads.Close(); err != nil {
			t.Error(err)
		}
	})
	recreated, err := restarted.CreateUploadSession(ctx, request)
	if err != nil || recreated.ID != snapshot.ID || recreated.Offset != request.Size {
		t.Fatalf("创建响应丢失后未重放完成会话: %+v %v", recreated, err)
	}
	replayed, err := restarted.CompleteUploadSession(ctx, snapshot.ID)
	if err != nil || replayed.AssetID != asset.AssetID {
		t.Fatalf("完成响应丢失后未重放原资产: %+v %v", replayed, err)
	}
	assertUploadRows(t, svc, 1, 1)
}

func assertUploadRows(t *testing.T, svc *Service, assets, receipts int) {
	t.Helper()
	for table, want := range map[string]int{"assets": assets, "upload_receipts": receipts} {
		var got int
		if err := svc.conn.QueryRow(fmt.Sprintf("SELECT COUNT(*) FROM %s", table)).Scan(&got); err != nil || got != want {
			t.Fatalf("%s 行数=%d, want=%d: %v", table, got, want, err)
		}
	}
}
