package thumbnail

import (
	"context"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"os"
	"path/filepath"
	"time"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store"
)

const (
	imageHeaderMaxBytes  = 1 << 20 // 限制不可信图片头读取量，不分配完整像素。
	imageMetadataTimeout = 5 * time.Second
)

// ReadImageDimensions 供单个后台回填循环使用；常见格式只读图片头，其他格式复用受限ffprobe。
// 返回显示方向宽高；源文件必须仍与扫描版本一致，绝不以缩略图尺寸冒充原件尺寸。
func (g *Generator) ReadImageDimensions(ctx context.Context, root, rel string, size int64, mtime string) (width, height int, retErr error) {
	ctx, cancel := context.WithTimeout(ctx, imageMetadataTimeout)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return 0, 0, err
	}
	src := filepath.Join(root, filepath.FromSlash(rel))
	if !filing.PathWithinRoot(root, src) {
		return 0, 0, filing.ErrPathEscape
	}
	r, err := os.OpenRoot(root)
	if err != nil {
		return 0, 0, err
	}
	defer func() { retErr = errors.Join(retErr, r.Close()) }()
	f, err := r.Open(filepath.FromSlash(rel))
	if err != nil {
		return 0, 0, err
	}
	defer func() { retErr = errors.Join(retErr, f.Close()) }()
	before, err := f.Stat()
	if err != nil {
		return 0, 0, err
	}
	if !matchesImageSource(before, size, mtime) {
		return 0, 0, ErrSourceUnavailable
	}
	config, format, err := image.DecodeConfig(io.LimitReader(imageHeaderReader{ctx, f}, imageHeaderMaxBytes))
	switch {
	case err == nil:
		width, height = config.Width, config.Height
		if format == "jpeg" {
			if _, err := f.Seek(0, io.SeekStart); err != nil {
				return 0, 0, err
			}
			if orientation := parseJPEGOrientation(imageHeaderReader{ctx, f}); orientation >= orientationTranspose && orientation <= orientationRot90CCW {
				width, height = height, width
			}
		}
	case errors.Is(err, image.ErrFormat):
		// 复用已有后台优先准入，不能在缩略图预算外额外堆叠外部进程。
		if !genSlots.acquirePriority(ctx, nil) {
			return 0, 0, ctx.Err()
		}
		probe, probeErr := g.ProbeVideo(ctx, src)
		genSlots.release()
		if probeErr != nil {
			return 0, 0, probeErr
		}
		if probe == nil {
			return 0, 0, errors.New("图片探测未返回尺寸")
		}
		width, height = probe.Width, probe.Height
	default:
		return 0, 0, fmt.Errorf("读取图片头: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return 0, 0, err
	}
	if width <= 0 || height <= 0 {
		return 0, 0, errors.New("图片尺寸不合法")
	}
	after, err := r.Stat(filepath.FromSlash(rel))
	if err != nil {
		return 0, 0, err
	}
	if !os.SameFile(before, after) || !matchesImageSource(after, size, mtime) {
		return 0, 0, ErrSourceUnavailable
	}
	return width, height, nil
}

func matchesImageSource(info os.FileInfo, size int64, mtime string) bool {
	return info.Mode().IsRegular() && info.Size() == size && store.FormatTimestamp(info.ModTime()) == mtime
}

type imageHeaderReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r imageHeaderReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.reader.Read(p)
}
