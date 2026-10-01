// match_test.go：库名净化与文件夹名匹配的规则锁定（与 Android 归档对齐的
// 锚定测试——九个非法字符逐个、trim、roundtrip、歧义/停用/COS/未命中）。
package localsync

import (
	"strings"
	"testing"

	"qimeng-media/server/internal/store/db"
)

// TestSanitizeLibraryDirNameIllegalChars 九个非法字符逐个替换为 _。
func TestSanitizeLibraryDirNameIllegalChars(t *testing.T) {
	for _, r := range LibraryDirIllegalChars {
		got := SanitizeLibraryDirName("a" + string(r) + "b")
		if got != "a_b" {
			t.Fatalf("非法字符 %q 净化结果 = %q，期望 a_b", r, got)
		}
	}
}

// TestSanitizeLibraryDirNameOthers trim、合法字符保留、空串。
func TestSanitizeLibraryDirNameOthers(t *testing.T) {
	cases := []struct{ in, want string }{
		{"  测试库  ", "测试库"},                           // 首尾空白剥离
		{"a:b\\c*d?e\"f<g>h|i", "a_b_c_d_e_f_g_h_i"}, // 九字符连用
		{"Night Cry", "Night Cry"},                   // 合法字符（含空格）原样保留
		{"日本語😀", "日本語😀"},                             // 非 ASCII 原样保留
		{"", ""},                                     // 空串
		{"   ", ""},                                  // 纯空白 → trim 后空串
	}
	for _, c := range cases {
		if got := SanitizeLibraryDirName(c.in); got != c.want {
			t.Fatalf("SanitizeLibraryDirName(%q) = %q，期望 %q", c.in, got, c.want)
		}
	}
}

// TestSanitizeRoundtripUniqueMatch roundtrip 锚定：sanitize(库名) 作为文件夹
// 名必须能唯一匹配回该库——这是与 App 归档对齐的核心不变量。
func TestSanitizeRoundtripUniqueMatch(t *testing.T) {
	libs := []db.Library{
		{ID: "1", Name: "测试库", Enabled: 1, Kind: "normal"},
		{ID: "2", Name: "a:b", Enabled: 1, Kind: "normal"},
	}
	for _, lib := range libs {
		res := MatchLibraryByDirName(SanitizeLibraryDirName(lib.Name), libs)
		if res.Reject != "" || res.Library.ID != lib.ID {
			t.Fatalf("roundtrip 失败：库名 %q 的文件夹名 %q 未唯一命中（reject=%q lib=%q）",
				lib.Name, SanitizeLibraryDirName(lib.Name), res.Reject, res.Library.ID)
		}
	}
}

// TestMatchRejects 四类拒绝：多库歧义（a/b、a\b、a*b 净化后同为 a_b）、
// 停用库、COS 库、未命中。
func TestMatchRejects(t *testing.T) {
	ambiguous := []db.Library{{ID: "1", Name: "a/b", Enabled: 1}, {ID: "2", Name: `a\b`, Enabled: 1}, {ID: "3", Name: "a*b", Enabled: 1}}
	if res := MatchLibraryByDirName("a_b", ambiguous); res.Reject != RejectAmbiguous {
		t.Fatalf("三个库净化后同为 a_b，期望 ambiguous，得到 %q", res.Reject)
	}
	// 歧义时不猜测目标：Library 必须是零值。
	if res := MatchLibraryByDirName("a_b", ambiguous); res.Library.ID != "" {
		t.Fatalf("歧义时 Library 应为零值，得到 %q", res.Library.ID)
	}
	disabled := []db.Library{{ID: "1", Name: "停用库", Enabled: 0}}
	if res := MatchLibraryByDirName("停用库", disabled); res.Reject != RejectDisabled || res.Library.ID != "1" {
		t.Fatalf("停用库期望 disabled 且携带命中库，得到 %q lib=%q", res.Reject, res.Library.ID)
	}
	cos := []db.Library{{ID: "1", Name: "coslib", Enabled: 1, Kind: "cos"}}
	if res := MatchLibraryByDirName("coslib", cos); res.Reject != RejectCosKind || res.Library.ID != "1" {
		t.Fatalf("COS 库期望 cos-kind 且携带命中库，得到 %q lib=%q", res.Reject, res.Library.ID)
	}
	if res := MatchLibraryByDirName("不存在库", nil); res.Reject != RejectNotFound {
		t.Fatalf("未命中期望 not-found，得到 %q", res.Reject)
	}
}

// TestMatchCaseSensitive 精确相等是区分大小写的：不同大小写不命中。
func TestMatchCaseSensitive(t *testing.T) {
	libs := []db.Library{{ID: "1", Name: "ABC", Enabled: 1, Kind: "normal"}}
	if res := MatchLibraryByDirName("abc", libs); res.Reject != RejectNotFound {
		t.Fatalf("大小写不同应不命中（not-found），得到 %q", res.Reject)
	}
	if res := MatchLibraryByDirName("ABC", libs); res.Reject != "" || res.Library.ID != "1" {
		t.Fatalf("精确相等应命中，得到 %q", res.Reject)
	}
}

// TestIllegalCharsCount 字符集长度锁定：九个字符，多一个少一个都是对齐事故。
func TestIllegalCharsCount(t *testing.T) {
	if n := len([]rune(LibraryDirIllegalChars)); n != 9 {
		t.Fatalf("非法字符集应为 9 个字符，实际 %d：%s", n, LibraryDirIllegalChars)
	}
	if strings.Count(LibraryDirIllegalChars, "_") != 0 {
		t.Fatal("非法字符集不应包含替换目标字符 _")
	}
}
