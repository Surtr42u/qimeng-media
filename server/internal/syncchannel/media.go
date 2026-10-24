package syncchannel

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/localsync"
	"qimeng-media/server/internal/mediawrite"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
)

// lsValidateErrText filing 四道校验哨兵 → 状态面板中文文案（与上传端点的
// 协议错误码映射同源不同形：后台通道无响应码，只给人读文案）。
func lsValidateErrText(err error) string {
	switch {
	case errors.Is(err, filing.ErrUploadExtension):
		return "扩展名不在白名单"
	case errors.Is(err, filing.ErrUploadMimeMismatch):
		return "文件内容与扩展名不符（魔数嗅探失败）"
	case errors.Is(err, filing.ErrUploadTooLarge):
		return "文件超过大小上限"
	case errors.Is(err, filing.ErrUploadFilename):
		return "文件名不合法"
	default:
		return "校验失败: " + err.Error()
	}
}

// processLocalSyncMedia 媒体条目入库：与直传完全一致的校验（读头魔数 +
// 四道 ValidateUpload）与入库管线（ingestPlacedUpload），差异只有两点——
// 字节搬运用 MoveFile（同步根与库根可能跨卷）且落位前后各做一次 size 复核。
// 失败一律 lsMarkFailed（文件原地保留下轮重试）；落位后复核不符绝不删除
// 库位文件（那是全项目唯一副本），处置见 lsPlaceMediaInLibrary。
func (s *Service) processLocalSyncMedia(ctx context.Context, entry localsync.Entry, lib db.Library, folderName string, policy mediawrite.UploadPolicy) {
	kind := string(localsync.KindMedia)
	src := filepath.Join(s.cfg.LocalSync.Root, filepath.FromSlash(entry.RelPath))
	baseName := path.Base(entry.RelPath)
	head, err := mediawrite.ReadUploadHead(src)
	if err != nil {
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "读取文件头失败: "+err.Error(), entry.Size)
		return
	}
	if verr := filing.ValidateUpload(baseName, entry.Size, policy.MaxBytes, head); verr != nil {
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, lsValidateErrText(verr), entry.Size)
		return
	}
	mediaType, ok := scanner.ClassifyMedia(baseName)
	if !ok {
		// 防御分支：ValidateUpload 第①道已过白名单，理论不可达。
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "扩展名不在白名单", entry.Size)
		return
	}
	// 目标子路径 = 条目相对路径去掉首段（库文件夹），过红线 #1 规范化。
	subRel, err := filing.NormalizeRelPath(strings.TrimPrefix(entry.RelPath, folderName+string(localSyncRelSeparator)))
	if err != nil {
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "子路径非法: "+err.Error(), entry.Size)
		return
	}
	targetRel, targetAbs, place, perr := s.lsPlaceMediaInLibrary(lib, subRel, src, entry.Size)
	if perr != nil {
		switch {
		case errors.Is(perr, mediawrite.ErrTargetEscape):
			s.lsMarkFailed(entry.RelPath, kind, lib.Name, "目标路径不合法", entry.Size)
		default:
			s.lsMarkFailed(entry.RelPath, kind, lib.Name, "移动文件入库失败: "+perr.Error(), entry.Size)
		}
		return
	}
	switch place {
	case lsPlaceSrcChanged:
		// 预复核不符：未搬动，源文件原地保留，下轮重新观测后再处理。
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "文件在处理间隙发生变化，本轮跳过", entry.Size)
		return
	case lsPlaceMovedBack:
		// 落位后复核不符：已移回同步源，源文件原地，下轮重新观测后再处理。
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "文件在搬移过程中发生变化，已移回同步源", entry.Size)
		return
	case lsPlaceKeptInLibrary:
		// 落位后复核不符且移回失败：库位文件是合法媒体件，删除即销毁唯一
		// 副本——保留交扫描器注册（复核不符的 Warn 已在复核内记录）。
		s.lsMarkSynced(entry.RelPath, kind, lib.Name, "落位后大小不一致且移回失败，保留库位待扫描器注册", entry.Size)
		return
	}
	if _, ierr := s.writes.IngestPlacedUpload(ctx, lib.ID, targetAbs, targetRel, path.Base(targetRel), mediaType); ierr != nil {
		// 文件已在库位：注册失败不回滚 move（回滚丢库归属），交扫描器兜底
		//（与上传通道「DB 失败但文件已落盘」同语义，重试 move 会产生重复件）。
		s.logger.Warn("同步文件已落库位但注册失败，等待扫描器兜底注册",
			"rel", entry.RelPath, "library", lib.Name, "err", ierr)
		s.lsMarkSynced(entry.RelPath, kind, lib.Name, "已入库位，注册将由扫描器补齐", entry.Size)
		return
	}
	s.lsMarkSynced(entry.RelPath, kind, lib.Name, "", entry.Size)
	s.logger.Info("本机同步媒体入库完成", "rel", entry.RelPath, "library", lib.Name, "size", entry.Size)
}

// lsPlaceResult 落位链路（预复核 → 搬移 → 落位后复核）的处置结果：err
// 之外的软结果，决定条目最终落 failed（源还在同步根）还是 synced（文件已
// 成库内合法件）。
type lsPlaceResult int

const (
	// lsPlaceProceed 预复核与落位后复核全部通过，可继续入库。
	lsPlaceProceed lsPlaceResult = iota
	// lsPlaceSrcChanged 落位前预复核不符：未搬动，源文件原地保留。
	lsPlaceSrcChanged
	// lsPlaceMovedBack 落位后复核不符：已移回同步源，源文件原地保留。
	lsPlaceMovedBack
	// lsPlaceKeptInLibrary 落位后复核不符且移回失败：保留库位交扫描器注册。
	lsPlaceKeptInLibrary
)

