package store

import (
	"context"
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store/db"
)

// openTestDB 在临时目录建库并完成迁移，返回带清理钩子的连接与查询对象。
// 每个测试独享一个库文件（t.TempDir 互不污染）。
func openTestDB(t *testing.T) (*sql.DB, *db.Queries) {
	t.Helper()
	conn, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("Open 失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := Migrate(conn); err != nil {
		t.Fatalf("Migrate 失败: %v", err)
	}
	return conn, db.New(conn)
}

// newLibrary 建一个测试库并返回 id。
func newLibrary(t *testing.T, q *db.Queries) string {
	t.Helper()
	lib, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID:        uuid.NewString(),
		Name:      "测试库",
		RootPath:  "/media/test",
		Kind:      "normal",
		CreatedAt: FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("CreateLibrary 失败: %v", err)
	}
	return lib.ID
}

// businessTables 是 0001 migration 应建出的全部业务表（schema_migrations
// 由 golang-migrate 管理，不计入）。
var businessTables = []string{
	"libraries", "users", "assets", "asset_characters", "tags", "asset_tags",
	"authors", "asset_authors", "view_events", "likes", "favorites",
	"timeline_tags", "trash_items", "daily_shown",
}

func tableExists(t *testing.T, conn *sql.DB, table string) bool {
	t.Helper()
	var n int
	err := conn.QueryRow(
		"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", table,
	).Scan(&n)
	if err != nil {
		t.Fatalf("查询 sqlite_master 失败: %v", err)
	}
	return n > 0
}

// searchObjects 是 0002 migration 创建的对象（FTS 表/聚合视图/同步触发器）。
type schemaObj struct {
	typ  string
	name string
}

var searchObjects = []schemaObj{
	{"trigger", "assets_fts_ai"}, {"trigger", "assets_fts_au"}, {"trigger", "assets_fts_ad"},
	{"trigger", "asset_tags_fts_ai"}, {"trigger", "asset_tags_fts_ad"}, {"trigger", "tags_fts_u"},
	{"trigger", "asset_characters_fts_ai"}, {"trigger", "asset_characters_fts_ad"},
	{"trigger", "asset_authors_fts_ai"}, {"trigger", "asset_authors_fts_ad"}, {"trigger", "authors_fts_u"},
	{"table", "assets_fts"}, {"view", "asset_search_text"},
}

func objExists(t *testing.T, conn *sql.DB, typ, name string) bool {
	t.Helper()
	var n int
	err := conn.QueryRow(
		"SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?", typ, name,
	).Scan(&n)
	if err != nil {
		t.Fatalf("查询 sqlite_master 失败: %v", err)
	}
	return n > 0
}

func TestOpenAndMigrateCreatesAllTables(t *testing.T) {
	conn, _ := openTestDB(t)
	for _, table := range businessTables {
		if !tableExists(t, conn, table) {
			t.Errorf("迁移后缺少表 %s", table)
		}
	}
}

// TestUpsertKeepsIdentity：adr/0004 核心语义——同路径再入库时身份（asset_id）
// 与首次入库时间保持不变，只有元数据属性被刷新。
func TestUpsertKeepsIdentity(t *testing.T) {
	_, q := openTestDB(t)
	ctx := context.Background()
	libID := newLibrary(t, q)

	firstAt := FormatTimestamp(time.Date(2026, 8, 1, 10, 0, 0, 0, time.UTC))
	secondAt := FormatTimestamp(time.Date(2026, 8, 22, 10, 0, 0, 0, time.UTC))

	// 第一次入库：身份 aaaa...
	a, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: "asset-identity-A", LibraryID: libID, RelPath: "dir/one.jpg",
		FileName: "one.jpg", MediaType: "image", SizeBytes: 100,
		Mtime: firstAt, CreatedAt: firstAt, UpdatedAt: firstAt,
	})
	if err != nil {
		t.Fatalf("首次 UpsertAsset 失败: %v", err)
	}
	if a.AssetID != "asset-identity-A" {
		t.Fatalf("首次入库身份 = %q, 期望 asset-identity-A", a.AssetID)
	}

	// 第二次入库：同路径但携带「新身份 + 新元数据」（模拟扫描器重新探测）。
	// 期望：走 DO UPDATE，返回身份仍是 A，size/updated_at 刷新，created_at 冻结。
	b, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: "asset-identity-B-should-be-ignored", LibraryID: libID, RelPath: "dir/one.jpg",
		FileName: "one.jpg", MediaType: "image", SizeBytes: 200,
		Mtime: secondAt, CreatedAt: secondAt, UpdatedAt: secondAt,
	})
	if err != nil {
		t.Fatalf("二次 UpsertAsset 失败: %v", err)
	}
	if b.AssetID != "asset-identity-A" {
		t.Errorf("身份被覆盖: got %q, want asset-identity-A（adr/0004：身份终身不变）", b.AssetID)
	}
	if b.SizeBytes != 200 {
		t.Errorf("元数据未刷新: SizeBytes = %d, want 200", b.SizeBytes)
	}
	if b.CreatedAt != firstAt {
		t.Errorf("created_at 被覆盖: got %q, want %q（入库时间冻结保 freshness 口径）", b.CreatedAt, firstAt)
	}

	// 按路径查应返回同一身份。
	got, err := q.GetAssetByPath(ctx, db.GetAssetByPathParams{LibraryID: libID, RelPath: "dir/one.jpg"})
	if err != nil {
		t.Fatalf("GetAssetByPath 失败: %v", err)
	}
	if got.AssetID != "asset-identity-A" || got.SizeBytes != 200 {
		t.Errorf("按路径查询结果异常: %+v", got)
	}
}

