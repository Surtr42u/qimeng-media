package subtitle

import (
	"strings"
	"testing"
)

// TestMicroDVDToVTT 覆盖帧率指令、帧→毫秒换算、`|` 换行与内联样式标签剥离。
func TestMicroDVDToVTT(t *testing.T) {
	in := strings.Join([]string{
		"{1}{1}25.000",
		"{25}{50}你|好",
		"{50}{75}{y:i}第二句",
	}, "\n")
	got := string(mustConvert(t, "sub", in))
	if !strings.HasPrefix(got, "WEBVTT\n\n") {
		t.Fatalf("缺 WEBVTT 头: %q", got)
	}
	if !strings.Contains(got, "00:00:01.000 --> 00:00:02.000\n你\n好") {
		t.Fatalf("首条 cue 不符: %q", got)
	}
	if !strings.Contains(got, "00:00:02.000 --> 00:00:03.000\n第二句") {
		t.Fatalf("内联标签未剥离: %q", got)
	}
}

// TestSBVToVTT 覆盖 SBV 时间行 + 多行文本。
func TestSBVToVTT(t *testing.T) {
	in := "0:00:01.000,0:00:02.500\n第一行\n第二行\n\n0:00:03.000,0:00:04.000\n下一句\n"
	got := string(mustConvert(t, "sbv", in))
	if !strings.Contains(got, "00:00:01.000 --> 00:00:02.500\n第一行\n第二行") {
		t.Fatalf("SBV cue 不符: %q", got)
	}
	if !strings.Contains(got, "00:00:03.000 --> 00:00:04.000\n下一句") {
		t.Fatalf("SBV 第二条不符: %q", got)
	}
}

// TestLRCToVTT 覆盖一行多标签、元数据标签忽略、结束时间=下一条起点、末条 +5s。
func TestLRCToVTT(t *testing.T) {
	in := "[ar:歌手]\n[00:01.00][00:03.50]重复句\n[00:05.2]末尾\n"
	got := string(mustConvert(t, "lrc", in))
	checks := []string{
		"00:00:01.000 --> 00:00:03.500\n重复句",
		"00:00:03.500 --> 00:00:05.200\n重复句",
		"00:00:05.200 --> 00:00:10.200\n末尾",
	}
	for _, want := range checks {
		if !strings.Contains(got, want) {
			t.Fatalf("LRC 缺 %q；实际: %q", want, got)
		}
	}
	if strings.Contains(got, "歌手") {
		t.Fatalf("元数据标签不应进入字幕: %q", got)
	}
}

// TestSAMIToVTT 覆盖 SYNC 分段、`<br>` 换行、HTML 标签剥离与实体解码、空段跳过。
func TestSAMIToVTT(t *testing.T) {
	in := `<SAMI><HEAD><STYLE><!-- p { color:red } --></STYLE></HEAD><BODY>
<SYNC Start=1000><P Class=CN>你好<BR>世界&amp;朋友</P>
<SYNC Start=2500>&nbsp;
<SYNC Start=4000><P>第三句</P>
</BODY></SAMI>`
	got := string(mustConvert(t, "smi", in))
	if !strings.Contains(got, "00:00:01.000 --> 00:00:02.499\n你好\n世界&amp;朋友") {
		t.Fatalf("SAMI 首条不符: %q", got)
	}
	if !strings.Contains(got, "00:00:04.000 --> 00:00:07.000\n第三句") {
		t.Fatalf("SAMI 末条不符: %q", got)
	}
	if strings.Contains(got, "color:red") {
		t.Fatalf("style 块不应进入字幕: %q", got)
	}
}

// TestConvertNativeFormats 原生格式不参与转换（调用方直接发原件）。
func TestConvertNativeFormats(t *testing.T) {
	for _, f := range []string{"ass", "ssa", "srt", "vtt"} {
		if _, ok := ConvertToVTT(f, []byte("x")); ok {
			t.Fatalf("%s 不应走服务端转换", f)
		}
	}
}

// TestConvertEmpty 空内容仍产出合法 VTT（客户端显示无字幕，不报错）。
func TestConvertEmpty(t *testing.T) {
	for _, f := range []string{"sub", "sbv", "lrc", "smi"} {
		got := string(mustConvert(t, f, ""))
		if got != "WEBVTT\n\n" {
			t.Fatalf("%s 空输入 = %q", f, got)
		}
	}
}

func mustConvert(t *testing.T, format, content string) []byte {
	t.Helper()
	out, ok := ConvertToVTT(format, []byte(content))
	if !ok {
		t.Fatalf("%s 应走服务端转换", format)
	}
	return out
}
