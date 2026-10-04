package thumbnail

import (
	"bytes"
	"context"
	"log/slog"
	"strconv"
	"strings"
	"time"
)

// StillFormat 静图缩略图的输出编码格式（所有媒体类型的缩略图最终落盘阶段，
// 见 Generator.ensureOne 的 scaleStill）。为什么需要双格式（U11 批次D 前置件，
// ADR-0015 内嵌形态）：App 内嵌服务端投放的 ffmpeg 成品（hzw1199 LGPL-2.1，
// 锁 commit hash）不含 libwebp 编码器——FFmpeg 没有原生 WebP 编码器，唯一
// 来源是外部库 libwebp（U10 卷 §9 真机实证 + 二进制配置串复核双源坐实）；
// 而含 libwebp 的 Termux pkg ffmpeg 是 GPL-3.0，内嵌分发不合规。服务端按
// 启动期探测结果自适应：libwebp 可用走 webp（NAS/桌面部署行为零变化），
// 不可用降级 mjpeg（内嵌形态正常档，非妥协 Hack——mjpeg 是 FFmpeg 内建
// 编码器，任何合规构建都携带）。
type StillFormat string

const (
	StillFormatWebP StillFormat = "webp"
	StillFormatJPEG StillFormat = "jpeg"
)

// Ext 缩略图缓存文件的扩展名（含点）。ThumbPath 与 httpapi 的 ServeContent
// 文件名共用，保证磁盘扩展名 = 推断的 Content-Type = 实际字节格式三者一致。
func (f StillFormat) Ext() string {
	switch f {
	case StillFormatJPEG:
		return ".jpg"
	default:
		return ".webp"
	}
}

// jpegQuality 是 mjpeg 降级档的 -q:v 值（2=最好 31=最差）。为什么定 2：
// 2026-10-04 用户拍板从 4 提到 2（mjpeg 刻度的最好档）——真机实测 q4 在
// 手机高分屏网格/详情上有可感知的压缩肉感，q2 体积增幅约两~六成（几十 KB
// 级）仍在缩略图预算内；旧口径「与 webpQuality=80 肉眼相当」随之作废。
// 产物内容已变，缓存键版本段随本次改动升 v4（见 cachekey.go 版本沿革）。
// 已知取舍：JPEG 无透明通道，带 alpha 的 PNG 源缩略图透明度被丢弃
// （swscale 直接弃通道，不合成底色）；用户真实库以 jpg 照片+视频为主，
// 该形态占比极小，不为此引入 overlay 滤镜复杂度。
const jpegQuality = 2

// encodeArgs 缩放命令的编码段参数。两档的参数名不通用
// （libwebp 用 -quality 0-100，mjpeg 用 -q:v 2-31），因此整体分支而非拼常量。
func (f StillFormat) encodeArgs() []string {
	switch f {
	case StillFormatJPEG:
		return []string{"-c:v", "mjpeg", "-q:v", strconv.Itoa(jpegQuality)}
	default:
		return []string{"-c:v", "libwebp", "-quality", strconv.Itoa(webpQuality)}
	}
}

// probeTimeout 是启动期编码器探测的单次兜底超时。探测跑一次真实 ffmpeg
// 进程（几十毫秒级），卡死的二进制不该拖垮启动路径。
const probeTimeout = 5 * time.Second

// probeStillFormat 探测 ffmpeg 是否携带 libwebp 编码器，决定静图缩略图格式。
// 探测失败（二进制缺失/超时/输出不可解析）一律回落 webp：与"无 ffmpeg 是
// 既定降级形态"的既有语义同向——错误留到首次缩略图生成时按原路径暴露，
// 启动期只记一条 Debug（CheckBinaries 已对二进制缺失给了面向部署的 Warn）。
// 格式在进程生命周期内固定：运行中替换 ffmpeg 二进制不重估，重启生效
// （换格式后旧格式缓存文件自然变孤儿，交给对账清理；客户端侧同键旧图
// 继续命中本地缓存直至逐出，内容等价仅编码不同，无害）。
func probeStillFormat(ffmpegBin string, logger *slog.Logger) StillFormat {
	ctx, cancel := context.WithTimeout(context.Background(), probeTimeout)
	defer cancel()
	var out bytes.Buffer
	// 探测复用 run() 底座：stderr 截尾、超时杀进程的语义与生产执行一致。
	if err := run(ctx, ffmpegBin, &out, "-hide_banner", "-encoders"); err != nil {
		logger.Debug("编码器探测失败，静图缩略图按 webp 处理", "err", err)
		return StillFormatWebP
	}
	if parseEncodersHasLibwebp(out.String()) {
		return StillFormatWebP
	}
	logger.Info("ffmpeg 不含 libwebp 编码器，静图缩略图降级 JPEG 输出（内嵌形态正常档）")
	return StillFormatJPEG
}

// parseEncodersHasLibwebp 解析 `ffmpeg -hide_banner -encoders` 的输出，
// 判断编码器清单里是否有 libwebp。输出形如：
//
//	Encoders:
//	 V.....D libwebp            WebP (lossy) ...
//	 V....D libwebp_anim        WebP animation
//
// 判定规则：按空白切字段后比对第 2 列（特性标志位之后的第一列即编码器名），
// 避免 "libwebp_anim" 或描述文本里的 "libwebp" 造成误匹配。
// 纯函数，单测锁定（含 libwebp_anim 不算、标志位列漂移的容错）。
func parseEncodersHasLibwebp(encodersOut string) bool {
	for _, line := range strings.Split(encodersOut, "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[1] == "libwebp" {
			return true
		}
	}
	return false
}
