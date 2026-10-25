package thumbnail

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/sysmon"
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
	// ffmpegBin/ffprobeBin 是实际执行的外部二进制路径：构造时经 resolveBin
	// 从 Options.FFmpegPath/FFprobePath 解析（显式配置优先，空回退裸命令名
	// DefaultFFmpegBin/DefaultFFprobeBin 走 PATH 自动发现）。解析只发生在这
	// 一处，管线内所有 exec 与对外探测出口（Generator.ProbeVideo）统一取用。
	ffmpegBin  string
	ffprobeBin string
	// stillFormat 是静图缩略图的输出编码格式（webp/mjpeg）：构造时对生效的
	// ffmpeg 做一次编码器探测决定（见 stillformat.go；libwebp 缺失降级 jpeg
	// 是内嵌形态的正常档）。进程生命周期内固定，缓存扩展名与 HTTP 侧同源。
	stillFormat StillFormat
	// pool 是本编排器自建的工作池（workers 来自 config.Thumbnail.Workers，
	// <=0 按 CPU 核数，见 NewWorkerPool）。后台回填经 Submit 提交，前台走 EnsureDetached；
	// 应用停机路径必须调用 Close 优雅收池。
	pool *WorkerPool
	// detached 是懒生成端点的「单飞+后台续生」组（detached.go，2026-09-15 批）：
	// 同键请求合并为一次生成，且客户端取消只结束等待不杀生成。
	detached *detachedGroup
	// generating 按最终缓存路径合并前台、后台及重叠尺寸集合，等待取消不杀生成。
	generating *detachedGroup
	// 生产构造统一注入进程预算；生成全过程使用同一个实例，隔离测试可用独立预算。
	slots     *semaphore
	lifecycle sync.Once
	life      context.Context
	cancel    context.CancelFunc
	budget    *representationBudget
	closing   sync.Once
	closed    chan struct{}
}

// Options 是 Generator 的构造配置。字段语义与 config.ThumbnailConfig 对应，
// 但以基础类型解耦：thumbnail 是业务模块，不反向依赖 config 包
// （依赖方向 httpapi→业务模块→store，ARCHITECTURE §5；config 由组装层读取后传入）。
type Options struct {
	// Workers 工作池大小；<=0 按 CPU 核数（NewWorkerPool 语义）。
	Workers int
	// LongSide 网格默认档（md）像素；<=0 回落 SizeGrid（512）。
	LongSide int
	// FFmpegPath/FFprobePath 是外部二进制路径（来自 config.Thumbnail 同名
	// 配置）；空 = 回退裸命令名走 PATH 自动发现（缺省行为零变化，M6 单机
	// 形态靠显式路径指向 App 打包的二进制，见 DefaultFFmpegBin 注释）。
	FFmpegPath  string
	FFprobePath string
}

// NewGenerator 创建编排器。dataDir 是服务端数据根目录
// （缩略图落在 dataDir/thumbs 下，见 ThumbPath；绝不写媒体库目录）。
// opts.LongSide<=0 时回落 SizeGrid（默认 512，维持既有档位行为）；
// opts.FFmpegPath/FFprobePath 空时回退裸命令名走 PATH 自动发现（维持既有
// 行为），显式配置则原样进 exec（单点解析，见 Generator.ffmpegBin 注释）；
// opts.Workers<=0 时按 CPU 核数建池。返回的 Generator 持有一个常驻工作池，
// 停机时调用方必须 Close 等任务排空。
func NewGenerator(dataDir string, logger *slog.Logger, opts Options) *Generator {
	if logger == nil {
		logger = slog.Default()
	}
	if opts.LongSide <= 0 {
		opts.LongSide = int(SizeGrid)
	}
	g := &Generator{
		dataDir:    dataDir,
		logger:     logger,
		longSide:   opts.LongSide,
		ffmpegBin:  resolveBin(opts.FFmpegPath, DefaultFFmpegBin),
		ffprobeBin: resolveBin(opts.FFprobePath, DefaultFFprobeBin),
		detached:   newDetachedGroup(),
		generating: newDetachedGroup(),
		slots:      genSlots,
	}
	g.stillFormat = probeStillFormat(g.ffmpegBin, logger)
	g.initLifecycle()
	g.pool = NewWorkerPool(g.life, opts.Workers, 0, g.handle, logger)
	return g
}

