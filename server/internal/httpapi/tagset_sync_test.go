// tagset_sync_test.go：§10 标签组同步语义测试（2026-09-20 用户拍板）。
// 覆盖：assets.tag_set_updated_at 随动（替换式 PUT / 单关联解绑 / 删除标签级联），
// 导入端「备份较新整体替换 / 其余并集合并」判定，重导幂等（备份时间==库内时间
// 落回并集路径），导出回带 tagsUpdatedAtMillis。
package httpapi

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedAssetWithTag 库内侧造数：资产（fileName 即备份匹配键）+ 已挂标签 +
// 标签组改动时间置为毫秒 tagSetMs（0 = 保持 '' 哨兵，模拟未知/旧数据）。
func seedAssetWithTag(t *testing.T, e *testEnv, assetID, fileName, tagName string, tagSetMs int64) string {
	t.Helper()
	now := e.clock.Now().Format(time.RFC3339Nano)
	if _, err := e.q.UpsertAsset(t.Context(), db.UpsertAssetParams{
		AssetID: assetID, LibraryID: e.libID,
		RelPath: "sync/" + fileName, FileName: fileName,
		MediaType: "image", SizeBytes: 10, Mtime: now, CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("造资产失败: %v", err)
	}
	tagID := "tag-" + tagName
	if _, err := e.q.CreateTag(t.Context(), db.CreateTagParams{ID: tagID, Name: tagName, CreatedAt: now}); err != nil {
		t.Fatalf("造标签失败: %v", err)
	}
	if err := e.q.AddAssetTag(t.Context(), db.AddAssetTagParams{AssetID: assetID, TagID: tagID, CreatedAt: now}); err != nil {
		t.Fatalf("挂标签失败: %v", err)
	}
	if tagSetMs > 0 {
		if err := e.q.TouchAssetTagSet(t.Context(), db.TouchAssetTagSetParams{
			AssetID: assetID, TagSetUpdatedAt: store.FormatTimestamp(time.UnixMilli(tagSetMs)),
		}); err != nil {
			t.Fatalf("置标签组时间失败: %v", err)
		}
	}
	return tagID
}

// assetTagNames 查资产的标签名集合（替换/并集断言用）。
func assetTagNames(t *testing.T, e *testEnv, assetID string) map[string]bool {
	t.Helper()
	rows, err := e.conn.Query(`SELECT t.name FROM asset_tags at JOIN tags t ON t.id = at.tag_id WHERE at.asset_id = ?`, assetID)
	if err != nil {
		t.Fatalf("查资产标签失败: %v", err)
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			t.Fatalf("扫描标签名失败: %v", err)
		}
		out[n] = true
	}
	return out
}

// tagSetTimeRaw 查标签组改动时间原值（'' = 未知哨兵）。
func tagSetTimeRaw(t *testing.T, e *testEnv, assetID string) string {
	t.Helper()
	var raw string
	if err := e.conn.QueryRow(`SELECT tag_set_updated_at FROM assets WHERE asset_id = ?`, assetID).Scan(&raw); err != nil {
		t.Fatalf("查标签组时间失败: %v", err)
	}
	return raw
}

// tagsBackup 构造只带标签段的最小备份请求。
func tagsBackup(ms int64, tag string, fileName string) gen.LegacyBackupImport {
	return gen.LegacyBackupImport{
		Format: "qimeng_backup", SchemaVersion: 1, AppIdentifier: "com.qimeng.media",
		ExportedAtMillis: ptr(int64(1756400000000)),
		Data: gen.LegacyBackupData{
			MediaFiles: &[]gen.LegacyMediaFile{
				{RecordKey: fileName, FileName: fileName, MediaType: "image", SizeBytes: 1,
					ModifiedAtMillis: 1, TagsUpdatedAtMillis: ptr(int64(ms))},
			},
			Tags:         &[]gen.LegacyTag{{Name: tag}},
			MediaTagRefs: &[]gen.LegacyMediaTagRef{{RecordKey: fileName, TagName: tag}},
		},
	}
}

// TestTagSync_newerBackupReplaces：备份时间较新 → 整体替换（备份独有标签在、
// 库内独有标签清、库内时间改写为备份时间、替换计数上报）。
func TestTagSync_newerBackupReplaces(t *testing.T) {
	e := newTestEnv(t)
	assetID := "01900000-0000-7000-8000-00000000ca01"
	seedAssetWithTag(t, e, assetID, "x.jpg", "库内旧", 1000)

	code, res := importBackup(t, e, tagsBackup(2000, "备份新", "x.jpg"))
	if code != http.StatusOK {
		t.Fatalf("导入应 200，got %d", code)
	}
	if got := derefVal(res.AssetsTagsReplaced); got != 1 {
		t.Errorf("AssetsTagsReplaced = %d, want 1", got)
	}
	got := assetTagNames(t, e, assetID)
	if len(got) != 1 || !got["备份新"] {
		t.Errorf("替换后标签 = %v, want 仅 [备份新]", got)
	}
	raw := tagSetTimeRaw(t, e, assetID)
	ms, err := millisOf(raw)
	if err != nil || ms != 2000 {
		t.Errorf("标签组时间 = %q (ms=%d, err=%v), want 2000ms", raw, ms, err)
	}
}

