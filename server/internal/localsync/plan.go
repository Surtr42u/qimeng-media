package localsync

import "fmt"

// ScanPlan 是一轮只读扫描的结果；只有安全校验和扫描均成功，调用方才能
// 用 Alive 清理运行态。错误时不返回部分计划，避免误删上轮状态。
type ScanPlan struct {
	Entries []Entry
	Ignored []Entry
	Alive   map[string]bool
}

// PlanScan 将根重叠校验、扫描和存活集合构建收敛在业务包；不写文件或数据库。
func PlanScan(root, dataDir string, libraryRoots []string) (ScanPlan, error) {
	if overlap := RootOverlapError(root, dataDir, libraryRoots); overlap != "" {
		return ScanPlan{}, fmt.Errorf("同步根与库根或数据目录重叠（%s）", overlap)
	}
	entries, ignored, err := ScanTree(root)
	if err != nil {
		return ScanPlan{}, fmt.Errorf("扫描同步根失败: %w", err)
	}
	alive := make(map[string]bool, len(entries)+len(ignored))
	for _, entry := range entries {
		alive[entry.RelPath] = true
	}
	for _, entry := range ignored {
		alive[entry.RelPath] = true
	}
	return ScanPlan{Entries: entries, Ignored: ignored, Alive: alive}, nil
}
