// Package searchtoken 提供全文搜索 q 谓词共享的关键词匹配语义，并以 SQLite
// 确定性标量函数 qimeng_search_token(all_text, token) 暴露给 SQL。
//
// 为什么独立成包：browse.sql 的列表/计数与 facets.sql 的六路分面复制了同一
// q 谓词，必须共用一份可测试实现；modernc 驱动的自定义函数是驱动级全局状态，
// 注册必须先于任何连接打开——store 包会 blank import 本包，store/db 包自身
// 的测试（直接 sql.Open）也显式 blank import 它。
//
// 语义（2026-11-07 用户反馈「朱诺 11」命中 110/2011/1080 后修订，
// DOMAIN_RULES §3 全文搜索口径）：
//   - 数字 token（全 ASCII 数字）按独立数字段匹配：命中位置前后都不能是
//     ASCII 数字；紧跟在 ( （ [ 【 之后的数字视为组图页码，裸数字不命中；
//   - 括号页码 token（(2)/（2）/[2]/【2】）按组图页码命中，正文括号全角/
//     半角混用不丢命中（组图页语义，旧项目命名约定的「名 (页号)」）；
//   - 非数字 token 保持旧子串语义：ASCII 大小写折叠，不做 LIKE 通配魔法
//     （%/_ 仍是字面量），多词 AND 由调用方在 SQL 侧组合。
package searchtoken

import (
	"database/sql/driver"
	"fmt"
	"strings"
	"unicode/utf8"

	"modernc.org/sqlite"
)

// FunctionName 是 browse.sql / facets.sql 的 q 谓词使用的 SQL 函数名。
// 改名前必须同步两个 queries 文件并重新生成 store/db（sqlc v1.31.1）。
const FunctionName = "qimeng_search_token"

// nArgs 固定两参数：搜索文本、单个关键词 token。
const nArgs = 2

// init 在任何连接打开前把确定性标量函数登记到 modernc 驱动。
//
// 注册失败意味着所有带 q 的查询都会报 "no such function" 而整体失败；
// 这里直接 panic，让问题在进程启动期暴露，禁止静默退化成旧子串语义
// （否则会复活「11 命中 110」缺陷）。
func init() {
	if err := sqlite.RegisterDeterministicScalarFunction(FunctionName, nArgs, scalar); err != nil {
		panic(fmt.Sprintf("searchtoken: 注册 %s 失败: %v", FunctionName, err))
	}
}

// scalar 是 SQL 函数入口：命中返回 1，未命中返回 0；NULL/非文本参数按未命中。
func scalar(_ *sqlite.FunctionContext, args []driver.Value) (driver.Value, error) {
	if len(args) != nArgs {
		return nil, fmt.Errorf("searchtoken: %s 需要 %d 个参数，实际 %d", FunctionName, nArgs, len(args))
	}
	text, textOK := asText(args[0])
	token, tokenOK := asText(args[1])
	if !textOK || !tokenOK {
		return int64(0), nil
	}
	if Match(text, token) {
		return int64(1), nil
	}
	return int64(0), nil
}

// asText 把 SQLite 值转成字符串；NULL 与非文本值直接视为未命中。
func asText(value driver.Value) (string, bool) {
	switch typed := value.(type) {
	case string:
		return typed, true
	case []byte:
		return string(typed), true
	case nil:
		return "", false
	default:
		return "", false
	}
}

// Match 判定单个关键词 token 是否命中搜索文本。
//
// 纯函数、不依赖 SQL；SQL 函数与单元测试共用这一份实现。
func Match(text, token string) bool {
	if token == "" {
		return false
	}
	// ASCII 折叠与旧谓词 lower() 等价；只改 ASCII 字节，非 ASCII 原样保留，
	// 保证字节偏移仍与原文一致（数字/括号判定按字节边界走）。
	hay := asciiLower(text)
	hayToken := asciiLower(token)
	if digits, ok := pageTokenDigits(hayToken); ok {
		return matchPageNumber(hay, digits)
	}
	if isASCIIDigits(hayToken) {
		return matchBareNumber(hay, hayToken)
	}
	return strings.Contains(hay, hayToken)
}

