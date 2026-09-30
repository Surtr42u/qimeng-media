package httpapi

// 作者体系三端点端到端测试（M3）：TXT 导入（格式 A/B2/C 覆盖 + 统一重建
// 并集语义）→ 作者列表（fileCount/followed/type）→ 关注置位与取消 → 404。
// 复用 browse_test.go 的 newTestEnv（setup + 假扫描入库 a.jpg/b.jpg/c.mp4）。

import (
	"context"
	"encoding/json"
	"testing"

	"qimeng-media/server/internal/authoring"
)

// importTXT 导入一个 TXT 并返回导入结果（失败即 Fatal）。
func importTXT(t *testing.T, e *testEnv, filename, content string) (imported, matched int) {
	t.Helper()
	resp := e.do(t, "POST", "/api/v1/authors/import-txt",
		`{"filename":`+jsonQuote(filename)+`,"content":`+jsonQuote(content)+`}`)
	defer closeBody(resp)
	if resp.StatusCode != 200 {
		t.Fatalf("导入 TXT 期望 200，得到 %d（%s）", resp.StatusCode, filename)
	}
	var res genTxtImportResult
	if err := decodeBody(resp, &res); err != nil {
		t.Fatalf("解析导入结果失败: %v", err)
	}
	return res.imported(), res.matched()
}

// genTxtImportResult 是 gen.TxtImportResult 的测试侧宽松投影（指针字段统一
// 解引用，缺省计 0）。
type genTxtImportResult struct {
	AuthorsImported *int `json:"authorsImported"`
	FilesMatched    *int `json:"filesMatched"`
}

func (r genTxtImportResult) imported() int {
	if r.AuthorsImported == nil {
		return 0
	}
	return *r.AuthorsImported
}

func (r genTxtImportResult) matched() int {
	if r.FilesMatched == nil {
		return 0
	}
	return *r.FilesMatched
}

// listAuthors GET /authors 全量响应。
func listAuthors(t *testing.T, e *testEnv) []genAuthor {
	t.Helper()
	resp := e.do(t, "GET", "/api/v1/authors", "")
	defer closeBody(resp)
	if resp.StatusCode != 200 {
		t.Fatalf("作者列表期望 200，得到 %d", resp.StatusCode)
	}
	var out []genAuthor
	if err := decodeBody(resp, &out); err != nil {
		t.Fatalf("解析作者列表失败: %v", err)
	}
	return out
}

// genAuthor 是 gen.Author 的测试侧宽松投影。
type genAuthor struct {
	Id          *string `json:"id"`
	DisplayName *string `json:"displayName"`
	Type        *string `json:"type"`
	FileCount   *int    `json:"fileCount"`
	Followed    *bool   `json:"followed"`
	ViewCount   *int    `json:"viewCount"`
}

func findAuthor(t *testing.T, list []genAuthor, id string) genAuthor {
	t.Helper()
	for _, a := range list {
		if a.Id != nil && *a.Id == id {
			return a
		}
	}
	t.Fatalf("作者 %q 不在列表中: %+v", id, list)
	return genAuthor{}
}

// jsonQuote 编码 JSON 字符串字面量（json.Marshal 保证 \n 等转义合法）。
// 名字避开 assets.go 的 jsonString（SQL json_each 数组编码，语义不同）。
func jsonQuote(s string) string {
	b, _ := json.Marshal(s) // string 的 Marshal 永不失败
	return string(b)
}

