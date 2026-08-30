package recommend

// 推荐/排行算法单元测试：推荐 7 例 + 排行 5 例。
// 逐条翻译自旧项目 MediaBrowserLogicRecommendTest.kt（v1.12 语义，
// 断言语义必须保留——测试是行为锁）。全部为纯函数测试：直接构造
// 结构体数据、无 DB；时间用 Params.Now / Rank 的 now 固定，保证复现。
//
// 与旧项目的差异点（测试语义兼容，预期值不变）：
//   - randomFactor 用 FNV-1a32(AssetID)（旧：String.hashCode）；
//   - balanceVideoImage 的 RNG 一律以 seed 初始化（旧：seed=0 用真随机）——
//     本类断言全是性质断言（全排列/相对顺序/可复现），不依赖具体随机序列。

import (
	"reflect"
	"strings"
	"testing"
	"time"
)

// fixedNow 是全部测试共用的固定"现在"（2026-08-22 UTC，与 httpapi
// 测试钟一致；算法只看相对时长，绝对时间无关紧要）。
var fixedNow = time.Date(2026, 8, 22, 12, 0, 0, 0, time.UTC)

// makeItem 构造测试条目（默认：图片、CreatedAt=now-1h、ModifiedAt=now，
// 与旧项目 makeMedia 的默认参数对应：indexedAtMillis=now-1h、
// modifiedAtMillis=now）。
func makeItem(key, mediaType string, now time.Time) Item {
	return Item{
		AssetID:    key,
		FileName:   key + ".jpg",
		MediaType:  mediaType,
		CreatedAt:  now.Add(-time.Hour),
		ModifiedAt: now,
	}
}

func ids(items []Item) []string {
	out := make([]string, len(items))
	for i, it := range items {
		out[i] = it.AssetID
	}
	return out
}

// containsAll 断言两个集合元素完全一致（顺序无关）。
func containsAll(ids, want []string) bool {
	if len(ids) != len(want) {
		return false
	}
	found := make(map[string]bool, len(want))
	for _, w := range want {
		found[w] = true
	}
	for _, id := range ids {
		if !found[id] {
			return false
		}
	}
	return true
}

// subsetOf 断言 ids 的元素全部落在 want 集合内（长度可不一致）。
func subsetOf(ids, want []string) bool {
	found := make(map[string]bool, len(want))
	for _, w := range want {
		found[w] = true
	}
	for _, id := range ids {
		if !found[id] {
			return false
		}
	}
	return true
}

// ---------- recommend() ----------

func TestRecommend_emptyList_returnsEmpty(t *testing.T) {
	got := Recommend(nil, Params{Now: fixedNow})
	if len(got) != 0 {
		t.Fatalf("空输入期望空结果，得到 %v", ids(got))
	}
}

func TestRecommend_singleItem_returnsIt(t *testing.T) {
	item := makeItem("only", "image", fixedNow)
	got := Recommend([]Item{item}, Params{Now: fixedNow})
	if len(got) != 1 || got[0].AssetID != "only" {
		t.Fatalf("单条目期望返回它本身，得到 %v", ids(got))
	}
}

func TestRecommend_withData_returnsAllByDefault(t *testing.T) {
	// Limit<=0 = 返回全部（旧语义：limit 默认 media.size 返回全部条目）
	items := []Item{
		makeItem("key1", "image", fixedNow),
		makeItem("key2", "image", fixedNow),
		makeItem("key3", "image", fixedNow),
	}
	got := Recommend(items, Params{Now: fixedNow})
	if len(got) != 3 {
		t.Fatalf("默认 limit 期望返回全部 3 条，得到 %d", len(got))
	}
	if !containsAll(ids(got), []string{"key1", "key2", "key3"}) {
		t.Fatalf("结果应为输入的全排列，得到 %v", ids(got))
	}
}

func TestRecommend_limit_truncatesResult(t *testing.T) {
	items := []Item{
		makeItem("key1", "image", fixedNow),
		makeItem("key2", "image", fixedNow),
		makeItem("key3", "image", fixedNow),
	}
	got := Recommend(items, Params{Limit: 2, Now: fixedNow})
	if len(got) != 2 {
		t.Fatalf("limit=2 期望 2 条，得到 %d", len(got))
	}
	// 结果必须是输入的子集（旧测试语义：结果包含的元素输入必须都有）
	input := []string{"key1", "key2", "key3"}
	if !subsetOf(ids(got), input) {
		t.Fatalf("结果必须是输入子集，得到 %v", ids(got))
	}
}

