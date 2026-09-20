// fileindex_test.go：FileIndex 与包级 MatchWorks 的逐元素等价性对照
// （审计 R2，2026-09-20）。FileIndex 是 MatchWorks 的索引化复刻，行为
// 不变性靠本文件锁死：语料覆盖带/不带媒体扩展名、X.wmv/X.txt 扩展名判定、
// 同名不同扩展、(N) 序号变体、作品自身含 (N)、大小写、半角空格折叠、
// 空 work，另把语料中每个文件名本身也当作品跑一遍。
package authoring

import "testing"

// TestFileIndexMatchesMatchWorks 对每个作品断言 ix.MatchWorks(w) 与
// MatchWorks(w, files) 逐元素相等（AssetID+顺序）。
func TestFileIndexMatchesMatchWorks(t *testing.T) {
	files := []MediaFile{
		// 带媒体扩展名：精确匹配要求扩展名一致（X.mp4 不得吃 X.png）
		mkFile("X.mp4"), mkFile("X.mp4 (1).mp4"), mkFile("X.mp4 (1).jpg"), mkFile("X.png"),
		// 非索引媒体扩展名（wmv 计入 hasMediaExt）与文档扩展名（txt 不计入）
		mkFile("X.wmv"), mkFile("X.wmv (1).jpg"), mkFile("X.txt (1).jpg"),
		// 旧项目实机回归：同名同基础名不同扩展名各归各（含双空格折叠）
		mkFile("守望先锋  雾子 3.png"), mkFile("守望先锋  雾子 3.mp4"),
		// 序号容错：精确同名在前、序号变体在后、异基础名不沾
		mkFile("守望先锋  朱诺 6.jpg"), mkFile("守望先锋  朱诺 6 (1).jpg"),
		mkFile("守望先锋  朱诺 6 (2).jpg"), mkFile("守望先锋  朱诺 6 卡芙卡.jpg"),
		// 大小写折叠（ASCII）
		mkFile("x.mp4"), mkFile("X.MP4"),
		// 多段空格折叠 + 尾 (N)
		mkFile("追忆  片段 (2).png"),
		// 无扩展名文件与「无扩展名 (N)」形态
		mkFile("无扩展名"), mkFile("无扩展名 (1)"), mkFile("无扩展名 (2).jpg"),
		// 作品自身含 (N)（禁规则 2 的形态）+ 连续两个 (N) 的文件尾
		mkFile("编号 (1) 作品.jpg"), mkFile("数字 123 (1)(2).jpg"),
		// 同名跨目录（多命中全收、输入序；AssetID 域内唯一，同文件名不同 ID）
		mkFile("同名.jpg"), MediaFile{AssetID: "rk_同名.jpg#2", FileName: "同名.jpg"},
	}
	ix := BuildFileIndex(files)
	works := []string{
		// 带媒体扩展名的作品（只精确匹配）
		"X.mp4", "X.mp4 (1).mp4", "X.png", "X.MP4", "X.wmv", "X.wmv (1).jpg",
		// 文档扩展名按无扩展名处理（txt 走规则 2）
		"X.txt",
		// 无媒体扩展名作品（规则 1 全收 + 规则 2 序号变体）
		"X", "X.mp4 (1)", "守望先锋  雾子 3", "守望先锋  雾子 3.mp4",
		"守望先锋  朱诺 6", "守望先锋  朱诺 6 (1)",
		// 作品自身含 (N)：规则 2 禁触发，只剩精确
		"追忆  片段 (2)", "编号 (1) 作品", "编号 (1) 作品.jpg", "数字 123 (1)",
		// 空格折叠与大小写
		"守望先锋 雾子 3.mp4", "x.mp4",
		// 无扩展名文件本身当作品
		"无扩展名", "无扩展名 (1)", "无扩展名 (2).jpg",
		// 不存在的作品（空命中）
		"雾子", "不存在的作品名",
		// 空/纯空白作品
		"", "   ",
	}
	// 语料中每个文件名本身也当作品跑一遍（Round-trip 锁）
	for _, f := range files {
		works = append(works, f.FileName)
	}
	for _, w := range works {
		want := MatchWorks(w, files)
		got := ix.MatchWorks(w)
		if len(got) != len(want) {
			t.Errorf("work=%q: 长度不等 got=%d want=%d\ngot=%v\nwant=%v", w, len(got), len(want), got, want)
			continue
		}
		for i := range want {
			if got[i] != want[i] {
				t.Errorf("work=%q: 第 %d 项不等 got=%v want=%v（AssetID 或顺序漂移）", w, i, got[i], want[i])
			}
		}
	}
}
