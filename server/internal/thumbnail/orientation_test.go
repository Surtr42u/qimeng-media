package thumbnail

import (
	"bytes"
	"context"
	"encoding/binary"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"
)

// 本文件覆盖 EXIF 方向显式处理的三层：解析（纯单测，字节流直喂）、
// 方向→ffmpeg 滤镜映射表（纯单测锁定）、真实 ffmpeg 端到端（带 EXIF 夹具
// 经 scaleStill 产物逐象限像素断言——映射表不许纸面定案，以夹具实测为准）。

// 四象限基准图尺寸：宽为高两倍的非方形（方向滤镜换维才能从输出尺寸显形）；
// 边长取 4 的倍数，使象限中心采样点远离 JPEG 色度子采样的象限边界混色带。
const (
	quadW = 32
	quadH = 16
	// quadTolerance 四象限纯色经 JPEG 有损编码（基准 75 + mjpeg q:v 4）的
	// 逐通道容差。四象限色两两距离 ≥255，60 足以吸收色度抽样/量化漂移
	// 而不产生误判重叠。
	quadTolerance = 60
)

// quadrant 基准象限色（RGB 各 8bit，测试期望值的唯一来源）。
type quadrant struct{ r, g, b uint8 }

var (
	quadRed   = quadrant{255, 0, 0}
	quadGreen = quadrant{0, 255, 0}
	quadBlue  = quadrant{0, 0, 255}
	quadWhite = quadrant{255, 255, 255}
)

// quadColorAt 存储像素 (r,c) 的基准象限色：左上红/右上绿/左下蓝/右下白。
// 四色互异且横竖两个轴向都有对比，水平/垂直镜像与各档旋转都能唯一显形。
func quadColorAt(r, c int) quadrant {
	switch {
	case r < quadH/2 && c < quadW/2:
		return quadRed
	case r < quadH/2:
		return quadGreen
	case c < quadW/2:
		return quadBlue
	default:
		return quadWhite
	}
}

// encodeQuadJPEG 生成无 EXIF 的四象限基准 JPEG 字节流（Go 标准库编码，
// 不依赖 ffmpeg 合成——夹具本身要能独立于被测 ffmpeg 行为复现）。
func encodeQuadJPEG(t *testing.T) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, quadW, quadH))
	for r := 0; r < quadH; r++ {
		for c := 0; c < quadW; c++ {
			q := quadColorAt(r, c)
			img.SetRGBA(c, r, color.RGBA{R: q.r, G: q.g, B: q.b, A: 255})
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, nil); err != nil {
		t.Fatalf("编码基准 JPEG: %v", err)
	}
	return buf.Bytes()
}

// boAppendUint16/32 按指定字节序把整数追加进字节切片（binary.ByteOrder 接口
// 只有 Put* 没有 Append*，测试夹具用的两行封装）。
func boAppendUint16(dst []byte, bo binary.ByteOrder, v uint16) []byte {
	var tmp [2]byte
	bo.PutUint16(tmp[:], v)
	return append(dst, tmp[:]...)
}

func boAppendUint32(dst []byte, bo binary.ByteOrder, v uint32) []byte {
	var tmp [4]byte
	bo.PutUint32(tmp[:], v)
	return append(dst, tmp[:]...)
}

// buildExifAPP1 构造最小 APP1 EXIF 段："Exif\0\0" 前缀 + TIFF 头（指定字节
// 序）+ 仅一个 {tag, 类型, count, 值} 的 IFD0 表项。参数化是为了让测试能
// 造出生产不该出现的畸形形态（错误类型/越界值/无 Orientation tag）。
func buildExifAPP1(bo binary.ByteOrder, tag, typ uint16, count uint32, value uint16) []byte {
	tiff := make([]byte, 0, 26) // 8 头 + 2 表项数 + 12 表项 + 4 下一 IFD
	if bo == binary.LittleEndian {
		tiff = append(tiff, 'I', 'I')
	} else {
		tiff = append(tiff, 'M', 'M')
	}
	tiff = boAppendUint16(tiff, bo, tiffMagic)
	tiff = boAppendUint32(tiff, bo, 8) // IFD0 紧跟 8 字节 TIFF 头
	tiff = boAppendUint16(tiff, bo, 1) // IFD0 恰 1 个表项
	tiff = boAppendUint16(tiff, bo, tag)
	tiff = boAppendUint16(tiff, bo, typ)
	tiff = boAppendUint32(tiff, bo, count)
	tiff = boAppendUint16(tiff, bo, value)
	tiff = append(tiff, 0, 0)          // SHORT 值占满 4 字节值字段
	tiff = boAppendUint32(tiff, bo, 0) // 无下一 IFD

	payload := append([]byte(nil), exifHeaderPrefix...)
	payload = append(payload, tiff...)
	seg := make([]byte, 0, 4+len(payload))
	seg = append(seg, 0xFF, jpegMarkerAPP1)
	// 段长是 JPEG 侧字段恒大端；TIFF 字节序只在段内生效（两套规范不混淆）。
	seg = binary.BigEndian.AppendUint16(seg, uint16(len(payload)+2))
	return append(seg, payload...)
}

