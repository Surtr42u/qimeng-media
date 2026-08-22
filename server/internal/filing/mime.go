package filing

import (
	"bytes"
	"math/bits"
)

// MediaType 是嗅探与扩展名映射统一使用的 MIME 类型标识。
type MediaType string

// 白名单内媒体类型（IANA 风格 MIME 字符串）。
const (
	ImageJPEG      MediaType = "image/jpeg"        // .jpg/.jpeg
	ImagePNG       MediaType = "image/png"         // .png
	ImageGIF       MediaType = "image/gif"         // .gif
	ImageWEBP      MediaType = "image/webp"       // .webp
	ImageAVIF      MediaType = "image/avif"       // .avif
	VideoMP4       MediaType = "video/mp4"         // .mp4/.m4v
	VideoQuickTime MediaType = "video/quicktime"   // .mov
	VideoMatroska  MediaType = "video/x-matroska"  // .mkv
	VideoWEBM      MediaType = "video/webm"        // .webm
	VideoAVI       MediaType = "video/x-msvideo"   // .avi
)

// RecommendedHeadBytes 是做一次可靠嗅探建议读取的文件头长度。
// 魔数最长只需 12 字节，但 webm/mkv 区分需要解析 EBML 头找 DocType（通常在前
// 64 字节内，留 512 字节余量覆盖畸形头部）。
const RecommendedHeadBytes = 512

// SniffMagic 按文件头魔数判断媒体类型，完全不信扩展名（docs/SECURITY.md：
// "MIME 嗅探按文件头魔数判断（不信 Content-Type 声明）"）。
//
// 魔数表来源（每条在分支处注明）：
//   - JPEG FF D8 FF：ITU-T T.81（JPEG）SOI 起始标记 + 第一个 marker 前缀；
//   - PNG 89 50 4E 47 0D 0A 1A 0A：W3C PNG Specification / RFC 2083 §3.1.1 签名；
//   - GIF "GIF87a"/"GIF89a"：GIF87a/89a 规范头；
//   - WEBP "RIFF"+len+"WEBP"：Google WebP Container Specification；
//   - AVIF "ftyp"+"avif"/"avis"：AOM AV1 Still Image File Format（ISO/IEC 14496-12 容器）；
//   - MP4 "ftyp"+brand：ISO/IEC 14496-12（ISOBMFF）major_brand；
//   - MOV "ftyp"+"qt  "：Apple QuickTime File Format（注意 brand 是 'q','t',' ',' '，
//     两个尾随空格——这是 avif/avi/quicktime 三者最易写错的地方）；
//   - MKV/WebM 1A 45 DF A3：Matroska 规范 EBML 头魔数；
//   - AVI "RIFF"+len+"AVI "：Microsoft RIFF/AVI 规范。
//
// avif / quicktime / mp4 的区分逻辑：三者都是 ISO BMFF 容器（[0:4]=box 大小、
// [4:8]="ftyp"、[8:12]=major_brand），只依据 major_brand 精确区分——major_brand
// 是封装器声明的主类型；其后的兼容 brand 列表（compatible_brands）常同时含多个
// 家族的 brand（如 mp4 文件兼容列表里带 "qt  "），不能作为主类型依据。
// 未知 major_brand 返回未识别：brand 集合无法穷举，宁可拒绝罕见变体（用户可改名
// 或重新封装）也不放宽伪造面——四道校验的安全收益依赖这张表足够紧。
//
// webp / avi 的区分逻辑：二者共用 RIFF 壳（[0:4]="RIFF"、[4:8]=小端长度），
// 由 [8:12] 的 form type 区分（"WEBP" vs "AVI "）。RIFF 头不足 12 字节时无法
// 区分，按未识别处理。
func SniffMagic(head []byte) (MediaType, bool) {
	switch {
	case bytes.HasPrefix(head, []byte{0xFF, 0xD8, 0xFF}):
		return ImageJPEG, true

	case bytes.HasPrefix(head, []byte{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}):
		return ImagePNG, true

	case bytes.HasPrefix(head, []byte("GIF87a")), bytes.HasPrefix(head, []byte("GIF89a")):
		return ImageGIF, true

	case bytes.HasPrefix(head, []byte("RIFF")):
		if len(head) < 12 {
			return "", false
		}
		switch string(head[8:12]) {
		case "WEBP":
			return ImageWEBP, true
		case "AVI ":
			return VideoAVI, true
		}
		return "", false // 其它 RIFF form type（WAVE 等）不在白名单

	case bytes.HasPrefix(head, []byte{0x1A, 0x45, 0xDF, 0xA3}):
		// Matroska/WebM 共用 EBML 头魔数，是同一容器的两个 profile，
		// 唯一可靠区分点是 EBML 头内的 DocType 元素（"webm"/"matroska"）。
		// DocType 位置不定长（变长整数编码），需做最小化 EBML 解析；
		// 解析失败、head 截断取不到 DocType 时归 mkv——webm 是 mkv 的严格子集
		// （仅 VP8/VP9/AV1 codec），"归父类型"方向不会放过额外风险；不规范的
		// .webm 文件（DocType 缺失）会因扩展名交叉校验不符被拒，属可接受代价。
		switch matroskaDocType(head) {
		case "webm":
			return VideoWEBM, true
		default:
			return VideoMatroska, true
		}

	case len(head) >= 12 && string(head[4:8]) == "ftyp":
		switch brand := string(head[8:12]); brand {
		case "avif", "avis":
			return ImageAVIF, true
		case "qt  ":
			return VideoQuickTime, true
		case "isom", "iso2", "mp41", "mp42", "avc1", "dash", "av01", "MSNV":
			// MP4 家族常见 major_brand：isom（ffmpeg 默认）/iso2/mp41/mp42 见
			// ISO/IEC 14496-12/-14；avc1 见 14496-15（AVC）；dash 见 MPEG-DASH；
			// av01 为 AV1-in-ISOBMFF（AOM）；MSNV 为 PSP/PS3 录制封装实践值。
			return VideoMP4, true
		}
		return "", false
	}
	return "", false
}

