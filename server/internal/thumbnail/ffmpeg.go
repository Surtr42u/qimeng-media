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
// （列表页密集展示时明显），再高对几十 KB 的缩略图收益递减。后续可提为配置项。
const webpQuality = 80

// stderrTailLen 是截进错误信息的 stderr 尾部长度上限（字节）。
const stderrTailLen = 512

// DefaultFFmpegBin / DefaultFFprobeBin 是未配置二进制路径时 exec 查找的裸命令名：
// os/exec 按当前进程 PATH 解析——路径配置化之前唯一可用的语义，缺省配置下
// 行为零变化。为什么需要可配置（M6 单机形态，ADR-0015）：服务端进手机后
// ffmpeg/ffprobe 打包在 App 的 nativeLibraryDir，进程 PATH 未必可达，
// 自动发现不可依赖，须由 config（thumbnail.ffmpeg_path/ffprobe_path）显式指定。
const (
	DefaultFFmpegBin  = "ffmpeg"
	DefaultFFprobeBin = "ffprobe"
)

// resolveBin 二进制路径解析（显式配置优先 / 缺省回退自动发现）：
// configured 非空原样采用，空串回退 fallback（裸命令名）。
// 两分支行为由 binpath_test.go 锁定（含"配置值确实进 exec"的实证）。
func resolveBin(configured, fallback string) string {
	if configured != "" {
		return configured
	}
	return fallback
}

