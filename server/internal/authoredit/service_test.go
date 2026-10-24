package authoredit

// service_test.go：Q1 事务行为回归（任务书附录 A.1）。真 SQLite（临时目录 +
// store.Open + store.Migrate 合成库）直测用例包，不经 HTTP——HTTP 层的协议
// 校验与状态码契约由 httpapi 既有矩阵测试锁定，这里锁定迁移后归本包持有的
// 事务不变量：
//   - 多步写入要么全成、要么全无（SQLite 触发器注入中途写失败，断言片段/
//     上传条目/关联/作者行全部回滚，不是「最终表对了但 TXT 已变」）；
//   - 镜像只在 Commit 成功后刷新：失败路径绝不触碰镜像文件；镜像目标不可
//     写不拖垮用例（尽力而为），且失败以日志留痕（可观察性）；
//   - 哨兵错误身份不被编排层吞改（COS/不存在/缺失资产/不支持库/身份冲突），
//     请求取消以 context.Canceled 传播且零副作用——不得被误映射为 4xx 哨兵。

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// testNow 固定时钟：与 authorattach 测试同款纪律（生产侧 Now 由 main 注入，
// 测试侧固定值保证时间戳可断言）。
var testNow = time.Date(2026, 10, 24, 12, 0, 0, 0, time.UTC)

// env 每个用例独立的临时合成库 + 被测服务。装配形态与生产 main 一致
// （attach/mirror/logger 同源注入，Now 固定）；日志进缓冲以断言可观察性。
type env struct {
	conn *sql.DB
	q    *db.Queries
	svc  *Service
	logs *bytes.Buffer
}

func newEnv(t *testing.T) *env {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() {
		if err := conn.Close(); err != nil {
			t.Logf("关闭测试库失败: %v", err)
		}
	})
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	var logs bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&logs, nil))
	q := db.New(conn)
	svc := New(Options{
		Conn: conn, Queries: q,
		Attach: &authorattach.Service{Logger: logger},
		Mirror: &authorattach.MirrorWriter{Logger: logger},
		Now:    func() time.Time { return testNow },
		Logger: logger,
	})
	return &env{conn: conn, q: q, svc: svc, logs: &logs}
}

// exec 在合成库上直接执行维护 SQL（仅测试用：注入/移除失败触发器）。
func (e *env) exec(t *testing.T, query string) {
	t.Helper()
	if _, err := e.conn.ExecContext(context.Background(), query); err != nil {
		t.Fatalf("执行 %q 失败: %v", query, err)
	}
}

// seedLibrary 建库（kind 决定挂靠能力，正常 "normal" / 不支持 "cos"）。
func seedLibrary(t *testing.T, q *db.Queries, id, kind string) {
	t.Helper()
	if _, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: id, Name: "测试库-" + id, RootPath: t.TempDir(), Kind: kind,
		CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("建库 %s 失败: %v", id, err)
	}
}

