package httpapi

// trash_move_failure_test.go：回收站三个搬运点（删除入站/恢复出站/回滚）
// 在 MoveFile 失败后的"目标端半截文件清理"行为（F2 根修，2026-10-01）。
//
// 失败注入靠包内替身 moveFile（真实跨卷复制中途失败无法用文件系统稳定
// 造出：EXDEV 要双挂载点、磁盘满不可造）；替身模拟"目标端写半截后报错、
// 源不动"的最常见失败形态，断言清理后目标端无残留、错误响应语义不变。

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"

	"qimeng-media/server/internal/filing"
)

// stubMoveFileFailAt 把包内替身 moveFile 换成"第 failOn 次调用失败"的版本：
// 失败时在目标端写半截副本（源字节数的一半）并返回注入错误，其余调用透传
// 真实现。原替身经 t.Cleanup 恢复。
func stubMoveFileFailAt(t *testing.T, failOn int) {
	t.Helper()
	orig := moveFile
	var calls atomic.Int32
	moveFile = func(src, dst string) error {
		if calls.Add(1) != int32(failOn) {
			return orig(src, dst)
		}
		b, err := os.ReadFile(src)
		if err != nil {
			return err
		}
		if err := os.WriteFile(dst, b[:len(b)/2], 0o644); err != nil {
			return err
		}
		return errors.New("注入的跨卷复制中途失败")
	}
	t.Cleanup(func() { moveFile = orig })
}

// filesUnder 返回 root 下的全部普通文件（root 不存在视为空）。
func filesUnder(t *testing.T, root string) []string {
	t.Helper()
	var out []string
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, werr error) error {
		if werr != nil {
			return werr
		}
		if !d.IsDir() {
			out = append(out, p)
		}
		return nil
	})
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		t.Fatalf("遍历 %s 失败: %v", root, err)
	}
	return out
}

// TestDeleteInboundMoveFailureCleansTrashHalfFile：删除入站失败 → 半截文件
// 不留在回收站（meta 未写，列表/清扫/指标对它全不可见，残留即幽灵文件），
// 库行保持原状（删除未生效），响应保持 500 语义。
func TestDeleteInboundMoveFailureCleansTrashHalfFile(t *testing.T) {
	env := newTestEnv(t)
	stubMoveFileFailAt(t, 1)

	resp := env.do(t, "DELETE", "/api/v1/assets/"+testFiles[0].id, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != 500 {
		t.Fatalf("删除入站失败应 500，得到 %d", resp.StatusCode)
	}

	got := filesUnder(t, filepath.Join(env.dataDir, filing.TrashRootName))
	if len(got) != 0 {
		t.Fatalf("入站失败后回收站不得残留任何文件（含半截），实际: %v", got)
	}
	if _, err := env.q.GetAsset(t.Context(), testFiles[0].id); err != nil {
		t.Fatalf("删除未生效，库行应保留: %v", err)
	}
}

// TestRestoreMoveFailureCleansLibraryHalfFile：恢复出站失败 → 半截文件不落
// 库内目标路径（否则重试恢复时残缺件与完整件并存、扫描器把残缺件注册成
// 坏资产）；meta 与回收站文件保留（用户可重试恢复），响应保持 500 语义。
func TestRestoreMoveFailureCleansLibraryHalfFile(t *testing.T) {
	env := newTestEnv(t)
	// 先真实删除一次，制造一条带 meta 的回收站条目。
	resp := env.do(t, "DELETE", "/api/v1/assets/"+testFiles[0].id, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != 200 {
		t.Fatalf("前置删除应 200，得到 %d", resp.StatusCode)
	}
	entries, err := env.s.listTrash()
	if err != nil || len(entries) != 1 {
		t.Fatalf("前置删除后应有且仅有 1 条回收站条目: n=%d err=%v", len(entries), err)
	}
	e := entries[0]

	stubMoveFileFailAt(t, 1)
	resp2 := env.do(t, "POST", "/api/v1/trash/"+e.id+"/restore", "")
	defer func() { _ = resp2.Body.Close() }()
	if resp2.StatusCode != 500 {
		t.Fatalf("恢复出站失败应 500，得到 %d", resp2.StatusCode)
	}

	if _, err := os.Stat(filepath.Join(env.media, testFiles[0].relPath)); !os.IsNotExist(err) {
		t.Fatalf("库内目标路径不得残留半截文件, stat err=%v", err)
	}
	if _, err := os.Stat(e.metaFile); err != nil {
		t.Fatalf("meta 必须保留（重试恢复的真相源）: %v", err)
	}
	if _, err := os.Stat(e.file); err != nil {
		t.Fatalf("回收站文件本体必须保留（失败时源未动）: %v", err)
	}
}

// TestRollbackFailureCleansLibraryHalfFile：meta 写失败触发回滚、回滚移动
// 再失败 → 库内原路径上的回滚半截副本被清理（否则扫描器把它注册成坏
// 资产）；回收站完整副本宁留勿删（此时是唯一完好副本），库行未删。
func TestRollbackFailureCleansLibraryHalfFile(t *testing.T) {
	env := newTestEnv(t)
	asset := testFiles[1] // b.jpg：与入站失败用例隔离，互不干扰
	// 与 handler 同参预写 meta 路径为目录：writeTrashMeta 必然失败，触发回滚
	// （fakeClock 定死时间，TrashPathFor 的 stamp 可复算）。
	trashFile, metaFile, err := filing.TrashPathFor(env.dataDir, asset.relPath, env.clock.Now(), asset.id)
	if err != nil {
		t.Fatalf("复算回收站路径失败: %v", err)
	}
	if err := os.MkdirAll(metaFile, 0o755); err != nil {
		t.Fatalf("预置 meta 路径为目录失败: %v", err)
	}
	// 第 1 次调用（入站）透传真实现成功搬运；第 2 次（回滚）注入失败+半截。
	stubMoveFileFailAt(t, 2)

	resp := env.do(t, "DELETE", "/api/v1/assets/"+asset.id, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != 500 {
		t.Fatalf("meta 写失败应 500，得到 %d", resp.StatusCode)
	}

	if _, err := os.Stat(filepath.Join(env.media, asset.relPath)); !os.IsNotExist(err) {
		t.Fatalf("库内回滚半截副本应被清理, stat err=%v", err)
	}
	if _, err := os.Stat(trashFile); err != nil {
		t.Fatalf("回收站完整副本必须保留（回滚失败时的唯一完好拷贝）: %v", err)
	}
	if _, err := env.q.GetAsset(t.Context(), asset.id); err != nil {
		t.Fatalf("删除未生效，库行应保留: %v", err)
	}
}