// TestKeysetPagination：13 条资产、created_at 只有 4 个不同毫秒值（同毫秒多条，
// 考验 asset_id 决胜键），LIMIT 5 翻页必须不重不漏、顺序稳定。
func TestKeysetPagination(t *testing.T) {
	_, q := openTestDB(t)
	ctx := context.Background()
	libID := newLibrary(t, q)

	// id 用受控递减命名，与 (created_at, asset_id) 复合排序的期望顺序对应。
	// 时间戳分 4 组（3+3+3+4 条），组内同毫秒。
	base := time.Date(2026, 8, 22, 12, 0, 0, 0, time.UTC)
	total := 13
	for i := 0; i < total; i++ {
		ts := FormatTimestamp(base.Add(time.Duration(i/3) * time.Millisecond))
		_, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
			AssetID:   idForIndex(i), // a12..a00，字典序与入库序一致，便于断言
			LibraryID: libID,
			RelPath:   "f/file-" + idForIndex(i) + ".jpg",
			FileName:  idForIndex(i) + ".jpg",
			MediaType: "image", SizeBytes: int64(1000 + i),
			Mtime: ts, CreatedAt: ts, UpdatedAt: ts,
		})
		if err != nil {
			t.Fatalf("插入第 %d 条资产失败: %v", i, err)
		}
	}

	// 期望全集 = 按 (created_at DESC, asset_id DESC) 的顺序。
	wantOrder := make([]string, 0, total)
	for i := total - 1; i >= 0; i-- {
		wantOrder = append(wantOrder, idForIndex(i))
	}

	// 翻页：首页 → cursor 翻页，直到取空。
	const pageSize = 5
	var gotOrder []string
	page, err := q.ListAssetsFirstPage(ctx, pageSize)
	if err != nil {
		t.Fatalf("ListAssetsFirstPage 失败: %v", err)
	}
	seen := map[string]bool{}
	for {
		if len(page) == 0 {
			break
		}
		for _, a := range page {
			if seen[a.AssetID] {
				t.Fatalf("翻页出现重复资产 %s（cursor 推进错误）", a.AssetID)
			}
			seen[a.AssetID] = true
			gotOrder = append(gotOrder, a.AssetID)
		}
		last := page[len(page)-1]
		page, err = q.ListAssetsAfterCursor(ctx, db.ListAssetsAfterCursorParams{
			CreatedAt:   last.CreatedAt,
			CreatedAt_2: last.CreatedAt,
			AssetID:     last.AssetID,
			Limit:       pageSize,
		})
		if err != nil {
			t.Fatalf("ListAssetsAfterCursor 失败: %v", err)
		}
	}

	if len(gotOrder) != total {
		t.Fatalf("翻页收集到 %d 条, 期望 %d（发生了丢页）", len(gotOrder), total)
	}
	for i := range wantOrder {
		if gotOrder[i] != wantOrder[i] {
			t.Fatalf("第 %d 条顺序错误: got %s, want %s（同毫秒决胜键失效或 cursor 排序不稳定）",
				i, gotOrder[i], wantOrder[i])
		}
	}
}

