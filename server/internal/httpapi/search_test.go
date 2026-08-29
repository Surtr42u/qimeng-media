package httpapi

import (
	"context"
	"database/sql"
	"net/http"
	"net/url"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/search"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// ---------- 全文搜索（q 参数，DOMAIN_RULES §3） ----------
//
// 覆盖：六维度子串命中（文件名/文件夹/标签/作者/角色/出处）、多词 AND、
// ASCII 大小写折叠、LIKE 通配符字面语义（%/_ 无魔法）、与筛选叠加、
// 索引触发器同步（移动/改名/级联删除）、RebuildIndex 重建兜底。

// seedSearchData 在基础三文件外补足搜索语料：
//   - a.jpg：标签「赛博朋克」「DVA」、角色「露西」、作者「未知画师」
//   - d1.jpg：带文件夹路径 + 标签「100%完结」「预告片」
//   - c.mp4：出处「尼尔机械纪元」
func seedSearchData(t *testing.T, ctx context.Context, q *db.Queries, conn *sql.DB) {
	t.Helper()
	now := store.FormatTimestamp(time.Now())
	a := testFiles[0]
	c := testFiles[2]

	libs, err := q.ListLibraries(ctx)
	if err != nil || len(libs) == 0 {
		t.Fatalf("读取库列表失败: %v", err)
	}
	libID := libs[0].ID
	dID := uuid.NewString()
	if _, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: dID, LibraryID: libID, RelPath: "番剧专区/第一季/d1.jpg", FileName: "d1.jpg",
		MediaType: "image", SizeBytes: 2048, Mtime: now, CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("插入 d1 失败: %v", err)
	}

	newTag := func(name string) string {
		tg, err := q.CreateTag(ctx, db.CreateTagParams{ID: uuid.NewString(), Name: name, CreatedAt: now})
		if err != nil {
			t.Fatalf("建标签 %s 失败: %v", name, err)
		}
		return tg.ID
	}
	for _, name := range []string{"赛博朋克", "DVA"} {
		tid := newTag(name)
		if err := q.AddAssetTag(ctx, db.AddAssetTagParams{AssetID: a.id, TagID: tid}); err != nil {
			t.Fatalf("挂标签失败: %v", err)
		}
	}
	for _, name := range []string{"100%完结", "预告片"} {
		tid := newTag(name)
		if err := q.AddAssetTag(ctx, db.AddAssetTagParams{AssetID: dID, TagID: tid}); err != nil {
			t.Fatalf("挂标签失败: %v", err)
		}
	}
	// 角色/作者/出处暂无 sqlc 写查询（M3 才实现），原始 SQL 直插——
	// 触发器对 SQL 层写入无差别（与 db 生成的查询同一条 SQLite 语句）。
	if _, err := conn.Exec("INSERT INTO asset_characters(asset_id, character_name) VALUES (?, '露西')", a.id); err != nil {
		t.Fatalf("直插角色失败: %v", err)
	}
	if _, err := conn.Exec("INSERT INTO authors(id, display_name, type, created_at) VALUES ('au1', '未知画师', 'regular', ?)", now); err != nil {
		t.Fatalf("直插作者失败: %v", err)
	}
	if _, err := conn.Exec("INSERT INTO asset_authors(asset_id, author_id) VALUES (?, 'au1')", a.id); err != nil {
		t.Fatalf("直插资产作者失败: %v", err)
	}
	if _, err := conn.Exec("UPDATE assets SET source = '尼尔机械纪元' WHERE asset_id = ?", c.id); err != nil {
		t.Fatalf("直插出处失败: %v", err)
	}
}

func searchItems(t *testing.T, env *testEnv, params string) []gen.AssetSummary {
	t.Helper()
	resp := env.do(t, "GET", "/api/v1/assets?"+params, "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("搜索请求期望 200，得到 %d", resp.StatusCode)
	}
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if page.Items == nil {
		return nil
	}
	return *page.Items
}

func names(items []gen.AssetSummary) []string {
	out := make([]string, 0, len(items))
	for _, it := range items {
		if it.FileName != nil {
			out = append(out, *it.FileName)
		} else {
			out = append(out, "")
		}
	}
	return out
}

