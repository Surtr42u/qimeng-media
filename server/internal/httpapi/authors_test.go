package httpapi

// 作者体系三端点端到端测试（M3）：TXT 导入（格式 A/B2/C 覆盖 + 统一重建
// 并集语义）→ 作者列表（fileCount/followed/type）→ 关注置位与取消 → 404。
// 复用 browse_test.go 的 newTestEnv（setup + 假扫描入库 a.jpg/b.jpg/c.mp4）。

import (
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
	txtA := "1  kamihikoki_mmd  紙飛行機(小红车资源出处)\n" +
		"来源\n" +
		"`https://hanime1.me/`\n" +
		"作品\n" +
		"a.jpg\n" +
		"c.mp4\n"
	if imported, matched := importTXT(t, env, "a.txt", txtA); imported != 1 || matched != 2 {
		t.Fatalf("格式 A 导入计数 imported=%d matched=%d, want 1/2", imported, matched)
	}

	// 格式 B2（编号 + 单作者 + 出处同行）：同名作者跨 TXT，作品 "b" 无扩展名
	// 精确命中 b.jpg；重建后关联 = 两 TXT 匹配文件的并集（a/c/b 共 3）。
	txtB := "2  kamihikoki_mmd  第二别名\n" +
		"出处  kemono\n" +
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