// seedAsset 建资产行：asset_authors 有外键，关联写入需要真实资产行
// （与 authorattach 测试同款前提）。
func seedAsset(t *testing.T, q *db.Queries, id, libID, name string) {
	t.Helper()
	if err := q.UpsertAsset(context.Background(), db.UpsertAssetParams{
		AssetID: id, LibraryID: libID, RelPath: name, FileName: name,
		MediaType: "image", SizeBytes: 1, Mtime: store.FormatTimestamp(testNow),
		CreatedAt: store.FormatTimestamp(testNow), UpdatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("建资产 %s 失败: %v", id, err)
	}
}

// seedAuthorRow 预置作者行（regular 或 cos）。
func seedAuthorRow(t *testing.T, q *db.Queries, id, display, typ string) {
	t.Helper()
	if err := q.UpsertAuthor(context.Background(), db.UpsertAuthorParams{
		ID: id, DisplayName: display, Type: typ,
		CreatedAt: store.FormatTimestamp(testNow), Origin: store.OriginClient,
	}); err != nil {
		t.Fatalf("预置作者 %s 失败: %v", id, err)
	}
}

// seedLink 预置资产-作者关联。
func seedLink(t *testing.T, q *db.Queries, assetID, authorID string) {
	t.Helper()
	if err := q.AddAssetAuthor(context.Background(), db.AddAssetAuthorParams{
		AssetID: assetID, AuthorID: authorID,
		CreatedAt: store.NullTimestamp(store.FormatTimestamp(testNow)),
		Origin:    store.OriginClient,
	}); err != nil {
		t.Fatalf("预置关联 %s-%s 失败: %v", assetID, authorID, err)
	}
}

// seedFragment 写入单片段（TXT 真相的合成形态）。
func seedFragment(t *testing.T, q *db.Queries, filename, content string) {
	t.Helper()
	if err := authorattach.PersistSources(context.Background(), q, testNow, []authorattach.Source{
		{Filename: filename, Content: content, ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段 %s 失败: %v", filename, err)
	}
}

// fragmentContents 读当前全部片段（回滚断言的比对基线）。
func fragmentContents(t *testing.T, q *db.Queries) []authorattach.Source {
	t.Helper()
	sources, err := authorattach.LoadSources(context.Background(), q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	return sources
}

// uploadEntries 读当前上传条目元数据（回滚断言的比对基线）。
func uploadEntries(t *testing.T, q *db.Queries) map[string][]authoring.UploadEntry {
	t.Helper()
	entries, err := authorattach.LoadUploadEntries(context.Background(), q)
	if err != nil {
		t.Fatalf("读取上传条目失败: %v", err)
	}
	return entries
}

// allAuthorIDs 当前作者表全部 id（排序后比较，与行序解耦）。
func allAuthorIDs(t *testing.T, q *db.Queries) []string {
	t.Helper()
	rows, err := q.ListAuthors(context.Background())
	if err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	ids := make([]string, 0, len(rows))
	for _, r := range rows {
		ids = append(ids, r.ID)
	}
	sort.Strings(ids)
	return ids
}

// assetAuthorIDs 资产当前关联的作者 id（排序后比较，与行序解耦）。
func assetAuthorIDs(t *testing.T, q *db.Queries, assetID string) []string {
	t.Helper()
	refs, err := q.ListAssetAuthorRefs(context.Background(), assetID)
	if err != nil {
		t.Fatalf("查询资产作者失败: %v", err)
	}
	ids := make([]string, 0, len(refs))
	for _, r := range refs {
		ids = append(ids, r.ID)
	}
	sort.Strings(ids)
	return ids
}

// sameUnordered 比较两个字符串切片的元素集合（忽略顺序）。
func sameUnordered(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	left := append([]string(nil), a...)
	right := append([]string(nil), b...)
	sort.Strings(left)
	sort.Strings(right)
	return reflect.DeepEqual(left, right)
}

// TestReplaceAssetAuthorsAtomicity：资产作者整体替换的事务不变量。
// 注入 asset_authors 写失败（触发器 RAISE）：片段已重写、新作者行已建、
// 旧关联已清空——全部必须回滚，镜像文件不被创建；移除触发器重试则片段
// 作品行、关联、新作者一起提交，镜像在 Commit 成功后刷新；再重试幂等
// 不重复（A.1 第 2-5 条）。
func TestReplaceAssetAuthorsAtomicity(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	seedLibrary(t, e.q, "lib-1", "normal")
	seedAsset(t, e.q, "asset-1", "lib-1", "f.jpg")
	idA := authoring.GenerateAuthorID("作者甲")
	idB := authoring.GenerateAuthorID("作者乙")
	seedAuthorRow(t, e.q, idA, "作者甲", authoring.AuthorTypeRegular)
	content := "1  作者甲\n来源\n站点A\n作品\nf.jpg\n"
	seedFragment(t, e.q, "f.txt", content)
	seedLink(t, e.q, "asset-1", idA)
	seededEntries := map[string][]authoring.UploadEntry{
		"f.txt": {{
			AuthorID: idA, DisplayName: "作者甲",
			Names: []string{"作者甲"}, Works: []string{"f.jpg"}, Sources: []string{"站点A"},
		}},
	}
	if err := authorattach.SaveUploadEntries(ctx, e.q, testNow, seededEntries); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}
	// 镜像指向真实可写路径但尚未存在：失败路径绝不创建/刷新它。
	mirrorPath := filepath.Join(t.TempDir(), "m.txt")
	if err := authorattach.SaveMirrorConfig(ctx, e.q, testNow, authorattach.MirrorConfig{Path: mirrorPath}); err != nil {
		t.Fatalf("预置镜像配置失败: %v", err)
	}

	e.exec(t, `CREATE TRIGGER boom_asset_authors_ins
BEFORE INSERT ON asset_authors
BEGIN SELECT RAISE(ABORT, 'injected asset_authors insert failure'); END`)

	if err := e.svc.ReplaceAssetAuthors(ctx, "asset-1", []string{idA}, []string{"作者乙"}); err == nil {
		t.Fatalf("注入关联写失败后期望报错")
	}
	// 整体回滚：片段、上传条目、关联、作者表、镜像文件全部保持原样。
	if got := fragmentContents(t, e.q); len(got) != 1 || got[0].Content != content {
		t.Fatalf("失败后片段被改动：\n%+v\nwant 内容不变:\n%s", got, content)
	}
	if got := uploadEntries(t, e.q); !reflect.DeepEqual(got, seededEntries) {
		t.Fatalf("失败后上传条目被改动：%+v", got)
	}
	if got := assetAuthorIDs(t, e.q, "asset-1"); !sameUnordered(got, []string{idA}) {
		t.Fatalf("失败后关联被改动：%v", got)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{idA}) {
		t.Fatalf("失败后新作者不应提交：%v", got)
	}
	if _, err := os.Stat(mirrorPath); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("失败路径不得刷新镜像：%v", err)
	}

	e.exec(t, `DROP TRIGGER boom_asset_authors_ins`)
	if err := e.svc.ReplaceAssetAuthors(ctx, "asset-1", []string{idA}, []string{"作者乙"}); err != nil {
		t.Fatalf("重试替换失败: %v", err)
	}
	// 一起成功：关联全集 = {甲, 乙}，新作者行已提交。
	if got := assetAuthorIDs(t, e.q, "asset-1"); !sameUnordered(got, []string{idA, idB}) {
		t.Fatalf("成功后关联=%v, want [%s %s]", got, idA, idB)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{idA, idB}) {
		t.Fatalf("成功后作者表=%v, want [%s %s]", got, idA, idB)
	}
	// 片段作品行：两个作者块都含 f.jpg（甲原有、乙新块）。
	sources := fragmentContents(t, e.q)
	if len(sources) != 1 {
		t.Fatalf("片段数=%d, want 1", len(sources))
	}
	worksByID := map[string][]string{}
	for _, block := range authoring.ParseAuthorBlocks(sources[0].Content) {
		if len(block.AuthorNames) == 0 {
			continue
		}
		worksByID[authoring.GenerateAuthorID(block.AuthorNames[0])] = block.Works
	}
	for _, id := range []string{idA, idB} {
		if !reflect.DeepEqual(worksByID[id], []string{"f.jpg"}) {
			t.Fatalf("作者 %s 的作品行=%v, want [f.jpg]（片段全文:\n%s）", id, worksByID[id], sources[0].Content)
		}
	}
	// 镜像在 Commit 成功后刷新，内容与片段真相逐字一致。
	mirror, err := os.ReadFile(mirrorPath)
	if err != nil {
		t.Fatalf("成功路径应刷新镜像: %v", err)
	}
	if string(mirror) != sources[0].Content {
		t.Fatalf("镜像内容与片段不一致：\n--- 镜像 ---\n%s\n--- 片段 ---\n%s", mirror, sources[0].Content)
	}

	// 重试不重复：关联/片段/条目零变化（幂等，A.1 第 4 条）。
	beforeFragment := fragmentContents(t, e.q)
	beforeEntries := uploadEntries(t, e.q)
	if err := e.svc.ReplaceAssetAuthors(ctx, "asset-1", []string{idA}, []string{"作者乙"}); err != nil {
		t.Fatalf("幂等重试失败: %v", err)
	}
	if got := assetAuthorIDs(t, e.q, "asset-1"); !sameUnordered(got, []string{idA, idB}) {
		t.Fatalf("幂等重试改变关联：%v", got)
	}
	if after := fragmentContents(t, e.q); !reflect.DeepEqual(after, beforeFragment) {
		t.Fatalf("幂等重试改变片段:\n%+v\n%+v", beforeFragment, after)
	}
	if after := uploadEntries(t, e.q); !reflect.DeepEqual(after, beforeEntries) {
		t.Fatalf("幂等重试改变条目元数据:\n%+v\n%+v", beforeEntries, after)
	}
}

// TestMirrorFailureIsBestEffortAfterCommit：镜像目标不可写（父目录不存在）
// 时事务仍提交、用例成功返回、提交数据可读；镜像失败以 Warn 日志留痕
// （可观察性，A.1 第 5、8 条——镜像绝不反向影响核心写入）。
func TestMirrorFailureIsBestEffortAfterCommit(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	seedLibrary(t, e.q, "lib-1", "normal")
	seedAsset(t, e.q, "asset-1", "lib-1", "f.jpg")
	idA := authoring.GenerateAuthorID("作者甲")
	seedAuthorRow(t, e.q, idA, "作者甲", authoring.AuthorTypeRegular)
	seedFragment(t, e.q, "f.txt", "1  作者甲\n来源\n站点A\n")
	// 合成不可写目标：目录不存在 → 镜像临时文件创建必败。
	badPath := filepath.Join(t.TempDir(), "no-such-dir", "m.txt")
	if err := authorattach.SaveMirrorConfig(ctx, e.q, testNow, authorattach.MirrorConfig{Path: badPath}); err != nil {
		t.Fatalf("预置镜像配置失败: %v", err)
	}

	saved, err := e.svc.ReplaceAuthorSources(ctx, idA, []string{"站点B"}, authorattach.SourcesModeReplace)
	if err != nil {
		t.Fatalf("镜像不可写不得拖垮用例: %v", err)
	}
	if !sameUnordered(saved, []string{"站点B"}) {
		t.Fatalf("replace 回显=%v, want [站点B]", saved)
	}
	// 提交数据可见：片段来源区已整体替换。
	sources := fragmentContents(t, e.q)
	if len(sources) != 1 {
		t.Fatalf("片段数=%d, want 1", len(sources))
	}
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 || !reflect.DeepEqual(blocks[0].Sources, []string{"站点B"}) {
		t.Fatalf("提交后来源区=%+v, want [站点B]（片段:\n%s）", blocks, sources[0].Content)
	}
	// 镜像失败必须以日志留痕（TextHandler 无最低级别门槛，Warn 可捕获）。
	if logText := e.logs.String(); !strings.Contains(logText, "作者镜像：写入失败") {
		t.Fatalf("镜像失败应以 Warn 日志留痕，实际日志：\n%s", logText)
	}
}

// TestAuthorManageWritesRollBackOnRowDeleteFailure：改名（身份迁移）与删除
// 的中途写失败整体回滚——触发器拦在流程末段的作者行删除上，此时 TXT 已在
// 事务内重写、（改名路径）新行已复制/关联已迁移，回滚必须全部还原，不得
// 出现「最终表对了但 TXT 已变」的半份状态（A.1 第 6 条）。
func TestAuthorManageWritesRollBackOnRowDeleteFailure(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	seedLibrary(t, e.q, "lib-1", "normal")
	seedAsset(t, e.q, "asset-1", "lib-1", "f.jpg")
	idA := authoring.GenerateAuthorID("作者甲")
	seedAuthorRow(t, e.q, idA, "作者甲", authoring.AuthorTypeRegular)
	content := "1  作者甲\n来源\n站点A\n作品\nf.jpg\n"
	seedFragment(t, e.q, "f.txt", content)
	seedLink(t, e.q, "asset-1", idA)
	seededEntries := map[string][]authoring.UploadEntry{
		"f.txt": {{AuthorID: idA, DisplayName: "作者甲", Names: []string{"作者甲"}, Works: []string{"f.jpg"}}},
	}
	if err := authorattach.SaveUploadEntries(ctx, e.q, testNow, seededEntries); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}

	e.exec(t, `CREATE TRIGGER boom_authors_del
BEFORE DELETE ON authors
BEGIN SELECT RAISE(ABORT, 'injected authors delete failure'); END`)

	assertUntouched := func(what string) {
		t.Helper()
		if got := fragmentContents(t, e.q); len(got) != 1 || got[0].Content != content {
			t.Fatalf("%s：片段被改动（TXT 已变即半份状态）：\n%+v", what, got)
		}
		if got := uploadEntries(t, e.q); !reflect.DeepEqual(got, seededEntries) {
			t.Fatalf("%s：上传条目被改动：%+v", what, got)
		}
		if got := assetAuthorIDs(t, e.q, "asset-1"); !sameUnordered(got, []string{idA}) {
			t.Fatalf("%s：关联被改动：%v", what, got)
		}
		if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{idA}) {
			t.Fatalf("%s：作者表被改动：%v", what, got)
		}
	}

	// 改名（身份迁移路径）：块重写 → 新行复制 → 关联迁移 → 删旧行失败。
	if _, err := e.svc.RenameAuthor(ctx, idA, "作者丙"); err == nil {
		t.Fatalf("注入作者行删除失败后改名应报错")
	}
	assertUntouched("改名中途失败")

	// 删除：块移除 → 条目清理 → 关联清理 → 作者行删除失败。
	if err := e.svc.DeleteAuthor(ctx, idA); err == nil {
		t.Fatalf("注入作者行删除失败后删除应报错")
	}
	assertUntouched("删除中途失败")

	e.exec(t, `DROP TRIGGER boom_authors_del`)
	// 恢复后删除成功：三清（TXT 块、关联、作者行），媒体/资产行保留。
	if err := e.svc.DeleteAuthor(ctx, idA); err != nil {
		t.Fatalf("恢复后删除失败: %v", err)
	}
	if sources := fragmentContents(t, e.q); len(sources) != 1 ||
		len(authoring.ParseAuthorBlocks(sources[0].Content)) != 0 {
		t.Fatalf("删除后片段应无作者块（片段:\n%+v）", sources)
	}
	if got := assetAuthorIDs(t, e.q, "asset-1"); len(got) != 0 {
		t.Fatalf("删除后关联应清空：%v", got)
	}
	if got := allAuthorIDs(t, e.q); len(got) != 0 {
		t.Fatalf("删除后作者表应清空：%v", got)
	}
}