func TestRecommend_sameSeed_producesIdenticalOrder(t *testing.T) {
	// 同 seed（含 shuffleBuckets 与 balanceVideoImage）下整个推荐结果
	// 必须逐位可复现——旧项目 v1.12 修复点，本实现由确定性 FNV 与
	// 种子化 RNG 共同保证。
	items := []Item{
		makeItem("keyA", "video", fixedNow),
		makeItem("keyB", "image", fixedNow),
		makeItem("keyC", "video", fixedNow),
		makeItem("keyD", "image", fixedNow),
		makeItem("keyE", "video", fixedNow),
		makeItem("keyF", "image", fixedNow),
	}
	first := Recommend(items, Params{Seed: 42, Now: fixedNow})
	second := Recommend(items, Params{Seed: 42, Now: fixedNow})
	if !reflect.DeepEqual(ids(first), ids(second)) {
		t.Fatalf("同 seed 两次调用顺序必须一致：\n第一次 %v\n第二次 %v",
			ids(first), ids(second))
	}
}

func TestRecommend_dailyShownPenalty_increasesWithShownCount(t *testing.T) {
	// 每日惩罚 = -0.8×shownCount（展示次数越多排得越靠后，不再恒 -0.8）。
	// 全部为图片且 seed=0（跳过 shuffleBuckets），balanceVideoImage 保持
	// 评分顺序，因此结果顺序可直接断言：未展示 > 展示1次 > 展示5次。
	// 惩罚差值：shownFive 最多 -4.0，即使 randomFactor 差异最大 0.485
	// 也无法翻盘（旧测试注释保留——该结论对 FNV/Go RNG 同样成立）。
	items := []Item{
		makeItem("unshownA", "image", fixedNow),
		makeItem("unshownB", "image", fixedNow),
		makeItem("shownOnce", "image", fixedNow),
		makeItem("shownFive", "image", fixedNow),
	}
	for i := range items {
		switch items[i].AssetID {
		case "shownOnce":
			items[i].ShownToday = 1
		case "shownFive":
			items[i].ShownToday = 5
		}
	}
	got := Recommend(items, Params{Seed: 0, Now: fixedNow})
	if len(got) != 4 {
		t.Fatalf("期望 4 条，得到 %d", len(got))
	}
	// 未展示的两个排最前（两者相对顺序由 randomFactor 决定，不断言）
	firstTwo := ids(got)[:2]
	if !containsAll(firstTwo, []string{"unshownA", "unshownB"}) {
		t.Fatalf("前两位应为未展示的两项，得到 %v", ids(got))
	}
	if got[2].AssetID != "shownOnce" {
		t.Fatalf("第 3 位应为 shownOnce，得到 %s", got[2].AssetID)
	}
	if got[3].AssetID != "shownFive" {
		t.Fatalf("第 4 位应为 shownFive，得到 %s", got[3].AssetID)
	}
}

func TestRecommend_balanceVideoImage_mixedResult_isPermutationOfInput(t *testing.T) {
	// balanceVideoImage：视频图片自然混合，结果是输入的全排列；
	// 类型序列应发生交错（存在相邻异类），不是"视频全部在前"的原始分组。
	media := []Item{
		makeItem("v1", "video", fixedNow), makeItem("v2", "video", fixedNow),
		makeItem("v3", "video", fixedNow), makeItem("v4", "video", fixedNow),
		makeItem("v5", "video", fixedNow), makeItem("v6", "video", fixedNow),
		makeItem("i1", "image", fixedNow), makeItem("i2", "image", fixedNow),
		makeItem("i3", "image", fixedNow), makeItem("i4", "image", fixedNow),
		makeItem("i5", "image", fixedNow), makeItem("i6", "image", fixedNow),
	}
	got := Recommend(media, Params{Seed: 7, Now: fixedNow})
	if len(got) != 12 {
		t.Fatalf("期望 12 条，得到 %d", len(got))
	}
	if !containsAll(ids(got), ids(media)) {
		t.Fatalf("结果必须是输入的全排列，得到 %v", ids(got))
	}
	// 原始输入是视频在前，混合后不应保持该顺序
	if reflect.DeepEqual(ids(got), ids(media)) {
		t.Fatalf("混合后不应保持原始分组顺序：%v", ids(got))
	}
	// 两种类型都出现，且发生至少一次类型交错
	hasVideo, hasImage, transitions := false, false, 0
	for i, it := range got {
		if it.MediaType == "video" {
			hasVideo = true
		} else {
			hasImage = true
		}
		if i > 0 && it.MediaType != got[i-1].MediaType {
			transitions++
		}
	}
	if !hasVideo || !hasImage {
		t.Fatalf("结果必须同时包含视频与图片：%v", ids(got))
	}
	if transitions == 0 {
		t.Fatalf("结果应发生类型交错（分离式排列）：%v", ids(got))
	}
}

// ---------- tagsFor（标签集构建，本包评分输入） ----------

// TestTagsFor_inferredTag_unicodeCharCount：推断标签长度按字符数计
// （旧项目 Kotlin length = UTF-16 码元、中文 1 字=1，≤16 保留）——
// UTF-8 字节数口径会把中文 6~15 字（≥18 字节）的标签误丢，此测试锁定
// 字符口径边界：16 字保留、17 字丢弃（与旧实现一致）。
func TestTagsFor_inferredTag_unicodeCharCount(t *testing.T) {
	item := makeItem("key1", "image", fixedNow)
	item.FileName = "一二三四五六七八九十甲乙丙丁戊己.jpg" // 16 字 / 48 字节
	if !containsStr(tagsFor(item), "一二三四五六七八九十甲乙丙丁戊己") {
		t.Fatalf("16 字推断标签应保留（RuneCount=16 ≤16），得到 %v", tagsFor(item))
	}
	item.FileName = "一二三四五六七八九十甲乙丙丁戊己庚.jpg" // 17 字 / 51 字节
	if containsStr(tagsFor(item), "一二三四五六七八九十甲乙丙丁戊己庚") {
		t.Fatalf("17 字推断标签应丢弃（>16），得到 %v", tagsFor(item))
	}
}

