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
		{"全白不判黑", bytes.Repeat([]byte{255}, 100), false},
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

// TestIsWhiteFrameBoundaries 锁定白帧判定的全部边界语义（DOMAIN_RULES §11 逐字遵守：
// 采样 100 像素亮度 >240 判白；恰好 240 不判白）。
func TestIsWhiteFrameBoundaries(t *testing.T) {
	cases := []struct {
		name    string
		samples []byte
		want    bool
	}{
		{"全白", bytes.Repeat([]byte{255}, 100), true},
		{"全白贴近阈值（241）", bytes.Repeat([]byte{241}, 100), true},
		{"恰好 240 不判白", bytes.Repeat([]byte{240}, 100), false},
		{"全黑不判白", bytes.Repeat([]byte{0}, 100), false},
		{"99 白 1 灰不判白", append(bytes.Repeat([]byte{255}, 99), 200), false},
		{"100 像素仅 1 白不判白", append(bytes.Repeat([]byte{0}, 99), 255), false},
		{"单样本白", []byte{255}, true},
		{"单样本恰好 240", []byte{240}, false},
		{"空样本不判白", nil, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := IsWhiteFrame(tc.samples); got != tc.want {
				t.Fatalf("IsWhiteFrame(%v) = %v，期望 %v", tc.samples, got, tc.want)
			}
		})
	}
}

// TestCandidateFrameFractionsLocked 锁定候选比例序列（DOMAIN_RULES §11 逐字遵守：
// 35%→25%→45%→15%→55%→5%→65%→0ms）。序列被意外改动（增删点、改比例）
// 必须在这里立刻失败。
func TestCandidateFrameFractionsLocked(t *testing.T) {
	want := []float64{0.35, 0.25, 0.45, 0.15, 0.55, 0.05, 0.65, 0.0}
	got := CandidateFrameFractions()
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("候选比例序列被改动：got %v，want %v", got, want)
	}
}

// TestCandidateFrameFractionsReturnsCopy 返回的必须是副本：调用方修改返回值
// 不能影响全局序列（否则一个越界的调用方会污染整个进程的选帧行为）。
func TestCandidateFrameFractionsReturnsCopy(t *testing.T) {
	got := CandidateFrameFractions()
	got[0] = 0.99
	if again := CandidateFrameFractions(); again[0] != 0.35 {
		t.Fatalf("修改返回值影响了全局序列：again[0]=%v", again[0])
	}
}

// TestCandidateTimesSpread 锁定按比例扩散的候选时间点换算（时长 2s：
// 35%→700ms、25%→500ms、45%→900ms、15%→300ms、55%→1100ms、5%→100ms、
// 65%→1300ms、0%→0ms，全部去重后按序列顺序保留）。
func TestCandidateTimesSpread(t *testing.T) {
	got := candidateTimes(2 * time.Second)
	want := []time.Duration{
		700 * time.Millisecond, 500 * time.Millisecond, 900 * time.Millisecond,
		300 * time.Millisecond, 1100 * time.Millisecond, 100 * time.Millisecond,
		1300 * time.Millisecond, 0,
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("candidateTimes(2s) = %v，期望 %v", got, want)
	}
}

// TestCandidateTimesShortDurationDedup 锁定"不重复取同一帧"（§11）：时长极短时
// 35%/25%/45%/15%/5%/0 全部塌缩到 0ms 只保留一次；0.55/0.65 塌缩到 1ms 也合并。
func TestCandidateTimesShortDurationDedup(t *testing.T) {
	got := candidateTimes(1 * time.Millisecond)
	want := []time.Duration{0, 1 * time.Millisecond}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("candidateTimes(1ms) = %v，期望 %v（塌缩点必须去重）", got, want)
	}
}

// TestCandidateTimesNoDurationShortCircuit 锁定"取不到时长时短路 0ms 帧"（§11）：
// duration<=0 时序列只有 0ms 一个点，不做按比例扩散。
func TestCandidateTimesNoDurationShortCircuit(t *testing.T) {
	got := candidateTimes(0)
	if len(got) != 1 || got[0] != 0 {
		t.Fatalf("candidateTimes(0) = %v，期望 [0ms]（短路语义）", got)
	}
}
