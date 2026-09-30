package thumbnail

import (
	"bytes"
	"encoding/binary"
	"io"
	"os"
)

// 本文件解决 DOMAIN_RULES §11「静图方向」问题：竖拍手机照（EXIF
// Orientation=6 等）的 JPEG 像素按拍摄姿态存储。ffmpeg 对静图 JPEG 的 EXIF
// 处理**随版本漂移**——新版解码期隐式转向（本仓 9.0.1 实证：默认解出已
// 转正的帧）、旧版不转，放任隐式行为则同一张图跨部署方向不同且错误方向进
// immutable 缓存。因此固定两件事：scaleStill 输入侧 -noautorotate 关闭隐式
// 行为（见 ffmpeg.go），本文件读方向、显式换 ffmpeg 滤镜——显式路径是唯一
// 方向权威，不依赖任何版本行为。浏览器显示原图自行按 EXIF 转向（CSS
// image-orientation 默认 from-image），故原图直链不需要服务端处理。

// exifOrientation EXIF Orientation tag 值（CIPA DC-008 EXIF 规范：
// 0th row/column 描述存储像素的方位，显示时需按下表反转变换到正立）。
type exifOrientation uint16

const (
	// orientationNormal 正常方向（1）：0th row 在上、0th column 在左，
	// 无需变换。也是一切解析失败的兜底值（解析不到 ≠ 报错，按无方向处理）。
	orientationNormal exifOrientation = 1
	// orientationMirrorH 水平镜像（2）：0th row 在上、0th column 在右。
	orientationMirrorH exifOrientation = 2
	// orientationRot180 旋转 180°（3）：0th row 在下、0th column 在右。
	orientationRot180 exifOrientation = 3
	// orientationMirrorV 垂直镜像（4）：0th row 在下、0th column 在左。
	orientationMirrorV exifOrientation = 4
	// orientationTranspose 主对角线翻转（5）：0th row 在左、0th column 在上。
	orientationTranspose exifOrientation = 5
	// orientationRot90CW 需顺时针转 90°（6）：0th row 在右、0th column 在上
	// ——手机竖拍横存（传感器横向、用户竖握）最常见档。
	orientationRot90CW exifOrientation = 6
	// orientationTransverse 副对角线翻转（7）：0th row 在右、0th column 在下。
	orientationTransverse exifOrientation = 7
	// orientationRot90CCW 需逆时针转 90°（8）：0th row 在左、0th column 在下。
	orientationRot90CCW exifOrientation = 8
)

// orientationFilterChain 把 EXIF Orientation 映射为 ffmpeg -vf 滤镜链段
//（空串 = 无需变换）。映射依据（注释即规格，两份文档对齐）：
//   - EXIF 侧：CIPA DC-008 各值的 0th row/column 方位（见上方常量注释）；
//   - ffmpeg 侧：transpose 滤镜 dir 取值语义（`ffmpeg -h filter=transpose`
//     官方输出）：0=rotate counter-clockwise with vertical flip、
//     1=rotate clockwise、2=rotate counter-clockwise、
//     3=rotate clockwise with vertical flip。
//
// 推导（每档均被 orientation_test.go 的真实 ffmpeg 像素级用例实测锁定，
// 不许纸面定案）：
//
//	2 = 水平镜像                 → hflip
//	3 = 旋转 180°                → hflip,vflip（连续两次镜像）
//	4 = 垂直镜像                 → vflip
//	5 = 主对角线翻转（transpose）  → transpose=0（= 逆时针 90°+垂直翻转，
//	    两步复合恰为主对角线翻转；等价 rot90CW 后再 hflip，取单滤镜形态）
//	6 = 顺时针 90°               → transpose=1
//	7 = 副对角线翻转（transverse） → transpose=3（= 顺时针 90°+垂直翻转，
//	    两步复合恰为副对角线翻转；等价 rot90CW 后再 vflip，取单滤镜形态）
//	8 = 逆时针 90°               → transpose=2
func orientationFilterChain(o exifOrientation) string {
	switch o {
	case orientationMirrorH:
		return "hflip"
	case orientationRot180:
		return "hflip,vflip"
	case orientationMirrorV:
		return "vflip"
	case orientationTranspose:
		return "transpose=0"
	case orientationRot90CW:
		return "transpose=1"
	case orientationTransverse:
		return "transpose=3"
	case orientationRot90CCW:
		return "transpose=2"
	default:
		// 1 与一切未知值：不转。未知值按 1 兜底而非报错——方向解析失败
		// 不该阻断缩略图生成（宁出未旋转图，不出"无缩略图"）。
		return ""
	}
}