// idForIndex 生成受控 asset_id：i=0 → "a00"…i=12 → "a12"。
func idForIndex(i int) string {
	return "a" + string(rune('0'+i/10)) + string(rune('0'+i%10))
}

// TestMigrateDownThenUp：down migration 必须可执行（生产禁用，测试与灾备依赖），
// 且 down 后能再次 up（幂等重建）。migration 演进后回退步数随之变化：
// 第一步验证 0005 down（物化表/关注列/COS 库列删除、0004 对象保留），
// 第二步验证 0004 down（asset_tags.created_at 删除、0003 对象保留），
// 第三步验证 0003 down（kv_settings 删除、0002 对象保留），
// 第四步验证 0002 down（FTS 对象删除、业务表保留），
// 第五步验证 0001 down（业务表全删）。
func TestMigrateDownThenUp(t *testing.T) {
	conn, _ := openTestDB(t) // 已 up
	// 第一步：0005 down（物化表/关注列/COS 库列删除、0004 对象保留）
	if err := MigrateDown(conn, 1); err != nil {
		t.Fatalf("MigrateDown 失败: %v", err)
	}
	if tableExists(t, conn, "asset_daily_stats") {
		t.Error("0005 down 后 asset_daily_stats 仍存在（0005 down 缺 DROP）")
	}
	if columnExists(t, conn, "authors", "followed") {
		t.Error("0005 down 后 authors.followed 仍存在（0005 down 缺 DROP COLUMN）")
	}
	if columnExists(t, conn, "libraries", "kind") {
		t.Error("0005 down 后 libraries.kind 仍存在（0005 down 缺 DROP COLUMN）")
	}
	if !columnExists(t, conn, "asset_tags", "created_at") {
		t.Error("0005 down 后 asset_tags.created_at 应保留（只回退了一个版本）")
	}
	// 第二步：0004 down（asset_tags.created_at 删除、0003 对象保留）
	if err := MigrateDown(conn, 1); err != nil {
		t.Fatalf("MigrateDown 第二次失败: %v", err)
	}
	if columnExists(t, conn, "asset_tags", "created_at") {
		t.Error("0004 down 后 asset_tags.created_at 仍存在（0004 down 缺 DROP COLUMN）")
	}
	if !tableExists(t, conn, "kv_settings") {
		t.Error("0004 down 后 kv_settings 应保留（只回退了两个版本）")
	}
	if err := MigrateDown(conn, 1); err != nil {
		t.Fatalf("MigrateDown 第三次失败: %v", err)
	}
	if tableExists(t, conn, "kv_settings") {
		t.Error("0003 down 后 kv_settings 仍存在（0003 down 缺 DROP）")
	}
	for _, obj := range searchObjects {
		if !objExists(t, conn, obj.typ, obj.name) {
			t.Errorf("0003 down 后 0002 对象 %s %s 应保留（只回退了三个版本）", obj.typ, obj.name)
		}
	}
	if err := MigrateDown(conn, 1); err != nil {
		t.Fatalf("MigrateDown 第四次失败: %v", err)
	}
	for _, obj := range searchObjects {
		if objExists(t, conn, obj.typ, obj.name) {
			t.Errorf("down 后 0002 对象 %s %s 仍存在（down.sql 缺 DROP）", obj.typ, obj.name)
		}
	}
	for _, table := range businessTables {
		if !tableExists(t, conn, table) {
			t.Errorf("0002 down 后业务表 %s 应保留（0001 未回滚）", table)
		}
	}
	if err := MigrateDown(conn, 1); err != nil {
		t.Fatalf("MigrateDown 第五次失败: %v", err)
	}
	for _, table := range businessTables {
		if tableExists(t, conn, table) {
			t.Errorf("down 后表 %s 仍存在（down.sql 缺 DROP）", table)
		}
	}
	if err := Migrate(conn); err != nil {
		t.Fatalf("down 后再次 Migrate 失败: %v", err)
	}
	for _, table := range businessTables {
		if !tableExists(t, conn, table) {
			t.Errorf("二次迁移后缺少表 %s", table)
		}
	}
	if !tableExists(t, conn, "kv_settings") {
		t.Error("二次迁移后缺少表 kv_settings")
	}
	if !columnExists(t, conn, "asset_tags", "created_at") {
		t.Error("二次迁移后缺少 asset_tags.created_at（0004 未应用）")
	}
}

