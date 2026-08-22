package thumbnail

import (
	"bytes"
	"context"
	"image"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// 本文件是真实 ffmpeg 集成测试：所有测试资源用 ffmpeg lavfi 合成源现场生成
//（几百字节的小文件），落在 t.TempDir 由测试框架自动回收，不留垃圾。

// requireFFmpeg 集成测试依赖真实 ffmpeg/ffprobe（部署镜像内置，ARCHITECTURE §3；
// 开发机实测 PATH 可用）。环境缺失时跳过而非失败：纯逻辑测试（黑帧判定/缓存键/
// 工作池）在任何环境都必须可跑。
func requireFFmpeg(t *testing.T) {
	t.Helper()
	for _, bin := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(bin); err != nil {
			t.Skipf("环境缺少 %s，跳过真实集成测试: %v", bin, err)
		}
	}
}

// makeSolidVideo 用 lavfi 纯色源合成测试视频（64x64@10fps）。
func makeSolidVideo(t *testing.T, dir, color string, durSeconds float64) string {
	t.Helper()
	out := filepath.Join(dir, color+".mp4")
	var buf bytes.Buffer
	if err := run(context.Background(), "ffmpeg", &buf,
		"-y",
		"-f", "lavfi",
		"-i", "color="+color+":size=64x64:rate=10:duration="+strconv.FormatFloat(durSeconds, 'f', -1, 64),
		"-pix_fmt", "yuv420p",
		out,
	); err != nil {
		t.Fatalf("合成 %s 测试视频失败: %v", color, err)
	}
	return out
}

// makeBlackRedVideo 合成"前 1.5s 纯黑 + 后 1.5s 纯红"视频：
// 验证选帧逻辑跳过黑帧、命中后面候选点的关键场景。
func makeBlackRedVideo(t *testing.T, dir string) string {
	t.Helper()
	black := makeSolidVideo(t, dir, "black", 1.5)
	red := makeSolidVideo(t, dir, "red", 1.5)
	out := filepath.Join(dir, "blackred.mp4")
	var buf bytes.Buffer
	if err := run(context.Background(), "ffmpeg", &buf,
		"-y",
		"-i", black,
		"-i", red,
		"-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
		"-map", "[v]",
		out,
	); err != nil {
		t.Fatalf("拼接黑红测试视频失败: %v", err)
	}
	return out
}

// makeTwoColorGif 合成"红 0.4s + 蓝 0.4s"的动图：验证首帧提取取的是第一帧
//（若取到末帧会得到蓝，测试当场失败）。
func makeTwoColorGif(t *testing.T, dir string) string {
	t.Helper()
	out := filepath.Join(dir, "redblue.gif")
	var buf bytes.Buffer
	if err := run(context.Background(), "ffmpeg", &buf,
		"-y",
		"-f", "lavfi", "-i", "color=red:size=32x32:rate=5:duration=0.4",
		"-f", "lavfi", "-i", "color=blue:size=32x32:rate=5:duration=0.4",
		"-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
		"-map", "[v]",
		out,
	); err != nil {
		t.Fatalf("合成红蓝 gif 失败: %v", err)
	}
	return out
}

// makeTallPNG 合成 20x100 竖图：验证缩放"最长边"语义（横图场景已由视频帧覆盖）。
func makeTallPNG(t *testing.T, dir string) string {
	t.Helper()
	out := filepath.Join(dir, "tall.png")
	var buf bytes.Buffer
	if err := run(context.Background(), "ffmpeg", &buf,
		"-y",
		"-f", "lavfi", "-i", "color=green:size=20x100:rate=1:duration=0.2",
		"-frames:v", "1",
		out,
	); err != nil {
		t.Fatalf("合成竖图失败: %v", err)
	}
	return out
}

// decodePNG 解码 PNG 并返回图像（内容验证用）。
func decodePNG(t *testing.T, path string) image.Image {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("打开 %s: %v", path, err)
	}
	defer f.Close()
	img, err := png.Decode(f)
	if err != nil {
		t.Fatalf("解码 PNG %s: %v", path, err)
	}
	return img
}