// TestAuthorsImportTxtFullChain：三格式导入全链 + 跨 TXT 并集 + 重复导入幂等。
func TestAuthorsImportTxtFullChain(t *testing.T) {
	env := newTestEnv(t)
	kami := authoring.GenerateAuthorID("kamihikoki_mmd")

	// 格式 A（编号 + 多别名 + 来源区）：两作品分别精确命中 a.jpg / c.mp4。
	txtA := "1  kamihikoki_mmd  紙飛行機(site-b资源出处)\n" +
		"来源\n" +
		"`https://site-d.me/`\n" +
		"作品\n" +
		"a.jpg\n" +
		"c.mp4\n"
	if imported, matched := importTXT(t, env, "a.txt", txtA); imported != 1 || matched != 2 {
		t.Fatalf("格式 A 导入计数 imported=%d matched=%d, want 1/2", imported, matched)
	}

	// 格式 B2（编号 + 单作者 + 出处同行）：同名作者跨 TXT，作品 "b" 无扩展名
	// 精确命中 b.jpg；重建后关联 = 两 TXT 匹配文件的并集（a/c/b 共 3）。
	txtB := "2  kamihikoki_mmd  第二别名\n" +
		"出处  site-a\n" +
		"作品\n" +
		"b\n"
	if imported, matched := importTXT(t, env, "b.txt", txtB); imported != 1 || matched != 1 {
		t.Fatalf("格式 B2 导入计数 imported=%d matched=%d, want 1/1", imported, matched)
	}
	a := findAuthor(t, listAuthors(t, env), kami)
	if a.FileCount == nil || *a.FileCount != 3 {
		t.Fatalf("跨 TXT 并集后 fileCount=%v, want 3（统一重建不得被单 TXT 覆盖）", a.FileCount)
	}
	if a.DisplayName == nil || *a.DisplayName != "kamihikoki_mmd / 紙飛行機" {
		t.Errorf("displayName=%v, want 首遇别名合并（括号备注去除）", a.DisplayName)
	}
	if a.Type == nil || *a.Type != "regular" {
		t.Errorf("type=%v, want regular", a.Type)
	}
	if a.Followed == nil || *a.Followed {
		t.Errorf("followed=%v, want false（新建作者默认未关注）", a.Followed)
	}

	// 重复导入 TXT_A：幂等，关联不被清空也不翻倍（并集语义回归锁）。
	if _, matched := importTXT(t, env, "a.txt", txtA); matched != 2 {
		t.Fatalf("重复导入计数 matched=%d, want 2", matched)
	}
	if a := findAuthor(t, listAuthors(t, env), kami); a.FileCount == nil || *a.FileCount != 3 {
		t.Fatalf("重复导入后 fileCount=%v, want 3", a.FileCount)
	}

	// 格式 C（纯作者名列表）：只创建作者，不关联文件、不触发重建。
	txtC := "纯粹作者甲\n纯粹作者乙\n纯粹作者甲\n"
	if imported, matched := importTXT(t, env, "c.txt", txtC); imported != 2 || matched != 0 {
		t.Fatalf("格式 C 导入计数 imported=%d matched=%d, want 2/0（重复行去重）", imported, matched)
	}
	list := listAuthors(t, env)
	if len(list) != 3 { // kami + 甲 + 乙
		t.Fatalf("作者总数=%d, want 3", len(list))
	}
	if a := findAuthor(t, list, authoring.GenerateAuthorID("纯粹作者甲")); a.FileCount == nil || *a.FileCount != 0 {
		t.Errorf("格式 C 作者 fileCount=%v, want 0", a.FileCount)
	}
}

// TestAuthorsViewCount：GET /authors 响应含 viewCount（作者全部作品累计
// open 事件数；作者归因表 asset_authors join 口径）。
func TestAuthorsViewCount(t *testing.T) {
	env := newTestEnv(t)
	kami := authoring.GenerateAuthorID("kamihikoki_mmd")
	importTXT(t, env, "a.txt", "1  kamihikoki_mmd\n作品\na.jpg\n")

	// 2 次 open（不同 session = 2 条计数；跨天无影响，session 去重只按当日）
	reportOpenAt(t, env, testFiles[0].id, "2026-08-21T10:00:00Z", "av-s1")
	reportOpenAt(t, env, testFiles[0].id, "2026-08-21T11:00:00Z", "av-s2")

	a := findAuthor(t, listAuthors(t, env), kami)
	if a.ViewCount == nil || *a.ViewCount != 2 {
		t.Fatalf("作者 viewCount 期望 2，得到 %v", a.ViewCount)
	}
}

// TestAuthorsFollow：关注置位与取消（DOMAIN_RULES §6：布尔标记，取消即清除），
// 未知作者 404。
func TestAuthorsFollow(t *testing.T) {
	env := newTestEnv(t)
	txtA := "1  kamihikoki_mmd\n作品\na.jpg\n"
	importTXT(t, env, "a.txt", txtA)
	id := authoring.GenerateAuthorID("kamihikoki_mmd")

	// 置位。
	resp := env.do(t, "PUT", "/api/v1/authors/"+id+"/follow", `{"follow":true}`)
	defer closeBody(resp)
	if resp.StatusCode != 204 {
		t.Fatalf("关注置位期望 204，得到 %d", resp.StatusCode)
	}
	if a := findAuthor(t, listAuthors(t, env), id); a.Followed == nil || !*a.Followed {
		t.Error("关注置位未生效")
	}

	// 取消：只清标记，作者与关联保留。
	resp = env.do(t, "PUT", "/api/v1/authors/"+id+"/follow", `{"follow":false}`)
	defer closeBody(resp)
	if resp.StatusCode != 204 {
		t.Fatalf("取消关注期望 204，得到 %d", resp.StatusCode)
	}
	a := findAuthor(t, listAuthors(t, env), id)
	if a.Followed == nil || *a.Followed {
		t.Error("取消关注未清标记")
	}
	if a.FileCount == nil || *a.FileCount != 1 {
		t.Errorf("取消关注后 fileCount=%v, want 1（作者与关联保留）", a.FileCount)
	}

	// 未知作者 → 404。
	resp = env.do(t, "PUT", "/api/v1/authors/"+authoring.GenerateAuthorID("不存在作者")+"/follow", `{"follow":true}`)
	defer closeBody(resp)
	if resp.StatusCode != 404 {
		t.Errorf("未知作者期望 404，得到 %d", resp.StatusCode)
	}
}