// columnExists 查 PRAGMA table_info 判断表是否含指定列（0004 列增删验证用）。
func columnExists(t *testing.T, conn *sql.DB, table, col string) bool {
	t.Helper()
	rows, err := conn.Query("PRAGMA table_info(" + table + ")")
	if err != nil {
		t.Fatalf("查询 %s 列信息失败: %v", table, err)
	}
	defer func() { _ = rows.Close() }()
	for rows.Next() {
		var cid, notnull, pk int
		var name, typ string
		var dflt sql.NullString
		if err := rows.Scan(&cid, &name, &typ, &notnull, &dflt, &pk); err != nil {
			t.Fatalf("扫描列信息失败: %v", err)
		}
		if name == col {
			return true
		}
	}
	return false
}

// assertUniqueViolation 断言错误是 UNIQUE 约束拒绝（约束存在性用例的公共断言）。
func assertUniqueViolation(t *testing.T, table, label string, err error) {
	t.Helper()
	if err == nil {
		t.Errorf("%s: %s 唯一约束未生效（第二次插入竟然成功）", table, label)
		return
	}
	if !strings.Contains(err.Error(), "UNIQUE constraint failed") {
		t.Errorf("%s: %s 期望 UNIQUE 约束错误, 实际: %v", table, label, err)
	}
}

// TestUniqueConstraints：关键唯一约束存在性——用「故意违反」的行为验证
// （比查 schema 文本更可靠：约束必须在运行时真的挡得住）。
func TestUniqueConstraints(t *testing.T) {
	conn, q := openTestDB(t)
	ctx := context.Background()
	libID := newLibrary(t, q)
	now := FormatTimestamp(time.Now())

	// 1) users.name 全局唯一（多用户设计的安全底线，SECURITY.md）。
	_, err := conn.Exec("INSERT INTO users (id, name, password_hash, role, token_hash, created_at) VALUES ('u1','admin','h','admin','t',?)", now)
	if err != nil {
		t.Fatalf("插入首个用户失败: %v", err)
	}
	_, err = conn.Exec("INSERT INTO users (id, name, password_hash, role, token_hash, created_at) VALUES ('u2','admin','h','admin','t',?)", now)
	assertUniqueViolation(t, "users", "name", err)

	// 2) assets 路径唯一（adr/0004：UNIQUE(library_id, rel_path)）。
	insertAsset := func(id, path string) error {
		_, err := conn.Exec(
			"INSERT INTO assets (asset_id, library_id, rel_path, file_name, media_type, size_bytes, mtime, created_at, updated_at) VALUES (?,?,?,?,'image',1,?,?,?)",
			id, libID, path, path+"-name.jpg", now, now, now)
		return err
	}
	if err := insertAsset("dup-a", "same/path.jpg"); err != nil {
		t.Fatalf("插入首条资产失败: %v", err)
	}
	assertUniqueViolation(t, "assets", "(library_id, rel_path)", insertAsset("dup-b", "same/path.jpg"))
	// 不同路径不受影响。
	if err := insertAsset("dup-c", "other/path.jpg"); err != nil {
		t.Errorf("不同路径插入不应被拒: %v", err)
	}

	// 3) tags.name 全局唯一（标签池平铺命名空间）。
	_, err = conn.Exec("INSERT INTO tags (id, name, created_at) VALUES ('t1','标签A',?)", now)
	if err != nil {
		t.Fatalf("插入首个标签失败: %v", err)
	}
	_, err = conn.Exec("INSERT INTO tags (id, name, created_at) VALUES ('t2','标签A',?)", now)
	assertUniqueViolation(t, "tags", "name", err)

	// 4) asset_tags 同资产同标签只挂一次（复合主键）。
	if _, err := conn.Exec("INSERT INTO asset_tags (asset_id, tag_id) VALUES ('dup-a','t1')"); err != nil {
		t.Fatalf("插入首个 asset_tag 失败: %v", err)
	}
	_, err = conn.Exec("INSERT INTO asset_tags (asset_id, tag_id) VALUES ('dup-a','t1')")
	assertUniqueViolation(t, "asset_tags", "(asset_id, tag_id)", err)

	// 5) likes 每资产每日一次（DOMAIN_RULES §5 的数据库层兜底）。
	today := FormatDay(time.Now())
	if err := q.AddLike(ctx, db.AddLikeParams{AssetID: "dup-a", Day: today, CreatedAt: now}); err != nil {
		t.Fatalf("当日首次点赞失败: %v", err)
	}
	err = q.AddLike(ctx, db.AddLikeParams{AssetID: "dup-a", Day: today, CreatedAt: now})
	assertUniqueViolation(t, "likes", "(asset_id, day)", err)
	// 次日可再赞。
	tomorrow := FormatDay(time.Now().AddDate(0, 0, 1))
	if err := q.AddLike(ctx, db.AddLikeParams{AssetID: "dup-a", Day: tomorrow, CreatedAt: now}); err != nil {
		t.Errorf("次日点赞不应被拒: %v", err)
	}
}

