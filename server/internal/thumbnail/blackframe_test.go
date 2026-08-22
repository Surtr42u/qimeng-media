package thumbnail

import (
	"bytes"
	"reflect"
	"testing"
	"time"
)

// TestIsBlackFrameBoundaries 锁定黑帧判定的全部边界语义（DOMAIN_RULES §11 逐字遵守：
// 采样 100 像素亮度 <15 判黑；15 本身不小于 15，不判黑）。
func TestIsBlackFrameBoundaries(t *testing.T) {
	cases := []struct {
		name    string
		samples []byte
		want    bool
	}{
		{"全黑", bytes.Repeat([]byte{0}, 100), true},
		{"全黑贴近阈值（14）", bytes.Repeat([]byte{14}, 100), true},
		{"恰好 15 不判黑", bytes.Repeat([]byte{15}, 100), false},
		{"全白", bytes.Repeat([]byte{255}, 100), false},
		{"99 黑 1 亮不判黑", append(bytes.Repeat([]byte{0}, 99), 200), false},
		{"100 像素仅 1 黑不判黑", append(bytes.Repeat([]byte{200}, 99), 0), false},
		{"单样本黑", []byte{0}, true},
		{"单样本恰好 15", []byte{15}, false},
		{"空样本不判黑", nil, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := IsBlackFrame(tc.samples); got != tc.want {
				t.Fatalf("IsBlackFrame(%v) = %v，期望 %v", tc.samples, got, tc.want)
			}
		})
	}
}

// TestCandidateTimepointsLocked 锁定候选时间点序列（DOMAIN_RULES §11 逐字遵守：
// 0s→1s→2s→3s→5s）。序列被意外改动（增删点、改间隔）必须在这里立刻失败。
func TestCandidateTimepointsLocked(t *testing.T) {
	want := []time.Duration{
		0,
		1 * time.Second,
		2 * time.Second,
		3 * time.Second,
		5 * time.Second,
	}
	got := CandidateTimepoints()
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("候选时间点序列被改动：got %v，want %v", got, want)
	}
}

// TestCandidateTimepointsReturnsCopy 返回的必须是副本：调用方修改返回值
// 不能影响全局序列（否则一个越界的调用方会污染整个进程的选帧行为）。
func TestCandidateTimepointsReturnsCopy(t *testing.T) {
	got := CandidateTimepoints()
	got[0] = 99 * time.Second
	if again := CandidateTimepoints(); again[0] != 0 {
		t.Fatalf("修改返回值影响了全局序列：again[0]=%v", again[0])
	}
}
