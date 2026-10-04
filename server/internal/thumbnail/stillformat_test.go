package thumbnail

import (
	"bytes"
	"context"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// TestParseEncodersHasLibwebp 锁定 `ffmpeg -encoders` 输出的解析判定：
// 内嵌形态（hzw1199 LGPL 成品无 libwebp）与桌面/NAS 全功能构建（有）都必须
// 判对——判反的后果分别是全量缩略图 404 或无谓降级 JPEG。
func TestParseEncodersHasLibwebp(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want bool
	}{
		{
			name: "含libwebp（桌面全功能构建）",
			in: "Encoders:\n" +
				" V.....D libwebp            WebP (lossy) [encoders: libwebp]\n" +
				" V....D libwebp_anim        WebP animation\n" +
				" -----\n",
			want: true,
		},
		{
			name: "只有libwebp_anim不算（独立编码器名必须精确匹配）",
			in: "Encoders:\n" +
				" V....D libwebp_anim        WebP animation\n",
			want: false,
		},
		{
			name: "无libwebp（hzw1199 内嵌成品形态）",
			in: "Encoders:\n" +
				" V....D mjpeg               MJPEG (Motion JPEG)\n" +
				" V....D png                 PNG (image)\n" +
				" -----\n",
			want: false,
		},
		{
			name: "描述文本提及libwebp不算（第2列才是编码器名）",
			in:   " V....D fakeenc             wrapper for libwebp stuff\n",
			want: false,
		},
		{
			name: "空输出",
			in:   "",
			want: false,
		},
	}
	for _, tc := range cases {
		if got := parseEncodersHasLibwebp(tc.in); got != tc.want {
			t.Errorf("%s: parseEncodersHasLibwebp = %v，期望 %v", tc.name, got, tc.want)
		}
	}
}

// TestStillFormatEncodeArgs 锁定两档编码参数——参数名不通用
// （libwebp 是 -quality 0-100、mjpeg 是 -q:v 2-31），拼错任一段缩略图管线
// 即全量失败，必须逐字锁定。
func TestStillFormatEncodeArgs(t *testing.T) {
	if got := StillFormatWebP.encodeArgs(); !reflect.DeepEqual(got, []string{"-c:v", "libwebp", "-quality", "90"}) {
		t.Errorf("webp 编码参数 = %v", got)
	}
	if got := StillFormatJPEG.encodeArgs(); !reflect.DeepEqual(got, []string{"-c:v", "mjpeg", "-q:v", "2"}) {
		t.Errorf("jpeg 编码参数 = %v", got)
	}
}

// TestStillFormatExt 锁定扩展名映射：磁盘扩展名 = ffmpeg 输出封装推断 =
// httpapi ServeContent 的 Content-Type 推断，三处同源靠它。
func TestStillFormatExt(t *testing.T) {
	if got := StillFormatWebP.Ext(); got != ".webp" {
		t.Errorf("webp 扩展名 = %q", got)
	}
	if got := StillFormatJPEG.Ext(); got != ".jpg" {
		t.Errorf("jpeg 扩展名 = %q", got)
	}
}

// TestScaleStillJPEGFallbackIntegration 内嵌形态降级路径的端到端验证：
// 强制 jpeg 档（模拟无 libwebp 的 hzw1199 成品）必须产出合法 JPEG 且保持
// "最长边"语义。这是 U11 批次D 在模拟器上无法复现 arm64 真机 ffmpeg 的
// 替代验证——降级路径在本测试里被真实执行过。
func TestScaleStillJPEGFallbackIntegration(t *testing.T) {
	requireFFmpeg(t)
	dir := t.TempDir()
	gen := newTestGenerator(t)
	gen.stillFormat = StillFormatJPEG
	src := makeTallPNG(t, dir)
	dst := filepath.Join(dir, "out.jpg")

	if err := gen.scaleStill(context.Background(), src, orientationNormal, 32, dst); err != nil {
		t.Fatalf("scaleStill(jpeg): %v", err)
	}
	w, h := ffprobeImageSize(t, dst)
	if w != 6 || h != 32 {
		t.Errorf("缩放结果 = %dx%d，期望 6x32（最长边 32、等比）", w, h)
	}
	var out bytes.Buffer
	if err := run(context.Background(), "ffprobe", &out,
		"-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", dst); err != nil {
		t.Fatalf("ffprobe 探测降级产物: %v", err)
	}
	if strings.TrimSpace(out.String()) != "mjpeg" {
		t.Errorf("降级产物编码 = %q，期望 mjpeg", out.String())
	}
}