// StillFormat 返回生效的静图缩略图输出格式（含扩展名/编码参数的消费出口）：
// httpapi 分发侧用它拼 ThumbPath 的扩展名与 ServeContent 的文件名。
func (g *Generator) StillFormat() StillFormat { return g.stillFormat }

// handle 标记工作池任务为后台；前台 Ensure/EnsureDetached 使用同一生成体并可提升它。
func (g *Generator) handle(ctx context.Context, t Task) error {
	defer t.reservation.release()
	if t.version != "" {
		return g.ensureVersion(ctx, t.AssetID, t.SourcePath, t.Kind, t.Sizes, t.version, false)
	}
	return g.ensure(ctx, t.AssetID, t.SourcePath, t.Kind, t.Sizes, false)
}

// Submit 非阻塞提交一个生成任务（ErrQueueFull/ErrPoolClosed 语义见 WorkerPool）。
// 后台回填经此入口；前台直接走 Ensure/EnsureDetached，才能标记/提升优先级。
func (g *Generator) Submit(t Task) error {
	g.initLifecycle()
	version, err := g.sourceVersion(t.SourcePath)
	if err != nil {
		sysmon.Default.IncThumbWork("failed")
		return fmt.Errorf("%w: %w", ErrSourceUnavailable, err)
	}
	keys := g.missingRepresentations(t.AssetID, version, t.Sizes)
	if len(keys) == 0 {
		return nil
	}
	lease, err := g.budget.acquire(context.Background(), keys, false)
	if err != nil {
		return err
	}
	t.reservation = lease
	t.version = version
	// 调用方可复用自己的切片，排队数据必须自有。
	t.Sizes = append([]Size(nil), t.Sizes...)
	if err := g.pool.Submit(t); err != nil {
		lease.release()
		return err
	}
	return nil
}

// Close 停止准入并取消全部服务级生成，至多等待一个 frameTimeout；重复调用安全。
func (g *Generator) Close() {
	ctx, cancel := context.WithTimeout(context.Background(), frameTimeout)
	defer cancel()
	if err := g.CloseContext(ctx); err != nil {
		g.logger.Error("缩略图关闭未在期限内完成", "err", err)
	}
}

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
	return g.ensure(ctx, assetID, srcPath, kind, sizes, true)
}

func (g *Generator) ensure(ctx context.Context, assetID, srcPath string, kind Kind, sizes []Size, foreground bool) error {
	version, err := g.sourceVersion(srcPath)
	if err != nil {
		return err
	}
	return g.ensureVersion(ctx, assetID, srcPath, kind, sizes, version, foreground)
}

func (g *Generator) ensureVersion(ctx context.Context, assetID, srcPath string, kind Kind, sizes []Size, version string, foreground bool) error {
	for _, size := range sizes {
		if err := g.ensureOne(ctx, assetID, srcPath, kind, size, version, foreground); err != nil {
			return fmt.Errorf("生成 %s 的 %d 缩略图: %w", assetID, int(size), err)
		}
	}
	return nil
}

// thumbDst 返回某资产某尺寸的缩略图落盘路径（ensureOne 生成侧与懒生成
// 快路径的存在性检查共用，EnsureDetached）。网格默认档（md）的像素由
// LongSide 配置决定（<=0 已在构造时回落 SizeGrid）：size 常量只是档位占位，
// 生成与缓存键都用换算后的像素，保证"调整配置像素 = 新键 = 新文件"的缓存
// 语义成立（cachekey.go 注释）。
func (g *Generator) thumbDst(assetID string, size Size, versions ...string) string {
	if size == SizeGrid {
		size = Size(g.longSide)
	}
	if len(versions) > 0 {
		return g.VersionedPath(assetID, size, versions[0])
	}
	return ThumbPath(g.dataDir, CacheKey(assetID, size), g.stillFormat.Ext())
}

