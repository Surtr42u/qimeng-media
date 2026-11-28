package mediawrite

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"math"
	"os"
	"path"
	"path/filepath"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store/db"
)

// 本机/局域网直接入库（2026-11-28 用户拍板）。
//
// 背景：媒体库与源文件同机同分区时，走 HTTP 流式上传要 `~2 分钟/3GB`（收流 +
// 边算摘要），实际只需 te 同盘 rename（毫秒级）。本通道把「移动文件 + 建索引」
// 下沉到服务端进程内完成，上传继续服务外网/手机/跨设备场景。
//
// 三条硬约束（不放宽）：
//  1. 源必须落在配置的读根内（`ingest_local.read_roots`），目标必须在库根内，
//     判定统一走 filing.PathWithinRoot（拒绝 `..` 与符号链接逃逸）；
//  2. 绝不覆盖已存在的目标：同内容返回 already_present，不同内容 409；
//  3. 本通道不删除任何库内资产——删除仍只走回收站端点（move=true 只删除
//     **已校验并入索引**的源文件）。
//
// 移动语义：同分区直接 rename（原子、零拷贝）；跨分区复制 → 长度+SHA256 复核
// → 建索引成功后才删源；move=false 只复制、源保留。任何失败都不丢源。
const (
	// IngestLocalStatusIngested 本次真正落位并入库。
	IngestLocalStatusIngested = "ingested"
	// IngestLocalStatusPresent 目标已存在且 size+SHA256 一致（源未动）。
	IngestLocalStatusPresent = "already_present"
)

var (
	// ErrIngestLocalDisabled 服务端未配置本机入库读根（默认关闭）。
	ErrIngestLocalDisabled = errors.New("mediawrite: 未配置本机直接入库读根")
	// ErrIngestSourceOutsideRoots 源路径不在任何授权读根内。
	ErrIngestSourceOutsideRoots = errors.New("mediawrite: 源路径不在授权读根内")
	// ErrIngestSourceMissing 源文件不存在。
	ErrIngestSourceMissing = errors.New("mediawrite: 源文件不存在")
	// ErrIngestSourceNotRegular 源不是普通文件（目录/符号链接/设备）。
	ErrIngestSourceNotRegular = errors.New("mediawrite: 源不是普通文件")
	// ErrIngestTargetExists 目标已存在且与源内容不同（不覆盖）。
	ErrIngestTargetExists = errors.New("mediawrite: 目标已存在且内容不同")
)

// IngestLocalRequest 本机直接入库请求。
type IngestLocalRequest struct {
	LibraryID  string
	SourcePath string // 源绝对路径（必须落在授权读根内）
	FileName   string // 库内文件名（走既有命名/扩展名校验）
	Directory  string // 库内目标目录（相对路径，空 = 库根）
	Move       bool   // true=移动（跨分区校验通过后才删源）；false=复制保留源
}

// IngestLocalResult 本机直接入库结果。
type IngestLocalResult struct {
	Status     string
	AssetID    string
	LibraryID  string
	RelPath    string
	FileName   string
	MediaType  string
	SizeBytes  int64
	SHA256     string // 纯同卷移动快路径不计算（空）；复制/幂等比对路径恒有值
	SourceKept bool
}

