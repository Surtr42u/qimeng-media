package httpapi

// suggestions_test.go：搜索框补全端点（GET /api/v1/search/suggestions）
// 端到端用例（2026-09-05 S-1 批次）。锁口径：五维命中、子串 ASCII 大小写
// 不敏感、同维去重 / 跨维同名保留、长度升序再名称字典序、默认 10 与 limit
// 覆写、常规作者只显有文件（LEGACY §C）、空 q 空列表。
//
// 数据基座 = 标准三文件，叠加：
//   a.jpg：source=AB、角色 XABCD+天使、常规作者 ZZAB 与 DVA（DVA 同时是
//          b.jpg 的角色名——跨维同名保留用例）
//   b.jpg：source=AB（同维同名去重用例）、角色 DVA、COS 作者「X酱」、
//          cos_work=作品W
//   c.mp4：无出处无角色；另挂 12 位「补全作者NN」常规作者（limit 用例）
//   幽灵作者：只建作者行不挂任何资产（无文件作者不出现用例）

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// suggRow 补全候选的测试视图（type/name 原样字符串便于断言）。
type suggRow struct {
	Type string `json:"type"`
	Name string `json:"name"`
}

// getSuggestions 请求补全端点并解码 items。
func getSuggestions(t *testing.T, env *testEnv, query string) []suggRow {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/search/suggestions"+query, "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("suggestions%s 期望 200，得到 %d", query, resp.StatusCode)
	}
	var page struct {
		Items []suggRow `json:"items"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&page); err != nil {
		t.Fatalf("解析 suggestions 响应失败: %v", err)
	}
	return page.Items
}

// seedSuggestionFixture 补全用例语料（见文件头数据基座说明）。
func seedSuggestionFixture(t *testing.T, env *testEnv) {
	t.Helper()
	ctx := context.Background()
	now := store.FormatTimestamp(time.Now())
	a, b, c := testFiles[0], testFiles[1], testFiles[2]

	upsert := func(assetID, rel, name, mediaType string, size int64, mtime, source string) {
		t.Helper()
		p := db.UpsertAssetParams{
			AssetID: assetID, LibraryID: env.libID, RelPath: rel,
			FileName: name, MediaType: mediaType, SizeBytes: size, Mtime: mtime,
			Source:    sql.NullString{String: source, Valid: source != ""},
			CreatedAt: now, UpdatedAt: now,
		}
		if _, err := env.q.UpsertAsset(ctx, p); err != nil {
			t.Fatalf("UpsertAsset %s 失败: %v", rel, err)
		}
	}
	// 同路径 Upsert 刷新元数据（source），asset_id 保持首见。cos_work 不在
	// UpsertAsset 的 DO UPDATE 列里（migration 0008 由扫描器路径维护，测试
	// 不走扫描链路），b.jpg 的作品名用直改 UPDATE 写入（与 history_test
	// duration_ms 用例同款）。
	upsert(a.id, a.relPath, a.name, a.mediaType, a.size, a.mtime, "AB")
	upsert(b.id, b.relPath, b.name, b.mediaType, b.size, b.mtime, "AB")
	if _, err := env.conn.Exec("UPDATE assets SET cos_work = ? WHERE asset_id = ?", "作品W", b.id); err != nil {
		t.Fatalf("写 cos_work 失败: %v", err)
	}

	newAuthor := func(display, typ string) string {
		t.Helper()
		id := authoring.GenerateAuthorID(display)
		if typ == authoring.AuthorTypeCos {
			id = authoring.GenerateCosAuthorID(display)
		}
		if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
			ID: id, DisplayName: display, Type: typ, CreatedAt: now,
		}); err != nil {
			t.Fatalf("UpsertAuthor %s 失败: %v", display, err)
		}
		return id
	}
	link := func(assetID, authorID string) {
		t.Helper()
		if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: assetID, AuthorID: authorID}); err != nil {
			t.Fatalf("AddAssetAuthor 失败: %v", err)
		}
	}
	char := func(assetID, name string) {
		t.Helper()
		if err := env.q.AddAssetCharacter(ctx, db.AddAssetCharacterParams{AssetID: assetID, CharacterName: name}); err != nil {
			t.Fatalf("AddAssetCharacter 失败: %v", err)
		}
	}

	link(a.id, newAuthor("ZZAB", authoring.AuthorTypeRegular))
	link(a.id, newAuthor("DVA", authoring.AuthorTypeRegular))
	link(b.id, newAuthor("X酱", authoring.AuthorTypeCos))
	newAuthor("幽灵作者", authoring.AuthorTypeRegular) // 只建行不挂资产

	char(a.id, "XABCD")
	char(a.id, "天使")
	char(b.id, "DVA")

	// limit 用例：c.mp4 挂 12 位同名长度作者（补全作者01..12，零填充保证
	// 字典序 = 数值序）。
	for i := 1; i <= 12; i++ {
		display := "补全作者" + string([]byte{byte('0' + i/10), byte('0' + i%10)})
		link(c.id, newAuthor(display, authoring.AuthorTypeRegular))
	}
}

// TestSearchSuggestionsFiveDimsAndOrdering：五维各自命中、大小写不敏感、
// 同维去重、跨维同名保留、长度升序再字典序、无文件作者不出现。
func TestSearchSuggestionsFiveDimsAndOrdering(t *testing.T) {
	env := newTestEnv(t)
	seedSuggestionFixture(t, env)

	// q=ab：source AB（两资产同 source 去重为一条）→ 作者 ZZAB（4 字）→
	// 角色 XABCD（5 字）；长度升序排序锁定。
	got := getSuggestions(t, env, "?q=ab")
	want := []suggRow{{"source", "AB"}, {"author", "ZZAB"}, {"character", "XABCD"}}
	if len(got) != len(want) {
		t.Fatalf("q=ab 应 3 条，得到 %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("q=ab 第 %d 条 = %v, want %v（长度升序）", i, got[i], want[i])
		}
	}
	// 大写 AB 同命中（ASCII 折叠）。
	if got = getSuggestions(t, env, "?q=AB"); len(got) != 3 {
		t.Fatalf("q=AB 应同样 3 条（大小写不敏感），得到 %v", got)
	}

	// q=dva：作者 DVA 与角色 DVA 跨维同名各保留（type 徽标区分语义）。
	got = getSuggestions(t, env, "?q=dva")
	if len(got) != 2 {
		t.Fatalf("q=dva 应 2 条（author+character 同名），得到 %v", got)
	}
	seen := map[string]bool{}
	for _, r := range got {
		seen[r.Type+"|"+r.Name] = true
	}
	if !seen["author|DVA"] || !seen["character|DVA"] {
		t.Fatalf("q=dva 应含 author 与 character 两条 DVA，得到 %v", got)
	}

	// 五维各自命中（每维一条专断言）。
	if got = getSuggestions(t, env, "?q="+percentEncode("天使")); len(got) != 1 || got[0].Type != "character" {
		t.Fatalf("q=天使 应命中角色维，得到 %v", got)
	}
	if got = getSuggestions(t, env, "?q="+percentEncode("x酱")); len(got) != 1 || got[0] != (suggRow{"cosAuthor", "X酱"}) {
		t.Fatalf("q=x酱 应命中 COS 作者维（大小写不敏感），得到 %v", got)
	}
	if got = getSuggestions(t, env, "?q="+percentEncode("作品")); len(got) != 1 || got[0] != (suggRow{"cosWork", "作品W"}) {
		t.Fatalf("q=作品 应命中 COS 作品维，得到 %v", got)
	}

	// 常规作者无关联文件不出现（LEGACY §C）。
	if got = getSuggestions(t, env, "?q="+percentEncode("幽灵")); len(got) != 0 {
		t.Fatalf("无文件作者不应出现在补全，得到 %v", got)
	}
}

// TestSearchSuggestionsLimit：默认上限 10、limit 覆写、越界 400。
func TestSearchSuggestionsLimit(t *testing.T) {
	env := newTestEnv(t)
	seedSuggestionFixture(t, env)

	q := "?q=" + percentEncode("补全作者")
	// 默认 10：12 候选截断，且字典序取前 10（01..10）。
	got := getSuggestions(t, env, q)
	if len(got) != 10 {
		t.Fatalf("默认应返回 10 条，得到 %d", len(got))
	}
	for i, r := range got {
		wantName := "补全作者" + string([]byte{byte('0' + (i+1)/10), byte('0' + (i+1)%10)})
		if r.Name != wantName || r.Type != "author" {
			t.Fatalf("默认第 %d 条 = %v, want %s", i, r, wantName)
		}
	}
	// limit 覆写：12（全量）与 3（再截断）。
	if got = getSuggestions(t, env, q+"&limit=12"); len(got) != 12 {
		t.Fatalf("limit=12 应返回 12 条，得到 %d", len(got))
	}
	if got = getSuggestions(t, env, q+"&limit=3"); len(got) != 3 || got[2].Name != "补全作者03" {
		t.Fatalf("limit=3 应返回前 3 条，得到 %v", got)
	}
	// 越界 → 400（协议 maximum=50）。
	resp := env.do(t, http.MethodGet, "/api/v1/search/suggestions"+q+"&limit=51", "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("limit=51 期望 400，得到 %d", resp.StatusCode)
	}
}

// TestSearchSuggestionsEmptyQ：空 q / 纯空白 q → 空列表（协议口径）。
func TestSearchSuggestionsEmptyQ(t *testing.T) {
	env := newTestEnv(t)
	seedSuggestionFixture(t, env)
	for _, query := range []string{"?q=", "?q=%20%09"} {
		got := getSuggestions(t, env, query)
		if len(got) != 0 {
			t.Fatalf("%s 应返回空列表，得到 %v", query, got)
		}
	}
}

// TestSearchSuggestionsRecommendRandom：recommend=true 且 q 为空 → 随机推荐
// 模式（openapi recommend 参数，LEGACY 随机语义）。随机只影响顺序不影响
// 口径，故锁定确定性不变量：默认 10 条、(type,name) 唯一、type 全法值、
// 无文件作者绝不入池；limit 覆写到候选全集时集合精确等于五维口径全集。
func TestSearchSuggestionsRecommendRandom(t *testing.T) {
	env := newTestEnv(t)
	seedSuggestionFixture(t, env)

	got := getSuggestions(t, env, "?q=&recommend=true")
	if len(got) != 10 {
		t.Fatalf("默认 limit 下随机推荐应返回 10 条，得到 %d", len(got))
	}
	legal := map[string]bool{"source": true, "character": true, "cosAuthor": true, "cosWork": true, "author": true}
	seen := map[string]bool{}
	for _, r := range got {
		if !legal[r.Type] {
			t.Fatalf("随机推荐出现非法 type %q（%v）", r.Type, r)
		}
		key := r.Type + "|" + r.Name
		if seen[key] {
			t.Fatalf("随机推荐 (type,name) 应唯一，重复 %s（%v）", key, got)
		}
		seen[key] = true
		if r.Name == "幽灵作者" {
			t.Fatalf("无文件作者不应进入随机推荐池，得到 %v", got)
		}
	}

	// limit=50：候选全集 = source 1 + character 3 + cosAuthor 1 + cosWork 1
	// + author 15（ZZAB/DVA/补全作者01..12，幽灵作者被口径排除）= 21 条，
	// 集合断言与顺序无关（随机性只落在排列上）。
	got = getSuggestions(t, env, "?q=&recommend=true&limit=50")
	want := map[string]bool{
		"source|AB":       true,
		"character|XABCD": true, "character|天使": true, "character|DVA": true,
		"cosAuthor|X酱": true,
		"cosWork|作品W":  true,
		"author|ZZAB":  true, "author|DVA": true,
	}
	for i := 1; i <= 12; i++ {
		want["author|补全作者"+string([]byte{byte('0' + i/10), byte('0' + i%10)})] = true
	}
	if len(got) != len(want) {
		t.Fatalf("limit=50 应返回全部 %d 条候选，得到 %d", len(want), len(got))
	}
	for _, r := range got {
		key := r.Type + "|" + r.Name
		if !want[key] {
			t.Fatalf("随机推荐出现口径外候选 %s（%v）", key, got)
		}
		delete(want, key)
	}
	if len(want) != 0 {
		t.Fatalf("随机推荐遗漏候选: %v", want)
	}
}

// TestSearchSuggestionsRecommendIgnoredWhenQNotEmpty：q 非空时 recommend
// 被忽略（协议口径：仅 q 为空时生效），照常子串匹配且排序不变。
func TestSearchSuggestionsRecommendIgnoredWhenQNotEmpty(t *testing.T) {
	env := newTestEnv(t)
	seedSuggestionFixture(t, env)

	got := getSuggestions(t, env, "?q=ab&recommend=true")
	want := []suggRow{{"source", "AB"}, {"author", "ZZAB"}, {"character", "XABCD"}}
	if len(got) != len(want) {
		t.Fatalf("q 非空 + recommend 应照常子串匹配 3 条，得到 %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("第 %d 条 = %v, want %v（子串匹配不受 recommend 影响）", i, got[i], want[i])
		}
	}
}
