package subtitle

import (
	"bytes"
	"encoding/binary"
	"errors"
	"testing"
	"unicode/utf16"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

// encodeGB18030 用 x/text 编码器造 GB18030 样本（测试数据不自带二进制）。
func encodeGB18030(t *testing.T, s string) []byte {
	t.Helper()
	out, _, err := transform.Bytes(simplifiedchinese.GB18030.NewEncoder(), []byte(s))
	if err != nil {
		t.Fatalf("编码 GB18030 失败: %v", err)
	}
	return out
}

// encodeUTF16 按指定字节序加 BOM 编码 UTF-16。
func encodeUTF16(s string, little bool) []byte {
	units := utf16.Encode([]rune(s))
	var buf bytes.Buffer
	if little {
		buf.Write(utf16LEBOM)
		for _, u := range units {
			_ = binary.Write(&buf, binary.LittleEndian, u)
		}
		return buf.Bytes()
	}
	buf.Write(utf16BEBOM)
	for _, u := range units {
		_ = binary.Write(&buf, binary.BigEndian, u)
	}
	return buf.Bytes()
}

const sampleChinese = "[Script Info]\n标题：荒坂 夜之城\nDialogue: 你好，世界！\n"

// TestDecodeToUTF8Encodings 覆盖用户要求的四种编码：UTF-8、UTF-8 BOM、UTF-16LE、GB18030。
func TestDecodeToUTF8Encodings(t *testing.T) {
	cases := []struct {
		name string
		in   []byte
	}{
		{"UTF-8", []byte(sampleChinese)},
		{"UTF-8 BOM", append([]byte{0xEF, 0xBB, 0xBF}, []byte(sampleChinese)...)},
		{"UTF-16LE", encodeUTF16(sampleChinese, true)},
		{"UTF-16BE", encodeUTF16(sampleChinese, false)},
		{"GB18030", encodeGB18030(t, sampleChinese)},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := DecodeToUTF8(c.in)
			if err != nil {
				t.Fatalf("DecodeToUTF8 报错: %v", err)
			}
			if string(got) != sampleChinese {
				t.Fatalf("解码结果 = %q, want %q", got, sampleChinese)
			}
		})
	}
}

// TestDecodeToUTF8Rejects 覆盖无法识别/损坏输入：UTF-16 奇数长度与三编码都解不了的字节。
func TestDecodeToUTF8Rejects(t *testing.T) {
	if _, err := DecodeToUTF8(append(append([]byte{}, utf16LEBOM...), 0x41)); !errors.Is(err, ErrInvalidUTF16) {
		t.Fatalf("奇数长度 UTF-16 应报 ErrInvalidUTF16，得到 %v", err)
	}
	// 0xFF 在 GB18030/Big5/Shift-JIS 里都不是合法首字节
	if _, err := DecodeToUTF8([]byte{0xFF, 0xFF, 0xFF, 0xFF}); !errors.Is(err, ErrUnknownEncoding) {
		t.Fatalf("垃圾字节应报 ErrUnknownEncoding，得到 %v", err)
	}
}