// CheckBinaries 对 resolveBin 解析出的最终 ffmpeg/ffprobe 命令做启动期可用性
// 自检（exec.LookPath）：显式配置路径验存在且可执行，空配置走 PATH 自动发现
// （与 run() 实际解析语义同源，单点不漂移）。
// 为什么只告警不阻断：无 ffmpeg 是既定降级形态（缩略图 404 占位、探测元数据
// 留空、下次扫描自动重探），缺失原本要到首次调用才暴露（备忘录第五节-5），
// 启动自检把暴露点提前到部署当下，一条 Warn 引导投放即可，不构成致命错误。
// 返回解析后的最终命令与各自错误，由调用方决定日志形态（main 装配处消费）。
func CheckBinaries(ffmpegCfg, ffprobeCfg string) (ffmpeg, ffprobe string, ffmpegErr, ffprobeErr error) {
	ffmpeg = resolveBin(ffmpegCfg, DefaultFFmpegBin)
	ffprobe = resolveBin(ffprobeCfg, DefaultFFprobeBin)
	_, ffmpegErr = exec.LookPath(ffmpeg)
	_, ffprobeErr = exec.LookPath(ffprobe)
	return
}

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
// （ffmpeg 对已存在的输出文件默认拒绝覆盖）。
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
// 二进制路径用 Generator 构造时解析的 g.ffmpegBin（显式配置优先/缺省裸命令名）。
func (g *Generator) extractFrame(ctx context.Context, video string, at time.Duration, dst string) error {
	return writeAtomically(dst, func(tmp string) error {
		var out bytes.Buffer
		return run(ctx, g.ffmpegBin, &out,
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
func (g *Generator) firstFrame(ctx context.Context, src string, dst string) error {
	return writeAtomically(dst, func(tmp string) error {
		var out bytes.Buffer
		return run(ctx, g.ffmpegBin, &out,
			"-y",
			"-i", src,
			"-frames:v", "1",
			tmp,
		)
	})
}

// ExtractAttachedPic 提取视频容器内的内嵌封面流（cover art），输出 PNG 到 dst
// （原子落盘）。选流按原始流索引 streamIndex（ProbeVideo 探测出的 attached_pic
// 流索引）而非 -map 0:v:N：后者 N 是"视频类流序号"而非容器流索引——纯音频+
// 封面的容器里唯一视频流序号恒为 0，而 ffprobe 的 attached_pic 落在索引 1 上，
// 按类内序号选流必然错选；用原始索引才与探测侧同源。
func (g *Generator) extractAttachedPic(ctx context.Context, src string, streamIndex int, dst string) error {
	if streamIndex < 0 {
		return fmt.Errorf("内嵌封面流索引非法: %d", streamIndex)
	}
	return writeAtomically(dst, func(tmp string) error {
		var out bytes.Buffer
		return run(ctx, g.ffmpegBin, &out,
			"-y",
			"-i", src,
			"-map", "0:"+strconv.Itoa(streamIndex),
			"-frames:v", "1",
			tmp,
		)
	})
}

// scaleStill 把图片（或抽出的中转帧）等比缩放到最长边 longSide，按 Generator
// 探测出的静图格式编码输出（原子落盘；格式裁决见 stillformat.go）。
// orientation 是源图的 EXIF 方向（DOMAIN_RULES §11「静图方向」）：缩放前先按
// orientationFilterChain 显式转正；**输入侧必带 -noautorotate**——新版 ffmpeg
// 解码 JPEG 会隐式按 EXIF 转向（本仓 9.0.1 实证：Orientation=6 默认解出已转
// 正的竖帧）、旧版则不转，不关闭它显式滤镜就会在隐式之上再转一次（双重变换
// 抵消/180° 皆实测复现），关掉它显式滤镜才是唯一方向权威、行为跨版本恒定。
// -noautorotate 对无方向元数据的输入（视频抽出的中转帧 PNG/gif 首帧）是
// no-op，不影响视频抽帧侧的 display-matrix autorotate 语义。
// 为什么用 scale=W:W:force_original_aspect_ratio=decrease 而非字面 scale=w:-1：
// 实测 20x100 竖图在 scale=32:-1 下得到 32x160，最长边反而超出目标；
// decrease 的语义是"在 W×W 框内等比缩小"，横图竖图都以 longSide 为最长边，
// 与 config.Thumbnail.LongSide（最长边像素）的语义一致。方向滤镜在前、scale
// 在后（先转正再适配方框）；目标框是正方形，两滤镜先后对结果尺寸无影响，
// 顺序只为语义直白。
// 质量参数见 webpQuality / jpegQuality 常量注释；输出封装格式由临时文件
// 扩展名（= stillFormat.Ext()）推断，编码段参数由 stillFormat.encodeArgs() 给出。
func (g *Generator) scaleStill(ctx context.Context, src string, orientation exifOrientation, longSide int, dst string) error {
	if longSide <= 0 {
		return fmt.Errorf("longSide 必须为正数，得到 %d", longSide)
	}
	side := strconv.Itoa(longSide)
	vf := orientationFilterChain(orientation)
	if vf != "" {
		vf += "," // 空方向 = 无滤镜段，链退化为纯 scale（除 -noautorotate 外与旧行为同参）
	}
	vf += "scale=" + side + ":" + side + ":force_original_aspect_ratio=decrease"
	return writeAtomically(dst, func(tmp string) error {
		var out bytes.Buffer
		args := []string{
			"-y",
			// 输入侧选项（须在 -i 之前）：关闭解码期隐式 EXIF 转向，见函数注释。
			"-noautorotate",
			"-i", src,
			"-vf", vf,
			"-frames:v", "1",
		}
		args = append(args, g.stillFormat.encodeArgs()...)
		args = append(args, tmp)
		return run(ctx, g.ffmpegBin, &out, args...)
	})
}

// ffprobeJSON 对应 ffprobe -show_streams/-show_format 的 json 输出。
// 只声明用到的字段：json.Unmarshal 对多出的字段默认忽略，ffprobe 输出随版本
// 增删字段不会破坏解析。
// 注意 duration 是字符串（ffprobe 的 json writer 对浮点固定带引号输出，
// 实测形如 "2.000000"），不能声明为 float64，否则 Unmarshal 直接报错。
// disposition.attached_pic 标记内嵌封面流（cover art）：DOMAIN_RULES §11
// "内嵌封面优先"的探测基础（0=普通流，1=封面流）。
type ffprobeJSON struct {
	Streams []struct {
		CodecType   string `json:"codec_type"`
		CodecName   string `json:"codec_name"`
		Width       int    `json:"width"`
		Height      int    `json:"height"`
		Duration    string `json:"duration"`
		Disposition struct {
			AttachedPic int `json:"attached_pic"`
		} `json:"disposition"`
	} `json:"streams"`
	Format struct {
		Duration string `json:"duration"`
	} `json:"format"`
}

// parseDurationField 解析 ffprobe 的字符串时长字段；缺失或非法返回 0
// （由 ProbeVideo 的取值优先级兜底，不在这里报错）。
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
	// VideoCodec/AudioCodec 为 ffprobe codec_name（如 h264/hevc/av1/aac），
	// 空串 = 未探到（消费侧转 NULL）；AudioCodec 取第一个音频流，
	// 与视频流遍历无关（音频流可能排在视频流之后，须全流扫描）。
	VideoCodec string
	AudioCodec string
	// AttachedPic 表示容器内含带 attached_pic disposition 的视频流（内嵌
	// 封面/cover art）；AttachedPicStream 是该流的 0 基索引用途：PickFrameTime
	// 据此返回封面优先选点，ExtractAttachedPic 按同索引抽流。
	AttachedPic       bool
	AttachedPicStream int // AttachedPic=false 时为 -1
}

// secondsToDuration 把 ffprobe json 里的浮点秒转 Duration。
func secondsToDuration(sec float64) time.Duration {
	return time.Duration(sec * float64(time.Second))
}

// ProbeVideo 用 PATH 自动发现的 ffprobe（裸命令名，DefaultFFprobeBin）探测视频，
// 行为与路径配置化之前完全一致（缺省回退分支的语义化身）。
// 生产路径统一走 Generator.ProbeVideo（配置单点解析），本函数保留给
// scanner 的缺省探测兜底与集成测试。
func ProbeVideo(ctx context.Context, path string) (*ProbeResult, error) {
	return probeVideo(ctx, DefaultFFprobeBin, path)
}

// ProbeVideo 用本 Generator 生效的 ffprobe 路径探测视频元数据。
// 扫描入库（scanner 经 main 装配注入）与上传探测（httpapi 直调）都经此出口，
// 保证 ffprobe 路径只在 NewGenerator 一处解析（配置单一来源，不散落三方）。
// 签名与包级 ProbeVideo 一致，可直接作为 scanner.ProbeFunc 注入。
func (g *Generator) ProbeVideo(ctx context.Context, path string) (*ProbeResult, error) {
	return probeVideo(ctx, g.ffprobeBin, path)
}

// probeVideo 是探测核心：ffprobeBin 为调用方解析好的二进制路径
// （显式配置优先/缺省裸命令名，解析语义见 resolveBin）。
// 为什么封装在 thumbnail 包：scanner 入库也要时长/宽高（变更检测后重新探测），
// 共用一份 ffprobe 封装，避免两处各写一套解析渐行渐远（任务约定）。
// 时长优先取 format.duration：部分封装的 stream 级 duration 缺失，
// format 级是容器聚合值（实测 mp4 两者一致）。
// 同时探测内嵌封面（disposition.attached_pic=1）与编码名：时长/宽高取第一个
// 非封面视频流（封面流是自己的时长/尺寸，取值无意义——mkv 封面常是
// 第 0 号视频流但真实视频在第 1 号）；整个容器只有一个封面流的极端情形
// （纯音频+封面）时用封面流兜底返回。codec 提取需要全流扫描（音频流可能
// 排在视频流之后），因此先收集后构造，不再循环内提前 return。
func probeVideo(ctx context.Context, ffprobeBin, path string) (*ProbeResult, error) {
	var out bytes.Buffer
	if err := run(ctx, ffprobeBin, &out,
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
	attachedPic := false
	attachedIdx := -1
	videoIdx := -1
	var audioCodec string
	for i, s := range probe.Streams {
		switch s.CodecType {
		case "audio":
			if audioCodec == "" {
				audioCodec = s.CodecName
			}
		case "video":
			if s.Disposition.AttachedPic == 1 {
				attachedPic = true
				attachedIdx = i
				continue
			}
			if videoIdx < 0 {
				videoIdx = i // 第一个非封面视频流：真实视频的时长与尺寸
			}
		}
	}
	if videoIdx >= 0 {
		vs := probe.Streams[videoIdx]
		dur := parseDurationField(probe.Format.Duration)
		if dur == 0 {
			dur = parseDurationField(vs.Duration)
		}
		return &ProbeResult{
			Duration:          secondsToDuration(dur),
			Width:             vs.Width,
			Height:            vs.Height,
			VideoCodec:        vs.CodecName,
			AudioCodec:        audioCodec,
			AttachedPic:       attachedPic,
			AttachedPicStream: attachedIdx,
		}, nil
	}
	if attachedPic {
		// 容器只有封面流（纯音频+封面）没有真实视频流：封面信息仍有效，
		// 时长只能由 format 级兜底（通常为 0，选点侧按短路 0ms 处理）。
		return &ProbeResult{AttachedPic: true, AttachedPicStream: attachedIdx, AudioCodec: audioCodec}, nil
	}
	return nil, fmt.Errorf("ffprobe 未在 %s 中找到视频流", path)
}