// TestAuthorsImportTxtValidation：空 content 显式 400。
func TestAuthorsImportTxtValidation(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, "POST", "/api/v1/authors/import-txt", `{"content":"  \n "}`)
	defer closeBody(resp)
	if resp.StatusCode != 400 {
		t.Errorf("空 content 期望 400，得到 %d", resp.StatusCode)
	}
}

// listTxt GET /authors/import-txt → 文件名升序数组（旧版数据管理卡片数据）。
func listTxt(t *testing.T, e *testEnv) []string {
	t.Helper()
	resp := e.do(t, "GET", "/api/v1/authors/import-txt", "")
	defer closeBody(resp)
	if resp.StatusCode != 200 {
		t.Fatalf("TXT 列表期望 200，得到 %d", resp.StatusCode)
	}
	var items []struct {
		Filename string `json:"filename"`
	}
	if err := decodeBody(resp, &items); err != nil {
		t.Fatalf("解析 TXT 列表失败: %v", err)
	}
	names := make([]string, 0, len(items))
	for _, it := range items {
		names = append(names, it.Filename)
	}
	return names
}

// deleteTxt DELETE /authors/import-txt?filename=…，返回状态码。
func deleteTxt(t *testing.T, e *testEnv, filename string) int {
	t.Helper()
	resp := e.do(t, "DELETE", "/api/v1/authors/import-txt?filename="+filename, "")
	defer closeBody(resp)
	return resp.StatusCode
}

// TestAuthorsTxtManageList：GET/DELETE TXT 片段（旧版数据管理「TXT导入
// 作者」卡片）：列表升序；删除以「删除前全量」为删关联目标（作者只在被删
// 片段出现时旧关联随删除清空）、以剩余片段重建并集关联；作者行保留不级联
// 删除；不存在 404。
func TestAuthorsTxtManageList(t *testing.T) {
	env := newTestEnv(t)
	kami := authoring.GenerateAuthorID("kamihikoki_mmd")
	other := authoring.GenerateAuthorID("另一位作者")

	// 两个片段：K 命中 a.jpg+c.mp4；另一位 命中 b.jpg。
	importTXT(t, env, "f1.txt", "1  kamihikoki_mmd\n作品\na.jpg\nc.mp4\n")
	importTXT(t, env, "f2.txt", "1  另一位作者\n作品\nb.jpg\n")

	// GET 列表升序（f1 < f2）。
	if got := listTxt(t, env); len(got) != 2 || got[0] != "f1.txt" || got[1] != "f2.txt" {
		t.Fatalf("TXT 列表=%v, want [f1.txt f2.txt]", got)
	}

	// 删除 f2：另一位作者 只在该片段出现 → 关联清空（fileCount 0）、
	// 作者行保留；K 的并集关联不受影响（a+c 仍 2）。
	if code := deleteTxt(t, env, "f2.txt"); code != 204 {
		t.Fatalf("删除 f2.txt 期望 204，得到 %d", code)
	}
	if a := findAuthor(t, listAuthors(t, env), kami); a.FileCount == nil || *a.FileCount != 2 {
		t.Errorf("删 f2 后 K fileCount=%v, want 2（剩余片段并集不受影响）", a.FileCount)
	}
	if a := findAuthor(t, listAuthors(t, env), other); a.FileCount == nil || *a.FileCount != 0 {
		t.Errorf("删 f2 后另一位作者 fileCount=%v, want 0（只在被删片段，关联清空）", a.FileCount)
	}
	if got := listTxt(t, env); len(got) != 1 || got[0] != "f1.txt" {
		t.Fatalf("删 f2 后 TXT 列表=%v, want [f1.txt]", got)
	}

	// 删除最后一个片段：K 关联清空但作者行保留（重建后零关联不级联删行）。
	if code := deleteTxt(t, env, "f1.txt"); code != 204 {
		t.Fatalf("删除 f1.txt 期望 204，得到 %d", code)
	}
	if a := findAuthor(t, listAuthors(t, env), kami); a.FileCount == nil || *a.FileCount != 0 {
		t.Errorf("删 f1 后 K fileCount=%v, want 0", a.FileCount)
	}
	if got := listTxt(t, env); len(got) != 0 {
		t.Fatalf("删光后 TXT 列表=%v, want 空", got)
	}

	// 删除不存在的片段 → 404。
	if code := deleteTxt(t, env, "ghost.txt"); code != 404 {
		t.Errorf("删除不存在片段期望 404，得到 %d", code)
	}
}

