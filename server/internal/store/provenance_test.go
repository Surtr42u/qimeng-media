package store

// provenance_test.go：关联/作者类记录溯源体系行为测试（ADR-0032，
// migration 0016）。锁定四组语义：
//  1. 词表校验（NormalizeOrigin 兜底规则）；
//  2. 业务写路径盖章（origin 恒为写入通道、created_at 恒为操作时刻；
//     DO NOTHING 重复挂载首写优先）；
//  3. 0016 之前形状的存量行哨兵（origin='legacy'、时间 NULL/epoch——
//     如实记录不可考，禁止伪造时间）；
//  4. 导入行级裁决（DOMAIN_RULES §10：补证 heal 只翻不可考行、
//     首写优先 keep 绝不覆盖可考行）。

import (
	"context"
	"testing"
	"time"

	"qimeng-media/server/internal/store/db"
)

func strPtr(s string) *string { return &s }

func TestNormalizeOrigin(t *testing.T) {
	cases := []struct {
		raw  *string
		want string
	}{
		{nil, OriginImport},                        // 未携带 → 兜底 import
		{strPtr(""), OriginImport},                 // 空串 → 兜底
		{strPtr("nonsense"), OriginImport},         // 词表外 → 兜底
		{strPtr(OriginClient), OriginClient},       // 合法透传
		{strPtr(OriginLocalSync), OriginLocalSync}, // 合法透传
		{strPtr(OriginLegacy), OriginLegacy},       // 对端不可考行原样透传（不撒谎）
		{strPtr(OriginScanner), OriginScanner},     // 合法透传
		{strPtr(OriginTXT), OriginTXT},             // 合法透传
		{strPtr(OriginImport), OriginImport},       // 合法透传
	}
	for _, c := range cases {
		if got := NormalizeOrigin(c.raw); got != c.want {
			t.Errorf("NormalizeOrigin(%v) = %q, want %q", c.raw, got, c.want)
		}
	}
}