// IngestLocal 本机/局域网直接入库：校验源与目标 → 移动或复制（跨分区带摘要复核）
// → 复用上传的建索引管线（DB 行/探测/富化/library.changed/文件数指标）。
func (s *Service) IngestLocal(ctx context.Context, req IngestLocalRequest) (IngestLocalResult, error) {
	roots := s.cfg.IngestLocal.ReadRoots
	if len(roots) == 0 {
		return IngestLocalResult{}, ErrIngestLocalDisabled
	}
	name, mediaType, err := uploadFilename(req.FileName)
	if err != nil {
		return IngestLocalResult{}, err
	}
	dir, err := normalizeUploadDir(req.Directory)
	if err != nil {
		return IngestLocalResult{}, err
	}
	if req.LibraryID == "" {
		return IngestLocalResult{}, fail(InvalidLibrary, "查询库", nil)
	}
	lib, err := s.q.GetLibrary(ctx, req.LibraryID)
	if errors.Is(err, sql.ErrNoRows) {
		return IngestLocalResult{}, fail(InvalidLibrary, "查询库", err)
	}
	if err != nil {
		return IngestLocalResult{}, fail(InternalFailure, "查询库", err)
	}

	src := filepath.Clean(req.SourcePath)
	if !filepath.IsAbs(src) || !withinAnyRoot(roots, src) {
		return IngestLocalResult{}, ErrIngestSourceOutsideRoots
	}
	srcInfo, err := os.Lstat(src)
	if errors.Is(err, os.ErrNotExist) {
		return IngestLocalResult{}, ErrIngestSourceMissing
	}
	if err != nil {
		return IngestLocalResult{}, fail(InternalFailure, "读取源文件信息", err)
	}
	if srcInfo.Mode()&os.ModeSymlink != 0 || !srcInfo.Mode().IsRegular() {
		return IngestLocalResult{}, ErrIngestSourceNotRegular
	}
	// 内容嗅探与扩展名校验与上传同源（filing.ValidateUpload 四道校验里的②④）：
	// 本机通道也不能把 exe 改名 .png 索引进媒体面。大小上限交给存储余量检查
	// （上传业务大小上限已退役），此处只复用扩展名+魔数+文件名清洗三道。
	if err := sniffIngestSource(name, src, srcInfo.Size()); err != nil {
		return IngestLocalResult{}, err
	}

	rel := name
	if dir != "" {
		rel = path.Join(dir, name)
	}
	target := filepath.Join(lib.RootPath, filepath.FromSlash(rel))
	if !filing.PathWithinRoot(lib.RootPath, target) {
		return IngestLocalResult{}, ErrTargetEscape
	}

	// 目标已存在：同内容幂等返回，不同内容拒绝覆盖（绝不动源）。
	if targetInfo, statErr := os.Lstat(target); statErr == nil {
		return s.ingestLocalExisting(ctx, lib, src, target, rel, name, mediaType, srcInfo.Size(), targetInfo)
	} else if !errors.Is(statErr, os.ErrNotExist) {
		return IngestLocalResult{}, fail(InternalFailure, "检查目标路径", statErr)
	}

	if dir != "" {
		root, err := os.OpenRoot(lib.RootPath)
		if err != nil {
			return IngestLocalResult{}, fail(InternalFailure, "打开媒体库根", err)
		}
		defer filing.CloseRoot(root)
		if err := root.MkdirAll(filepath.FromSlash(dir), dirPerm); err != nil {
			return IngestLocalResult{}, fail(InternalFailure, "创建目标目录", err)
		}
	}

	moved := false
	if req.Move {
		// 同分区 rename 是原子零拷贝；跨分区（EXDEV）走下面的复制+复核分支。
		if err := s.ingestRename(src, target); err == nil {
			moved = true
		} else if !filing.IsCrossDeviceError(err) {
			return IngestLocalResult{}, fail(InternalFailure, "移动源文件", err)
		}
	}

	var digest filing.Digest
	if !moved {
		// 复制前先确认目标所在卷余量（rename 路径不新增占用，无需检查）。
		if err := s.checkUploadStorage(ctx, lib.RootPath, srcInfo.Size()); err != nil {
			return IngestLocalResult{}, err
		}
		digest, err = filing.CopyFileVerified(src, target)
		if err != nil {
			if errors.Is(err, filing.ErrMoveTargetExists) {
				return IngestLocalResult{}, ErrIngestTargetExists
			}
			return IngestLocalResult{}, fail(InternalFailure, "复制源文件", err)
		}
	}

	asset, err := s.IngestPlacedLocal(ctx, lib.ID, target, rel, name, mediaType)
	if err != nil {
		// 索引失败不删源：同卷 rename 的源已变成库内文件（数据仍在库内，重扫可
		// 补索引）；复制路径的源保持原位，重试零损失。
		return IngestLocalResult{}, fail(InternalFailure, "入库索引", err)
	}
	sourceKept := !req.Move
	if req.Move && !moved {
		// 跨分区：目标已落位、摘要复核通过且已入库，才可以删源。
		if err := os.Remove(src); err != nil { // #nosec G304 -- src 已过读根与普通文件校验
			s.logger.Warn("本机入库删源失败（目标已入库，源保留）", "src", src, "err", err)
			sourceKept = true
		}
	}
	return IngestLocalResult{
		Status:     IngestLocalStatusIngested,
		AssetID:    asset.AssetID,
		LibraryID:  lib.ID,
		RelPath:    rel,
		FileName:   name,
		MediaType:  mediaType,
		SizeBytes:  asset.SizeBytes,
		SHA256:     digest.SHA256,
		SourceKept: sourceKept,
	}, nil
}