func (g *Generator) ensureOne(ctx context.Context, assetID, srcPath string, kind Kind, size Size, version string, foreground bool) error {
	// 档位归一化必须在取 dst 与缩放前完成：md 档（SizeGrid）的像素由
	// LongSide 配置决定（构造时 <=0 已回落 SizeGrid）。此前只有 thumbDst
	// 的缓存键做了换算、scaleStill 仍用 512 占位值——long_side≠512 时
	// 预热路径产出"512px 内容配换算后像素的键"，配置静默失效（2026-09-20
	// 全库审查 F1 根修：懒生成路径由调用方换算传入故从未显现，默认配置
	// 下两值相等也不显现）。
	if size == SizeGrid {
		size = Size(g.longSide)
	}
	dst := g.thumbDst(assetID, size, version)
	g.initLifecycle()
	if _, err := os.Stat(dst); err == nil {
		return nil
	}
	admission, cancel := context.WithTimeout(ctx, frameTimeout)
	defer cancel()
	lease, err := g.budget.acquire(admission, []string{dst}, true)
	if err != nil {
		return err
	}
	return g.generating.doOwned(dst, admission, foreground, lease, func(priority <-chan struct{}) error {
		// 验证必须在续生体内：浏览取消后也不能留下生成期间已失效的缓存。
		if err := g.generateOne(g.life, srcPath, kind, size, dst, priority); err != nil {
			if !errors.Is(err, context.Canceled) {
				sysmon.Default.IncThumbWork("failed")
			}
			return err
		}
		if current, err := g.sourceVersion(srcPath); err != nil || current != version {
			if rmErr := os.Remove(dst); rmErr != nil && !errors.Is(rmErr, fs.ErrNotExist) {
				g.logger.Warn("清理生成期间失效的缩略图失败", "err", rmErr)
			}
			return errors.New("生成期间原件发生变化，请重试")
		}
		return nil
	})
}

func cacheExists(dst string) (bool, error) {
	if _, err := os.Stat(dst); err == nil {
		return true, nil
	} else if !errors.Is(err, fs.ErrNotExist) {
		// 权限等真实错误必须暴露，静默跳过会让"以为生成了其实没有"难以排查。
		return false, fmt.Errorf("检查缓存文件 %s: %w", dst, err)
	}
	return false, nil
}

