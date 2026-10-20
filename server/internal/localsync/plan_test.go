package localsync

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPlanScanFailureCannotPrunePreviousState(t *testing.T) {
	root := t.TempDir()
	for _, roots := range [][]string{{root}, {}} {
		scanRoot := root
		if len(roots) == 0 {
			scanRoot = filepath.Join(root, "missing")
		}
		plan, err := PlanScan(scanRoot, t.TempDir(), roots)
		if err == nil || plan.Alive != nil || plan.Entries != nil || plan.Ignored != nil {
			t.Fatalf("不安全或失败扫描必须无可执行计划: plan=%+v err=%v", plan, err)
		}
	}
}

func TestPlanScanKeepsIgnoredEntriesAlive(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"a.jpg", ".hidden"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("synthetic"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	plan, err := PlanScan(root, t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Entries) != 1 || len(plan.Ignored) != 1 || !plan.Alive["a.jpg"] || !plan.Alive[".hidden"] {
		t.Fatalf("忽略条目也应保留可见状态: %+v", plan)
	}
}
