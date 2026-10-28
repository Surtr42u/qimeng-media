package mediawrite

import (
	"context"
	"errors"
	"io"
	"math"
	"os"
	"path"
	"path/filepath"

	"github.com/google/uuid"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/sysmon"
)

// 临时件与最终件同目录；扩展名在扫描白名单之外，中断不会误入库。
const UploadTmpPrefix = ".qm-upload-"
const uploadTmpSuffix = ".tmp"
const dirPerm = 0o755

func (s *Service) receiveUploadToRoot(ctx context.Context, root *os.Root, dir, filename string, body io.Reader, length, maxBytes int64) (string, error) {
	limit := maxBytes
	if limit < math.MaxInt64 {
		limit++
	} // 多读至多 1 字节判超限，不整读请求体。
	limited := &io.LimitedReader{R: body, N: limit}
	head := make([]byte, filing.RecommendedHeadBytes)
	n, err := io.ReadFull(limited, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return "", fail(ReadStreamFailure, "读取上传流", err)
	}
	head = head[:n]
	declared := maxBytes
	if length >= 0 && length < declared {
		declared = length
	}
	if err := filing.ValidateUpload(filename, declared, maxBytes, head); err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return "", err
	}
	rel := filepath.FromSlash(path.Join(dir, UploadTmpPrefix+uuid.NewString()+uploadTmpSuffix))
	target := filepath.Join(root.Name(), rel)
	file, err := root.OpenFile(rel, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return "", fail(InternalFailure, "创建上传临时文件", err)
	}
	_, err = file.Write(head)
	if err == nil && n == filing.RecommendedHeadBytes {
		guard := &uploadSpaceWriter{destination: file, check: func() error { return s.checkUploadStorage(ctx, root.Name(), 0) }}
		_, err = io.Copy(guard, limited)
	}
	if err == nil && limit-limited.N > maxBytes {
		err = filing.ErrUploadTooLarge
	}
	if closeErr := file.Close(); closeErr != nil {
		err = errors.Join(err, closeErr)
	}
	if err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		s.cleanStaging(root, rel)()
		var failure *Failure
		if errors.As(err, &failure) && failure.Kind == StorageUnavailable {
			return "", err
		}
		return "", fail(ReadStreamFailure, "接收上传流", err)
	}
	return target, nil
}

// 去除单文件上限后，流式接收每8MiB复核磁盘安全余量，未知长度也不会一直写到磁盘耗尽。
const uploadSpaceCheckEvery = 8 << 20

type uploadSpaceWriter struct {
	destination io.Writer
	check       func() error
	unchecked   int64
}

func (w *uploadSpaceWriter) Write(p []byte) (int, error) {
	if w.unchecked == 0 || w.unchecked >= uploadSpaceCheckEvery {
		if err := w.check(); err != nil {
			return 0, err
		}
		w.unchecked = 0
	}
	n, err := w.destination.Write(p)
	w.unchecked += int64(n)
	return n, err
}

func (s *Service) cleanStaging(root *os.Root, rel string) func() {
	return func() {
		if err := root.Remove(rel); err != nil && !os.IsNotExist(err) {
			s.logger.Warn("清理上传预置文件失败", "path", rel, "err", err)
		}
	}
}