// assertRedPixel 断言图像中心像素是红色（yuv420p 的 color=red 转出 R≈254、G/B≈0）。
func assertRedPixel(t *testing.T, img image.Image) {
	t.Helper()
	bounds := img.Bounds()
	r, g, b, _ := img.At(bounds.Dx()/2, bounds.Dy()/2).RGBA()
	// RGBA() 返回 16bit，右移 8 位回 8bit 值再比较；留容差吸收色彩空间转换误差。
	if r>>8 < 200 || g>>8 > 60 || b>>8 > 60 {
		t.Fatalf("中心像素不是红色：R=%d G=%d B=%d", r>>8, g>>8, b>>8)
	}
}

// ffprobeImageSize 用 ffprobe 读图片/视频尺寸（Go 标准库不解 WebP，借 ffprobe 验证）。
func ffprobeImageSize(t *testing.T, path string) (int, int) {
	t.Helper()
	out, err := exec.Command("ffprobe",
		"-v", "error", "-select_streams", "v",
		"-show_entries", "stream=width,height", "-of", "csv=p=0",
		path,
	).Output()
	if err != nil {
		t.Fatalf("ffprobe 读取 %s 尺寸: %v", path, err)
	}
	parts := strings.Split(strings.TrimSpace(string(out)), ",")
	if len(parts) != 2 {
		t.Fatalf("ffprobe 输出不是 width,height 形态: %q", string(out))
	}
	w, err1 := strconv.Atoi(parts[0])
	h, err2 := strconv.Atoi(parts[1])
	if err1 != nil || err2 != nil {
		t.Fatalf("解析 ffprobe 尺寸输出 %q: %v %v", string(out), err1, err2)
	}
	return w, h
}

// TestProbeVideoIntegration 用真实 ffprobe 验证 json 解析：时长与宽高。
func TestProbeVideoIntegration(t *testing.T) {
	requireFFmpeg(t)
	dir := t.TempDir()
	video := makeSolidVideo(t, dir, "red", 2)

	got, err := ProbeVideo(context.Background(), video)
	if err != nil {
		t.Fatalf("ProbeVideo: %v", err)
	}
	// 容器时间戳允许微小抖动（±200ms），宽高必须精确。
	if diff := got.Duration - 2*time.Second; diff > 200*time.Millisecond || diff < -200*time.Millisecond {
		t.Errorf("时长 = %v，期望 2s±200ms", got.Duration)
	}
	if got.Width != 64 || got.Height != 64 {
		t.Errorf("宽高 = %dx%d，期望 64x64", got.Width, got.Height)
	}
}

// TestPickFrameTimeIntegration 用真实视频验证选帧逻辑的三个分支：
// 首点命中 / 跳过黑帧命中后点 / 纯黑兜底到最后有效点。
func TestPickFrameTimeIntegration(t *testing.T) {
	requireFFmpeg(t)
	dir := t.TempDir()

	t.Run("红视频首点命中", func(t *testing.T) {
		video := makeSolidVideo(t, dir, "red", 2)
		at, err := PickFrameTime(context.Background(), video)
		if err != nil {
			t.Fatalf("PickFrameTime: %v", err)
		}
		if at != 0 {
			t.Errorf("红视频 0s 即非黑，应返回 0，得到 %v", at)
		}
	})

	t.Run("黑红视频跳过黑帧", func(t *testing.T) {
		video := makeBlackRedVideo(t, dir) // 0~1.5s 黑，1.5~3s 红
		at, err := PickFrameTime(context.Background(), video)
		if err != nil {
			t.Fatalf("PickFrameTime: %v", err)
		}
		// 0s、1s 黑（1.0s < 1.5s 红段起点），2s 落入红段。
		if at != 2*time.Second {
			t.Errorf("黑红视频应选中 2s（首个非黑候选点），得到 %v", at)
		}
	})

	t.Run("纯黑视频兜底到最后有效点", func(t *testing.T) {
		video := makeSolidVideo(t, dir, "black", 2)
		at, err := PickFrameTime(context.Background(), video)
		if err != nil {
			t.Fatalf("PickFrameTime: %v", err)
		}
		// 实测 -ss 2 恰好等于时长时 ffmpeg 输出 0 字节（该点无帧被跳过），
		// 3s/5s 超界同样跳过；0s/1s 取到帧但全黑 → 兜底返回最后有效点 1s。
		if at != 1*time.Second {
			t.Errorf("纯黑 2s 视频应兜底到 1s（最后有效取帧点），得到 %v", at)
		}
	})
}

