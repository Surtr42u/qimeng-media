package sysmon

import (
	"context"
	"runtime"

	"github.com/shirou/gopsutil/v4/mem"
)

// 自动扫描预算是保守估计：每个全库快照预留512MiB、两个可用CPU配一个扫描，最多8库。
// 不改变单库处理顺序，也不声称为NAS磁盘吞吐基准；失败回退单库。
const (
	scanWorkerMemoryBytes = 512 << 20
	autoScanWorkersMax    = 8
)

func AutoScanWorkers(ctx context.Context) int {
	vm, err := mem.VirtualMemoryWithContext(ctx)
	if err != nil {
		return 1
	}
	return scanWorkersForResources(runtime.GOMAXPROCS(0), vm.Available)
}

func scanWorkersForResources(cpus int, available uint64) int {
	cpuWorkers := max(1, cpus/2)
	memoryWorkers := max(uint64(1), available/scanWorkerMemoryBytes)
	return min(autoScanWorkersMax, cpuWorkers, int(min(uint64(autoScanWorkersMax), memoryWorkers)))
}