// insertAPP1AfterSOI 把 APP1 段插到 JPEG 的 SOI 之后（EXIF 规范要求 APP1
// 紧随 SOI 的位置，相机实拍文件均如此）。
func insertAPP1AfterSOI(jpg, app1 []byte) []byte {
	out := make([]byte, 0, len(jpg)+len(app1))
	out = append(out, jpg[:2]...) // SOI
	out = append(out, app1...)
	return append(out, jpg[2:]...)
}

// withOrientation 给基准 JPEG 写入指定字节序与方向的 EXIF（正常形态夹具）。
func withOrientation(t *testing.T, jpg []byte, bo binary.ByteOrder, o exifOrientation) []byte {
	t.Helper()
	return insertAPP1AfterSOI(jpg, buildExifAPP1(bo, exifTagOrientation, tiffTypeShort, 1, uint16(o)))
}

// makeOrientedJPEG 落盘一份带指定 EXIF 方向的四象限 JPEG，返回路径。
func makeOrientedJPEG(t *testing.T, dir, name string, bo binary.ByteOrder, o exifOrientation) string {
	t.Helper()
	path := filepath.Join(dir, name+".jpg")
	if err := os.WriteFile(path, withOrientation(t, encodeQuadJPEG(t), bo, o), 0o644); err != nil {
		t.Fatalf("写入 %s: %v", path, err)
	}
	return path
}

// displayedSampleStored 按 EXIF 规范的 0th row/column 语义独立推导
// "显示采样点 (sx,sy) → 存储像素 (r,c)" 映射——这是集成测试的判定基准，
// 与实现侧 orientationFilterChain 互为对镜：实现若把方向转错，象限色必不
// 匹配。逐档依据（0th row = 存储第 0 行在显示画面的方位，0th column 同理）：
//
//	1 行上列左 = 恒等                 → (sy, sx)
//	2 行上列右 = 水平镜像             → (sy, W-1-sx)
//	3 行下列右 = 旋转 180°            → (H-1-sy, W-1-sx)
//	4 行下列左 = 垂直镜像             → (H-1-sy, sx)
//	5 行左列上 = 主对角线翻转（transpose）→ (sx, sy)
//	6 行右列上 = 顺时针 90°           → (H-1-sx, sy)
//	7 行右列下 = 副对角线翻转（transverse）→ (H-1-sx, W-1-sy)
//	8 行左列下 = 逆时针 90°           → (sx, W-1-sy)
//
// 换维档（5-8）显示宽=存储高、显示高=存储宽，回绕借用存储侧两轴边长。
func displayedSampleStored(o exifOrientation, sx, sy int) (r, c int) {
	switch o {
	case orientationMirrorH:
		return sy, quadW - 1 - sx
	case orientationRot180:
		return quadH - 1 - sy, quadW - 1 - sx
	case orientationMirrorV:
		return quadH - 1 - sy, sx
	case orientationTranspose:
		return sx, sy
	case orientationRot90CW:
		return quadH - 1 - sx, sy
	case orientationTransverse:
		return quadH - 1 - sx, quadW - 1 - sy
	case orientationRot90CCW:
		return sx, quadW - 1 - sy
	default:
		return sy, sx
	}
}

