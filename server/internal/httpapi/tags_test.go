package httpapi

// 标签体系端点测试：标签池（创建/重名 409/删除级联）、资产标签替换式
// 绑定（事务回滚）、时间轴标签（排序/跨资产删除隔离）。

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
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
	// 名字序（LEGACY_REQUIREMENTS §A：筛选面板等非详情场景一律名字序）。
	// UTF-8 字节序下"猫"(U+732B) < "风景"(U+98CE)，与创建顺序（风景先建）相反，
	// 正好证明排序键是名称而非创建时间。
	if *tags[0].Name != "猫" || *tags[1].Name != "风景" {
		t.Fatalf("标签列表应按名称升序 [猫, 风景]，得到 [%s, %s]", *tags[0].Name, *tags[1].Name)
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

// TestAssetTagsDetailOrder 详情页标签按关联时间倒序（LEGACY_REQUIREMENTS §A：
// 详情标签弹窗"最近添加置顶"）。替换式 PUT 一次写入全部行并刷新全部关联
// 时间（整体替换 = 重添加也置顶）；同批毫秒内按标签创建时间倒序 tie-break
// （后创建的标签在前），跨批次由刷新后的关联时间主导。
func TestAssetTagsDetailOrder(t *testing.T) {
	env := newTestEnv(t)
	id1 := createTag(t, env, "标签一") // t0
	env.clock.advance(time.Hour)
	id2 := createTag(t, env, "标签二") // t0+1h（后创建 → 批次内 tie-break 排前）
	asset, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("测试前置失败：a.jpg 不在列表")
	}
	put := func(t *testing.T, ids ...string) {
		t.Helper()
		body := `{"tagIds":["` + strings.Join(ids, `","`) + `"]}`
		resp := env.do(t, http.MethodPut, "/api/v1/assets/"+asset+"/tags", body)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusNoContent {
			t.Fatalf("绑定期望 204，得到 %d", resp.StatusCode)
		}
	}
	names := func(t *testing.T) []string {
		t.Helper()
		d := env.assetDetail(t, asset)
		out := make([]string, 0, len(*d.Tags))
		for _, tg := range *d.Tags {
			out = append(out, *tg.Name)
		}
		return out
	}
	put(t, id1, id2)
	if got := names(t); len(got) != 2 || got[0] != "标签二" || got[1] != "标签一" {
		t.Fatalf("详情应按关联时间倒序（同批按标签创建时间）[标签二, 标签一]，得到 %v", got)
	}
	// 关联时间随替换刷新：再次替换后两行的 created_at 都等于当前时钟
	//（整体替换语义，替换即刷新——"重添加也置顶"的数据基础）。
	env.clock.advance(time.Hour) // t0+2h
	put(t, id1, id2)
	latest := store.FormatTimestamp(env.clock.Now())
	for _, tagID := range []string{id1, id2} {
		var ts string
		if err := env.conn.QueryRow(
			"SELECT created_at FROM asset_tags WHERE asset_id = ? AND tag_id = ?",
			asset, tagID).Scan(&ts); err != nil {
			t.Fatalf("查询关联时间失败: %v", err)
		}
		if ts != latest {
			t.Fatalf("替换后关联时间应刷新为 %s，实际 %s", latest, ts)
		}
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

// TestAssetTagUnbindSingle 逐条解除（协议批 P2，DELETE /assets/{id}/tags/{tag}）：
// 只删该条关联、其余关联 created_at 不动；幂等（未挂载 → 204）；
// 资产不存在 / 标签池无此名 → 404。
func TestAssetTagUnbindSingle(t *testing.T) {
	env := newTestEnv(t)
	id1 := createTag(t, env, "标签一")
	id2 := createTag(t, env, "标签二")
	asset, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("测试前置失败：a.jpg 不在列表")
	}
	resp := env.do(t, http.MethodPut, "/api/v1/assets/"+asset+"/tags", `{"tagIds":["`+id1+`","`+id2+`"]}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("绑定期望 204，得到 %d", resp.StatusCode)
	}
	env.clock.advance(time.Hour)
	createdAtBefore := func(t *testing.T, tagID string) string {
		t.Helper()
		var ts string
		if err := env.conn.QueryRow(
			"SELECT created_at FROM asset_tags WHERE asset_id = ? AND tag_id = ?",
			asset, tagID).Scan(&ts); err != nil {
			t.Fatalf("查询关联时间失败: %v", err)
		}
		return ts
	}
	ts2Before := createdAtBefore(t, id2)

	// 逐条解除「标签一」（URL 编码标签名）
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+asset+"/tags/"+url.PathEscape("标签一"), "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("逐条解除期望 204，得到 %d", resp.StatusCode)
	}
	// 详情只剩「标签二」
	d := env.assetDetail(t, asset)
	if d.Tags == nil || len(*d.Tags) != 1 || *(*d.Tags)[0].Name != "标签二" {
		t.Fatalf("解除后详情应只剩 标签二，得到 %v", d.Tags)
	}
	// 其余关联 created_at 不动（区别于整体替换的刷新语义）
	if got := createdAtBefore(t, id2); got != ts2Before {
		t.Fatalf("未解除关联的 created_at 不应变化：before=%s after=%s", ts2Before, got)
	}
	// 幂等：再删同一个（已无关联）→ 204
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+asset+"/tags/"+url.PathEscape("标签一"), "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("重复解除（幂等）期望 204，得到 %d", resp.StatusCode)
	}
	// 标签池无此名 → 404
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+asset+"/tags/"+url.PathEscape("不存在的标签"), "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("标签不存在期望 404，得到 %d", resp.StatusCode)
	}
	// 资产不存在 → 404
	resp = env.do(t, http.MethodDelete, "/api/v1/assets/"+uuid.NewString()+"/tags/"+url.PathEscape("标签二"), "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("资产不存在期望 404，得到 %d", resp.StatusCode)
	}
}

// TestTimelineTagColor 时间轴标签颜色协议化（协议批 P2）：创建透传、
// 更新改色/清除、响应省略空色、非法 color 400。
func TestTimelineTagColor(t *testing.T) {
	env := newTestEnv(t)
	asset, ok := env.assetIDByName(t, "c.mp4")
	if !ok {
		t.Fatal("测试前置失败：c.mp4 不在列表")
	}
	// 创建带颜色 → 201 响应回带；不带 → 响应无 color 字段
	resp := env.do(t, http.MethodPost, "/api/v1/assets/"+asset+"/timeline-tags",
		`{"timeMillis":1000,"name":"开场","color":"#d6336c"}`)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("带色创建期望 201，得到 %d", resp.StatusCode)
	}
	var colored gen.TimelineTag
	if err := json.NewDecoder(resp.Body).Decode(&colored); err != nil {
		t.Fatalf("解析带色创建响应失败: %v", err)
	}
	_ = resp.Body.Close()
	if colored.Id == nil || colored.Color == nil || *colored.Color != "#d6336c" {
		t.Fatalf("带色创建响应应回带 id 与 color，得到 %+v", colored)
	}

	resp = env.do(t, http.MethodPost, "/api/v1/assets/"+asset+"/timeline-tags",
		`{"timeMillis":2000,"name":"高潮"}`)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("无色创建期望 201，得到 %d", resp.StatusCode)
	}
	var plain gen.TimelineTag
	if err := json.NewDecoder(resp.Body).Decode(&plain); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	_ = resp.Body.Close()
	if plain.Color != nil {
		t.Fatalf("未设置 color 时响应应省略字段，得到 %q", *plain.Color)
	}
	// 非法 color → 400
	resp = env.do(t, http.MethodPost, "/api/v1/assets/"+asset+"/timeline-tags",
		`{"timeMillis":3000,"name":"x","color":"red"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("非法 color 期望 400，得到 %d", resp.StatusCode)
	}
	// GET 列表：有色者带 color、无色者省略
	resp = env.do(t, http.MethodGet, "/api/v1/assets/"+asset+"/timeline-tags", "")
	var tags []gen.TimelineTag
	if err := json.NewDecoder(resp.Body).Decode(&tags); err != nil {
		t.Fatalf("解析列表失败: %v", err)
	}
	_ = resp.Body.Close()
	if len(tags) != 2 {
		t.Fatalf("期望 2 条时间轴标签，得到 %d", len(tags))
	}
	if tags[0].Color == nil || *tags[0].Color != "#d6336c" {
		t.Fatalf("列表应回带 color=#d6336c，得到 %v", tags[0].Color)
	}
	if tags[1].Color != nil {
		t.Fatalf("列表无色条目应省略 color，得到 %q", *tags[1].Color)
	}
	// 更新：改名 + 改色 → 200 回带新值
	resp = env.do(t, http.MethodPut, "/api/v1/assets/"+asset+"/timeline-tags/"+*colored.Id,
		`{"timeMillis":1500,"name":"开场修订","color":"#0d6efd"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("更新期望 200，得到 %d", resp.StatusCode)
	}
	// 清除颜色（省略 color）→ 响应无 color
	resp = env.do(t, http.MethodPut, "/api/v1/assets/"+asset+"/timeline-tags/"+*colored.Id,
		`{"timeMillis":1500,"name":"开场修订"}`)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("清除色更新期望 200，得到 %d", resp.StatusCode)
	}
	var updated gen.TimelineTag
	if err := json.NewDecoder(resp.Body).Decode(&updated); err != nil {
		t.Fatalf("解析更新响应失败: %v", err)
	}
	_ = resp.Body.Close()
	if updated.Color != nil || updated.Name == nil || *updated.Name != "开场修订" || updated.TimeMillis == nil || *updated.TimeMillis != 1500 {
		t.Fatalf("清除色后响应应无 color 且字段为提交值: %+v", updated)
	}
	// 跨资产/不存在 tagId → 404
	other, _ := env.assetIDByName(t, "a.jpg")
	resp = env.do(t, http.MethodPut, "/api/v1/assets/"+other+"/timeline-tags/"+*colored.Id,
		`{"timeMillis":1,"name":"x"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("跨资产更新期望 404，得到 %d", resp.StatusCode)
	}
	resp = env.do(t, http.MethodPut, "/api/v1/assets/"+asset+"/timeline-tags/no-such-id",
		`{"timeMillis":1,"name":"x"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("不存在 tagId 期望 404，得到 %d", resp.StatusCode)
	}
}
