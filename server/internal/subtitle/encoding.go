package subtitle

import (
	"bytes"
	"encoding/binary"
	"errors"
	"unicode/utf16"
	"unicode/utf8"

	"golang.org/x/text/encoding"
	"golang.org/x/text/encoding/japanese"
	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/encoding/traditionalchinese"
	"golang.org/x/text/transform"
)

// 字幕正文编码归一（2026-11-28 外挂字幕第二批，用户口径）。
//
// 现状：Windows 上导出的 .ass/.srt 大量是 GBK/GB18030，直接按 UTF-8 下发会
// 整段乱码。服务端在发文件前统一转成 UTF-8（三端解码器都按 UTF-8 读），
// 客户端零改动。
//
// 顺序（用户指定）：BOM 优先（UTF-8 / UTF-16LE / UTF-16BE）→ 严格 UTF-8 校验
// → GB18030 → Big5 → Shift-JIS。无 BOM 的探测本质是启发式：GB18030 的双字节
// 空间覆盖极广，多数 Big5/Shift-JIS 文本会被 GB18030 「成功」解成错字——这是
// 该顺序的已知边界，按用户口径保留（要精确识别需要 chardet 类模型，不在本批）。
var (
	// ErrUnknownEncoding 所有候选编码都无法解码（二进制垃圾/损坏文件）。
	ErrUnknownEncoding = errors.New("subtitle: 无法识别字幕文本编码")
	// ErrInvalidUTF16 UTF-16 BOM 声明但字节数不是偶数。
	ErrInvalidUTF16 = errors.New("subtitle: UTF-16 字幕字节长度不是偶数")
)

// 候选编码顺序即探测顺序；GB18030 必须最先尝试（覆盖 GBK/GB2312 超集）。
var fallbackEncodings = []encoding.Encoding{
	simplifiedchinese.GB18030,
	traditionalchinese.Big5,
	japanese.ShiftJIS,
}

var (
	utf8BOM    = []byte{0xEF, 0xBB, 0xBF}
	utf16LEBOM = []byte{0xFF, 0xFE}
	utf16BEBOM = []byte{0xFE, 0xFF}
)

// DecodeToUTF8 把字幕正文统一成 UTF-8。输入数据不会被修改（返回的切片可能
// 直接复用输入，调用方不得就地改写）。
func DecodeToUTF8(data []byte) ([]byte, error) {
	switch {
	case bytes.HasPrefix(data, utf8BOM):
		return data[len(utf8BOM):], nil
	case bytes.HasPrefix(data, utf16LEBOM):
		return decodeUTF16(data[len(utf16LEBOM):], binary.LittleEndian)
	case bytes.HasPrefix(data, utf16BEBOM):
		return decodeUTF16(data[len(utf16BEBOM):], binary.BigEndian)
	}
	if utf8.Valid(data) {
		return data, nil
	}
	for _, enc := range fallbackEncodings {
		// 解码器对非法序列返回错误即视为不匹配；能解出合法 UTF-8 就采用。
		// 解码成功但含替换符（U+FFFD）同样视为不可信（多半是错编码解出残字）。
		out, _, err := transform.Bytes(enc.NewDecoder(), data)
		if err != nil || !utf8.Valid(out) || bytes.ContainsRune(out, utf8.RuneError) {
			continue
		}
		return out, nil
	}
	return nil, ErrUnknownEncoding
}

// decodeUTF16 按指定字节序解码 UTF-16（BOM 已在调用方剥离）。
// 落单的代理项由 utf16.Decode 归一为 U+FFFD，不为此拒绝整份字幕。
func decodeUTF16(data []byte, order binary.ByteOrder) ([]byte, error) {
	if len(data)%2 != 0 {
		return nil, ErrInvalidUTF16
	}
	units := make([]uint16, 0, len(data)/2)
	for i := 0; i < len(data); i += 2 {
		units = append(units, order.Uint16(data[i:]))
	}
	return []byte(string(utf16.Decode(units))), nil
}
