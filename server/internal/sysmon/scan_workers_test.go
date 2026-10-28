package sysmon

import "testing"

func TestScanWorkersResourceBudget(t *testing.T) {
	for _, sample := range []struct {
		cpus   int
		memory uint64
		want   int
	}{{1, 8 << 30, 1}, {8, 1 << 30, 2}, {64, 128 << 30, 8}, {8, 0, 1}, {0, 0, 1}} {
		if got := scanWorkersForResources(sample.cpus, sample.memory); got != sample.want {
			t.Fatalf("cpu=%d available=%d got=%d want=%d", sample.cpus, sample.memory, got, sample.want)
		}
	}
}
