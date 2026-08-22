package filing

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var delAt = time.Date(2026, 8, 22, 12, 0, 0, 0, time.UTC)

func stamp(t time.Time) string { return fmt.Sprintf("%019d", t.UnixNano()) }

func TestTrashPathForLayout(t *testing.T) {
	trash, meta, err := TrashPathFor("/data", "作者/作品/a.jpg", delAt, "asset-1")
	if err != nil {
		t.Fatalf("TrashPathFor 报错: %v", err)
	}
	wantTrash := filepath.Join("/data", TrashRootName, stamp(delAt), "asset-1", "作者", "作品", "a.jpg")
	if trash != wantTrash {
		t.Fatalf("trash = %q, want %q", trash, wantTrash)
	}
	if meta != trash+TrashMetaSuffix {
		t.Fatalf("meta = %q, want %q", meta, trash+TrashMetaSuffix)
	}
}

func TestTrashPathForStampPadding(t *testing.T) {
	early := time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC) // UnixNano 为 18 位
	trash, _, err := TrashPathFor("/data", "a.jpg", early, "x")
	if err != nil {
		t.Fatalf("报错: %v", err)
	}
	want := "/" + stamp(early) + "/" // 应零填充为 0946684800000000000
	if !strings.Contains(filepath.ToSlash(trash), want) {
		t.Fatalf("时间戳子目录应零填充到 19 位定宽（%q）, got %q", stamp(early), trash)
	}
}

func TestTrashPathForDeepStructure(t *testing.T) {
	rel := "a/b/c/d/e/f.jpg"
	trash, _, err := TrashPathFor("/data", rel, delAt, "a1")
	if err != nil {
		t.Fatalf("报错: %v", err)
	}
	if !strings.Contains(filepath.ToSlash(trash), "/"+rel) {
		t.Fatalf("深层级应保留原相对路径结构, got %q", trash)
	}
}

func TestTrashPathForSameNameConflict(t *testing.T) {
	// 同一资产删两次：时间戳不同 → 落点不同（同名冲突由时间戳子目录解决）
	t1 := delAt
	t2 := delAt.Add(time.Second)
	p1, _, err := TrashPathFor("/data", "a.jpg", t1, "a1")
	if err != nil {
		t.Fatal(err)
	}
	p2, _, err := TrashPathFor("/data", "a.jpg", t2, "a1")
	if err != nil {
		t.Fatal(err)
	}
	if p1 == p2 {
		t.Fatalf("不同删除时间的落点不应相同: %q", p1)
	}
	// 同一时刻不同资产：assetID 层隔离
	p3, _, _ := TrashPathFor("/data", "a.jpg", t1, "a2")
	if p1 == p3 {
		t.Fatalf("不同资产的落点不应相同: %q", p1)
	}
	// 同参数确定性：可重复计算
	p1Again, _, _ := TrashPathFor("/data", "a.jpg", t1, "a1")
	if p1 != p1Again {
		t.Fatalf("同参数应得到相同落点")
	}
}

func TestTrashPathForRejectsBadInput(t *testing.T) {
	if _, _, err := TrashPathFor("/data", "../escape.jpg", delAt, "a1"); !errors.Is(err, ErrPathEscape) {
		t.Fatalf("逃逸路径应被拒, got %v", err)
	}
	if _, _, err := TrashPathFor("/data", "/abs/path.jpg", delAt, "a1"); !errors.Is(err, ErrAbsolutePath) {
		t.Fatalf("绝对路径应被拒, got %v", err)
	}
	if _, _, err := TrashPathFor("/data", "CON.jpg", delAt, "a1"); !errors.Is(err, ErrReservedName) {
		t.Fatalf("保留名应被拒, got %v", err)
	}
	if _, _, err := TrashPathFor("/data", "a.jpg", delAt, ""); err == nil {
		t.Fatal("空 assetID 应被拒")
	}
}

func TestTrashRestoreRoundTrip(t *testing.T) {
	rel := "作者/作品/深层/🎬视频.mp4"
	meta := TrashMeta{AssetID: "asset-9", OriginalPath: rel, DeletedAt: delAt}
	got, err := RestorePaths(meta)
	if err != nil {
		t.Fatalf("RestorePaths 报错: %v", err)
	}
	if got != rel {
		t.Fatalf("往返结果 = %q, want %q", got, rel)
	}
	// meta 被篡改/损坏后反算必须报错而不是给出危险路径
	bad := TrashMeta{OriginalPath: "../../etc/passwd"}
	if _, err := RestorePaths(bad); !errors.Is(err, ErrPathEscape) {
		t.Fatalf("损坏 meta 应被拒, got %v", err)
	}
}

func TestTrashMetaJSONRoundTrip(t *testing.T) {
	meta := TrashMeta{AssetID: "a1", OriginalPath: "x/y.jpg", DeletedAt: delAt}
	b, err := json.Marshal(meta)
	if err != nil {
		t.Fatal(err)
	}
	var back TrashMeta
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	if back != meta { // time.Time 精度：RFC3339Nano 序列化往返保持等值
		t.Fatalf("JSON 往返不等: %+v vs %+v", back, meta)
	}
}

func TestTrashExpired(t *testing.T) {
	if DefaultTrashRetentionDays != 30 {
		t.Fatalf("默认保留天数应为 30（DOMAIN_RULES §9）, got %d", DefaultTrashRetentionDays)
	}
	meta := TrashMeta{DeletedAt: delAt}
	tests := []struct {
		name          string
		now           time.Time
		retentionDays int
		want          bool
	}{
		{"第 30 天整点到期", delAt.AddDate(0, 0, 30), 30, true},
		{"差 1 纳秒未到期", delAt.AddDate(0, 0, 30).Add(-time.Nanosecond), 30, false},
		{"第 29 天未到期", delAt.AddDate(0, 0, 29), 30, false},
		{"配置 0 天 fail-safe 永不过期", delAt.AddDate(1, 0, 0), 0, false},
		{"负配置 fail-safe", delAt.AddDate(1, 0, 0), -5, false},
		{"自定义 7 天", delAt.AddDate(0, 0, 7), 7, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := TrashExpired(meta, tt.now, tt.retentionDays); got != tt.want {
				t.Fatalf("TrashExpired = %v, want %v", got, tt.want)
			}
		})
	}
}