// lsPlaceMediaInLibrary 把同步源文件搬进库内目标路径（subRel 已过
// NormalizeRelPath）：建子目录（锁外，幂等）→ WithLibraryGate 内「冲突
// 解析 → 落位前预复核 → MoveFile → 落位后 size 复核」关键段（与上传通道
// 同构，持锁时长与文件大小无关）。返回库内相对路径、绝对路径与处置结果；
// err 非空 = 硬失败（路径逃逸/搬移失败），此时文件未被搬动或搬移失败未落位。
func (s *Service) lsPlaceMediaInLibrary(lib db.Library, subRel, src string, expectSize int64) (string, string, lsPlaceResult, error) {
	dirRel := path.Dir(subRel) // "." = 库根
	targetDir := filepath.Join(lib.RootPath, filepath.FromSlash(dirRel))
	if !filing.PathWithinRoot(lib.RootPath, targetDir) {
		return "", "", lsPlaceProceed, mediawrite.ErrTargetEscape
	}
	root, err := os.OpenRoot(lib.RootPath)
	if err != nil {
		return "", "", lsPlaceProceed, err
	}
	defer filing.CloseRoot(root)
	if err := root.MkdirAll(filepath.FromSlash(dirRel), dirPerm); err != nil {
		return "", "", lsPlaceProceed, err
	}

	var targetRel, targetAbs string
	place := lsPlaceProceed
	gerr := filing.WithLibraryGate(lib.ID, func() error {
		targetDir := lib.RootPath
		if dirRel != "." {
			targetDir = filepath.Join(lib.RootPath, filepath.FromSlash(dirRel))
		}
		if !filing.PathWithinRoot(lib.RootPath, targetDir) {
			return mediawrite.ErrTargetEscape
		}
		exists := func(n string) bool {
			_, serr := os.Stat(filepath.Join(targetDir, n))
			return serr == nil
		}
		finalName := filing.ResolveConflict(path.Base(subRel), exists)
		targetRel = path.Join(dirRel, finalName)
		targetAbs = filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
		if !filing.PathWithinRoot(lib.RootPath, targetAbs) {
			return mediawrite.ErrTargetEscape
		}
		// 落位前预复核（对齐分片通道 placeSealedInLibrary 的 Seal 后落位前
		// 复核语义）：扫描观测/读头/校验与搬移之间源文件可能被改，size 不符
		// 或已消失就不搬动——此刻源文件还在同步根，条目按 failed 留待下轮
		// 重新观测。stat 与 MoveFile 相邻执行把竞态窗压到两条系统调用之间。
		st, serr := os.Stat(src)
		if serr != nil {
			s.logger.Warn("落位前预复核同步源失败，本轮跳过", "src", src, "err", serr.Error())
			place = lsPlaceSrcChanged
			return nil
		}
		if st.Size() != expectSize {
			place = lsPlaceSrcChanged
			return nil
		}
		if err := s.moveFile(src, targetAbs); err != nil {
			return err
		}
		// 落位后 size 复核（stat 与 MoveFile 两条系统调用间隙的极小竞态）：
		// 不符时绝不删除已落位文件——move 成功后源已不在，库位件是全项目
		// 唯一副本；移回同步源或保留库位，处置见 lsReconcilePlacedSize。
		// 忽略的第二返回值（不符原因 error）在该函数的非 proceed 分支内
		// 已 s.logger.Warn 记录（携 reason/moveBackErr），此处只消费处置
		// 枚举，原因在日志里不丢失。
		rplace, _ := s.lsReconcilePlacedSize(src, targetAbs, expectSize)
		switch rplace {
		case lsPlaceMovedBack:
			place = lsPlaceMovedBack
		case lsPlaceKeptInLibrary:
			place = lsPlaceKeptInLibrary
		}
		return nil
	})
	if gerr != nil {
		return targetRel, targetAbs, lsPlaceProceed, gerr
	}
	return targetRel, targetAbs, place, nil
}

// lsReconcilePlacedSize 落位后 size 复核与处置（可独立调用，函数级测试
// 锁行为）：目标与声明不符时优先 MoveFile 移回同步源（条目下轮重新观测），
// 移回失败则保留库位（扫描器会注册该合法媒体文件）。任何分支禁止删除——
// move 成功后库位件是全项目唯一副本，删除即销毁用户数据。返回处置结果；
// 结果非 lsPlaceProceed 时第二返回值携带不符原因（已记日志，供测试/调用方
// 诊断）。
func (s *Service) lsReconcilePlacedSize(srcAbs, targetAbs string, expect int64) (lsPlaceResult, error) {
	st, serr := os.Stat(targetAbs)
	if serr == nil && st.Size() == expect {
		return lsPlaceProceed, nil
	}
	var reason error
	if serr != nil {
		reason = fmt.Errorf("复核落位文件失败: %w", serr)
	} else {
		reason = fmt.Errorf("落位文件实际 %d 字节，与同步源观测 %d 字节不符", st.Size(), expect)
	}
	if mbErr := s.moveFile(targetAbs, srcAbs); mbErr != nil {
		s.logger.Warn("落位后大小不一致且移回同步源失败，保留库位待扫描器注册",
			"target", targetAbs, "src", srcAbs, "reason", reason.Error(), "moveBackErr", mbErr.Error())
		return lsPlaceKeptInLibrary, reason
	}
	s.logger.Warn("落位后大小不一致，已移回同步源", "target", targetAbs, "reason", reason.Error())
	return lsPlaceMovedBack, reason
}
