package stats

// 趋势分桶测试：前 7 例逐条翻译自旧项目 StatsFormatHelperTest.kt（v1.16
// 语义，断言语义必须保留——测试是行为锁；只译行为，不搬 Kotlin 代码）。
// 追加固定 Range（day/month/year）与前导 0 桶用例。
//
// 时间构造：全部用"日中段"时刻（如 15:00），任意时区下都落在同一日历日，
// 测试结果与机器时区无关（与旧测试 atStartOfDay+15h 的意图一致）。

import (
	"reflect"
	"testing"
	"time"
)

// localDay 构造本地时区某日 15:00 的时间（日历日口径的测试钟）。
func localDay(y int, m time.Month, d int) time.Time {
	return time.Date(y, m, d, 15, 0, 0, 0, time.Local)
}

// row 构造聚合行（默认 view=1，与旧测试 row() 默认值一致）。
func row(day string, view, play int64) DailyRow {
	return DailyRow{Day: day, ViewCount: view, PlayCount: play}
}

// labelsOf 提取桶标签序列。
func labelsOf(buckets []TrendBucket) []string {
	out := make([]string, len(buckets))
	for i, b := range buckets {
		out[i] = b.Label
	}
	return out
}

// viewPlaySum 返回各桶 view+play 之和（总和守恒断言用）。
func viewPlaySum(buckets []TrendBucket) int64 {
	var sum int64
	for _, b := range buckets {
		sum += b.ViewCount + b.PlayCount
	}
	return sum
}

// ---------- 旧测试 1/7：周对齐（v1.16 周日缺陷修复） ----------

func TestBuildTrendBuckets_sunday_alignsCurrentWeekToThisMonday(t *testing.T) {
	// now = 2026-08-16（周日）15:00，数据落在当天
	got := BuildTrendBuckets([]DailyRow{row("2026-08-16", 3, 0)}, RangeAll, localDay(2026, time.August, 16))
	// 仅 1 周数据 → 向前补一个 0 值周桶
	if len(got) != 2 {
		t.Fatalf("期望 2 个桶，得到 %d（%v）", len(got), labelsOf(got))
	}
	if got[0].Label != "08/03" || got[0].ViewCount+got[0].PlayCount != 0 {
		t.Fatalf("前导 0 桶期望 08/03 值 0，得到 %+v", got[0])
	}
	// 本周桶对齐到本周一 08/10，而非缺陷版的下周一 08/17
	if got[1].Label != "08/10" || got[1].ViewCount != 3 {
		t.Fatalf("本周桶期望 08/10 值 3，得到 %+v", got[1])
	}
	if got[1].Start != "2026-08-10" || got[1].End != "2026-08-16" {
		t.Fatalf("周桶区间期望 2026-08-10~2026-08-16，得到 %s~%s", got[1].Start, got[1].End)
	}
}

// ---------- 旧测试 2/7：周一对其自身 ----------

func TestBuildTrendBuckets_monday_alignsToSelf(t *testing.T) {
	got := BuildTrendBuckets([]DailyRow{row("2026-08-10", 2, 0)}, RangeAll, localDay(2026, time.August, 10))
	if len(got) == 0 {
		t.Fatal("期望非空桶序列")
	}
	last := got[len(got)-1]
	if last.Label != "08/10" || last.ViewCount != 2 {
		t.Fatalf("末桶期望 08/10 值 2，得到 %+v", last)
	}
}

// ---------- 旧测试 3/7：跨度 ≤12 周按周 ----------

func TestBuildTrendBuckets_spanWithin12Weeks_bucketsByWeek(t *testing.T) {
	got := BuildTrendBuckets([]DailyRow{
		row("2026-08-10", 1, 0), row("2026-07-20", 1, 0),
	}, RangeAll, localDay(2026, time.August, 16))
	// 07-20（周一）所在周到 08-16 所在周共 4 个周桶
	want := []string{"07/20", "07/27", "08/03", "08/10"}
	if !reflect.DeepEqual(labelsOf(got), want) {
		t.Fatalf("期望周桶 %v，得到 %v", want, labelsOf(got))
	}
}

// ---------- 旧测试 4/7：12 周~24 月按月（首末同年 → MM月 标签） ----------

func TestBuildTrendBuckets_spanOver12WeeksWithin24Months_bucketsByMonth(t *testing.T) {
	got := BuildTrendBuckets([]DailyRow{
		row("2026-08-01", 1, 0), row("2026-02-10", 1, 0),
	}, RangeAll, localDay(2026, time.August, 16))
	// 约 27 周 > 12 周、7 个月 ≤ 24 → 按月；首末同年 → MM月 标签
	want := []string{"02月", "03月", "04月", "05月", "06月", "07月", "08月"}
	if !reflect.DeepEqual(labelsOf(got), want) {
		t.Fatalf("期望月桶 %v，得到 %v", want, labelsOf(got))
	}
}

// ---------- 旧测试 5/7：超 24 月按季 ----------

func TestBuildTrendBuckets_spanOver24Months_bucketsByQuarter(t *testing.T) {
	got := BuildTrendBuckets([]DailyRow{
		row("2026-08-01", 1, 0), row("2023-12-15", 1, 0),
	}, RangeAll, localDay(2026, time.August, 16))
	// 约 33 个月 > 24 → 按季，2023-Q4 到 2026-Q3 共 12 个季桶
	if len(got) != 12 {
		t.Fatalf("期望 12 个季桶，得到 %d（%v）", len(got), labelsOf(got))
	}
	if got[0].Label != "23/Q4" {
		t.Fatalf("首桶期望 23/Q4，得到 %s", got[0].Label)
	}
	if got[len(got)-1].Label != "26/Q3" {
		t.Fatalf("末桶期望 26/Q3，得到 %s", got[len(got)-1].Label)
	}
}