// TestTagSync_olderBackupStaysUnion：备份时间较旧 → 并集合并（只增不删），
// 库内时间不被改写、不计数替换。
func TestTagSync_olderBackupStaysUnion(t *testing.T) {
	e := newTestEnv(t)
	assetID := "01900000-0000-7000-8000-00000000ca02"
	seedAssetWithTag(t, e, assetID, "y.jpg", "库内新", 5000)

	code, res := importBackup(t, e, tagsBackup(2000, "备份旧", "y.jpg"))
	if code != http.StatusOK {
		t.Fatalf("导入应 200，got %d", code)
	}
	if got := derefVal(res.AssetsTagsReplaced); got != 0 {
		t.Errorf("AssetsTagsReplaced = %d, want 0（备份较旧不替换）", got)
	}
	got := assetTagNames(t, e, assetID)
	if len(got) != 2 || !got["库内新"] || !got["备份旧"] {
		t.Errorf("并集后标签 = %v, want [库内新 备份旧]", got)
	}
	if ms, err := millisOf(tagSetTimeRaw(t, e, assetID)); err != nil || ms != 5000 {
		t.Errorf("库内时间被改写: ms=%d err=%v, want 5000", ms, err)
	}
}

// TestTagSync_unknownTimeFallsBackToUnion：任一侧未知（库内 '' 哨兵）→ 回退
// 并集合并，库内时间保持 ''（不造假版本，§10）。
func TestTagSync_unknownTimeFallsBackToUnion(t *testing.T) {
	e := newTestEnv(t)
	assetID := "01900000-0000-7000-8000-00000000ca03"
	seedAssetWithTag(t, e, assetID, "z.jpg", "库内A", 0)

	code, res := importBackup(t, e, tagsBackup(9999, "备份B", "z.jpg"))
	if code != http.StatusOK {
		t.Fatalf("导入应 200，got %d", code)
	}
	if got := derefVal(res.AssetsTagsReplaced); got != 0 {
		t.Errorf("AssetsTagsReplaced = %d, want 0（库内时间未知不替换）", got)
	}
	got := assetTagNames(t, e, assetID)
	if len(got) != 2 || !got["库内A"] || !got["备份B"] {
		t.Errorf("并集后标签 = %v, want [库内A 备份B]", got)
	}
	if raw := tagSetTimeRaw(t, e, assetID); raw != "" {
		t.Errorf("库内时间应保持 '' 哨兵，got %q", raw)
	}
}

// TestTagSync_reimportSameBackupIdempotent：备份较新替换后同备份重导——
// 备份时间 == 库内时间落回并集路径（ON CONFLICT DO NOTHING），状态不再变化。
func TestTagSync_reimportSameBackupIdempotent(t *testing.T) {
	e := newTestEnv(t)
	assetID := "01900000-0000-7000-8000-00000000ca04"
	seedAssetWithTag(t, e, assetID, "w.jpg", "库内W", 1000)

	if code, res := importBackup(t, e, tagsBackup(2000, "备份W", "w.jpg")); code != http.StatusOK || derefVal(res.AssetsTagsReplaced) != 1 {
		t.Fatalf("首次导入应替换 1 资产，code=%d replaced=%d", code, derefVal(res.AssetsTagsReplaced))
	}
	code, res := importBackup(t, e, tagsBackup(2000, "备份W", "w.jpg"))
	if code != http.StatusOK {
		t.Fatalf("重导应 200，got %d", code)
	}
	if got := derefVal(res.AssetsTagsReplaced); got != 0 {
		t.Errorf("重导 AssetsTagsReplaced = %d, want 0（时间相等落回并集）", got)
	}
	if got := assetTagNames(t, e, assetID); len(got) != 1 || !got["备份W"] {
		t.Errorf("重导后标签 = %v, want 仅 [备份W]（不翻倍）", got)
	}
}

