package stats

import (
	"sort"
	"time"
)

// 维度键与 OpenAPI StatsOverview/TrendBucket 的计数映射双向同步。
const (
	MediaImage    = "image"
	MediaVideo    = "video"
	MediaAnimated = "animated_image"
	SourceNormal  = "normal"
	SourceCos     = "cos"
)

type Totals struct{ ViewCount, PlayCount, Seconds int64 }
type DimensionRow struct {
	DailyRow
	MediaType, Source string
}
type BreakdownBucket struct {
	TrendBucket
	MediaTypes, Sources map[string]Totals
}

func MediaTotals() map[string]Totals {
	return map[string]Totals{MediaImage: {}, MediaVideo: {}, MediaAnimated: {}}
}
func SourceTotals() map[string]Totals {
	return map[string]Totals{SourceNormal: {}, SourceCos: {}}
}
func addTotal(left, right Totals) Totals {
	return Totals{left.ViewCount + right.ViewCount, left.PlayCount + right.PlayCount, left.Seconds + right.Seconds}
}
func rowTotals(row DimensionRow) Totals {
	return Totals{row.ViewCount, row.PlayCount, row.Seconds}
}
func addDimensions(types, sources map[string]Totals, row DimensionRow) {
	value := rowTotals(row)
	types[row.MediaType] = addTotal(types[row.MediaType], value)
	sources[row.Source] = addTotal(sources[row.Source], value)
}

// SumDimensions 为总览提供服务端汇总，客户端无需再遍历/反推领域数据。
func SumDimensions(rows []DimensionRow) (Totals, map[string]Totals, map[string]Totals) {
	types, sources := MediaTotals(), SourceTotals()
	var total Totals
	for _, row := range rows {
		if _, err := time.Parse(dayLayout, row.Day); err != nil {
			continue
		}
		total = addTotal(total, rowTotals(row))
		addDimensions(types, sources, row)
	}
	return total, types, sources
}

// BuildDimensionBuckets 只选一次全量日期轴/粒度。每一维均按真实桶区间归并，
// 不按各自数组下标拼接，也不在客户端拆分无法逆推的月/周聚合数据。
func BuildDimensionBuckets(rows []DimensionRow, r Range, now time.Time) []BreakdownBucket {
	daily := make([]DailyRow, len(rows))
	for i, row := range rows {
		daily[i] = row.DailyRow
	}
	axis := BuildTrendBuckets(daily, r, now)
	out := make([]BreakdownBucket, len(axis))
	for i, bucket := range axis {
		out[i] = BreakdownBucket{bucket, MediaTotals(), SourceTotals()}
	}
	for _, row := range rows {
		if _, err := time.Parse(dayLayout, row.Day); err != nil {
			continue
		}
		i := sort.Search(len(axis), func(i int) bool { return axis[i].Start > row.Day }) - 1
		if i >= 0 && row.Day <= axis[i].End {
			addDimensions(out[i].MediaTypes, out[i].Sources, row)
		}
	}
	return out
}