// TestProvenanceBusinessStamping：业务写路径盖章 + 作者行 origin 补证规则。
func TestProvenanceBusinessStamping(t *testing.T) {
	conn, q := openTestDB(t)
	ctx := context.Background()
	libID := newLibrary(t, q)

	now := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	stamp := FormatTimestamp(now)

	if _, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: "asset-p1", LibraryID: libID, RelPath: "p1.jpg",
		FileName: "p1.jpg", MediaType: "image", SizeBytes: 10,
		Mtime: stamp, CreatedAt: stamp, UpdatedAt: stamp,
	}); err != nil {
		t.Fatalf("造资产: %v", err)
	}

	// 作者行：首次经 TXT 通道建立 → origin=txt。
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "author_x", DisplayName: "作者X", Type: "regular", CreatedAt: stamp, Origin: OriginTXT,
	}); err != nil {
		t.Fatalf("UpsertAuthor: %v", err)
	}
	var origin string
	if err := conn.QueryRow(`SELECT origin FROM authors WHERE id='author_x'`).Scan(&origin); err != nil || origin != OriginTXT {
		t.Errorf("作者行 origin = %q, err=%v, want txt", origin, err)
	}

	// 同一作者行改经 client 通道重挂（别名挂靠等场景）：可考行不被覆盖。
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "author_x", DisplayName: "作者X", Type: "regular", CreatedAt: stamp, Origin: OriginClient,
	}); err != nil {
		t.Fatalf("UpsertAuthor 二次: %v", err)
	}
	if err := conn.QueryRow(`SELECT origin FROM authors WHERE id='author_x'`).Scan(&origin); err != nil || origin != OriginTXT {
		t.Errorf("可考作者行 origin 被覆盖 = %q, want txt（首写优先）", origin)
	}

	// legacy 作者行（裸插默认哨兵）被 client 通道补证 → origin 升级。
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO authors (id, display_name, type, created_at) VALUES ('author_old', '旧作者', 'regular', ?)`, stamp); err != nil {
		t.Fatalf("造 legacy 作者: %v", err)
	}
	if err := conn.QueryRow(`SELECT origin FROM authors WHERE id='author_old'`).Scan(&origin); err != nil || origin != OriginLegacy {
		t.Fatalf("裸插作者行 origin = %q, err=%v, want legacy（0016 默认哨兵）", origin, err)
	}
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "author_old", DisplayName: "旧作者", Type: "regular", CreatedAt: stamp, Origin: OriginClient,
	}); err != nil {
		t.Fatalf("UpsertAuthor 补证: %v", err)
	}
	if err := conn.QueryRow(`SELECT origin FROM authors WHERE id='author_old'`).Scan(&origin); err != nil || origin != OriginClient {
		t.Errorf("legacy 作者行未被补证 = %q, want client", origin)
	}

	// 关联写入：created_at=操作时刻、origin=通道；重复写入首写优先。
	if err := q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: "asset-p1", AuthorID: "author_x",
		CreatedAt: NullTimestamp(stamp), Origin: OriginClient,
	}); err != nil {
		t.Fatalf("AddAssetAuthor: %v", err)
	}
	var createdAt, linkOrigin string
	if err := conn.QueryRow(
		`SELECT created_at, origin FROM asset_authors WHERE asset_id='asset-p1' AND author_id='author_x'`,
	).Scan(&createdAt, &linkOrigin); err != nil || createdAt != stamp || linkOrigin != OriginClient {
		t.Errorf("关联盖章 = (%q, %q), err=%v, want (%q, client)", createdAt, linkOrigin, err, stamp)
	}
	// DO NOTHING：另一通道重复挂同一关联不改既有章。
	if err := q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: "asset-p1", AuthorID: "author_x",
		CreatedAt: NullTimestamp(stamp), Origin: OriginScanner,
	}); err != nil {
		t.Fatalf("AddAssetAuthor 重复: %v", err)
	}
	if err := conn.QueryRow(
		`SELECT origin FROM asset_authors WHERE asset_id='asset-p1' AND author_id='author_x'`,
	).Scan(&linkOrigin); err != nil || linkOrigin != OriginClient {
		t.Errorf("重复挂载后 origin = %q, want client（DO NOTHING 首写优先）", linkOrigin)
	}

	// 角色行：scanner 派生章 + 重算时刻。
	if err := q.AddAssetCharacter(ctx, db.AddAssetCharacterParams{
		AssetID: "asset-p1", CharacterName: "角色A",
		CreatedAt: NullTimestamp(stamp), Origin: OriginScanner,
	}); err != nil {
		t.Fatalf("AddAssetCharacter: %v", err)
	}
	var charCreatedAt, charOrigin string
	if err := conn.QueryRow(
		`SELECT created_at, origin FROM asset_characters WHERE asset_id='asset-p1'`,
	).Scan(&charCreatedAt, &charOrigin); err != nil || charOrigin != OriginScanner || charCreatedAt != stamp {
		t.Errorf("角色盖章 = (%q, %q), err=%v, want (%q, scanner)", charCreatedAt, charOrigin, err, stamp)
	}

	// 标签关联：client 章。
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO tags (id, name, created_at) VALUES ('tag-1', '标签一', ?)`, stamp); err != nil {
		t.Fatalf("造标签: %v", err)
	}
	if err := q.AddAssetTag(ctx, db.AddAssetTagParams{
		AssetID: "asset-p1", TagID: "tag-1", CreatedAt: stamp, Origin: OriginClient,
	}); err != nil {
		t.Fatalf("AddAssetTag: %v", err)
	}
	var tagOrigin string
	if err := conn.QueryRow(
		`SELECT origin FROM asset_tags WHERE asset_id='asset-p1' AND tag_id='tag-1'`,
	).Scan(&tagOrigin); err != nil || tagOrigin != OriginClient {
		t.Errorf("标签关联 origin = %q, err=%v, want client", tagOrigin, err)
	}
}