// TestOrientationFilterChain 锁定方向→滤镜映射表：表就是本任务的行为契约
// （DOMAIN_RULES §11「静图方向」），任何一档被改动必须是有意为之。
func TestOrientationFilterChain(t *testing.T) {
	cases := []struct {
		o    exifOrientation
		want string
	}{
		{orientationNormal, ""},                // 1：不转
		{orientationMirrorH, "hflip"},          // 2
		{orientationRot180, "hflip,vflip"},     // 3
		{orientationMirrorV, "vflip"},          // 4
		{orientationTranspose, "transpose=0"},  // 5：CCW90+垂直翻转 = 主对角线翻转
		{orientationRot90CW, "transpose=1"},    // 6：顺时针 90°
		{orientationTransverse, "transpose=3"}, // 7：CW90+垂直翻转 = 副对角线翻转
		{orientationRot90CCW, "transpose=2"},   // 8：逆时针 90°
		{exifOrientation(0), ""},               // 越界值兜底不转
		{exifOrientation(9), ""},               // 越界值兜底不转
	}
	for _, tc := range cases {
		if got := orientationFilterChain(tc.o); got != tc.want {
			t.Errorf("orientation %d → %q，期望 %q", tc.o, got, tc.want)
		}
	}
}

// TestParseJPEGOrientation 解析层单测：正常 8 档（小端 II）/大端 MM/无 EXIF/
// 非 JPEG/截断/畸形 EXIF 全覆盖，失败一律回落 1 而非报错。
func TestParseJPEGOrientation(t *testing.T) {
	base := encodeQuadJPEG(t)

	t.Run("八档小端逐档读回", func(t *testing.T) {
		for o := orientationNormal; o <= orientationRot90CCW; o++ {
			got := parseJPEGOrientation(bytes.NewReader(withOrientation(t, base, binary.LittleEndian, o)))
			if got != o {
				t.Errorf("II 小端 Orientation=%d 读回 %d", o, got)
			}
		}
	})

	t.Run("大端MM同样可读", func(t *testing.T) {
		got := parseJPEGOrientation(bytes.NewReader(withOrientation(t, base, binary.BigEndian, orientationRot90CW)))
		if got != orientationRot90CW {
			t.Errorf("MM 大端 Orientation=6 读回 %d", got)
		}
	})

	t.Run("无EXIF与非JPEG一律为1", func(t *testing.T) {
		if got := parseJPEGOrientation(bytes.NewReader(base)); got != orientationNormal {
			t.Errorf("无 EXIF 读到 %d，期望 1", got)
		}
		png := append([]byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, make([]byte, 64)...)
		if got := parseJPEGOrientation(bytes.NewReader(png)); got != orientationNormal {
			t.Errorf("PNG 字节流读到 %d，期望 1（非 JPEG 恒无方向）", got)
		}
	})

	t.Run("截断与垃圾字节兜底为1", func(t *testing.T) {
		for name, data := range map[string][]byte{
			"空":      {},
			"只有SOI":  {0xFF, 0xD8},
			"SOI后截断": {0xFF, 0xD8, 0xFF, 0xE1, 0x00},
			"段长越界":   append([]byte{0xFF, 0xD8, 0xFF, 0xE1, 0xFF, 0xFF}, make([]byte, 4)...),
			"纯垃圾":    {0x12, 0x34, 0x56, 0x78},
		} {
			if got := parseJPEGOrientation(bytes.NewReader(data)); got != orientationNormal {
				t.Errorf("%s 读到 %d，期望兜底 1", name, got)
			}
		}
	})

	t.Run("紧容量切片不越界（审查P1回归）", func(t *testing.T) {
		// 经 ReadAll 的用例切片容量有富余，会掩盖越界读；这里用 cap==len 的字面量
		// 直喂底层解析函数，锁定边界守卫本身（2026-10-01 审查 P1：两处守卫各差
		// 1/6 字节时这两类输入曾可 panic 打挂进程）。
		shortSegLen := []byte{0xFF, 0xD8, 0xFF, 0xE1, 0x00} // 段长只读到 1 字节即截断
		shortTiffHeader := append(                          // APP1 载荷止于字节序对，TIFF 头不足 8 字节
			[]byte{0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x0A}, []byte("Exif\x00\x00II")...)[:14:14]
		for name, data := range map[string][]byte{
			"段长截断":    shortSegLen,
			"TIFF头截断": shortTiffHeader,
		} {
			if got := jpegOrientationFromBytes(data); got != orientationNormal {
				t.Errorf("%s 读到 %d，期望兜底 1", name, got)
			}
		}
	})

	t.Run("畸形EXIF形态兜底为1", func(t *testing.T) {
		bo := binary.LittleEndian
		cases := map[string][]byte{
			// 值越界（EXIF 只定义 1-8）：
			"值越界9": insertAPP1AfterSOI(base, buildExifAPP1(bo, exifTagOrientation, tiffTypeShort, 1, 9)),
			"值为0":  insertAPP1AfterSOI(base, buildExifAPP1(bo, exifTagOrientation, tiffTypeShort, 1, 0)),
			// 类型不是 SHORT（4=LONG）：解析器只认全现实写法，不造通用解析：
			"类型LONG": insertAPP1AfterSOI(base, buildExifAPP1(bo, exifTagOrientation, 4, 1, 6)),
			// APP1 载荷无 "Exif\0\0" 前缀（XMP 形态）：
			"XMP段": insertAPP1AfterSOI(base, append([]byte{0xFF, jpegMarkerAPP1, 0x00, 0x2A},
				[]byte("http://ns.adobe.com/xap/1.0/\x00<x/>")...)),
			// EXIF 段没有 Orientation tag（tag 换成 Make 0x010F ASCII）：
			"无OrientationTag": insertAPP1AfterSOI(base, buildExifAPP1(bo, 0x010F, 2, 5, 0)),
		}
		for name, data := range cases {
			if got := parseJPEGOrientation(bytes.NewReader(data)); got != orientationNormal {
				t.Errorf("%s 读到 %d，期望兜底 1", name, got)
			}
		}
	})

	t.Run("前导FF填充不挡解析", func(t *testing.T) {
		// 个别编码器在标记前连发 0xFF 填充：解析器必须跳过而不是误判段边界。
		padded := append([]byte(nil), base[:2]...)
		padded = append(padded, 0xFF, 0xFF) // 填充 FF
		padded = append(padded, withOrientation(t, base, binary.LittleEndian, orientationRot90CW)[2:]...)
		if got := parseJPEGOrientation(bytes.NewReader(padded)); got != orientationRot90CW {
			t.Errorf("FF 填充后读到 %d，期望 6", got)
		}
	})
}

