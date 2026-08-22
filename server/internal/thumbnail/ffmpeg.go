package thumbnail

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"time"
)

// webpQuality 是 libwebp 有损质量参数（0-100，ffmpeg 默认 75）。
// 为什么定 80：缩略图体积与肉眼质量的常用折中——再低会出现块状伪影
//（列表页密集展示时明显），再高对几十 KB 的缩略图收益递减。后续可提为配置项。
const webpQuality = 80

// stderrTailLen 是截进错误信息的 stderr 尾部长度上限（字节）。
const stderrTailLen = 512

// run 执行外部命令（ffmpeg/ffprobe 共用底座）：stdout 交还调用方、stderr 截进错误。
// 为什么截 stderr 尾部而非全文：ffmpeg 对损坏文件可能输出几 MB 日志，
// 尾部才是含结论的行（"Error opening ..." 等），既可排障又不撑爆错误信息。
// 超时控制由调用方通过 ctx 注入（exec.CommandContext 负责到期杀进程）。
func run(ctx context.Context, bin string, stdout *bytes.Buffer, args ...string) error {
	cmd := exec.CommandContext(ctx, bin, args...)
	var stderr bytes.Buffer
	cmd.Stdout = stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	if ctx.Err() != nil {
		return fmt.Errorf("%s 被取消/超时: %w; stderr 尾部: %q", bin, ctx.Err(), stderrTail(stderr.String()))
	}
	if err != nil {
		return fmt.Errorf("%s 退出异常: %w; stderr 尾部: %q", bin, err, stderrTail(stderr.String()))
	}
	return nil
}

func stderrTail(s string) string {
	if len(s) <= stderrTailLen {
		return s
	}
	return s[len(s)-stderrTailLen:]
}

// writeAtomically 把"生成 dst 文件"的动作原子化：先写同目录临时文件，成功后 rename。
// 为什么必须原子：缩略图是 HTTP 直读的共享文件，直接写目标路径会让并发读的客户端
// 拿到半张图；同目录保证 rename 不跨文件系统（跨盘 rename 退化为拷贝，失去原子性）。
// 临时文件名保留目标扩展名（ffmpeg 靠扩展名推断输出封装格式）；
// 正因临时文件已被 CreateTemp 创建，各 ffmpeg 命令的 "-y" 必不可少
//（ffmpeg 对已存在的输出文件默认拒绝覆盖）。
func writeAtomically(dst string, build func(tmp string) error) (retErr error) {
	dir := filepath.Dir(dst)
	ext := filepath.Ext(dst)
	f, err := os.CreateTemp(dir, ".qimeng-*"+ext)
	if err != nil {
		return fmt.Errorf("在 %s 创建临时文件: %w", dir, err)
	}
	tmp := f.Name()
	// CreateTemp 打开的句柄必须先关：ffmpeg 要独占写入该文件。
	if err := f.Close(); err != nil {
		return fmt.Errorf("关闭临时文件 %s: %w", tmp, err)
	}
	defer func() {
		// 失败时清理临时文件；清理失败并入返回错误但不掩盖原始错误。
		if retErr != nil {
			if rmErr := os.Remove(tmp); rmErr != nil && !errors.Is(rmErr, fs.ErrNotExist) {
				retErr = fmt.Errorf("%w;（清理临时文件 %s 也失败: %v）", retErr, tmp, rmErr)
			}
		}
	}()
	if err := build(tmp); err != nil {
		return err
	}
	if err := os.Rename(tmp, dst); err != nil {
		return fmt.Errorf("原子落盘 %s: %w", dst, err)
	}
	return nil
}

// formatSeconds 把时长格式化为 ffmpeg -ss 接受的秒字符串。
// 为什么不用 Duration.String()：ffmpeg 不认识 "1m30s" 这种格式；
// 毫秒精度对抽帧足够。
func formatSeconds(d time.Duration) string {
	return strconv.FormatFloat(d.Seconds(), 'f', 3, 64)
}

// ExtractFrame 抽取视频在 at 时刻的单帧，输出 PNG 到 dst（原子落盘）。
// 参数组合语义（ffmpeg 9 实测确认）：-ss 前置于 -i 是快速 input seeking，
// ffmpeg 定位到目标点前关键帧再解码丢弃至精确目标；-frames:v 1 只编码一帧。
func ExtractFrame(ctx context.Context, video string, at time.Duration, dst string) error {
	return writeAtomically(dst, func(tmp string) error {
		var out bytes.Buffer
		return run(ctx, "ffmpeg", &out,
			"-y",
			"-ss", formatSeconds(at),
			"-i", video,
			"-frames:v", "1",
			tmp,
		)
	})
}