// ingestLocalExisting 目标已存在时的幂等判定：size 相等且 SHA-256 一致才算
// already_present（源不动）；否则 409。已存在但缺 DB 行时补建索引（自愈）。
func (s *Service) ingestLocalExisting(
	ctx context.Context,
	lib db.Library,
	src, target, rel, name, mediaType string,
	srcSize int64,
	targetInfo os.FileInfo,
) (IngestLocalResult, error) {
	if !targetInfo.Mode().IsRegular() || targetInfo.Size() != srcSize {
		return IngestLocalResult{}, ErrIngestTargetExists
	}
	srcDigest, err := filing.FileSHA256(src)
	if err != nil {
		return IngestLocalResult{}, fail(InternalFailure, "计算源摘要", err)
	}
	dstDigest, err := filing.FileSHA256(target)
	if err != nil {
		return IngestLocalResult{}, fail(InternalFailure, "计算目标摘要", err)
	}
	if srcDigest != dstDigest {
		return IngestLocalResult{}, ErrIngestTargetExists
	}
	asset, err := s.IngestPlacedLocal(ctx, lib.ID, target, rel, name, mediaType)
	if err != nil {
		return IngestLocalResult{}, fail(InternalFailure, "入库索引", err)
	}
	return IngestLocalResult{
		Status:     IngestLocalStatusPresent,
		AssetID:    asset.AssetID,
		LibraryID:  lib.ID,
		RelPath:    rel,
		FileName:   name,
		MediaType:  mediaType,
		SizeBytes:  asset.SizeBytes,
		SHA256:     dstDigest.SHA256,
		SourceKept: true,
	}, nil
}

// sniffIngestSource 读文件头做扩展名↔魔数比对（不信任扩展名）。空文件/读头
// 失败按上传同口径拒绝（ErrUploadMimeMismatch / 底层错误）。
func sniffIngestSource(name, src string, size int64) error {
	f, err := os.Open(src) // #nosec G304 -- 源已过读根与普通文件校验
	if err != nil {
		return err
	}
	defer func() {
		if cerr := f.Close(); cerr != nil {
			_ = cerr
		}
	}()
	head := make([]byte, filing.RecommendedHeadBytes)
	n, err := io.ReadFull(f, head)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
		return err
	}
	return filing.ValidateUpload(name, size, math.MaxInt64, head[:n])
}

// withinAnyRoot 判定绝对路径是否落在任一授权读根内（逐根用与媒体直链同源的
// 根内校验：`..`、绝对路径重写与符号链接逃逸都拒绝）。
func withinAnyRoot(roots []string, abs string) bool {
	for _, root := range roots {
		cleaned := filepath.Clean(root)
		if cleaned == "" || cleaned == "." {
			continue
		}
		if filing.PathWithinRoot(cleaned, abs) {
			return true
		}
	}
	return false
}
