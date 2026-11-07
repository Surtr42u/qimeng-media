package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/store/db"
)

// 2026-11-07 用户反馈：Android 搜索「朱诺 11」出现大量不相关内容。
// 根因是 q 谓词用 instr 纯子串匹配，数字 token 会命中 110/2011/1080 等。
// 本文件的合成语料锁定修复后的编号语义：
//   - 数字 token 按「独立数字段」匹配（前后均非 ASCII 数字）；
//   - 裸数字不命中紧跟在 ( （ [ 【 之后的组图页码；
//   - 括号页码 token（(2)/（2）/【2】 等）按组图页码命中；
//   - 非数字 token 保持旧子串语义（含 ASCII 大小写折叠、多词 AND）。
//
// 语料只用于隔离临时库，不读写真实库/媒体。
var searchSemanticsFiles = []struct {
	name      string
	mediaType string
}{
	{"朱诺 1.png", "image"},
	{"朱诺 10.png", "image"},
	{"朱诺 11.png", "image"},
	{"朱诺 12.png", "image"},
	{"朱诺 110.png", "image"},
	{"朱诺 11 (1).png", "image"},
	{"朱诺 11 (2).png", "image"},
	{"朱诺 11 (4).png", "image"},
	{"朱诺 11（2）.png", "image"},
	{"朱诺 2011.png", "image"},
	{"朱诺 1080p.png", "image"},
	{"朱诺 11.mp4", "video"},
	{"朱诺11.png", "image"},
}