// TestSentinelErrorIdentities：低层哨兵原样透传 + 本包新哨兵身份
// （A.1 第 7 条；HTTP 状态码映射由 httpapi 矩阵测试锁定，这里锁错误身份
// 不被编排层吞改）。
func TestSentinelErrorIdentities(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	seedLibrary(t, e.q, "lib-1", "normal")
	seedAsset(t, e.q, "asset-1", "lib-1", "f.jpg")
	idA := authoring.GenerateAuthorID("作者甲")
	idB := authoring.GenerateAuthorID("作者乙")
	seedAuthorRow(t, e.q, idA, "作者甲", authoring.AuthorTypeRegular)
	seedAuthorRow(t, e.q, idB, "作者乙", authoring.AuthorTypeRegular)
	// COS 作者行：id 恰为 GenerateAuthorID 的合法产物（httpapi 撞名契约同款）。
	seedAuthorRow(t, e.q, "cos_y2", "COS酱", authoring.AuthorTypeCos)
	content := "1  作者甲\n来源\n站点A\n"
	seedFragment(t, e.q, "f.txt", content)

	cases := []struct {
		name string
		call func() error
		want error
	}{
		{"来源区：COS 作者", func() error {
			_, err := e.svc.ReplaceAuthorSources(ctx, "cos_y2", []string{"x"}, authorattach.SourcesModeReplace)
			return err
		}, authorattach.ErrAuthorNotRegular},
		{"删除：COS 作者", func() error {
			return e.svc.DeleteAuthor(ctx, "cos_y2")
		}, authorattach.ErrAuthorNotRegular},
		{"改名：COS 作者", func() error {
			_, err := e.svc.RenameAuthor(ctx, "cos_y2", "新名")
			return err
		}, authorattach.ErrAuthorNotRegular},
		{"来源区：作者不存在", func() error {
			_, err := e.svc.ReplaceAuthorSources(ctx, "ghost-id", []string{"x"}, authorattach.SourcesModeReplace)
			return err
		}, authorattach.ErrAuthorNotFound},
		{"删除：作者不存在", func() error {
			return e.svc.DeleteAuthor(ctx, "ghost-id")
		}, authorattach.ErrAuthorNotFound},
		{"改名：作者不存在", func() error {
			_, err := e.svc.RenameAuthor(ctx, "ghost-id", "新名")
			return err
		}, authorattach.ErrAuthorNotFound},
		{"按名新建：撞 COS 行", func() error {
			_, err := e.svc.CreateAuthor(ctx, "cos_y2")
			return err
		}, authorattach.ErrAuthorIDConflict},
		{"改名：撞既有作者", func() error {
			_, err := e.svc.RenameAuthor(ctx, idA, "作者乙")
			return err
		}, authorattach.ErrAuthorIDConflict},
		{"资产作者替换：资产不存在", func() error {
			return e.svc.ReplaceAssetAuthors(ctx, "no-such-asset", []string{idA}, nil)
		}, ErrAssetNotFound},
		{"资产作者替换：ids 含未知作者", func() error {
			return e.svc.ReplaceAssetAuthors(ctx, "asset-1", []string{idA, "ghost-id"}, nil)
		}, authorattach.ErrAuthorNotFound},
	}
	for _, c := range cases {
		if err := c.call(); !errors.Is(err, c.want) {
			t.Errorf("%s：错误身份漂移：got %v, want %v", c.name, err, c.want)
		}
	}
	// 不支持挂靠的库类型：能力判定单一来源 scanner.SupportsAuthorAttach。
	seedLibrary(t, e.q, "lib-cos", "cos")
	seedAsset(t, e.q, "asset-cos", "lib-cos", "c.jpg")
	if err := e.svc.ReplaceAssetAuthors(ctx, "asset-cos", []string{idA}, nil); !errors.Is(err, ErrUnsupportedLibraryKind) {
		t.Errorf("cos 库资产替换：got %v, want %v", err, ErrUnsupportedLibraryKind)
	}
	// 冲突类失败零副作用：片段保持原样（不存在「冲突却写了半份」）。
	if got := fragmentContents(t, e.q); len(got) != 1 || got[0].Content != content {
		t.Fatalf("哨兵失败后片段被改动：\n%+v", got)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{idA, idB, "cos_y2"}) {
		t.Fatalf("哨兵失败后作者表被改动：%v", got)
	}
}

