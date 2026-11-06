package research

import (
	"testing"
	"time"
)

// TestBudgetWindowResets：窗口按 UTC 对齐，进入下一窗口即重置。
func TestBudgetWindowResets(t *testing.T) {
	now := time.Date(2026, 11, 6, 12, 34, 10, 0, time.UTC)
	clock := func() time.Time { return now }
	b := newBudget(clock, 2, time.Minute)
	if !b.take() || !b.take() {
		t.Fatal("前两次占用应通过")
	}
	if b.take() {
		t.Fatal("第三次应被拒绝")
	}
	now = now.Add(time.Minute)
	if !b.take() {
		t.Fatal("进入下一分钟应重置预算")
	}
}

// TestBudgetZeroMeansUnlimited：limit<=0 表示不限制。
func TestBudgetZeroMeansUnlimited(t *testing.T) {
	b := newBudget(time.Now, 0, time.Minute)
	for i := 0; i < 100; i++ {
		if !b.take() {
			t.Fatal("limit=0 不应拒绝")
		}
	}
}

// TestLimitsLensRequiresBothWindows：日预算耗尽时拒绝，且不白吃小时配额。
func TestLimitsLensRequiresBothWindows(t *testing.T) {
	now := time.Date(2026, 11, 6, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }
	limits := newLimits(clock, 20, 5, 1)
	if !limits.takeLens() {
		t.Fatal("第一次应通过")
	}
	if limits.takeLens() {
		t.Fatal("日预算耗尽后应拒绝")
	}
	// 日预算耗尽不应消耗小时预算：换到新一天后仍可用满 5 次。
	now = now.Add(24 * time.Hour)
	for i := 0; i < 5; i++ {
		if !limits.takeLens() {
			t.Fatalf("新的一天第 %d 次应通过", i+1)
		}
	}
	if limits.takeLens() {
		t.Fatal("小时预算耗尽后应拒绝")
	}
}

// TestLimitsProviderSeparateBudget：provider 预算彼此独立。
func TestLimitsProviderSeparateBudget(t *testing.T) {
	now := time.Date(2026, 11, 6, 12, 0, 0, 0, time.UTC)
	limits := newLimits(func() time.Time { return now }, 1, 20, 100)
	if !limits.takeProvider(NameFxTwitter) {
		t.Fatal("第一个 provider 第一次应通过")
	}
	if limits.takeProvider(NameFxTwitter) {
		t.Fatal("同 provider 第二次应拒绝")
	}
	if !limits.takeProvider(NamePixiv) {
		t.Fatal("不同 provider 预算应独立")
	}
	now = now.Add(time.Minute)
	if !limits.takeProvider(NameFxTwitter) {
		t.Fatal("下一分钟应重置")
	}
}