// TestForeignKeysEnforced：验证 Open() 的 foreign_keys PRAGMA 真实生效
// （SQLite 默认关闭外键，靠 DSN 逐连接开启）。
func TestForeignKeysEnforced(t *testing.T) {
	conn, _ := openTestDB(t)
	now := FormatTimestamp(time.Now())
	// library_id 指向不存在的库 → 必须被 FK 拒绝。
	_, err := conn.Exec(
		"INSERT INTO assets (asset_id, library_id, rel_path, file_name, media_type, size_bytes, mtime, created_at, updated_at) VALUES ('fk-1','no-such-library','x.jpg','x.jpg','image',1,?,?,?)",
		now, now, now)
	if err == nil || !strings.Contains(err.Error(), "FOREIGN KEY") {
		t.Errorf("外键约束未生效（PRAGMA foreign_keys 失效?）: err = %v", err)
	}
}

// TestViewEventAggregation：open/play 从事件流聚合、dwell 不计入计数
// （DOMAIN_RULES §5 口径）。
func TestViewEventAggregation(t *testing.T) {
	_, q := openTestDB(t)
	ctx := context.Background()
	libID := newLibrary(t, q)
	now := FormatTimestamp(time.Now())

	_, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: "agg-1", LibraryID: libID, RelPath: "v/agg-1.mp4",
		FileName: "agg-1.mp4", MediaType: "video", SizeBytes: 1,
		Mtime: now, CreatedAt: now, UpdatedAt: now,
	})
	if err != nil {
		t.Fatalf("插入资产失败: %v", err)
	}

	// 两次 open（不同会话，各计一次）+ 一次 play（另一会话）+ 一次 dwell。
	events := []db.InsertViewEventParams{
		{AssetID: "agg-1", Kind: "open", SessionID: "s1", StartedAt: now},
		{AssetID: "agg-1", Kind: "open", SessionID: "s2", StartedAt: now},
		{AssetID: "agg-1", Kind: "play", SessionID: "s1", StartedAt: now},
		{AssetID: "agg-1", Kind: "dwell", SessionID: "s1", StartedAt: now, Seconds: sqlInt64(12)},
	}
	for _, e := range events {
		if err := q.InsertViewEvent(ctx, e); err != nil {
			t.Fatalf("插入事件 %+v 失败: %v", e, err)
		}
	}

	rows, err := q.CountAssetEvents(ctx, "agg-1")
	if err != nil {
		t.Fatalf("CountAssetEvents 失败: %v", err)
	}
	counts := map[string]int64{}
	for _, r := range rows {
		counts[r.Kind] = r.Cnt
	}
	if counts["open"] != 2 || counts["play"] != 1 {
		t.Errorf("聚合结果错误: open=%d want 2, play=%d want 1（dwell 应不计入）",
			counts["open"], counts["play"])
	}
	if _, has := counts["dwell"]; has {
		t.Errorf("dwell 不应出现在计数聚合里: %v", counts)
	}

	all, err := q.AggregateAllEventCounts(ctx)
	if err != nil {
		t.Fatalf("AggregateAllEventCounts 失败: %v", err)
	}
	var found bool
	for _, r := range all {
		if r.AssetID == "agg-1" && r.Kind == "open" && r.Cnt == 2 {
			found = true
		}
	}
	if !found {
		t.Errorf("全库聚合未包含 agg-1 的 open=2: %+v", all)
	}
}

func sqlInt64(v int64) sql.NullInt64 { return sql.NullInt64{Int64: v, Valid: true} }