// ---------- 旧测试 6/7：总和守恒 ----------

func TestBuildTrendBuckets_allRowsSummed_bucketsTotalEqualsInputTotal(t *testing.T) {
	rows := []DailyRow{
		row("2026-08-16", 3, 1),
		row("2026-08-15", 2, 0),
		row("2026-07-01", 5, 2),
		row("2026-03-10", 4, 0),
		row("2024-01-01", 7, 0),
	}
	got := BuildTrendBuckets(rows, RangeAll, localDay(2026, time.August, 16))
	// 无论落在哪个粒度的桶，各桶之和必须等于全量总和（与数字卡「全部」口径一致）
	var want int64
	for _, r := range rows {
		want += r.ViewCount + r.PlayCount
	}
	if gotSum := viewPlaySum(got); gotSum != want {
		t.Fatalf("桶之和期望 %d，得到 %d（%v）", want, gotSum, labelsOf(got))
	}
}

// ---------- 旧测试 7/7：空数据返回空 ----------

func TestBuildTrendBuckets_emptyRows_returnsEmpty(t *testing.T) {
	// 空数据返回空切片，图表走「暂无趋势数据」空态
	if got := BuildTrendBuckets(nil, RangeAll, localDay(2026, time.August, 16)); len(got) != 0 {
		t.Fatalf("空输入期望空切片，得到 %v", labelsOf(got))
	}
	if got := BuildTrendBuckets([]DailyRow{}, RangeDay, localDay(2026, time.August, 16)); len(got) != 0 {
		t.Fatalf("空输入期望空切片，得到 %v", labelsOf(got))
	}
}

// ---------- 补例：固定 Range 分桶 ----------

func TestBuildTrendBuckets_dayRange_fixedDailyBuckets(t *testing.T) {
	got := BuildTrendBuckets([]DailyRow{
		row("2026-08-16", 2, 0), row("2026-08-13", 0, 1),
	}, RangeDay, localDay(2026, time.August, 16))
	// 从最早数据 08-13 铺到今天 08-16，逐天补齐空桶
	want := []string{"08/13", "08/14", "08/15", "08/16"}
	if !reflect.DeepEqual(labelsOf(got), want) {
		t.Fatalf("期望天桶 %v，得到 %v", want, labelsOf(got))
	}
	if got[0].PlayCount != 1 || got[3].ViewCount != 2 {
		t.Fatalf("桶值归位错误：%+v / %+v", got[0], got[3])
	}
	if got[0].Start != "2026-08-13" || got[0].End != "2026-08-13" {
		t.Fatalf("天桶区间应为单日，得到 %s~%s", got[0].Start, got[0].End)
	}
}

func TestBuildTrendBuckets_monthRange_fixedMonthlyBuckets(t *testing.T) {
	got := BuildTrendBuckets([]DailyRow{
		row("2026-08-01", 1, 0), row("2026-05-20", 2, 0),
	}, RangeMonth, localDay(2026, time.August, 16))
	want := []string{"05月", "06月", "07月", "08月"}
	if !reflect.DeepEqual(labelsOf(got), want) {
		t.Fatalf("期望月桶 %v，得到 %v", want, labelsOf(got))
	}
	if got[0].End != "2026-05-31" || got[len(got)-1].End != "2026-08-31" {
		t.Fatalf("月桶末日应精确到月末，得到 %s / %s", got[0].End, got[len(got)-1].End)
	}
}

func TestBuildTrendBuckets_yearRange_fixedYearlyBuckets(t *testing.T) {
	got := BuildTrendBuckets([]DailyRow{
		row("2026-08-01", 1, 0), row("2024-03-05", 2, 0),
	}, RangeYear, localDay(2026, time.August, 16))
	want := []string{"2024", "2025", "2026"}
	if !reflect.DeepEqual(labelsOf(got), want) {
		t.Fatalf("期望年桶 %v，得到 %v", want, labelsOf(got))
	}
	if got[0].ViewCount != 2 || got[2].ViewCount != 1 {
		t.Fatalf("年桶值归位错误：%+v / %+v", got[0], got[2])
	}
}

// ---------- 补例：前导 0 桶（固定 Range 同样生效） ----------

func TestBuildTrendBuckets_leadingZeroBucket_forSinglePeriodData(t *testing.T) {
	got := BuildTrendBuckets([]DailyRow{row("2026-08-16", 1, 0)}, RangeMonth, localDay(2026, time.August, 16))
	// 单周期数据 → 补一个前导 0 值月桶，折线至少两点可画
	if len(got) != 2 {
		t.Fatalf("期望 2 个桶（1 数据 + 1 前导 0），得到 %v", labelsOf(got))
	}
	if got[0].Label != "07月" || got[0].ViewCount+got[0].PlayCount != 0 {
		t.Fatalf("前导桶期望 07月 值 0，得到 %+v", got[0])
	}
	if got[1].Label != "08月" || got[1].ViewCount != 1 {
		t.Fatalf("数据桶期望 08月 值 1，得到 %+v", got[1])
	}
}

// ---------- 补例：未来行兜底（守恒不因时钟漂移破坏） ----------

func TestBuildTrendBuckets_futureRow_stillCounted(t *testing.T) {
	// 行日期晚于 today（时钟漂移/时区差）：桶铺到最晚数据，总和仍守恒
	got := BuildTrendBuckets([]DailyRow{
		row("2026-08-16", 1, 0), row("2026-08-18", 4, 0),
	}, RangeWeek, localDay(2026, time.August, 16))
	if gotSum := viewPlaySum(got); gotSum != 5 {
		t.Fatalf("未来行也必须入桶（总和守恒），期望 5 得到 %d（%v）", gotSum, labelsOf(got))
	}
}