// TestCanceledContextPropagatesWithoutSideEffects：请求取消贯穿全部用例
// （Q1 第 7 条 / 4.3「context 贯穿请求查询」）：已取消的 context 传播为
// context.Canceled，不得被误映射为任何 4xx 哨兵语义，且零副作用。
func TestCanceledContextPropagatesWithoutSideEffects(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	seedLibrary(t, e.q, "lib-1", "normal")
	seedAsset(t, e.q, "asset-1", "lib-1", "f.jpg")
	idA := authoring.GenerateAuthorID("作者甲")
	seedAuthorRow(t, e.q, idA, "作者甲", authoring.AuthorTypeRegular)
	content := "1  作者甲\n来源\n站点A\n作品\nf.jpg\n"
	seedFragment(t, e.q, "f.txt", content)
	seedLink(t, e.q, "asset-1", idA)

	canceled, cancel := context.WithCancel(context.Background())
	cancel()

	calls := map[string]func() error{
		"EnsureSourceVocabulary": func() error {
			_, err := e.svc.EnsureSourceVocabulary(canceled)
			return err
		},
		"ReplaceAuthorSources": func() error {
			_, err := e.svc.ReplaceAuthorSources(canceled, idA, []string{"站点B"}, authorattach.SourcesModeReplace)
			return err
		},
		"CreateAuthor": func() error {
			_, err := e.svc.CreateAuthor(canceled, "新作者乙")
			return err
		},
		"RenameAuthor": func() error {
			_, err := e.svc.RenameAuthor(canceled, idA, "作者丙")
			return err
		},
		"DeleteAuthor": func() error {
			return e.svc.DeleteAuthor(canceled, idA)
		},
		"ReplaceAssetAuthors": func() error {
			return e.svc.ReplaceAssetAuthors(canceled, "asset-1", []string{idA}, []string{"新作者乙"})
		},
	}
	sentinels := []error{
		authorattach.ErrAuthorNotFound, authorattach.ErrAuthorNotRegular,
		authorattach.ErrAuthorIDConflict, authorattach.ErrInvalidAuthorName,
		authorattach.ErrDisplayNameIdentityMismatch,
		ErrAssetNotFound, ErrUnsupportedLibraryKind,
	}
	for name, call := range calls {
		err := call()
		if err == nil {
			t.Errorf("%s：已取消 context 期望报错", name)
			continue
		}
		if !errors.Is(err, context.Canceled) {
			t.Errorf("%s：取消应传播为 context.Canceled，got %v", name, err)
		}
		for _, sentinel := range sentinels {
			if errors.Is(err, sentinel) {
				t.Errorf("%s：取消被误映射为哨兵语义 %v（HTTP 层会错归 4xx）", name, sentinel)
			}
		}
	}
	// 零副作用：片段/关联/作者表保持原样。
	if got := fragmentContents(t, e.q); len(got) != 1 || got[0].Content != content {
		t.Fatalf("取消后片段被改动：\n%+v", got)
	}
	if got := assetAuthorIDs(t, e.q, "asset-1"); !sameUnordered(got, []string{idA}) {
		t.Fatalf("取消后关联被改动：%v", got)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{idA}) {
		t.Fatalf("取消后作者表被改动：%v", got)
	}
}