// TestProvenanceLegacySentinels：0016 之前形状的存量行（裸插不带新列）呈现
// legacy/NULL/epoch 哨兵——「如实记录不可考，禁止伪造时间」。
func TestProvenanceLegacySentinels(t *testing.T) {
	conn, q := openTestDB(t)
	ctx := context.Background()
	_ = newLibrary(t, q) // assets 的 library_id FK 锚点

	stamp := FormatTimestamp(time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC))
	// 资产锚点：asset_authors/asset_tags/asset_characters 均有 assets FK，
	// 裸插最小资产行（0001 形状）。
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO assets (asset_id, library_id, rel_path, file_name, media_type, size_bytes, mtime, created_at, updated_at)
		 SELECT 'asset-old', id, 'old.jpg', 'old.jpg', 'image', 1, ?, ?, ? FROM libraries LIMIT 1`, stamp, stamp, stamp); err != nil {
		t.Fatalf("造资产: %v", err)
	}
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO authors (id, display_name, type, created_at) VALUES ('a_old', '旧', 'regular', ?)`, stamp); err != nil {
		t.Fatalf("造作者: %v", err)
	}
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO asset_authors (asset_id, author_id) VALUES ('asset-old', 'a_old')`); err != nil {
		t.Fatalf("造关联: %v", err)
	}
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO asset_characters (asset_id, character_name) VALUES ('asset-old', '角色')`); err != nil {
		t.Fatalf("造角色: %v", err)
	}
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO tags (id, name, created_at) VALUES ('tag-old', '旧标签', ?)`, stamp); err != nil {
		t.Fatalf("造标签: %v", err)
	}
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO asset_tags (asset_id, tag_id) VALUES ('asset-old', 'tag-old')`); err != nil {
		t.Fatalf("造标签关联: %v", err)
	}

	var origin string
	var createdAt any
	if err := conn.QueryRow(`SELECT origin, created_at FROM asset_authors WHERE asset_id='asset-old'`).Scan(&origin, &createdAt); err != nil || origin != OriginLegacy || createdAt != nil {
		t.Errorf("存量作者关联 = (%q, %v), err=%v, want (legacy, NULL)", origin, createdAt, err)
	}
	if err := conn.QueryRow(`SELECT origin, created_at FROM asset_characters WHERE asset_id='asset-old'`).Scan(&origin, &createdAt); err != nil || origin != OriginLegacy || createdAt != nil {
		t.Errorf("存量角色行 = (%q, %v), err=%v, want (legacy, NULL)", origin, createdAt, err)
	}
	if err := conn.QueryRow(`SELECT origin FROM authors WHERE id='a_old'`).Scan(&origin); err != nil || origin != OriginLegacy {
		t.Errorf("存量作者行 origin = %q, err=%v, want legacy", origin, err)
	}
	var tagCreatedAt string
	if err := conn.QueryRow(`SELECT origin, created_at FROM asset_tags WHERE asset_id='asset-old'`).Scan(&origin, &tagCreatedAt); err != nil || origin != OriginLegacy || tagCreatedAt != TimestampEpoch {
		t.Errorf("存量标签关联 = (%q, %q), err=%v, want (legacy, epoch 哨兵)", origin, tagCreatedAt, err)
	}
}