// TestTagSync_exportCarriesTagSetTime：导出回带 tagsUpdatedAtMillis；
// 无时间资产（'' 哨兵）该字段缺省（旧版 App 容错读取）。
func TestTagSync_exportCarriesTagSetTime(t *testing.T) {
	e := newTestEnv(t)
	var assetID, fileName string
	if err := e.conn.QueryRow(`SELECT asset_id, file_name FROM assets ORDER BY file_name LIMIT 1`).Scan(&assetID, &fileName); err != nil {
		t.Fatalf("取测试资产失败: %v", err)
	}
	// 给既有资产挂标签（不置时间；资产已存在，不能重复造数）
	now := e.clock.Now().Format(time.RFC3339Nano)
	if _, err := e.q.CreateTag(t.Context(), db.CreateTagParams{ID: "tag-export", Name: "导出标签", CreatedAt: now}); err != nil {
		t.Fatalf("造标签失败: %v", err)
	}
	if err := e.q.AddAssetTag(t.Context(), db.AddAssetTagParams{AssetID: assetID, TagID: "tag-export", CreatedAt: now}); err != nil {
		t.Fatalf("挂标签失败: %v", err)
	}
	// 先导出：字段应缺省
	resp := e.do(t, http.MethodGet, "/api/v1/export/qimeng-backup", "")
	var file gen.LegacyBackupFile
	if err := json.NewDecoder(resp.Body).Decode(&file); err != nil {
		t.Fatalf("解析导出失败: %v", err)
	}
	resp.Body.Close()
	for _, f := range *file.Data.MediaFiles {
		if f.RecordKey == fileName && f.TagsUpdatedAtMillis != nil {
			t.Fatalf("未置时间资产导出应缺省 tagsUpdatedAtMillis，got %d", *f.TagsUpdatedAtMillis)
		}
	}
	// 置时间后再导出：字段应回带同值
	const ms = int64(123456789)
	if err := e.q.TouchAssetTagSet(t.Context(), db.TouchAssetTagSetParams{
		AssetID: assetID, TagSetUpdatedAt: store.FormatTimestamp(time.UnixMilli(ms)),
	}); err != nil {
		t.Fatalf("置时间失败: %v", err)
	}
	resp = e.do(t, http.MethodGet, "/api/v1/export/qimeng-backup", "")
	var file2 gen.LegacyBackupFile
	if err := json.NewDecoder(resp.Body).Decode(&file2); err != nil {
		t.Fatalf("解析二次导出失败: %v", err)
	}
	resp.Body.Close()
	found := false
	for _, f := range *file2.Data.MediaFiles {
		if f.RecordKey != fileName {
			continue
		}
		found = true
		if f.TagsUpdatedAtMillis == nil || *f.TagsUpdatedAtMillis != ms {
			t.Fatalf("导出 tagsUpdatedAtMillis = %v, want %d", f.TagsUpdatedAtMillis, ms)
		}
	}
	if !found {
		t.Fatalf("导出未包含资产 %s", fileName)
	}
}

// TestTagSync_handlerMutationsBumpTime：运行时标签组变更路径全部随动——
// 替换式 PUT、单关联解绑、删除标签级联清关联。先置哨兵时间，变更后断言
// 离开哨兵（不依赖测试时钟推进）。
func TestTagSync_handlerMutationsBumpTime(t *testing.T) {
	e := newTestEnv(t)
	assetID := "01900000-0000-7000-8000-00000000ca05"
	seedAssetWithTag(t, e, assetID, "h.jpg", "哨兵前", 0)
	const sentinel = "2000-01-01T00:00:00Z"
	setSentinel := func() {
		t.Helper()
		if err := e.q.TouchAssetTagSet(t.Context(), db.TouchAssetTagSetParams{AssetID: assetID, TagSetUpdatedAt: sentinel}); err != nil {
			t.Fatalf("置哨兵失败: %v", err)
		}
	}

	// 替换式 PUT：同资产新建+挂另一标签
	if _, err := e.q.CreateTag(t.Context(), db.CreateTagParams{ID: "tag-put", Name: "PUT标签", CreatedAt: sentinel}); err != nil {
		t.Fatalf("造 PUT 标签失败: %v", err)
	}
	setSentinel()
	resp := e.do(t, http.MethodPut, "/api/v1/assets/"+assetID+"/tags", `{"tagIds":["tag-put"]}`)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("PUT 标签应 204，got %d", resp.StatusCode)
	}
	if raw := tagSetTimeRaw(t, e, assetID); raw == sentinel {
		t.Error("PUT 替换后标签组时间未随动")
	}

	// 单关联解绑：先重置哨兵，DELETE 单标签
	setSentinel()
	resp = e.do(t, http.MethodDelete, "/api/v1/assets/"+assetID+"/tags/PUT标签", "")
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("DELETE 单标签应 204，got %d", resp.StatusCode)
	}
	if raw := tagSetTimeRaw(t, e, assetID); raw == sentinel {
		t.Error("单解绑后标签组时间未随动")
	}

	// 删除标签级联清关联：重挂回再从池里删
	if err := e.q.AddAssetTag(t.Context(), db.AddAssetTagParams{AssetID: assetID, TagID: "tag-哨兵前", CreatedAt: sentinel}); err != nil {
		t.Fatalf("重挂标签失败: %v", err)
	}
	setSentinel()
	resp = e.do(t, http.MethodDelete, "/api/v1/tags/tag-哨兵前", "")
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("DELETE 池标签应 204，got %d", resp.StatusCode)
	}
	if raw := tagSetTimeRaw(t, e, assetID); raw == sentinel {
		t.Error("删除标签级联清关联后标签组时间未随动")
	}
}
