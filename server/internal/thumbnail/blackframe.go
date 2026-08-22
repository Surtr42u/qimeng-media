package thumbnail

import (
	"bytes"
	"context"
	"fmt"
	"time"
)

// 黑帧检测【逐字遵守 docs/DOMAIN_RULES.md §11】：
//	采样 100 像素亮度 <15 判黑；候选时间点 0s→1s→2s→3s→5s 逐点尝试，
//	直到取到非黑帧。常量与候选序列禁止改动（要改必须先让用户确认并同步测试）。
const (
	// blackLumaThreshold 判黑亮度阈值（8bit full-range 亮度值）。
	blackLumaThreshold = 15
	// lumaSampleCount 采样像素数：10×10 均匀网格共 100 点。
	lumaSampleCount = 100
)

// candidateTimepoints 视频封面候选时间点。为什么是这些点：片头黑场集中在开头
// 两三秒，1s 步进已覆盖绝大多数；3s/5s 应对更长片头；全部落空说明大概率全片黑，
// 交给 PickFrameTime 的兜底逻辑。
var candidateTimepoints = []time.Duration{
	0,
	1 * time.Second,
	2 * time.Second,
	3 * time.Second,
	5 * time.Second,
}

// CandidateTimepoints 返回封面候选时间点序列的副本。
// 为什么返回副本而非导出切片：导出可变切片会被任何调用方修改，污染全局序列。
func CandidateTimepoints() []time.Duration {
	out := make([]time.Duration, len(candidateTimepoints))
	copy(out, candidateTimepoints)
	return out
}

// IsBlackFrame 纯函数判定：samples 是某帧的亮度采样（8bit full-range），
// 全部像素亮度都 <15 才判黑。与 ffmpeg 完全解耦——边界行为
// （全黑/全白/恰好 15/99 黑 1 亮）由单测锁定，选帧逻辑的变化不会牵连判定语义。
//
// 为什么是"全部 <15"而非"多数 <15"：99/100 黑说明画面已有可见内容
//（片头淡入的残影、角落台标），不该跳过；只有整帧没有任何可疑亮点的纯黑
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

// PickFrameTime 为视频挑选封面帧时间点：按候选序列逐点取灰度采样，
// 首个非黑点即中选。兜底策略：
//   - 某候选点超出时长（实测：ffmpeg 退出码 0 但输出 0 字节）→ 跳过该点继续；
//   - 所有成功取帧的候选点都是黑帧（纯黑视频）→ 返回最后一个成功取到帧的点：
//     保证后续抽帧命令一定能出图（哪怕画面是黑的），没有封面比黑封面更糟；
//   - 一个帧都取不到（文件损坏/无视频流）→ 报错。
func PickFrameTime(ctx context.Context, videoPath string) (time.Duration, error) {
	var lastFrameAt time.Duration
	gotAnyFrame := false
	for _, at := range candidateTimepoints {
		samples, err := lumaSamplesAt(ctx, videoPath, at)
		if err != nil {
			return 0, fmt.Errorf("探测 %s 在 %s 处的灰度采样: %w", videoPath, at, err)
		}
		if len(samples) != lumaSampleCount {
			// 该点无帧（超出时长，ffmpeg 退出码 0 但 0 字节输出），见函数注释。
			continue
		}
		gotAnyFrame = true
		lastFrameAt = at
		if !IsBlackFrame(samples) {
			return at, nil
		}
	}
	if !gotAnyFrame {
		return 0, fmt.Errorf("所有候选点都取不到帧，疑似损坏文件: %s", videoPath)
	}
	return lastFrameAt, nil
}

// lumaSamplesAt 用 ffmpeg 取视频在 at 时刻的 10×10 灰度采样（stdout 100 字节）。
// 参数语义（ffmpeg 9 实测）：
//   - scale=10:10 把整帧拉伸到 10×10 网格，即"采样 100 像素"的字面实现
//    （宽高比被拉伸不影响黑帧判定）；
//   - out_range=full 显式声明 full-range 输出。实测当前 ffmpeg 的 gray rawvideo
//     输出已是 full range（纯黑=0x00、红=0x4c、白=0xff），显式参数防止未来版本
//     swscale 行为漂移（比如输出 limited range 的黑=16）导致纯黑视频漏判；
//   - ffmpeg 实测 black=0x00 恰在阈值 15 之下，字节数据可直接与阈值比较。
func lumaSamplesAt(ctx context.Context, videoPath string, at time.Duration) ([]byte, error) {
	var out bytes.Buffer
	if err := run(ctx, "ffmpeg", &out,
		"-ss", formatSeconds(at),
		"-i", videoPath,
		"-vf", "scale=10:10:out_range=full,format=gray",
		"-frames:v", "1",
		"-f", "rawvideo",
		"-",
	); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
