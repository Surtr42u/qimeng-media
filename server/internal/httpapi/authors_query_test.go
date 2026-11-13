package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

type authorQueryTestItem struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
	Type        string `json:"type"`
}

type authorQueryTestPage struct {
	Items      []authorQueryTestItem `json:"items"`
	NextCursor *string               `json:"nextCursor"`
}

func insertAuthorQueryTestRows(t *testing.T, env *testEnv, rows ...authorQueryTestItem) {
	t.Helper()
	for _, row := range rows {
		if _, err := env.conn.Exec(`INSERT INTO authors(id, display_name, type, created_at) VALUES (?, ?, ?, '2026-01-01')`,
			row.ID, row.DisplayName, row.Type); err != nil {
			t.Fatalf("插入合成作者 %q 失败: %v", row.ID, err)
		}
	}
}

func fetchAuthorQueryPage(t *testing.T, env *testEnv, query string) (authorQueryTestPage, []map[string]json.RawMessage) {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/authors/query"+query, "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("作者查询期望 200，得到 %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	var page authorQueryTestPage
	if err := json.Unmarshal(body, &page); err != nil {
		t.Fatalf("解析作者分页失败: %v", err)
	}
	var object struct {
		Items []map[string]json.RawMessage `json:"items"`
	}
	if err := json.Unmarshal(body, &object); err != nil {
		t.Fatalf("解析作者紧凑字段失败: %v", err)
	}
	return page, object.Items
}

func TestAuthorsQuerySearchStableCursorAndCompactItems(t *testing.T) {
	env := newTestEnv(t)
	insertAuthorQueryTestRows(t, env,
		authorQueryTestItem{ID: "id-a2", DisplayName: "Alice", Type: "regular"},
		authorQueryTestItem{ID: "id-a1", DisplayName: "Alice", Type: "regular"},
		authorQueryTestItem{ID: "author-alice-id", DisplayName: "Zelda", Type: "cos"},
		authorQueryTestItem{ID: "unicode-author", DisplayName: "Älice", Type: "regular"},
	)

	first, rawItems := fetchAuthorQueryPage(t, env, "?q="+url.QueryEscape("ALICE")+"&limit=2")
	if len(first.Items) != 2 || first.Items[0].ID != "id-a1" || first.Items[1].ID != "id-a2" {
		t.Fatalf("重名结果顺序/页大小不符: %+v", first.Items)
	}
	if first.NextCursor == nil || *first.NextCursor == "" {
		t.Fatal("仍有匹配项时应返回 nextCursor")
	}
	for _, item := range rawItems {
		if len(item) != 3 || item["id"] == nil || item["displayName"] == nil || item["type"] == nil {
			t.Fatalf("作者查询应只返回 id/displayName/type，得到字段 %v", item)
		}
	}

	second, _ := fetchAuthorQueryPage(t, env, "?q="+url.QueryEscape("ALICE")+"&limit=2&cursor="+url.QueryEscape(*first.NextCursor))
	if len(second.Items) != 1 || second.Items[0].ID != "author-alice-id" || second.Items[0].Type != "cos" {
		t.Fatalf("游标续页未按 ID 子串补齐剩余匹配: %+v", second.Items)
	}
	if second.NextCursor != nil {
		t.Fatalf("末页 nextCursor 应为 null，得到 %q", *second.NextCursor)
	}

	unicodeLower, _ := fetchAuthorQueryPage(t, env, "?q="+url.QueryEscape("ä"))
	if len(unicodeLower.Items) != 0 {
		t.Fatalf("非 ASCII 搜索应区分大小写，q=ä 命中 %+v", unicodeLower.Items)
	}
	unicodeExact, _ := fetchAuthorQueryPage(t, env, "?q="+url.QueryEscape("Ä"))
	if len(unicodeExact.Items) != 1 || unicodeExact.Items[0].ID != "unicode-author" {
		t.Fatalf("非 ASCII 原值搜索结果不符: %+v", unicodeExact.Items)
	}
}

func TestAuthorsQueryDefaultLimitAndInvalidInputs(t *testing.T) {
	env := newTestEnv(t)
	rows := make([]authorQueryTestItem, 21)
	for i := range rows {
		id := "default-" + string(rune('a'+i))
		rows[i] = authorQueryTestItem{ID: id, DisplayName: "Default " + id, Type: "regular"}
	}
	insertAuthorQueryTestRows(t, env, rows...)

	first, _ := fetchAuthorQueryPage(t, env, "")
	if len(first.Items) != authorQueryDefaultLimit || first.NextCursor == nil {
		t.Fatalf("默认查询应限 20 条并提供续页游标：items=%d cursor=%v", len(first.Items), first.NextCursor)
	}
	last, _ := fetchAuthorQueryPage(t, env, "?cursor="+url.QueryEscape(*first.NextCursor))
	if len(last.Items) != 1 || last.NextCursor != nil {
		t.Fatalf("默认页续读应剩余 1 条且到末页：items=%d cursor=%v", len(last.Items), last.NextCursor)
	}

	for _, query := range []string{"?limit=101", "?q=" + url.QueryEscape(strings.Repeat("x", 201)), "?cursor=invalid"} {
		resp := env.do(t, http.MethodGet, "/api/v1/authors/query"+query, "")
		closeBody(resp)
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("非法查询 %q 应返回 400，得到 %d", query, resp.StatusCode)
		}
	}
}
