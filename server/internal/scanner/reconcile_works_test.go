package scanner

// reconcile_works_test.go：扫描对账（移动合并/删除）与作者 TXT 作品行的
// 同事务硬约束回归（ADR-0024 片段本体唯一真相）。用现有 testEnv 走真实
// Scan，不复制扫描逻辑。

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/authorattach"
)

func seedAuthorTXTForAsset(t *testing.T, e *testEnv, assetID, finalName string) {
	t.Helper()
	if _, err := (&authorattach.Service{}).Apply(context.Background(), e.q, time.Now(), authorattach.AttachRequest{
		AssetID: assetID, FinalName: finalName, AuthorName: "aaa",
	}); err != nil {
		t.Fatalf("预置作者 TXT 失败: %v", err)
	}
}

func TestScanMoveMergeReplacesAuthorTXTWorkLine(t *testing.T) {
	e := newTestEnv(t, nil)
	ctx := context.Background()
	e.writeFile(t, "old.jpg", 10)
	if _, err := e.s.Scan(ctx, e.lib); err != nil {
		t.Fatalf("首次扫描失败: %v", err)
	}
	old := e.assetByPath(t, "old.jpg")
	seedAuthorTXTForAsset(t, e, old.AssetID, "old.jpg")
	refsBefore, err := e.q.ListAssetAuthorRefs(ctx, old.AssetID)
	if err != nil || len(refsBefore) != 1 {
		t.Fatalf("预置关联失败: %+v %v", refsBefore, err)
	}
	if err := os.Rename(e.abs("old.jpg"), e.abs("new.jpg")); err != nil {
		t.Fatalf("改名文件失败: %v", err)
	}

	res, err := e.s.Scan(ctx, e.lib)
	if err != nil {
		t.Fatalf("对账扫描失败: %v", err)
	}
	if res.Moved != 1 || res.Removed != 0 {
		t.Fatalf("应走移动合并: %+v", res)
	}
	current, err := e.q.GetAsset(ctx, old.AssetID)
	if err != nil || current.RelPath != "new.jpg" {
		t.Fatalf("身份未迁到新路径: %+v %v", current, err)
	}
	sources, err := authorattach.LoadSources(ctx, e.q)
	if err != nil || len(sources) != 1 {
		t.Fatalf("读取片段失败: %+v %v", sources, err)
	}
	if strings.Contains(sources[0].Content, "old.jpg") {
		t.Fatalf("移动合并后旧作品行残留: %q", sources[0].Content)
	}
	if !strings.Contains(sources[0].Content, "new.jpg") {
		t.Fatalf("移动合并后缺少新作品行: %q", sources[0].Content)
	}
	refsAfter, err := e.q.ListAssetAuthorRefs(ctx, old.AssetID)
	if err != nil || len(refsAfter) != 1 || refsAfter[0].ID != refsBefore[0].ID || refsAfter[0].Origin != refsBefore[0].Origin {
		t.Fatalf("移动合并破坏了作者关联: before=%+v after=%+v err=%v", refsBefore, refsAfter, err)
	}
}

func TestScanDeleteDropsAuthorTXTWorkLine(t *testing.T) {
	e := newTestEnv(t, nil)
	ctx := context.Background()
	e.writeFile(t, "gone.jpg", 10)
	if _, err := e.s.Scan(ctx, e.lib); err != nil {
		t.Fatalf("首次扫描失败: %v", err)
	}
	gone := e.assetByPath(t, "gone.jpg")
	seedAuthorTXTForAsset(t, e, gone.AssetID, "gone.jpg")
	if err := os.Remove(e.abs("gone.jpg")); err != nil {
		t.Fatalf("删除文件失败: %v", err)
	}

	res, err := e.s.Scan(ctx, e.lib)
	if err != nil {
		t.Fatalf("对账扫描失败: %v", err)
	}
	if res.Removed != 1 || res.Moved != 0 {
		t.Fatalf("应走删除分支: %+v", res)
	}
	if _, err := e.q.GetAsset(ctx, gone.AssetID); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("资产行未删除: %v", err)
	}
	sources, err := authorattach.LoadSources(ctx, e.q)
	if err != nil || len(sources) != 1 {
		t.Fatalf("读取片段失败: %+v %v", sources, err)
	}
	if strings.Contains(sources[0].Content, "gone.jpg") {
		t.Fatalf("删除后作品行残留: %q", sources[0].Content)
	}
	if !strings.Contains(sources[0].Content, "1  aaa") {
		t.Fatalf("删除作品行不得移除作者块: %q", sources[0].Content)
	}
	entries, err := authorattach.LoadUploadEntries(ctx, e.q)
	if err != nil {
		t.Fatalf("读取上传条目失败: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("删除后上传条目未修剪: %+v", entries)
	}
}