// TestTagsFor_manualTags_keptAsIs：manual 标签原样并入——不 trim、不限长
// （旧项目 manual 集合直接并入；trim/限长只作用于文件名推断标签）。
func TestTagsFor_manualTags_keptAsIs(t *testing.T) {
	longManual := strings.Repeat("标", 20) // 20 字，远超推断标签 16 字上限
	spaced := "  带空格标签  "
	item := makeItem("key1", "image", fixedNow)
	item.Tags = []string{longManual, spaced}
	tags := tagsFor(item)
	if !containsStr(tags, longManual) {
		t.Fatalf("超长 manual 标签应原样保留，得到 %v", tags)
	}
	if !containsStr(tags, spaced) {
		t.Fatalf("manual 标签不应被 trim（原样并入），得到 %v", tags)
	}
}

func containsStr(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}

// ---------- rank() ----------

func TestRank_emptySource_returnsEmpty(t *testing.T) {
	got := Rank(nil, PeriodAll, fixedNow)
	if len(got) != 0 {
		t.Fatalf("空输入期望空结果，得到 %d", len(got))
	}
}

func TestRank_singleItem_returnsIt(t *testing.T) {
	item := makeItem("key1", "image", fixedNow)
	got := Rank([]Item{item}, PeriodAll, fixedNow)
	if len(got) != 1 || got[0].AssetID != "key1" {
		t.Fatalf("单条目期望返回它本身，得到 %v", ids(got))
	}
}

func TestRank_allPeriod_sortsByViewsPlaysLikes(t *testing.T) {
	// 热度排序 = viewCount + playCount + likeCount 降序
	a := makeItem("keyA", "image", fixedNow)
	b := makeItem("keyB", "image", fixedNow.Add(-time.Millisecond))
	c := makeItem("keyC", "image", fixedNow.Add(-2*time.Millisecond))
	a.Stats.ViewCount = 100
	b.Stats.ViewCount, b.Stats.PlayCount = 50, 10
	c.LikeCount = 70
	got := Rank([]Item{a, b, c}, PeriodAll, fixedNow)
	// keyA = 100，keyC = 70（点赞计入总分），keyB = 60（50 观看 + 10 播放）
	if want := []string{"keyA", "keyC", "keyB"}; !reflect.DeepEqual(ids(got), want) {
		t.Fatalf("期望 %v，得到 %v", want, ids(got))
	}
}

func TestRank_tieScore_newerModifiedAtFirst(t *testing.T) {
	// 同分时按 ModifiedAt 降序，新文件在前
	newer := makeItem("keyNew", "image", fixedNow)
	older := makeItem("keyOld", "image", fixedNow.Add(-100*time.Second))
	newer.Stats.ViewCount, older.Stats.ViewCount = 10, 10
	got := Rank([]Item{older, newer}, PeriodAll, fixedNow)
	if want := []string{"keyNew", "keyOld"}; !reflect.DeepEqual(ids(got), want) {
		t.Fatalf("期望 %v，得到 %v", want, ids(got))
	}
}

func TestRank_dayPeriod_onlyIncludesPeriodFiles(t *testing.T) {
	// 周期筛选：非总榜只保留周期内有浏览记录的文件；周期依据
	// lastViewedAt >= now - 窗口（服务端口径：open 事件的 MAX(started_at)）。
	recentHistory := makeItem("keyRecentHistory", "image", fixedNow)
	oldHistory := makeItem("keyOldHistory", "image", fixedNow)
	recentStat := makeItem("keyRecentStat", "image", fixedNow)
	nowMinusHour := fixedNow.Add(-time.Hour)
	recentHistory.Stats.LastViewedAt = &fixedNow
	oldHistory.Stats.LastViewedAt = ptrTime(fixedNow.Add(-10 * 24 * time.Hour))
	recentStat.Stats.LastViewedAt = &nowMinusHour
	recentStat.Stats.ViewCount = 5
	got := Rank([]Item{recentHistory, oldHistory, recentStat}, PeriodDay, fixedNow)
	// keyOldHistory 超出日榜周期被排除；剩余两项中 keyRecentStat(5)
	// 热度高于 keyRecentHistory(0)
	if want := []string{"keyRecentStat", "keyRecentHistory"}; !reflect.DeepEqual(ids(got), want) {
		t.Fatalf("期望 %v，得到 %v", want, ids(got))
	}
}

func ptrTime(t time.Time) *time.Time { return &t }
