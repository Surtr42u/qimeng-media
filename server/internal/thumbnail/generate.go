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

// frameTimeout 是单个尺寸生成全流程（视频 1 次 ffprobe 探测 + 最多 8 个
// 候选点灰度采样 + 抽帧 + 缩放）的兜底超时。为什么必须有：没有它，一个
// 卡死解码的 ffmpeg 进程会永久占住一个 worker，池吞吐被单个坏文件耗尽；
// 60s 对 NAS 上大多数片源的探测+抽帧足够宽裕。
const frameTimeout = 60 * time.Second

// Generator 缩略图编排器：把 ffmpeg 各能力组合成
// "保证某资产在某尺寸下的缩略图存在"的幂等操作，供工作池/懒生成端点调用。
type Generator struct {
	dataDir string
	logger  *slog.Logger
	// longSide 是网格默认档（md）的生成像素：来自 config.Thumbnail.LongSide，
	// <=0 时回落 SizeGrid（档位像素单一来源见 cachekey.go 的 Size 常量）。
	longSide int
	// pool 是本编排器自建的工作池（workers 来自 config.Thumbnail.Workers，
	// <=0 按 CPU 核数，见 NewWorkerPool）。懒生成/首屏预热经 Submit 提交；
	// 应用停机路径必须调用 Close 优雅收池。
	pool *WorkerPool
}

// Options 是 Generator 的构造配置。字段语义与 config.ThumbnailConfig 对应，
// 但以基础类型解耦：thumbnail 是业务模块，不反向依赖 config 包
// （依赖方向 httpapi→业务模块→store，ARCHITECTURE §5；config 由组装层读取后传入）。
type Options struct {
	// Workers 工作池大小；<=0 按 CPU 核数（NewWorkerPool 语义）。
	Workers int
	// LongSide 网格默认档（md）像素；<=0 回落 SizeGrid（512）。
	LongSide int
}

// NewGenerator 创建编排器。dataDir 是服务端数据根目录
// （缩略图落在 dataDir/thumbs 下，见 ThumbPath；绝不写媒体库目录）。
// opts.LongSide<=0 时回落 SizeGrid（默认 512，维持既有档位行为）；
// opts.Workers<=0 时按 CPU 核数建池。返回的 Generator 持有一个常驻工作池，
// 停机时调用方必须 Close 等任务排空。
func NewGenerator(dataDir string, logger *slog.Logger, opts Options) *Generator {
	if logger == nil {
		logger = slog.Default()
	}
	if opts.LongSide <= 0 {
		opts.LongSide = int(SizeGrid)
	}
	g := &Generator{dataDir: dataDir, logger: logger, longSide: opts.LongSide}
	g.pool = NewWorkerPool(context.Background(), opts.Workers, 0, g.handle, logger)
	return g
}

// handle 把池任务转成 Ensure 调用（WorkerPool 的 handle 签名）。
func (g *Generator) handle(ctx context.Context, t Task) error {
	return g.Ensure(ctx, t.AssetID, t.SourcePath, t.Kind, t.Sizes)
}

// Submit 非阻塞提交一个生成任务（ErrQueueFull/ErrPoolClosed 语义见 WorkerPool）。
// 懒生成端点与扫描入库的预热接线经此入口；任务幂等（Ensure 命中缓存即跳过）。
func (g *Generator) Submit(t Task) error { return g.pool.Submit(t) }

// Close 优雅关闭内置工作池（等已入队任务执行完；重复调用安全）。
func (g *Generator) Close() { g.pool.Close() }

// GridLongSide 返回网格默认档（md）当前生效的像素：LongSide 配置的接线出口，
// HTTP 侧把 openapi size=md 换算成它，保证请求缓存键与生成尺寸一致。
func (g *Generator) GridLongSide() int { return g.longSide }

// Ensure 幂等保证资产在 sizes 每个尺寸下的缩略图存在（已存在直接跳过——
// 键即内容身份（含策略版本段，见 cachekey.go），存在即有效）。
// 按媒体类型分派：图片→等比缩放；动图→首帧静帧再缩放；视频→DOMAIN_RULES §11
// 抽帧策略（内嵌封面优先 → 无则 35% 代表帧 → 黑/白扩散序列纠偏）选点抽帧再缩放。
// 性能分工（§11："列表/详情实时显示用首帧、代表帧只用于预生成缓存"）：
// M3 预留——本批保持懒生成统一管线（首请求同步生成），届时首帧走快速档、
// 代表帧走工作池预热，本函数的分派点不变。
func (g *Generator) Ensure(ctx context.Context, assetID, srcPath string, kind Kind, sizes []Size) error {
	for _, size := range sizes {
		if err := g.ensureOne(ctx, assetID, srcPath, kind, size); err != nil {
			return fmt.Errorf("生成 %s 的 %d 缩略图: %w", assetID, int(size), err)
		}
	}
	return nil
}

func (g *Generator) ensureOne(ctx context.Context, assetID, srcPath string, kind Kind, size Size) error {
	// 网格默认档（md）的像素由 LongSide 配置决定（<=0 已在构造时回落
	// SizeGrid）：size 常量只是档位占位，生成与缓存键都用换算后的像素，
	// 保证"调整配置像素 = 新键 = 新文件"的缓存语义成立（cachekey.go 注释）。
	if size == SizeGrid {
		size = Size(g.longSide)
	}
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
			pick, err := PickFrameTime(ctx, srcPath)
			if err != nil {
				return err
			}
			if pick.AttachedPic {
				// 内嵌封面优先（DOMAIN_RULES §11）：封面是发行方/作者选定的
				// 画面，无需黑白纠偏，直接按探测出的流索引抽取。
				if err := ExtractAttachedPic(ctx, srcPath, pick.AttachedPicStream, frame); err != nil {
					return err
				}
			} else if err := ExtractFrame(ctx, srcPath, pick.At, frame); err != nil {
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