// rebuildTxt POST /authors/import-txt/rebuild，返回 (imported, matched, 状态码)。
func rebuildTxt(t *testing.T, e *testEnv) (int, int, int) {
	t.Helper()
	resp := e.do(t, "POST", "/api/v1/authors/import-txt/rebuild", "")
	defer closeBody(resp)
	var res genTxtImportResult
	if err := decodeBody(resp, &res); err != nil {
		t.Fatalf("解析重放结果失败: %v", err)
	}
	return res.imported(), res.matched(), resp.StatusCode
}

// TestAuthorsTxtRebuild：重放已导入 TXT 片段重建常规作者关联（库重建/关联
// 丢失后的修复入口）——手工清空关联后重放恢复；幂等重放结果一致、关联不
// 翻倍；重放不写回片段（列表不变）。
func TestAuthorsTxtRebuild(t *testing.T) {
	env := newTestEnv(t)
	kami := authoring.GenerateAuthorID("kamihikoki_mmd")
	other := authoring.GenerateAuthorID("另一位作者")

	// 两个片段：K 命中 a.jpg+c.mp4；另一位 命中 b.jpg。
	importTXT(t, env, "f1.txt", "1  kamihikoki_mmd\n作品\na.jpg\nc.mp4\n")
	importTXT(t, env, "f2.txt", "1  另一位作者\n作品\nb.jpg\n")

	// 模拟关联丢失：手工删掉全部常规作者关联（库重建后 TXT 关联无重放入口，
	// 正是本端点修复的场景）。
	if err := env.q.DeleteAssetAuthorsByAuthorIds(context.Background(), []string{kami, other}); err != nil {
		t.Fatalf("清空关联失败: %v", err)
	}
	for _, a := range listAuthors(t, env) {
		if a.FileCount != nil && *a.FileCount != 0 {
			t.Fatalf("重放前关联未清空: %s fileCount=%v", *a.Id, *a.FileCount)
		}
	}

	// 重放：片段不变（仍 2 份），关联恢复为两片段并集（2+1=3）。
	if imported, matched, code := rebuildTxt(t, env); code != 200 || imported != 2 || matched != 3 {
		t.Fatalf("重放得到 (%d, %d, code %d), want (2, 3, 200)", imported, matched, code)
	}
	if a := findAuthor(t, listAuthors(t, env), kami); a.FileCount == nil || *a.FileCount != 2 {
		t.Errorf("重放后 K fileCount=%v, want 2", a.FileCount)
	}
	if a := findAuthor(t, listAuthors(t, env), other); a.FileCount == nil || *a.FileCount != 1 {
		t.Errorf("重放后另一位作者 fileCount=%v, want 1", a.FileCount)
	}

	// 再调一次：幂等——结果一致、关联不翻倍。
	if imported, matched, code := rebuildTxt(t, env); code != 200 || imported != 2 || matched != 3 {
		t.Fatalf("幂等重放得到 (%d, %d, code %d), want (2, 3, 200)", imported, matched, code)
	}
	if a := findAuthor(t, listAuthors(t, env), kami); a.FileCount == nil || *a.FileCount != 2 {
		t.Errorf("幂等重放后 K fileCount=%v, want 2（不翻倍）", a.FileCount)
	}

	// 重放不写回片段：列表不变。
	if got := listTxt(t, env); len(got) != 2 {
		t.Errorf("重放后 TXT 列表=%v, want 2 份（不新增不删除）", got)
	}
}

// TestAuthorsTxtRebuildEmpty：无已存片段时重放返回零值（200，不报错）。
func TestAuthorsTxtRebuildEmpty(t *testing.T) {
	env := newTestEnv(t)
	if imported, matched, code := rebuildTxt(t, env); code != 200 || imported != 0 || matched != 0 {
		t.Fatalf("无片段重放得到 (%d, %d, code %d), want (0, 0, 200)", imported, matched, code)
	}
}