// exifScanMaxBytes 是 EXIF 解析的读取字节上限。EXIF APP1 段按规范必须位于
// 文件头部附近（首个 APPn），缩略图判定需要的 SOI→APP1→IFD0 链通常在 1KB 内；
// 上限只为防御畸形文件（超长无 APP1 的段序列）把内存读爆，取值宽裕即可。
const exifScanMaxBytes = 1 << 18 // 256KiB

// jpegOrientationOf 读出 srcPath 的 EXIF Orientation；非 JPEG/解析失败/无
// 该 tag 一律返回 orientationNormal（1）。为什么吞错不上报：方向解析是
// 缩略图生成的旁路增强，失败（含文件不可读——ffmpeg 侧马上会以更准确的
// 语境报同一个错）不应改变生成管线的错误路径。
func jpegOrientationOf(srcPath string) exifOrientation {
	f, err := os.Open(srcPath)
	if err != nil {
		return orientationNormal
	}
	defer func() { _ = f.Close() }()
	return parseJPEGOrientation(f)
}

// parseJPEGOrientation 从任意 reader 解析 JPEG EXIF Orientation（带扫描上限，
// 见 exifScanMaxBytes）。独立于磁盘入口以便单测直喂字节流。
func parseJPEGOrientation(r io.Reader) exifOrientation {
	data, err := io.ReadAll(io.LimitReader(r, exifScanMaxBytes))
	if err != nil {
		return orientationNormal
	}
	return jpegOrientationFromBytes(data)
}

// JPEG 段标记（ISO/IEC 10918-1）：SOI 文件头、SOS 扫描数据起点（其后是
// 熵编码数据，段结构结束，EXIF 不可能再出现）、APP1 是 EXIF 所在应用段。
const (
	jpegMarkerSOI  = 0xD8
	jpegMarkerSOS  = 0xDA
	jpegMarkerAPP1 = 0xE1
)

// exifHeaderPrefix 是 APP1 EXIF 段的载荷前缀（"Exif\0\0"，6 字节）。
var exifHeaderPrefix = []byte{'E', 'x', 'i', 'f', 0x00, 0x00}

// jpegOrientationFromBytes 解析 JPEG 字节流：走 SOI→段链，只认 APP1 EXIF 的
// IFD0 Orientation tag，其它段/其它 EXIF 内容一概跳过（任务口径：只解析
// Orientation 一个 tag）。任何结构不符都返回 orientationNormal，绝不 panic。
func jpegOrientationFromBytes(b []byte) exifOrientation {
	// SOI（FF D8）必须打头；不足一个最短段头的字节量直接按无方向处理。
	if len(b) < 4 || b[0] != 0xFF || b[1] != jpegMarkerSOI {
		return orientationNormal
	}
	pos := 2
	for pos < len(b) {
		// 段一律以 0xFF 前导（个别编码器会连发多个 0xFF 填充，逐个跳过）。
		for pos < len(b) && b[pos] == 0xFF {
			pos++
		}
		if len(b)-pos < 3 { // 标记字节 + 段长共 3 字节：只保 2 会让段长读取越界（审查 P1 修复）
			return orientationNormal
		}
		marker := b[pos]
		pos++
		switch {
		case marker == jpegMarkerSOI:
			continue // 异常的嵌套 SOI：容忍并继续
		case marker == jpegMarkerSOS:
			return orientationNormal // 扫描数据开始，EXIF 不会再出现
		case marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7):
			continue // TEM/RSTn：无长度字段的独立标记
		}
		segLen := int(binary.BigEndian.Uint16(b[pos : pos+2]))
		if segLen < 2 || len(b)-pos < segLen {
			return orientationNormal // 段长含自身两字节，<2 即畸形；越界=被截断
		}
		seg := b[pos+2 : pos+segLen]
		if marker == jpegMarkerAPP1 {
			if o := exifOrientationInAPP1(seg); o != orientationNormal {
				return o
			}
			// 非 EXIF 的 APP1（XMP 等）与 EXIF 段内没找到 tag：继续扫后续段
			//（多 APP1 场景取第一个含 Orientation 的）。
		}
		pos += segLen
	}
	return orientationNormal
}

