package thumbnail

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"time"
)

// Kind 媒体类型（DOMAIN_RULES §11：IMAGE / ANIMATED_IMAGE(gif) / VIDEO）。
// 为什么不直接用 httpapi 生成的枚举：依赖方向是 httpapi→业务模块（ARCHITECTURE
// §5），管线反向引用协议层会把 openapi 泄漏进离线管线；接线时由上层做枚举映射。
type Kind string

const (
	KindImage         Kind = "image"
	KindAnimatedImage Kind = "animated_image"
	KindVideo         Kind = "video"
)

// frameTimeout 是单个尺寸生成全流程（视频最多 5 次黑帧探针 + 抽帧 + 缩放）的
// 兜底超时。为什么必须有：没有它，一个卡死解码的 ffmpeg 进程会永久占住一个
// worker，池吞吐被单个坏文件耗尽；60s 对 NAS 上大多数片源的黑帧探测足够宽裕。
const frameTimeout = 60 * time.Second

// Generator 缩略图编排器：把 ffmpeg 各能力组合成
// "保证某资产在某尺寸下的缩略图存在"的幂等操作，供工作池/懒生成端点调用。
type Generator struct {
	dataDir string
	logger  *slog.Logger
}

// NewGenerator 创建编排器。dataDir 是服务端数据根目录
//（缩略图落在 dataDir/thumbs 下，见 ThumbPath；绝不写媒体库目录）。
func NewGenerator(dataDir string, logger *slog.Logger) *Generator {
	if logger == nil {
		logger = slog.Default()
	}
	return &Generator{dataDir: dataDir, logger: logger}
}

// Ensure 幂等保证资产在 sizes 每个尺寸下的缩略图存在（已存在直接跳过——
// 键即内容身份，存在即有效，本期无失效逻辑）。按媒体类型分派：
// 图片→等比缩放；动图→首帧静帧再缩放；视频→黑帧检测选点抽帧再缩放。
// DOMAIN_RULES §11 的"视频内嵌封面（cover art）优先"需要入库元数据配合，
// 属于接线任务的职责；本期管线件只负责"给定源文件产出缩略图"。
func (g *Generator) Ensure(ctx context.Context, assetID, srcPath string, kind Kind, sizes []Size) error {
	for _, size := range sizes {
		if err := g.ensureOne(ctx, assetID, srcPath, kind, size); err != nil {
			return fmt.Errorf("生成 %s 的 %d 缩略图: %w", assetID, int(size), err)
		}
	}
	return nil
}

func (g *Generator) ensureOne(ctx context.Context, assetID, srcPath string, kind Kind, size Size) error {
	dst := ThumbPath(g.dataDir, CacheKey(assetID, size))
	if _, err := os.Stat(dst); err == nil {
		return nil // 缓存命中
	} else if !errors.Is(err, fs.ErrNotExist) {
		// 权限等真实错误必须暴露，静默跳过会让"以为生成了其实没有"难以排查。
		return fmt.Errorf("检查缓存文件 %s: %w", dst, err)
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return fmt.Errorf("创建缓存目录 %s: %w", filepath.Dir(dst), err)
	}

	ctx, cancel := context.WithTimeout(ctx, frameTimeout)
	defer cancel()

	switch kind {
	case KindImage:
		// 原图永不转码：缩放输出是独立副本，源文件只读。
		return ScaleToWebP(ctx, srcPath, int(size), dst)
	case KindAnimatedImage, KindVideo:
		// 动图取首帧静帧；视频经黑帧检测选点抽帧。中转帧放系统临时目录：
		// 它只是 ffmpeg 的中间输入，不进缓存目录，也不污染数据目录布局。
		frame, err := tempFramePath()
		if err != nil {
			return err
		}
		defer func() {
			if rmErr := os.Remove(frame); rmErr != nil && !errors.Is(rmErr, fs.ErrNotExist) {
				// 清理失败不影响生成结果，但必须留痕（系统临时目录堆积也是问题）。
				g.logger.Warn("清理抽帧中转文件失败", "path", frame, "err", rmErr)
			}
		}()
		if kind == KindAnimatedImage {
			if err := FirstFrame(ctx, srcPath, frame); err != nil {
				return err
			}
		} else {
			at, err := PickFrameTime(ctx, srcPath)
			if err != nil {
				return err
			}
			if err := ExtractFrame(ctx, srcPath, at, frame); err != nil {
				return err
			}
		}
		return ScaleToWebP(ctx, frame, int(size), dst)
	default:
		return fmt.Errorf("未知媒体类型 %q", kind)
	}
}

// tempFramePath 在系统临时目录创建带 .png 扩展名的中转文件并返回路径。
// 为什么保留扩展名：ffmpeg 靠扩展名推断输出封装格式。
func tempFramePath() (string, error) {
	f, err := os.CreateTemp("", "qimeng-frame-*.png")
	if err != nil {
		return "", fmt.Errorf("创建抽帧中转文件: %w", err)
	}
	name := f.Name()
	if err := f.Close(); err != nil {
		return "", fmt.Errorf("关闭抽帧中转文件 %s: %w", name, err)
	}
	return name, nil
}