// TestJPEGOrientationOfDiskPath 磁盘入口接线：文件路径读方向、缺失/不存在
// 文件一律回落 1（吞错语义，见 jpegOrientationOf 注释）。
func TestJPEGOrientationOfDiskPath(t *testing.T) {
	dir := t.TempDir()
	src := makeOrientedJPEG(t, dir, "disk", binary.LittleEndian, orientationRot90CW)
	if got := jpegOrientationOf(src); got != orientationRot90CW {
		t.Errorf("磁盘文件读到 %d，期望 6", got)
	}
	plain := filepath.Join(dir, "plain.jpg")
	if err := os.WriteFile(plain, encodeQuadJPEG(t), 0o644); err != nil {
		t.Fatalf("写入无 EXIF 文件: %v", err)
	}
	if got := jpegOrientationOf(plain); got != orientationNormal {
		t.Errorf("无 EXIF 文件读到 %d，期望 1", got)
	}
	if got := jpegOrientationOf(filepath.Join(dir, "no-such.jpg")); got != orientationNormal {
		t.Errorf("不存在文件读到 %d，期望 1", got)
	}
}

// TestScaleStillAppliesEXIFOrientationIntegration 端到端像素级验证：带 EXIF
// 的四象限 JPEG 经 scaleStill 后，输出尺寸换维正确、四象限颜色分布与 EXIF
// 规范推导的显示变换逐一吻合。这是映射表的最终裁判（任务口径：以夹具实测为准）。
// 覆盖边界：longSide 恰等源图长边 → scale 恒等（输出与输入同像素数），产物
// 差异只能来自方向滤镜；故象限色断言 = 纯方向语义验证，与缩放语义（既有
// TestScaleStillIntegration 锁定）互不掺和。
func TestScaleStillAppliesEXIFOrientationIntegration(t *testing.T) {
	requireFFmpeg(t)
	ctx := context.Background()
	dir := t.TempDir()
	gen := newTestGenerator(t)
	// 强制 jpeg 降级档：Go 标准库能直接解码产物逐像素断言（webp 无标准库
	// 解码器）；强制 stillFormat 的手法与既有 TestScaleStillJPEGFallback
	// Integration 同源，jpeg 档参数已由 TestStillFormatEncodeArgs 锁定。
	gen.stillFormat = StillFormatJPEG

	cases := []struct {
		name         string
		orientation  exifOrientation
		withExif     bool
		wantW, wantH int
	}{
		{"无EXIF不转", orientationNormal, false, quadW, quadH},
		{"1_正立", orientationNormal, true, quadW, quadH},
		{"2_水平镜像", orientationMirrorH, true, quadW, quadH},
		{"3_旋转180", orientationRot180, true, quadW, quadH},
		{"4_垂直镜像", orientationMirrorV, true, quadW, quadH},
		{"5_主对角线翻转", orientationTranspose, true, quadH, quadW},
		{"6_顺时针90_竖拍主档", orientationRot90CW, true, quadH, quadW},
		{"7_副对角线翻转", orientationTransverse, true, quadH, quadW},
		{"8_逆时针90", orientationRot90CCW, true, quadH, quadW},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var src string
			if tc.withExif {
				src = makeOrientedJPEG(t, dir, tc.name, binary.LittleEndian, tc.orientation)
			} else {
				src = filepath.Join(dir, tc.name+".jpg")
				if err := os.WriteFile(src, encodeQuadJPEG(t), 0o644); err != nil {
					t.Fatalf("写入无 EXIF 源: %v", err)
				}
			}
			dst := filepath.Join(dir, tc.name+".out.jpg")
			if err := gen.scaleStill(ctx, src, tc.orientation, quadW, dst); err != nil {
				t.Fatalf("scaleStill: %v", err)
			}
			img := decodeOutJPEG(t, dst)
			b := img.Bounds()
			if b.Dx() != tc.wantW || b.Dy() != tc.wantH {
				t.Fatalf("输出尺寸 = %dx%d，期望 %dx%d（方向换维错误）", b.Dx(), b.Dy(), tc.wantW, tc.wantH)
			}
			// 四象限中心采样：期望色 = 基准图在 EXIF 显示变换下的象限色。
			for _, s := range []struct {
				name   string
				sx, sy int
			}{
				{"左上", tc.wantW / 4, tc.wantH / 4},
				{"右上", tc.wantW * 3 / 4, tc.wantH / 4},
				{"左下", tc.wantW / 4, tc.wantH * 3 / 4},
				{"右下", tc.wantW * 3 / 4, tc.wantH * 3 / 4},
			} {
				r, c := displayedSampleStored(tc.orientation, s.sx, s.sy)
				assertQuadrant(t, img, s.sx, s.sy, s.name, quadColorAt(r, c))
			}
		})
	}
}

// decodeOutJPEG 解码 scaleStill 产物（jpeg 档，Go 标准库可解）。
func decodeOutJPEG(t *testing.T, path string) image.Image {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("打开 %s: %v", path, err)
	}
	defer f.Close()
	img, err := jpeg.Decode(f)
	if err != nil {
		t.Fatalf("解码 %s: %v", path, err)
	}
	return img
}

// assertQuadrant 断言采样点颜色与期望象限色逐通道差 ≤ quadTolerance。
func assertQuadrant(t *testing.T, img image.Image, x, y int, name string, want quadrant) {
	t.Helper()
	cr, cg, cb, _ := img.At(x, y).RGBA()
	got := quadrant{uint8(cr >> 8), uint8(cg >> 8), uint8(cb >> 8)}
	chanDiff := func(a, b uint8) int {
		d := int(a) - int(b)
		if d < 0 {
			return -d
		}
		return d
	}
	if chanDiff(got.r, want.r) > quadTolerance || chanDiff(got.g, want.g) > quadTolerance || chanDiff(got.b, want.b) > quadTolerance {
		t.Fatalf("%s (%d,%d) 像素 = %+v，期望 %+v（±%d）", name, x, y, got, want, quadTolerance)
	}
}