// TIFF 常量（TIFF 6.0 规范）：魔数 42；IFD 表项固定 12 字节；Orientation
// tag = 0x0112、类型 SHORT = 3。EXIF 的 IFD 结构就是内嵌的一段 TIFF。
const (
	tiffMagic      = 42
	tiffIFDEntrySz = 12
	exifTagOrientation = 0x0112
	tiffTypeShort      = 3
)

// exifOrientationInAPP1 在 APP1 段载荷内解析 TIFF IFD0 的 Orientation：
// "Exif\0\0" 前缀 → TIFF 头（字节序 II/MM + 魔数 42 + IFD0 偏移）→ IFD0
// 表项线性扫一遍。只认 SHORT 且 count=1 的 Orientation 表项（这正是全部
// 现实写法），其它形态放弃（返回 1），不做通用 EXIF 解析。
func exifOrientationInAPP1(seg []byte) exifOrientation {
	// 前缀 6 字节 + TIFF 头 8 字节（字节序 2 + 魔数 2 + IFD0 偏移 4）是最小完整
	// 形态；短于此的段按无方向处理（审查 P1 修复：原 8 字节下界放过了 8-13 字节
	// 段，TIFF 头读取越界）。
	if len(seg) < len(exifHeaderPrefix)+8 || !bytes.HasPrefix(seg, exifHeaderPrefix) {
		return orientationNormal
	}
	tiff := seg[len(exifHeaderPrefix):]
	var bo binary.ByteOrder = binary.BigEndian
	switch {
	case tiff[0] == 'I' && tiff[1] == 'I': // 小端（Intel），手机照片绝对主流
		bo = binary.LittleEndian
	case tiff[0] == 'M' && tiff[1] == 'M': // 大端（Motorola）
	default:
		return orientationNormal
	}
	if bo.Uint16(tiff[2:4]) != tiffMagic {
		return orientationNormal
	}
	ifd0 := int(bo.Uint32(tiff[4:8]))
	if ifd0+2 > len(tiff) {
		return orientationNormal
	}
	entries := int(bo.Uint16(tiff[ifd0 : ifd0+2]))
	for i := 0; i < entries; i++ {
		off := ifd0 + 2 + i*tiffIFDEntrySz
		if off+tiffIFDEntrySz > len(tiff) {
			break // 表项越界：文件被截断，已扫过的没命中就按无方向
		}
		entry := tiff[off : off+tiffIFDEntrySz]
		if bo.Uint16(entry[0:2]) != exifTagOrientation {
			continue
		}
		// SHORT(3) 且 count=1：值内联在表项后 4 字节的前 2 字节（TIFF 规范：
		// ≤4 字节的值不另设偏移）；其它类型/计数不认，按无方向兜底。
		if bo.Uint16(entry[2:4]) != tiffTypeShort || bo.Uint32(entry[4:8]) != 1 {
			return orientationNormal
		}
		if v := bo.Uint16(entry[8:10]); v >= uint16(orientationNormal) && v <= uint16(orientationRot90CCW) {
			return exifOrientation(v)
		}
		return orientationNormal // 值越界（0 或 >8）：畸形，按无方向
	}
	return orientationNormal
}
