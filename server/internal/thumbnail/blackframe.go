package thumbnail

import (
	"bytes"
	"context"
	"fmt"
	"math"
	"time"
)

// 黑白帧检测与代表帧选点【逐字遵守 docs/DOMAIN_RULES.md §11】：
//
//	采样 100 像素亮度全部 <15 判黑、全部 >240 判白；候选点按比例向两侧
//	扩散（35%→25%→45%→15%→55%→5%→65%→0ms）逐点尝试，取第一个黑、白
//	检测双合格的点；取不到时长时短路 0ms 帧；不重复取同一帧。
//	常量与序列禁止擅自改动（要改必须先让用户确认并同步测试——§11 逐字遵守）。
const (
	// blackLumaThreshold 判黑亮度阈值（8bit full-range 亮度值，DOMAIN_RULES §11：全部 <15）。
	blackLumaThreshold = 15
	// whiteLumaThreshold 判白亮度阈值（DOMAIN_RULES §11：全部 >240 判白）。
	// 来源与注释：黑白同带只通过阈值判定——恰好 240 不判白、恰好 15 不判黑。
	whiteLumaThreshold = 240
	// lumaSampleCount 采样像素数：10×10 均匀网格共 100 点（DOMAIN_RULES §11）。
	lumaSampleCount = 100
	// lumaSampleGrid 采样网格边长：scale=10:10 字面语义的常量由来（§11 采样 100 像素）。
	lumaSampleGrid = 10
)

// frameFractionOffsets 是候选时间点占视频时长的比例序列（DOMAIN_RULES §11 逐字遵守：
// 35%→25%→45%→15%→55%→5%→65%→0ms，向两侧交错扩散）。
// 为什么是这些点：35% 是旧版 ffprobe 实测构图上最有"封面感"的偏移（对齐
// Windows 资源管理器 Icaros 体验）；两侧交错让黑白被跳开的概率随尝试次数递减；
// 0ms 是永不缺帧的最后兜底点（时长未知或全部候选不合格时靠它收底）。
var frameFractionOffsets = []float64{0.35, 0.25, 0.45, 0.15, 0.55, 0.05, 0.65, 0.0}

// CandidateFrameFractions 返回候选比例序列的副本。
// 为什么返回副本而非导出切片：导出可变切片会被任何调用方修改，污染全局序列。
func CandidateFrameFractions() []float64 {
	out := make([]float64, len(frameFractionOffsets))
	copy(out, frameFractionOffsets)
	return out
}

// IsBlackFrame 纯函数判定：samples 是某帧的亮度采样（8bit full-range），
// 全部像素亮度都 <15 才判黑。与 ffmpeg 完全解耦——边界行为
// （全黑/全白/恰好 15/99 黑 1 亮）由单测锁定，选帧逻辑的变化不会牵连判定语义。
//
// 为什么是"全部 <15"而非"多数 <15"：99/100 黑说明画面已有可见内容
// （片头淡入的残影、角落台标），不该触发跳帧；只有整帧没有任何可疑亮点的纯黑
// 才值得换下一个候选点。
// 空样本返回 false（无证据不判黑）：探测失败应由调用方按错误路径处理。
func IsBlackFrame(samples []byte) bool {
	for _, luma := range samples {
		if luma >= blackLumaThreshold {
			return false
		}
	}
	return len(samples) > 0
}

// IsWhiteFrame 纯函数判定（DOMAIN_RULES §11：采样 100 像素全部 >240 判白）。
// 语义与 IsBlackFrame 对称：只是 5 秒黑场换 5 秒白场（画面过曝/纯白转场），
// 全部像素都超过阈值才判——恰好 240 不判白，99/100 白 1 个灰点也不判白。
func IsWhiteFrame(samples []byte) bool {
	for _, luma := range samples {
		if luma <= whiteLumaThreshold {
			return false
		}
	}
	return len(samples) > 0
}

// candidateTimes 把时长换算成候选时间点列表（毫秒精度、按出现顺序去重）。
//
// 为什么毫秒精度：lumaSamplesAt 的 -ss 输出只有毫秒（formatSeconds 取 3 位
// 小数），更细的差异在采样侧不可分辨。
// 去重语义（§11 "不重复取同一帧"）：时长极短时 35% 与 25% 等比例分片会
// 塌缩到同一毫秒——同一点只试一次，避免扩散序列与首点相同重复取帧。
// 时长取不到（<=0）时返回单元素 [0ms]：§11 "取不到时长时短路 0ms 帧"——
// 知道时长是按比例扩散的前提，未知时没有扩散可言，直接短路。
func candidateTimes(duration time.Duration) []time.Duration {
	if duration <= 0 {
		return []time.Duration{0}
	}
	seen := make(map[time.Duration]struct{}, len(frameFractionOffsets))
	out := make([]time.Duration, 0, len(frameFractionOffsets))
	for _, frac := range frameFractionOffsets {
		at := roundToMS(time.Duration(float64(duration) * frac))
		if _, dup := seen[at]; dup {
			continue
		}
		seen[at] = struct{}{}
		out = append(out, at)
	}
	return out
}