func seedSearchSemantics(t *testing.T) (*sql.DB, *db.Queries, string) {
	t.Helper()
	conn, err := Open(t.TempDir() + "/search-semantics.db")
	if err != nil {
		t.Fatalf("打开隔离测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	q := db.New(conn)
	ctx := context.Background()
	lib, err := q.CreateLibrary(ctx, db.CreateLibraryParams{
		ID: "sem-lib", Name: "合成库", RootPath: "/synthetic-search", Kind: "normal",
		CreatedAt: FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	now := FormatTimestamp(time.Now())
	for i, f := range searchSemanticsFiles {
		if _, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
			AssetID: fmt.Sprintf("sem-%02d", i+1), LibraryID: lib.ID,
			RelPath: f.name, FileName: f.name, MediaType: f.mediaType,
			SizeBytes: int64(1024 + i), Mtime: now, CreatedAt: now, UpdatedAt: now,
		}); err != nil {
			t.Fatalf("插入合成资产 %q 失败: %v", f.name, err)
		}
	}
	return conn, q, lib.ID
}

// qTokens 复刻 handler 侧行为：search.ParseQuery 空格切词后编成 JSON 数组。
func qTokens(query string) string {
	tokens := strings.Fields(query)
	if len(tokens) == 0 {
		return "null"
	}
	raw, err := json.Marshal(tokens)
	if err != nil {
		panic(err)
	}
	return string(raw)
}

func listSearchNames(t *testing.T, q *db.Queries, libID, query string) []string {
	t.Helper()
	rows, err := q.ListAssetsFilteredDesc(context.Background(), db.ListAssetsFilteredDescParams{
		Sort: "name", LibraryID: libID, SourceIsOther: 0, IncludeCos: 0, CosOnly: 0,
		QJson: qTokens(query), RowLimit: 100,
	})
	if err != nil {
		t.Fatalf("列表搜索 q=%q 失败: %v", query, err)
	}
	names := make([]string, 0, len(rows))
	for _, row := range rows {
		names = append(names, row.FileName)
	}
	sort.Strings(names)
	return names
}

func sortedCopy(in []string) []string {
	out := append([]string(nil), in...)
	sort.Strings(out)
	return out
}

func TestSearchNumberTokenSemantics(t *testing.T) {
	_, q, libID := seedSearchSemantics(t)
	cases := []struct {
		query string
		want  []string
	}{
		// 独立数字段：1 不命中 10/11/12/110/2011/(1)。
		{"1", []string{"朱诺 1.png"}},
		{"10", []string{"朱诺 10.png"}},
		{"12", []string{"朱诺 12.png"}},
		{"110", []string{"朱诺 110.png"}},
		{"2011", []string{"朱诺 2011.png"}},
		// 11 命中所有「11」独立段，包括组图条目与混合媒体；不命中 110/12/1。
		{"11", []string{
			"朱诺 11 (1).png", "朱诺 11 (2).png", "朱诺 11 (4).png",
			"朱诺 11.mp4", "朱诺 11.png", "朱诺 11（2）.png", "朱诺11.png",
		}},
		// 裸数字不命中组图页码：正文里唯一的独立 2 只在 (2) 页码中。
		{"2", nil},
		// 括号页码 token 定位组图页（ASCII/全角括号互认）。
		{"(2)", []string{"朱诺 11 (2).png", "朱诺 11（2）.png"}},
		{"（2）", []string{"朱诺 11 (2).png", "朱诺 11（2）.png"}},
		{"(4)", []string{"朱诺 11 (4).png"}},
		{"【2】", []string{"朱诺 11 (2).png", "朱诺 11（2）.png"}},
		// 多词 AND：作者 + 编号；组图页组合。
		{"朱诺 11", []string{
			"朱诺 11 (1).png", "朱诺 11 (2).png", "朱诺 11 (4).png",
			"朱诺 11.mp4", "朱诺 11.png", "朱诺 11（2）.png", "朱诺11.png",
		}},
		{"朱诺 1", []string{"朱诺 1.png"}},
		{"朱诺 2", nil},
		{"11 (2)", []string{"朱诺 11 (2).png", "朱诺 11（2）.png"}},
		// 非数字 token 保持旧子串语义。
		{"1080p", []string{"朱诺 1080p.png"}},
		{"1080P", []string{"朱诺 1080p.png"}},
		{"1080", []string{"朱诺 1080p.png"}},
		{"朱诺11", []string{"朱诺11.png"}},
	}
	for _, c := range cases {
		got := listSearchNames(t, q, libID, c.query)
		if strings.Join(sortedCopy(c.want), "\x00") != strings.Join(got, "\x00") {
			t.Errorf("q=%q 命中 %v，期望 %v", c.query, got, sortedCopy(c.want))
		}
	}
	// 无数字 token 命中全部语料（非数字语义不回归）。
	if got := listSearchNames(t, q, libID, "朱诺"); len(got) != len(searchSemanticsFiles) {
		t.Errorf("q=朱诺 应命中全部 %d 条，实际 %d 条: %v", len(searchSemanticsFiles), len(got), got)
	}
}

// 列表 / 计数 / 分面必须使用同一 q 谓词（同一批数字 token 结果一致）。
func TestSearchNumberTokenCountsAndFacets(t *testing.T) {
	_, q, libID := seedSearchSemantics(t)
	ctx := context.Background()
	for _, query := range []string{"1", "11", "110", "(2)", "朱诺 11", "1080"} {
		items := listSearchNames(t, q, libID, query)
		count, err := q.CountAssetsFiltered(ctx, db.CountAssetsFilteredParams{
			LibraryID: libID, SourceIsOther: 0, IncludeCos: 0, CosOnly: 0,
			QJson: qTokens(query),
		})
		if err != nil {
			t.Fatalf("计数搜索 q=%q 失败: %v", query, err)
		}
		if int(count) != len(items) {
			t.Errorf("q=%q 列表 %d 条与计数 %d 不一致", query, len(items), count)
		}
		// favorite/history 是 sqlc.arg（非 narg）：缺省必须显式传 0，NULL 会让
		// 三值逻辑把整行过滤掉（handler 侧恒传，见 httpapi assets_filters）。
		facets, err := q.FacetMediaTypeCounts(ctx, db.FacetMediaTypeCountsParams{
			IncludeCos: int64(1), CosOnly: int64(0), QJson: qTokens(query),
			FavoriteSubset: int64(0), HistorySubset: int64(0),
		})
		if err != nil {
			t.Fatalf("分面搜索 q=%q 失败: %v", query, err)
		}
		var total int64
		for _, row := range facets {
			total += row.FileCount
		}
		if total != count {
			t.Errorf("q=%q 分面合计 %d 与计数 %d 不一致（分面与列表分家）", query, total, count)
		}
	}
}
