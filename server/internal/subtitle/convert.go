package subtitle

import (
	"bytes"
	"fmt"
	"html"
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// 非原生字幕格式的服务端转换（2026-11-28 外挂字幕第二批）。
//
// 原生格式（ass/ssa/srt/vtt）直接发原件；.sub/.sbv/.lrc/.smi 在服务端转成
// WebVTT 再下发（客户端零改动：协议 format 恒写作 vtt，MIME text/vtt）。
// 转换是尽力而为：MicroDVD 的帧率、SAMI 的样式/定位、LRC 的无结束时间都会
// 丢失，只保留「时间轴 + 文本」——这是格式本身的表达力上限，不是实现缺陷。
const (
	formatVTT = "vtt"
	formatSRT = "srt"
)

// cue 转换中间态（毫秒时间轴 + 纯文本）。
type cue struct {
	start int64
	end   int64
	text  string
}

// ConvertToVTT 把需要转换的格式转成 WebVTT；原生格式返回 ok=false（调用方
// 直接发原件）。空内容返回带 WEBVTT 头的空文档（合法 VTT，客户端显示无字幕）。
func ConvertToVTT(sourceFormat string, content []byte) ([]byte, bool) {
	switch sourceFormat {
	case "sub":
		return microDVDToVTT(string(content)), true
	case "sbv":
		return sbvToVTT(string(content)), true
	case "lrc":
		return lrcToVTT(string(content)), true
	case "smi":
		return samiToVTT(string(content)), true
	default:
		return nil, false
	}
}

var (
	microDVDLine   = regexp.MustCompile(`^\{(\d+)\}\{(\d+)\}(.*)$`)
	microDVDHeader = regexp.MustCompile(`^\{[01]\}\{[01]\}([0-9]+(?:\.[0-9]+)?)\s*$`)
	// MicroDVD 内联样式标签（{y:i}、{c:$00ff00} 等）：VTT 不认，整段丢弃。
	microDVDInlineTag = regexp.MustCompile(`\{[A-Za-z]:[^}]*\}`)

	sbvTimeLine = regexp.MustCompile(`^(\d{1,2}):(\d{2}):(\d{2})\.(\d{3})\s*,\s*(\d{1,2}):(\d{2}):(\d{2})\.(\d{3})\s*$`)

	lrcTag = regexp.MustCompile(`\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]`)

	samiSyncTag = regexp.MustCompile(`(?is)<sync\s+start\s*=\s*"?(\d+)"?\s*>`)
	samiBRTag   = regexp.MustCompile(`(?is)<br\s*/?>`)
	samiAnyTag  = regexp.MustCompile(`(?s)<[^>]*>`)
	samiScript  = regexp.MustCompile(`(?is)<(script|style)\b[^>]*>.*?</(script|style)\s*>`)
)

// microDVDToVTT 转换 MicroDVD（`{起帧}{止帧}文本`，`|` 为换行）。
// 帧率取片头 `{1}{1}<fps>` 指令，缺省 25 fps（PAL 常见值；MicroDVD 无帧率字段时
// 本身就有歧义，25 与 23.976 的累计偏差在长片里可见——文档已记录该假设）。
func microDVDToVTT(content string) []byte {
	lines := strings.Split(strings.ReplaceAll(content, "\r\n", "\n"), "\n")
	fps := 25.0
	cues := make([]cue, 0, len(lines))
	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		if m := microDVDHeader.FindStringSubmatch(line); m != nil {
			if v, err := strconv.ParseFloat(m[1], 64); err == nil && v > 0 && v < 1000 {
				fps = v
			}
			continue
		}
		m := microDVDLine.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		startFrame, err1 := strconv.ParseInt(m[1], 10, 64)
		endFrame, err2 := strconv.ParseInt(m[2], 10, 64)
		if err1 != nil || err2 != nil {
			continue
		}
		text := microDVDInlineTag.ReplaceAllString(m[3], "")
		text = strings.ReplaceAll(text, "|", "\n")
		cues = append(cues, cue{
			start: framesToMillis(startFrame, fps),
			end:   framesToMillis(endFrame, fps),
			text:  escapeCueText(text),
		})
	}
	return buildVTT(cues)
}

func framesToMillis(frame int64, fps float64) int64 {
	return int64(math.Round(float64(frame) / fps * 1000))
}

// sbvToVTT 转换 YouTube SBV（`H:MM:SS.mmm,H:MM:SS.mmm` + 文本块）。
func sbvToVTT(content string) []byte {
	lines := strings.Split(strings.ReplaceAll(content, "\r\n", "\n"), "\n")
	cues := make([]cue, 0, len(lines)/3)
	for i := 0; i < len(lines); i++ {
		m := sbvTimeLine.FindStringSubmatch(strings.TrimSpace(lines[i]))
		if m == nil {
			continue
		}
		start := timePartsMillis(m[1], m[2], m[3], m[4])
		end := timePartsMillis(m[5], m[6], m[7], m[8])
		var text []string
		for i+1 < len(lines) {
			next := strings.TrimSpace(lines[i+1])
			if next == "" || sbvTimeLine.MatchString(next) {
				break
			}
			text = append(text, escapeCueText(next))
			i++
		}
		cues = append(cues, cue{start: start, end: end, text: strings.Join(text, "\n")})
	}
	return buildVTT(cues)
}