// matroskaDocType 在 head 内做最小化 EBML 头解析，返回 DocType 元素的字符串值。
// EBML（RFC 8554 / Matroska 规范）：元素 = 元素ID（变长）+ 尺寸（变长）+ 数据；
// DocType 的元素 ID 是 0x4282，必然出现在 EBML 头顶层。
// 任何越界、畸形、截断一律返回 ""（调用方按 mkv 处理，见 SniffMagic 注释）。
func matroskaDocType(head []byte) string {
	pos := 4 // 跳过 4 字节 EBML 魔数
	for pos < len(head) {
		id, nID, ok := readEBMLVint(head[pos:], false)
		if !ok {
			return ""
		}
		pos += nID
		size, nSize, ok := readEBMLVint(head[pos:], true)
		if !ok {
			return ""
		}
		pos += nSize
		if size > uint64(len(head)-pos) {
			return "" // 元素数据被截断
		}
		if id == 0x4282 {
			return string(head[pos : pos+int(size)])
		}
		pos += int(size)
	}
	return ""
}

// readEBMLVint 读取一个 EBML 变长整数。stripMarker 指示是否剥离首字节的 marker 位
//（尺寸 vint 需要剥离，元素 ID 保留全部位）。返回值、消耗字节数、是否合法。
func readEBMLVint(b []byte, stripMarker bool) (uint64, int, bool) {
	if len(b) == 0 || b[0] == 0 {
		return 0, 0, false // 首字节为 0 非法（EBML 要求 marker 落在首字节）
	}
	n := bits.LeadingZeros8(b[0]) + 1 // marker 位置决定总字节数（1~8）
	if n > 8 || n > len(b) {
		return 0, 0, false
	}
	var v uint64
	if stripMarker {
		v = uint64(b[0] & (0xFF >> n)) // 去掉 marker 位
	} else {
		v = uint64(b[0])
	}
	for _, c := range b[1:n] {
		v = v<<8 | uint64(c)
	}
	if stripMarker && v == 1<<(7*n)-1 {
		// 全 1 是 unknown size（流式元素），DocType 不会使用，视为畸形
		return 0, 0, false
	}
	return v, n, true
}