// TestEnsureSourceVocabularyPrefillOnce：词表预填用例——键不存在时从已
// 导入片段统计共用平台名一次性预填并落键；再次读取直接回放存储值（键存在
// 即用户/预填已表态，永不再预填）。
func TestEnsureSourceVocabularyPrefillOnce(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	// 两位作者共用「站点A」（无点号、非链接形态）；单人词「个人页」必须排除。
	seedFragment(t, e.q, "f.txt",
		"1  作者甲\n来源\n站点A\n个人页\n作品\nf.jpg\n2  作者乙\n来源\n站点A\n作品\ng.jpg\n")

	first, err := e.svc.EnsureSourceVocabulary(ctx)
	if err != nil {
		t.Fatalf("首次 Ensure 失败: %v", err)
	}
	if !reflect.DeepEqual(first, []string{"站点A"}) {
		t.Fatalf("预填结果=%v, want [站点A]（单人词排除）", first)
	}
	second, err := e.svc.EnsureSourceVocabulary(ctx)
	if err != nil {
		t.Fatalf("二次 Ensure 失败: %v", err)
	}
	if !reflect.DeepEqual(second, first) {
		t.Fatalf("二次读取=%v, want %v（键已落，直接回放）", second, first)
	}
	// 存储值与回放一致（键落库，非每次重算）。
	stored, err := authorattach.LoadSourceVocabulary(ctx, e.q)
	if err != nil {
		t.Fatalf("读取存储词表失败: %v", err)
	}
	if !reflect.DeepEqual(stored, first) {
		t.Fatalf("存储词表=%v, want %v", stored, first)
	}
	// 空片段库：无合格词不写键、不报错（空数组语义）。
	e2 := newEnv(t)
	empty, err := e2.svc.EnsureSourceVocabulary(ctx)
	if err != nil {
		t.Fatalf("空库 Ensure 失败: %v", err)
	}
	if len(empty) != 0 {
		t.Fatalf("空库预填=%v, want 空数组", empty)
	}
}