func TestSearchByAllDimensions(t *testing.T) {
	env := newTestEnv(t)
	seedSearchData(t, context.Background(), env.q, env.conn)

	cases := []struct {
		q    string
		want string
	}{
		{q: "露西", want: "a.jpg"},
		{q: "赛博朋克", want: "a.jpg"},  // 标签维度
		{q: "DVA", want: "a.jpg"},   // 标签维度（英文原词）
		{q: "未知画师", want: "a.jpg"},  // 作者维度
		{q: "尼尔机械", want: "c.mp4"},  // 出处维度（子串）
		{q: "番剧专区", want: "d1.jpg"}, // 文件夹路径维度
		{q: "第一季", want: "d1.jpg"},  // 文件夹路径子目录
		{q: "d1", want: "d1.jpg"},   // 文件名维度
		{q: "预告片", want: "d1.jpg"},
	}
	for _, c := range cases {
		items := searchItems(t, env, "q="+url.QueryEscape(c.q))
		if len(items) != 1 || names(items)[0] != c.want {
			t.Fatalf("q=%q 应命中 %s，得到 %v", c.q, c.want, names(items))
		}
	}

	// 多词 AND：一词命中角色、一词命中标签 → 同一资产
	if items := searchItems(t, env, "q="+url.QueryEscape("露西 赛博朋克")); len(items) != 1 || names(items)[0] != "a.jpg" {
		t.Fatalf("多词 AND 应命中 a.jpg，得到 %v", names(items))
	}
	// 多词 AND：两个词分散在不同资产 → 无命中
	if items := searchItems(t, env, "q="+url.QueryEscape("露西 预告片")); len(items) != 0 {
		t.Fatalf("跨资产多词应无命中，得到 %v", names(items))
	}
	// ASCII 大小写折叠：小写 dva 命中标签 DVA
	if items := searchItems(t, env, "q=dva"); len(items) != 1 || names(items)[0] != "a.jpg" {
		t.Fatalf("小写 dva 应命中 a.jpg，得到 %v", names(items))
	}
	// 通配符字面语义：'%' 无 LIKE 魔法
	if items := searchItems(t, env, "q="+url.QueryEscape("100%完结")); len(items) != 1 || names(items)[0] != "d1.jpg" {
		t.Fatalf("含百分号的标签应字面命中 d1.jpg，得到 %v", names(items))
	}
	if items := searchItems(t, env, "q="+url.QueryEscape("100%")); len(items) != 1 || names(items)[0] != "d1.jpg" {
		t.Fatalf("查询词末尾百分号应字面命中 d1.jpg（非通配符），得到 %v", names(items))
	}
	// 2 字中文子串（trigram MATCH 的短词盲区，instr 语义必须覆盖）
	if items := searchItems(t, env, "q=尼尔"); len(items) != 1 || names(items)[0] != "c.mp4" {
		t.Fatalf("q=尼尔（2字出处子串）应命中 c.mp4，得到 %v", names(items))
	}

	// 与筛选叠加：q + mediaType
	if items := searchItems(t, env, "q="+url.QueryEscape("露西")+"&mediaType=image"); len(items) != 1 || names(items)[0] != "a.jpg" {
		t.Fatalf("q+mediaType=image 应命中 a.jpg，得到 %v", names(items))
	}
	if items := searchItems(t, env, "q="+url.QueryEscape("露西")+"&mediaType=video"); len(items) != 0 {
		t.Fatalf("q+mediaType=video 应无命中，得到 %v", names(items))
	}
	// totalMatched 与搜索同口径
	resp := env.do(t, "GET", "/api/v1/assets?q="+url.QueryEscape("露西"), "")
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	closeBody(resp)
	if page.TotalMatched == nil || *page.TotalMatched != 1 {
		t.Fatalf("搜索 totalMatched 应 1，得到 %v", page.TotalMatched)
	}
	// 空白 q → 等于全量（4 条）
	if items := searchItems(t, env, "q="+url.QueryEscape("   ")); len(items) != 4 {
		t.Fatalf("空白 q 应返回全量 4 条，得到 %v", names(items))
	}
}

