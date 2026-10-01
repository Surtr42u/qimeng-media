package httpapi

// import_txtfrag_test.go：备份载荷并入 TXT 片段（DOMAIN_RULES §10「TXT 片段」）
// 六用例：导出含片段（逐字）、新片段导入落点、同名同内容幂等跳过、keep
// 保护不冲掉上传写入条目、旧备份无该段零处理、txtFragments 先于
// authorMediaRefs 处理（关联不被统一重建冲掉）。复用既有夹具：newTestEnv
// 假扫描入库 a.jpg/b.jpg/c.mp4，importBackup/exportBackup/importTXT/
// fragmentOf/seedUploadEntry 等见各定义文件。

import (
	"context"
	"net/http"
	"testing"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
)

// newBackupReq 组装最小合法备份信封（format 校验通过即可，段按用例填）。
func newBackupReq(data gen.LegacyBackupData) gen.LegacyBackupImport {
	return gen.LegacyBackupImport{
		Format: "qimeng_backup", SchemaVersion: 1, AppIdentifier: "com.qimeng.media",
		ExportedAtMillis: ptr(int64(1756400000000)),
		Data:             data,
	}
}

// TestBackupExport_txtFragments（用例 1）：先经 /authors/import-txt 导入一个
// 片段 → GET /export/qimeng-backup → txtFragments 含该片段，filename/content
// 逐字一致且 importedAtMillis > 0。
func TestBackupExport_txtFragments(t *testing.T) {
	env := newTestEnv(t)
	content := "1  备份作者\n作品\na.jpg\n"
	importTXT(t, env, "备份清单.txt", content)

	_, file := exportBackup(t, env)
	if file.Data.TxtFragments == nil {
		t.Fatal("txtFragments 段应恒导出（空库为空数组）")
	}
	frags := *file.Data.TxtFragments
	if len(frags) != 1 {
		t.Fatalf("txtFragments 应恰含 1 条：%+v", frags)
	}
	if frags[0].Filename != "备份清单.txt" || frags[0].Content != content {
		t.Fatalf("片段应逐字导出：got %+v", frags[0])
	}
	if frags[0].ImportedAtMillis <= 0 {
		t.Fatalf("importedAtMillis 应 > 0：%d", frags[0].ImportedAtMillis)
	}
}

// TestImportBackup_txtFragmentNew（用例 2）：全新库导入携带 txtFragments 的
// 备份 → 片段落 kv、作者已建并关联同名资产、响应 imported=1/skipped=0。
func TestImportBackup_txtFragmentNew(t *testing.T) {
	env := newTestEnv(t) // 假扫描入库 a.jpg/b.jpg/c.mp4（片段作品行的匹配域）
	content := "1  片段作者\n作品\na.jpg\n"
	code, res := importBackup(t, env, newBackupReq(gen.LegacyBackupData{
		TxtFragments: &[]gen.LegacyTxtFragment{
			{Filename: "新片段.txt", Content: content, ImportedAtMillis: 1756400000000},
		},
	}))
	if code != http.StatusOK {
		t.Fatalf("备份导入应 200，got %d", code)
	}
	assertCount(t, "txtFragmentsImported", res.TxtFragmentsImported, 1)
	assertCount(t, "txtFragmentsSkipped", res.TxtFragmentsSkipped, 0)

	// kv 落点：片段逐字入库。
	if got, ok := fragmentOf(t, env, "新片段.txt"); !ok || got != content {
		t.Fatalf("片段应逐字落 kv：ok=%v got %q", ok, got)
	}
	// 统一重建生效：作者已建且关联 a.jpg。
	aid := authoring.GenerateAuthorID("片段作者")
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 1 {
		t.Fatalf("片段作者 fileCount=%v, want 1", a.FileCount)
	}
	if got := authorRefAsset(t, env, aid); got != queryAssetID(t, env, "a.jpg") {
		t.Fatalf("片段作者应关联 a.jpg 资产，got %q", got)
	}
}

