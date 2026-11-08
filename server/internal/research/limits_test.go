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
	first, second := b.take(), b.take()
	if !first || !second {
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

// TestLimitsLensRequiresBothWindows：小时预算耗尽时归还日预算占用；下一小时
// 仍可用满日预算，且日预算耗尽后拒绝。
func TestLimitsLensRequiresBothWindows(t *testing.T) {
	now := time.Date(2026, 11, 6, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }
	limits := newLimits(clock, 20, 1, 2)
	if !limits.takeLens() {
		t.Fatal("第一次应通过")
	}
	if limits.takeLens() {
		t.Fatal("同一小时第二次应拒绝（小时预算=1）")
	}
	// 第二次被拒时已归还日预算占用：下一小时仍应能用掉剩余日预算。
	now = now.Add(time.Hour)
	if !limits.takeLens() {
		t.Fatal("下一小时应通过（日预算未被白吃）")
	}
	if limits.takeLens() {
		t.Fatal("日预算耗尽后应拒绝")
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
