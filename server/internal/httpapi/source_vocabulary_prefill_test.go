package httpapi

// source_vocabulary_prefill_test.go：通用来源词表自动预填端到端（GET
// /authors/source-vocabulary 读取路径，ADR-0024）。统计口径由 authorattach
// 包纯函数测试锁定；本文件锁定 HTTP 行为：出厂态空数组不写键、共用词出现
// 后一次预填、键存在（已预填/用户 PUT）永不再预填。

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"reflect"
	"testing"

	"qimeng-media/server/internal/authoring"
)

// vocabularyKeyStored 报告词表 kv 键是否已存在（预填一次性的判定锚点）。
func vocabularyKeyStored(t *testing.T, e *testEnv) bool {
	t.Helper()
	_, err := e.q.GetSetting(context.Background(), authoring.SettingKeyAuthorSourceVocabulary)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return false
	case err != nil:
		t.Fatalf("读词表键失败: %v", err)
	}
	return true
}

// TestSourceVocabularyAutoPrefillE2E：出厂空 → 空数组不写键；两位作者共用
// 平台出现 → 一次预填并返回；此后（新增共用词 / 用户 PUT 含清空）永不
// 再预填。
func TestSourceVocabularyAutoPrefillE2E(t *testing.T) {
	env := newTestEnv(t)

	// 出厂态（无片段）：GET → 200 空数组，键不存在（未来片段导入后自然重试）。
	if code, got := getVocabulary(t, env); code != http.StatusOK || len(got) != 0 {
		t.Fatalf("出厂态 GET=（%d, %v）, want（200, 空）", code, got)
	}
	if vocabularyKeyStored(t, env) {
		t.Fatal("出厂态 GET 不得写词表键")
	}

	// 只有单作者词：仍无合格词、不写键。
	importTXT(t, env, "a.txt", "1  作者甲\n来源\nlofter\n甲的私人x页\n作品\na.jpg\n")
	if code, got := getVocabulary(t, env); code != http.StatusOK || len(got) != 0 {
		t.Fatalf("单作者词 GET=（%d, %v）, want（200, 空）", code, got)
	}
	if vocabularyKeyStored(t, env) {
		t.Fatal("无合格词不得写词表键")
	}

	// 第二位作者共用 lofter（各自另带私人 x 页与链接形态词）→ 一次预填。
	importTXT(t, env, "b.txt", "1  作者乙\n来源\nlofter\nhttps://b.example.com/home\n作品\nb.jpg\n")
	if code, got := getVocabulary(t, env); code != http.StatusOK || !reflect.DeepEqual(got, []string{"lofter"}) {
		t.Fatalf("预填 GET=（%d, %v）, want（200, [lofter]）", code, got)
	}
	if !vocabularyKeyStored(t, env) {
		t.Fatal("预填结果应写入词表键")
	}

	// 键已存在：新导入引入新的共用词（forum-c）不再触发预填。
	importTXT(t, env, "c.txt", "1  作者丙\n来源\nlofter\nforum-c\n作品\nc.jpg\n\n"+
		"2  作者丁\n来源\nforum-c\n作品\nd.jpg\n")
	if code, got := getVocabulary(t, env); code != http.StatusOK || !reflect.DeepEqual(got, []string{"lofter"}) {
		t.Fatalf("键存在后再 GET=（%d, %v）, want（200, [lofter]）不得再预填", code, got)
	}

	// 用户 PUT 覆盖（写键）→ 永不覆盖用户词表。
	resp := env.do(t, "PUT", "/api/v1/authors/source-vocabulary", `{"sources":["手动站"]}`)
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 词表期望 200，得到 %d", resp.StatusCode)
	}
	if code, got := getVocabulary(t, env); code != http.StatusOK || !reflect.DeepEqual(got, []string{"手动站"}) {
		t.Fatalf("用户 PUT 后 GET=（%d, %v）, want（200, [手动站]）", code, got)
	}

	// 用户 PUT 清空（空数组也是键存在形态）→ 仍不预填。
	resp = env.do(t, "PUT", "/api/v1/authors/source-vocabulary", `{"sources":[]}`)
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 清空期望 200，得到 %d", resp.StatusCode)
	}
	if code, got := getVocabulary(t, env); code != http.StatusOK || len(got) != 0 {
		t.Fatalf("用户清空后 GET=（%d, %v）, want（200, 空）", code, got)
	}
}