func TestSearchTriggersKeepIndexInSync(t *testing.T) {
	env := newTestEnv(t)
	seedSearchData(t, context.Background(), env.q, env.conn)
	ctx := context.Background()

	// 资产移动（filing 的 SQL 层路径）：旧文件夹词失效、新文件夹词生效。
	// 注意 MoveAssetPath 会整体重写路径属性（含 file_name），必须补全。
	if _, err := env.q.MoveAssetPath(ctx, db.MoveAssetPathParams{
		AssetID: testFiles[0].id, RelPath: "移动后/新位置/a.jpg", FileName: "a.jpg",
		UpdatedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatalf("移动资产失败: %v", err)
	}
	if items := searchItems(t, env, "q="+url.QueryEscape("移动后")); len(items) != 1 || names(items)[0] != "a.jpg" {
		t.Fatalf("移动后新文件夹词应命中 a.jpg: %v", names(items))
	}
	if items := searchItems(t, env, "q="+url.QueryEscape("第一季")); len(items) != 1 || names(items)[0] != "d1.jpg" {
		t.Fatalf("第一季应仍只命中 d1.jpg: %v", names(items))
	}

	// 作者改名：旧名失效、新名命中（authors_fts_u）
	if _, err := env.conn.Exec(`UPDATE authors SET display_name = '大师' WHERE id = 'au1'`); err != nil {
		t.Fatalf("改作者名失败: %v", err)
	}
	if items := searchItems(t, env, "q="+url.QueryEscape("未知画师")); len(items) != 0 {
		t.Fatalf("作者改名后旧名应失效: %v", names(items))
	}
	if items := searchItems(t, env, "q="+url.QueryEscape("大师")); len(items) != 1 || names(items)[0] != "a.jpg" {
		t.Fatalf("作者改名后新名应命中: %v", names(items))
	}

	// 标签删除（DeleteTag 级联清 asset_tags → asset_tags_fts_ad 重建）：标签词失效
	rows, err := env.q.ListTags(ctx)
	if err != nil {
		t.Fatalf("读标签失败: %v", err)
	}
	var tagID, tagName string
	for _, tg := range rows {
		if tg.Name == "赛博朋克" {
			tagID, tagName = tg.ID, tg.Name
		}
	}
	if tagID == "" {
		t.Fatal("测试数据缺少赛博朋克标签")
	}
	if err := env.q.DeleteTag(ctx, tagID); err != nil {
		t.Fatalf("删除标签失败: %v", err)
	}
	if items := searchItems(t, env, "q="+url.QueryEscape(tagName)); len(items) != 0 {
		t.Fatalf("删标签后标签词应失效: %v", names(items))
	}
}

func TestSearchRebuildIndex(t *testing.T) {
	env := newTestEnv(t)
	seedSearchData(t, context.Background(), env.q, env.conn)

	// 人为破坏索引后搜索失配，RebuildIndex 全量重建后恢复——运维兜底。
	if _, err := env.conn.Exec("DELETE FROM assets_fts"); err != nil {
		t.Fatalf("破坏索引失败: %v", err)
	}
	if items := searchItems(t, env, "q="+url.QueryEscape("露西")); len(items) != 0 {
		t.Fatalf("索引清空后搜索应无命中: %v", names(items))
	}
	if err := search.RebuildIndex(context.Background(), env.q); err != nil {
		t.Fatalf("RebuildIndex 失败: %v", err)
	}
	if items := searchItems(t, env, "q="+url.QueryEscape("露西")); len(items) != 1 || names(items)[0] != "a.jpg" {
		t.Fatalf("重建后搜索应恢复命中 a.jpg: %v", names(items))
	}
}

func TestSearchWithSortAndPaginationParams(t *testing.T) {
	env := newTestEnv(t)
	seedSearchData(t, context.Background(), env.q, env.conn)

	// 搜索 + 排序 + 分页参数共存：命中 1 条且仅 1 条时无游标是
	// keyset 的正确行为（多取 1 行探测失败）；重点是参数组合不破坏查询。
	resp := env.do(t, "GET", "/api/v1/assets?q=DVA&sort=name&order=asc&limit=1", "")
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusOK || len(deref(page.Items)) != 1 {
		t.Fatalf("搜索+排序应命中 1 条: status=%d items=%d", resp.StatusCode, len(deref(page.Items)))
	}
	if page.NextCursor != nil {
		t.Fatalf("仅 1 条命中不应有游标: %v", *page.NextCursor)
	}
}
