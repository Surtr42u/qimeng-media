package httpapi

// 标签体系端点测试：标签池（创建/重名 409/删除级联）、资产标签替换式
// 绑定（事务回滚）、时间轴标签（排序/跨资产删除隔离）。

import (
	"encoding/json"
	"net/http"
	"strconv"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

func createTag(t *testing.T, env *testEnv, name string) string {
	t.Helper()
	resp := env.do(t, http.MethodPost, "/api/v1/tags", `{"name":"`+name+`"}`)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("创建标签 %s 期望 201，得到 %d", name, resp.StatusCode)
	}
	var tag gen.Tag
	if err := json.NewDecoder(resp.Body).Decode(&tag); err != nil {
		t.Fatalf("解析标签响应失败: %v", err)
	}
	return *tag.Id
}

func TestTagPoolLifecycle(t *testing.T) {
	env := newTestEnv(t)
	id1 := createTag(t, env, "风景")
	id2 := createTag(t, env, "猫")
	if id1 == id2 {
		t.Fatal("两个标签 ID 不应相同")
	}
	// 重名 409、空名 400
	resp := env.do(t, http.MethodPost, "/api/v1/tags", `{"name":"风景"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Errorf("重名标签期望 409，得到 %d", resp.StatusCode)
	}
	resp = env.do(t, http.MethodPost, "/api/v1/tags", `{"name":"  "}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("空名标签期望 400，得到 %d", resp.StatusCode)
	}
	// 列表含关联文件数（未绑定 = 0）
	resp = env.do(t, http.MethodGet, "/api/v1/tags", "")
	defer func() { _ = resp.Body.Close() }()
	var tags []gen.Tag
	if err := json.NewDecoder(resp.Body).Decode(&tags); err != nil {
		t.Fatalf("解析标签列表失败: %v", err)
	}
	if len(tags) != 2 {
		t.Fatalf("标签池期望 2 个，得到 %d", len(tags))
	}
	for _, tg := range tags {
		if tg.FileCount == nil || *tg.FileCount != 0 {
			t.Errorf("新标签 fileCount 应 0: %+v", tg)
		}
	}
	// 删除一个 → 池里剩一个；再删同一个 → 404
	resp = env.do(t, http.MethodDelete, "/api/v1/tags/"+id1, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Errorf("删除标签期望 204，得到 %d", resp.StatusCode)
	}
	resp = env.do(t, http.MethodDelete, "/api/v1/tags/"+id1, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("重复删除期望 404，得到 %d", resp.StatusCode)
	}
}

func TestAssetTagsReplace(t *testing.T) {
	env := newTestEnv(t)
	id1 := createTag(t, env, "标签一")
	id2 := createTag(t, env, "标签二")
	asset, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("测试前置失败：a.jpg 不在列表")
	}
	put := func(body string) int {
		resp := env.do(t, http.MethodPut, "/api/v1/assets/"+asset+"/tags", body)
		_ = resp.Body.Close()
		return resp.StatusCode
	}
	detailTags := func(t *testing.T) int {
		t.Helper()
		d := env.assetDetail(t, asset)
		if d.Tags == nil {
			return 0
		}
		return len(*d.Tags)
	}
	if code := put(`{"tagIds":["` + id1 + `","` + id2 + `"]}`); code != http.StatusNoContent {
		t.Fatalf("绑定两标签期望 204，得到 %d", code)
	}
	if n := detailTags(t); n != 2 {
		t.Errorf("详情应显示 2 个标签，得到 %d", n)
	}
	// 替换式：只挂 id1 → 详情只剩 1 个
	if code := put(`{"tagIds":["` + id1 + `"]}`); code != http.StatusNoContent {
		t.Fatalf("替换绑定期望 204，得到 %d", code)
	}
	if n := detailTags(t); n != 1 {
		t.Errorf("替换后应剩 1 个标签，得到 %d", n)
	}
	// 不存在的标签 ID → 400 且原绑定保持（事务回滚验证）
	if code := put(`{"tagIds":["no-such-tag"]}`); code != http.StatusBadRequest {
		t.Errorf("引用不存在标签期望 400，得到 %d", code)
	}
	if n := detailTags(t); n != 1 {
		t.Errorf("失败替换后绑定应保持 1 个，得到 %d", n)
	}
	// 空数组 = 清空
	if code := put(`{"tagIds":[]}`); code != http.StatusNoContent {
		t.Fatalf("清空绑定期望 204，得到 %d", code)
	}
	if n := detailTags(t); n != 0 {
		t.Errorf("清空后应 0 个标签，得到 %d", n)
	}
	// 重复 ID 幂等收敛
	if code := put(`{"tagIds":["` + id1 + `","` + id1 + `"]}`); code != http.StatusNoContent {
		t.Errorf("重复 ID 期望 204，得到 %d", code)
	}
}

func TestTimelineTagsCRUD(t *testing.T) {
	env := newTestEnv(t)
	asset, ok := env.assetIDByName(t, "c.mp4")
	if !ok {
		t.Fatal("测试前置失败：c.mp4 不在列表")
	}
	add := func(ms int64, name string) string {
		resp := env.do(t, http.MethodPost, "/api/v1/assets/"+asset+"/timeline-tags",
			`{"timeMillis":`+strconv.FormatInt(ms, 10)+`,"name":"`+name+`"}`)
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("添加时间轴标签期望 201，得到 %d", resp.StatusCode)
		}
		var tg gen.TimelineTag
		if err := json.NewDecoder(resp.Body).Decode(&tg); err != nil {
			t.Fatalf("解析时间轴标签失败: %v", err)
		}
		return *tg.Id
	}
	// 乱序添加 → 列表按时间点升序
	idLate := add(5000, "高潮")
	idEarly := add(1200, "开场")
	resp := env.do(t, http.MethodGet, "/api/v1/assets/"+asset+"/timeline-tags", "")
	var tags []gen.TimelineTag
	if err := json.NewDecoder(resp.Body).Decode(&tags); err != nil {
		t.Fatalf("解析时间轴列表失败: %v", err)
	}
	_ = resp.Body.Close()
	if len(tags) != 2 || *tags[0].Id != idEarly || *tags[1].Id != idLate {
		t.Fatalf("时间轴应按时间升序 [开场, 高潮]，得到 %+v", tags)
	}
	// 跨资产删除隔离：用 a.jpg 的路径删 c.mp4 的标签 → 404 且原条目还在
	other, _ := env.assetIDByName(t, "a.jpg")
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+other+"/timeline-tags/"+idEarly, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("跨资产删除期望 404，得到 %d", resp.StatusCode)
	}
	// 正常删除 → 204，再删 404
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+asset+"/timeline-tags/"+idEarly, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Errorf("删除时间轴标签期望 204，得到 %d", resp.StatusCode)
	}
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+asset+"/timeline-tags/"+idEarly, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("重复删除期望 404，得到 %d", resp.StatusCode)
	}
	// 非法请求体：负时间点 / 空名 → 400
	resp = env.do(t, http.MethodPost, "/api/v1/assets/"+asset+"/timeline-tags",
		`{"timeMillis":-1,"name":"x"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("负时间点期望 400，得到 %d", resp.StatusCode)
	}
}