// roundToMS 舍入到毫秒：候选时间点的最低可分辨粒度（见 candidateTimes 注释）。
func roundToMS(d time.Duration) time.Duration {
	return time.Duration(math.Round(float64(d)/float64(time.Millisecond))) * time.Millisecond
}

// FramePick 描述视频封面抽帧的选点结果（DOMAIN_RULES §11 优先级：
// 内嵌封面优先 → 无则按时长 35% 偏移取代表帧）。
type FramePick struct {
	// At 是代表帧时间点（AttachedPic=false 时有效，经 ExtractFrame 抽取）。
	At time.Duration
	// AttachedPic 为 true 表示命中内嵌封面（cover art）流：封面是发行方/作者
	// 选定的画面，无需黑白纠偏，直接经 ExtractAttachedPic 按流索引抽取；
	// 此时 At 无意义。
	AttachedPic bool
	// AttachedPicStream 是内嵌封面流在容器内的 0 基索引（AttachedPic=false 时为 -1）。
	// 为什么携带索引而不是抽帧时固定取 0:v:0：带封面的容器（如带附件封面的
	// mkv）封面流不一定是第一个视频流，选流必须与 ffprobe 探测同源（见
	// ffmpeg.go ProbeVideo 的 disposition.attached_pic 判定）。
	AttachedPicStream int
}

// PickFrameTime 为视频挑选封面帧（DOMAIN_RULES §11 抽帧策略）：
//
//  1. ffprobe 探测：命中内嵌封面（attached_pic）→ 直接返回封面流
//     （优先级：内嵌封面 > 代表帧）；
//  2. 探测成功且有时长 → 按扩散序列逐点取灰度采样，首个黑、白双合格的
//     点即中选；全部不合格 → 返回最后一个成功取到帧的点（纯黑/纯白视频
//     也要出图——没有封面比黑封面更糟）；
//  3. 探测失败（无 ffprobe/文件不可探测）或时长未知 → 短路 0ms 帧：
//     0ms 是永不缺帧的位置（即使画面是黑/白，后续抽帧仍能出图）；0ms
//     采样也失败（文件损坏/无视频流）→ 报错。
//  4. 某个候选点超出有效范围（实测 ffmpeg 退出码 0 但输出 0 字节）→
//     跳过该点继续。
func PickFrameTime(ctx context.Context, videoPath string) (*FramePick, error) {
	probe, probeErr := ProbeVideo(ctx, videoPath)
	if probeErr == nil && probe.AttachedPic {
		return &FramePick{AttachedPic: true, AttachedPicStream: probe.AttachedPicStream}, nil
	}
	// 探测失败（错误被吞掉，见函数注释第 3 条：短路 0ms 由后续采样兜底）
	// 或时长未知：candidateTimes(duration<=0) = [0ms]。
	var duration time.Duration
	if probeErr == nil {
		duration = probe.Duration
	}

	var lastFrameAt time.Duration
	gotAnyFrame := false
	for _, at := range candidateTimes(duration) {
		samples, err := lumaSamplesAt(ctx, videoPath, at)
		if err != nil {
			return nil, fmt.Errorf("探测 %s 在 %s 处的灰度采样: %w", videoPath, at, err)
		}
		if len(samples) != lumaSampleCount {
			// 该点无帧（超出时长，ffmpeg 退出码 0 但 0 字节输出），见函数注释。
			continue
		}
		gotAnyFrame = true
		lastFrameAt = at
		if !IsBlackFrame(samples) && !IsWhiteFrame(samples) {
			return &FramePick{At: at}, nil
		}
	}
	if !gotAnyFrame {
		return nil, fmt.Errorf("所有候选点都取不到帧，疑似损坏文件: %s", videoPath)
	}
	return &FramePick{At: lastFrameAt}, nil
}

// lumaSamplesAt 用 ffmpeg 取视频在 at 时刻的 10×10 灰度采样（stdout
// lumaSampleCount 字节）。参数语义（ffmpeg 9 实测）：
//   - scale=10:10 把整帧拉伸到 10×10 网格，即"采样 100 像素"的字面实现
//     （宽高比被拉伸不影响黑/白帧判定）；
//   - out_range=full 显式声明 full-range 输出。实测当前 ffmpeg 的 gray rawvideo
//     输出已是 full range（纯黑=0x00、红=0x4c、白=0xff），显式参数防止未来版本
//     swscale 行为漂移（比如输出 limited range 的黑=16）导致纯黑视频漏判；
//   - ffmpeg 实测 black=0x00 恰在阈值 15 之下、white=0xff 恰在阈值 240 之上，
//     字节数据可直接与阈值比较。
func lumaSamplesAt(ctx context.Context, videoPath string, at time.Duration) ([]byte, error) {
	grid := fmt.Sprintf("%d:%d", lumaSampleGrid, lumaSampleGrid)
	var out bytes.Buffer
	if err := run(ctx, "ffmpeg", &out,
		"-ss", formatSeconds(at),
		"-i", videoPath,
		"-vf", "scale="+grid+":out_range=full,format=gray",
		"-frames:v", "1",
		"-f", "rawvideo",
		"-",
	); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