// FirstFrame 取动图第一帧静帧，输出 PNG 到 dst（原子落盘）。
// 为什么动图封面必须是首帧静帧（DOMAIN_RULES §11）：列表页若直接引用 gif，
// 几百个动图同时播动既是流量事故也是渲染灾难。
func FirstFrame(ctx context.Context, src string, dst string) error {
	return writeAtomically(dst, func(tmp string) error {
		var out bytes.Buffer
		return run(ctx, "ffmpeg", &out,
			"-y",
			"-i", src,
			"-frames:v", "1",
			tmp,
		)
	})
}

// ScaleToWebP 把图片等比缩放到最长边 longSide，输出 WebP 到 dst（原子落盘）。
// 为什么用 scale=W:W:force_original_aspect_ratio=decrease 而非字面 scale=w:-1：
// 实测 20x100 竖图在 scale=32:-1 下得到 32x160，最长边反而超出目标；
// decrease 的语义是"在 W×W 框内等比缩小"，横图竖图都以 longSide 为最长边，
// 与 config.Thumbnail.LongSide（最长边像素）的语义一致。
// 质量参数见 webpQuality 常量注释。
func ScaleToWebP(ctx context.Context, src string, longSide int, dst string) error {
	if longSide <= 0 {
		return fmt.Errorf("longSide 必须为正数，得到 %d", longSide)
	}
	side := strconv.Itoa(longSide)
	return writeAtomically(dst, func(tmp string) error {
		var out bytes.Buffer
		return run(ctx, "ffmpeg", &out,
			"-y",
			"-i", src,
			"-vf", "scale="+side+":"+side+":force_original_aspect_ratio=decrease",
			"-frames:v", "1",
			"-c:v", "libwebp",
			"-quality", strconv.Itoa(webpQuality),
			tmp,
		)
	})
}

// ffprobeJSON 对应 ffprobe -show_streams/-show_format 的 json 输出。
// 只声明用到的字段：json.Unmarshal 对多出的字段默认忽略，ffprobe 输出随版本
// 增删字段不会破坏解析。
// 注意 duration 是字符串（ffprobe 的 json writer 对浮点固定带引号输出，
// 实测形如 "2.000000"），不能声明为 float64，否则 Unmarshal 直接报错。
type ffprobeJSON struct {
	Streams []struct {
		CodecType string `json:"codec_type"`
		Width     int    `json:"width"`
		Height    int    `json:"height"`
		Duration  string `json:"duration"`
	} `json:"streams"`
	Format struct {
		Duration string `json:"duration"`
	} `json:"format"`
}

// parseDurationField 解析 ffprobe 的字符串时长字段；缺失或非法返回 0
//（由 ProbeVideo 的取值优先级兜底，不在这里报错）。
func parseDurationField(s string) float64 {
	if s == "" {
		return 0
	}
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0
	}
	return v
}

// ProbeResult 视频元数据探测结果。
type ProbeResult struct {
	Duration time.Duration
	Width    int
	Height   int
}

// secondsToDuration 把 ffprobe json 里的浮点秒转 Duration。
func secondsToDuration(sec float64) time.Duration {
	return time.Duration(sec * float64(time.Second))
}

// ProbeVideo 用 ffprobe 探测视频时长与宽高（json 输出解析）。
// 为什么导出在 thumbnail 包：scanner 入库也要时长/宽高（变更检测后重新探测），
// 共用一份 ffprobe 封装，避免两处各写一套解析渐行渐远（任务约定）。
// 时长优先取 format.duration：部分封装的 stream 级 duration 缺失，
// format 级是容器聚合值（实测 mp4 两者一致）。
func ProbeVideo(ctx context.Context, path string) (*ProbeResult, error) {
	var out bytes.Buffer
	if err := run(ctx, "ffprobe", &out,
		"-v", "error",
		"-print_format", "json",
		"-show_format",
		"-show_streams",
		path,
	); err != nil {
		return nil, err
	}
	var probe ffprobeJSON
	if err := json.Unmarshal(out.Bytes(), &probe); err != nil {
		return nil, fmt.Errorf("解析 %s 的 ffprobe 输出: %w", path, err)
	}
	for _, s := range probe.Streams {
		if s.CodecType != "video" {
			continue
		}
		dur := parseDurationField(probe.Format.Duration)
		if dur == 0 {
			dur = parseDurationField(s.Duration)
		}
		return &ProbeResult{
			Duration: secondsToDuration(dur),
			Width:    s.Width,
			Height:   s.Height,
		}, nil
	}
	return nil, fmt.Errorf("ffprobe 未在 %s 中找到视频流", path)
}