// TestProvenanceImportAdjudication：导入行级裁决（DOMAIN_RULES §10）——
// 补证 heal 只翻不可考行；首写优先 keep 绝不覆盖可考行。
func TestProvenanceImportAdjudication(t *testing.T) {
	conn, q := openTestDB(t)
	ctx := context.Background()
	_ = newLibrary(t, q) // assets 的 library_id FK 锚点

	now := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	stamp := FormatTimestamp(now)
	backupStamp := FormatTimestamp(now.Add(-72 * time.Hour)) // 备份声称的原始关联时刻
	knownStamp := FormatTimestamp(now.Add(-time.Hour))       // 库内可考行的既有关联时刻

	// 资产锚点（asset_authors/asset_tags 的 assets FK）。
	for _, id := range []string{"asset-n", "asset-l", "asset-k", "asset-k2"} {
		if _, err := conn.ExecContext(ctx,
			`INSERT INTO assets (asset_id, library_id, rel_path, file_name, media_type, size_bytes, mtime, created_at, updated_at)
			 SELECT ?, id, ?, ?, 'image', 1, ?, ?, ? FROM libraries LIMIT 1`, id, id+".jpg", id+".jpg", stamp, stamp, stamp); err != nil {
			t.Fatalf("造资产 %s: %v", id, err)
		}
	}

	// 作者行三分支：新行透传章 / legacy 行补证 / 可考行保留。
	if err := q.ImportUpsertAuthor(ctx, db.ImportUpsertAuthorParams{
		ID: "author_new", DisplayName: "新作者", Type: "regular", CreatedAt: stamp, Origin: OriginClient,
	}); err != nil {
		t.Fatalf("ImportUpsertAuthor 新行: %v", err)
	}
	var authorOrigin string
	if err := conn.QueryRow(`SELECT origin FROM authors WHERE id='author_new'`).Scan(&authorOrigin); err != nil || authorOrigin != OriginClient {
		t.Errorf("新导入作者 origin = %q, err=%v, want client（透传）", authorOrigin, err)
	}

	if _, err := conn.ExecContext(ctx,
		`INSERT INTO authors (id, display_name, type, created_at) VALUES ('author_legacy', '旧作者', 'regular', ?)`, stamp); err != nil {
		t.Fatalf("造 legacy 作者: %v", err)
	}
	if err := q.ImportUpsertAuthor(ctx, db.ImportUpsertAuthorParams{
		ID: "author_legacy", DisplayName: "旧作者", Type: "regular", CreatedAt: stamp, Origin: OriginTXT,
	}); err != nil {
		t.Fatalf("ImportUpsertAuthor 补证: %v", err)
	}
	if err := conn.QueryRow(`SELECT origin FROM authors WHERE id='author_legacy'`).Scan(&authorOrigin); err != nil || authorOrigin != OriginTXT {
		t.Errorf("legacy 作者未被补证 = %q, err=%v, want txt", authorOrigin, err)
	}

	if err := q.ImportUpsertAuthor(ctx, db.ImportUpsertAuthorParams{
		ID: "author_new", DisplayName: "新作者", Type: "regular", CreatedAt: stamp, Origin: OriginLocalSync,
	}); err != nil {
		t.Fatalf("ImportUpsertAuthor 重导: %v", err)
	}
	if err := conn.QueryRow(`SELECT origin FROM authors WHERE id='author_new'`).Scan(&authorOrigin); err != nil || authorOrigin != OriginClient {
		t.Errorf("可考作者 origin 被重导覆盖 = %q, want client", authorOrigin)
	}

	// 作者关联三分支：新增透传 / legacy+NULL 补证 / 可考行保留。
	if err := q.ImportAddAssetAuthor(ctx, db.ImportAddAssetAuthorParams{
		AssetID: "asset-n", AuthorID: "author_new",
		CreatedAt: NullTimestamp(backupStamp), Origin: OriginClient,
	}); err != nil {
		t.Fatalf("ImportAddAssetAuthor 新增: %v", err)
	}
	var linkCreatedAt, linkOrigin string
	if err := conn.QueryRow(
		`SELECT created_at, origin FROM asset_authors WHERE asset_id='asset-n' AND author_id='author_new'`,
	).Scan(&linkCreatedAt, &linkOrigin); err != nil || linkOrigin != OriginClient || linkCreatedAt != backupStamp {
		t.Errorf("新增关联章 = (%q, %q), err=%v, want (%q, client)", linkCreatedAt, linkOrigin, err, backupStamp)
	}

	// legacy 存量行（origin=legacy、created_at NULL）被备份补证。
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO asset_authors (asset_id, author_id) VALUES ('asset-l', 'author_new')`); err != nil {
		t.Fatalf("造 legacy 关联: %v", err)
	}
	if err := q.ImportAddAssetAuthor(ctx, db.ImportAddAssetAuthorParams{
		AssetID: "asset-l", AuthorID: "author_new",
		CreatedAt: NullTimestamp(backupStamp), Origin: OriginClient,
	}); err != nil {
		t.Fatalf("ImportAddAssetAuthor 补证: %v", err)
	}
	if err := conn.QueryRow(
		`SELECT created_at, origin FROM asset_authors WHERE asset_id='asset-l' AND author_id='author_new'`,
	).Scan(&linkCreatedAt, &linkOrigin); err != nil || linkOrigin != OriginClient || linkCreatedAt != backupStamp {
		t.Errorf("不可考关联补证 = (%q, %q), err=%v, want (%q, client)", linkCreatedAt, linkOrigin, err, backupStamp)
	}

	// 可考行（首写 client + knownStamp）被备份（scanner + backupStamp）撞上 → 保留。
	if err := q.ImportAddAssetAuthor(ctx, db.ImportAddAssetAuthorParams{
		AssetID: "asset-k", AuthorID: "author_new",
		CreatedAt: NullTimestamp(knownStamp), Origin: OriginClient,
	}); err != nil {
		t.Fatalf("造可考关联: %v", err)
	}
	if err := q.ImportAddAssetAuthor(ctx, db.ImportAddAssetAuthorParams{
		AssetID: "asset-k", AuthorID: "author_new",
		CreatedAt: NullTimestamp(backupStamp), Origin: OriginScanner,
	}); err != nil {
		t.Fatalf("ImportAddAssetAuthor keep: %v", err)
	}
	if err := conn.QueryRow(
		`SELECT created_at, origin FROM asset_authors WHERE asset_id='asset-k' AND author_id='author_new'`,
	).Scan(&linkCreatedAt, &linkOrigin); err != nil || linkOrigin != OriginClient || linkCreatedAt != knownStamp {
		t.Errorf("可考关联被覆盖 = (%q, %q), err=%v, want (%q, client)", linkCreatedAt, linkOrigin, err, knownStamp)
	}

	// 标签关联：epoch 哨兵行补证 + 可考行保留。
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO tags (id, name, created_at) VALUES ('tag-i', '导入标签', ?)`, stamp); err != nil {
		t.Fatalf("造标签: %v", err)
	}
	if _, err := conn.ExecContext(ctx,
		`INSERT INTO asset_tags (asset_id, tag_id) VALUES ('asset-l', 'tag-i')`); err != nil {
		t.Fatalf("造 epoch 标签关联: %v", err)
	}
	if err := q.ImportAddAssetTag(ctx, db.ImportAddAssetTagParams{
		AssetID: "asset-l", TagID: "tag-i",
		CreatedAt: backupStamp, Origin: OriginClient,
	}); err != nil {
		t.Fatalf("ImportAddAssetTag 补证: %v", err)
	}
	if err := conn.QueryRow(
		`SELECT created_at, origin FROM asset_tags WHERE asset_id='asset-l' AND tag_id='tag-i'`,
	).Scan(&linkCreatedAt, &linkOrigin); err != nil || linkOrigin != OriginClient || linkCreatedAt != backupStamp {
		t.Errorf("epoch 标签关联补证 = (%q, %q), err=%v, want (%q, client)", linkCreatedAt, linkOrigin, err, backupStamp)
	}

	if err := q.ImportAddAssetTag(ctx, db.ImportAddAssetTagParams{
		AssetID: "asset-k2", TagID: "tag-i",
		CreatedAt: knownStamp, Origin: OriginTXT,
	}); err != nil {
		t.Fatalf("造可考标签关联: %v", err)
	}
	if err := q.ImportAddAssetTag(ctx, db.ImportAddAssetTagParams{
		AssetID: "asset-k2", TagID: "tag-i",
		CreatedAt: backupStamp, Origin: OriginImport,
	}); err != nil {
		t.Fatalf("ImportAddAssetTag keep: %v", err)
	}
	if err := conn.QueryRow(
		`SELECT created_at, origin FROM asset_tags WHERE asset_id='asset-k2' AND tag_id='tag-i'`,
	).Scan(&linkCreatedAt, &linkOrigin); err != nil || linkOrigin != OriginTXT || linkCreatedAt != knownStamp {
		t.Errorf("可考标签关联被覆盖 = (%q, %q), err=%v, want (%q, txt)", linkCreatedAt, linkOrigin, err, knownStamp)
	}
}