func timePartsMillis(h, m, s, ms string) int64 {
	hh, _ := strconv.ParseInt(h, 10, 64)
	mm, _ := strconv.ParseInt(m, 10, 64)
	ss, _ := strconv.ParseInt(s, 10, 64)
	mmm, _ := strconv.ParseInt(ms, 10, 64)
	return hh*3600000 + mm*60000 + ss*1000 + mmm
}

// lrcToVTT 转换 LRC（`[mm:ss.xx]歌词`，一行可带多个时间标签）。
// LRC 只有起点没有终点：每条唱词持续到下一条开始，最后一条固定 5 秒。
func lrcToVTT(content string) []byte {
	var cues []cue
	for _, raw := range strings.Split(strings.ReplaceAll(content, "\r\n", "\n"), "\n") {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		matches := lrcTag.FindAllStringSubmatchIndex(line, -1)
		if len(matches) == 0 {
			continue
		}
		// 正文 = 最后一个时间标签之后的内容（`[ar:歌手]` 等元数据标签不匹配数字形态）。
		textStart := matches[len(matches)-1][1]
		text := strings.TrimSpace(line[textStart:])
		if text == "" {
			continue
		}
		for _, m := range matches {
			min, _ := strconv.ParseInt(line[m[2]:m[3]], 10, 64)
			sec, _ := strconv.ParseInt(line[m[4]:m[5]], 10, 64)
			var frac int64
			if m[6] >= 0 {
				f := line[m[6]:m[7]]
				fv, _ := strconv.ParseInt(f, 10, 64)
				switch len(f) {
				case 1:
					frac = fv * 100
				case 2:
					frac = fv * 10
				default:
					frac = fv
				}
			}
			cues = append(cues, cue{start: min*60000 + sec*1000 + frac, text: escapeCueText(text)})
		}
	}
	sort.SliceStable(cues, func(i, j int) bool { return cues[i].start < cues[j].start })
	for i := range cues {
		if i+1 < len(cues) {
			cues[i].end = cues[i+1].start
		} else {
			cues[i].end = cues[i].start + 5000
		}
	}
	return buildVTT(cues)
}

// samiToVTT 尽力转换 SAMI（`<SYNC Start=毫秒>` 段落）。样式、定位、字体全部
// 丢弃，只保留时间轴与纯文本；结束时间取下一段起点减 1ms（SAMI 语义）。
func samiToVTT(content string) []byte {
	content = samiScript.ReplaceAllString(content, "")
	locs := samiSyncTag.FindAllStringSubmatchIndex(content, -1)
	cues := make([]cue, 0, len(locs))
	for i, loc := range locs {
		start, err := strconv.ParseInt(content[loc[2]:loc[3]], 10, 64)
		if err != nil {
			continue
		}
		bodyEnd := len(content)
		if i+1 < len(locs) {
			bodyEnd = locs[i+1][0]
		}
		body := content[loc[1]:bodyEnd]
		body = samiBRTag.ReplaceAllString(body, "\n")
		body = samiAnyTag.ReplaceAllString(body, "")
		body = html.UnescapeString(body)
		text := strings.TrimSpace(body)
		if text == "" || text == "\u00a0" {
			continue
		}
		end := start + 3000
		if i+1 < len(locs) {
			if next, err := strconv.ParseInt(content[locs[i+1][2]:locs[i+1][3]], 10, 64); err == nil && next > start {
				end = next - 1
			}
		}
		cues = append(cues, cue{start: start, end: end, text: escapeCueText(text)})
	}
	return buildVTT(cues)
}

// escapeCueText 转义 VTT 文本里会被当成标记的字符（`<`/`>`/`&`）。转换来源
// （SAMI 的 HTML、LRC 的方括号标签残留）不可信，必须转义后才能拼进 cue。
func escapeCueText(s string) string {
	s = strings.ReplaceAll(s, "&", "&amp;")
	s = strings.ReplaceAll(s, "<", "&lt;")
	s = strings.ReplaceAll(s, ">", "&gt;")
	return s
}

// buildVTT 拼装 VTT 文档：跳过空文本、修正非正时长（+1s），时间统一
// HH:MM:SS.mmm。
func buildVTT(cues []cue) []byte {
	var b bytes.Buffer
	b.WriteString("WEBVTT\n\n")
	for _, c := range cues {
		text := strings.TrimSpace(strings.ReplaceAll(c.text, "\r", ""))
		if text == "" {
			continue
		}
		end := c.end
		if end <= c.start {
			end = c.start + 1000
		}
		fmt.Fprintf(&b, "%s --> %s\n%s\n\n", formatVTTTime(c.start), formatVTTTime(end), text)
	}
	return b.Bytes()
}

// formatVTTTime 毫秒 → VTT 时间戳（小时位固定两位，兼容超过 99 分钟的片长）。
func formatVTTTime(ms int64) string {
	if ms < 0 {
		ms = 0
	}
	return fmt.Sprintf("%02d:%02d:%02d.%03d",
		ms/3600000, (ms/60000)%60, (ms/1000)%60, ms%1000)
}