// matchBareNumber 匹配独立数字段；紧跟开括号的数字是组图页码，裸数字不命中。
func matchBareNumber(hay, digits string) bool {
	for offset := 0; offset <= len(hay)-len(digits); {
		index := strings.Index(hay[offset:], digits)
		if index < 0 {
			return false
		}
		start := offset + index
		end := start + len(digits)
		if numberSegmentAt(hay, start, end) && !openingBracketBefore(hay, start) {
			return true
		}
		offset = start + 1
	}
	return false
}

// matchPageNumber 匹配组图页码：数字段前后是开/闭括号（全角/半角互认）。
func matchPageNumber(hay, digits string) bool {
	for offset := 0; offset <= len(hay)-len(digits); {
		index := strings.Index(hay[offset:], digits)
		if index < 0 {
			return false
		}
		start := offset + index
		end := start + len(digits)
		if numberSegmentAt(hay, start, end) && openingBracketBefore(hay, start) && closingBracketAfter(hay, end) {
			return true
		}
		offset = start + 1
	}
	return false
}

// numberSegmentAt 检查 [start,end) 的数字段前后都不是 ASCII 数字。
func numberSegmentAt(hay string, start, end int) bool {
	if start > 0 && isASCIIDigitByte(hay[start-1]) {
		return false
	}
	if end < len(hay) && isASCIIDigitByte(hay[end]) {
		return false
	}
	return true
}

func openingBracketBefore(hay string, index int) bool {
	if index <= 0 {
		return false
	}
	r, _ := utf8.DecodeLastRuneInString(hay[:index])
	return isOpeningBracket(r)
}

func closingBracketAfter(hay string, index int) bool {
	if index >= len(hay) {
		return false
	}
	r, _ := utf8.DecodeRuneInString(hay[index:])
	return isClosingBracket(r)
}

// pageTokenDigits 识别括号页码 token；只接受配对的括号族。
func pageTokenDigits(token string) (string, bool) {
	if len(token) < 3 {
		return "", false
	}
	first, firstSize := utf8.DecodeRuneInString(token)
	last, lastSize := utf8.DecodeLastRuneInString(token)
	if !bracketPair(first, last) {
		return "", false
	}
	inner := token[firstSize : len(token)-lastSize]
	if !isASCIIDigits(inner) {
		return "", false
	}
	return inner, true
}

// bracketPair 判断 token 首尾是否是同一括号族（正文侧允许全角/半角混用）。
func bracketPair(opening, closing rune) bool {
	return (opening == '(' && closing == ')') ||
		(opening == '（' && closing == '）') ||
		(opening == '[' && closing == ']') ||
		(opening == '【' && closing == '】')
}

func isOpeningBracket(r rune) bool {
	return r == '(' || r == '（' || r == '[' || r == '【'
}

func isClosingBracket(r rune) bool {
	return r == ')' || r == '）' || r == ']' || r == '】'
}

func isASCIIDigits(s string) bool {
	if s == "" {
		return false
	}
	for i := 0; i < len(s); i++ {
		if !isASCIIDigitByte(s[i]) {
			return false
		}
	}
	return true
}

func isASCIIDigitByte(c byte) bool {
	return c >= '0' && c <= '9'
}

// asciiLower 把 ASCII A-Z 折叠成小写；非 ASCII 字节原样保留（等价旧谓词
// lower() 的 ASCII 折叠口径，同时保持字节长度不变）。
func asciiLower(s string) string {
	out := []byte(nil)
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			if out == nil {
				out = []byte(s)
			}
			out[i] = c + ('a' - 'A')
		}
	}
	if out == nil {
		return s
	}
	return string(out)
}
