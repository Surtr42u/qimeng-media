package filing

// workname.go：作品编号与组图页码解析（纯函数，无 IO）。
//
// 命名语法（2026-11-07 用户补充，规则正文见 docs/domain/files.md
// 「作品编号与组图页码」）：
//   - 独立作品：{基名} {作品编号}.ext
//   - 组图页：  {基名} {作品编号} ({页码}).ext
//
// 作品编号只按作品递增：同一作品的组内页共享一个作品编号，页码不参与
// 作品编号占用计算；SuggestSeriesNames 据此建议下一作品编号而不是下一页码。
//
// 仅括号序号而无主编号的旧格式（{基名} (2).ext，含上传同名冲突自动改名的
// 「基名 (2).ext」产物）不属于本语法，保持既有 seriesSuffix 语义。消歧依据：
// 尾随括号内为纯正整数页码，且其前的片段本身能解析出裸作品编号。因此
// 「{基名} N (M)」视为「作品 N 第 M 页」，而「{基名} (M)」仍是旧序列/改名
// 括号序号；后者不能被当作作品编号占用。
//
// 本文件只做解析，不读写文件、不涉及 HTTP/协议；上传与移动业务语义不变。

import (
	"strings"
	"unicode"
)

// workName 是一次作品名解析的结果：作品基名 + 主作品编号（含原始分隔与
// 位宽）+ 可选组内页码。pageNumber 为 0 表示独立作品（无组内页码）。
type workName struct {
	familyPrefix string // 作品基名（作品编号前的原样片段）
	separator    string // 基名与作品编号之间的原样分隔（空白/空串等）
	marker       string // 作品编号前缀标记（No./#/EP 等，原样保留）
	workNumber   int    // 主作品编号
	workWidth    int    // 主作品编号原始位宽（保留前导零）
	pageNumber   int    // 组内页码；0 = 独立作品
	pageWidth    int    // 组内页码原始位宽
	hasPage      bool   // 是否带组内页码
}

// parseWorkName 解析独立作品或组图页；组图页优先（尾随括号页码 + 句柄前
// 可解析的作品编号），解析失败返回 false 交由调用方回退旧 seriesSuffix 语义。
func parseWorkName(base string) (workName, bool) {
	if base == "" {
		return workName{}, false
	}
	if page, pageWidth, workPart, ok := trailingPageSuffix(base); ok {
		if series, parsed := parseBareSeries(workPart); parsed {
			return workName{
				familyPrefix: series.prefix,
				separator:    series.ws,
				marker:       series.marker,
				workNumber:   series.num,
				workWidth:    series.width,
				pageNumber:   page,
				pageWidth:    pageWidth,
				hasPage:      true,
			}, true
		}
		// 尾随括号内是数字但前面没有可解析的作品编号：属于「仅括号序号」
		// 旧格式（如「旅行 (2)」），不按作品语法处理。
		return workName{}, false
	}
	if series, ok := parseBareSeries(base); ok {
		return workName{
			familyPrefix: series.prefix,
			separator:    series.ws,
			marker:       series.marker,
			workNumber:   series.num,
			workWidth:    series.width,
		}, true
	}
	return workName{}, false
}

// asSeriesSuffix 把「作品编号 + 分隔/位宽」转成既有序号汇总结构，页码不参与；
// 这样组图页与同名独立作品归入同一作品族，只占用一次作品编号。
func (w workName) asSeriesSuffix() seriesSuffix {
	return seriesSuffix{
		prefix: w.familyPrefix,
		ws:     w.separator,
		marker: w.marker,
		num:    w.workNumber,
		width:  w.workWidth,
	}
}

// parseSuggestSuffix 是 SuggestSeriesNames 的序号解析入口：组图页按作品编号
// 计入序号汇总（建议下一作品编号），其余文件名保持既有 seriesSuffix 语义
// （裸序号/仅括号序号/标记前缀等）。
func parseSuggestSuffix(base string) (seriesSuffix, bool) {
	if w, ok := parseWorkName(base); ok && w.hasPage {
		return w.asSeriesSuffix(), true
	}
	return parseSeriesSuffix(base)
}

// trailingPageSuffix 识别「基名+作品编号 (页码)」的尾随页码括号。
// 只接受半角/全角圆括号（协议示例为 (1)；方括号保留给既有 seriesSuffix 语义，
// 避免把「名 11 [1]」这类旧风格误判成组图页）。页码必须是纯正整数。
func trailingPageSuffix(base string) (page, pageWidth int, workPart string, ok bool) {
	for _, pair := range [...]struct{ open, close string }{
		{"(", ")"},
		{"（", "）"},
	} {
		if !strings.HasSuffix(base, pair.close) {
			continue
		}
		openIdx := strings.LastIndex(base, pair.open)
		if openIdx <= 0 {
			continue
		}
		inner := base[openIdx+len(pair.open) : len(base)-len(pair.close)]
		if !isDigits(inner) {
			continue
		}
		n, valid := parseDigits(inner)
		if !valid || n <= 0 {
			continue
		}
		part := strings.TrimRightFunc(base[:openIdx], unicode.IsSpace)
		if part == "" {
			continue
		}
		return n, len(inner), part, true
	}
	return 0, 0, "", false
}