// TestImportBackup_txtFragmentIdempotent（用例 3）：同一备份连导两次——第二次
// imported=0/skipped=1，片段内容不变。
func TestImportBackup_txtFragmentIdempotent(t *testing.T) {
	env := newTestEnv(t)
	content := "1  幂等作者\n作品\na.jpg\n"
	req := newBackupReq(gen.LegacyBackupData{
		TxtFragments: &[]gen.LegacyTxtFragment{
			{Filename: "幂等.txt", Content: content, ImportedAtMillis: 1756400000000},
		},
	})
	if code, _ := importBackup(t, env, req); code != http.StatusOK {
		t.Fatalf("首次导入应 200，got %d", code)
	}
	before, ok := fragmentOf(t, env, "幂等.txt")
	if !ok || before != content {
		t.Fatalf("首次导入片段应落库：ok=%v %q", ok, before)
	}

	code, res := importBackup(t, env, req)
	if code != http.StatusOK {
		t.Fatalf("重复导入应 200，got %d", code)
	}
	assertCount(t, "txtFragmentsImported-2nd", res.TxtFragmentsImported, 0)
	assertCount(t, "txtFragmentsSkipped-2nd", res.TxtFragmentsSkipped, 1)
	after, ok := fragmentOf(t, env, "幂等.txt")
	if !ok || after != before {
		t.Fatalf("跳过路径不得改动片段：ok=%v %q", ok, after)
	}
}

// TestImportBackup_txtFragmentKeepProtectsUploadEntries（用例 4）：目标端先有
// 「上传写入条目」（存量模拟，seedUploadEntry），备份片段内容缺该条目 →
// keep 语义并回，导入后条目行仍在片段中、关联保留。
func TestImportBackup_txtFragmentKeepProtectsUploadEntries(t *testing.T) {
	env := newTestEnv(t)
	x := authoring.GenerateAuthorID("保护作者")
	stale := "1  保护作者\n作品\na.jpg\n"
	importTXT(t, env, "保护清单.txt", stale)

	// 目标端增量：f.jpg 挂靠写入片段 + 存量上传条目（重导入保护的比对输入）。
	d := uploadOne(t, env, "f.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{x}); resp.StatusCode != http.StatusOK {
		t.Fatalf("编辑挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	seedUploadEntry(t, env, "保护清单.txt", authoring.UploadEntry{
		AuthorID: x, DisplayName: "保护作者", Names: []string{"保护作者"}, Works: []string{"f.jpg"},
	})

	// 备份片段 = 旧内容（缺 f.jpg 行）：同名内容不同 → keep 替换并回条目。
	code, res := importBackup(t, env, newBackupReq(gen.LegacyBackupData{
		TxtFragments: &[]gen.LegacyTxtFragment{
			{Filename: "保护清单.txt", Content: stale, ImportedAtMillis: 1756400000000},
		},
	}))
	if code != http.StatusOK {
		t.Fatalf("备份导入应 200，got %d", code)
	}
	assertCount(t, "txtFragmentsImported", res.TxtFragmentsImported, 1) // 内容不同 → 替换
	assertCount(t, "txtFragmentsSkipped", res.TxtFragmentsSkipped, 0)

	content, ok := fragmentOf(t, env, "保护清单.txt")
	if !ok {
		t.Fatal("片段应存在")
	}
	if !containsLine(content, "f.jpg") {
		t.Fatalf("keep 应并回上传写入条目 f.jpg 行：\n%s", content)
	}
	if works, _ := blockWorksOf(t, content, x); len(works) != 2 || works[0] != "a.jpg" || works[1] != "f.jpg" {
		t.Fatalf("keep 后片段作品行=%v, want [a.jpg f.jpg]", works)
	}
	// 关联保留：a.jpg（既有）+ f.jpg（挂靠）都不被重建冲掉。
	if a := findAuthor(t, listAuthors(t, env), x); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("keep 后 fileCount=%v, want 2（关联保留）", a.FileCount)
	}
}