func (g *Generator) generateOne(ctx context.Context, srcPath string, kind Kind, size Size, dst string, priority <-chan struct{}) error {
	if exists, err := cacheExists(dst); err != nil || exists {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return fmt.Errorf("创建缓存目录 %s: %w", filepath.Dir(dst), err)
	}

	// 并发闸（2026-09-13 用户真机 BUG-A 侧修，见 genSlots 注释）：懒生成端点对每个
	// 未命中请求同步起 ffmpeg，此前无上限——真库首屏几十请求 = ffmpeg 风暴，拖慢
	// original 流（客户端 10s 读超时被掐断的诱因）。这里排队等待而非拒绝（响应慢
	// 但不失败）；排队期间 ctx 取消（客户端断开/超时）立即中断。排队只受上游 ctx
	// 约束、不占 frameTimeout——后者衡量的是拿到闸位后的生成全流程。缓存命中路径
	// 不进闸（不消耗 ffmpeg 预算）；release 经 defer 保证成功/失败/panic 都归还。
	sysmon.Default.AddThumbWork("waiting", 1)
	acquired := g.slots.acquirePriority(ctx, priority)
	sysmon.Default.AddThumbWork("waiting", -1)
	if !acquired {
		return fmt.Errorf("排队等待缩略图生成闸位时上游已取消: %w", ctx.Err())
	}
	defer g.slots.release()
	sysmon.Default.AddThumbWork("running", 1)
	defer sysmon.Default.AddThumbWork("running", -1)
	// 等待资源闸期间，缓存可能由其他 Generator/外部回填写好，不能重复抽帧。
	if exists, err := cacheExists(dst); err != nil || exists {
		return err
	}

	ctx, cancel := context.WithTimeout(ctx, frameTimeout)
	defer cancel()

	switch kind {
	case KindImage:
		// 原图永不转码：缩放输出是独立副本，源文件只读。
		// EXIF 方向显式转正（DOMAIN_RULES §11「静图方向」）：只有这里面对
		// 用户原图（JPEG 竖拍照主战场）；下方动图/视频的中转帧是 ffmpeg
		// 自产 PNG，天然无 EXIF，恒按 orientationNormal 跳过滤镜段。
		return g.scaleStill(ctx, srcPath, jpegOrientationOf(srcPath), int(size), dst)
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
			if err := g.firstFrame(ctx, srcPath, frame); err != nil {
				return err
			}
		} else {
			pick, err := g.pickFrameTime(ctx, srcPath)
			if err != nil {
				return err
			}
			if pick.AttachedPic {
				// 内嵌封面优先（DOMAIN_RULES §11）：封面是发行方/作者选定的
				// 画面，无需黑白纠偏，直接按探测出的流索引抽取。
				if err := g.extractAttachedPic(ctx, srcPath, pick.AttachedPicStream, frame); err != nil {
					return err
				}
			} else if err := g.extractFrame(ctx, srcPath, pick.At, frame); err != nil {
				return err
			}
		}
		return g.scaleStill(ctx, frame, orientationNormal, int(size), dst)
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

// HasThumbnail 缩略图是否已落盘（预热回填/进度统计的存在性判定出口；
// md 档走 LongSide 映射，与生成侧 thumbDst 同一换算，保证判定与落盘同键）。
func (g *Generator) HasThumbnail(assetID string, size Size, srcPaths ...string) bool {
	var versions []string
	if len(srcPaths) > 0 {
		version, err := g.sourceVersion(srcPaths[0])
		if err != nil {
			return false
		}
		versions = []string{version}
	}
	_, err := os.Stat(g.thumbDst(assetID, size, versions...))
	return err == nil
}

// HasStoredThumbnail 仅检查扫描元数据所指向的表示，用于低开销回填/覆盖统计。
// 不用作媒体出口鉴权或版本校验；真实生成仍从原件重新取得版本。
func (g *Generator) HasStoredThumbnail(assetID string, size Size, sizeBytes int64, mtime string) bool {
	_, err := os.Stat(g.thumbDst(assetID, size, g.RepresentationVersion(sizeBytes, mtime)))
	return err == nil
}

// CountOnDisk 统计缩略图缓存目录已落盘文件数（进度页分母口径见 httpapi
// GetApiV1ThumbnailsProgress；目录不存在=零张，属正常态非错误）。
func (g *Generator) CountOnDisk() (int64, error) {
	var n int64
	root := filepath.Join(g.dataDir, "thumbs")
	err := filepath.WalkDir(root, func(_ string, d fs.DirEntry, werr error) error {
		if werr != nil {
			return nil // 子目录失联跳过（fs.ErrNotExist 常态），进度统计不受损
		}
		if !d.IsDir() {
			n++
		}
		return nil
	})
	if err != nil {
		return 0, err
	}
	return n, nil
}

// sourceVersion 生产路径统一取扫描器同款毫秒精度版本。
func (g *Generator) sourceVersion(srcPath string) (string, error) {
	fi, err := os.Stat(srcPath)
	if err != nil {
		return "", err
	}
	return g.RepresentationVersion(fi.Size(), store.FormatTimestamp(fi.ModTime())), nil
}