// TestLikesDailyStateAndFavoritesToggle：当日点赞状态查询 + 收藏切换组合
// （RemoveFavorite rows==0 → AddFavorite，见 favorites.sql 约定）。
func TestLikesDailyStateAndFavoritesToggle(t *testing.T) {
	_, q := openTestDB(t)
	ctx := context.Background()
	libID := newLibrary(t, q)
	now := FormatTimestamp(time.Now())
	today := FormatDay(time.Now())

	_, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: "like-1", LibraryID: libID, RelPath: "l/1.jpg",
		FileName: "1.jpg", MediaType: "image", SizeBytes: 1,
		Mtime: now, CreatedAt: now, UpdatedAt: now,
	})
	if err != nil {
		t.Fatalf("插入资产失败: %v", err)
	}

	// 点赞当日状态：未赞 → 点赞 → 已赞。
	if n, err := q.HasLikedOnDay(ctx, db.HasLikedOnDayParams{AssetID: "like-1", Day: today}); err != nil || n != 0 {
		t.Fatalf("初始当日状态应为 0: n=%d err=%v", n, err)
	}
	if err := q.AddLike(ctx, db.AddLikeParams{AssetID: "like-1", Day: today, CreatedAt: now}); err != nil {
		t.Fatalf("点赞失败: %v", err)
	}
	if n, err := q.HasLikedOnDay(ctx, db.HasLikedOnDayParams{AssetID: "like-1", Day: today}); err != nil || n != 1 {
		t.Fatalf("点赞后当日状态应为 1: n=%d err=%v", n, err)
	}
	if n, err := q.CountAssetLikes(ctx, "like-1"); err != nil || n != 1 {
		t.Fatalf("累计点赞应为 1: n=%d err=%v", n, err)
	}

	// 收藏切换：未收藏 → Remove(0) + Add(1) → IsFavorite=1；
	// 再切回：Remove(1) → IsFavorite=0。
	if rows, err := q.RemoveFavorite(ctx, "like-1"); err != nil || rows != 0 {
		t.Fatalf("未收藏时 RemoveFavorite 应影响 0 行: rows=%d err=%v", rows, err)
	}
	if rows, err := q.AddFavorite(ctx, db.AddFavoriteParams{AssetID: "like-1", CreatedAt: now}); err != nil || rows != 1 {
		t.Fatalf("AddFavorite 应影响 1 行: rows=%d err=%v", rows, err)
	}
	if n, err := q.IsFavorite(ctx, "like-1"); err != nil || n != 1 {
		t.Fatalf("收藏后 IsFavorite 应为 1: n=%d err=%v", n, err)
	}
	// 幂等：重复 AddFavorite 0 行、状态不变。
	if rows, err := q.AddFavorite(ctx, db.AddFavoriteParams{AssetID: "like-1", CreatedAt: now}); err != nil || rows != 0 {
		t.Fatalf("重复 AddFavorite 应影响 0 行: rows=%d err=%v", rows, err)
	}
	if rows, err := q.RemoveFavorite(ctx, "like-1"); err != nil || rows != 1 {
		t.Fatalf("取消收藏应影响 1 行: rows=%d err=%v", rows, err)
	}
	if n, err := q.IsFavorite(ctx, "like-1"); err != nil || n != 0 {
		t.Fatalf("取消后 IsFavorite 应为 0: n=%d err=%v", n, err)
	}
}

// TestTimestampFormats：锁死全库时间戳格式约定（keyset 排序正确性的根基，
// 误改这里会被本测试立刻抓住）。
func TestTimestampFormats(t *testing.T) {
	got := FormatTimestamp(time.Date(2026, 8, 22, 10, 30, 5, 123456789, time.UTC))
	if got != "2026-08-22T10:30:05.123Z" {
		t.Errorf("FormatTimestamp = %q, 与全库约定不符（migrations/0001 文件头）", got)
	}
	// 字典序 == 时间序的前提自检：相邻毫秒的字符串比较方向正确。
	older := FormatTimestamp(time.Date(2026, 8, 22, 10, 30, 5, 0, time.UTC))
	if !(older < got) {
		t.Errorf("时间戳字符串字典序与时间序不一致: %q 应小于 %q", older, got)
	}
	zone := time.FixedZone("CST", 8*3600)
	if d := FormatDay(time.Date(2026, 8, 22, 23, 30, 0, 0, zone)); d != "2026-08-22" {
		t.Errorf("FormatDay = %q, want 2026-08-22（本地时区日界）", d)
	}
}