// TestImportBackup_txtFragmentAbsentBackCompat（用例 5）：无 txtFragments 字段
// 的旧备份 → 导入成功、片段计数缺省（nil）、kv 不产生片段。
func TestImportBackup_txtFragmentAbsentBackCompat(t *testing.T) {
	env := newTestEnv(t)
	code, res := importBackup(t, env, newBackupReq(gen.LegacyBackupData{
		MediaFiles: &[]gen.LegacyMediaFile{
			{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", SizeBytes: 100, ModifiedAtMillis: 1},
		},
	}))
	if code != http.StatusOK {
		t.Fatalf("旧备份导入应 200，got %d", code)
	}
	if res.TxtFragmentsImported != nil || res.TxtFragmentsSkipped != nil {
		t.Fatalf("旧备份无该段，片段计数应缺省：%+v %+v", res.TxtFragmentsImported, res.TxtFragmentsSkipped)
	}
	sources, err := authorattach.LoadSources(context.Background(), env.q)
	if err != nil || len(sources) != 0 {
		t.Fatalf("旧备份导入不应产生片段：sources=%v err=%v", sources, err)
	}
}

// TestImportBackup_txtFragmentBeforeAuthorRefs（用例 6，顺序保护关键用例）：
// 备份同时含 txtFragments 与 authorMediaRefs → 导入完成后备份关联仍存在
// （片段统一重建在先，不冲掉后处理的 authorMediaRefs）。
func TestImportBackup_txtFragmentBeforeAuthorRefs(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("顺序作者")
	code, res := importBackup(t, env, newBackupReq(gen.LegacyBackupData{
		// mediaFiles 提供 recordKey→asset 映射（authorMediaRefs 的挂载前提）。
		MediaFiles: &[]gen.LegacyMediaFile{
			{RecordKey: "c.mp4", FileName: "c.mp4", MediaType: "video", SizeBytes: 100, ModifiedAtMillis: 1},
		},
		TxtFragments: &[]gen.LegacyTxtFragment{
			{Filename: "顺序.txt", Content: "1  顺序作者\n作品\nb.jpg\n", ImportedAtMillis: 1756400000000},
		},
		Authors: &[]gen.LegacyAuthor{
			{AuthorId: aid, DisplayName: "顺序作者", CreatedAtMillis: ptr(int64(1000))},
		},
		AuthorMediaRefs: &[]gen.LegacyAuthorMediaRef{
			{AuthorId: aid, RecordKey: "c.mp4", FileName: "c.mp4"},
		},
	}))
	if code != http.StatusOK {
		t.Fatalf("备份导入应 200，got %d", code)
	}
	assertCount(t, "txtFragmentsImported", res.TxtFragmentsImported, 1)
	assertCount(t, "authorRefsImported", res.AuthorRefsImported, 1)

	// 片段重建贡献 b.jpg 关联、备份 authorMediaRefs 贡献 c.mp4 关联——
	// 两者必须同时在场（顺序颠倒时统一重建会清掉 c.mp4 关联）。
	got := authorLinkFiles(t, env, aid)
	if len(got) != 2 || got[0] != "b.jpg" || got[1] != "c.mp4" {
		t.Fatalf("顺序作者关联=%v, want [b.jpg c.mp4]（备份关联不被片段重建冲掉）", got)
	}
}

// authorLinkFiles 查作者全部关联资产的文件名（升序，顺序保护用例断言用）。
func authorLinkFiles(t *testing.T, e *testEnv, authorID string) []string {
	t.Helper()
	rows, err := e.conn.Query(`SELECT a.file_name FROM asset_authors aa
		JOIN assets a ON a.asset_id = aa.asset_id WHERE aa.author_id = ?
		ORDER BY a.file_name`, authorID)
	if err != nil {
		t.Fatalf("查作者关联文件失败: %v", err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatalf("扫描关联文件失败: %v", err)
		}
		out = append(out, name)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("遍历关联文件失败: %v", err)
	}
	return out
}