// TestExtractFrameIntegration 真实抽帧并验证像素内容是红（抽到了正确画面，
// 而不是生成了空文件）。
func TestExtractFrameIntegration(t *testing.T) {
	requireFFmpeg(t)
	dir := t.TempDir()
	video := makeSolidVideo(t, dir, "red", 2)
	dst := filepath.Join(dir, "frame.png")

	if err := ExtractFrame(context.Background(), video, 500*time.Millisecond, dst); err != nil {
		t.Fatalf("ExtractFrame: %v", err)
	}
	assertRedPixel(t, decodePNG(t, dst))
}

// TestScaleToWebPIntegration 竖图缩放验证"最长边"语义与 WebP 容器合法性：
// 20x100 在 longSide=32 下必须得到 6x32（等比缩小、长边恰为 32）。
func TestScaleToWebPIntegration(t *testing.T) {
	requireFFmpeg(t)
	dir := t.TempDir()
	src := makeTallPNG(t, dir)
	dst := filepath.Join(dir, "out.webp")

	if err := ScaleToWebP(context.Background(), src, 32, dst); err != nil {
		t.Fatalf("ScaleToWebP: %v", err)
	}
	w, h := ffprobeImageSize(t, dst)
	if w != 6 || h != 32 {
		t.Errorf("缩放结果 = %dx%d，期望 6x32（最长边 32、等比）", w, h)
	}
}

// TestFirstFrameIntegration 红蓝动图的首帧静帧必须是红帧。
func TestFirstFrameIntegration(t *testing.T) {
	requireFFmpeg(t)
	dir := t.TempDir()
	gif := makeTwoColorGif(t, dir)
	dst := filepath.Join(dir, "first.png")

	if err := FirstFrame(context.Background(), gif, dst); err != nil {
		t.Fatalf("FirstFrame: %v", err)
	}
	assertRedPixel(t, decodePNG(t, dst))
}

// TestGeneratorEnsureIntegration 全管线端到端：三类媒体经 Ensure 落盘到
// 约定目录布局，二次调用幂等跳过（文件未被重写）。
func TestGeneratorEnsureIntegration(t *testing.T) {
	requireFFmpeg(t)
	dir := t.TempDir()
	dataDir := filepath.Join(dir, "data")
	gen := NewGenerator(dataDir, nil)

	video := makeSolidVideo(t, dir, "red", 2)
	gif := makeTwoColorGif(t, dir)
	img := makeTallPNG(t, dir)

	cases := []struct {
		name string
		src  string
		kind Kind
	}{
		{"视频管线（黑帧检测+抽帧+缩放）", video, KindVideo},
		{"动图管线（首帧静帧+缩放）", gif, KindAnimatedImage},
		{"图片管线（直接缩放）", img, KindImage},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			assetID := "asset-" + tc.name
			if err := gen.Ensure(context.Background(), assetID, tc.src, tc.kind, []Size{SizeGrid}); err != nil {
				t.Fatalf("Ensure: %v", err)
			}
			path := ThumbPath(dataDir, CacheKey(assetID, SizeGrid))
			info, err := os.Stat(path)
			if err != nil {
				t.Fatalf("缩略图应存在于 %s: %v", path, err)
			}
			if info.Size() == 0 {
				t.Fatalf("缩略图 %s 是空文件", path)
			}

			// 幂等：第二次 Ensure 必须跳过（文件未被重写，ModTime 不变）。
			before := info.ModTime()
			if err := gen.Ensure(context.Background(), assetID, tc.src, tc.kind, []Size{SizeGrid}); err != nil {
				t.Fatalf("二次 Ensure: %v", err)
			}
			after, err := os.Stat(path)
			if err != nil {
				t.Fatalf("二次 Ensure 后 Stat: %v", err)
			}
			if !after.ModTime().Equal(before) {
				t.Errorf("二次 Ensure 重写了缓存文件（应命中缓存跳过）：%v -> %v", before, after.ModTime())
			}

			// 布局：路径必须形如 dataDir/thumbs/{key[:2]}/{key}.webp。
			key := CacheKey(assetID, SizeGrid)
			want := filepath.Join(dataDir, "thumbs", key[:2], key+".webp")
			if path != want {
				t.Errorf("目录布局 = %s，期望 %s", path, want)
			}
		})
	}
}
