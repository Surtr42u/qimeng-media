package filing

import "testing"

// ftypHead 构造 ISO BMFF 头：[0:4]=box 大小 [4:8]="ftyp" [8:12]=major_brand。
func ftypHead(brand string) []byte {
	return []byte{0, 0, 0, 0x20, 'f', 't', 'y', 'p', brand[0], brand[1], brand[2], brand[3]}
}

// mkvHead 构造带 DocType 的最小 EBML 头。
func mkvHead(docType string) []byte {
	h := []byte{0x1A, 0x45, 0xDF, 0xA3}
	h = append(h, 0x42, 0x86, 0x81, 0x01)            // EBMLVersion 元素 = 1
	h = append(h, 0x42, 0x82, byte(0x80|len(docType))) // DocType 元素（ID 0x4282）
	h = append(h, docType...)
	return h
}

func TestSniffMagicKnown(t *testing.T) {
	tests := []struct {
		name  string
		head  []byte
		want  MediaType
	}{
		{"JPEG", []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00}, ImageJPEG},
		{"PNG", []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D}, ImagePNG},
		{"GIF87a", append([]byte("GIF87a"), 0x01, 0x00), ImageGIF},
		{"GIF89a", append([]byte("GIF89a"), 0x01, 0x00), ImageGIF},
		{"WEBP", append([]byte("RIFF\x24\x00\x00\x00WEBPVP8 "), 0x00), ImageWEBP},
		{"AVIF", ftypHead("avif"), ImageAVIF},
		{"AVIS 序列", ftypHead("avis"), ImageAVIF},
		{"MOV qt brand", ftypHead("qt  "), VideoQuickTime},
		{"AVI", append([]byte("RIFF\x24\x00\x00\x00AVI LIST"), 0x00), VideoAVI},
		{"MKV DocType=matroska", mkvHead("matroska"), VideoMatroska},
		{"WebM DocType=webm", mkvHead("webm"), VideoWEBM},
	}
	mp4Brands := []string{"isom", "iso2", "mp41", "mp42", "avc1", "dash", "av01", "MSNV"}
	for _, b := range mp4Brands {
		tests = append(tests, struct {
			name string
			head []byte
			want MediaType
		}{"MP4 brand " + b, ftypHead(b), VideoMP4})
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, ok := SniffMagic(tt.head)
			if !ok {
				t.Fatalf("SniffMagic 未能识别 %v 的头部", tt.head)
			}
			if got != tt.want {
				t.Fatalf("SniffMagic = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestSniffMagicRejected(t *testing.T) {
	tests := []struct {
		name string
		head []byte
	}{
		{"空 head", nil},
		{"PE 可执行文件（伪装 jpg 的 exe）", []byte{'M', 'Z', 0x90, 0x00, 0x03, 0x00, 0, 0}},
		{"PDF", []byte("%PDF-1.7")},
		{"Shell 脚本", []byte("#!/bin/sh\n")},
		{"ELF", []byte{0x7F, 'E', 'L', 'F', 0x02}},
		{"截断的 PNG（仅 3 字节）", []byte{0x89, 'P', 'N'}},
		{"截断的 JPEG（仅 2 字节）", []byte{0xFF, 0xD8}},
		{"截断的 RIFF（不足 12 字节无法区分）", []byte("RIFF\x00\x00\x00")},
		{"RIFF 但 form type 是 WAVE", []byte("RIFF\x24\x00\x00\x00WAVEfmt ")},
		{"ftyp 未知 brand", ftypHead("3gp5")},
		{"ftyp 位置错位（不在 [4:8]）", []byte("fXtypisom0000")},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, ok := SniffMagic(tt.head)
			if ok {
				t.Fatalf("SniffMagic(%v) = %q, 想要未识别", tt.head, got)
			}
		})
	}
}

// TestMatroskaGarbageFallsBackToMKV 验证"区分不了归 mkv"的约定：
// 畸形 EBML（非 webm DocType）仍识别为 Matroska 家族。
func TestMatroskaGarbageFallsBackToMKV(t *testing.T) {
	got, ok := SniffMagic(append([]byte{0x1A, 0x45, 0xDF, 0xA3}, 0xFF, 0xFF, 0xFF, 0xFF, 0x00))
	if !ok || got != VideoMatroska {
		t.Fatalf("畸形 Matroska 头应归 mkv, got %q ok=%v", got, ok)
	}
}

// TestWebMVsMKVDistinction webm/mkv 的唯一区分依据是 DocType。
func TestWebMVsMKVDistinction(t *testing.T) {
	if got, _ := SniffMagic(mkvHead("webm")); got != VideoWEBM {
		t.Fatalf("DocType=webm 应识别为 webm, got %q", got)
	}
	if got, _ := SniffMagic(mkvHead("matroska")); got != VideoMatroska {
		t.Fatalf("DocType=matroska 应识别为 mkv, got %q", got)
	}
}
