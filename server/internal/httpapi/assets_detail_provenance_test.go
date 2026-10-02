package httpapi

// assets_detail_provenance_test.go：详情响应溯源字段断言（ADR-0032 详情面
// 读取独立提案）。锁定三情形（与 store/provenance_test.go 的写入面行为测试
// 互补，这里锁 HTTP 响应装配面）：
//  1. client 盖章行可见：origin=client + createdAtMillis=关联成立毫秒；
//  2. legacy 行 origin=legacy：0016 存量哨兵原样透传（纯透传、不伪造词表值）；
//  3. 时间戳可空：asset_authors.created_at NULL / asset_tags.created_at epoch
//     两个不可考哨兵 → createdAtMillis 字段缺省，绝不落 1970 纪元字面量。

import (
	"context"
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func TestAssetDetailProvenanceFields(t *testing.T) {
	env := newTestEnv(t)
	ctx := context.Background()
	assetID := testFiles[0].id // newTestEnv 假扫描入库的首个资产

	stampTime := time.Date(2026, 10, 1, 12, 30, 0, 0, time.UTC)
	stamp := store.FormatTimestamp(stampTime)

	// -- 造数：可考行走写入路径盖章，legacy 行裸插（不带新列=默认哨兵）。

	// 作者行 + client 关联（PutAssetAuthors/挂靠通道同款盖章语义）。
	if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "author-prov", DisplayName: "溯源作者", Type: "regular", CreatedAt: stamp, Origin: store.OriginClient,
	}); err != nil {
		t.Fatalf("UpsertAuthor: %v", err)
	}
	if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: assetID, AuthorID: "author-prov",
		CreatedAt: store.NullTimestamp(stamp), Origin: store.OriginClient,
	}); err != nil {
		t.Fatalf("AddAssetAuthor: %v", err)
	}
	// legacy 作者关联（裸插：origin 默认 legacy、created_at NULL）。
	if _, err := env.conn.ExecContext(ctx,
		`INSERT INTO authors (id, display_name, type, created_at) VALUES ('author-old', '旧作者', 'regular', ?)`, stamp); err != nil {
		t.Fatalf("造 legacy 作者: %v", err)
	}
	if _, err := env.conn.ExecContext(ctx,
		`INSERT INTO asset_authors (asset_id, author_id) VALUES (?, 'author-old')`, assetID); err != nil {
		t.Fatalf("造 legacy 作者关联: %v", err)
	}

	// client 标签关联。
	if _, err := env.conn.ExecContext(ctx,
		`INSERT INTO tags (id, name, created_at) VALUES ('tag-prov', '溯源标签', ?)`, stamp); err != nil {
		t.Fatalf("造标签: %v", err)
	}
	if err := env.q.AddAssetTag(ctx, db.AddAssetTagParams{
		AssetID: assetID, TagID: "tag-prov", CreatedAt: stamp, Origin: store.OriginClient,
	}); err != nil {
		t.Fatalf("AddAssetTag: %v", err)
	}
	// legacy 标签关联（裸插：origin 默认 legacy、created_at 默认 epoch 哨兵）。
	if _, err := env.conn.ExecContext(ctx,
		`INSERT INTO tags (id, name, created_at) VALUES ('tag-old', '旧标签', ?)`, stamp); err != nil {
		t.Fatalf("造旧标签: %v", err)
	}
	if _, err := env.conn.ExecContext(ctx,
		`INSERT INTO asset_tags (asset_id, tag_id) VALUES (?, 'tag-old')`, assetID); err != nil {
		t.Fatalf("造 legacy 标签关联: %v", err)
	}

	// -- 详情响应断言。
	resp := env.do(t, "GET", "/api/v1/assets/"+assetID, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("详情期望 200，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := decodeBody(resp, &d); err != nil {
		t.Fatalf("解析详情失败: %v", err)
	}

	wantMillis := stampTime.UnixMilli()
	findTag := func(name string) *gen.AssetDetailTag {
		for i := range deref(d.Tags) {
			if deref(d.Tags)[i].Name != nil && *deref(d.Tags)[i].Name == name {
				return &deref(d.Tags)[i]
			}
		}
		return nil
	}
	findAuthor := func(id string) *gen.AssetDetailAuthor {
		for i := range deref(d.Authors) {
			if deref(d.Authors)[i].Id != nil && *deref(d.Authors)[i].Id == id {
				return &deref(d.Authors)[i]
			}
		}
		return nil
	}

	// ① client 盖章行可见。
	if tg := findTag("溯源标签"); tg == nil {
		t.Fatal("详情缺「溯源标签」条目")
	} else if tg.Origin == nil || *tg.Origin != store.OriginClient {
		t.Errorf("client 标签 origin = %v, want client", tg.Origin)
	} else if tg.CreatedAtMillis == nil || *tg.CreatedAtMillis != wantMillis {
		t.Errorf("client 标签 createdAtMillis = %v, want %d", tg.CreatedAtMillis, wantMillis)
	}
	if au := findAuthor("author-prov"); au == nil {
		t.Fatal("详情缺 author-prov 条目")
	} else if au.Origin == nil || *au.Origin != store.OriginClient {
		t.Errorf("client 作者 origin = %v, want client", au.Origin)
	} else if au.CreatedAtMillis == nil || *au.CreatedAtMillis != wantMillis {
		t.Errorf("client 作者 createdAtMillis = %v, want %d", au.CreatedAtMillis, wantMillis)
	}

	// ② legacy 行 origin=legacy（原样透传，不伪造词表值）。
	// ③ 时间戳可空：NULL/epoch 哨兵 → 字段缺省（nil），无 1970 字面量。
	if tg := findTag("旧标签"); tg == nil {
		t.Fatal("详情缺「旧标签」条目")
	} else if tg.Origin == nil || *tg.Origin != store.OriginLegacy {
		t.Errorf("legacy 标签 origin = %v, want legacy", tg.Origin)
	} else if tg.CreatedAtMillis != nil {
		t.Errorf("epoch 哨兵标签 createdAtMillis = %v, want 缺省（不可考）", *tg.CreatedAtMillis)
	}
	if au := findAuthor("author-old"); au == nil {
		t.Fatal("详情缺 author-old 条目")
	} else if au.Origin == nil || *au.Origin != store.OriginLegacy {
		t.Errorf("legacy 作者 origin = %v, want legacy", au.Origin)
	} else if au.CreatedAtMillis != nil {
		t.Errorf("NULL 哨兵作者 createdAtMillis = %v, want 缺省（不可考）", *au.CreatedAtMillis)
	}
}
